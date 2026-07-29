# 3-predicate, all-selective (temperature bucket AND weightKg>threshold AND certified)
- `/features/env/properties/temperature`: temperature in [bucket,bucket+0.99] — selective, ~1.4%
- `/attributes/weightKg`: weightKg > threshold — selective, ~4-9%
- `/attributes/certified`: certified = true — selective, ~2% (of full population; ~49% of the ~4% present)

## EXISTS-chain

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_num BETWEEN ? AND ?)
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_num > ?)
  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ? AND f.val_bool = ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| EXISTS-chain                               |         20 |      153 |      820 |      893 |      904 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop Semi Join  (cost=1456.42..5414.46 rows=1 width=41) (actual time=134.978..134.980 rows=0 loops=1)
  Buffers: shared hit=47049 read=36253
  ->  Nested Loop Semi Join  (cost=1455.72..5197.91 rows=1 width=123) (actual time=134.978..134.979 rows=0 loops=1)
        Join Filter: (f.thing_id = f_2.thing_id)
        Buffers: shared hit=47049 read=36253
        ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=59.332..134.875 rows=5 loops=1)
              Buffers: shared hit=47041 read=36232
              ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=54.798..56.268 rows=13869 loops=1)
                    Group Key: f.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared read=13928
                    ->  Bitmap Heap Scan on search_flat f  (cost=17.18..1453.58 rows=362 width=41) (actual time=2.954..52.285 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared read=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=1.789..1.790 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared read=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
                    Index Cond: (thing_id = f.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=47041 read=22304
        ->  Index Scan using search_flat_pkey on search_flat f_2  (cost=0.69..219.78 rows=1 width=41) (actual time=0.020..0.020 rows=0 loops=5)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/attributes/certified'::text))
              Filter: val_bool
              Rows Removed by Filter: 0
              Buffers: shared hit=8 read=21
  ->  Index Scan using search_flat_pkey on search_flat f_1  (cost=0.69..236.78 rows=1 width=41) (never executed)
        Index Cond: ((thing_id = f.thing_id) AND (wpath = '/attributes/weightKg'::text))
        Filter: (val_num > 700.0)
Planning:
  Buffers: shared hit=104 read=11
Planning Time: 0.389 ms
Execution Time: 135.108 ms
```

## Semi-join (thing_id IN + GROUP BY/HAVING count FILTER)

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND st.thing_id IN (
    SELECT thing_id FROM search_flat f
    WHERE (f.wpath = ? AND f.val_num BETWEEN ? AND ?) OR (f.wpath = ? AND f.val_num > ?) OR (f.wpath = ? AND f.val_bool = ?)
    GROUP BY thing_id
    HAVING count(*) FILTER (WHERE f.wpath = ? AND f.val_num BETWEEN ? AND ?) > 0
       AND count(*) FILTER (WHERE f.wpath = ? AND f.val_num > ?) > 0
       AND count(*) FILTER (WHERE f.wpath = ? AND f.val_bool = ?) > 0
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
| semi-join                                  |         20 |     2289 |     2947 |     4151 |     4329 |

Index-backed (no seq scan on search_flat): true

```
Nested Loop  (cost=209417.80..219900.53 rows=11 width=41) (actual time=3103.833..3103.835 rows=0 loops=1)
  Buffers: shared hit=44 read=99483, temp read=698 written=1433
  ->  HashAggregate  (cost=209417.25..209973.73 rows=1178 width=41) (actual time=3085.636..3103.509 rows=22 loops=1)
        Group Key: f.thing_id
        Filter: ((count(*) FILTER (WHERE ((f.wpath = '/features/env/properties/temperature'::text) AND (f.val_num >= '-15.0'::numeric) AND (f.val_num <= '-14.01'::numeric))) > 0) AND (count(*) FILTER (WHERE ((f.wpath = '/attributes/weightKg'::text) AND (f.val_num > 700.0))) > 0) AND (count(*) FILTER (WHERE ((f.wpath = '/attributes/certified'::text) AND f.val_bool)) > 0))
        Batches: 5  Memory Usage: 8241kB  Disk Usage: 7352kB
        Rows Removed by Filter: 99786
        Buffers: shared hit=1 read=99416, temp read=698 written=1433
        ->  Bitmap Heap Scan on search_flat f  (cost=2247.01..207862.72 rows=62181 width=75) (actual time=22.541..3032.696 rows=106460 loops=1)
              Recheck Cond: (((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric)) OR ((wpath = '/attributes/weightKg'::text) AND (val_num > 700.0)) OR ((wpath = '/attributes/certified'::text) AND val_bool))
              Rows Removed by Index Recheck: 3766729
              Heap Blocks: exact=27988 lossy=70848
              Buffers: shared hit=1 read=99416
              ->  BitmapOr  (cost=2247.01..2247.01 rows=62191 width=0) (actual time=13.146..13.148 rows=0 loops=1)
                    Buffers: shared hit=1 read=580
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.600..2.601 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..1945.08 rows=48052 width=0) (actual time=8.615..8.616 rows=72745 loops=1)
                          Index Cond: ((wpath = '/attributes/weightKg'::text) AND (val_num > 700.0))
                          Buffers: shared hit=1 read=442
                    ->  Bitmap Index Scan on sf_bool  (cost=0.00..238.20 rows=13777 width=0) (actual time=1.929..1.930 rows=19846 loops=1)
                          Index Cond: ((wpath = '/attributes/certified'::text) AND (val_bool = true))
                          Buffers: shared read=20
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.43 rows=1 width=41) (actual time=0.014..0.014 rows=0 loops=22)
        Index Cond: (thing_id = f.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=43 read=67
Planning:
  Buffers: shared read=1
Planning Time: 0.220 ms
JIT:
  Functions: 20
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.880 ms, Inlining 0.000 ms, Optimization 0.542 ms, Emission 10.426 ms, Total 11.847 ms
Execution Time: 3104.751 ms
```

## Head-to-head (p50 / p95 / max, ms)

| strategy | p50 | p95 | max |
|---|---:|---:|---:|
| EXISTS-chain | 820 | 893 | 904 |
| semi-join | 2947 | 4151 | 4329 |
