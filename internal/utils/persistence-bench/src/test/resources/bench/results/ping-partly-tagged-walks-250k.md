# Persistence ping on a partly tagged corpus: read-journal walks over 250,000 long-history things

- generated: 2026-10-22T04:40:00.218069Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged (load evidence: `load-pg-partly-tagged-250k.md`), schema v3
- backend: pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- evidence label: 250k
- client: the production read journal (`PostgresReadJournal` over `things_*`: the things service has no ping, the SQL is the policies ping's with another table name); connection options of `ConnectionPoolFactory.createDdlConnectionFactory` (`SHOW statement_timeout` through this client: `1min`), production pool limits; every statement timed by an r2dbc-proxy listener (execution until the last row is read); **no throttle**; read-journal-batch-size 500, maxIdleTime PT10M

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 35070304 | 35070304 | 0 | 1 | 0 | 2026-10-22 07:06:50.890118+03 | null |
| things_journal_seq | 250000 | 250000 | 0 | 1 | 0 | 2026-10-22 06:58:54.98001+03 | null |
| things_snaps | 44271 | 44271 | 0 | 1 | 0 | 2026-10-22 06:58:55.217422+03 | null |

## Ping tag mix

Tags `always-alive` + `priority-<n>` (n = 0–9 per pid) on the tagged events; group draw = generator stream 6, priority = stream 7; cluster = the last count/100 pids in pid order, from `thing:bench.ns48:pid-00185848`. Checksum = SHA-256 over the sorted pids, each followed by `\n`.

| group | pids | share | tagged events | journal rows of these pids | sha256 |
|---|---|---|---|---|---|
| CLUSTER | 2,500 | 1.0000 % | every event | 319,529 | `6fc077c773951297702d9167b5c666221f7387b665bbc94f256d333b44b49f44` |
| SPREAD | 24,727 | 9.8908 % | every event | 3,388,468 | `df54d607290651b8261856767224ad1cfff8a4c2cd4775012543ca42bf01ef32` |
| OLDER_ONLY | 2,317 | 0.9268 % | every event but the newest | 372,615 | `a1173e3105d6049affb678e2734366dae57b123abe1cc6dce7af92fcc4b4ebae` |
| NEWEST_ONLY | 2,494 | 0.9976 % | the newest event only | 355,693 | `c3021f5a3348155ed579101f0c84a97288b10c9774ae54ad7ef0bd6301a8ac54` |
| UNTAGGED | 217,962 | 87.1848 % | none | 30,633,999 | `2e6a10eccd545f3862b9f04467c9ac5f5608a4dc608e0fb54a127b62fad73eb8` |

| expected set | pids | sha256 |
|---|---|---|
| ID order, any tagged event; priority order; the policies ping | 32,038 | `5761a81d53cd3e99ab4fdfec2843cc9fc434e9cad37ea74fb3324f6f03617988` |
| ID order, newest event tagged | 29,721 | `39ef64837a2833e3d9346405e6ec9a3b986b28efd6e45f6d71600a856790f757` |

- tagged journal rows (expected): 4,080,789


## Walks

Order: strictly ascending pid order for the ID walks (the priority order is checked below). Percentiles: nearest rank over the statements of a walk, the final empty window excluded.

| walk | statements | delivered / expected | missing | extra | dup | order | walk time | p50 | p95 | p99 | max (statement #) | max / statement_timeout (1min) | host memory (Δ swap-outs, free % before→after) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ID order, any tagged event (the policies ping) | 501 | 32,038 / 32,038 | 0 | 0 | 0 | ok | 228.5 s | 464.50 ms | 713.69 ms | 893.88 ms | 1067.17 ms (#23) | 1.7786 % | +0, 65 → 71 % | RELIABLE |
| ID order, newest event tagged | 501 | 29,721 / 29,721 | 0 | 0 | 0 | ok | 21.2 s | 41.39 ms | 63.79 ms | 80.61 ms | 235.24 ms (#1) | 0.3921 % | +0, 71 → 77 % | RELIABLE |
| priority order (TAGS), one statement | 33 | 0 / 32,038 | 32038 | 0 | 0 | ok | 1202.1 s | 60029.58 ms | 60111.09 ms | 60111.14 ms | 60111.14 ms (#3) | 100.1852 % | +0, 77 → 68 % | RELIABLE |

### Median statement time per tenth of the walk (ms)

| walk | tenth 1 | tenth 2 | tenth 3 | tenth 4 | tenth 5 | tenth 6 | tenth 7 | tenth 8 | tenth 9 | tenth 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| ID order, any tagged event (the policies ping) | 386.35 | 486.48 | 516.86 | 411.86 | 445.13 | 483.25 | 490.49 | 464.50 | 484.14 | 435.62 |
| ID order, newest event tagged | 51.26 | 48.23 | 49.73 | 43.61 | 43.67 | 43.86 | 40.73 | 40.62 | 26.90 | 16.81 |
| priority order (TAGS), one statement | 60111.09 | 60028.57 | 60006.83 | 60022.14 | 60060.26 | 60011.38 | 60029.58 | 60008.60 | 60044.53 | 60036.05 |

### Five slowest windows: ID order, any tagged event (the policies ping)

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 23 | 1067.17 | 500 | 158,373 | 153,385 |
| 106 | 932.39 | 500 | 104,895 | 93,548 |
| 26 | 931.20 | 500 | 148,053 | 143,089 |
| 255 | 921.49 | 500 | 140,647 | 136,402 |
| 174 | 895.94 | 500 | 134,625 | 131,690 |

### Five slowest windows: ID order, newest event tagged

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 1 | 235.24 | 500 | 77,583 | 73,210 |
| 142 | 88.47 | 500 | 82,810 | 75,702 |
| 246 | 83.48 | 500 | 54,907 | 50,178 |
| 136 | 83.13 | 500 | 92,188 | 44,315 |
| 118 | 82.51 | 500 | 130,831 | 119,821 |

Priority order: 0 pids delivered above a pid of lower priority.


## EXPLAIN (ANALYZE, BUFFERS) of the walks' statements

Each statement as the walk recorded it, prepared on a JDBC connection with `plan_cache_mode = force_custom_plan` and executed with the bind values of the window: window 0, the middle window, the first window of the clustered block and the window with the most journal rows (generator).

### ID order, any tagged event (the policies ping): window 0, cursor `''`, 77,583 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..73262.36 rows=500 width=31) (actual time=46.893..191.649 rows=500 loops=1)
  Buffers: shared hit=5608 read=10094
  ->  Limit  (cost=1000.59..55179.09 rows=500 width=30) (actual time=46.777..49.260 rows=500 loops=1)
        Buffers: shared hit=1813 read=387
        ->  Unique  (cost=1000.59..1464470.28 rows=13506 width=30) (actual time=46.772..49.236 rows=500 loops=1)
              Buffers: shared hit=1813 read=387
              ->  Gather Merge  (cost=1000.59..1464402.75 rows=27012 width=30) (actual time=46.772..49.186 rows=662 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1813 read=387
                    ->  Unique  (cost=0.56..1460284.87 rows=13506 width=30) (actual time=0.048..35.367 rows=977 loops=3)
                          Buffers: shared hit=1813 read=387
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423753.31 rows=14612627 width=30) (actual time=0.047..33.162 rows=97021 loops=3)
                                Index Cond: (pid > ''::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1813 read=387
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10503.02 rows=295 width=0) (actual time=0.284..0.284 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 146
          Buffers: shared hit=3795 read=9707
Planning:
  Buffers: shared hit=100 read=11
Planning Time: 4.433 ms
Execution Time: 191.749 ms
```

### ID order, any tagged event (the policies ping): window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..46939.21 rows=500 width=31) (actual time=9.324..132.262 rows=500 loops=1)
  Buffers: shared hit=5632 read=6387
  ->  Limit  (cost=1000.59..28855.94 rows=500 width=30) (actual time=8.679..11.086 rows=500 loops=1)
        Buffers: shared hit=2330
        ->  Unique  (cost=1000.59..753429.44 rows=13506 width=30) (actual time=8.679..11.068 rows=500 loops=1)
              Buffers: shared hit=2330
              ->  Gather Merge  (cost=1000.59..753361.91 rows=27012 width=30) (actual time=8.679..11.015 rows=599 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=2330
                    ->  Unique  (cost=0.56..749244.03 rows=13506 width=30) (actual time=0.043..15.730 rows=879 loops=3)
                          Buffers: shared hit=2330
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..730500.58 rows=7497379 width=30) (actual time=0.043..13.356 rows=102437 loops=3)
                                Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=2330
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10503.02 rows=295 width=0) (actual time=0.242..0.242 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 97
          Buffers: shared hit=3302 read=6387
Planning Time: 0.264 ms
Execution Time: 132.316 ms
```

### ID order, any tagged event (the policies ping): window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Subquery Scan on w  (cost=0.56..18930.87 rows=500 width=31) (actual time=0.079..24.760 rows=500 loops=1)
  Buffers: shared hit=2721 read=224
  ->  Limit  (cost=0.56..847.60 rows=500 width=30) (actual time=0.060..21.914 rows=500 loops=1)
        Buffers: shared hit=222 read=223
        ->  Result  (cost=0.56..22880.72 rows=13506 width=30) (actual time=0.059..21.897 rows=500 loops=1)
              Buffers: shared hit=222 read=223
              ->  Unique  (cost=0.56..22880.72 rows=13506 width=30) (actual time=0.058..21.867 rows=500 loops=1)
                    Buffers: shared hit=222 read=223
                    ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..21712.18 rows=467417 width=30) (actual time=0.057..20.476 rows=49912 loops=1)
                          Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                          Heap Fetches: 0
                          Buffers: shared hit=222 read=223
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10503.02 rows=295 width=0) (actual time=0.005..0.005 rows=1 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Buffers: shared hit=2499 read=1
Planning:
  Buffers: shared hit=5
Planning Time: 0.239 ms
Execution Time: 24.791 ms
```

### ID order, any tagged event (the policies ping): window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..71096.75 rows=500 width=31) (actual time=18.406..790.018 rows=500 loops=1)
  Buffers: shared hit=8399 read=20506
  ->  Limit  (cost=1000.59..53013.48 rows=500 width=30) (actual time=16.447..18.429 rows=500 loops=1)
        Buffers: shared hit=3409
        ->  Unique  (cost=1000.59..1405972.83 rows=13506 width=30) (actual time=16.447..18.409 rows=500 loops=1)
              Buffers: shared hit=3409
              ->  Gather Merge  (cost=1000.59..1405905.30 rows=27012 width=30) (actual time=16.446..18.356 rows=573 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=3409
                    ->  Unique  (cost=0.56..1401787.42 rows=13506 width=30) (actual time=0.039..21.749 rows=878 loops=3)
                          Buffers: shared hit=3409
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366719.29 rows=14027251 width=30) (actual time=0.039..17.379 rows=154679 loops=3)
                                Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=3409
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10503.02 rows=295 width=0) (actual time=1.543..1.543 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 307
          Buffers: shared hit=4990 read=20506
Planning Time: 0.816 ms
Execution Time: 790.060 ms
```

### ID order, newest event tagged: window 0, cursor `''`, 77,583 journal rows (generator)

```
Nested Loop  (cost=1001.15..57493.63 rows=500 width=31) (actual time=8.960..12.719 rows=500 loops=1)
  Buffers: shared hit=3645 read=1
  ->  Limit  (cost=1000.59..55179.09 rows=500 width=30) (actual time=8.946..10.333 rows=500 loops=1)
        Buffers: shared hit=1146
        ->  Unique  (cost=1000.59..1464470.28 rows=13506 width=30) (actual time=8.945..10.312 rows=500 loops=1)
              Buffers: shared hit=1146
              ->  Gather Merge  (cost=1000.59..1464402.75 rows=27012 width=30) (actual time=8.945..10.279 rows=569 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1146
                    ->  Unique  (cost=0.56..1460284.87 rows=13506 width=30) (actual time=1.075..6.694 rows=484 loops=3)
                          Buffers: shared hit=1146
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423753.31 rows=14612627 width=30) (actual time=1.074..5.623 rows=50901 loops=3)
                                Index Cond: (pid > ''::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1146
  ->  Limit  (cost=0.56..4.60 rows=1 width=25) (actual time=0.005..0.005 rows=1 loops=500)
        Buffers: shared hit=2499 read=1
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10470.55 rows=2597 width=25) (actual time=0.005..0.005 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2499 read=1
Planning:
  Buffers: shared hit=12
Planning Time: 0.151 ms
Execution Time: 12.748 ms
```

### ID order, newest event tagged: window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Nested Loop  (cost=1001.15..31170.48 rows=500 width=31) (actual time=6.864..10.432 rows=500 loops=1)
  Buffers: shared hit=3281
  ->  Limit  (cost=1000.59..28855.94 rows=500 width=30) (actual time=6.840..7.948 rows=500 loops=1)
        Buffers: shared hit=781
        ->  Unique  (cost=1000.59..753429.44 rows=13506 width=30) (actual time=6.840..7.926 rows=500 loops=1)
              Buffers: shared hit=781
              ->  Gather Merge  (cost=1000.59..753361.91 rows=27012 width=30) (actual time=6.840..7.888 rows=599 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=781
                    ->  Unique  (cost=0.56..749244.03 rows=13506 width=30) (actual time=0.019..5.163 rows=371 loops=3)
                          Buffers: shared hit=781
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..730500.58 rows=7497379 width=30) (actual time=0.018..4.395 rows=33153 loops=3)
                                Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=781
  ->  Limit  (cost=0.56..4.60 rows=1 width=25) (actual time=0.005..0.005 rows=1 loops=500)
        Buffers: shared hit=2500
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10470.55 rows=2597 width=25) (actual time=0.005..0.005 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2500
Planning Time: 0.073 ms
Execution Time: 10.455 ms
```

### ID order, newest event tagged: window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Nested Loop  (cost=1.13..3162.14 rows=500 width=31) (actual time=0.058..13.973 rows=500 loops=1)
  Buffers: shared hit=2656 read=289
  ->  Limit  (cost=0.56..847.60 rows=500 width=30) (actual time=0.020..4.274 rows=500 loops=1)
        Buffers: shared hit=445
        ->  Result  (cost=0.56..22880.72 rows=13506 width=30) (actual time=0.019..4.258 rows=500 loops=1)
              Buffers: shared hit=445
              ->  Unique  (cost=0.56..22880.72 rows=13506 width=30) (actual time=0.019..4.226 rows=500 loops=1)
                    Buffers: shared hit=445
                    ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..21712.18 rows=467417 width=30) (actual time=0.019..3.066 rows=49912 loops=1)
                          Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                          Heap Fetches: 0
                          Buffers: shared hit=445
  ->  Limit  (cost=0.56..4.60 rows=1 width=25) (actual time=0.019..0.019 rows=1 loops=500)
        Buffers: shared hit=2211 read=289
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10470.55 rows=2597 width=25) (actual time=0.019..0.019 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2211 read=289
Planning:
  Buffers: shared hit=5
Planning Time: 0.129 ms
Execution Time: 14.004 ms
```

### ID order, newest event tagged: window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Nested Loop  (cost=1001.15..55328.02 rows=500 width=31) (actual time=8.900..14.313 rows=500 loops=1)
  Buffers: shared hit=4137 read=1
  ->  Limit  (cost=1000.59..53013.48 rows=500 width=30) (actual time=8.882..11.132 rows=500 loops=1)
        Buffers: shared hit=1638
        ->  Unique  (cost=1000.59..1405972.83 rows=13506 width=30) (actual time=8.882..11.114 rows=500 loops=1)
              Buffers: shared hit=1638
              ->  Gather Merge  (cost=1000.59..1405905.30 rows=27012 width=30) (actual time=8.882..11.053 rows=637 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1638
                    ->  Unique  (cost=0.56..1401787.42 rows=13506 width=30) (actual time=0.020..7.226 rows=401 loops=3)
                          Buffers: shared hit=1638
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366719.29 rows=14027251 width=30) (actual time=0.020..5.502 rows=73513 loops=3)
                                Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1638
  ->  Limit  (cost=0.56..4.60 rows=1 width=25) (actual time=0.006..0.006 rows=1 loops=500)
        Buffers: shared hit=2499 read=1
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10470.55 rows=2597 width=25) (actual time=0.006..0.006 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2499 read=1
Planning Time: 0.102 ms
Execution Time: 14.370 ms
```

### priority order (TAGS), one statement

```
Sort  (cost=5345271.84..5345305.60 rows=13506 width=34) (actual time=69710.840..69712.007 rows=32038 loops=1)
  Sort Key: (COALESCE((max(("substring"(tag.tag, '^priority-([0-9]+)$'::text))::integer)), 0)) DESC
  Sort Method: quicksort  Memory: 2771kB
  Buffers: shared hit=446499 read=4377998
  ->  Nested Loop Left Join  (cost=4856326.62..5344345.24 rows=13506 width=34) (actual time=68828.867..69705.720 rows=32038 loops=1)
        Buffers: shared hit=446496 read=4377998
        ->  Nested Loop  (cost=4856326.47..5344007.30 rows=13506 width=47) (actual time=68828.325..69695.949 rows=32038 loops=1)
              Buffers: shared hit=446496 read=4377998
              ->  Unique  (cost=4856325.90..4856460.96 rows=13506 width=30) (actual time=68827.851..68834.846 rows=32038 loops=1)
                    Buffers: shared hit=290147 read=4373783
                    ->  Sort  (cost=4856325.90..4856393.43 rows=27012 width=30) (actual time=68827.838..68830.126 rows=33539 loops=1)
                          Sort Key: things_journal.pid COLLATE "C"
                          Sort Method: quicksort  Memory: 2847kB
                          Buffers: shared hit=290147 read=4373783
                          ->  Gather  (cost=4851501.38..4854337.64 rows=27012 width=30) (actual time=68813.870..68816.926 rows=33539 loops=1)
                                Workers Planned: 2
                                Workers Launched: 2
                                Buffers: shared hit=290147 read=4373783
                                ->  HashAggregate  (cost=4850501.38..4850636.44 rows=13506 width=30) (actual time=68799.913..68800.969 rows=11180 loops=3)
                                      Group Key: things_journal.pid
                                      Batches: 1  Memory Usage: 1425kB
                                      Buffers: shared hit=290147 read=4373783
                                      Worker 0:  Batches: 1  Memory Usage: 1425kB
                                      Worker 1:  Batches: 1  Memory Usage: 1425kB
                                      ->  Parallel Seq Scan on things_journal  (cost=0.00..4846353.83 rows=1659020 width=30) (actual time=123.289..68525.578 rows=1360263 loops=3)
                                            Filter: (tags @> '{always-alive}'::text[])
                                            Rows Removed by Filter: 10329838
                                            Buffers: shared hit=290147 read=4373783
              ->  Limit  (cost=0.56..36.08 rows=1 width=25) (actual time=0.027..0.027 rows=1 loops=32038)
                    Buffers: shared hit=156349 read=4215
                    ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10477.05 rows=295 width=25) (actual time=0.027..0.027 rows=1 loops=32038)
                          Index Cond: (pid = things_journal.pid)
                          Filter: (tags @> '{always-alive}'::text[])
                          Rows Removed by Filter: 0
                          Buffers: shared hit=156349 read=4215
        ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=32038)
              Cache Key: things_journal_1.tags
              Cache Mode: binary
              Hits: 32028  Misses: 10  Evictions: 0  Overflows: 0  Memory Usage: 2kB
              ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.066..0.066 rows=1 loops=10)
                    ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.028..0.028 rows=1 loops=10)
                          Filter: (tag ~ '^priority-[0-9]+$'::text)
                          Rows Removed by Filter: 1
