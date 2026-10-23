# Persistence ping on a partly tagged corpus: read-journal walks over 250,000 long-history things

- generated: 2026-10-22T17:43:51.986751Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged (load evidence: `load-pg-partly-tagged-250k.md`), schema v3
- backend: pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- evidence label: 250k
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
| ID order, any tagged event (the policies ping) | 501 | 32,038 / 32,038 | 0 | 0 | 0 | ok | 280.5 s | 549.94 ms | 783.87 ms | 976.10 ms | 1092.10 ms (#117) | 1.8202 % | +0, 51 → 54 % | RELIABLE |
| ID order, newest event tagged | 501 | 29,721 / 29,721 | 0 | 0 | 0 | ok | 24.3 s | 46.56 ms | 67.97 ms | 82.40 ms | 219.77 ms (#23) | 0.3663 % | +0, 54 → 60 % | RELIABLE |
| priority order (TAGS): windows, sorted by priority | 501 | 32,038 / 32,038 | 0 | 0 | 0 | by priority, see below | 189.6 s | 351.19 ms | 590.86 ms | 807.02 ms | 941.40 ms (#272) | 1.5690 % | +0, 60 → 49 % | UNRELIABLE |

### Median statement time per tenth of the walk (ms)

| walk | tenth 1 | tenth 2 | tenth 3 | tenth 4 | tenth 5 | tenth 6 | tenth 7 | tenth 8 | tenth 9 | tenth 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| ID order, any tagged event (the policies ping) | 524.44 | 532.57 | 603.20 | 500.59 | 566.71 | 515.94 | 558.72 | 588.86 | 563.56 | 541.23 |
| ID order, newest event tagged | 53.89 | 54.84 | 50.61 | 40.54 | 41.53 | 45.96 | 48.76 | 50.27 | 44.19 | 27.34 |
| priority order (TAGS): windows, sorted by priority | 329.19 | 366.09 | 370.18 | 326.44 | 364.58 | 348.73 | 377.56 | 380.82 | 340.08 | 349.06 |

### Five slowest windows: ID order, any tagged event (the policies ping)

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 117 | 1092.10 | 500 | 103,575 | 91,216 |
| 118 | 1068.57 | 500 | 130,831 | 119,821 |
| 232 | 1053.12 | 500 | 123,863 | 108,909 |
| 255 | 1035.02 | 500 | 140,647 | 136,402 |
| 243 | 993.09 | 500 | 150,826 | 119,028 |

### Five slowest windows: ID order, newest event tagged

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 23 | 219.77 | 500 | 158,373 | 153,385 |
| 1 | 104.38 | 500 | 77,583 | 73,210 |
| 378 | 94.37 | 500 | 47,634 | 45,244 |
| 443 | 93.60 | 500 | 121,078 | 110,832 |
| 74 | 89.51 | 500 | 57,998 | 46,650 |

### Five slowest windows: priority order (TAGS): windows, sorted by priority

| # (window = # − 1) | ms | rows returned | journal rows of the window's pids (generator) | of them, of untagged pids |
|---|---|---|---|---|
| 272 | 941.40 | 500 | 153,003 | 148,869 |
| 23 | 891.83 | 500 | 158,373 | 153,385 |
| 443 | 890.62 | 500 | 121,078 | 110,832 |
| 255 | 879.47 | 500 | 140,647 | 136,402 |
| 447 | 807.46 | 500 | 110,526 | 104,724 |

Priority order: 0 pids out of order (a higher priority after a lower one, or equal priorities not in ascending pid order).


## EXPLAIN (ANALYZE, BUFFERS) of the walks' statements

Each statement as the walk recorded it, prepared on a JDBC connection with `plan_cache_mode = force_custom_plan` and executed with the bind values of the window: window 0, the middle window, the first window of the clustered block and the window with the most journal rows (generator).

### ID order, any tagged event (the policies ping): window 0, cursor `''`, 77,583 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..72280.65 rows=500 width=31) (actual time=50.616..778.855 rows=500 loops=1)
  Buffers: shared hit=3364 read=12371
  ->  Limit  (cost=1000.59..54495.05 rows=500 width=30) (actual time=50.278..57.074 rows=500 loops=1)
        Buffers: shared hit=125 read=2108
        ->  Unique  (cost=1000.59..1464181.20 rows=13676 width=30) (actual time=50.278..57.047 rows=500 loops=1)
              Buffers: shared hit=125 read=2108
              ->  Gather Merge  (cost=1000.59..1464112.82 rows=27352 width=30) (actual time=50.277..56.992 rows=568 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=125 read=2108
                    ->  Unique  (cost=0.56..1459955.70 rows=13676 width=30) (actual time=0.389..69.819 rows=879 loops=3)
                          Buffers: shared hit=125 read=2108
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423424.13 rows=14612627 width=30) (actual time=0.388..67.766 rows=97602 loops=3)
                                Index Cond: (pid > ''::text)
                                Heap Fetches: 117
                                Buffers: shared hit=125 read=2108
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=1.443..1.443 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 146
          Buffers: shared hit=3239 read=10263
Planning:
  Buffers: shared hit=91 read=20
Planning Time: 6.516 ms
Execution Time: 778.934 ms
```

### ID order, any tagged event (the policies ping): window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..45992.13 rows=500 width=31) (actual time=35.169..745.351 rows=500 loops=1)
  Buffers: shared hit=2794 read=9629
  ->  Limit  (cost=1000.59..28206.53 rows=500 width=30) (actual time=33.537..41.715 rows=500 loops=1)
        Buffers: shared hit=141 read=2593
        ->  Unique  (cost=1000.59..745137.57 rows=13676 width=30) (actual time=33.537..41.694 rows=500 loops=1)
              Buffers: shared hit=141 read=2593
              ->  Gather Merge  (cost=1000.59..745069.19 rows=27352 width=30) (actual time=33.537..41.620 rows=646 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=141 read=2593
                    ->  Unique  (cost=0.56..740912.07 rows=13676 width=30) (actual time=0.313..69.870 rows=976 loops=3)
                          Buffers: shared hit=141 read=2593
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..722372.84 rows=7415692 width=30) (actual time=0.313..67.192 rows=120827 loops=3)
                                Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=141 read=2593
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=1.407..1.407 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 97
          Buffers: shared hit=2653 read=7036
Planning Time: 0.126 ms
Execution Time: 745.406 ms
```

### ID order, any tagged event (the policies ping): window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Subquery Scan on w  (cost=0.56..18487.78 rows=500 width=31) (actual time=0.065..56.147 rows=500 loops=1)
  Buffers: shared hit=2611 read=336
  ->  Limit  (cost=0.56..702.19 rows=500 width=30) (actual time=0.030..4.775 rows=500 loops=1)
        Buffers: shared hit=447
        ->  Result  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.029..4.756 rows=500 loops=1)
              Buffers: shared hit=447
              ->  Unique  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.029..4.724 rows=500 loops=1)
                    Buffers: shared hit=447
                    ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..18210.92 rows=392144 width=30) (actual time=0.028..3.671 rows=49912 loops=1)
                          Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                          Heap Fetches: 2
                          Buffers: shared hit=447
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=0.102..0.102 rows=1 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Buffers: shared hit=2164 read=336
Planning:
  Buffers: shared hit=5
