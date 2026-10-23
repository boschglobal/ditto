# Persistence ping on a partly tagged corpus: read-journal walks over 250,000 long-history things

- generated: 2026-10-22T17:56:09.994272Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged (load evidence: `load-pg-partly-tagged-250k-repeat.md`), schema v3
- backend: pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- evidence label: 250k-repeat
- client: the production read journal (`PostgresReadJournal` over `things_*`: the things service has no ping, the SQL is the policies ping's with another table name); connection options of `ConnectionPoolFactory.createDdlConnectionFactory` (`SHOW statement_timeout` through this client: `1min`), production pool limits; every statement timed by an r2dbc-proxy listener (execution until the last row is read); **no throttle**; read-journal-batch-size 500, maxIdleTime PT10M

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 35070304 | 35070304 | 0 | 1 | 0 | 2026-10-22 20:39:49.795337+03 | null |
| things_journal_seq | 250000 | 250000 | 0 | 1 | 0 | 2026-10-22 20:30:53.24393+03 | null |
| things_snaps | 44271 | 44271 | 0 | 1 | 0 | 2026-10-22 20:30:54.577596+03 | null |

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

Order: strictly ascending pid order for the ID walks (the priority order is checked below). Percentiles: nearest rank over the statements of a walk, the final empty window excluded. Every walk, the priority order included, reads windows of 500 pids; the priority order emits its pids only after its last window.

| walk | statements | delivered / expected | missing | extra | dup | order | walk time | p50 | p95 | p99 | max (statement #) | max / statement_timeout (1min) | host memory (Δ swap-outs, free % before→after) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ID order, any tagged event (the policies ping) | 501 | 32,038 / 32,038 | 0 | 0 | 0 | ok | 289.3 s | 571.69 ms | 813.37 ms | 965.77 ms | 1037.39 ms (#469) | 1.7290 % | +0, 51 → 52 % | RELIABLE |
| ID order, newest event tagged | 501 | 29,721 / 29,721 | 0 | 0 | 0 | ok | 26.1 s | 49.00 ms | 80.93 ms | 95.65 ms | 181.32 ms (#286) | 0.3022 % | +0, 52 → 60 % | RELIABLE |
| priority order (TAGS): windows, sorted by priority | 501 | 32,038 / 32,038 | 0 | 0 | 0 | by priority, see below | 183.1 s | 347.48 ms | 597.02 ms | 715.47 ms | 799.29 ms (#272) | 1.3321 % | +0, 61 → 64 % | RELIABLE |

### Median statement time per tenth of the walk (ms)

| walk | tenth 1 | tenth 2 | tenth 3 | tenth 4 | tenth 5 | tenth 6 | tenth 7 | tenth 8 | tenth 9 | tenth 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| ID order, any tagged event (the policies ping) | 557.20 | 561.57 | 602.58 | 528.26 | 598.45 | 581.15 | 569.83 | 568.61 | 606.61 | 490.60 |
| ID order, newest event tagged | 73.08 | 61.77 | 59.05 | 50.05 | 48.17 | 51.12 | 45.34 | 40.47 | 37.95 | 27.24 |
| priority order (TAGS): windows, sorted by priority | 307.20 | 362.13 | 345.05 | 349.59 | 360.17 | 340.62 | 386.47 | 353.00 | 371.08 | 310.78 |

### Five slowest windows: ID order, any tagged event (the policies ping)

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 469 | 1037.39 | 500 | 140,019 | 131,334 |
| 115 | 1006.69 | 500 | 96,903 | 89,070 |
| 316 | 986.51 | 500 | 111,711 | 97,512 |
| 118 | 971.04 | 500 | 130,831 | 119,821 |
| 243 | 967.26 | 500 | 150,826 | 119,028 |

### Five slowest windows: ID order, newest event tagged

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 286 | 181.32 | 500 | 45,596 | 41,527 |
| 1 | 116.28 | 500 | 77,583 | 73,210 |
| 3 | 101.24 | 500 | 57,468 | 53,182 |
| 108 | 100.88 | 500 | 58,004 | 47,048 |
| 103 | 96.81 | 500 | 69,643 | 59,879 |

### Five slowest windows: priority order (TAGS): windows, sorted by priority

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 272 | 799.29 | 500 | 153,003 | 148,869 |
| 23 | 755.28 | 500 | 158,373 | 153,385 |
| 174 | 752.54 | 500 | 134,625 | 131,690 |
| 189 | 740.34 | 500 | 142,732 | 138,532 |
| 277 | 736.30 | 500 | 122,451 | 114,447 |

Priority order: 0 pids out of order (a higher priority after a lower one, or equal priorities not in ascending pid order).


## EXPLAIN (ANALYZE, BUFFERS) of the walks' statements

Each statement as the walk recorded it, prepared on a JDBC connection with `plan_cache_mode = force_custom_plan` and executed with the bind values of the window: window 0, the middle window, the first window of the clustered block and the window with the most journal rows (generator).

### ID order, any tagged event (the policies ping): window 0, cursor `''`, 77,583 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..72280.65 rows=500 width=31) (actual time=62.936..794.507 rows=500 loops=1)
  Buffers: shared hit=3362 read=12356
  ->  Limit  (cost=1000.59..54495.05 rows=500 width=30) (actual time=62.852..166.027 rows=500 loops=1)
        Buffers: shared hit=123 read=2093
        ->  Unique  (cost=1000.59..1464181.20 rows=13676 width=30) (actual time=62.851..166.000 rows=500 loops=1)
              Buffers: shared hit=123 read=2093
              ->  Gather Merge  (cost=1000.59..1464112.82 rows=27352 width=30) (actual time=62.850..165.944 rows=558 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=123 read=2093
                    ->  Unique  (cost=0.56..1459955.70 rows=13676 width=30) (actual time=0.466..67.890 rows=879 loops=3)
                          Buffers: shared hit=123 read=2093
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423424.13 rows=14612627 width=30) (actual time=0.466..65.000 rows=96939 loops=3)
                                Index Cond: (pid > ''::text)
                                Heap Fetches: 117
                                Buffers: shared hit=123 read=2093
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=1.257..1.257 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 146
          Buffers: shared hit=3239 read=10263
Planning:
  Buffers: shared hit=91 read=20
Planning Time: 8.447 ms
Execution Time: 794.577 ms
```

### ID order, any tagged event (the policies ping): window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..45992.13 rows=500 width=31) (actual time=33.774..849.215 rows=500 loops=1)
  Buffers: shared hit=2795 read=9646
  ->  Limit  (cost=1000.59..28206.53 rows=500 width=30) (actual time=32.379..50.595 rows=500 loops=1)
        Buffers: shared hit=142 read=2610
        ->  Unique  (cost=1000.59..745137.57 rows=13676 width=30) (actual time=32.379..50.576 rows=500 loops=1)
              Buffers: shared hit=142 read=2610
              ->  Gather Merge  (cost=1000.59..745069.19 rows=27352 width=30) (actual time=32.378..50.512 rows=645 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=142 read=2610
                    ->  Unique  (cost=0.56..740912.07 rows=13676 width=30) (actual time=2.866..85.292 rows=976 loops=3)
                          Buffers: shared hit=142 read=2610
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..722372.84 rows=7415692 width=30) (actual time=2.865..82.572 rows=121585 loops=3)
                                Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=142 read=2610
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=1.597..1.597 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 97
          Buffers: shared hit=2653 read=7036
Planning Time: 0.138 ms
Execution Time: 849.259 ms
```

### ID order, any tagged event (the policies ping): window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Subquery Scan on w  (cost=0.56..18487.78 rows=500 width=31) (actual time=0.095..48.852 rows=500 loops=1)
  Buffers: shared hit=2611 read=336
  ->  Limit  (cost=0.56..702.19 rows=500 width=30) (actual time=0.039..4.857 rows=500 loops=1)
        Buffers: shared hit=447
        ->  Result  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.039..4.837 rows=500 loops=1)
              Buffers: shared hit=447
              ->  Unique  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.038..4.803 rows=500 loops=1)
                    Buffers: shared hit=447
                    ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..18210.92 rows=392144 width=30) (actual time=0.038..3.735 rows=49912 loops=1)
                          Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                          Heap Fetches: 2
                          Buffers: shared hit=447
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=0.088..0.088 rows=1 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Buffers: shared hit=2164 read=336
Planning:
  Buffers: shared hit=5
Planning Time: 0.132 ms
Execution Time: 48.876 ms
```

### ID order, any tagged event (the policies ping): window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..70148.94 rows=500 width=31) (actual time=64.382..1052.039 rows=500 loops=1)
  Buffers: shared hit=4505 read=24444
  ->  Limit  (cost=1000.59..52363.35 rows=500 width=30) (actual time=62.817..83.243 rows=500 loops=1)
        Buffers: shared hit=93 read=3360
        ->  Unique  (cost=1000.59..1405874.80 rows=13676 width=30) (actual time=62.817..83.224 rows=500 loops=1)
              Buffers: shared hit=93 read=3360
              ->  Gather Merge  (cost=1000.59..1405806.42 rows=27352 width=30) (actual time=62.817..83.165 rows=637 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=93 read=3360
                    ->  Unique  (cost=0.56..1401649.30 rows=13676 width=30) (actual time=0.200..120.987 rows=976 loops=3)
                          Buffers: shared hit=93 read=3360
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366576.66 rows=14029054 width=30) (actual time=0.200..117.606 rows=156660 loops=3)
                                Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=93 read=3360
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=1.937..1.937 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 307
          Buffers: shared hit=4412 read=21084
Planning Time: 0.095 ms
Execution Time: 1052.081 ms
```

### ID order, newest event tagged: window 0, cursor `''`, 77,583 journal rows (generator)

```
Nested Loop  (cost=1001.15..56808.19 rows=500 width=31) (actual time=4.379..9.185 rows=500 loops=1)
  Buffers: shared hit=3572 read=19
  ->  Limit  (cost=1000.59..54495.05 rows=500 width=30) (actual time=4.369..5.500 rows=500 loops=1)
        Buffers: shared hit=1091
        ->  Unique  (cost=1000.59..1464181.20 rows=13676 width=30) (actual time=4.369..5.484 rows=500 loops=1)
              Buffers: shared hit=1091
              ->  Gather Merge  (cost=1000.59..1464112.82 rows=27352 width=30) (actual time=4.368..5.429 rows=664 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1091
                    ->  Unique  (cost=0.56..1459955.70 rows=13676 width=30) (actual time=0.022..4.514 rows=482 loops=3)
                          Buffers: shared hit=1091
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423424.13 rows=14612627 width=30) (actual time=0.022..3.424 rows=48380 loops=3)
                                Index Cond: (pid > ''::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1091
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.007..0.007 rows=1 loops=500)
        Buffers: shared hit=2481 read=19
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.007..0.007 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2481 read=19
Planning:
  Buffers: shared hit=12
Planning Time: 0.141 ms
Execution Time: 9.233 ms
```

### ID order, newest event tagged: window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Nested Loop  (cost=1001.15..30519.67 rows=500 width=31) (actual time=3.714..13.488 rows=500 loops=1)
  Buffers: shared hit=3953 read=25
  ->  Limit  (cost=1000.59..28206.53 rows=500 width=30) (actual time=3.706..4.947 rows=500 loops=1)
        Buffers: shared hit=1478
        ->  Unique  (cost=1000.59..745137.57 rows=13676 width=30) (actual time=3.705..4.931 rows=500 loops=1)
              Buffers: shared hit=1478
              ->  Gather Merge  (cost=1000.59..745069.19 rows=27352 width=30) (actual time=3.705..4.874 rows=657 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1478
                    ->  Unique  (cost=0.56..740912.07 rows=13676 width=30) (actual time=0.017..7.133 rows=755 loops=3)
                          Buffers: shared hit=1478
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..722372.84 rows=7415692 width=30) (actual time=0.017..5.535 rows=63816 loops=3)
                                Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1478
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.017..0.017 rows=1 loops=500)
        Buffers: shared hit=2475 read=25
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.017..0.017 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2475 read=25
Planning Time: 0.084 ms
Execution Time: 13.536 ms
```

### ID order, newest event tagged: window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Nested Loop  (cost=1.13..3015.32 rows=500 width=31) (actual time=0.019..5.732 rows=500 loops=1)
  Buffers: shared hit=2947
  ->  Limit  (cost=0.56..702.19 rows=500 width=30) (actual time=0.008..3.353 rows=500 loops=1)
        Buffers: shared hit=447
        ->  Result  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.008..3.338 rows=500 loops=1)
              Buffers: shared hit=447
              ->  Unique  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.007..3.316 rows=500 loops=1)
                    Buffers: shared hit=447
                    ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..18210.92 rows=392144 width=30) (actual time=0.007..2.262 rows=49912 loops=1)
                          Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                          Heap Fetches: 2
                          Buffers: shared hit=447
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.005..0.005 rows=1 loops=500)
        Buffers: shared hit=2500
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.004..0.004 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2500
Planning:
  Buffers: shared hit=5
Planning Time: 0.093 ms
Execution Time: 5.750 ms
```

