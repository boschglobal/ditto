# Probe: the grouped pick when the point leg wins with tens of thousands of ids

## Purpose

Grouping the pick's `sel_leg` (`SELECT sli.thing_id FROM (…) sli GROUP BY sli.thing_id`) replaces its row estimate by
the planner's default group count (200). That fixes the case it was made for: the range wins and the ungrouped estimate
carries the multi-valued branch's column-wide over-estimate (shapes 1/5). The same 200-row estimate made a **lone**
range's sorted sudo find drive from every id instead of stopping early, so no lone range is grouped (or materialised)
any more. The pick can still meet that setting when the **point** leg wins with many ids. No bench variant covers it, so
this probe measures it: city Stuttgart (89,901 entries, counted up to 50,000) with temperature 0..100 (622,807 things,
counted up to 50,001), so the point leg wins, as find with authorization (`pw-auth`), sudo find (`pw-sudo`) and count
with authorization (`pw-count`). It also re-measures the earlier spike's pair statements on this load.

## Method

- Warm `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` in `psql` in the bench container, binds inlined, three executions in one
  transaction (`BEGIN … ROLLBACK`); **warm = mean of executions 2 and 3**. Finds run leader-only as the shipped find
  does (`SET LOCAL max_parallel_workers = 0`); counts run with parallel workers as the shipped count does.
- HEAD = the start code's ungrouped pick; S1 = the grouped pick. The S1 statements of `1`, `5`, `ce-b` and `ce-a-sudo`
  are byte-identical to the final code's render with the binds inlined (checked against `after/B1-auto`).
- `pw-*` are the spike's scripts for shape 1 (auth find), shape 5 (auth count) and the range-wins sudo pair with only
  the temperature bounds changed to 0..100.
- Run 2026-10-22 ~12:42 UTC on the "after" load, after the timed series; host sample before and after: RELIABLE
  (0 swap-outs, 71 % / 73 % free).
- Band = max(10 % of HEAD warm, 10 ms). Gate for shipping the grouping: S1 ≤ HEAD + band for `pw-sudo`, `pw-auth` and
  `pw-count`.

## Result

| variant | HEAD warm (executions 1/2/3) | S1 warm (executions 1/2/3) | band | verdict | `sel_leg` node est/actual rows HEAD → S1 | city conjunct HEAD → S1 |
|---|---:|---:|---:|---|---|---|
| 1 | 496.6 (581.0 / 501.3 / 491.9) | 126.4 (142.9 / 125.1 / 127.6) | 50 | S1 <= HEAD + band | Append 33536/13869 → HashAggregate 200/13869 | bitmap → probes |
| 5 | 495.4 (511.9 / 507.5 / 483.3) | 123.6 (134.3 / 124.7 / 122.6) | 50 | S1 <= HEAD + band | Append 33536/13869 → HashAggregate 200/13869 | bitmap → probes |
| ce-a-sudo | 0.1 (7.4 / 0.1 / 0.1) | 0.1 (0.3 / 0.1 / 0.1) | 10 | S1 <= HEAD + band | Append 53651/7 → HashAggregate 200/7 | ? → ? |
| ce-b | 7.4 (16.7 / 7.7 / 7.1) | 8.1 (9.9 / 8.8 / 7.5) | 10 | S1 <= HEAD + band | Append 33188/1372 → HashAggregate 200/1372 | bitmap → probes |
| pairwide-sudo | 733.2 (1372.4 / 728.0 / 738.4) | 889.3 (799.1 / 943.7 / 834.9) | 73 | S1 slower beyond band ¹ | Append 34273/40368 → HashAggregate 200/40368 | bitmap → bitmap |
| pw-sudo | 1225.5 (1726.5 / 1223.4 / 1227.5) | 1230.8 (1286.0 / 1235.0 / 1226.5) | 123 | S1 <= HEAD + band | Append 54956/89901 → HashAggregate 200/89901 | bitmap → bitmap |
| pw-auth | 1211.7 (1215.5 / 1218.8 / 1204.7) | 834.4 (865.2 / 834.0 / 834.9) | 121 | S1 <= HEAD + band | Append 54956/89901 → HashAggregate 200/89901 | bitmap → probes |
| pw-count | 1161.5 (1185.3 / 1163.4 / 1159.7) | 831.8 (841.5 / 826.9 / 836.6) | 116 | S1 <= HEAD + band | Append 54956/89901 → HashAggregate 200/89901 | bitmap → probes |

