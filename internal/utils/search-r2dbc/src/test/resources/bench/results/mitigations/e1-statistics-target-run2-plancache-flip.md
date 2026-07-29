# E1 - per-column statistics on wpath + val_text/val_num escalation

Lever: bump wpath's per-column statistics target from the default 100 to 10000 (plan doc §5 "Planner regressions at scale" risk row) so the planner's row estimate for a per-wpath predicate stops being pooled across all wpaths sharing val_num/val_text's table-wide histograms (Task 0.2's root cause #1, 38-80x misestimates). ANALYZE re-run after the ALTER so the new target takes effect immediately (not on the next autovacuum). Escalated to also bump val_text/val_num to statistics target 1000: true (escalation trigger: shape 1/5's sf_num/sf_text plan-line estimate-vs-actual ratio still outside 0.2x-5x after the wpath-only bump).

## DDL / settings applied this phase

```sql
ALTER TABLE search_flat ALTER COLUMN wpath SET STATISTICS 10000;  -- 6ms
ANALYZE search_flat;  -- 28217ms
ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS 1000;  -- 2ms
ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS 1000;  -- 0ms
ANALYZE search_flat;  -- 29007ms
```

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
| e1-statistics-target                       |         20 |      699 |     3366 |     3707 |     3715 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 407 rows vs actual 13869 rows (34.1x).
Top-level PG shared-buffer hit ratio: 57.6% (hit=47947 read=35359).

```
Nested Loop Semi Join  (cost=1637.75..5191.39 rows=1 width=41) (actual time=401.925..401.926 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=47947 read=35359
  ->  Nested Loop  (cost=1637.06..5121.44 rows=4 width=82) (actual time=326.401..401.717 rows=5 loops=1)
        Buffers: shared hit=47939 read=35334
        ->  HashAggregate  (cost=1636.51..1640.58 rows=407 width=41) (actual time=321.584..323.062 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat s_1  (cost=21.75..1635.49 rows=407 width=41) (actual time=4.210..318.106 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..21.65 rows=407 width=0) (actual time=2.984..2.985 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=47939 read=21406
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.041..0.041 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=8 read=25
Planning:
  Buffers: shared hit=55 read=15
Planning Time: 0.490 ms
Execution Time: 401.960 ms
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
| e1-statistics-target                       |         20 |      161 |      180 |      204 |      206 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): true

Top-level PG shared-buffer hit ratio: 4.6% (hit=1442 read=29908).

```
Gather  (cost=1205.01..143062.38 rows=1638 width=41) (actual time=14.688..63.888 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=1442 read=29908
  ->  Nested Loop Semi Join  (cost=205.01..141898.58 rows=682 width=41) (actual time=10.417..54.453 rows=20 loops=3)
        Buffers: shared hit=1442 read=29908
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=7.866..49.776 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=10708
              Buffers: shared hit=2 read=27143
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=3.952..3.952 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=2 read=13
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.37 rows=1 width=41) (actual time=0.024..0.024 rows=0 loops=572)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
              Filter: (val_num > 38.0)
              Rows Removed by Filter: 1
              Buffers: shared hit=1440 read=2765
Planning:
  Buffers: shared hit=6 read=17
Planning Time: 0.302 ms
JIT:
  Functions: 33
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.654 ms, Inlining 0.000 ms, Optimization 1.037 ms, Emission 15.158 ms, Total 17.849 ms
Execution Time: 64.579 ms
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
| e1-statistics-target                       |         20 |    53156 |    55271 |    56990 |    57366 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: true
Uses wpath-scoped `sf_trgm_scoped_feature`: false
Top-level PG shared-buffer hit ratio: 25.0% (hit=13397 read=40107).

```
Nested Loop  (cost=67434.78..84097.15 rows=19 width=41) (actual time=328.057..342.645 rows=3 loops=1)
  Buffers: shared hit=13397 read=40107
  ->  HashAggregate  (cost=67434.23..67454.28 rows=2005 width=41) (actual time=306.837..307.510 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=7 read=32282
        ->  Bitmap Heap Scan on search_flat s  (cost=59609.08..67429.21 rows=2007 width=41) (actual time=133.988..305.775 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Rows Removed by Index Recheck: 36950
              Heap Blocks: exact=28323
              Buffers: shared hit=7 read=32282
              ->  BitmapAnd  (cost=59609.08..59609.08 rows=2007 width=0) (actual time=131.476..131.477 rows=0 loops=1)
                    Buffers: shared hit=1 read=3965
                    ->  Bitmap Index Scan on sf_trgm  (cost=0.00..166.91 rows=30375 width=0) (actual time=6.827..6.827 rows=44298 loops=1)
                          Index Cond: ((val_text)::text ~~* '%234%'::text)
                          Buffers: shared hit=1 read=16
                    ->  Bitmap Index Scan on sf_exists  (cost=0.00..59440.91 rows=4525913 width=0) (actual time=124.233..124.233 rows=4544614 loops=1)
                          Index Cond: (wpath = '/features/*/properties/prop0'::text)
                          Buffers: shared read=3949
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.31 rows=1 width=41) (actual time=0.008..0.008 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=13390 read=7825
Planning:
  Buffers: shared hit=7 read=17
Planning Time: 0.990 ms
Execution Time: 342.671 ms
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
| e1-statistics-target                       |         20 |      707 |     3692 |     4142 |     4204 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 407 rows vs actual 13869 rows (34.1x).
Top-level PG shared-buffer hit ratio: 57.6% (hit=47947 read=35359).

```
Aggregate  (cost=5191.39..5191.40 rows=1 width=8) (actual time=431.266..431.268 rows=1 loops=1)
  Buffers: shared hit=47947 read=35359
  ->  Nested Loop Semi Join  (cost=1637.75..5191.39 rows=1 width=0) (actual time=431.265..431.266 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=47947 read=35359
        ->  Nested Loop  (cost=1637.06..5121.44 rows=4 width=82) (actual time=357.430..431.050 rows=5 loops=1)
              Buffers: shared hit=47939 read=35334
              ->  HashAggregate  (cost=1636.51..1640.58 rows=407 width=41) (actual time=352.638..354.100 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared read=13928
                    ->  Bitmap Heap Scan on search_flat s_1  (cost=21.75..1635.49 rows=407 width=41) (actual time=4.013..349.027 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared read=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..21.65 rows=407 width=0) (actual time=2.810..2.810 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared read=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.005..0.005 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=47939 read=21406
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.042..0.042 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=8 read=25
Planning:
  Buffers: shared hit=55 read=15
Planning Time: 0.468 ms
Execution Time: 431.295 ms
```


---

**Provenance note (Task 0.2b):** this is the preserved SECOND full run of E1 (2026-07-04, 39:24 min), still
under Task 0.2's original connection settings — it reproduced run 1's ~55-59s shape-3-high "anomaly"
(53.2-57.4s here), which was subsequently root-caused as a PostgreSQL plancache GENERIC-plan flip triggered by
pgjdbc's connection-level named-statement promotion (NOT a property of the statistics lever's intended
custom-plan behavior) — see `e1-generic-plan-flip-evidence.md` for the full proof chain. The authoritative E1
measurement (custom plans pinned via `prepareThreshold=0`) is `e1-statistics-target.md`.