### ID order, newest event tagged: window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Nested Loop  (cost=1001.15..54676.49 rows=500 width=31) (actual time=7.260..12.594 rows=500 loops=1)
  Buffers: shared hit=4247 read=35
  ->  Limit  (cost=1000.59..52363.35 rows=500 width=30) (actual time=7.252..8.301 rows=500 loops=1)
        Buffers: shared hit=1782
        ->  Unique  (cost=1000.59..1405874.80 rows=13676 width=30) (actual time=7.251..8.284 rows=500 loops=1)
              Buffers: shared hit=1782
              ->  Gather Merge  (cost=1000.59..1405806.42 rows=27352 width=30) (actual time=7.251..8.236 rows=644 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1782
                    ->  Unique  (cost=0.56..1401649.30 rows=13676 width=30) (actual time=0.021..6.933 rows=493 loops=3)
                          Buffers: shared hit=1782
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366576.66 rows=14029054 width=30) (actual time=0.021..5.211 rows=80227 loops=3)
                                Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1782
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.008..0.008 rows=1 loops=500)
        Buffers: shared hit=2465 read=35
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.008..0.008 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2465 read=35
Planning Time: 0.054 ms
Execution Time: 12.639 ms
```

### priority order (TAGS): windows, sorted by priority: window 0, cursor `''`, 77,583 journal rows (generator)

```
Nested Loop Left Join  (cost=1001.30..72303.44 rows=500 width=35) (actual time=4.272..32.481 rows=500 loops=1)
  Buffers: shared hit=16619
  ->  Nested Loop Left Join  (cost=1001.15..72290.65 rows=500 width=47) (actual time=4.266..32.235 rows=500 loops=1)
        Buffers: shared hit=16619
        ->  Limit  (cost=1000.59..54495.05 rows=500 width=30) (actual time=4.250..6.023 rows=500 loops=1)
              Buffers: shared hit=2133
              ->  Unique  (cost=1000.59..1464181.20 rows=13676 width=30) (actual time=4.250..6.008 rows=500 loops=1)
                    Buffers: shared hit=2133
                    ->  Gather Merge  (cost=1000.59..1464112.82 rows=27352 width=30) (actual time=4.250..5.965 rows=663 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=2133
                          ->  Unique  (cost=0.56..1459955.70 rows=13676 width=30) (actual time=0.018..9.125 rows=966 loops=3)
                                Buffers: shared hit=2133
                                ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423424.13 rows=14612627 width=30) (actual time=0.018..6.868 rows=92858 loops=3)
                                      Index Cond: (pid > ''::text)
                                      Heap Fetches: 117
                                      Buffers: shared hit=2133
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.052..0.052 rows=0 loops=500)
              Buffers: shared hit=14486
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.052..0.052 rows=0 loops=500)
                    Index Cond: (pid = things_journal.pid)
                    Filter: ('always-alive'::text = ANY (tags))
                    Rows Removed by Filter: 146
                    Buffers: shared hit=14486
  ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=500)
        Cache Key: things_journal_1.tags
        Cache Mode: binary
        Hits: 489  Misses: 11  Evictions: 0  Overflows: 0  Memory Usage: 2kB
        ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.009..0.009 rows=1 loops=11)
              ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=11)
                    Filter: (tag ~ '^priority-[0-9]+$'::text)
                    Rows Removed by Filter: 1
