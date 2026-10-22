# Warm EXPLAIN checks: forms of the capped lone range (a) and of the selective leg's estimate (b)

Run on 2026-10-22 between 02:02 and 02:20 UTC, on the corpus of `load/`, right after the "before" series
(`before/`), before any change to production code. All four "before" runs were reliable: 0 swap-outs, 82–87 % free.

## Verdicts

- **(a) No form of the capped lone range keeps a lone wide range within the noise band of `off` for all four of
  sudo/auth × find/count.** The gate itself works in every form tried. `range_wins` is false at 622,807 entries, and
  `CTE sel_leg` is `(never executed)` behind a `One-Time Filter`. The remaining costs are these:
  - **F5, the gated source** (`FROM (SELECT 1 AS sel_src, stb.* … WHERE range_wins AND EXISTS(sel_leg …) UNION ALL
    SELECT 2 AS sel_src, stb.* FROM search_things stb) st`): auth count 4,206 ms instead of 65 ms, auth find 160.5 ms
    instead of 110.1 ms.
    - The derived table replaces the base relation `search_things st`. The planner then has no column statistics
      for `st.thing_id`, and no parallel paths: the leg branch scans a CTE, which makes the whole `UNION ALL`
      parallel-restricted.
    - So the count hashes all 899,788 rows of `temperature <= 100` (lossy bitmap, spill to disk) instead of probing
      the 573 readable things. The find reads `search_things_pkey` in order instead of the `global_read` bitmap and
      a sort.
  - **f5a** (F5 with the leg branch reading `sel_leg` through an `ARRAY(…)` init plan, no CTE scan in the source):
    same picture (count 4,080 ms).
  - **V4, two complete query blocks behind one-time filters** (`(… WHERE range_wins AND EXISTS(sel_leg) AND <pred>
    ORDER BY … LIMIT …) UNION ALL (… WHERE NOT range_wins AND <pred> ORDER BY … LIMIT …)`; a count sums two
    counts):
    - The executed block is planned exactly like the statement without range legs. With `jit = off`, the auth find
      is 102.9 vs 102.7 ms.
    - Two costs remain, and no statement form avoids them: the gate's count of 50,000 index entries (9.3 ms on its
      own), and, with the image default `jit = on`, the JIT compilation of the second block (the larger statement
      crosses `jit_above_cost`; e.g. 12.2 ms on the sudo find, which needs none without range legs).
    - Result: auth find 127.8 vs 110.1 + 11.0, sudo find 22.1 vs 0.2 + 10, auth count 95.9 vs 65.0 + 10, sudo
      count 6,058.8 vs 6,062.5 ± 606.
- **`ce-c` has no gain to keep on this host.** HEAD auto 123.4 ms vs `off` 128.7 ms. In the timed series too, only
  the first round of the `off` runs is slower. Per-round p95, rounds 2–3: `off` 119.5–129.4 ms, auto 125.7–128.3 ms.
- **A lone range below the cap is slower with its CTE than without.** `lone-mid` (~40,000 things): auth 327.4 vs
  113.2 ms, sudo 144.4 vs 3.8 ms.
- **(b) S1 (`sel_leg` grouped by thing id) passes for the pairs**:
  - shape 1: 121.3 vs 510.8 ms; shape 5: 133.1 vs 486.4 ms. `sel_leg` is estimated at 200 rows (actual 13,869),
    and the city conjunct becomes 5 per-thing index probes.
  - `ce-a`, `ce-a-sudo` and `ce-b` are unchanged.
  - A pair whose range wins with 40,368 ids is unchanged too: 716.6 vs 734.9 ms.
- **S1 on a lone range's sudo find is a large regression:** `lone-mid-sudo` 1,074.9 vs 144.4 ms, `lone-wide-sudo`
  138,751 vs 3,242 ms.
  - Why: with 200 estimated rows the planner drives from every id and sorts all matches.
  - Without S1: a merge join over `search_things_pkey` that stops after 26 rows.
