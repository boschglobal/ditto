# C3 — full cleanup sweep

- generated: 2026-10-21T21:37:05.864663Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 2.6 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 837.5 s (26461 rows/s), finished at 2026-10-21T21:51:06.333200Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 433 | 1162154 | 14.4 | 38738 | 1161946 | 38287.8 MB | 294.1 MB | none |
| 1 | 775 | 1870180 | 11.4 | 23601 | 1850165 | 38287.8 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 2 | 1084 | 2654233 | 10.3 | 26135 | 1012635 | 38287.8 MB | 294.1 MB | none |
| 3 | 1410 | 3487244 | 10.9 | 27767 | 483550 | 38287.9 MB | 294.1 MB | none |
| 4 | 1761 | 4388844 | 11.7 | 30053 | 1386044 | 38287.9 MB | 294.1 MB | none |
| 5 | 2038 | 5169445 | 9.2 | 26020 | 432433 | 38287.9 MB | 294.1 MB | none |
| 6 | 2368 | 5949524 | 11.0 | 26003 | 1246722 | 38287.9 MB | 294.1 MB | none |
| 7 | 2648 | 6669613 | 9.3 | 24003 | 1969511 | 38288.0 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 8 | 2980 | 7415332 | 11.1 | 24857 | 858300 | 38288.0 MB | 294.1 MB | none |
| 9 | 3246 | 8170362 | 8.9 | 25168 | 327472 | 38288.0 MB | 294.1 MB | none |
| 10 | 3616 | 9134002 | 12.3 | 32121 | 1300804 | 38288.0 MB | 294.1 MB | none |
| 11 | 3889 | 9910410 | 9.1 | 25880 | 201618 | 38288.0 MB | 294.1 MB | none |
| 12 | 4172 | 10724778 | 9.4 | 27146 | 1502037 | 38288.0 MB | 294.1 MB | none |
| 13 | 4452 | 11454874 | 9.3 | 24337 | 408976 | 38288.1 MB | 294.1 MB | none |
| 14 | 4761 | 12273488 | 10.3 | 27287 | 1222730 | 38288.1 MB | 294.1 MB | none |
| 15 | 5085 | 13050798 | 10.8 | 25910 | 265015 | 38288.1 MB | 294.1 MB | none |
| 16 | 5389 | 13798447 | 10.1 | 24922 | 1021176 | 38288.1 MB | 294.1 MB | none |
| 17 | 5671 | 14593307 | 9.4 | 26495 | 138724 | 38288.1 MB | 294.1 MB | none |
| 18 | 5941 | 15324827 | 9.0 | 24384 | 1478190 | 38288.1 MB | 294.1 MB | none |
| 19 | 6194 | 16048665 | 8.4 | 24128 | 90587 | 38288.2 MB | 294.1 MB | none |
| 20 | 6624 | 17191278 | 14.3 | 38087 | 1211307 | 38288.2 MB | 294.1 MB | none |
| 21 | 6859 | 17888705 | 7.8 | 23248 | 1928438 | 38288.2 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 22 | 7107 | 18565367 | 8.3 | 22555 | 1186248 | 38288.2 MB | 294.1 MB | none |
| 23 | 7401 | 19254858 | 9.8 | 22983 | 253947 | 38288.2 MB | 294.1 MB | none |
| 24 | 7678 | 20023135 | 9.2 | 25609 | 1006909 | 38288.2 MB | 294.1 MB | none |
| 25 | 7975 | 20735165 | 9.9 | 23734 | 305429 | 38288.3 MB | 294.1 MB | none |
| 26 | 8275 | 21506908 | 10.0 | 25725 | 1086184 | 38288.3 MB | 294.1 MB | none |

### PG plan (first page of the pid stream, before the sweep)

```
Nested Loop  (cost=0.83..541.18 rows=100 width=110) (actual time=0.071..1.976 rows=100 loops=1)
  Buffers: shared hit=854 read=3
  ->  Limit  (cost=0.41..31.52 rows=100 width=30) (actual time=0.005..0.051 rows=100 loops=1)
        Buffers: shared hit=6 read=1
        ->  Group  (cost=0.41..2361.84 rows=7592 width=30) (actual time=0.005..0.042 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=6 read=1
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2251.16 rows=44271 width=30) (actual time=0.004..0.025 rows=407 loops=1)
                    Index Cond: (pid > ''::text)
                    Heap Fetches: 0
                    Buffers: shared hit=6 read=1
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.019..0.019 rows=1 loops=100)
        Buffers: shared hit=848 read=2
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.44 rows=6 width=80) (actual time=0.019..0.019 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=848 read=2
Planning:
  Buffers: shared hit=52 read=4
Planning Time: 0.841 ms
Execution Time: 1.988 ms
```

### PG plan (cleanup of the first snapshotted corpus pid, before the sweep; deletes rolled back)

pid thing:bench.ns01:pid-00000055: min event sn 1, min snapshot sn 500, snapshot sn 3000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.61..0.62 rows=1 width=8) (actual time=0.017..0.017 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.61 rows=1 width=8) (actual time=0.015..0.015 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..115.48 rows=2346 width=8) (actual time=0.015..0.015 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.065 ms
Execution Time: 0.025 ms
```
```
Delete on things_journal  (cost=0.56..2539.73 rows=0 width=0) (actual time=1.928..1.928 rows=0 loops=1)
  Buffers: shared hit=120 read=14 dirtied=15
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2539.73 rows=629 width=6) (actual time=0.165..1.866 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=7 read=14
Planning:
  Buffers: shared hit=6
Planning Time: 0.032 ms
Execution Time: 2.782 ms
```
```
Result  (cost=1.23..1.24 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
  Buffers: shared hit=4
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.41..1.23 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=4
          ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..4.51 rows=5 width=8) (actual time=0.006..0.006 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=4
Planning Time: 0.035 ms
Execution Time: 0.012 ms
```
```
Delete on things_snaps  (cost=0.41..8.44 rows=0 width=0) (actual time=0.049..0.049 rows=0 loops=1)
  Buffers: shared hit=18 dirtied=4
  ->  Index Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.44 rows=1 width=6) (actual time=0.005..0.005 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 500) AND (sn <= 599))
        Buffers: shared hit=4
Planning Time: 0.024 ms
Execution Time: 0.060 ms
```


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