Planning:
  Buffers: shared hit=107 read=2
Planning Time: 0.538 ms
Execution Time: 32.564 ms
```

### priority order (TAGS): windows, sorted by priority: window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Nested Loop Left Join  (cost=1001.30..46014.92 rows=500 width=35) (actual time=5.838..27.009 rows=500 loops=1)
  Buffers: shared hit=12620
  ->  Nested Loop Left Join  (cost=1001.15..46002.13 rows=500 width=47) (actual time=5.834..26.801 rows=500 loops=1)
        Buffers: shared hit=12620
        ->  Limit  (cost=1000.59..28206.53 rows=500 width=30) (actual time=5.782..7.668 rows=500 loops=1)
              Buffers: shared hit=2258
              ->  Unique  (cost=1000.59..745137.57 rows=13676 width=30) (actual time=5.782..7.654 rows=500 loops=1)
                    Buffers: shared hit=2258
                    ->  Gather Merge  (cost=1000.59..745069.19 rows=27352 width=30) (actual time=5.782..7.612 rows=568 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=2258
                          ->  Unique  (cost=0.56..740912.07 rows=13676 width=30) (actual time=0.030..10.143 rows=862 loops=3)
                                Buffers: shared hit=2258
                                ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..722372.84 rows=7415692 width=30) (actual time=0.029..7.846 rows=99168 loops=3)
                                      Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                      Heap Fetches: 0
                                      Buffers: shared hit=2258
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.038..0.038 rows=0 loops=500)
              Buffers: shared hit=10362
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.038..0.038 rows=0 loops=500)
                    Index Cond: (pid = things_journal.pid)
                    Filter: ('always-alive'::text = ANY (tags))
                    Rows Removed by Filter: 95
                    Buffers: shared hit=10362
  ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=500)
        Cache Key: things_journal_1.tags
        Cache Mode: binary
        Hits: 489  Misses: 11  Evictions: 0  Overflows: 0  Memory Usage: 2kB
        ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.005..0.005 rows=1 loops=11)
              ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=11)
                    Filter: (tag ~ '^priority-[0-9]+$'::text)
                    Rows Removed by Filter: 1
Planning Time: 0.111 ms
Execution Time: 27.060 ms
```