Planning Time: 0.122 ms
Execution Time: 56.176 ms
```

### ID order, any tagged event (the policies ping): window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Subquery Scan on w  (cost=1000.59..70148.94 rows=500 width=31) (actual time=54.675..1000.333 rows=500 loops=1)
  Buffers: shared hit=4505 read=24457
  ->  Limit  (cost=1000.59..52363.35 rows=500 width=30) (actual time=52.928..54.910 rows=500 loops=1)
        Buffers: shared hit=93 read=3373
        ->  Unique  (cost=1000.59..1405874.80 rows=13676 width=30) (actual time=52.928..54.891 rows=500 loops=1)
              Buffers: shared hit=93 read=3373
              ->  Gather Merge  (cost=1000.59..1405806.42 rows=27352 width=30) (actual time=52.928..54.833 rows=638 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=93 read=3373
                    ->  Unique  (cost=0.56..1401649.30 rows=13676 width=30) (actual time=0.438..94.211 rows=976 loops=3)
                          Buffers: shared hit=93 read=3373
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366576.66 rows=14029054 width=30) (actual time=0.438..90.910 rows=157240 loops=3)
                                Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=93 read=3373
  SubPlan 1
    ->  Index Scan using things_journal_pkey on things_journal t  (cost=0.56..10362.40 rows=296 width=0) (actual time=1.890..1.890 rows=0 loops=500)
          Index Cond: (pid = w.pid)
          Filter: ('always-alive'::text = ANY (tags))
          Rows Removed by Filter: 307
          Buffers: shared hit=4412 read=21084
Planning Time: 0.090 ms
Execution Time: 1000.374 ms
```

