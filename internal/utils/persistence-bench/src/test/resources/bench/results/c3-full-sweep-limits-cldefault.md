# C3 — full cleanup sweep

- generated: 2026-10-22T23:50:37.011020Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cldefault

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 2.9 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 776.5 s (28542 rows/s), finished at 2026-10-23T00:03:36.760146Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 367 | 925611 | 12.2 | 30854 | 925411 | 38287.8 MB | 294.1 MB | none |
| 1 | 744 | 1814630 | 12.6 | 29634 | 1784913 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 551477/4641592, index passes 0) |
| 2 | 1104 | 2699579 | 12.0 | 29498 | 2681756 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 1033934/4641592, index passes 0) |
| 3 | 1473 | 3631376 | 12.3 | 31060 | 3602085 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 1668344/4641592, index passes 0) |
| 4 | 1819 | 4518378 | 11.5 | 29567 | 4492380 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 2291653/4641592, index passes 0) |
| 5 | 2102 | 5403288 | 9.4 | 29497 | 5379305 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 2791765/4641592, index passes 0) |
| 6 | 2488 | 6269044 | 12.9 | 28859 | 6247262 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 3261107/4641592, index passes 0) |
| 7 | 2890 | 7167530 | 13.4 | 29950 | 7146390 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 3689692/4641592, index passes 0) |
| 8 | 3198 | 8023108 | 10.3 | 28519 | 8004047 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 4080641/4641592, index passes 0) |
| 9 | 3555 | 8936432 | 11.9 | 30444 | 8917306 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 4428102/4641592, index passes 0) |
| 10 | 3851 | 9841555 | 9.9 | 30171 | 9824836 | 38287.8 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 11 | 4163 | 10705957 | 10.4 | 28813 | 10689719 | 38287.8 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 12 | 4499 | 11537744 | 11.2 | 27726 | 11497577 | 38287.8 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 13 | 4781 | 12322151 | 9.4 | 26147 | 12281334 | 38287.8 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 14 | 5133 | 13146183 | 11.7 | 27468 | 13108781 | 38287.8 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 15 | 5454 | 14013047 | 10.7 | 28895 | 4879779 | 38287.8 MB | 294.1 MB | none |
| 16 | 5786 | 14842339 | 11.1 | 27643 | 5709390 | 38287.8 MB | 294.1 MB | none |
| 17 | 6030 | 15653307 | 8.1 | 27032 | 6531009 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 206214/4641592, index passes 0) |
| 18 | 6309 | 16411125 | 9.3 | 25261 | 7288876 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 401153/4641592, index passes 0) |
| 19 | 6624 | 17207085 | 10.5 | 26532 | 8076160 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 562761/4641592, index passes 0) |
| 20 | 6975 | 18182361 | 11.7 | 32509 | 9056838 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 769216/4641592, index passes 0) |
| 21 | 7268 | 19016148 | 9.8 | 27793 | 9891032 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 901526/4641592, index passes 0) |
| 22 | 7606 | 19814767 | 11.3 | 26621 | 10661302 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 1013514/4641592, index passes 0) |
| 23 | 7921 | 20636068 | 10.5 | 27377 | 11492212 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 1128909/4641592, index passes 0) |
| 24 | 8257 | 21468152 | 11.2 | 27736 | 12319974 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 1259964/4641592, index passes 0) |

### PG plan (first page of the pid stream, before the sweep)

```
Nested Loop  (cost=0.83..541.09 rows=100 width=110) (actual time=0.047..1.841 rows=100 loops=1)
  Buffers: shared hit=854 read=3
  ->  Limit  (cost=0.41..31.36 rows=100 width=30) (actual time=0.004..0.045 rows=100 loops=1)
        Buffers: shared hit=6 read=1
        ->  Group  (cost=0.41..2361.84 rows=7630 width=30) (actual time=0.004..0.042 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=6 read=1
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2251.16 rows=44271 width=30) (actual time=0.003..0.025 rows=407 loops=1)
                    Index Cond: (pid > ''::text)
                    Heap Fetches: 0
                    Buffers: shared hit=6 read=1
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.018..0.018 rows=1 loops=100)
        Buffers: shared hit=848 read=2
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.45 rows=6 width=80) (actual time=0.018..0.018 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=848 read=2
Planning:
  Buffers: shared hit=52 read=4
Planning Time: 0.729 ms
Execution Time: 1.853 ms
```

### PG plan (cleanup of the first snapshotted corpus pid, before the sweep; deletes rolled back)

pid thing:bench.ns01:pid-00000055: min event sn 1, min snapshot sn 500, snapshot sn 3000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.62..0.63 rows=1 width=8) (actual time=0.012..0.012 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.62 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..120.03 rows=2373 width=8) (actual time=0.011..0.011 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.040 ms
Execution Time: 0.017 ms
```
```
Delete on things_journal  (cost=0.56..2557.61 rows=0 width=0) (actual time=1.983..1.983 rows=0 loops=1)
  Buffers: shared hit=120 read=14 dirtied=15
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2557.61 rows=633 width=6) (actual time=0.322..1.919 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=7 read=14
Planning:
  Buffers: shared hit=6
Planning Time: 0.036 ms
Execution Time: 2.461 ms
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
Planning Time: 0.041 ms
Execution Time: 0.013 ms
```
```
Delete on things_snaps  (cost=0.41..8.44 rows=0 width=0) (actual time=0.038..0.038 rows=0 loops=1)
  Buffers: shared hit=18 dirtied=4
  ->  Index Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.44 rows=1 width=6) (actual time=0.005..0.005 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 500) AND (sn <= 599))
        Buffers: shared hit=4
Planning Time: 0.023 ms
Execution Time: 0.049 ms
```


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