- S2 and S3 were not tried: S1 meets its criteria for the statements it is meant for.

## Method

Each script takes a variant's iteration-0 SQL and binds from the harness's evidence file (`before/A1-off/…` for `off`,
`before/B1-auto/…` for HEAD with range legs) and inlines the binds as untyped literals, as
`../selective-range-leg/psql-checks.md` did. It then rewrites the statement into the candidate form and runs three
`EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` executions in one transaction. Finds run leader-only
(`SET LOCAL max_parallel_workers = 0`), as the harness explains them; counts may use parallel workers. "Warm" is the
mean of executions 2 and 3. Band = max(10 % of the reference, 10 ms). The `jit off` columns add `SET LOCAL jit = off`.

### (a) lone ranges: forms of the capped statement

| variant | off | HEAD auto | F5 (gated source) | f5a (F5, leg via array) | V4 (two blocks) | off, jit off | V4, jit off | off + band |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| lone-wide | 110.1 | 5674.7 | 160.5 | 153.0 | 127.8 | 102.7 | 102.9 | 121.1 |
| lone-wide-sudo | 0.2 | 3241.9 | 9.1 | 9.0 | 22.1 | 0.2 | 8.9 | 10.2 |
| lone-wide-count | 65.0 | 5703.9 | 4206.3 | 4080.2 | 95.9 | 47.1 | 62.9 | 75.0 |
| lone-wide-sudo-count | 6062.5 | 9271.2 | 6173.5 | – | 6058.8 | – | – | 6668.7 |
| ce-c | 128.7 | 123.4 | 120.1 | – | 139.6 | – | – | 141.6 |
| lone-mid | 113.2 | 327.4 | 339.9 | – | 377.3 | – | – | 124.6 |
| lone-mid-sudo | 3.8 | 144.4 | 345.5 | – | 139.2 | – | – | 13.8 |

The count of the gate alone (`SELECT count(*) < 50000 … LIMIT 50000` over temperature 0..100): 9.3 ms.

### (b) S1 (`sel_leg` grouped by thing id)

| variant | off | HEAD auto | S1 on HEAD | F5 | F5 + S1 | HEAD − band |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 890.0 | 510.8 | 121.3 | – | – | 459.7 |
| 5 | 836.5 | 486.4 | 133.1 | – | – | 437.7 |
| ce-a | 0.0 | 0.1 | 0.1 | – | – | -9.9 |
| ce-a-sudo | 0.1 | 0.1 | 0.1 | – | – | -9.9 |
| ce-b | 810.5 | 9.0 | 8.9 | – | – | -1.0 |
| ce-c | 128.7 | 123.4 | 118.1 | 120.1 | 116.7 | 111.0 |
| lone-mid | 113.2 | 327.4 | 323.4 | 339.9 | 344.5 | 294.7 |
| lone-mid-sudo | 3.8 | 144.4 | 1074.9 | 345.5 | 352.4 | 129.9 |
| lone-wide | 110.1 | 5674.7 | 6865.8 | 160.5 | – | 5107.2 |
| lone-wide-sudo | 0.2 | 3241.9 | 138750.8 | 9.1 | – | 2917.7 |
| pair: city Stuttgart + temperature −15..−12.11, sudo (the range wins with 40,368 ids) | – | 734.9 | 716.6 | – | – | – |

## Plan excerpts (execution 3; costs shown as total cost and estimated rows)

### `lone-wide-count`, `off` (64 ms): parallel, per-thing probes of the readable things