### ID order, newest event tagged: window 0, cursor `''`, 77,583 journal rows (generator)

```
Nested Loop  (cost=1001.15..56808.19 rows=500 width=31) (actual time=5.813..17.800 rows=500 loops=1)
  Buffers: shared hit=4034 read=19
  ->  Limit  (cost=1000.59..54495.05 rows=500 width=30) (actual time=5.794..7.214 rows=500 loops=1)
        Buffers: shared hit=1553
        ->  Unique  (cost=1000.59..1464181.20 rows=13676 width=30) (actual time=5.794..7.197 rows=500 loops=1)
              Buffers: shared hit=1553
              ->  Gather Merge  (cost=1000.59..1464112.82 rows=27352 width=30) (actual time=5.794..7.146 rows=662 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1553
                    ->  Unique  (cost=0.56..1459955.70 rows=13676 width=30) (actual time=0.024..9.754 rows=706 loops=3)
                          Buffers: shared hit=1553
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423424.13 rows=14612627 width=30) (actual time=0.024..8.072 rows=67747 loops=3)
                                Index Cond: (pid > ''::text)
                                Heap Fetches: 117
                                Buffers: shared hit=1553
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.021..0.021 rows=1 loops=500)
        Buffers: shared hit=2481 read=19
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.021..0.021 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2481 read=19
Planning:
  Buffers: shared hit=12
Planning Time: 0.189 ms
Execution Time: 17.853 ms
```

### ID order, newest event tagged: window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Nested Loop  (cost=1001.15..30519.67 rows=500 width=31) (actual time=3.670..11.747 rows=500 loops=1)
  Buffers: shared hit=3516 read=25
  ->  Limit  (cost=1000.59..28206.53 rows=500 width=30) (actual time=3.659..6.267 rows=500 loops=1)
        Buffers: shared hit=1041
        ->  Unique  (cost=1000.59..745137.57 rows=13676 width=30) (actual time=3.659..6.252 rows=500 loops=1)
              Buffers: shared hit=1041
              ->  Gather Merge  (cost=1000.59..745069.19 rows=27352 width=30) (actual time=3.658..6.192 rows=647 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1041
                    ->  Unique  (cost=0.56..740912.07 rows=13676 width=30) (actual time=0.012..6.373 rows=477 loops=3)
                          Buffers: shared hit=1041
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..722372.84 rows=7415692 width=30) (actual time=0.012..5.365 rows=44684 loops=3)
                                Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1041
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.011..0.011 rows=1 loops=500)
        Buffers: shared hit=2475 read=25
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.010..0.010 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2475 read=25
Planning Time: 0.090 ms
Execution Time: 11.803 ms
```

### ID order, newest event tagged: window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Nested Loop  (cost=1.13..3015.32 rows=500 width=31) (actual time=0.026..6.776 rows=500 loops=1)
  Buffers: shared hit=2947
  ->  Limit  (cost=0.56..702.19 rows=500 width=30) (actual time=0.016..3.804 rows=500 loops=1)
        Buffers: shared hit=447
        ->  Result  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.016..3.788 rows=500 loops=1)
              Buffers: shared hit=447
              ->  Unique  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.016..3.753 rows=500 loops=1)
                    Buffers: shared hit=447
                    ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..18210.92 rows=392144 width=30) (actual time=0.015..2.675 rows=49912 loops=1)
                          Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                          Heap Fetches: 2
                          Buffers: shared hit=447
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.006..0.006 rows=1 loops=500)
        Buffers: shared hit=2500
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.006..0.006 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2500
Planning:
  Buffers: shared hit=5
Planning Time: 0.098 ms
Execution Time: 6.801 ms
```