### priority order (TAGS): windows, sorted by priority: window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Nested Loop Left Join  (cost=1.28..18510.57 rows=500 width=35) (actual time=0.042..5.569 rows=500 loops=1)
  Buffers: shared hit=2947
  ->  Nested Loop Left Join  (cost=1.13..18497.78 rows=500 width=47) (actual time=0.014..5.362 rows=500 loops=1)
        Buffers: shared hit=2947
        ->  Limit  (cost=0.56..702.19 rows=500 width=30) (actual time=0.010..3.506 rows=500 loops=1)
              Buffers: shared hit=447
              ->  Result  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.009..3.487 rows=500 loops=1)
                    Buffers: shared hit=447
                    ->  Unique  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.009..3.461 rows=500 loops=1)
                          Buffers: shared hit=447
                          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..18210.92 rows=392144 width=30) (actual time=0.009..2.350 rows=49912 loops=1)
                                Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                                Heap Fetches: 2
                                Buffers: shared hit=447
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.004..0.004 rows=1 loops=500)
              Buffers: shared hit=2500
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.003..0.003 rows=1 loops=500)
                    Index Cond: (pid = things_journal.pid)
                    Filter: ('always-alive'::text = ANY (tags))
                    Buffers: shared hit=2500
  ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=500)
        Cache Key: things_journal_1.tags
        Cache Mode: binary
        Hits: 490  Misses: 10  Evictions: 0  Overflows: 0  Memory Usage: 2kB
        ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.005..0.005 rows=1 loops=10)
              ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=10)
                    Filter: (tag ~ '^priority-[0-9]+$'::text)
                    Rows Removed by Filter: 1
