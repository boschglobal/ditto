# E1 - per-column statistics on wpath + val_text/val_num escalation

Lever: bump wpath's per-column statistics target from the default 100 to 10000 (plan doc §5 "Planner regressions at scale" risk row) so the planner's row estimate for a per-wpath predicate stops being pooled across all wpaths sharing val_num/val_text's table-wide histograms (Task 0.2's root cause #1, 38-80x misestimates). ANALYZE re-run after the ALTER so the new target takes effect immediately (not on the next autovacuum). Escalated to also bump val_text/val_num to statistics target 1000: true (escalation trigger: shape 1/5's sf_num/sf_text plan-line estimate-vs-actual ratio still outside 0.2x-5x after the wpath-only bump).

## DDL / settings applied this phase

```sql
ALTER TABLE search_flat ALTER COLUMN wpath SET STATISTICS 10000;  -- 5ms
ANALYZE search_flat;  -- 30287ms
ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS 1000;  -- 2ms
ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS 1000;  -- 0ms
ANALYZE search_flat;  -- 27885ms
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
| e1-statistics-target                       |         20 |      712 |     3516 |     3849 |     3853 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 391 rows vs actual 13869 rows (35.5x).
Top-level PG shared-buffer hit ratio: 57.6% (hit=47947 read=35359).

```
Nested Loop Semi Join  (cost=1570.48..4986.33 rows=1 width=41) (actual time=418.934..418.935 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=47947 read=35359
  ->  Nested Loop  (cost=1569.78..4916.38 rows=4 width=82) (actual time=342.008..418.713 rows=5 loops=1)
        Buffers: shared hit=47939 read=35334
        ->  HashAggregate  (cost=1569.23..1573.14 rows=391 width=41) (actual time=337.274..338.790 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat s_1  (cost=17.55..1568.26 rows=391 width=41) (actual time=4.814..333.608 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..17.45 rows=391 width=0) (actual time=3.688..3.688 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=47939 read=21406
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.043..0.044 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=8 read=25
Planning:
  Buffers: shared hit=55 read=15
Planning Time: 0.585 ms
Execution Time: 418.962 ms
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
| e1-statistics-target                       |         20 |      166 |      185 |      203 |      213 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): true

Top-level PG shared-buffer hit ratio: 4.6% (hit=1442 read=29908).

```
Gather  (cost=1205.01..143062.79 rows=1642 width=41) (actual time=15.786..66.229 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=1442 read=29908
  ->  Nested Loop Semi Join  (cost=205.01..141898.59 rows=684 width=41) (actual time=11.411..56.569 rows=20 loops=3)
        Buffers: shared hit=1442 read=29908
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=8.895..51.601 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=10667
              Buffers: shared hit=2 read=27143
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=3.767..3.767 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=2 read=13
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.37 rows=1 width=41) (actual time=0.026..0.026 rows=0 loops=572)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
              Filter: (val_num > 38.0)
              Rows Removed by Filter: 1
              Buffers: shared hit=1440 read=2765
Planning:
  Buffers: shared hit=6 read=17
Planning Time: 0.299 ms
JIT:
  Functions: 33
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.932 ms, Inlining 0.000 ms, Optimization 1.225 ms, Emission 17.399 ms, Total 20.556 ms
Execution Time: 66.944 ms
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
| e1-statistics-target                       |         20 |    56585 |    58046 |    59194 |    59195 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: true
Uses wpath-scoped `sf_trgm_scoped_feature`: false
Top-level PG shared-buffer hit ratio: 25.0% (hit=13397 read=40107).

```
Nested Loop  (cost=70862.16..94301.59 rows=27 width=41) (actual time=379.489..395.868 rows=3 loops=1)
  Buffers: shared hit=13397 read=40107
  ->  HashAggregate  (cost=70861.61..70890.21 rows=2860 width=41) (actual time=356.287..356.979 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=7 read=32282
        ->  Bitmap Heap Scan on search_flat s  (cost=59762.20..70854.44 rows=2865 width=41) (actual time=152.760..354.896 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Rows Removed by Index Recheck: 36950
              Heap Blocks: exact=28323
              Buffers: shared hit=7 read=32282
              ->  BitmapAnd  (cost=59762.20..59762.20 rows=2865 width=0) (actual time=150.154..150.155 rows=0 loops=1)
                    Buffers: shared hit=1 read=3965
                    ->  Bitmap Index Scan on sf_trgm  (cost=0.00..181.66 rows=43274 width=0) (actual time=7.444..7.445 rows=44298 loops=1)
                          Index Cond: ((val_text)::text ~~* '%234%'::text)
                          Buffers: shared hit=1 read=16
                    ->  Bitmap Index Scan on sf_exists  (cost=0.00..59578.85 rows=4536305 width=0) (actual time=142.263..142.263 rows=4544614 loops=1)
                          Index Cond: (wpath = '/features/*/properties/prop0'::text)
                          Buffers: shared read=3949
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.20 rows=1 width=41) (actual time=0.009..0.009 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=13390 read=7825
Planning:
  Buffers: shared hit=7 read=17
Planning Time: 1.025 ms
Execution Time: 395.896 ms
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
| e1-statistics-target                       |         20 |      747 |     4078 |     4330 |     4376 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 391 rows vs actual 13869 rows (35.5x).
Top-level PG shared-buffer hit ratio: 57.6% (hit=47947 read=35359).

```
Aggregate  (cost=4986.33..4986.34 rows=1 width=8) (actual time=463.647..463.648 rows=1 loops=1)
  Buffers: shared hit=47947 read=35359
  ->  Nested Loop Semi Join  (cost=1570.48..4986.33 rows=1 width=0) (actual time=463.645..463.646 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=47947 read=35359
        ->  Nested Loop  (cost=1569.78..4916.38 rows=4 width=82) (actual time=387.555..463.262 rows=5 loops=1)
              Buffers: shared hit=47939 read=35334
              ->  HashAggregate  (cost=1569.23..1573.14 rows=391 width=41) (actual time=382.833..384.302 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared read=13928
                    ->  Bitmap Heap Scan on search_flat s_1  (cost=17.55..1568.26 rows=391 width=41) (actual time=3.991..379.346 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared read=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..17.45 rows=391 width=0) (actual time=2.869..2.869 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared read=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=47939 read=21406
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.076..0.076 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=8 read=25
Planning:
  Buffers: shared hit=55 read=15
Planning Time: 0.453 ms
Execution Time: 463.695 ms
```


---

**Provenance note (Task 0.2b):** this is the preserved FIRST run of E1 (2026-07-04, ~43 min total: both its measurement rounds showed shape 3-high at ~56.5-59.2s per iteration uniformly, while the same-session representative EXPLAIN showed 396ms). Post-hoc reproduction attempts (psql literal-bind, psql parameterized PREPARE/EXECUTE, and a standalone pgjdbc probe cycling the same 8 substrings) all measured 0.3-1.4s with the same BitmapAnd(sf_trgm, sf_exists) plan and could NOT reproduce the ~58s. E1 was therefore re-run; the authoritative E1 file is e1-statistics-target.md. The anomaly is documented in the Task 0.2b report.