### ID order, newest event tagged: window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Nested Loop  (cost=1001.15..54676.49 rows=500 width=31) (actual time=9.094..15.830 rows=500 loops=1)
  Buffers: shared hit=4153 read=35
  ->  Limit  (cost=1000.59..52363.35 rows=500 width=30) (actual time=9.076..10.241 rows=500 loops=1)
        Buffers: shared hit=1688
        ->  Unique  (cost=1000.59..1405874.80 rows=13676 width=30) (actual time=9.076..10.225 rows=500 loops=1)
              Buffers: shared hit=1688
              ->  Gather Merge  (cost=1000.59..1405806.42 rows=27352 width=30) (actual time=9.075..10.168 rows=635 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=1688
                    ->  Unique  (cost=0.56..1401649.30 rows=13676 width=30) (actual time=0.016..8.717 rows=435 loops=3)
                          Buffers: shared hit=1688
                          ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366576.66 rows=14029054 width=30) (actual time=0.016..6.826 rows=75856 loops=3)
                                Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                Heap Fetches: 0
                                Buffers: shared hit=1688
  ->  Limit  (cost=0.56..4.59 rows=1 width=25) (actual time=0.011..0.011 rows=1 loops=500)
        Buffers: shared hit=2465 read=35
        ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10330.35 rows=2564 width=25) (actual time=0.011..0.011 rows=1 loops=500)
              Index Cond: (pid = things_journal.pid)
              Buffers: shared hit=2465 read=35
Planning Time: 0.057 ms
Execution Time: 15.885 ms
```

### priority order (TAGS): windows, sorted by priority: window 0, cursor `''`, 77,583 journal rows (generator)

```
Nested Loop Left Join  (cost=1001.30..72303.44 rows=500 width=35) (actual time=7.292..47.299 rows=500 loops=1)
  Buffers: shared hit=16436
  ->  Nested Loop Left Join  (cost=1001.15..72290.65 rows=500 width=47) (actual time=7.286..46.958 rows=500 loops=1)
        Buffers: shared hit=16436
        ->  Limit  (cost=1000.59..54495.05 rows=500 width=30) (actual time=7.277..8.784 rows=500 loops=1)
              Buffers: shared hit=1950
              ->  Unique  (cost=1000.59..1464181.20 rows=13676 width=30) (actual time=7.276..8.769 rows=500 loops=1)
                    Buffers: shared hit=1950
                    ->  Gather Merge  (cost=1000.59..1464112.82 rows=27352 width=30) (actual time=7.276..8.739 rows=506 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=1950
                          ->  Unique  (cost=0.56..1459955.70 rows=13676 width=30) (actual time=0.011..13.168 rows=781 loops=3)
                                Buffers: shared hit=1950
                                ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1423424.13 rows=14612627 width=30) (actual time=0.010..11.283 rows=85109 loops=3)
                                      Index Cond: (pid > ''::text)
                                      Heap Fetches: 117
                                      Buffers: shared hit=1950
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.076..0.076 rows=0 loops=500)
              Buffers: shared hit=14486
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.076..0.076 rows=0 loops=500)
                    Index Cond: (pid = things_journal.pid)
                    Filter: ('always-alive'::text = ANY (tags))
                    Rows Removed by Filter: 146
                    Buffers: shared hit=14486
  ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=500)
        Cache Key: things_journal_1.tags
        Cache Mode: binary
        Hits: 489  Misses: 11  Evictions: 0  Overflows: 0  Memory Usage: 2kB
        ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.017..0.017 rows=1 loops=11)
              ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.010..0.010 rows=1 loops=11)
                    Filter: (tag ~ '^priority-[0-9]+$'::text)
                    Rows Removed by Filter: 1