```
 Finalize Aggregate  (cost=165711.82 rows=1) (actual time=61.428..63.751 rows=1 loops=1)
   ->  Gather  (cost=165711.81 rows=2) (actual time=61.278..63.743 rows=3 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         ->  Partial Aggregate  (cost=164711.61 rows=1) (actual time=55.017..55.018 rows=1 loops=3)
               ->  Nested Loop Semi Join  (cost=164711.46 rows=56) (actual time=11.586..54.994 rows=120 loops=3)
                     ->  Nested Loop Semi Join  (cost=153697.10 rows=56) (actual time=11.578..54.645 rows=120 loops=3)
                           ->  Parallel Bitmap Heap Scan on search_things st  (cost=75609.32 rows=1355) (actual time=11.524..50.936 rows=191 loops=3)
                                 Filter: ((NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,…
                                 Heap Blocks: exact=10310
                                 ->  Bitmap Index Scan on st_global_read  (cost=205.00 rows=29333) (actual time=3.624..3.624 rows=29411 loops=1)
                                       Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])…
                           ->  Index Scan using search_flat_pkey on search_flat sf  (cost=276.18 rows=5) (actual time=0.019..0.019 rows=1 loops=573)
                                 Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                                 Filter: (val_num >= '0'::numeric)…
                     ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=255.89 rows=2) (actual time=0.003..0.003 rows=1 loops=361)
                           Index Cond: ((thing_id = sf.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                           Filter: (val_num <= '100'::numeric)…
 JIT:
   Timing: Generation 1.910 ms, Inlining 0.000 ms, Optimization 1.049 ms, Emission 26.427 ms, Total 29.386 ms
 Execution Time: 64.365 ms

```

### `lone-wide-count`, F5 (4,214 ms): the gate works, the plan above the derived table does not

