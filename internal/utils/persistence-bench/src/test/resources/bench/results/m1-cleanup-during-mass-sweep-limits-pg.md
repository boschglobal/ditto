# M1 — live traffic during the mass cleanup sweep (loaded backlog, no VACUUM FULL before it)

- generated: 2026-10-21T20:30:55.214388Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

- churn: 500/s target, 8 workers x 100 pid(s)/worker, 600 s, 30 s windows; sweep: one unthrottled pass over the whole cleanup backlog (C3's sweep), started with the churn and run to its end
- churn pid prefix thing:bench.churn-m1m; the churn has W3's shape, so the same backend's w3-sustained-churn evidence is the no-sweep control

## PostgreSQL

- backlog before the run: 42225 snapshot rows (the corpus generates 44271)
- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 3.0 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

- churn: 299980 ops (+0 snapshots), achieved 500.0/s over 600 s
- mass sweep: 8582 pids, 21054465 event + 33643 snapshot rows deleted, 378586 delete batches, 890.2 s (23690 rows/s); started 2026-10-21T20:30:58.542675Z, finished 2026-10-21T20:45:48.695675Z = 890 s after the churn started (the churn ran 600 s)
- churn pids swept by the mass sweep: 0 (expected 0)
- churn windows that ended while the mass sweep was still deleting: windows 0–19 (20 × 30 s): achieved 500.0/s; window p50 2.57–4.66 ms, p95 12.30–36.72 ms
- churn windows after the mass sweep had finished: no complete window

Churn windows (on the churn's clock):

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14832 | 3.30 | 12.30 | 190.31 | 0.0 MB | 38319.4 MB | 294.1 MB | 1173487 |
| 1 | 15244 | 2.97 | 19.80 | 409.78 | 1236.7 MB | 38320.5 MB | 294.1 MB | 2068919 |
| 2 | 15012 | 3.38 | 33.60 | 484.92 | 717.2 MB | 38321.4 MB | 294.1 MB | 2590380 |
| 3 | 15008 | 3.42 | 28.18 | 514.55 | 976.6 MB | 38321.4 MB | 294.1 MB | 266801 |
| 4 | 15016 | 2.57 | 14.74 | 274.05 | 1246.0 MB | 38321.4 MB | 294.1 MB | 1377492 |
| 5 | 15008 | 3.23 | 29.74 | 706.35 | 917.5 MB | 38321.4 MB | 294.1 MB | 1993782 |
| 6 | 15008 | 3.34 | 31.02 | 401.52 | 812.3 MB | 38321.4 MB | 294.1 MB | 705679 |
| 7 | 15016 | 3.56 | 23.67 | 250.49 | 990.7 MB | 38321.4 MB | 294.1 MB | 299180 |
| 8 | 15016 | 3.43 | 25.63 | 368.94 | 803.2 MB | 38321.4 MB | 294.1 MB | 1004993 |
| 9 | 14952 | 3.53 | 26.10 | 483.46 | 976.6 MB | 38321.4 MB | 294.1 MB | 1685444 |
| 10 | 15064 | 2.58 | 17.63 | 397.12 | 1248.6 MB | 38321.4 MB | 294.1 MB | 1002081 |
| 11 | 14992 | 3.25 | 27.91 | 409.78 | 875.9 MB | 38321.4 MB | 294.1 MB | 1726286 |
| 12 | 15029 | 3.61 | 36.72 | 576.87 | 840.9 MB | 38321.4 MB | 294.1 MB | 416327 |
| 13 | 14987 | 3.16 | 18.42 | 327.80 | 1229.7 MB | 38321.4 MB | 294.1 MB | 194516 |
| 14 | 15022 | 3.61 | 23.52 | 443.42 | 735.1 MB | 38321.4 MB | 294.1 MB | 855539 |
| 15 | 15026 | 3.69 | 26.58 | 546.12 | 793.1 MB | 38321.4 MB | 294.1 MB | 1398751 |
| 16 | 15008 | 4.66 | 26.02 | 445.18 | 699.7 MB | 38321.4 MB | 294.1 MB | 659424 |
| 17 | 15008 | 3.80 | 29.13 | 429.99 | 819.4 MB | 38321.4 MB | 294.1 MB | 1202646 |
| 18 | 15008 | 4.03 | 25.35 | 341.13 | 716.9 MB | 38321.4 MB | 294.1 MB | 924448 |
| 19 | 14724 | 3.35 | 26.78 | 435.06 | 829.0 MB | 38321.4 MB | 294.1 MB | 1494099 |

Mass sweep windows (on the sweeper's clock, which starts just before the churn's):

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 387 | 970958 | 12.9 | 32365 | 1150755 | 38319.4 MB | 294.1 MB | none |
| 1 | 819 | 1885094 | 14.4 | 30471 | 2068919 | 38320.5 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 2 | 1022 | 2402159 | 6.8 | 17236 | 2590380 | 38321.4 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 3 | 1279 | 3007460 | 8.6 | 20177 | 246968 | 38321.4 MB | 294.1 MB | none |
| 4 | 1742 | 4112535 | 15.4 | 36836 | 1357838 | 38321.4 MB | 294.1 MB | none |
| 5 | 1967 | 4739045 | 7.5 | 20884 | 1993782 | 38321.4 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 6 | 2166 | 5314603 | 6.6 | 19185 | 705679 | 38321.4 MB | 294.1 MB | none |
| 7 | 2486 | 5984932 | 10.7 | 22344 | 299180 | 38321.4 MB | 294.1 MB | none |
| 8 | 2820 | 6691744 | 11.1 | 23560 | 1004993 | 38321.4 MB | 294.1 MB | none |
| 9 | 3105 | 7368536 | 9.5 | 22560 | 1685444 | 38321.4 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 10 | 3501 | 8434354 | 13.2 | 35527 | 1002081 | 38321.4 MB | 294.1 MB | none |
| 11 | 3713 | 9131939 | 7.1 | 23253 | 1710766 | 38321.4 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 12 | 3931 | 9661151 | 7.3 | 17640 | 400603 | 38321.4 MB | 294.1 MB | none |
| 13 | 4228 | 10525497 | 9.9 | 28812 | 194516 | 38321.4 MB | 294.1 MB | none |
| 14 | 4521 | 11175305 | 9.8 | 21660 | 836491 | 38321.4 MB | 294.1 MB | none |
| 15 | 4767 | 11728122 | 8.2 | 18427 | 1398751 | 38321.4 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 16 | 5029 | 12258375 | 8.7 | 17675 | 642907 | 38321.4 MB | 294.1 MB | none |
| 17 | 5273 | 12823609 | 8.1 | 18841 | 1202646 | 38321.4 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 18 | 5479 | 13414191 | 6.9 | 19686 | 908436 | 38321.4 MB | 294.1 MB | none |
| 19 | 5717 | 14010307 | 7.9 | 19871 | 1494099 | 38321.4 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 20 | 5988 | 14741826 | 9.0 | 24384 | 835220 | 38321.4 MB | 294.1 MB | none |
| 21 | 6239 | 15362775 | 8.4 | 20698 | 1450076 | 38321.4 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 22 | 6527 | 16054580 | 9.6 | 23060 | 696001 | 38321.4 MB | 294.1 MB | none |
| 23 | 6779 | 16763996 | 8.4 | 23647 | 1419631 | 38321.4 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 24 | 7143 | 17702342 | 12.1 | 31278 | 1277532 | 38321.4 MB | 294.1 MB | none |
| 25 | 7578 | 18720715 | 14.5 | 33946 | 2328740 | 38321.4 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 26 | 7807 | 19328377 | 7.6 | 20255 | 581003 | 38321.4 MB | 294.1 MB | none |
| 27 | 8081 | 20015806 | 9.1 | 22914 | 1277833 | 38321.4 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 28 | 8376 | 20654589 | 9.8 | 21293 | 929264 | 38321.4 MB | 294.1 MB | none |

### PG plan (C1-shaped page at the start of the pid stream, before the sweep and the churn started)

```
Nested Loop  (cost=0.83..553.66 rows=100 width=109) (actual time=0.043..1.600 rows=100 loops=1)
  Buffers: shared hit=857
  ->  Limit  (cost=0.41..30.51 rows=100 width=29) (actual time=0.005..0.045 rows=100 loops=1)
        Buffers: shared hit=7
        ->  Group  (cost=0.41..2324.91 rows=7724 width=29) (actual time=0.005..0.041 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=7
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2219.35 rows=42225 width=29) (actual time=0.004..0.023 rows=407 loops=1)
                    Index Cond: (pid > ''::text)
                    Heap Fetches: 0
                    Buffers: shared hit=7
  ->  Limit  (cost=0.41..5.22 rows=1 width=80) (actual time=0.015..0.015 rows=1 loops=100)
        Buffers: shared hit=850
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..24.45 rows=5 width=80) (actual time=0.015..0.015 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=850
Planning:
  Buffers: shared hit=47
Planning Time: 0.139 ms
Execution Time: 1.613 ms
```

### PG plan (cleanup of the first snapshotted corpus pid, before the sweep and the churn started; deletes rolled back)

pid thing:bench.ns01:pid-00000055: min event sn 1, min snapshot sn 500, snapshot sn 3000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.64..0.65 rows=1 width=8) (actual time=0.015..0.015 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.64 rows=1 width=8) (actual time=0.014..0.014 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..169.37 rows=2256 width=8) (actual time=0.013..0.013 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.044 ms
Execution Time: 0.021 ms
```
```
Delete on things_journal  (cost=0.56..2487.59 rows=0 width=0) (actual time=1.088..1.088 rows=0 loops=1)
  Buffers: shared hit=121 read=13 dirtied=14
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2487.59 rows=617 width=6) (actual time=0.053..1.013 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=8 read=13
Planning:
  Buffers: shared hit=6
Planning Time: 0.042 ms
Execution Time: 1.226 ms
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
Planning Time: 0.037 ms
Execution Time: 0.013 ms
```
```
Delete on things_snaps  (cost=0.41..8.44 rows=0 width=0) (actual time=0.354..0.354 rows=0 loops=1)
  Buffers: shared hit=16 read=2 dirtied=3
  ->  Index Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.44 rows=1 width=6) (actual time=0.005..0.005 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00000055'::text) AND (sn >= 500) AND (sn <= 599))
        Buffers: shared hit=4
Planning Time: 0.025 ms
Execution Time: 0.366 ms
```

### PG plan (churn append of thing:bench.churn-m1m:w0-p0 at sn 1, before the sweep and the churn started; rolled back)

```
Insert on things_journal  (cost=0.00..0.01 rows=0 width=0) (actual time=0.223..0.223 rows=0 loops=1)
  Buffers: shared hit=24 read=1 dirtied=3
  ->  Result  (cost=0.00..0.01 rows=1 width=152) (actual time=0.117..0.117 rows=1 loops=1)
        Buffers: shared hit=10 dirtied=1
Planning Time: 0.030 ms
Execution Time: 0.238 ms
```
```
Insert on things_journal_seq  (cost=0.00..0.01 rows=0 width=0) (actual time=0.086..0.086 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_journal_seq_pkey
  Tuples Inserted: 1
  Conflicting Tuples: 0
  Buffers: shared hit=13 dirtied=1
  ->  Result  (cost=0.00..0.01 rows=1 width=48) (actual time=0.000..0.001 rows=1 loops=1)
Planning:
  Buffers: shared hit=2
Planning Time: 0.024 ms
Execution Time: 0.115 ms
```

