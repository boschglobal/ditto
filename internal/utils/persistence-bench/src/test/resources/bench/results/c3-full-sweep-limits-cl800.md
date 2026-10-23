# C3 — full cleanup sweep

- generated: 2026-10-23T01:20:48.422833Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cl800

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 3.1 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 976.9 s (22686 rows/s), finished at 2026-10-23T01:37:08.731145Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 357 | 906991 | 11.9 | 30233 | 905891 | 38287.8 MB | 294.1 MB | none |
| 1 | 721 | 1780209 | 12.1 | 29107 | 1754793 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 517128/4641592, index passes 0) |
| 2 | 1073 | 2605347 | 11.7 | 27505 | 2579633 | 38287.8 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 3 | 1371 | 3437621 | 9.9 | 27742 | 975503 | 38287.8 MB | 294.1 MB | none |
| 4 | 1752 | 4362202 | 12.7 | 30819 | 1903031 | 38287.8 MB | 294.1 MB | none |
| 5 | 2036 | 5166740 | 9.5 | 26818 | 2721274 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 1638519/4641592, index passes 0) |
| 6 | 2267 | 5765982 | 7.7 | 19975 | 3321196 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 2612102/4641592, index passes 0) |
| 7 | 2570 | 6453341 | 10.1 | 22912 | 4000604 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 3767394/4641592, index passes 0) |
| 8 | 2886 | 7161530 | 10.5 | 23606 | 4693878 | 38287.8 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 9 | 3137 | 7826542 | 8.4 | 22167 | 5360234 | 38287.9 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 10 | 3379 | 8516853 | 8.1 | 23010 | 6052709 | 38287.9 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 11 | 3616 | 9129602 | 7.9 | 20425 | 6667681 | 38287.9 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 12 | 3813 | 9774397 | 6.6 | 21493 | 192275 | 38287.9 MB | 294.1 MB | none |
| 13 | 4120 | 10544614 | 10.2 | 25674 | 5539721 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 318171/4641592, index passes 0) |
| 14 | 4332 | 11140488 | 7.1 | 19862 | 6139390 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 964118/4641592, index passes 0) |
| 15 | 4624 | 11849621 | 9.7 | 23638 | 6847754 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 1952752/4641592, index passes 0) |
| 16 | 4840 | 12451891 | 7.2 | 20076 | 7434499 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 2641134/4641592, index passes 0) |
| 17 | 5118 | 13111448 | 9.3 | 21985 | 8095149 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 3295028/4641592, index passes 0) |
| 18 | 5345 | 13690451 | 7.6 | 19300 | 8678966 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 3996671/4641592, index passes 0) |
| 19 | 5571 | 14339597 | 7.5 | 21638 | 9327869 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 4632362/4641592, index passes 0) |
| 20 | 5858 | 15026186 | 9.6 | 22886 | 10018768 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 21 | 6032 | 15658007 | 5.8 | 21061 | 10643081 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 22 | 6240 | 16233399 | 6.9 | 19180 | 11208575 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 23 | 6519 | 16860822 | 9.3 | 20914 | 11844508 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 24 | 6669 | 17322929 | 5.0 | 15404 | 12308915 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 25 | 6912 | 18026437 | 8.1 | 23450 | 13013481 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 26 | 7168 | 18738892 | 8.5 | 23749 | 2289354 | 38288.1 MB | 294.1 MB | none |
| 27 | 7473 | 19484873 | 10.2 | 24866 | 3047262 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 162069/4641592, index passes 0) |
| 28 | 7725 | 20158112 | 8.4 | 22441 | 3729928 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 781977/4641592, index passes 0) |
| 29 | 7946 | 20682912 | 7.4 | 17493 | 4252729 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 1324837/4641592, index passes 0) |
| 30 | 8204 | 21294525 | 8.6 | 20387 | 4854836 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 1755785/4641592, index passes 0) |
| 31 | 8465 | 21864742 | 8.7 | 19007 | 5418015 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 2165652/4641592, index passes 0) |

### PG plan (first page of the pid stream, before the sweep)

```
Nested Loop  (cost=0.83..540.80 rows=100 width=110) (actual time=0.059..1.973 rows=100 loops=1)
  Buffers: shared hit=854 read=3
  ->  Limit  (cost=0.41..31.00 rows=100 width=30) (actual time=0.005..0.048 rows=100 loops=1)
        Buffers: shared hit=6 read=1
        ->  Group  (cost=0.41..2361.84 rows=7720 width=30) (actual time=0.004..0.045 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=6 read=1
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2251.16 rows=44271 width=30) (actual time=0.004..0.026 rows=407 loops=1)
                    Index Cond: (pid > ''::text)
                    Heap Fetches: 0
                    Buffers: shared hit=6 read=1
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.019..0.019 rows=1 loops=100)
        Buffers: shared hit=848 read=2
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.45 rows=6 width=80) (actual time=0.019..0.019 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=848 read=2
Planning:
  Buffers: shared hit=52 read=4
Planning Time: 0.718 ms
Execution Time: 1.986 ms
```

### PG plan (cleanup of the first snapshotted corpus pid, before the sweep; deletes rolled back)

pid thing:bench.ns01:pid-00000055: min event sn 1, min snapshot sn 500, snapshot sn 3000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.61..0.62 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.61 rows=1 width=8) (actual time=0.010..0.010 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..115.34 rows=2339 width=8) (actual time=0.009..0.010 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.047 ms
Execution Time: 0.017 ms
```
```
Delete on things_journal  (cost=0.56..2473.38 rows=0 width=0) (actual time=1.182..1.183 rows=0 loops=1)
  Buffers: shared hit=120 read=14 dirtied=15
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2473.38 rows=612 width=6) (actual time=0.183..1.125 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=7 read=14
Planning:
  Buffers: shared hit=6
Planning Time: 0.044 ms
Execution Time: 1.928 ms
```
```
Result  (cost=1.23..1.24 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
  Buffers: shared hit=4
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.41..1.23 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Buffers: shared hit=4
          ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..4.51 rows=5 width=8) (actual time=0.008..0.008 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=4
Planning Time: 0.040 ms
Execution Time: 0.015 ms
```
```
Delete on things_snaps  (cost=0.41..8.44 rows=0 width=0) (actual time=0.067..0.068 rows=0 loops=1)
  Buffers: shared hit=18 dirtied=4
  ->  Index Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.44 rows=1 width=6) (actual time=0.004..0.004 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 500) AND (sn <= 599))
        Buffers: shared hit=4
Planning Time: 0.018 ms
Execution Time: 0.078 ms
```


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