```
 Aggregate  (cost=475588.82 rows=1) (actual time=4210.961..4210.965 rows=1 loops=1)
   CTE sel_pick
     ->  Aggregate  (cost=3867.94 rows=1) (actual time=10.400..10.401 rows=1 loops=1)
           ->  Limit  (cost=3242.92 rows=50000) (actual time=0.040..9.350 rows=50000 loops=1)
                 ->  Append  (cost=3261.41 rows=50285) (actual time=0.034..7.791 rows=50000 loops=1)
                       ->  Index Only Scan using sf_num on search_flat sfc  (cost=1062.50 rows=20175) (actual time=0.034..6.239 rows=50000 loops=1)
                             Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '0'::numeric) AND (val_num <…
                       ->  Index Only Scan using sf_multi on search_flat sfc_1  (cost=1947.48 rows=30110) (never executed)
                             Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   CTE sel_leg
     ->  Append  (cost=76127.24 rows=50285) (never executed)
           ->  Bitmap Heap Scan on search_flat sfc_2  (cost=73928.34 rows=20175) (never executed)
                 ->  Bitmap Index Scan on sf_num  (cost=856.75 rows=20175) (never executed)
                       Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '0'::numeric) AND (val_num <…
           ->  Index Only Scan using sf_multi on search_flat sfc_3  (cost=1947.48 rows=30110) (never executed)
                 Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   InitPlan 3 (returns $2)
     ->  CTE Scan on sel_pick sp  (cost=0.02 rows=1) (actual time=0.000..0.000 rows=1 loops=1)
   ->  Nested Loop Semi Join  (cost=395589.33 rows=1708) (actual time=4064.062..4210.928 rows=361 loops=1)
         ->  Hash Join  (cost=300752.17 rows=1708) (actual time=4064.016..4202.391 rows=505 loops=1)
               Hash Cond: ("*SELECT* 1".thing_id = sf_1.thing_id)
               ->  Append  (cost=80418.46 rows=3417) (actual time=33.118..124.568 rows=573 loops=1)
                     ->  Subquery Scan on "*SELECT* 1"  (cost=2867.07 rows=164) (actual time=27.940..27.942 rows=0 loops=1)
                           ->  Result  (cost=2865.43 rows=164) (actual time=27.940..27.941 rows=0 loops=1)
                                 One-Time Filter: $3…
                                 InitPlan 4 (returns $3)
                                   ->  CTE Scan on sel_pick sp_1  (cost=0.02 rows=1) (actual time=10.402..10.402 rows=1 loops=1)
                                 ->  Nested Loop  (cost=2865.41 rows=164) (never executed)
                                       ->  HashAggregate  (cost=1133.41 rows=200) (never executed)
                                             Group Key: sl.thing_id
                                             ->  CTE Scan on sel_leg sl  (cost=1005.70 rows=50285) (never executed)
                                       ->  Index Scan using search_things_pkey on search_things stb_1  (cost=8.65 rows=1) (never executed)
                                             Index Cond: (thing_id = sl.thing_id)…
                                             Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALE…
                     ->  Result  (cost=77534.30 rows=3253) (actual time=5.177..96.603 rows=573 loops=1)
                           One-Time Filter: (NOT $2)…
                           ->  Bitmap Heap Scan on search_things stb  (cost=77534.30 rows=3253) (actual time=5.174..96.569 rows=573 loops=1)
                                 Filter: ((NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,…
                                 Heap Blocks: exact=27130
                                 ->  Bitmap Index Scan on st_global_read  (cost=205.00 rows=29333) (actual time=2.541..2.541 rows=29411 loops=1)
                                       Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])…
               ->  Hash  (cost=219892.82 rows=33033) (actual time=4030.776..4030.776 rows=899788 loops=1)
                     Buckets: 131072 (originally 65536)  Batches: 16 (originally 1)  Memory Usage: 7169kB
                     ->  HashAggregate  (cost=219892.82 rows=33033) (actual time=3811.741..3954.540 rows=899788 loops=1)
                           Group Key: sf_1.thing_id
                           Batches: 25  Memory Usage: 8281kB  Disk Usage: 52192kB
                           ->  Bitmap Heap Scan on search_flat sf_1  (cost=219396.88 rows=66247) (actual time=93.216..3614.713 rows=899788 loops=1)
                                 Heap Blocks: exact=54033 lossy=630192
                                 ->  Bitmap Index Scan on sf_num  (cost=2643.03 rows=66247) (actual time=87.114..87.114 rows=899788 loops=1)
                                       Index Cond: ((wpath = '/features/env/properties/temperature'::text COLLATE "C") AND (val_num <= '100'::numeric))…
         ->  Index Scan using search_flat_pkey on search_flat sf  (cost=275.74 rows=5) (actual time=0.017..0.017 rows=1 loops=505)
               Index Cond: ((thing_id = "*SELECT* 1".thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLAT…
               Filter: (val_num >= '0'::numeric)…
 JIT:
   Timing: Generation 1.314 ms, Inlining 0.000 ms, Optimization 0.701 ms, Emission 18.009 ms, Total 20.024 ms
 Execution Time: 4214.431 ms

```

### `lone-wide-sudo`, V4 (21.9 ms): `off`'s plan in the second block; the count and JIT are the difference