Planning:
  Buffers: shared hit=107 read=2
Planning Time: 0.348 ms
Execution Time: 47.391 ms
```

### priority order (TAGS): windows, sorted by priority: window 250, cursor `thing:bench.ns05:pid-00199631`, 52,186 journal rows (generator)

```
Nested Loop Left Join  (cost=1001.30..46014.92 rows=500 width=35) (actual time=3.811..35.166 rows=500 loops=1)
  Buffers: shared hit=12624
  ->  Nested Loop Left Join  (cost=1001.15..46002.13 rows=500 width=47) (actual time=3.807..34.968 rows=500 loops=1)
        Buffers: shared hit=12624
        ->  Limit  (cost=1000.59..28206.53 rows=500 width=30) (actual time=3.741..8.404 rows=500 loops=1)
              Buffers: shared hit=2262
              ->  Unique  (cost=1000.59..745137.57 rows=13676 width=30) (actual time=3.740..8.389 rows=500 loops=1)
                    Buffers: shared hit=2262
                    ->  Gather Merge  (cost=1000.59..745069.19 rows=27352 width=30) (actual time=3.740..8.346 rows=653 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=2262
                          ->  Unique  (cost=0.56..740912.07 rows=13676 width=30) (actual time=0.019..11.102 rows=928 loops=3)
                                Buffers: shared hit=2262
                                ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..722372.84 rows=7415692 width=30) (actual time=0.019..8.448 rows=99162 loops=3)
                                      Index Cond: (pid > 'thing:bench.ns05:pid-00199631'::text)
                                      Heap Fetches: 0
                                      Buffers: shared hit=2262
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.053..0.053 rows=0 loops=500)
              Buffers: shared hit=10362
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.053..0.053 rows=0 loops=500)
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
Planning Time: 0.090 ms
Execution Time: 35.209 ms
```

### priority order (TAGS): windows, sorted by priority: window 495, cursor `thing:bench.ns48:pid-00185697`, 49,999 journal rows (generator)

```
Nested Loop Left Join  (cost=1.28..18510.57 rows=500 width=35) (actual time=0.033..5.275 rows=500 loops=1)
  Buffers: shared hit=2947
  ->  Nested Loop Left Join  (cost=1.13..18497.78 rows=500 width=47) (actual time=0.015..5.088 rows=500 loops=1)
        Buffers: shared hit=2947
        ->  Limit  (cost=0.56..702.19 rows=500 width=30) (actual time=0.007..3.339 rows=500 loops=1)
              Buffers: shared hit=447
              ->  Result  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.007..3.323 rows=500 loops=1)
                    Buffers: shared hit=447
                    ->  Unique  (cost=0.56..19191.28 rows=13676 width=30) (actual time=0.007..3.296 rows=500 loops=1)
                          Buffers: shared hit=447
                          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..18210.92 rows=392144 width=30) (actual time=0.006..2.215 rows=49912 loops=1)
                                Index Cond: (pid > 'thing:bench.ns48:pid-00185697'::text)
                                Heap Fetches: 2
                                Buffers: shared hit=447
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.003..0.003 rows=1 loops=500)
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
Planning Time: 0.123 ms
Execution Time: 5.300 ms
```

### priority order (TAGS): windows, sorted by priority: window 22, cursor `thing:bench.ns01:pid-00048932`, 158,373 journal rows (generator)

```
Nested Loop Left Join  (cost=1001.30..70171.73 rows=500 width=35) (actual time=7.397..84.070 rows=500 loops=1)
  Buffers: shared hit=30408
  ->  Nested Loop Left Join  (cost=1001.15..70158.94 rows=500 width=47) (actual time=7.392..83.826 rows=500 loops=1)
        Buffers: shared hit=30408
        ->  Limit  (cost=1000.59..52363.35 rows=500 width=30) (actual time=7.341..8.893 rows=500 loops=1)
              Buffers: shared hit=3386
              ->  Unique  (cost=1000.59..1405874.80 rows=13676 width=30) (actual time=7.340..8.878 rows=500 loops=1)
                    Buffers: shared hit=3386
                    ->  Gather Merge  (cost=1000.59..1405806.42 rows=27352 width=30) (actual time=7.340..8.837 rows=635 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=3386
                          ->  Unique  (cost=0.56..1401649.30 rows=13676 width=30) (actual time=0.028..16.981 rows=970 loops=3)
                                Buffers: shared hit=3386
                                ->  Parallel Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..1366576.66 rows=14029054 width=30) (actual time=0.027..13.037 rows=153521 loops=3)
                                      Index Cond: (pid > 'thing:bench.ns01:pid-00048932'::text)
                                      Heap Fetches: 0
                                      Buffers: shared hit=3386
        ->  Limit  (cost=0.56..35.57 rows=1 width=25) (actual time=0.150..0.150 rows=0 loops=500)
              Buffers: shared hit=27022
              ->  Index Scan Backward using things_journal_pkey on things_journal things_journal_1  (cost=0.56..10362.40 rows=296 width=25) (actual time=0.150..0.150 rows=0 loops=500)
                    Index Cond: (pid = things_journal.pid)
                    Filter: ('always-alive'::text = ANY (tags))
                    Rows Removed by Filter: 307
                    Buffers: shared hit=27022
  ->  Memoize  (cost=0.15..0.16 rows=1 width=4) (actual time=0.000..0.000 rows=1 loops=500)
        Cache Key: things_journal_1.tags
        Cache Mode: binary
        Hits: 489  Misses: 11  Evictions: 0  Overflows: 0  Memory Usage: 2kB
        ->  Aggregate  (cost=0.14..0.15 rows=1 width=4) (actual time=0.007..0.007 rows=1 loops=11)
              ->  Function Scan on unnest tag  (cost=0.00..0.13 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=11)
                    Filter: (tag ~ '^priority-[0-9]+$'::text)
                    Rows Removed by Filter: 1
