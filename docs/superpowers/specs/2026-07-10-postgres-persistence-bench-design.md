# Core-persistence benchmark: PostgreSQL vs MongoDB (journal, snapshots, cleanup)

Status: DESIGN APPROVED 2026-07-10 (brainstormed with user; all section-level approvals given). Not implemented.
Companion precedent: the things-search Phase-0 bench
(`internal/utils/search-r2dbc/src/test/.../bench/`, results doc
`docs/superpowers/specs/2026-07-03-postgres-search-bench-results.md` on the search branch) — this design
deliberately reuses its methodology, naming conventions, evidence rules, and script/README structure.

## 1. Goal

Produce presentable, reproducible performance evidence for the **core persistence** of the three event-sourced
services (things/policies/connectivity) on the PostgreSQL backend, **side by side with MongoDB**, covering:

- recovery (latest snapshot + event replay),
- write path (event append, snapshot write, sustained churn),
- **the cleanup flow** — deletion of journal entries and snapshots older than each entity's latest snapshot,
  exactly as `PersistenceCleanupActor`/`Cleanup` performs it (pid streaming via `getNewestSnapshotsAbove`,
  credit-throttled batched range deletes),
- cleanup running concurrently with live write traffic.

The existing coverage on this branch is correctness-only (45 unit/IT classes in `persistence-r2dbc`,
end-to-end write→restart→recover runs). No latency/throughput numbers exist for either backend; this bench
closes that gap and complements the search bench so the whole Postgres story is benchmarked.

## 2. Decisions (user-approved 2026-07-10)

| # | Decision | Choice |
|---|---|---|
| D1 | Measurement level | **SQL/driver shapes only** — replicate the exact operations the plugins issue, over JDBC/Mongo-driver; no Pekko actor system in the harness. Cleanup's *flow* is reproduced as a sequenced driver loop, not via the real actor. |
| D2 | Corpus | **1M pids, Zipf-distributed journal depth** (median ~20 events, p99 ~2,000, cap 50,000) → ~60–80M journal rows; things-shaped payloads with size knobs; one corpus for all three services (their table DDL is identical per entity). |
| D3 | Comparison | **PG + MongoDB twin** — identical corpus loaded into both; every scenario runs against both; evidence is side-by-side. |
| D4 | Placement | **New test-only module `internal/utils/persistence-bench`** (deploy-skipped, registered in root `<modules>`; precedent: `ide-postgres-launcher`). Keeps the MongoDB driver out of `persistence-r2dbc` (the repo enforces Mongo/Postgres separation — `MongoModuleNoPostgresArchTest`) and keeps throwaway bench code out of merge-bound production modules. |

## 3. What is being benchmarked (ground truth in this repo)

PostgreSQL schema (per entity `<entity>`, from `PostgresSchema`):

- `<entity>_journal(pid TEXT COLLATE "C", sn BIGINT, seq BIGINT IDENTITY, manifest TEXT, tags TEXT[],
  event JSONB, written_at TIMESTAMPTZ, PK(pid, sn))` + GIN index on `tags`.
- `<entity>_journal_seq(pid PK, highest_sn BIGINT, deleted_to BIGINT)` — bookkeeping; append upserts it,
  `deleteMessagesTo` moves `deleted_to` only.
- `<entity>_snaps(pid, sn, snapshot JSONB, lifecycle TEXT, written_at, PK(pid, sn, written_at))` +
  `(pid, sn DESC)` index + partial index on `lifecycle='DELETED'`.
- All three tables ship with `autovacuum_vacuum_scale_factor = 0.01` — the cleanup sweep scenario is the
  designed test of exactly that provision.

Cleanup flow (backend-neutral, `internal/utils/persistent-actors/.../cleanup/`): `PersistenceCleanupActor`
streams `(pid, newest-snapshot-sn)` pages via `readJournal.getNewestSnapshotsAbove(lowerBoundPid, batchSize,
minAge, pidFilter)`, then `Cleanup` issues per-pid **batched** `deleteEvents(pid, from, to)` up to
`snapshotRevision - 1` and `deleteSnapshots(pid, from, to)` below the latest snapshot, under a credit
mechanism (`Credits`). Both backends implement these primitives in-repo: `PostgresReadJournal` +
`PostgresJournalOps`/`PostgresSnapshotStoreOps` (bounded PK-range deletes) and `MongoReadJournal`
(`getNewestSnapshotsAbove` aggregation, `deleteEvents`/`deleteSnapshots`). The bench transcribes these
operations 1:1; batch sizes come from `CleanupConfig` defaults (pinned from `DefaultCleanupConfig` at
implementation time and recorded in the evidence files).

