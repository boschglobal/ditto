# E0 - baseline re-measure (unchanged schema/settings)

Re-run of shapes 1/2/3-high/5 with NO schema or configuration changes, to anchor today's numbers before any Task 0.2b mitigation lever is applied. Same protocol and seeded binds as Task 0.2 (3 warmup + 20 timed executions per shape, one representative `EXPLAIN (ANALYZE, BUFFERS)`).

## shape 1 (eq two-predicate AND, auth+gr)

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

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e0-baseline                                |         20 |      225 |     7222 |     8424 |     8439 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 362 rows vs actual 13869 rows (38.3x).
Top-level PG shared-buffer hit ratio: 57.9% (hit=48222 read=35084).

```
Nested Loop Semi Join  (cost=1455.72..5404.71 rows=1 width=41) (actual time=1027.464..1027.465 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=48222 read=35084
  ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=942.902..1027.032 rows=5 loops=1)
        Buffers: shared hit=48213 read=35060
        ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=938.208..939.984 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat s_1  (cost=17.18..1453.58 rows=362 width=41) (actual time=4.231..932.409 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.947..2.948 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=48213 read=21132
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..288.67 rows=1 width=41) (actual time=0.085..0.085 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=9 read=24
Planning:
  Buffers: shared hit=56 read=14
Planning Time: 0.334 ms
Execution Time: 1027.490 ms
```

## shape 2 (gt range, CONTROL — must not regress)

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

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e0-baseline                                |         20 |      158 |      169 |      176 |      180 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): false

Top-level PG shared-buffer hit ratio: 4.6% (hit=1442 read=29908).

```
Gather  (cost=1205.01..346244.34 rows=383 width=41) (actual time=12.707..59.690 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=1442 read=29908
  ->  Nested Loop Semi Join  (cost=205.01..345206.04 rows=160 width=41) (actual time=9.422..51.055 rows=20 loops=3)
        Buffers: shared hit=1442 read=29908
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=6.973..46.681 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=10922
              Buffers: shared hit=2 read=27143
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=3.648..3.648 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=2 read=13
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..268.69 rows=4 width=41) (actual time=0.023..0.023 rows=0 loops=572)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
              Filter: (val_num > 38.0)
              Rows Removed by Filter: 1
              Buffers: shared hit=1440 read=2765
Planning:
  Buffers: shared hit=6 read=17
Planning Time: 0.240 ms
JIT:
  Functions: 33
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.237 ms, Inlining 0.000 ms, Optimization 0.669 ms, Emission 13.488 ms, Total 15.394 ms
Execution Time: 60.013 ms
```

## shape 3-high (ilike, high-cardinality feature path — RED FLAGGED at baseline)

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = ? AND s.val_text COLLATE "C.utf8" ILIKE ? ESCAPE '\')
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e0-baseline                                |         20 |      478 |     2092 |     2294 |     2459 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: true
Uses wpath-scoped `sf_trgm_scoped_feature`: false
Top-level PG shared-buffer hit ratio: 25.7% (hit=13391 read=38761).

```
Nested Loop  (cost=5891.88..6735.28 rows=1 width=41) (actual time=493.198..504.398 rows=3 loops=1)
  Buffers: shared hit=13391 read=38761
  ->  HashAggregate  (cost=5891.33..5892.31 rows=98 width=41) (actual time=476.452..476.859 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 737kB
        Buffers: shared hit=1 read=30936
        ->  Bitmap Heap Scan on search_flat s  (cost=139.68..5891.09 rows=98 width=41) (actual time=5.261..475.267 rows=4260 loops=1)
              Recheck Cond: ((val_text)::text ~~* '%234%'::text)
              Filter: (wpath = '/features/*/properties/prop0'::text)
              Rows Removed by Filter: 40038
              Heap Blocks: exact=30920
              Buffers: shared hit=1 read=30936
              ->  Bitmap Index Scan on sf_trgm  (cost=0.00..139.65 rows=1469 width=0) (actual time=2.601..2.601 rows=44298 loops=1)
                    Index Cond: ((val_text)::text ~~* '%234%'::text)
                    Buffers: shared hit=1 read=16
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=13390 read=7825
Planning:
  Buffers: shared hit=7 read=17
Planning Time: 0.262 ms
Execution Time: 504.420 ms
```

## shape 5 (count + auth — RED FLAGGED at baseline)

```sql
SELECT count(*) FROM search_things st
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

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e0-baseline                                |         20 |      162 |     7095 |     8367 |     8378 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 362 rows vs actual 13869 rows (38.3x).
Top-level PG shared-buffer hit ratio: 57.9% (hit=48222 read=35084).

```
Aggregate  (cost=5404.71..5404.72 rows=1 width=8) (actual time=1082.354..1082.355 rows=1 loops=1)
  Buffers: shared hit=48222 read=35084
  ->  Nested Loop Semi Join  (cost=1455.72..5404.71 rows=1 width=0) (actual time=1082.352..1082.353 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=48222 read=35084
        ->  Nested Loop  (cost=1455.03..4538.67 rows=3 width=82) (actual time=1006.877..1081.818 rows=5 loops=1)
              Buffers: shared hit=48213 read=35060
              ->  HashAggregate  (cost=1454.48..1458.08 rows=360 width=41) (actual time=999.550..1001.100 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared read=13928
                    ->  Bitmap Heap Scan on search_flat s_1  (cost=17.18..1453.58 rows=362 width=41) (actual time=4.060..994.832 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared read=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..17.09 rows=362 width=0) (actual time=2.746..2.746 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared read=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.56 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=48213 read=21132
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..288.67 rows=1 width=41) (actual time=0.106..0.106 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=9 read=24
Planning:
  Buffers: shared hit=56 read=14
Planning Time: 0.396 ms
Execution Time: 1082.383 ms
```

