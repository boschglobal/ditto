# Cleanup loose-index-scan rewrite — design

Date: 2026-07-13
Status: approved (user), ready for implementation planning
Scope basis: persistence-bench C1 flag (`internal/utils/persistence-bench/src/test/resources/bench/results/c1-pid-stream-page.md`)

## 1. Problem

The C1 benchmark (cleanup pid-stream page, `getNewestSnapshotsAbove`, batch 100, 250k corpus) flagged
PostgreSQL at **21× slower than MongoDB** (p95 887 ms vs 42 ms). The captured plan shows why:

```
Limit → Unique → Sort (external merge, Disk: 242112kB) → Seq Scan on things_snaps
Buffers: shared hit=181397
```

Two compounding causes:

1. **The sort order is not index-satisfiable.** The query orders by `pid, sn DESC, written_at DESC`
   (the `written_at` tiebreak is required — PK `(pid, sn, written_at)` permits same-`sn`
   re-snapshots), but the only ordered index is `things_snaps_pid_sn_desc_idx (pid, sn DESC)`.
   The planner falls back to Seq Scan + full sort of every row above the page's lower bound
   (~40k rows for a 100-row page). Mongo's `IXSCAN (pid:1, sn:-1, ts:-1)` examines exactly 100 keys.
2. **The JSONB payload rides through the sort.** `snapshot::text` is projected at the scan node, so
   every candidate row is detoasted pre-sort — 181k buffer hits and a 242 MB temp spill per page,
   99.7 % of it discarded by the LIMIT. A full C3 sweep repeats this ~2,500 times.

The same shape exists on the journal: `getLatestJournalEntries()` runs an *unbounded*
`SELECT DISTINCT ON (pid) … event::text … ORDER BY pid, sn DESC` — journal PK is `(pid, sn)` ASC,
so the entire journal including event payloads goes through one external sort.

## 2. Decision

**Approach A — two-phase LATERAL "loose index scan" rewrite** of both queries. Chosen over
(B) widening the snaps index and keeping `DISTINCT ON` — fast plan would remain a cost-based
gamble (C1 proves the planner mis-costs TOAST width) and does not transfer to the journal without
adding a write-amplifying index to the hottest table; and over (C) deferring only the payload
projection — removes the spill but keeps the O(rows-above-bound) per-page scan, degrading with scale.

The rewrite is fast **by construction**: phase 1 touches only index columns, phase 2 detoasts only
the page's winners. No new index is required; one existing index becomes redundant and is dropped.

## 3. Production query changes (`PostgresPersistenceOperations`)

### 3.1 `getNewestSnapshotsAbove` (the C1 query)

```sql
SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at
FROM (SELECT pid FROM <snaps>
      WHERE pid > $1
        AND ($2 = '' OR pid ~ $2)
        AND ($3 OR written_at < now() - $4::interval)
      GROUP BY pid ORDER BY pid LIMIT $5) p
CROSS JOIN LATERAL
     (SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM <snaps>
      WHERE pid = p.pid
        AND ($3 OR written_at < now() - $4::interval)
      ORDER BY sn DESC, written_at DESC LIMIT 1) s
ORDER BY p.pid
```

Bind parameters and their meaning are unchanged from today ($1 lower-bound pid, $2 POSIX-ERE pid
filter or '', $3 no-age-filter flag, $4 min-age interval, $5 batch size).

Semantic-parity requirements (each is an existing Mongo-parity property that MUST survive):

- **Pre-grouping filters.** Age and regex predicates exclude ROWS before pid selection. They appear
  in BOTH phases: phase 1 so a pid with no qualifying row consumes no page slot (identical to
  pre-`DISTINCT ON` filtering today), phase 2 so the winner is the newest *qualifying* row.
- **Tiebreak.** `ORDER BY sn DESC, written_at DESC LIMIT 1` picks the same winner as
  `DISTINCT ON (pid) … ORDER BY pid, sn DESC, written_at DESC`. Served by a backward PK scan.
- **DELETED filtering stays in Java** (`PostgresReadJournal.newestSnapshots`, post-page), and the
  pagination cursor still advances from the RAW page before that filter. No caller changes.
- **Single statement** → one MVCC snapshot; no cross-phase race.
- Expected plan: phase 1 = index-only scan on PK (covers pid, sn, written_at — index-only even with
  the age filter active) + Group + Limit, early-stopping after ~(rows-per-pid × batch) entries;
  phase 2 = batch-size backward PK point scans; exactly `LIMIT` payload detoasts per page.

### 3.2 `getLatestJournalEntries` (journal sibling)

```sql
SELECT p.pid, j.sn, j.seq, j.manifest, j.tags, j.event
FROM (SELECT DISTINCT pid FROM <journal>) p
CROSS JOIN LATERAL
     (SELECT sn, seq, manifest, tags, event::text AS event FROM <journal>
      WHERE pid = p.pid ORDER BY sn DESC LIMIT 1) j
ORDER BY p.pid
```

