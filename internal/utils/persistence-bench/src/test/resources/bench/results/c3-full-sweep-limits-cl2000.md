# C3 — full cleanup sweep

- generated: 2026-10-22T22:49:17.436423Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cl2000

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 4.7 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 976.4 s (22699 rows/s), finished at 2026-10-22T23:05:38.790336Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 400 | 990973 | 13.3 | 33032 | 990373 | 38287.8 MB | 294.1 MB | none |
| 1 | 802 | 1916924 | 13.4 | 30865 | 1894806 | 38287.8 MB | 294.1 MB | none |
| 2 | 1073 | 2631608 | 9.0 | 23823 | 2616947 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 987386/4641592, index passes 0) |
| 3 | 1330 | 3297724 | 8.6 | 22204 | 3286917 | 38287.9 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 4 | 1583 | 3876755 | 8.4 | 19301 | 721818 | 38287.9 MB | 294.1 MB | none |
| 5 | 1926 | 4851833 | 11.4 | 32503 | 1709856 | 38287.9 MB | 294.1 MB | none |
| 6 | 2170 | 5600946 | 8.1 | 24970 | 2462402 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 3455607/4641592, index passes 0) |
| 7 | 2486 | 6267073 | 10.5 | 22204 | 3133841 | 38287.9 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 8 | 2721 | 6846132 | 7.8 | 19302 | 170076 | 38287.9 MB | 294.1 MB | none |
| 9 | 3050 | 7638852 | 11.0 | 26424 | 2465451 | 38287.9 MB | 294.1 MB | none |
| 10 | 3260 | 8238630 | 7.0 | 19993 | 3049397 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 1458620/4641592, index passes 0) |
| 11 | 3501 | 8811587 | 8.0 | 19099 | 3622380 | 38287.9 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 12 | 3674 | 9382182 | 5.8 | 19020 | 4195419 | 38288.0 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 13 | 3905 | 9969772 | 7.7 | 19586 | 1000082 | 38288.0 MB | 294.1 MB | none |
| 14 | 4162 | 10704958 | 8.6 | 24506 | 1741164 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 3659342/4641592, index passes 0) |
| 15 | 4396 | 11315311 | 7.8 | 20345 | 2351618 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 16 | 4674 | 11993990 | 9.3 | 22623 | 1620631 | 38288.1 MB | 294.1 MB | none |
| 17 | 5003 | 12856672 | 11.0 | 28756 | 2477193 | 38288.1 MB | 294.1 MB | none |
| 18 | 5265 | 13496027 | 8.7 | 21312 | 3133361 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 2253032/4641592, index passes 0) |
| 19 | 5477 | 14101813 | 7.1 | 20193 | 3740749 | 38288.1 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 20 | 5687 | 14618926 | 7.0 | 17237 | 4257493 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 21 | 5916 | 15224879 | 7.6 | 20198 | 593950 | 38288.1 MB | 294.1 MB | none |
| 22 | 6127 | 15875955 | 7.0 | 21703 | 1230706 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 2766080/4641592, index passes 0) |
| 23 | 6327 | 16450904 | 6.7 | 19165 | 1811468 | 38288.1 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 24 | 6564 | 16993389 | 7.9 | 18083 | 2353341 | 38288.2 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 25 | 6814 | 17767761 | 8.3 | 25812 | 2287812 | 38288.2 MB | 294.1 MB | none |
| 26 | 7108 | 18569368 | 9.8 | 26720 | 3093320 | 38288.2 MB | 294.1 MB | things_journal scanning heap (heap 3504611/4641592, index passes 0) |
| 27 | 7382 | 19220428 | 9.1 | 21702 | 3746781 | 38288.2 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 28 | 7618 | 19840885 | 7.9 | 20682 | 649934 | 38288.2 MB | 294.1 MB | none |
| 29 | 7915 | 20625266 | 9.9 | 26146 | 1437921 | 38288.2 MB | 294.1 MB | none |
| 30 | 8199 | 21288323 | 9.5 | 22102 | 2083354 | 38288.2 MB | 294.1 MB | things_journal scanning heap (heap 1823780/4641592, index passes 0) |
| 31 | 8454 | 21849937 | 8.5 | 18720 | 2649663 | 38288.2 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |

### PG plan (first page of the pid stream, before the sweep)

```
Nested Loop  (cost=0.83..541.33 rows=100 width=110) (actual time=0.028..1.074 rows=100 loops=1)
  Buffers: shared hit=857
  ->  Limit  (cost=0.41..31.39 rows=100 width=30) (actual time=0.005..0.043 rows=100 loops=1)
        Buffers: shared hit=7
        ->  Group  (cost=0.41..2361.84 rows=7624 width=30) (actual time=0.004..0.039 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=7
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2251.16 rows=44271 width=30) (actual time=0.003..0.025 rows=407 loops=1)
                    Index Cond: (pid > ''::text)
                    Heap Fetches: 0
                    Buffers: shared hit=7
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.010..0.010 rows=1 loops=100)
        Buffers: shared hit=850
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.46 rows=6 width=80) (actual time=0.010..0.010 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=850
Planning:
  Buffers: shared hit=56
Planning Time: 0.121 ms
Execution Time: 1.085 ms
```

### PG plan (cleanup of the first snapshotted corpus pid, before the sweep; deletes rolled back)

pid thing:bench.ns01:pid-00000055: min event sn 1, min snapshot sn 500, snapshot sn 3000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.62..0.63 rows=1 width=8) (actual time=0.010..0.011 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.62 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..119.84 rows=2364 width=8) (actual time=0.009..0.009 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.044 ms
Execution Time: 0.016 ms
```
```
Delete on things_journal  (cost=0.56..2545.07 rows=0 width=0) (actual time=7.118..7.118 rows=0 loops=1)
  Buffers: shared hit=120 read=14 dirtied=15
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2545.07 rows=631 width=6) (actual time=0.202..7.061 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=7 read=14
Planning:
  Buffers: shared hit=6
Planning Time: 0.036 ms
Execution Time: 8.317 ms
```
```
Result  (cost=1.23..1.24 rows=1 width=8) (actual time=0.010..0.010 rows=1 loops=1)
  Buffers: shared hit=4
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.41..1.23 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Buffers: shared hit=4
          ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..4.51 rows=5 width=8) (actual time=0.008..0.008 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=4
Planning Time: 0.050 ms
Execution Time: 0.017 ms
```
```
Delete on things_snaps  (cost=0.41..8.44 rows=0 width=0) (actual time=0.351..0.351 rows=0 loops=1)
  Buffers: shared hit=16 read=2 dirtied=4
  ->  Index Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.44 rows=1 width=6) (actual time=0.004..0.004 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 500) AND (sn <= 599))
        Buffers: shared hit=4
Planning Time: 0.021 ms
Execution Time: 0.361 ms
```


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
