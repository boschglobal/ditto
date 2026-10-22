# C1 what-if (NOT PRODUCTION) — the PG cleanup page with and without the snapshot payload (retention 3d and 0, batch 100)

- generated: 2026-10-22T21:20:43.010387Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-whatif-run1

## PostgreSQL

| min age (one page, varying lower bound) | production page, with payload: p50 \| p95 \| max (ms) | payload-free what-if (NOT PRODUCTION): p50 \| p95 \| max (ms) | payload share of the page: p50 / p95 |
|---|---|---|---|
| minAge 0 | 4.07 \| 4.48 \| 4.57 | 0.48 \| 0.78 \| 0.85 | 88 % / 83 % |
| retention 3d | 2.66 \| 2.88 \| 2.93 | 0.37 \| 0.44 \| 0.46 | 86 % / 85 % |

| production page at retention 3d, client handling | p50 \| p95 \| max (ms) |
|---|---|
| payload transferred, not decoded (the C1 bench client) | 2.74 \| 2.98 \| 3.32 |
| payload decoded and JSON-parsed as PostgresReadJournal does (pgjdbc estimate) | 5.27 \| 14.61 \| 17.81 |

- production page: result check, minAge PT0S: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- production page: result check, minAge PT72H: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- payload-free what-if: result check, minAge PT0S: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- payload-free what-if: result check, minAge PT72H: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- payload per production page (octet_length of snapshot::text, untimed), retention 3d: min/mean/max 547 / 590 / 624 kB
- payload per production page (octet_length of snapshot::text, untimed), minAge 0: min/mean/max 547 / 590 / 624 kB
- age mix read after timing (its full scan of things_snaps would warm the heap before a cold run): 8581 pids with snapshots (expected 8581), 0 of them without a snapshot older than 3d (expected 0)
- cache state: cold: the server was recreated right before the stage (shared buffers started empty; the VM's page cache is not controlled, its hits show as reads with a sub-millisecond read time); server started 2026-10-22 21:18:36.187408Z, 125 s before this sample; warm pass before timing: none
- things_snaps blocks during timing (pg_statio_user_tables; read = not in shared buffers; read time = pg_stat_database.blk_read_time of this session's reads, track_io_timing on): heap 531 read / 31593 hit, index 189 / 97426, toast 5977 / 89964; 6697 reads in all, read time 80.5 ms
- quiet-server gate before timing: quiet after 0 s (not yet vacuumed: none; vacuums in progress: none); things_snaps: last_vacuum null, last_autovacuum 2026-10-23 00:01:42.89053+03; relallvisible 547 of relpages 576
- vacuums in progress at timing start: none; at timing end: none
- server settings: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36272.7 MB | 2015.3 MB | 38288.0 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.5 MB |
| things_snaps | 291.4 MB | 2.9 MB | 294.3 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 40.3 MB |
| things_snaps_pkey | 2.9 MB |

### PG plan — production page, retention 3d (mid-corpus lower bound)

```
Nested Loop  (cost=0.84..536.16 rows=100 width=110) (actual time=0.029..1.305 rows=100 loops=1)
  Buffers: shared hit=863
  ->  Limit  (cost=0.42..25.42 rows=100 width=30) (actual time=0.011..0.061 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.42..1909.38 rows=7637 width=30) (actual time=0.011..0.058 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.42..1829.23 rows=32060 width=30) (actual time=0.011..0.039 rows=527 loops=1)
                    Index Cond: ((pid > 'thing:bench.ns02:pid-00122616'::text) AND (written_at < (now() - '72:00:00'::interval)))
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.42..5.10 rows=1 width=80) (actual time=0.012..0.012 rows=1 loops=100)
        Buffers: shared hit=855
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.42..28.48 rows=6 width=80) (actual time=0.012..0.012 rows=1 loops=100)
              Index Cond: ((pid = things_snaps.pid) AND (written_at < (now() - '72:00:00'::interval)))
              Buffers: shared hit=855
Planning Time: 0.043 ms
Execution Time: 1.312 ms
```

### PG plan — payload-free what-if (NOT PRODUCTION), retention 3d (mid-corpus lower bound)

```
Nested Loop  (cost=0.84..535.66 rows=100 width=78) (actual time=0.005..0.147 rows=100 loops=1)
  Buffers: shared hit=408
  ->  Limit  (cost=0.42..25.42 rows=100 width=30) (actual time=0.004..0.051 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.42..1909.38 rows=7637 width=30) (actual time=0.004..0.048 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.42..1829.23 rows=32060 width=30) (actual time=0.004..0.030 rows=527 loops=1)
                    Index Cond: ((pid > 'thing:bench.ns02:pid-00122616'::text) AND (written_at < (now() - '72:00:00'::interval)))
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.42..5.09 rows=1 width=48) (actual time=0.001..0.001 rows=1 loops=100)
        Buffers: shared hit=400
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.42..28.45 rows=6 width=48) (actual time=0.001..0.001 rows=1 loops=100)
              Index Cond: ((pid = things_snaps.pid) AND (written_at < (now() - '72:00:00'::interval)))
              Buffers: shared hit=400
Planning Time: 0.035 ms
Execution Time: 0.154 ms
```

### PG plan — production page, minAge 0 (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..534.35 rows=100 width=110) (actual time=0.015..1.054 rows=100 loops=1)
  Buffers: shared hit=863
  ->  Limit  (cost=0.41..24.36 rows=100 width=30) (actual time=0.003..0.045 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1829.23 rows=7637 width=30) (actual time=0.003..0.042 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1749.08 rows=32060 width=30) (actual time=0.003..0.024 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.010..0.010 rows=1 loops=100)
        Buffers: shared hit=855
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.46 rows=6 width=80) (actual time=0.010..0.010 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=855
Planning Time: 0.029 ms
Execution Time: 1.060 ms
```

### PG plan — payload-free what-if (NOT PRODUCTION), minAge 0 (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..533.85 rows=100 width=78) (actual time=0.006..0.125 rows=100 loops=1)
  Buffers: shared hit=408
  ->  Limit  (cost=0.41..24.36 rows=100 width=30) (actual time=0.004..0.042 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1829.23 rows=7637 width=30) (actual time=0.004..0.039 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1749.08 rows=32060 width=30) (actual time=0.003..0.023 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.08 rows=1 width=48) (actual time=0.001..0.001 rows=1 loops=100)
        Buffers: shared hit=400
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.43 rows=6 width=48) (actual time=0.001..0.001 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=400
Planning Time: 0.034 ms
Execution Time: 0.131 ms
```

### Statements with literals (mid-corpus lower bound)

production page, retention 3d:

```sql
SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('false' OR written_at < now() - '259200 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('false' OR written_at < now() - '259200 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

payload-free what-if (NOT PRODUCTION), retention 3d:

```sql
SELECT p.pid, s.sn, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('false' OR written_at < now() - '259200 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('false' OR written_at < now() - '259200 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

production page, minAge 0:

```sql
SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('true' OR written_at < now() - '0 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('true' OR written_at < now() - '0 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

payload-free what-if (NOT PRODUCTION), minAge 0:

```sql
SELECT p.pid, s.sn, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('true' OR written_at < now() - '0 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('true' OR written_at < now() - '0 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

What is compared (NOT PRODUCTION what-if): the production page is the statement PostgresPersistenceOperations.getNewestSnapshotsAbove issues for Cleanup (includeDeleted=true, pidFilter='', minAgeFromNow = history-retention-duration), transcribed as PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE. The payload-free what-if is that SQL without the outer s.snapshot and the lateral snapshot::text: the same binds and plan nodes, lifecycle and written_at still read from the heap; Ditto does not issue it. Both are read through pgjdbc taking pid and sn only; the decoded variant also reads every payload as a String and parses it with JsonFactory.newObject, as PostgresReadJournal.toSnapshotEntry does (production reads through r2dbc, so that row is an estimate). Timing: each pair interleaved per lower bound, alternating which runs first; pairs in the order retention, minAge 0, decode (cold: minAge 0, retention, decode, as only the first pair meets cold shared buffers; the later pairs re-read the same lower bounds); result checks, payload sizes, plans and statements after timing. MongoDB is not run: its cleanup pipeline projects the payload away (sn and lifecycle only). Cache state (bench.c1.cacheState): as is = no preparation; cold = the server was recreated right before the stage (run-benchmark.sh recreate), so shared buffers started empty while the VM's page cache is not controlled, and the run fails unless timing read blocks; warm = one untimed pass over the whole pid stream with the production page first. The things_snaps block line counts what the timed statements read into shared buffers (pg_statio_user_tables) and the time those reads took (pg_stat_database.blk_read_time, track_io_timing on in this session).