## 4. Module and corpus

**Module** `internal/utils/persistence-bench` (`ditto-internal-utils-persistence-bench`): no production code;
test-scoped deps only — PostgreSQL JDBC, MongoDB sync driver, `ditto-json`, JUnit. Bench classes use the
CI-excluded naming convention (match neither Surefire `*Test`/`Test*` nor Failsafe `*IT`/`IT*`), so
`mvn install`/`verify` never runs them. One exception: `LoaderSmokeIT` (Testcontainers, self-skipping without
Docker) validates both loaders + generator determinism on a 5k corpus.

**Corpus** — deterministic from `(seed, pidIndex)`, defaults seed 42, 1M pids:

- Namespaces Zipf-skewed over a pool of 50 (search-bench convention).
- Journal depth per pid: Zipf-like discrete distribution, median ~20, p99 ~2,000, hard cap 50,000. Pid
  classes ("median", "p99", "outlier") are addressable by construction so scenarios can target them
  deterministically.
- Events: things-shaped JSON — `ThingCreated` first, then attribute/feature-property modified events;
  payload-size knob (~300 B–1 KB).
- Snapshots: every **500** events (Ditto's things default `snapshot.threshold`; knob), payload = full thing
  state JSON, size knob ~2–10 KB. Consequence (accepted as realistic): only the ~3–5% hot-tail pids carry
  snapshots and therefore cleanup backlog.
- PG extra: one `things_journal_seq` row per pid (`highest_sn` = depth, `deleted_to` = 0).

**Loaders** — one generator stream, two loaders; both build indexes AFTER bulk load and record sizes:

- PG: `COPY ... FROM STDIN` into `things_journal`, `things_snaps`, `things_journal_seq`; `ANALYZE`; sizes via
  `pg_relation_size` per table/index.
- Mongo: documents + indexes mirroring exactly what Ditto's persistence plugin creates (document layout
  extracted from the plugin configuration at implementation time; the `MongoReadJournal` queries in-repo pin
  the field names) — bulk `insertMany`; sizes via `collStats`.

**Environment** — two long-lived host-mapped containers (corpus must survive across stages):

- `postgres:16` with the search bench's laptop-class settings from the start:
  `shared_buffers=4GB, effective_cache_size=12GB, work_mem=64MB`.
- MongoDB at the version Ditto's deployment compose pins (checked at implementation), WiredTiger cache 4 GB
  for symmetry. All fairness choices documented in the README.
- Disk budget ~40 GB per backend (~80–90 GB total at full scale); `--count` scales down.

## 5. Scenario matrix