In every `pw-*` plan the point branch ran (89,901 rows) and both range branches were `(never executed)`. "probes" = the
city conjunct is checked per candidate through `search_flat_pkey`; "bitmap" = a bitmap scan of every Stuttgart row.

¹ `pairwide-sudo` (the range wins with 40,368 ids, sudo find) was repeated twice, in alternating order (~12:45 UTC,
RELIABLE): HEAD 709.9 / 716.1, S1 723.1 / 718.2 ms warm, both within the band. The S1 plan of the first pair is the
same plan as in the repeats (node lines, conditions); only its timing differs. The earlier spike measured HEAD
734.9, S1 716.6 ms.

**Verdict:** the gate holds. With the point leg winning, S1 is equal (`pw-sudo`) or faster (`pw-auth` −31 %,
`pw-count` −28 %): the 200-row estimate turns the city conjunct into per-candidate probes, while HEAD's estimate
(54,956) keeps the whole-city bitmap scan. Shapes 1/5 confirm the timed runs (497 → 126 ms, 495 → 124 ms).

## Plan excerpts (execution 3; the main statement below the CTEs, buffer lines dropped)

### Shape 1 (auth find), HEAD

````
Limit  (cost=16959.63..17335.17 rows=1 width=90) (actual time=491.462..491.467 rows=0 loops=1)
  …
  CTE sel_leg
    ->  Append  (cost=41.98..8892.61 rows=33536 width=41) (actual time=9.539..47.412 rows=13869 loops=1)
  …
  ->  Nested Loop Semi Join  (cost=7731.14..8106.68 rows=1 width=90) (actual time=491.461..491.462 rows=0 loops=1)
        Join Filter: (sl.thing_id = sf_2.thing_id)
        ->  Nested Loop Semi Join  (cost=7730.45..7840.78 rows=1 width=163) (actual time=491.461..491.462 rows=0 loops=1)
              ->  Merge Semi Join  (cost=7729.75..7736.48 rows=1 width=122) (actual time=491.460..491.461 rows=0 loops=1)
                    Merge Cond: (sl.thing_id = sf.thing_id)
                    ->  Sort  (cost=2495.59..2495.69 rows=37 width=81) (actual time=122.306..122.307 rows=5 loops=1)
                          Sort Key: st.thing_id COLLATE "C"
                          Sort Method: quicksort  Memory: 25kB
                          ->  Nested Loop  (cost=755.11..2494.63 rows=37 width=81) (actual time=54.414..122.299 rows=5 loops=1)
                                ->  HashAggregate  (cost=754.56..756.56 rows=200 width=32) (actual time=50.054..51.356 rows=13869 loops=1)
                                      Group Key: sl.thing_id
                                      ->  CTE Scan on sel_leg sl  (cost=0.00..670.72 rows=33536 width=32) (actual time=9.540..48.252 rows=13869 loops=1)
                                ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.69 rows=1 width=49) (actual time=0.005..0.005 rows=0 loops=13869)
                                      Index Cond: (thing_id = sl.thing_id)
                                      Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,city,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,city,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
                                      Rows Removed by Filter: 1
                    ->  Sort  (cost=5234.16..5237.43 rows=1307 width=41) (actual time=365.951..367.858 rows=61454 loops=1)
                          Sort Key: sf.thing_id COLLATE "C"
                          Sort Method: external merge  Disk: 3992kB
                          ->  Bitmap Heap Scan on search_flat sf  (cost=41.96..5166.51 rows=1307 width=41) (actual time=9.739..346.060 rows=89901 loops=1)
                                Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                Rows Removed by Index Recheck: 1933925
                                Heap Blocks: exact=50982 lossy=36654
                                ->  Bitmap Index Scan on sf_text  (cost=0.00..41.63 rows=1307 width=0) (actual time=5.195..5.195 rows=89901 loops=1)
                                      Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
              ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..267.15 rows=5 width=41) (never executed)
                    Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                    Filter: (val_num >= '-15.0'::numeric)
        ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..265.89 rows=1 width=41) (never executed)
              Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
              Filter: (val_num <= '-14.01'::numeric)
Settings: max_parallel_workers = '0'
Execution Time: 491.906 ms
````

### Shape 1 (auth find), S1

