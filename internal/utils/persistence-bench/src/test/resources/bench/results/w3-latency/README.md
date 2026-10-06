# W3 per-op latency profile (PostgreSQL vs MongoDB)

Why the committed W3 run (`../w3-sustained-churn.md`, 250k corpus, 2026-07-13) shows a PostgreSQL
per-op median of 6.53–7.40 ms against MongoDB's 1.79–3.20 ms while both hold 500 appends/s.

- Profiled 2026-10-06 on a **50k corpus** (seed 42, 6,953,631 journal rows per backend; load evidence
  `load-pg-50k.md`, `load-mongo-50k.md`). Both backends were measured on the same corpus with the same
  run protocol.
- Environment: the pins of `../../../../../../benchmark/README.md` §2 (postgres:16 = 16.15,
  mongo:7.0 = 7.0.41, `shared_buffers=4GB`, WT cache 4 GB, `synchronous_commit=on`, Mongo
  `WriteConcern.JOURNALED`) in OrbStack (12 CPUs, 23.5 GiB). `pg_test_fsync` on the PG data volume:
  `fdatasync` of one 8 kB write 451 µs; `dd oflag=dsync` 8 kB writes ≈ 0.5 ms on both data volumes
  (`server-side.md` §1).
- All runs: 500 appends/s, 8 workers × 100 pids unless the file name says otherwise, 30 s windows.
  Each run used its own churn pid prefix (the run's working label, kept inside each file), so runs
  could follow each other on one corpus.
- `canonical-600s-both.md` and `restart-aligned-txn.md` were produced by an earlier revision of this
  harness: same timed path as the committed W3 (default knobs), but without the "worker schedules"
  line and the per-window table. `canonical-600s-both.md` ran on the pinned server config. Every later
  PG run had `pg_stat_statements`, `track_io_timing` and `track_wal_io_timing` enabled (needed for the
  WAL fsync column) and the 10 ms wait-event sampler on.
- Knobs used (all opt-in, see `AppendBench`/`BenchConfig`): `bench.w3.pgShape`, `bench.w3.stagger`,
  `bench.w3.mongoWriteConcern`, `bench.w3.waitSampleMillis`, `bench.w3.pidPrefix`,
  `bench.w3.evidenceFile`; server settings (`synchronous_commit`, `commit_delay`) were changed with
  `ALTER SYSTEM` + reload for the named runs only.
- Server-side outputs this file cites (`pg_stat_statements` per run, free space map, fsync baselines,
  per-window placement of the canonical run, autovacuum periods): `server-side.md`.

## Short answer

1. **The load generator fires the eight workers in lock-step (cause).** Each worker starts its 16 ms
   pacing schedule when its connection is ready; the connections open nearly together, so the
   schedules are aligned within 0.06–2.0 ms in the aligned runs that record them (0.24 ms in
   `aligned-300s-txn`; the canonical and restart runs predate the schedule line). Every 16 ms eight
   appends arrive at once. PostgreSQL flushes WAL under one lock: a burst of eight commits waits for
   two flushes back to back. Every durable (`synchronous_commit=on`) aligned 8-worker run with
   `commit_delay = 0` shows 3.9–4.0 commits per WAL flush, except `aligned-no-hwm` (3.13; its
   schedules spread over 2.0 ms); with even arrivals it is 1.0. The commit step takes 1.6–2.1 × the
   average fsync, and the backends queue on `LWLock:WALWrite`. MongoDB's journaled writes absorb
   the same bursts better (1.7–2.0 ms aligned vs 1.0–1.1 ms even).
2. **Bulk-load free space (explains the WAL volume, not shown to cost latency).** COPY leaves a few
   hundred bytes free on most heap pages and the load leaves them in the free space map, so every
   churn insert on a freshly loaded corpus lands on a different old page: one block read and one
   full-page image per op (8.7 kB WAL/op instead of 1.3 kB). This is where the committed run's
   124–131 MB WAL per window comes from (here: 124 MB per window while it lasted, 19 MB after).
   Whether this state, or the write-back that follows a bulk load, adds latency was **not
   isolated**: the slower aligned runs coincided with the bulk-loaded state, but a run during the
   post-load autovacuum (100 % page reuse, heavy write-back) ran at 3.82 ms, and in the canonical
   run the latency did not drop when the page reuse stopped (finding below the table).

**Not code-side.** Removing the high-water-mark upsert, merging the append into one statement (one
round trip) or tuning group commit does not remove the gap; spreading the arrivals does.

**What even arrivals mean.** `-Dbench.w3.stagger=true` spaces the commits exactly 2 ms apart, longer
than one fsync, so commits almost never overlap: it is the best case, lock-step is the worst.
Production appends arrive at random times and were not measured, so production latency lies in
between. With even arrivals PostgreSQL's median is 1.0–1.7 ms vs MongoDB's 1.0–1.1 ms; in the
same-period interleaved runs PostgreSQL stays about 0.5 ms (1.4–1.6×) above MongoDB (1.56 / 1.60 vs
1.10 / 1.02 ms). PostgreSQL's p95 is 2.2–2.7 ms on W3's pgjdbc shape and 2.2–4.9 ms on the
production append path (the 4.9 ms run overlapped an autovacuum, see below) vs MongoDB's
3.3–6.4 ms; with lock-step arrivals MongoDB's p95 is the lower one (3.7–3.8 vs 6.0–10.4 ms).

## Key runs

PG = the plugin's append transaction on pgjdbc (W3's shape) unless the run names another shape.
"Aligned" = W3's default schedules, "staggered" = `-Dbench.w3.stagger=true` (offsets 0, 2, …, 14 ms).
Latency = median over the run's 30 s windows of the window p50 / p95.