```
 Append  (cost=143945.09 rows=52) (actual time=20.775..21.018 rows=26 loops=1)
   CTE sel_pick
     ->  Aggregate  (cost=3867.94 rows=1) (actual time=9.292..9.293 rows=1 loops=1)
           ->  Limit  (cost=3242.92 rows=50000) (actual time=0.013..8.253 rows=50000 loops=1)
                 ->  Append  (cost=3261.41 rows=50285) (actual time=0.012..6.687 rows=50000 loops=1)
                       ->  Index Only Scan using sf_num on search_flat sfc  (cost=1062.50 rows=20175) (actual time=0.012..5.127 rows=50000 loops=1)
                             Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '0'::numeric) AND (val_num <…
                       ->  Index Only Scan using sf_multi on search_flat sfc_1  (cost=1947.48 rows=30110) (never executed)
                             Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   CTE sel_leg
     ->  Append  (cost=76127.24 rows=50285) (never executed)
           ->  Bitmap Heap Scan on search_flat sfc_2  (cost=73928.34 rows=20175) (never executed)
                 ->  Bitmap Index Scan on sf_num  (cost=856.75 rows=20175) (never executed)
                       Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '0'::numeric) AND (val_num <…
           ->  Index Only Scan using sf_multi on search_flat sfc_3  (cost=1947.48 rows=30110) (never executed)
                 Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   ->  Limit  (cost=23228.21 rows=26) (actual time=20.748..20.749 rows=0 loops=1)
         InitPlan 3 (returns $2)
           ->  CTE Scan on sel_pick sp  (cost=0.02 rows=1) (actual time=9.294..9.294 rows=1 loops=1)
         ->  Result  (cost=10679337.35 rows=12571) (actual time=9.297..9.298 rows=0 loops=1)
               One-Time Filter: $2…
               ->  Nested Loop Semi Join  (cost=10679337.35 rows=12571) (never executed)
                     Join Filter: (sl.thing_id = sf_1.thing_id)…
                     ->  Nested Loop Semi Join  (cost=5933559.13 rows=25142) (never executed)
                           ->  Merge Join  (cost=3328081.62 rows=50285) (never executed)
                                 Merge Cond: (st.thing_id = sl.thing_id)
                                 ->  Index Scan using search_things_pkey on search_things st  (cost=3324438.83 rows=999495) (never executed)
                                 ->  Sort  (cost=1141.56 rows=200) (never executed)
                                       Sort Key: sl.thing_id COLLATE "C"
                                       ->  HashAggregate  (cost=1133.41 rows=200) (never executed)
                                             Group Key: sl.thing_id
                                             ->  CTE Scan on sel_leg sl  (cost=1005.70 rows=50285) (never executed)
                           ->  Index Scan using search_flat_pkey on search_flat sf  (cost=257.12 rows=5) (never executed)
                                 Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                                 Filter: (val_num >= '0'::numeric)…
                     ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=255.53 rows=2) (never executed)
                           Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                           Filter: (val_num <= '100'::numeric)…
   ->  Limit  (cost=40721.43 rows=26) (actual time=0.027..0.267 rows=26 loops=1)
         InitPlan 4 (returns $3)
           ->  CTE Scan on sel_pick sp_1  (cost=0.02 rows=1) (actual time=0.000..0.000 rows=1 loops=1)
         ->  Result  (cost=64784693.15 rows=41366) (actual time=0.025..0.264 rows=26 loops=1)
               One-Time Filter: (NOT $3)…
               ->  Nested Loop Semi Join  (cost=64784693.15 rows=41366) (actual time=0.023..0.261 rows=26 loops=1)
                     Join Filter: (sf_2.thing_id = sf_3.thing_id)…
                     ->  Nested Loop Semi Join  (cost=56660058.86 rows=41366) (actual time=0.016..0.197 rows=26 loops=1)
                           ->  Index Scan using search_things_pkey on search_things st_1  (cost=3324438.83 rows=999495) (actual time=0.004..0.016 rows=46 loops=1)
                           ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=255.53 rows=5) (actual time=0.004..0.004 rows=1 loops=46)
                                 Index Cond: ((thing_id = st_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                                 Filter: (val_num >= '0'::numeric)…
                     ->  Index Scan using search_flat_pkey on search_flat sf_3  (cost=255.53 rows=2) (actual time=0.002..0.002 rows=1 loops=26)
                           Index Cond: ((thing_id = st_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                           Filter: (val_num <= '100'::numeric)…
 JIT:
   Timing: Generation 0.779 ms, Inlining 0.000 ms, Optimization 0.453 ms, Emission 11.009 ms, Total 12.241 ms
 Execution Time: 21.856 ms

```

### `lone-mid-sudo`, HEAD auto (142.9 ms): early stop over `search_things_pkey`