````
Limit  (cost=11714.11..11714.12 rows=1 width=90) (actual time=127.511..127.517 rows=0 loops=1)
  …
  CTE sel_leg
    ->  HashAggregate  (cost=8976.45..8978.45 rows=200 width=41) (actual time=48.853..49.424 rows=13869 loops=1)
  …
  ->  Sort  (cost=2399.78..2399.79 rows=1 width=90) (actual time=127.511..127.512 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        ->  Nested Loop Semi Join  (cost=7.13..2399.77 rows=1 width=90) (actual time=127.509..127.509 rows=0 loops=1)
              Join Filter: (sl.thing_id = sf_2.thing_id)
              ->  Nested Loop Semi Join  (cost=6.43..2133.87 rows=1 width=163) (actual time=127.508..127.509 rows=0 loops=1)
                    Join Filter: (sl.thing_id = sf.thing_id)
                    ->  Nested Loop Semi Join  (cost=5.74..1859.68 rows=1 width=122) (actual time=55.739..127.485 rows=5 loops=1)
                          ->  Nested Loop  (cost=5.05..1747.01 rows=1 width=81) (actual time=55.720..127.396 rows=5 loops=1)
                                ->  HashAggregate  (cost=4.50..6.50 rows=200 width=32) (actual time=51.758..52.557 rows=13869 loops=1)
                                      Group Key: sl.thing_id
                                      ->  CTE Scan on sel_leg sl  (cost=0.00..4.00 rows=200 width=32) (actual time=48.853..50.261 rows=13869 loops=1)
                                ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.69 rows=1 width=49) (actual time=0.005..0.005 rows=0 loops=13869)
                                      Index Cond: (thing_id = sl.thing_id)
                                      Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,city,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,city,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
                                      Rows Removed by Filter: 1
                          ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..288.68 rows=5 width=41) (actual time=0.017..0.017 rows=1 loops=5)
                                Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                                Filter: (val_num >= '-15.0'::numeric)
                    ->  Index Scan using search_flat_pkey on search_flat sf  (cost=0.69..274.18 rows=1 width=41) (actual time=0.004..0.004 rows=0 loops=5)
                          Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/attributes/location/city'::text))
                          Filter: (val_text = 'Stuttgart'::text)
                          Rows Removed by Filter: 1
              ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..265.89 rows=1 width=41) (never executed)
                    Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                    Filter: (val_num <= '-14.01'::numeric)
Settings: max_parallel_workers = '0'
Execution Time: 127.592 ms
````

### `pw-auth`, HEAD

````
Limit  (cost=95691.20..96058.44 rows=1 width=90) (actual time=1200.370..1204.026 rows=26 loops=1)
  …
  CTE sel_leg
    ->  Append  (cost=41.98..86992.11 rows=54956 width=41) (actual time=24.526..383.253 rows=89901 loops=1)
  …
  ->  Nested Loop Semi Join  (cost=8213.94..8581.17 rows=1 width=90) (actual time=1200.370..1204.021 rows=26 loops=1)
        ->  Nested Loop Semi Join  (cost=8213.24..8475.58 rows=1 width=163) (actual time=1200.366..1203.911 rows=37 loops=1)
              ->  Merge Semi Join  (cost=8212.55..8219.41 rows=2 width=122) (actual time=1200.330..1203.225 rows=42 loops=1)
                    Merge Cond: (sl.thing_id = sf.thing_id)
                    ->  Sort  (cost=2978.39..2978.54 rows=60 width=81) (actual time=817.094..817.096 rows=42 loops=1)
                          Sort Key: st.thing_id COLLATE "C"
                          Sort Method: quicksort  Memory: 32kB
                          ->  Nested Loop  (cost=1237.06..2976.62 rows=60 width=81) (actual time=409.548..817.054 rows=63 loops=1)
                                ->  HashAggregate  (cost=1236.51..1238.51 rows=200 width=32) (actual time=408.591..420.080 rows=89901 loops=1)
                                      Group Key: sl.thing_id
                                      ->  CTE Scan on sel_leg sl  (cost=0.00..1099.12 rows=54956 width=32) (actual time=24.527..391.216 rows=89901 loops=1)
                                ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.69 rows=1 width=49) (actual time=0.004..0.004 rows=0 loops=89901)
                                      Index Cond: (thing_id = sl.thing_id)
                                      Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,city,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,city,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
                                      Rows Removed by Filter: 1
                    ->  Sort  (cost=5234.16..5237.43 rows=1307 width=41) (actual time=383.213..384.952 rows=55266 loops=1)
                          Sort Key: sf.thing_id COLLATE "C"
                          Sort Method: external merge  Disk: 3992kB
                          ->  Bitmap Heap Scan on search_flat sf  (cost=41.96..5166.51 rows=1307 width=41) (actual time=10.179..362.955 rows=89901 loops=1)
                                Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                Rows Removed by Index Recheck: 1933925
                                Heap Blocks: exact=50982 lossy=36654
                                ->  Bitmap Index Scan on sf_text  (cost=0.00..41.63 rows=1307 width=0) (actual time=5.323..5.323 rows=89901 loops=1)
                                      Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
              ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..266.56 rows=2 width=41) (actual time=0.016..0.016 rows=1 loops=42)
                    Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                    Filter: (val_num <= '100'::numeric)
        ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..266.56 rows=5 width=41) (actual time=0.003..0.003 rows=1 loops=37)
              Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
              Filter: (val_num >= '0'::numeric)
              Rows Removed by Filter: 0