Planning:
  Buffers: shared hit=5
Planning Time: 0.152 ms
Execution Time: 5.607 ms
```

### priority order (TAGS): windows, sorted by priority: window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Nested Loop Left Join  (cost=1001.30..70171.73 rows=500 width=35) (actual time=8.655..74.948 rows=500 loops=1)
  Buffers: shared hit=30476 read=77
  ->  Nested Loop Left Join  (cost=1001.15..70158.94 rows=500 width=47) (actual time=8.640..74.695 rows=500 loops=1)
        Buffers: shared hit=30476 read=77
        ->  Limit  (cost=1000.59..52363.35 rows=500 width=30) (actual time=8.583..10.380 rows=500 loops=1)
              Buffers: shared hit=3454 read=77
              ->  Unique  (cost=1000.59..1405874.80 rows=13676 width=30) (actual time=8.582..10.365 rows=500 loops=1)
                    Buffers: shared hit=3454 read=77
                    ->  Gather Merge  (cost=1000.59..1405806.42 rows=27352 width=30) (actual time=8.582..10.330 rows=642 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=3454 read=77
                          ->  Unique  (cost=0.56..1401649.30 rows=13676 width=30) (actual time=0.027..18.149 rows=950 loops=3)
                                Buffers: shared hit=3454 read=77
                                ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366576.66 rows=14029054 width=30) (actual time=0.026..14.510 rows=160261 loops=3)
                                      Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                      Heap Fetches: 0
                                      Buffers: shared hit=3454 read=77
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.128..0.128 rows=0 loops=500)
              Buffers: shared hit=27022
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.128..0.128 rows=0 loops=500)
                    Index Cond: (pid = things_journal.pid)
                    Filter: ('always-alive'::text = ANY (tags))
                    Rows Removed by Filter: 307
                    Buffers: shared hit=27022
  ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=500)
        Cache Key: things_journal_1.tags
        Cache Mode: binary
        Hits: 489  Misses: 11  Evictions: 0  Overflows: 0  Memory Usage: 2kB
        ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.009..0.009 rows=1 loops=11)
              ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=11)
                    Filter: (tag ~ '^priority-[0-9]+$'::text)
                    Rows Removed by Filter: 1
Planning Time: 0.131 ms
Execution Time: 75.005 ms
```

