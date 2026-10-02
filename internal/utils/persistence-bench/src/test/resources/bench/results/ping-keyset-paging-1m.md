# Persistence ping on PostgreSQL: windowed ID order at 1,000,000 pids, priority order in one statement

- measured: 2026-10-02, local, Testcontainers `postgres:16` (PostgreSQL 16.15, aarch64), container defaults
  (`shared_buffers = 128MB`, `work_mem = 4MB`), torn down after the run
- client: the production read journal (`PostgresReadJournal`), unnamed statements, pool `fetch-size 0`, **no
  throttle** (the ping's 1 pid/s throttle removed); statements timed with an r2dbc-proxy listener (execution until the
  last row is read); every table `VACUUM ANALYZE`d after seeding; warm cache (the numbers are a best case for I/O)
- corpora, 1,000,000 pids × 2 events each (2,000,000 rows, ~0.35 GB per table), seeded with batched
  `INSERT … SELECT generate_series`:
  - **all tagged, heap in pid order** (`always-alive` + `priority-(i % 10)` on every event);
  - **all tagged, heap in random order** (rows shuffled within 100,000-pid chunks);
  - **1 % tagged, clustered at the end** of the key space (pids 990,001–1,000,000), heap in random order.

## ID order: one window of `batchSize` pids per statement

Each statement reads the next `batchSize` distinct pids after the previous window (index-only primary-key range scan)
and checks the tag per pid on the pid's own primary-key range (`$tag = ANY(tags)`, never the GIN index), so its cost
does not depend on where the tagged pids lie.

