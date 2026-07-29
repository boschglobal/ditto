# Shape 2 — gt range on a numeric path (auth + gr)

## SQL

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = '/features/env/properties/temperature' AND s.val_num > ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

Note: Predicate: /features/env/properties/temperature > <threshold in 38..44>, verified ~1.4%-9.7% selectivity against the corpus before coding.

## Latency (3 warmup + 20 timed executions, varying binds)

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| shape-2-gt-range                           |         20 |      159 |      168 |      178 |      189 |

Index-backed (no seq scan on search_flat): true
Warm p95 > 200ms (red flag): false

## EXPLAIN (ANALYZE, BUFFERS) — representative bind

```
Gather  (cost=1205.01..346244.34 rows=383 width=41) (actual time=14.131..67.030 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=1442 read=29908
  ->  Nested Loop Semi Join  (cost=205.01..345206.04 rows=160 width=41) (actual time=10.444..57.549 rows=20 loops=3)
        Buffers: shared hit=1442 read=29908
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=7.679..52.503 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=10818
              Buffers: shared hit=2 read=27143
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=4.218..4.218 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=2 read=13
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..268.69 rows=4 width=41) (actual time=0.026..0.026 rows=0 loops=572)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
              Filter: (val_num > 38.0)
              Rows Removed by Filter: 1
              Buffers: shared hit=1440 read=2765
Planning:
  Buffers: shared hit=6 read=17
Planning Time: 0.257 ms
JIT:
  Functions: 33
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.460 ms, Inlining 0.000 ms, Optimization 0.807 ms, Emission 15.026 ms, Total 17.293 ms
Execution Time: 67.424 ms
```