Planning Time: 0.064 ms
Execution Time: 84.114 ms
```

## Same-run control (the clustered layout of the 1M evidence, this container)

Seeded in 15 s into a production-DDL `policies_journal`, dropped afterwards.

| walk | statements | delivered / expected | missing | extra | dup | order | walk time | p50 | p95 | p99 | max (statement #) | max / statement_timeout | host memory (Δ swap-outs, free % before→after) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| same-run control: 1,000,000 pids x 2 events, 1 % clustered at the end, random heap | 2,001 | 10,000 / 10,000 | 0 | 0 | 0 | ok | 3.5 s | 1.50 ms | 1.96 ms | 2.28 ms | 30.73 ms (#637) | — | +0, 53 → 60 % | RELIABLE |
| same-run control, priority order (TAGS) | 2,001 | 10,000 / 10,000 | 0 | 0 | 0 | ok | 3.3 s | 1.47 ms | 1.77 ms | 2.10 ms | 2.48 ms (#1) | — | +0, 60 → 61 % | RELIABLE |

For comparison, `ping-keyset-paging-1m.md` (2026-10-02, Testcontainers defaults, shared_buffers 128MB, warm cache): 2,000 windows, 10,000 delivered, 3.0 s, p50 1.27 ms, p95 2.06 ms, p99 3.50 ms, max 6.52 ms (the ID order only; the priority order was one statement then).


**Result: every walk delivered exactly the expected pids.**