```
 Limit  (cost=34803.11 rows=26) (actual time=141.529..142.725 rows=26 loops=1)
   CTE sel_leg
     ->  Append  (cost=6595.50 rows=31240) (actual time=8.697..121.850 rows=40368 loops=1)
           ->  Bitmap Heap Scan on search_flat sfc  (cost=4491.82 rows=1130) (actual time=8.697..120.544 rows=40368 loops=1)
                 Heap Blocks: exact=39907
                 ->  Bitmap Index Scan on sf_num  (cost=50.69 rows=1130) (actual time=4.971..4.971 rows=40368 loops=1)
                       Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_n…
           ->  Index Only Scan using sf_multi on search_flat sfc_1  (cost=1947.48 rows=30110) (actual time=0.019..0.019 rows=0 loops=1)
                 Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   ->  Nested Loop Semi Join  (cost=8259226.07 rows=7810) (actual time=141.529..142.722 rows=26 loops=1)
         Join Filter: (sl.thing_id = sf_1.thing_id)…
         ->  Nested Loop Semi Join  (cost=4930873.29 rows=15620) (actual time=141.523..142.656 rows=26 loops=1)
               ->  Merge Join  (cost=3327653.11 rows=31240) (actual time=141.496..142.352 rows=26 loops=1)
                     Merge Cond: (st.thing_id = sl.thing_id)
                     ->  Index Scan using search_things_pkey on search_things st  (cost=3324438.83 rows=999495) (actual time=0.002..0.880 rows=607 loops=1)
                     ->  Sort  (cost=713.04 rows=200) (actual time=141.445..141.446 rows=26 loops=1)
                           Sort Key: sl.thing_id COLLATE "C"
                           ->  HashAggregate  (cost=704.90 rows=200) (actual time=131.465..132.977 rows=40368 loops=1)
                                 Group Key: sl.thing_id
                                 Batches: 1  Memory Usage: 4641kB
                                 ->  CTE Scan on sel_leg sl  (cost=624.80 rows=31240) (actual time=8.699..124.611 rows=40368 loops=1)
               ->  Index Scan using search_flat_pkey on search_flat sf  (cost=258.15 rows=5) (actual time=0.011..0.011 rows=1 loops=26)
                     Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                     Filter: (val_num >= '-15.0'::numeric)…
         ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=255.53 rows=1) (actual time=0.002..0.002 rows=1 loops=26)
               Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
               Filter: (val_num <= '-12.11'::numeric)…
 Execution Time: 142.948 ms

```

### `lone-mid-sudo`, S1 (910.0 ms): the 200-row estimate drives from all 40,368 ids and sorts them

```
 Limit  (cost=41009.83 rows=26) (actual time=909.701..909.704 rows=26 loops=1)
   CTE sel_leg
     ->  HashAggregate  (cost=6675.60 rows=200) (actual time=119.235..121.000 rows=40368 loops=1)
           Group Key: sfc.thing_id
           Batches: 1  Memory Usage: 4641kB
           ->  Append  (cost=6595.50 rows=31240) (actual time=7.972..113.274 rows=40368 loops=1)
                 ->  Bitmap Heap Scan on search_flat sfc  (cost=4491.82 rows=1130) (actual time=7.972..112.111 rows=40368 loops=1)
                       Heap Blocks: exact=39907
                       ->  Bitmap Index Scan on sf_num  (cost=50.69 rows=1130) (actual time=4.689..4.689 rows=40368 loops=1)
                             Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_n…
                 ->  Index Only Scan using sf_multi on search_flat sfc_1  (cost=1947.48 rows=30110) (actual time=0.025..0.025 rows=0 loops=1)
                       Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   ->  Sort  (cost=34334.29 rows=50) (actual time=909.700..909.701 rows=26 loops=1)
         Sort Key: st.thing_id COLLATE "C"
         ->  Nested Loop Semi Join  (cost=34332.76 rows=50) (actual time=127.365..907.216 rows=40368 loops=1)
               Join Filter: (sl.thing_id = sf_1.thing_id)…
               ->  Nested Loop Semi Join  (cost=13024.48 rows=100) (actual time=127.361..816.464 rows=40368 loops=1)
                     ->  Nested Loop  (cost=1716.00 rows=200) (actual time=127.333..357.672 rows=40368 loops=1)
                           ->  HashAggregate  (cost=6.50 rows=200) (actual time=127.312..129.872 rows=40368 loops=1)
                                 Group Key: sl.thing_id
                                 Batches: 1  Memory Usage: 4641kB
                                 ->  CTE Scan on sel_leg sl  (cost=4.00 rows=200) (actual time=119.237..123.534 rows=40368 loops=1)
                           ->  Index Scan using search_things_pkey on search_things st  (cost=8.55 rows=1) (actual time=0.005..0.005 rows=1 loops=40368)
                                 Index Cond: (thing_id = sl.thing_id)…
                     ->  Index Scan using search_flat_pkey on search_flat sf  (cost=284.71 rows=5) (actual time=0.011..0.011 rows=1 loops=40368)
                           Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                           Filter: (val_num >= '-15.0'::numeric)…
               ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=255.53 rows=1) (actual time=0.002..0.002 rows=1 loops=40368)
                     Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                     Filter: (val_num <= '-12.11'::numeric)…
 Execution Time: 909.986 ms

```

