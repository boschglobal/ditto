# Shape 1 — eq two-predicate AND (auth + gr)

## SQL

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = '/attributes/location/city' AND s.val_text = ?)
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = '/features/env/properties/temperature' AND s.val_num BETWEEN ? AND ?)
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

Note: Predicates: /attributes/location/city = <one of 10 cities> (unselective, ~9%/city) AND /features/env/properties/temperature BETWEEN [bucket, bucket+0.99] (selective, ~1.4%). gr filter varies 2 subjects per iteration; auth recheck as specified in the brief (see AuthRecheck.java Javadoc for the <a>=attributes,<b>=location concretization).

## Latency (3 warmup + 20 timed executions, varying binds)

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| shape-1-eq-and                             |         20 |      169 |     6462 |     6905 |     7506 |

Index-backed (no seq scan on search_flat): true
Warm p95 > 200ms (red flag): true

## EXPLAIN (ANALYZE, BUFFERS) — representative bind

```
Nested Loop Semi Join  (cost=1455.72..5404.71 rows=1 width=41) (actual time=850.229..850.230 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=48222 read=35084
  ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=774.400..849.827 rows=5 loops=1)
        Buffers: shared hit=48213 read=35060
        ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=769.920..771.438 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat s_1  (cost=17.18..1453.58 rows=362 width=41) (actual time=4.089..765.223 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.976..2.976 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=48213 read=21132
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..288.67 rows=1 width=41) (actual time=0.080..0.080 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=9 read=24
Planning:
  Buffers: shared hit=56 read=14
Planning Time: 0.335 ms
Execution Time: 850.252 ms
```
