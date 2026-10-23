# C1 payload pair — the PG cleanup page before the payload-free change and the production page (retention 3d and 0, batch 100)

- generated: 2026-10-23T13:03:55.063369Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-p1-run1

## PostgreSQL

| min age (one page, varying lower bound) | statement before the change, with payload: p50 \| p95 \| max (ms) | production page, payload-free: p50 \| p95 \| max (ms) | payload share of the page before the change: p50 / p95 |
|---|---|---|---|
| retention 3d | 4.88 \| 9.49 \| 11.61 | 1.20 \| 2.03 \| 2.70 | 75 % / 79 % |
| minAge 0 | 5.91 \| 9.77 \| 11.39 | 1.28 \| 1.97 \| 4.66 | 78 % / 80 % |

| statement before the change at retention 3d, client handling | p50 \| p95 \| max (ms) |
|---|---|
| payload transferred, not decoded (the C1 bench client) | 4.61 \| 8.11 \| 9.07 |
| payload decoded and JSON-parsed as PostgresReadJournal does for a stream with fields (pgjdbc estimate) | 8.88 \| 14.43 \| 15.15 |

- statement before the change: result check, minAge PT0S: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- statement before the change: result check, minAge PT72H: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- production page: result check, minAge PT0S: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- production page: result check, minAge PT72H: 53 of 53 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- payload per page of the statement before the change (octet_length of snapshot::text, untimed), retention 3d: min/mean/max 547 / 590 / 624 kB
- payload per page of the statement before the change (octet_length of snapshot::text, untimed), minAge 0: min/mean/max 547 / 590 / 624 kB
- age mix read after timing (its full scan of things_snaps would warm the heap before a cold run): 8581 pids with snapshots (expected 8581), 0 of them without a snapshot older than 3d (expected 0)
- cache state: warm: one untimed pass over the whole pid stream with the statement before the change (payload included: index, heap and TOAST blocks) before timing; server started 2026-10-23 12:59:33.122925Z, 259 s before this sample; warm pass before timing: 86 pages / 8581 pids with the statement before the change at minAge 0 in 0.5 s; things_snaps blocks during the pass: heap 0 read / 8669 hit, index 180 / 26185, toast 5353 / 33515; 5533 reads in all, read time 131.4 ms
- things_snaps blocks during timing (pg_statio_user_tables; read = not in shared buffers; read time = pg_stat_database.blk_read_time of this session's reads, track_io_timing on): heap 0 read / 32124 hit, index 0 / 97614, toast 0 / 95940; 0 reads in all, read time 0.0 ms
- quiet-server gate before timing: quiet after 0 s (not yet vacuumed: none; vacuums in progress: none); things_snaps: last_vacuum null, last_autovacuum 2026-10-23 15:41:18.978827+03; relallvisible 547 of relpages 576
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

### PG plan — production page (payload-free), retention 3d (mid-corpus lower bound)

```
Nested Loop  (cost=0.84..535.27 rows=100 width=78) (actual time=0.019..0.228 rows=100 loops=1)
  Buffers: shared hit=408
  ->  Limit  (cost=0.42..25.41 rows=100 width=30) (actual time=0.012..0.063 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.42..1904.19 rows=7618 width=30) (actual time=0.011..0.059 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.42..1824.16 rows=32012 width=30) (actual time=0.011..0.037 rows=527 loops=1)
                    Index Cond: ((pid > 'thing:bench.ns02:pid-00122616'::text) AND (written_at < (now() - '72:00:00'::interval)))
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.42..5.09 rows=1 width=48) (actual time=0.002..0.002 rows=1 loops=100)
        Buffers: shared hit=400
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.42..28.43 rows=6 width=48) (actual time=0.001..0.001 rows=1 loops=100)
              Index Cond: ((pid = things_snaps.pid) AND (written_at < (now() - '72:00:00'::interval)))
              Buffers: shared hit=400
Planning Time: 0.103 ms
Execution Time: 0.242 ms
```

### PG plan — statement before the change (with payload), retention 3d (mid-corpus lower bound)

```
Nested Loop  (cost=0.84..535.77 rows=100 width=110) (actual time=0.042..1.425 rows=100 loops=1)
  Buffers: shared hit=863
  ->  Limit  (cost=0.42..25.41 rows=100 width=30) (actual time=0.015..0.068 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.42..1904.19 rows=7618 width=30) (actual time=0.014..0.065 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.42..1824.16 rows=32012 width=30) (actual time=0.014..0.041 rows=527 loops=1)
                    Index Cond: ((pid > 'thing:bench.ns02:pid-00122616'::text) AND (written_at < (now() - '72:00:00'::interval)))
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.42..5.09 rows=1 width=80) (actual time=0.013..0.013 rows=1 loops=100)
        Buffers: shared hit=855
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.42..28.46 rows=6 width=80) (actual time=0.013..0.013 rows=1 loops=100)
              Index Cond: ((pid = things_snaps.pid) AND (written_at < (now() - '72:00:00'::interval)))
              Buffers: shared hit=855
Planning Time: 0.117 ms
Execution Time: 1.440 ms
```

### PG plan — production page (payload-free), minAge 0 (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..533.47 rows=100 width=78) (actual time=0.017..0.196 rows=100 loops=1)
  Buffers: shared hit=408
  ->  Limit  (cost=0.41..24.35 rows=100 width=30) (actual time=0.012..0.054 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1824.16 rows=7618 width=30) (actual time=0.011..0.050 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1744.13 rows=32012 width=30) (actual time=0.011..0.032 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.08 rows=1 width=48) (actual time=0.001..0.001 rows=1 loops=100)
        Buffers: shared hit=400
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.41 rows=6 width=48) (actual time=0.001..0.001 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=400
Planning Time: 0.093 ms
Execution Time: 0.209 ms
```

### PG plan — statement before the change (with payload), minAge 0 (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..533.97 rows=100 width=110) (actual time=0.069..1.772 rows=100 loops=1)
  Buffers: shared hit=863
  ->  Limit  (cost=0.41..24.35 rows=100 width=30) (actual time=0.021..0.084 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1824.16 rows=7618 width=30) (actual time=0.020..0.080 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1744.13 rows=32012 width=30) (actual time=0.020..0.052 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.017..0.017 rows=1 loops=100)
        Buffers: shared hit=855
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.44 rows=6 width=80) (actual time=0.017..0.017 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=855
Planning Time: 0.168 ms
Execution Time: 1.802 ms
```

### Statements with literals (mid-corpus lower bound)

production page, retention 3d:

```sql
SELECT p.pid, s.sn, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('false' OR written_at < now() - '259200 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('false' OR written_at < now() - '259200 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

statement before the change, retention 3d:

```sql
SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('false' OR written_at < now() - '259200 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('false' OR written_at < now() - '259200 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

production page, minAge 0:

```sql
SELECT p.pid, s.sn, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('true' OR written_at < now() - '0 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('true' OR written_at < now() - '0 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

statement before the change, minAge 0:

```sql
SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM (SELECT pid FROM things_snaps WHERE pid > 'thing:bench.ns02:pid-00122616' AND ('' = '' OR pid ~ '') AND ('true' OR written_at < now() - '0 seconds'::interval) GROUP BY pid ORDER BY pid LIMIT 100) p CROSS JOIN LATERAL (SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps WHERE pid = p.pid AND ('true' OR written_at < now() - '0 seconds'::interval) ORDER BY sn DESC, written_at DESC LIMIT 1) s ORDER BY p.pid;
```

What is compared: the production page is the statement PostgresPersistenceOperations.getNewestSnapshotHeadsAbove issues for Cleanup (includeDeleted=true, pidFilter='', minAgeFromNow = history-retention-duration; a stream without snapshot fields), transcribed as PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE: no snapshot column. The statement before the change, PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD, is that SQL plus the outer s.snapshot and the lateral snapshot::text (Cleanup issued it until 2026-10-23; production still issues it for a stream with fields): the same binds and plan nodes. Both are read through pgjdbc taking pid and sn only; the decoded variant also reads every payload of the statement before the change as a String and parses it with JsonFactory.newObject, as PostgresReadJournal.toSnapshotEntry does (an estimate: production reads through r2dbc). Timing: each pair interleaved per lower bound, alternating which runs first; pairs in the order retention, minAge 0, decode (cold: minAge 0, retention, decode, as only the first pair meets cold shared buffers; the later pairs re-read the same lower bounds); result checks, payload sizes, plans and statements after timing. MongoDB is not run: its cleanup pipeline projects the payload away (sn and lifecycle only). Cache state (bench.c1.cacheState): as is = no preparation; cold = the server was recreated right before the stage (run-benchmark.sh recreate), so shared buffers started empty while the VM's page cache is not controlled, and the run fails unless timing read blocks; warm = one untimed pass over the whole pid stream with the statement before the change first. The things_snaps block line counts what the timed statements read into shared buffers (pg_statio_user_tables) and the time those reads took (pg_stat_database.blk_read_time, track_io_timing on in this session).