### Shape 1, S1 (120.9 ms): `sel_leg` estimated at 200 rows, the city as per-thing probes

```
                       InitPlan 5 (returns $4)
                         ->  CTE Scan on sel_pick sp_2  (cost=0.02 rows=1) (actual time=0.000..0.001 rows=1 loops=1)
                       ->  Index Only Scan using sf_multi on search_flat sfc_5  (cost=1947.50 rows=30110) (actual time=0.007..0.007 rows=0 loops=1)
                             Index Cond: (wpath = '/features/env/properties/temperature'::text)…
   ->  Sort  (cost=2384.98 rows=1) (actual time=120.867..120.868 rows=0 loops=1)
         Sort Key: st.thing_id COLLATE "C"
         ->  Nested Loop Semi Join  (cost=2384.96 rows=1) (actual time=120.864..120.865 rows=0 loops=1)
               Join Filter: (sl.thing_id = sf_2.thing_id)…
               ->  Nested Loop Semi Join  (cost=2122.68 rows=1) (actual time=120.864..120.865 rows=0 loops=1)
                     Join Filter: (sl.thing_id = sf.thing_id)…
                     ->  Nested Loop Semi Join  (cost=1859.43 rows=1) (actual time=54.741..120.841 rows=5 loops=1)
                           ->  Nested Loop  (cost=1747.04 rows=1) (actual time=54.721..120.756 rows=5 loops=1)
                                 ->  HashAggregate  (cost=6.50 rows=200) (actual time=50.691..51.430 rows=13869 loops=1)
                                       Group Key: sl.thing_id
                                       Batches: 1  Memory Usage: 1825kB
                                       ->  CTE Scan on sel_leg sl  (cost=4.00 rows=200) (actual time=47.805..49.244 rows=13869 loops=1)
                                 ->  Index Scan using search_things_pkey on search_things st  (cost=8.69 rows=1) (actual time=0.005..0.005 rows=0 loops=13869)
                                       Index Cond: (thing_id = sl.thing_id)…
                                       Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALE…
                           ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=284.71 rows=5) (actual time=0.016..0.016 rows=1 loops=5)
                                 Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                                 Filter: (val_num >= '-15.0'::numeric)…
                     ->  Index Scan using search_flat_pkey on search_flat sf  (cost=263.24 rows=1) (actual time=0.004..0.004 rows=0 loops=5)
                           Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/attributes/location/city'::text))…
                           Filter: (val_text = 'Stuttgart'::text)…
               ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=262.27 rows=1) (never executed)
                     Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))…
                     Filter: (val_num <= '-14.01'::numeric)…
 Execution Time: 120.947 ms

```