Planning:
  Buffers: shared hit=117 read=2
Planning Time: 0.768 ms
JIT:
  Functions: 43
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 1.421 ms, Inlining 156.045 ms, Optimization 47.235 ms, Emission 58.098 ms, Total 262.800 ms
Execution Time: 69853.129 ms
```

## Same-run control (the clustered layout of the 1M evidence, this container)

Seeded in 12 s into a production-DDL `policies_journal`, dropped afterwards.

| walk | statements | delivered / expected | missing | extra | dup | order | walk time | p50 | p95 | p99 | max (statement #) | max / statement_timeout | host memory (Δ swap-outs, free % before→after) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| same-run control: 1,000,000 pids x 2 events, 1 % clustered at the end, random heap | 2,001 | 10,000 / 10,000 | 0 | 0 | 0 | ok | 3.9 s | 1.62 ms | 2.20 ms | 2.89 ms | 51.15 ms (#46) | — | +0, 68 → 72 % | RELIABLE |

For comparison, `ping-keyset-paging-1m.md` (2026-10-02, Testcontainers defaults, shared_buffers 128MB, warm cache): 2,000 windows, 10,000 delivered, 3.0 s, p50 1.27 ms, p95 2.06 ms, p99 3.50 ms, max 6.52 ms.


**Result: ⛔ offenders** (the stage fails):

- priority order (TAGS), one statement: the walk failed: SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: 32038 missing, first [thing:bench.ns01:pid-00000039, thing:bench.ns01:pid-00000206, thing:bench.ns01:pid-00000279, thing:bench.ns01:pid-00000288, thing:bench.ns01:pid-00000300, thing:bench.ns01:pid-00000303, thing:bench.ns01:pid-00000307, thing:bench.ns01:pid-00000406, thing:bench.ns01:pid-00000557, thing:bench.ns01:pid-00000578, thing:bench.ns01:pid-00000639, thing:bench.ns01:pid-00000670, thing:bench.ns01:pid-00000783, thing:bench.ns01:pid-00000992, thing:bench.ns01:pid-00001021, thing:bench.ns01:pid-00001074, thing:bench.ns01:pid-00001154, thing:bench.ns01:pid-00001318, thing:bench.ns01:pid-00001350, thing:bench.ns01:pid-00001352]
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: failed statement SQLSTATE <57014> canceling statement due to statement timeout
- priority order (TAGS), one statement: a statement reached statement_timeout