**Run-to-run variance.** Aligned runs of the same PG shape ranged from 2.87 to 6.47 ms p50 in this
session (`restart-aligned-txn` 2.87, `reload-during-autovacuum-aligned-txn` 3.82,
`aligned-300s-txn` 4.08, `commit-delay-0-aligned-txn` 5.46, `reload-free-space-reuse-aligned-txn`
5.55, `canonical-600s-both` 6.47); even ones from 1.04 to 1.60 ms. A difference between two single
runs taken at different times that is smaller than that spread is not an effect; only the
interleaved runs compare variants within one period.

| run (file) | arrivals | PG p50 / p95 ms | PG commit step p50 ms | commits per WAL flush | avg WAL fsync ms | MongoDB p50 / p95 ms |
|---|---|---|---|---|---|---|
| `canonical-600s-both` (committed protocol, 600 s) | aligned | 6.47 / 10.09 | 4.16 | 3.98 | not instrumented | 1.99 / 3.79 |
| `aligned-300s-txn` / `aligned-mongo-journaled` | aligned | 4.08 / 6.59 | 2.90 | 3.96 | 1.56 | 1.71 / 3.71 |
| `staggered-300s-txn` | even | 1.58 / 2.73 | 1.20 | 1.02 | 1.12 | — |
| `staggered-txn` / `staggered-mongo-journaled` | even | 1.04 / 2.33 | 0.67 | 1.02 | 0.71 | 1.00 / 6.08 |
| `interleaved-{1,2}-txn` / `-mongo-journaled` (same period, A-B-…-B-A) | even | 1.56, 1.60 / 2.19–2.28 | 1.13, 1.22 | 0.99 | 1.03, 1.12 | 1.10, 1.02 / 3.30–6.44 |
| `reload-free-space-reuse-aligned-txn` (100 % of rows on old pages) | aligned | 5.55 / 8.66 | 3.88 | 3.93 | 2.09 | — |
| `reload-free-space-reuse-staggered-txn` (100 % on old pages) | even | 1.73 / 3.08 | 1.35 | 1.01 | 1.32 | — |

The canonical run reproduces the committed numbers at 50k (PG 5.19–6.82 vs MongoDB 1.68–2.09 ms per
window p50). Its windows 0–6 put 100 % of rows on old pages (WAL ≈ 124 MB per window, the committed
run's 124–131 MB), window 7 35 %, windows 8–19 none (19 MB per window; `server-side.md` §3), and its
latency stayed at 6.2–6.8 ms after the switch. Why it stayed there is not known: one hypothesis is
the write-back of the ~110k pages dirtied in the first phase (checkpointer and backends wrote ~150k
buffers during the run), but that run has no per-window write-back data, and the run with the
heaviest write-back (`reload-during-autovacuum-aligned-txn`, 81–85k backend buffers per window in
windows 1–3, up to 0.85 GB dirty in the VM) had an average WAL fsync of 1.51 ms and a p50 of 3.82 ms. At 250k the
load leaves about five times as many reusable gaps, enough for all 300k inserts of the committed
10-minute run, which matches its constant 124–131 MB per window (inference: the committed run kept
no placement data).

Autovacuum overlaps (`server-side.md` §4): an insert-triggered `things_journal` autovacuum ran
10:32:53–10:35:19 UTC, during the last ~10 s of `staggered-one-statement` and about two of the three
minutes of `staggered-r2dbc`; the post-reload autovacuum began about 30 s into
`reload-during-autovacuum-aligned-txn` (windows 1–3). No other run overlapped an autovacuum.

In the staggered runs the schedules start 1 s after the run's clock (`Schedule`'s lead time), so
window 0 holds about 29 s of ops and the last second of ops is counted in the totals but in no
window; the medians are not affected.

## Per hypothesis

**Commit / WAL fsync (fairness).** Both backends make every append durable before acknowledging it:
PG flushes WAL at commit (`synchronous_commit=on`, `fdatasync`); MongoDB `j:true` waits for a journal
sync (1.21–1.93 journal syncs per op, the plugin's realtime side-insert is also journaled). One sync
costs about the same on both: PG 0.7–2.2 ms per WAL flush (`synchronous_commit=on` runs), MongoDB
0.78–1.14 ms per journal sync. The commit wait is PG's dominant step: `synchronous_commit=off` (not
durable, what-if only) drops PG to 1.24 ms aligned and 0.62 ms even (`*-txn-sync-commit-off`);
MongoDB `w:1` (journal write not awaited) 0.21 ms (`staggered-mongo-w1`). `commit_delay = 500 µs`
makes PG group more commits per flush (6.4 instead of 4.0) but the aligned run gets slower (6.42 vs
5.46 ms back to back, `commit-delay-*`), so it is no remedy.

