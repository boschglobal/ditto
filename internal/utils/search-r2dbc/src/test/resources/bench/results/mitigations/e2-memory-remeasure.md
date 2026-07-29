# E2 - realistic memory (container restarted with laptop-class settings)

Lever: `ditto-search-bench-pg` recreated (docker stop + rm, WITHOUT `-v` so the existing named data volume is preserved, then docker run reusing that same volume) with `-c shared_buffers=4GB -c effective_cache_size=12GB -c work_mem=64MB` in place of the postgres:16 image defaults (128MB/4GB/4MB) — see bench/README.md for the exact command and this task's report for confirmation that E1's per-column statistics targets survived the restart (they are stored in pg_attribute/pg_statistic inside the reused data directory, not runtime GUCs). No schema DDL in this method; measurement only.

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
| e2-memory-remeasure                        |         20 |       46 |       53 |      818 |      827 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=83306 read=0).

```
Nested Loop Semi Join  (cost=1645.66..5216.52 rows=1 width=41) (actual time=40.120..40.121 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=83306
  ->  Nested Loop  (cost=1644.97..5146.57 rows=4 width=82) (actual time=13.954..40.093 rows=5 loops=1)
        Buffers: shared hit=83273
        ->  HashAggregate  (cost=1644.42..1648.51 rows=409 width=41) (actual time=12.658..13.777 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared hit=13928
              ->  Bitmap Heap Scan on search_flat s_1  (cost=21.78..1643.39 rows=409 width=41) (actual time=2.875..10.440 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared hit=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..21.68 rows=409 width=0) (actual time=1.701..1.701 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared hit=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.002..0.002 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=69345
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.005..0.005 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=33
Planning:
  Buffers: shared hit=70
Planning Time: 0.383 ms
Execution Time: 40.242 ms
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
| e2-memory-remeasure                        |         20 |      181 |      199 |      215 |      216 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): true

Top-level PG shared-buffer hit ratio: 100.0% (hit=31350 read=0).

```
Gather  (cost=1205.01..143064.15 rows=1655 width=41) (actual time=13.686..43.447 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=31350
  ->  Nested Loop Semi Join  (cost=205.01..141898.65 rows=690 width=41) (actual time=8.462..30.870 rows=20 loops=3)
        Buffers: shared hit=31350
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=7.920..28.931 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=13937
              Buffers: shared hit=27145
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=4.615..4.615 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=15
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.37 rows=1 width=41) (actual time=0.010..0.010 rows=0 loops=572)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
              Filter: (val_num > 38.0)
              Rows Removed by Filter: 1
              Buffers: shared hit=4205
Planning:
  Buffers: shared hit=23
Planning Time: 0.254 ms
JIT:
  Functions: 33
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.426 ms, Inlining 0.000 ms, Optimization 0.729 ms, Emission 15.184 ms, Total 17.339 ms
Execution Time: 43.863 ms
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
| e2-memory-remeasure                        |         20 |       54 |      248 |     1753 |     2056 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: true
Uses wpath-scoped `sf_trgm_scoped_feature`: false
Top-level PG shared-buffer hit ratio: 100.0% (hit=39668 read=0).

```
Nested Loop  (cost=70767.46..94159.18 rows=27 width=41) (actual time=238.456..243.926 rows=3 loops=1)
  Buffers: shared hit=39668
  ->  HashAggregate  (cost=70766.91..70795.45 rows=2854 width=41) (actual time=231.330..231.652 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=18453
        ->  Bitmap Heap Scan on search_flat s  (cost=59690.35..70759.76 rows=2859 width=41) (actual time=214.623..230.777 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Rows Removed by Index Recheck: 15698
              Heap Blocks: exact=14487
              Buffers: shared hit=18453
              ->  BitmapAnd  (cost=59690.35..59690.35 rows=2859 width=0) (actual time=213.389..213.390 rows=0 loops=1)
                    Buffers: shared hit=3966
                    ->  Bitmap Index Scan on sf_trgm  (cost=0.00..181.56 rows=43238 width=0) (actual time=3.202..3.202 rows=44298 loops=1)
                          Index Cond: ((val_text)::text ~~* '%234%'::text)
                          Buffers: shared hit=17
                    ->  Bitmap Index Scan on sf_exists  (cost=0.00..59507.11 rows=4531006 width=0) (actual time=205.444..205.444 rows=4544614 loops=1)
                          Index Cond: (wpath = '/features/*/properties/prop0'::text)
                          Buffers: shared hit=3949
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.20 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=21215
Planning:
  Buffers: shared hit=24
Planning Time: 0.712 ms
Execution Time: 245.730 ms
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
| e2-memory-remeasure                        |         20 |       39 |       44 |       49 |       50 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): false

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=83306 read=0).

```
Aggregate  (cost=5216.52..5216.53 rows=1 width=8) (actual time=39.539..39.540 rows=1 loops=1)
  Buffers: shared hit=83306
  ->  Nested Loop Semi Join  (cost=1645.66..5216.52 rows=1 width=0) (actual time=39.538..39.538 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=83306
        ->  Nested Loop  (cost=1644.97..5146.57 rows=4 width=82) (actual time=12.936..39.510 rows=5 loops=1)
              Buffers: shared hit=83273
              ->  HashAggregate  (cost=1644.42..1648.51 rows=409 width=41) (actual time=11.594..12.703 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared hit=13928
                    ->  Bitmap Heap Scan on search_flat s_1  (cost=21.78..1643.39 rows=409 width=41) (actual time=2.479..9.813 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared hit=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..21.68 rows=409 width=0) (actual time=1.387..1.387 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared hit=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.002..0.002 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=69345
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.005..0.005 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=33
Planning:
  Buffers: shared hit=70
Planning Time: 0.338 ms
Execution Time: 39.559 ms
```

