# E5 - plan-shape rescue via materialized selective-leg CTE

Only runs because E1+E2 left shapes 1/5 over ~500ms warm p95 (see the Task 0.2b report's trigger evaluation). Forces the selective temperature-BETWEEN leg into a `MATERIALIZED` CTE first, checks the unselective city-equality leg via `EXISTS`, then joins back to `search_things` for `global_read`+auth. This is a translator-level strategy (query SHAPE), not a tuning knob — see the report for whether it is required.

## shape 1 RESCUE (materialized selective-leg CTE)

```sql
WITH cand AS MATERIALIZED (
    SELECT s.thing_id FROM search_flat s WHERE s.wpath = ? AND s.val_num BETWEEN ? AND ?
)
SELECT st.thing_id FROM search_things st
JOIN cand ON cand.thing_id = st.thing_id
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = '/attributes/location/city' AND s.val_text = ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e5-materialized-cte-rescue                 |         20 |       36 |       40 |       48 |       56 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): false

Top-level PG shared-buffer hit ratio: 100.0% (hit=11659 read=0).

```
Nested Loop  (cost=159.19..176.81 rows=1 width=41) (actual time=47.919..47.920 rows=0 loops=1)
  Join Filter: (cand.thing_id = st.thing_id)
  Buffers: shared hit=11659
  CTE cand
    ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.77 rows=409 width=41) (actual time=0.007..2.341 rows=13869 loops=1)
          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
          Heap Fetches: 0
          Buffers: shared hit=4141
  ->  Hash Join  (cost=120.87..130.60 rows=1 width=73) (actual time=37.458..43.621 rows=1267 loops=1)
        Hash Cond: (cand.thing_id = s.thing_id)
        Buffers: shared hit=5324
        ->  CTE Scan on cand  (cost=0.00..8.18 rows=409 width=32) (actual time=0.008..3.892 rows=13869 loops=1)
              Buffers: shared hit=4141
        ->  Hash  (cost=106.35..106.35 rows=1162 width=41) (actual time=37.427..37.428 rows=89901 loops=1)
              Buckets: 131072 (originally 2048)  Batches: 1 (originally 1)  Memory Usage: 7455kB
              Buffers: shared hit=1183
              ->  HashAggregate  (cost=94.73..106.35 rows=1162 width=41) (actual time=23.125..28.842 rows=89901 loops=1)
                    Group Key: s.thing_id
                    Batches: 1  Memory Usage: 11281kB
                    Buffers: shared hit=1183
                    ->  Index Only Scan using sf_text_covering on search_flat s  (cost=0.56..91.82 rows=1163 width=41) (actual time=0.014..8.745 rows=89901 loops=1)
                          Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                          Heap Fetches: 0
                          Buffers: shared hit=1183
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.43 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=1267)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=6335
Planning:
  Buffers: shared hit=24
Planning Time: 0.327 ms
Execution Time: 48.374 ms
```

## shape 5 RESCUE (materialized selective-leg CTE)

```sql
WITH cand AS MATERIALIZED (
    SELECT s.thing_id FROM search_flat s WHERE s.wpath = ? AND s.val_num BETWEEN ? AND ?
)
SELECT count(*) FROM search_things st
JOIN cand ON cand.thing_id = st.thing_id
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = '/attributes/location/city' AND s.val_text = ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e5-materialized-cte-rescue                 |         20 |       38 |       39 |       41 |       42 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): false

Top-level PG shared-buffer hit ratio: 100.0% (hit=11659 read=0).

```
Aggregate  (cost=176.81..176.82 rows=1 width=8) (actual time=47.892..47.894 rows=1 loops=1)
  Buffers: shared hit=11659
  CTE cand
    ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.77 rows=409 width=41) (actual time=0.009..2.385 rows=13869 loops=1)
          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
          Heap Fetches: 0
          Buffers: shared hit=4141
  ->  Nested Loop  (cost=121.42..139.04 rows=1 width=0) (actual time=47.890..47.892 rows=0 loops=1)
        Join Filter: (cand.thing_id = st.thing_id)
        Buffers: shared hit=11659
        ->  Hash Join  (cost=120.87..130.60 rows=1 width=73) (actual time=37.286..43.540 rows=1267 loops=1)
              Hash Cond: (cand.thing_id = s.thing_id)
              Buffers: shared hit=5324
              ->  CTE Scan on cand  (cost=0.00..8.18 rows=409 width=32) (actual time=0.011..3.967 rows=13869 loops=1)
                    Buffers: shared hit=4141
              ->  Hash  (cost=106.35..106.35 rows=1162 width=41) (actual time=37.254..37.255 rows=89901 loops=1)
                    Buckets: 131072 (originally 2048)  Batches: 1 (originally 1)  Memory Usage: 7455kB
                    Buffers: shared hit=1183
                    ->  HashAggregate  (cost=94.73..106.35 rows=1162 width=41) (actual time=22.578..28.615 rows=89901 loops=1)
                          Group Key: s.thing_id
                          Batches: 1  Memory Usage: 11281kB
                          Buffers: shared hit=1183
                          ->  Index Only Scan using sf_text_covering on search_flat s  (cost=0.56..91.82 rows=1163 width=41) (actual time=0.023..8.536 rows=89901 loops=1)
                                Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                Heap Fetches: 0
                                Buffers: shared hit=1183
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.43 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=1267)
              Index Cond: (thing_id = s.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=6335
Planning:
  Buffers: shared hit=24
Planning Time: 0.431 ms
Execution Time: 48.380 ms
```