- Phase 1 = index-only PK scan + Unique: no detoast, no sort (today: external sort of the whole
  journal WITH event payloads). Phase 2 detoasts one event per pid — the actual output.
- Remains a `streamingQuerySource` statement: the nested-loop plan is pull-based, so the
  server-side portal (`fetchSize`) backpressure contract is unchanged.
- Output order stays `pid` ascending (explicit `ORDER BY p.pid`; satisfied without a sort node —
  phase 1 emits pids in index order and the nested loop preserves it).

## 4. Schema change: drop `<entity>_snaps_pid_sn_desc_idx`

Post-rewrite audit — every snaps statement is served by the PK `(pid, sn, written_at)`:

| Statement | Access path |
|---|---|
| snapshot load `pid=$1 … ORDER BY sn DESC, written_at DESC LIMIT 1` | backward PK scan |
| `MIN(sn) WHERE pid=$1` | forward PK scan, first entry |
| newest-before-timestamp (`written_at < $2 ORDER BY sn DESC LIMIT 1`) | backward PK scan, in-index filter |
| all deletes (by pid / pid+sn / pid+sn+written_at / sn-range / pid LIKE prefix) | PK |
| rewritten 3.1 phase 1 | index-only PK scan |
| rewritten 3.1 phase 2 | backward PK scan |

`(pid, sn DESC)` existed for the `DISTINCT ON` global order — which it never satisfied (missing
`written_at`), which is precisely why C1 seq-scanned. Change `PostgresSchema`:

- remove `createSnapsPidSnIndex` from the DDL list;
- add an idempotent `DROP INDEX IF EXISTS <entity>_snaps_pid_sn_desc_idx` statement so existing
  dev databases converge at boot (schema-manager DDL is ordered and idempotent; no
  `information_schema` table-contract change is involved — index statements are not part of the
  `TableContract` PK/column verification).

Net effect: one fewer index maintained on every snapshot write (write-path + storage win; W2 may
improve marginally).

## 5. Out of scope (recorded follow-ups)

- `getPidsWithTagOrderedByPriority` and `getPidsWithTag(considerOnlyLatest=true)` share the
  `DISTINCT ON` shape but sort only narrow rows (pid + tags — no TOAST amplification) and run on
  cold paths (cluster-start ping). Candidates for the same pattern later; not touched here.
- True skip-scan emulation (recursive-CTE pid jumping) — rejected: at realistic events-per-pid
  ratios a sequential index-only scan is cheaper than per-pid random descents, and PG 18's native
  skip scan will eventually make it moot (PG floor is 16).
- A `journal_seq`-based shortcut for 3.2 (join high-water-mark table to journal) — rejected:
  `highest_sn` equality with the surviving newest row is not invariant under cleanup edge cases;
  the LATERAL form is semantically identical to the current query by construction.

## 6. Verification

1. **Bench sync + re-run (the proof).** The bench copies the SQL shape
   (`persistence-bench … PersistenceShapes.java`, used by `CleanupShapeBench`/`SweepEngine`).
   Update it to the rewritten shape, then re-run the PG side of **C1, C3, C4, M1** at full scale
   (count=250000, same protocol) and refresh the results docs + README §5 flag lines. The fresh
   corpus load runs against the reduced schema, so `load-pg.md` is refreshed as a side effect;
   re-run **W2** as well to capture the index-drop effect on snapshot writes (expected neutral
   to slightly better — any regression is a stop-and-investigate signal).
   Success criteria:
   - C1 p95 ≤ Mongo's 42 ms (expected single-digit ms), captured plan shows no Sort node,
     no temp read/written, buffers proportional to page size not table size;
   - C3 sweep totals drop accordingly; C4/M1 re-captured for consistency of the curated doc.
2. **Correctness gates unchanged.** Existing unit + Testcontainers ITs must pass untouched —
   `SnapshotTieBreakIT` (same-sn re-snapshot winner), cleanup/read-journal ITs (age filter,
   DELETED handling, pagination/cursor semantics, pid regex). No test semantics may be loosened.
3. **No EXPLAIN-shape assertions in ITs** — tiny fixtures legitimately plan differently
   (seq scan + small sort is optimal at 20 rows); the bench re-run is the plan-shape evidence.

## 7. Edge cases

- Empty phase-1 page → zero rows → paginated stream terminates exactly as today.
- Page whose trailing pids are all DELETED → cursor still advances (raw-page seeding, unchanged).
- Visibility-map churn from cleanup deletes can force phase-1 heap fetches; bounded by entries
  scanned (~rows-per-pid per page slot), not table size. `autovacuum_vacuum_scale_factor = 0.01`
  is already set on the snaps table and keeps the VM fresh.
- `$2 = ''`/`$3` constant-folding: with r2dbc extended protocol these arrive as binds; the OR
  guards are plain filter quals on an index-only scan either way — they cannot force a heap path.
