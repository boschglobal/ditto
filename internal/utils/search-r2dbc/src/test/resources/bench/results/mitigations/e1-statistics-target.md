# E1 - per-column statistics on wpath + val_text/val_num escalation

Lever: bump wpath's per-column statistics target from the default 100 to 10000 (plan doc §5 "Planner regressions at scale" risk row) so the planner's row estimate for a per-wpath predicate stops being pooled across all wpaths sharing val_num/val_text's table-wide histograms (Task 0.2's root cause #1, 38-80x misestimates). ANALYZE re-run after the ALTER so the new target takes effect immediately (not on the next autovacuum). Escalated to also bump val_text/val_num to statistics target 1000: true (escalation trigger: shape 1/5's sf_num/sf_text plan-line estimate-vs-actual ratio still outside 0.2x-5x after the wpath-only bump).

## DDL / settings applied this phase

```sql
ALTER TABLE search_flat ALTER COLUMN wpath SET STATISTICS 10000;  -- 8ms
ANALYZE search_flat;  -- 30186ms
ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS 1000;  -- 2ms
ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS 1000;  -- 0ms
ANALYZE search_flat;  -- 28449ms
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
| e1-statistics-target                       |         20 |      124 |      135 |      536 |      537 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 56.5% (hit=47052 read=36254).

```
Nested Loop Semi Join  (cost=1645.66..5216.52 rows=1 width=41) (actual time=125.280..125.281 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=47052 read=36254
  ->  Nested Loop  (cost=1644.97..5146.57 rows=4 width=82) (actual time=50.877..125.179 rows=5 loops=1)
        Buffers: shared hit=47043 read=36230
        ->  HashAggregate  (cost=1644.42..1648.51 rows=409 width=41) (actual time=46.457..47.927 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared read=13928
              ->  Bitmap Heap Scan on search_flat s_1  (cost=21.78..1643.39 rows=409 width=41) (actual time=2.772..44.402 rows=13869 loops=1)
                    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Blocks: exact=13810
                    Buffers: shared read=13928
                    ->  Bitmap Index Scan on sf_num  (cost=0.00..21.68 rows=409 width=0) (actual time=1.646..1.646 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Buffers: shared read=118
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.005..0.005 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=47043 read=22302
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.019..0.019 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=9 read=24
Planning:
  Buffers: shared hit=64 read=6
Planning Time: 0.400 ms
Execution Time: 125.301 ms
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
| e1-statistics-target                       |         20 |      173 |      185 |      197 |      218 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): false

Top-level PG shared-buffer hit ratio: 4.6% (hit=1442 read=29908).

```
Gather  (cost=1205.01..143064.15 rows=1655 width=41) (actual time=12.652..60.466 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=1442 read=29908
  ->  Nested Loop Semi Join  (cost=205.01..141898.65 rows=690 width=41) (actual time=9.130..51.829 rows=20 loops=3)
        Buffers: shared hit=1442 read=29908
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=6.858..47.288 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=10820
              Buffers: shared hit=2 read=27143
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=3.425..3.426 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=2 read=13
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.37 rows=1 width=41) (actual time=0.023..0.023 rows=0 loops=572)
              Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
              Filter: (val_num > 38.0)
              Rows Removed by Filter: 1
              Buffers: shared hit=1440 read=2765
Planning:
  Buffers: shared hit=6 read=17
Planning Time: 0.286 ms
JIT:
  Functions: 33
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 1.306 ms, Inlining 0.000 ms, Optimization 0.700 ms, Emission 13.452 ms, Total 15.457 ms
Execution Time: 60.866 ms
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
| e1-statistics-target                       |         20 |      140 |      247 |     1018 |     1021 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: true
Uses wpath-scoped `sf_trgm_scoped_feature`: false
Top-level PG shared-buffer hit ratio: 25.0% (hit=13391 read=40113).

```
Nested Loop  (cost=70767.46..94159.18 rows=27 width=41) (actual time=231.322..242.744 rows=3 loops=1)
  Buffers: shared hit=13391 read=40113
  ->  HashAggregate  (cost=70766.91..70795.45 rows=2854 width=41) (actual time=214.555..215.007 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=1 read=32288
        ->  Bitmap Heap Scan on search_flat s  (cost=59690.35..70759.76 rows=2859 width=41) (actual time=117.464..213.735 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Rows Removed by Index Recheck: 36950
              Heap Blocks: exact=28323
              Buffers: shared hit=1 read=32288
              ->  BitmapAnd  (cost=59690.35..59690.35 rows=2859 width=0) (actual time=114.965..114.966 rows=0 loops=1)
                    Buffers: shared hit=1 read=3965
                    ->  Bitmap Index Scan on sf_trgm  (cost=0.00..181.56 rows=43238 width=0) (actual time=2.564..2.564 rows=44298 loops=1)
                          Index Cond: ((val_text)::text ~~* '%234%'::text)
                          Buffers: shared hit=1 read=16
                    ->  Bitmap Index Scan on sf_exists  (cost=0.00..59507.11 rows=4531006 width=0) (actual time=111.976..111.976 rows=4544614 loops=1)
                          Index Cond: (wpath = '/features/*/properties/prop0'::text)
                          Buffers: shared read=3949
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.20 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=13390 read=7825
Planning:
  Buffers: shared hit=7 read=17
Planning Time: 0.773 ms
Execution Time: 242.768 ms
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
| e1-statistics-target                       |         20 |      129 |      135 |      530 |      546 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 56.5% (hit=47052 read=36254).

```
Aggregate  (cost=5216.52..5216.53 rows=1 width=8) (actual time=126.650..126.651 rows=1 loops=1)
  Buffers: shared hit=47052 read=36254
  ->  Nested Loop Semi Join  (cost=1645.66..5216.52 rows=1 width=0) (actual time=126.648..126.649 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=47052 read=36254
        ->  Nested Loop  (cost=1644.97..5146.57 rows=4 width=82) (actual time=51.727..126.547 rows=5 loops=1)
              Buffers: shared hit=47043 read=36230
              ->  HashAggregate  (cost=1644.42..1648.51 rows=409 width=41) (actual time=47.299..48.748 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared read=13928
                    ->  Bitmap Heap Scan on search_flat s_1  (cost=21.78..1643.39 rows=409 width=41) (actual time=2.837..45.225 rows=13869 loops=1)
                          Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Blocks: exact=13810
                          Buffers: shared read=13928
                          ->  Bitmap Index Scan on sf_num  (cost=0.00..21.68 rows=409 width=0) (actual time=1.726..1.726 rows=13869 loops=1)
                                Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                Buffers: shared read=118
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=47043 read=22302
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.020..0.020 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=9 read=24
Planning:
  Buffers: shared hit=66 read=4
Planning Time: 0.408 ms
Execution Time: 126.677 ms
```


---

**Provenance note (Task 0.2b):** this is E1's authoritative (third) run, measured with the harness's
`prepareThreshold=0` fix so every execution is custom-planned (see `e1-generic-plan-flip-evidence.md` for why
that fix exists, and the two preserved earlier runs `e1-statistics-target-run1.md` /
`e1-statistics-target-run2-plancache-flip.md` for the plancache-flip history). Caveat: this run executed
~80 minutes into the E0→E1 sequence on an OS-page-cache substantially warmed by the two earlier full runs, so
its absolute latencies are flattered relative to E0's colder pass; the plan shapes, estimate ratios and DDL
timings are the load-bearing evidence here. The statistics DDL itself was (re-)applied idempotently in every
run; targets active: wpath=10000, val_text=1000, val_num=1000.