**High-water-mark upsert.** No row-lock contention is possible in W3 (each pid belongs to one worker)
and none was observed: no `Lock:tuple`/`Lock:transactionid` waits in any of the 29 PG runs with a
wait table, only `Lock:extend` at ≤ 0.1 % of observations. Server time 0.008–0.040 ms per upsert
(`server-side.md` §5); on the client it is one round trip (0.13–0.68 ms p50). Leaving it out does
not change the latency (`*-no-hwm`: 4.28 vs 4.08 ms aligned, 1.02 vs 1.04 ms even, single runs
within the variance above).

**Pool and connection count.** The bench uses eight dedicated pgjdbc connections, so it has no
acquire step. The production pool acquires in 0.02–0.07 ms p50 and releases connections in 0.01 ms
(`*-r2dbc-phased`), ending with 8 allocated and 0 pending borrowers. The number of connections does
not matter, the size of the simultaneous burst does: 4 aligned workers 3.02 ms (2.0 commits per
flush), 8 aligned 4.08 ms (4.0), 16 workers whose schedules happened to spread over 5.7 ms of their
32 ms period 2.44 ms (2.6); staggered 1.56 / 1.04–1.60 / 1.59 ms (`*-txn-{4,16}-workers`).

**Driver path.** W3 times a pgjdbc transcription (`prepareThreshold=0`; three round trips: INSERT
with BEGIN piggy-backed, upsert, COMMIT), not the production write path. The production path here
is `PostgresPersistenceOperations#insertEvents` on a pool from the production `ConnectionPoolFactory`
(r2dbc-postgresql, unnamed statements), not the Pekko journal plugin end to end. It makes four round
trips: BEGIN is one of its own (0.17 ms even, 0.55 ms aligned). With even arrivals it costs about
0.1 ms more than the transcription (interleaved 1.69 / 1.71 vs 1.56 / 1.60 ms). Under lock-step
bursts it was slower in single, non-interleaved runs (`aligned-r2dbc` 6.22 and `aligned-r2dbc-phased`
4.98 vs `aligned-300s-txn` 4.08 ms), which is not isolated: those runs had tighter schedules
(0.06 / 0.14 ms, no connection to open) and `aligned-r2dbc` a higher average fsync (1.89 ms). A
one-round-trip variant (insert + upsert in one CTE, autocommit) gains nothing measurable with even
arrivals (interleaved 1.64 / 1.61 ms) and was worse in the aligned run (5.21 ms: all eight commits
reach the WAL lock together).

**Server side.** Aligned 8-worker runs: `LWLock:WALWrite` 11.6–25.6 % of the churn backends'
observations against `IO:WALSync` 2.3–4.2 %, i.e. several committers wait while one flushes. Even
8-worker runs: `LWLock:WALWrite` 0.1–1.7 %, `IO:WALSync` 5.0–8.8 %. `pg_stat_statements`: the event
INSERT executes in 0.013–0.030 ms when appending with even arrivals and 0.23–0.27 ms when it reuses
an old page under lock-step bursts (0.046 ms with even arrivals; 1.0 block read, 1.0 full-page image,
8.4 kB WAL);
`BEGIN`/`COMMIT` show ≈ 0 because the flush wait is not executor time. The canonical run saw two
WAL-requested checkpoints and checkpoint writing during 370 s of its 600 s.

## Files

| file prefix | what |
|---|---|
| `canonical-600s-both` | the committed W3 protocol, both backends, 600 s, pinned server config (earlier harness revision) |
| `restart-aligned-txn` | first instrumented run, right after a server restart (earlier harness revision) |
| `aligned-*`, `staggered-*` | one variant per hypothesis, 180–300 s, each with both schedules |
| `reload-*` | freshly reloaded PG corpus: during the post-load autovacuum, then while inserts reuse the bulk load's free space |
| `commit-delay-*` | `commit_delay` 0 vs 500 µs, back to back |
| `interleaved-{1,2}-*` | even arrivals, 60 s each, order mongo, txn, r2dbc, one-statement, then reversed |
| `load-*-50k` | load evidence of the 50k corpus |
| `server-side.md` | verbatim server-side outputs cited above |

Each PG file (except the two earlier-revision ones) has the per-step client timings, a per-window
table (step p50s, WAL flushes, commits per flush, average WAL fsync, full-page images per op,
buffers written back by checkpointer / bgwriter / backends), the heap placement of the run's rows,
the run's `pg_stat_*` counter deltas and the wait events. Each MongoDB file has the `serverStatus`
deltas (journal syncs, sync time).