Every scenario runs identically against both backends. Protocol per scenario: **3 warmup + 20 timed
executions, varying-but-deterministic binds, p50/p95/max**; one representative plan captured —
`EXPLAIN (ANALYZE, BUFFERS)` on PG, `explain("executionStats")` on Mongo; results rewritten into committed
evidence files with side-by-side PG | Mongo tables. PG connections use pgjdbc `prepareThreshold=0` (the
search bench's generic-plan-flip finding, adopted as protocol). Mongo `deleteMany` has no executionStats —
delete scenarios are timed, and the gap is disclosed in the evidence file.

**Group R — Recovery:**

| # | Scenario | Shape |
|---|---|---|
| R1 | Latest-snapshot fetch | `SELECT ... FROM things_snaps WHERE pid=? ORDER BY sn DESC LIMIT 1` vs plugin snaps query |
| R2 | Replay from snapshot | `SELECT ... FROM things_journal WHERE pid=? AND sn>? ORDER BY sn` — measured per pid class (median/p99/outlier) |
| R3 | Highest sequence nr | `things_journal_seq` lookup vs Mongo max-sn query |

**Group W — Write path:**

| # | Scenario | Shape |
|---|---|---|
| W1 | Single append txn | journal INSERT + `journal_seq` upsert (one txn) vs plugin-shaped journal doc insert |
| W2 | Snapshot write | ~2–10 KB JSONB/BSON insert/upsert |
| W3 | Sustained churn | 10-min run at target rate, 30 s samples: achieved rate, latency, WAL/oplog growth, table+index sizes (search-bench Part-1 protocol) |

**Group C — Cleanup (the explicit ask):**

| # | Scenario | Shape |
|---|---|---|
| C1 | Pid-stream page | one `getNewestSnapshotsAbove` batch — PG SQL vs the Mongo aggregation `MongoReadJournal` issues |
| C2 | Single-pid cleanup | batched journal range delete (`pid=? AND sn>=? AND sn<=?`, `CleanupConfig` default batch size) + `deleted_to` update + snapshot range delete keeping latest — per pid class (p99, outlier) |
| C3 | Full cleanup sweep | driver loop replicating `PersistenceCleanupActor`'s sequence (stream pid pages → per-pid batched deletes, rate-limited like `Credits`) over the whole corpus; 30 s samples: pids/s, rows-deleted/s, PG dead tuples + autovacuum activity, size curves both backends |
| C4 | Before/after proof | re-measure R2 + table sizes after C3 completes — quantifies reclaimed space and recovery-latency improvement |

**Group M — Mixed:**

| # | Scenario | Shape |
|---|---|---|
| M1 | Cleanup under live traffic | C3 sweep concurrent with W3 churn, 10 min — interference metrics on both backends |

Ordering constraint: C3 mutates the corpus irreversibly (that is its job). Stage order in the script is
therefore R → W → C1/C2 → C3 → C4 → M1, and M1 runs against the post-sweep corpus with fresh churn pids (or
after a reload at the operator's choice — `run-benchmark.sh` prints the corpus state via `status`).

## 6. Deliverables

- Bench classes: `CorpusGenerator`, `PgLoadBench`, `MongoLoadBench`, `RecoveryBench`, `AppendBench`,
  `SnapshotBench`, `CleanupShapeBench`, `CleanupSweepBench`, `MixedChurnBench`, `LoaderSmokeIT`.
- Evidence: `src/test/resources/bench/results/` — one md file per scenario (side-by-side table + both plans);
  load evidence records corpus counts + per-table/index/collection sizes.
- `benchmark/README.md`: methodology, environment/fairness pins, headline comparison tables, caveats,
  reproduction, presentation storyline (same structure as the search bench's).
- `benchmark/run-benchmark.sh`: stages `up` (both containers), `build`, `load`, `smoke`, `recovery`, `write`,
  `cleanup` (C1+C2), `sweep` (C3+C4), `mixed`, `all`, `status`, `down`; options `--count`, `--seed`,
  `--quick` (50k pids + short windows), `--backend pg|mongo|both`, `--yes`, `--purge`.

## 7. Verdict criteria

Primary: the side-by-side ratio table — **flag any scenario where PG p95 is worse than Mongo p95 by > 2×**.
Secondary absolute bars for PG standing alone (proposed; adjustable before the first full run, and the
evidence files must state the bar they were judged against):

- R2 median-pid replay p95 < 50 ms; p99-pid < 250 ms; outlier (50k events) < 2 s.
- W1 single append p95 < 25 ms.
- C3 sweep sustains without unbounded dead-tuple growth (autovacuum keeps up — measured via
  `pg_stat_user_tables` samples, not assumed).

## 8. Caveats (carried into README and every quoted number)

1. Laptop-scale Docker environment; 10-minute sustained windows — trends, not plateau claims (same
   honesty rule as the search bench).
2. Driver-level, not actor-level: no Pekko/serialization overhead in either backend's numbers. Symmetric by
   design, so the *comparison* is fair even though absolute service-level latency will be higher.
3. Mongo delete timings lack plan-level evidence (`deleteMany` has no executionStats).
4. The corpus is synthetic; payload-size knobs approximate, not reproduce, any specific customer data set.

## 9. Open items pinned at implementation time (not design gaps)

- `CleanupConfig` default batch sizes / reads-per-query (from `DefaultCleanupConfig`), recorded in evidence.
- Exact Mongo journal/snapshot document layout + index set as Ditto's plugin creates them (verified against a
  live Ditto-on-Mongo instance or the plugin source, then frozen in the loader).
- MongoDB container version (from Ditto's deployment compose).
- Final absolute bars (§7) confirmed with the user before the first full run.