Settings: max_parallel_workers = '0'
Execution Time: 1204.707 ms
````

### `pw-auth`, S1

````
Limit  (cost=90018.12..90018.12 rows=1 width=90) (actual time=834.399..834.405 rows=26 loops=1)
  …
  CTE sel_leg
    ->  HashAggregate  (cost=87129.50..87131.50 rows=200 width=41) (actual time=395.877..402.060 rows=89901 loops=1)
  …
  ->  Sort  (cost=2401.46..2401.47 rows=1 width=90) (actual time=834.398..834.399 rows=26 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 29kB
        ->  Nested Loop Semi Join  (cost=7.13..2401.45 rows=1 width=90) (actual time=430.547..834.381 rows=37 loops=1)
              Join Filter: (sl.thing_id = sf_2.thing_id)
              ->  Nested Loop Semi Join  (cost=6.43..2135.53 rows=1 width=163) (actual time=430.543..834.270 rows=37 loops=1)
                    Join Filter: (sl.thing_id = sf.thing_id)
                    ->  Nested Loop Semi Join  (cost=5.74..1861.33 rows=1 width=122) (actual time=430.535..834.126 rows=37 loops=1)
                          ->  Nested Loop  (cost=5.05..1747.01 rows=1 width=81) (actual time=419.596..832.920 rows=63 loops=1)
                                ->  HashAggregate  (cost=4.50..6.50 rows=200 width=32) (actual time=418.589..426.050 rows=89901 loops=1)
                                      Group Key: sl.thing_id
                                      ->  CTE Scan on sel_leg sl  (cost=0.00..4.00 rows=200 width=32) (actual time=395.877..409.415 rows=89901 loops=1)
                                ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.69 rows=1 width=49) (actual time=0.004..0.004 rows=0 loops=89901)
                                      Index Cond: (thing_id = sl.thing_id)
                                      Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,city,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,city,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
                                      Rows Removed by Filter: 1
                          ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..288.68 rows=5 width=41) (actual time=0.019..0.019 rows=1 loops=63)
                                Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                                Filter: (val_num >= '0'::numeric)
                                Rows Removed by Filter: 0
                    ->  Index Scan using search_flat_pkey on search_flat sf  (cost=0.69..274.18 rows=1 width=41) (actual time=0.003..0.003 rows=1 loops=37)
                          Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/attributes/location/city'::text))
                          Filter: (val_text = 'Stuttgart'::text)
              ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..265.90 rows=2 width=41) (actual time=0.002..0.002 rows=1 loops=37)
                    Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                    Filter: (val_num <= '100'::numeric)
Settings: max_parallel_workers = '0'
Execution Time: 834.871 ms
````

### `pw-sudo`, HEAD