| walk | windows | delivered | total | p50 | p95 | p99 | max (window) | first 50 (median) | last 50 (median) |
|---|---|---|---|---|---|---|---|---|---|
| all tagged, pid-ordered heap, batch 256 | 3,907 | 1,000,000 | 3.6 s | 0.66 ms | 0.90 ms | 1.67 ms | 10.52 ms (#1, cold) | 2.08 ms | 0.62 ms |
| all tagged, pid-ordered heap, batch 500 (shipped) | 2,000 | 1,000,000 | 2.1 s | 0.93 ms | 1.04 ms | 1.38 ms | 21.52 ms (#316, single outlier) | 1.04 ms | 0.96 ms |
| all tagged, pid-ordered heap, newest event, batch 500 | 2,000 | 1,000,000 | 2.1 s | 0.93 ms | 1.03 ms | 1.39 ms | 1.86 ms (#1) | 0.98 ms | 0.95 ms |
| all tagged, random heap, batch 500 | 2,000 | 1,000,000 | 2.4 s | 1.07 ms | 1.46 ms | 2.05 ms | 3.52 ms (#1) | 1.15 ms | 1.17 ms |
| all tagged, random heap, newest event, batch 500 | 2,000 | 1,000,000 | 2.5 s | 1.06 ms | 1.56 ms | 2.04 ms | 3.43 ms (#1) | 1.17 ms | 1.24 ms |
| 1 % clustered at the end, random heap, batch 500 | 2,000 | 10,000 | 3.0 s | 1.27 ms | 2.06 ms | 3.50 ms | 6.52 ms (#1462) | 1.48 ms | 1.12 ms |
| 1 % clustered at the end, random heap, newest event, batch 500 | 2,000 | 10,000 | 2.3 s | 1.01 ms | 1.48 ms | 2.36 ms | 6.77 ms (#1460) | 1.11 ms | 1.03 ms |

Median per tenth of the walk (ms), first to last tenth:

| walk | tenths |
|---|---|
| all tagged, pid-ordered heap, batch 256 | 0.88 0.74 0.68 0.75 0.71 0.65 0.62 0.62 0.60 0.61 |
| all tagged, pid-ordered heap, batch 500 | 0.99 0.99 0.95 0.94 0.90 0.90 0.87 0.88 0.88 0.94 |
| all tagged, random heap, batch 500 | 1.05 1.10 1.05 1.04 1.02 1.06 1.12 1.10 1.06 1.16 |
| 1 % clustered at the end, random heap, batch 500 | 1.35 1.20 1.21 1.17 1.38 1.42 1.55 1.35 1.22 1.18 |

Flat from the first to the last window in every layout. A walk takes (distinct pids / batchSize) statements whatever
the share of tagged pids; at the shipped ping rate of one pid per second the walk over 1,000,000 tagged pids itself takes
about 11.6 days, of which about 2 s are database time.

### Window statement and plans

```sql
SELECT w.pid AS pid, ($2 = '' OR EXISTS (SELECT 1 FROM policies_journal t WHERE t.pid = w.pid AND $2 = ANY(t.tags))) AS qualifies
FROM (SELECT DISTINCT pid FROM policies_journal WHERE pid > $1 ORDER BY pid LIMIT $3) w ORDER BY w.pid
```

`EXPLAIN (ANALYZE, BUFFERS)`, default planner settings, all tagged, batch 256 — window 1 (cursor `''`):

```
Subquery Scan on w  (cost=0.43..1398.87 rows=256 width=20) (actual time=0.019..0.378 rows=256 loops=1)
  Buffers: shared hit=1032
  ->  Limit  (cost=0.43..30.85 rows=256 width=19) (actual time=0.011..0.083 rows=256 loops=1)
        Buffers: shared hit=8
        ->  Result  (cost=0.43..112768.85 rows=948970 width=19) (actual time=0.011..0.074 rows=256 loops=1)
              ->  Unique  (cost=0.43..112768.85 rows=948970 width=19) (actual time=0.010..0.063 rows=256 loops=1)
                    ->  Index Only Scan using policies_journal_pkey on policies_journal  (cost=0.43..107768.79 rows=2000021 width=19) (actual time=0.009..0.035 rows=511 loops=1)
                          Index Cond: (pid > ''::text)
                          Heap Fetches: 0
  SubPlan 1
    ->  Index Scan using policies_journal_pkey on policies_journal t  (cost=0.43..10.26 rows=2 width=0) (actual time=0.001..0.001 rows=1 loops=256)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Buffers: shared hit=1024
Execution Time: 0.395 ms
```

Window 3,900 (cursor `policy:mio:0998144`): the same plan, `Buffers: shared hit=1020 read=13`, 0.999 ms.

1 % clustered layout, first window (cursor `''`; all 500 pids untagged): the same plan, `Buffers: shared hit=1619
read=892`, 8.1 ms (cold heap pages), 500 rows examined, none qualifying. A statement that looked for the next tagged
pids instead (`pid > $1 AND tags @> … ORDER BY pid LIMIT n`) reads every row before the cluster in one statement: on the
500,000-pid version of this layout (`PostgresClusteredTagLayoutIT`) that was 998,800 buffers and 845–975 ms for the
first statement, against 2,510 buffers and 2.5 ms for a window (slowest statement of the whole walk: 18.3 ms).

## While the consumer pauses

`pg_stat_activity` 3 s into a pause after window 1: every client backend `idle`, `backend_xmin` NULL, `xact_start`
NULL, `wait_event = ClientRead`; the one that ran the window shows it as its last query. No backend of any type held an
xmin horizon.

## Priority order (connectivity ping): one statement per walk

The priority is computed from the tags, so ordering needs every connection's priority; the walk reads the whole order in
one statement, collected in memory before the first connection is pinged. Own table set per run (only these
connections), 2 events per connection; median of 5 walks after a warm-up.

| connections | statement, read completely | retained heap of the result (System.gc deltas, indicative) |
|---|---|---|
| 5,000 | 10.8 ms | ≈ 0.41 MB (83 bytes per connection) |
| 20,000 | 46.9 ms | ≈ 1.6 MB (82 bytes per connection) |
| 100,000 | 271.8 ms | ≈ 8.1 MB (81 bytes per connection) |

For comparison, paging the priority order by keyset on `(priority, pid)` at 500 per page costs one such statement per
page: 94.6 ms per walk at 5,000 connections and 1,548 ms at 20,000.

## Rows out of statement order under incremental demand (r2dbc-postgresql 1.0.7)

During the first measurements, a walk that streamed its rows into Pekko `Sink.seq` occasionally delivered one row of a
page up to 26 positions late, e.g. `…0125032, 0125016, 0125033…` (page complete, no duplicate, not in the statement's
`ORDER BY` order). Reproduction on the all-tagged corpus, 256-row pages, raw page query:

| consumer of `DittoPostgresClient.executeSqlPublisher` | pages | pages with a late row |
|---|---|---|
| Pekko `Source.fromPublisher` + `Sink.seq` (demand in small batches from the stream's thread) | 400,000 | 2 |
| Reactor `publishOn(parallel, 16)` + `collectList` (bounded demand from another thread) | 400,000 | 7 |
| Reactor `collectList` (one collecting subscriber) | 400,000 | 0 |
| page reader of the read journal (collect the page, then emit it), raw page query | 468,840 | 0 |
| page reader, full read-journal walks (121 × 1,000,000 pids) | 472,747 | 0 |

Cause: in r2dbc-postgresql 1.0.7, `ReactorNettyClient.BackendMessageSubscriber#onNext` emits a message directly when its
buffer is empty, without taking the drain guard; when a `request(n)` from another thread is draining the buffer, a
message the drain loop has dequeued but not yet emitted is overtaken by the next one. Fixed upstream in 1.1.2.RELEASE
(the fast path takes the drain guard). The read journal collects every window, page and the priority statement with
one subscriber before emitting: the driver then receives its requests (batches of 32, then 24, from the client's
`flatMap`) only on its own event-loop thread, which never takes that path.
