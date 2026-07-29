# 3-predicate, selective+unselective mix (city AND temperature bucket AND weightKg)
- `/attributes/location/city`: location/city = <one of 10 cities> — unselective, ~9%/city
- `/features/env/properties/temperature`: temperature in [bucket,bucket+0.99] — selective, ~1.4%
- `/attributes/weightKg`: weightKg > threshold — selective, ~4-9%

## EXISTS-chain

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_text = ?)
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
| EXISTS-chain                               |         20 |      178 |     5715 |     6728 |     7185 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop Semi Join  (cost=1456.42..5516.97 rows=1 width=41) (actual time=531.821..531.822 rows=0 loops=1)
  Join Filter: (st.thing_id = f.thing_id)
  Buffers: shared hit=48223 read=35079
  ->  Nested Loop Semi Join  (cost=1455.72..5249.04 rows=1 width=123) (actual time=531.821..531.822 rows=0 loops=1)
        Join Filter: (st.thing_id = f_2.thing_id)
        Buffers: shared hit=48223 read=35079
        ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=455.356..531.725 rows=5 loops=1)
              Buffers: shared hit=48215 read=35058
              ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=450.728..452.235 rows=13869 loops=1)
                    Group Key: f_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared read=13928
                    ->  Bitmap Heap Scan on search_flat f_1  (cost=17.18..1453.58 rows=362 width=41) (actual time=3.760..446.484 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared read=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.617..2.618 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared read=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
                    Index Cond: (thing_id = f_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=48215 read=21130
        ->  Index Scan using search_flat_pkey on search_flat f_2  (cost=0.69..236.78 rows=1 width=41) (actual time=0.019..0.019 rows=0 loops=5)
              Index Cond: ((thing_id = f_1.thing_id) AND (wpath = '/attributes/weightKg'::text))
              Filter: (val_num > 700.0)
              Rows Removed by Filter: 0
              Buffers: shared hit=8 read=21
  ->  Index Scan using search_flat_pkey on search_flat f  (cost=0.69..267.91 rows=1 width=41) (never executed)
        Index Cond: ((thing_id = f_2.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
Planning:
  Buffers: shared hit=102 read=14
Planning Time: 0.481 ms
Execution Time: 531.853 ms
```

## Semi-join (thing_id IN + GROUP BY/HAVING count FILTER)

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND st.thing_id IN (
    SELECT thing_id FROM search_flat f
    WHERE (f.wpath = ? AND f.val_text = ?) OR (f.wpath = ? AND f.val_num BETWEEN ? AND ?) OR (f.wpath = ? AND f.val_num > ?)
    GROUP BY thing_id
    HAVING count(*) FILTER (WHERE f.wpath = ? AND f.val_text = ?) > 0
       AND count(*) FILTER (WHERE f.wpath = ? AND f.val_num BETWEEN ? AND ?) > 0
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
| semi-join                                  |         20 |     3568 |     8212 |     8720 |     8815 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop  (cost=171359.10..180802.43 rows=10 width=41) (actual time=3741.412..3741.414 rows=0 loops=1)
  Buffers: shared hit=197 read=161272, temp read=1606 written=3104
  ->  HashAggregate  (cost=171358.55..171859.03 rows=1059 width=41) (actual time=3703.770..3739.960 rows=90 loops=1)
        Group Key: f.thing_id
        Filter: ((count(*) FILTER (WHERE ((f.wpath = '/attributes/location/city'::text) AND (f.val_text = 'Stuttgart'::text))) > 0) AND (count(*) FILTER (WHERE ((f.wpath = '/features/env/properties/temperature'::text) AND (f.val_num >= '-15.0'::numeric) AND (f.val_num <= '-14.01'::numeric))) > 0) AND (count(*) FILTER (WHERE ((f.wpath = '/attributes/weightKg'::text) AND (f.val_num > 700.0))) > 0))
        Batches: 5  Memory Usage: 8241kB  Disk Usage: 15296kB
        Rows Removed by Filter: 164809
        Buffers: shared hit=1 read=161018, temp read=1606 written=3104
        ->  Bitmap Heap Scan on search_flat f  (cost=2035.13..169996.25 rows=49538 width=85) (actual time=24.238..3596.986 rows=176515 loops=1)
              Recheck Cond: (((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text)) OR ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric)) OR ((wpath = '/attributes/weightKg'::text) AND (val_num > 700.0)))
              Rows Removed by Index Recheck: 5471061
              Heap Blocks: exact=56573 lossy=103802
              Buffers: shared hit=1 read=161018
              ->  BitmapOr  (cost=2035.13..2035.13 rows=49539 width=0) (actual time=17.445..17.446 rows=0 loops=1)
                    Buffers: shared hit=1 read=643
                    ->  Bitmap Index Scan on sf_text  (cost=0.00..35.81 rows=1125 width=0) (actual time=5.497..5.497 rows=89901 loops=1)
                          Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                          Buffers: shared read=83
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=4.075..4.075 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..1945.08 rows=48052 width=0) (actual time=7.871..7.871 rows=72745 loops=1)
                          Index Cond: ((wpath = '/attributes/weightKg'::text) AND (val_num > 700.0))
                          Buffers: shared hit=1 read=442
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.45 rows=1 width=41) (actual time=0.016..0.016 rows=0 loops=90)
        Index Cond: (thing_id = f.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=196 read=254
Planning:
  Buffers: shared read=2
Planning Time: 0.251 ms
JIT:
  Functions: 20
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.218 ms, Inlining 0.000 ms, Optimization 0.659 ms, Emission 12.287 ms, Total 14.163 ms
Execution Time: 3743.098 ms
```

## Head-to-head (p50 / p95 / max, ms)

| strategy | p50 | p95 | max |
|---|---:|---:|---:|
| EXISTS-chain | 5715 | 6728 | 7185 |
| semi-join | 8212 | 8720 | 8815 |