````
Limit  (cost=102540.71..114746.36 rows=26 width=90) (actual time=1225.073..1225.590 rows=26 loops=1)
  …
  CTE sel_leg
    ->  Append  (cost=41.98..86992.11 rows=54956 width=41) (actual time=24.954..373.286 rows=89901 loops=1)
  …
  ->  Nested Loop Semi Join  (cost=15063.45..217864.90 rows=432 width=90) (actual time=1211.476..1211.987 rows=26 loops=1)
        ->  Nested Loop Semi Join  (cost=15062.75..107198.54 rows=864 width=163) (actual time=1211.469..1211.916 rows=26 loops=1)
              ->  Merge Semi Join  (cost=15062.06..15360.66 rows=1728 width=122) (actual time=1211.433..1211.446 rows=36 loops=1)
                    Merge Cond: (sl.thing_id = sf.thing_id)
                    ->  Sort  (cost=9827.90..9965.29 rows=54956 width=81) (actual time=836.148..836.153 rows=36 loops=1)
                          Sort Key: st.thing_id COLLATE "C"
                          Sort Method: external merge  Disk: 9400kB
                          ->  Nested Loop  (cost=1237.06..2869.22 rows=54956 width=81) (actual time=399.275..810.802 rows=89901 loops=1)
                                ->  HashAggregate  (cost=1236.51..1238.51 rows=200 width=32) (actual time=399.248..411.231 rows=89901 loops=1)
                                      Group Key: sl.thing_id
                                      ->  CTE Scan on sel_leg sl  (cost=0.00..1099.12 rows=54956 width=32) (actual time=24.955..381.313 rows=89901 loops=1)
                                ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=49) (actual time=0.004..0.004 rows=1 loops=89901)
                                      Index Cond: (thing_id = sl.thing_id)
                    ->  Sort  (cost=5234.16..5237.43 rows=1307 width=41) (actual time=375.260..375.263 rows=36 loops=1)
                          Sort Key: sf.thing_id COLLATE "C"
                          Sort Method: external merge  Disk: 3992kB
                          ->  Bitmap Heap Scan on search_flat sf  (cost=41.96..5166.51 rows=1307 width=41) (actual time=10.064..354.720 rows=89901 loops=1)
                                Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                Rows Removed by Index Recheck: 1933925
                                Heap Blocks: exact=50982 lossy=36654
                                ->  Bitmap Index Scan on sf_text  (cost=0.00..41.63 rows=1307 width=0) (actual time=5.233..5.233 rows=89901 loops=1)
                                      Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
              ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..266.56 rows=5 width=41) (actual time=0.013..0.013 rows=1 loops=36)
                    Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                    Filter: (val_num >= '0'::numeric)
                    Rows Removed by Filter: 0
        ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..266.56 rows=2 width=41) (actual time=0.002..0.002 rows=1 loops=26)
              Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
              Filter: (val_num <= '100'::numeric)
Settings: max_parallel_workers = '0'
Execution Time: 1227.489 ms
````

### `pw-sudo`, S1

````
Limit  (cost=94575.85..95337.16 rows=2 width=90) (actual time=1224.922..1225.415 rows=26 loops=1)
  …
  CTE sel_leg
    ->  HashAggregate  (cost=87129.50..87131.50 rows=200 width=41) (actual time=396.393..402.826 rows=89901 loops=1)
  …
  ->  Nested Loop Semi Join  (cost=6959.19..7720.51 rows=2 width=90) (actual time=1224.922..1225.409 rows=26 loops=1)
        ->  Nested Loop Semi Join  (cost=6958.50..7310.46 rows=3 width=163) (actual time=1224.918..1225.343 rows=26 loops=1)
              ->  Merge Semi Join  (cost=6957.81..6965.40 rows=6 width=122) (actual time=1224.878..1224.891 rows=36 loops=1)
                    Merge Cond: (sl.thing_id = sf.thing_id)
                    ->  Sort  (cost=1723.65..1724.15 rows=200 width=81) (actual time=841.406..841.412 rows=36 loops=1)
                          Sort Key: st.thing_id COLLATE "C"
                          Sort Method: external merge  Disk: 9400kB
                          ->  Nested Loop  (cost=5.05..1716.00 rows=200 width=81) (actual time=418.844..816.147 rows=89901 loops=1)
                                ->  HashAggregate  (cost=4.50..6.50 rows=200 width=32) (actual time=418.818..426.176 rows=89901 loops=1)
                                      Group Key: sl.thing_id
                                      ->  CTE Scan on sel_leg sl  (cost=0.00..4.00 rows=200 width=32) (actual time=396.393..409.946 rows=89901 loops=1)
                                ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=49) (actual time=0.004..0.004 rows=1 loops=89901)
                                      Index Cond: (thing_id = sl.thing_id)
                    ->  Sort  (cost=5234.16..5237.43 rows=1307 width=41) (actual time=383.467..383.470 rows=36 loops=1)
                          Sort Key: sf.thing_id COLLATE "C"
                          Sort Method: external merge  Disk: 3992kB
                          ->  Bitmap Heap Scan on search_flat sf  (cost=41.96..5166.51 rows=1307 width=41) (actual time=10.015..363.452 rows=89901 loops=1)
                                Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                Rows Removed by Index Recheck: 1933925
                                Heap Blocks: exact=50982 lossy=36654
                                ->  Bitmap Index Scan on sf_text  (cost=0.00..41.63 rows=1307 width=0) (actual time=5.170..5.170 rows=89901 loops=1)
                                      Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
              ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..288.68 rows=5 width=41) (actual time=0.012..0.012 rows=1 loops=36)
                    Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                    Filter: (val_num >= '0'::numeric)
                    Rows Removed by Filter: 0
        ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..288.68 rows=2 width=41) (actual time=0.002..0.002 rows=1 loops=26)
              Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
              Filter: (val_num <= '100'::numeric)
Settings: max_parallel_workers = '0'
Execution Time: 1226.531 ms
````
