# 2-predicate, selective+unselective (city AND temperature bucket — shape 1's own WHERE)
- `/attributes/location/city`: location/city = <one of 10 cities> — unselective, ~9%/city
- `/features/env/properties/temperature`: temperature in [bucket,bucket+0.99] — selective, ~1.4%

## EXISTS-chain

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_text = ?)
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_num BETWEEN ? AND ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| EXISTS-chain                               |         20 |      190 |     5674 |     6564 |     6621 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop Semi Join  (cost=1455.72..5404.71 rows=1 width=41) (actual time=691.252..691.253 rows=0 loops=1)
  Join Filter: (st.thing_id = f.thing_id)
  Buffers: shared hit=48222 read=35084
  ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=615.969..690.752 rows=5 loops=1)
        Buffers: shared hit=48213 read=35060
        ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=611.379..612.833 rows=13869 loops=1)
              Group Key: f_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat f_1  (cost=17.18..1453.58 rows=362 width=41) (actual time=4.129..607.097 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.976..2.976 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
              Index Cond: (thing_id = f_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=48213 read=21132
  ->  Index Scan using search_flat_pkey on search_flat f  (cost=0.69..288.67 rows=1 width=41) (actual time=0.099..0.099 rows=0 loops=5)
        Index Cond: ((thing_id = f_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=9 read=24
Planning:
  Buffers: shared hit=56 read=14
Planning Time: 0.341 ms
Execution Time: 691.276 ms
```

## Semi-join (thing_id IN + GROUP BY/HAVING count FILTER)

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND st.thing_id IN (
    SELECT thing_id FROM search_flat f
    WHERE (f.wpath = ? AND f.val_text = ?) OR (f.wpath = ? AND f.val_num BETWEEN ? AND ?)
    GROUP BY thing_id
    HAVING count(*) FILTER (WHERE f.wpath = ? AND f.val_text = ?) > 0
       AND count(*) FILTER (WHERE f.wpath = ? AND f.val_num BETWEEN ? AND ?) > 0
  )
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| semi-join                                  |         20 |     1465 |     6502 |     6783 |     7196 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop  (cost=5916.06..7330.61 rows=2 width=41) (actual time=1497.668..1497.669 rows=0 loops=1)
  Buffers: shared hit=3686 read=102716, temp read=656 written=1387
  ->  HashAggregate  (cost=5915.51..5937.41 rows=162 width=41) (actual time=1473.155..1486.923 rows=1267 loops=1)
        Group Key: f.thing_id
        Filter: ((count(*) FILTER (WHERE ((f.wpath = '/attributes/location/city'::text) AND (f.val_text = 'Stuttgart'::text))) > 0) AND (count(*) FILTER (WHERE ((f.wpath = '/features/env/properties/temperature'::text) AND (f.val_num >= '-15.0'::numeric) AND (f.val_num <= '-14.01'::numeric))) > 0))
        Batches: 5  Memory Usage: 9009kB  Disk Usage: 7240kB
        Rows Removed by Filter: 101236
        Buffers: shared read=100067, temp read=656 written=1387
        ->  Bitmap Heap Scan on search_flat f  (cost=53.64..5885.77 rows=1487 width=85) (actual time=16.056..1429.523 rows=103770 loops=1)
              Recheck Cond: (((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text)) OR ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric)))
              Rows Removed by Index Recheck: 3731963
              Heap Blocks: exact=29101 lossy=70765
              Buffers: shared read=100067
              ->  BitmapOr  (cost=53.64..53.64 rows=1487 width=0) (actual time=12.947..12.948 rows=0 loops=1)
                    Buffers: shared read=201
                    ->  Bitmap Index Scan on sf_text  (cost=0.00..35.81 rows=1125 width=0) (actual time=7.472..7.472 rows=89901 loops=1)
                          Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                          Buffers: shared read=83
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=5.474..5.474 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=41) (actual time=0.008..0.008 rows=0 loops=1267)
        Index Cond: (thing_id = f.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=3686 read=2649
Planning:
  Buffers: shared read=2
Planning Time: 0.253 ms
Execution Time: 1498.072 ms
```

## Head-to-head (p50 / p95 / max, ms)

| strategy | p50 | p95 | max |
|---|---:|---:|---:|
| EXISTS-chain | 5674 | 6564 | 6621 |
| semi-join | 6502 | 6783 | 7196 |