## Same-run control (the clustered layout of the 1M evidence, this container)

Seeded in 15 s into a production-DDL `policies_journal`, dropped afterwards.

| walk | statements | delivered / expected | missing | extra | dup | order | walk time | p50 | p95 | p99 | max (statement #) | max / statement_timeout | host memory (Δ swap-outs, free % before→after) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| same-run control: 1,000,000 pids x 2 events, 1 % clustered at the end, random heap | 2,001 | 10,000 / 10,000 | 0 | 0 | 0 | ok | 3.4 s | 1.47 ms | 1.76 ms | 2.25 ms | 3.46 ms (#216) | — | +0, 56 → 57 % | RELIABLE |
| same-run control, priority order (TAGS) | 2,001 | 10,000 / 10,000 | 0 | 0 | 0 | ok | 3.3 s | 1.47 ms | 1.76 ms | 2.06 ms | 4.32 ms (#341) | — | +0, 57 → 58 % | RELIABLE |

For comparison, `ping-keyset-paging-1m.md` (2026-10-02, Testcontainers defaults, shared_buffers 128MB, warm cache): 2,000 windows, 10,000 delivered, 3.0 s, p50 1.27 ms, p95 2.06 ms, p99 3.50 ms, max 6.52 ms (the ID order only; the priority order was one statement then).


**Result: every walk delivered exactly the expected pids.**
