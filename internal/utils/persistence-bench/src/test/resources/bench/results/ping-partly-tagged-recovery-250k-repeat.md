# Persistence ping on a partly tagged corpus: an ID-order walk broken twice

- generated: 2026-10-22T18:16:36.929514Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged (load evidence: `load-pg-partly-tagged-250k-repeat.md`), schema v3
- backend: pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- evidence label: 250k-repeat

## Walk

- client: exactly the production pool (`ConnectionPoolFactory.createConnectionPool`), `PostgresReadJournal` over `things_*`, ID order, any tagged event, read-journal-batch-size 500, maxIdleTime PT10M (10 retries per window), no throttle
- delivered / expected: 32,038 / 32,038; missing 0, extra 0, duplicates 0, strictly ascending
- walk time: 305.4 s
- walk failure: none
- host memory before: 2026-10-22T18:16:36.981877Z swap_used_MB=1607.12 swapins=4551309 swapouts=6137538 free=48
- host memory after: 2026-10-22T18:21:42.464553Z swap_used_MB=1857.44 swapins=4551312 swapouts=6153558 free=52
- host memory verdict: UNRELIABLE
- postmaster start time: 2026-10-22 21:11:37.197312+03 → 2026-10-22 21:20:15.495998+03 (restarted)

## Breaks

| break | at (UTC) | delivered before | detail | failure |
|---|---|---|---|---|
| pg_terminate_backend | 2026-10-22T18:18:24.235972Z | 10,726 | backend 203 (wait IO, `SELECT w.pid AS pid, ($2 = '' OR EXISTS (SELECT 1 FROM thing…`) terminated: true | — |
| container restart | 2026-10-22T18:20:14.049815Z | 21,387 | `docker restart -t 60 ditto-persistence-bench-pg` returned after 1.2 s (ditto-persistence-bench-pg) | — |

## Read-journal WARN lines (verbatim)

Each line: the time, the break it follows and the gap to the previous WARN of that break (the back-off: 1 s, 2 s, 4 s … plus the attempt's own time and jitter).

- 2026-10-22T18:18:24.318Z (pg_terminate_backend, first): `Page query of read-journal stream <pids with tag <always-alive>> after <thing:bench.ns03:pid-00079758> failed on attempt <1> (at most <10> retries with back-off): SQLSTATE <57P01> io.r2dbc.postgresql.ExceptionFactory$PostgresqlNonTransientResourceException: [57P01] terminating connection due to administrator command`
- 2026-10-22T18:18:25.361Z (pg_terminate_backend, +1.0 s): `Page query of read-journal stream <pids with tag <always-alive>> after <thing:bench.ns03:pid-00079758> failed on attempt <2> (at most <10> retries with back-off): io.r2dbc.spi.R2dbcNonTransientResourceException: Connection validation failed`
- 2026-10-22T18:20:14.725Z (container restart, first): `Page query of read-journal stream <pids with tag <always-alive>> after <thing:bench.ns14:pid-00123494> failed on attempt <1> (at most <10> retries with back-off): SQLSTATE <57P01> io.r2dbc.postgresql.ExceptionFactory$PostgresqlNonTransientResourceException: [57P01] terminating connection due to administrator command`
- 2026-10-22T18:20:15.827Z (container restart, +1.1 s): `Page query of read-journal stream <pids with tag <always-alive>> after <thing:bench.ns14:pid-00123494> failed on attempt <2> (at most <10> retries with back-off): io.r2dbc.spi.R2dbcNonTransientResourceException: Connection validation failed`

## Resume points

Each break is tied to the window it failed: the cursor (`after <…>`) of the first read-journal WARN logged at or after its trigger and before the next trigger. The read journal fetches a page ahead, so when a trigger fires the walk may still be delivering a page it holds, and the window that fails is a later one. The walk resumed right after the failed window if the last pid delivered up to the cursor and the first one after it are neighbours in the expected order. A break with no WARN had no effect and fails the stage.

| break | failed window: cursor (first WARN) | failed attempts of that window | last pid ≤ cursor | its index (expected order) | first pid > cursor | its index | contiguous | s from the trigger to the first pid > cursor | s from the first WARN |
|---|---|---|---|---|---|---|---|---|---|
| pg_terminate_backend | `thing:bench.ns03:pid-00079758` (2026-10-22T18:18:24.318Z) | 2 | `thing:bench.ns03:pid-00079707` | 10725 | `thing:bench.ns03:pid-00080066` | 10726 | yes | 3.58 | 3.50 |
| container restart | `thing:bench.ns14:pid-00123494` (2026-10-22T18:20:14.725Z) | 2 | `thing:bench.ns14:pid-00123428` | 21386 | `thing:bench.ns14:pid-00123975` | 21387 | yes | 4.24 | 3.57 |

A failed window is retried as a whole from its cursor; the rows of the failed attempt are discarded (the window is read completely before it is emitted), so an ID-order walk delivers no pid twice. Observed: 0 duplicates.

**Result: every walk delivered exactly the expected pids.**
