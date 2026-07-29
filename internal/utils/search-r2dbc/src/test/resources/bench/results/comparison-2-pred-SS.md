# 2-predicate, selective+selective (temperature bucket AND weightKg>threshold)
- `/features/env/properties/temperature`: temperature in [bucket,bucket+0.99] — selective, ~1.4%
- `/attributes/weightKg`: weightKg > threshold — selective, ~4-9%

## EXISTS-chain

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_num BETWEEN ? AND ?)
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_num > ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| EXISTS-chain                               |         20 |      154 |      853 |      927 |      929 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop Semi Join  (cost=1455.72..5164.24 rows=3 width=41) (actual time=130.963..130.964 rows=0 loops=1)
  Join Filter: (f.thing_id = f_1.thing_id)
  Buffers: shared hit=47048 read=36254
  ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=55.254..130.869 rows=5 loops=1)
        Buffers: shared hit=47040 read=36233
        ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=50.627..52.078 rows=13869 loops=1)
              Group Key: f.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat f  (cost=17.18..1453.58 rows=362 width=41) (actual time=2.960..48.353 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=1.771..1.771 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
              Index Cond: (thing_id = f.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=47040 read=22305
  ->  Index Scan using search_flat_pkey on search_flat f_1  (cost=0.69..221.11 rows=1 width=41) (actual time=0.018..0.018 rows=0 loops=5)
        Index Cond: ((thing_id = st.thing_id) AND (wpath = '/attributes/weightKg'::text))
        Filter: (val_num > 700.0)
        Rows Removed by Filter: 0
        Buffers: shared hit=8 read=21
Planning:
  Buffers: shared hit=58 read=11
Planning Time: 0.308 ms
Execution Time: 131.154 ms
```

## Semi-join (thing_id IN + GROUP BY/HAVING count FILTER)

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND st.thing_id IN (
    SELECT thing_id FROM search_flat f
    WHERE (f.wpath = ? AND f.val_num BETWEEN ? AND ?) OR (f.wpath = ? AND f.val_num > ?)
    GROUP BY thing_id
    HAVING count(*) FILTER (WHERE f.wpath = ? AND f.val_num BETWEEN ? AND ?) > 0
       AND count(*) FILTER (WHERE f.wpath = ? AND f.val_num > ?) > 0
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
| semi-join                                  |         20 |     1363 |     2521 |     3187 |     3385 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop  (cost=167232.76..193280.15 rows=30 width=41) (actual time=3491.900..3491.901 rows=0 loops=1)
  Buffers: shared hit=2755 read=84185, temp read=394 written=744
  ->  HashAggregate  (cost=167232.21..167656.15 rows=3140 width=41) (actual time=3471.384..3484.261 rows=968 loops=1)
        Group Key: f.thing_id
        Filter: ((count(*) FILTER (WHERE ((f.wpath = '/features/env/properties/temperature'::text) AND (f.val_num >= '-15.0'::numeric) AND (f.val_num <= '-14.01'::numeric))) > 0) AND (count(*) FILTER (WHERE ((f.wpath = '/attributes/weightKg'::text) AND (f.val_num > 700.0))) > 0))
        Batches: 5  Memory Usage: 9009kB  Disk Usage: 3656kB
        Rows Removed by Filter: 81507
        Buffers: shared hit=1 read=82099, temp read=394 written=744
        ->  Bitmap Heap Scan on search_flat f  (cost=1986.38..166263.93 rows=48414 width=74) (actual time=21.234..3424.343 rows=86614 loops=1)
              Recheck Cond: (((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric)) OR ((wpath = '/attributes/weightKg'::text) AND (val_num > 700.0)))
              Rows Removed by Index Recheck: 2021476
              Heap Blocks: exact=43525 lossy=38014
              Buffers: shared hit=1 read=82099
              ->  BitmapOr  (cost=1986.38..1986.38 rows=48414 width=0) (actual time=10.822..10.823 rows=0 loops=1)
                    Buffers: shared hit=1 read=560
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.601..2.601 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..1945.08 rows=48052 width=0) (actual time=8.221..8.221 rows=72745 loops=1)
                          Index Cond: ((wpath = '/attributes/weightKg'::text) AND (val_num > 700.0))
                          Buffers: shared hit=1 read=442
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.16 rows=1 width=41) (actual time=0.008..0.008 rows=0 loops=968)
        Index Cond: (thing_id = f.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=2754 read=2086
Planning:
  Buffers: shared read=1
Planning Time: 0.205 ms
JIT:
  Functions: 20
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.916 ms, Inlining 0.000 ms, Optimization 0.513 ms, Emission 9.447 ms, Total 10.875 ms
Execution Time: 3492.947 ms
```

## Head-to-head (p50 / p95 / max, ms)

| strategy | p50 | p95 | max |
|---|---:|---:|---:|
| EXISTS-chain | 853 | 927 | 929 |
| semi-join | 2521 | 3187 | 3385 |
