# E4 - wpath-scoped trigram (partial index, option (b))

Lever: a partial trigram GIN index scoped to the exact high-cardinality path `/features/*/properties/prop0` (option (b) from the brief — see this method's Javadoc for why over option (a)'s table-wide btree_gin composite). Measures whether the planner picks the scoped index over the unscoped table-wide `sf_trgm` for this wpath, and whether that closes shape 3-high's red flag.

## DDL / settings applied this phase

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS sf_trgm_scoped_feature ON search_flat USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops) WHERE wpath = '/features/*/properties/prop0' AND val_text IS NOT NULL;  -- 3ms  [NO-OP: index already existed from a discarded earlier run — this "3ms" timed IF NOT EXISTS's skip, not a build; see "Review-fix (round 1, Finding 2)" below for the real, clean CONCURRENTLY build time]
-- size: sf_trgm_scoped_feature = 51 MB
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
| e4-scoped-trigram                          |         20 |       41 |       48 |       87 |       98 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): false

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=73519 read=0).

```
Nested Loop Semi Join  (cost=40.03..3610.89 rows=1 width=41) (actual time=41.934..41.935 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=73519
  ->  Nested Loop  (cost=39.34..3540.94 rows=4 width=82) (actual time=6.269..41.899 rows=5 loops=1)
        Buffers: shared hit=73486
        ->  HashAggregate  (cost=38.79..42.88 rows=409 width=41) (actual time=4.422..5.942 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared hit=4141
              ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.77 rows=409 width=41) (actual time=0.008..2.092 rows=13869 loops=1)
                    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                    Heap Fetches: 0
                    Buffers: shared hit=4141
        ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.002..0.002 rows=0 loops=13869)
              Index Cond: (thing_id = s_1.thing_id)
              Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 1
              Buffers: shared hit=69345
  ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.007..0.007 rows=0 loops=5)
        Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
        Filter: (val_text = 'Stuttgart'::text)
        Rows Removed by Filter: 1
        Buffers: shared hit=33
Planning:
  Buffers: shared hit=70
Planning Time: 0.495 ms
Execution Time: 42.040 ms
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
| e4-scoped-trigram                          |         20 |       48 |       57 |       71 |       83 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): false

`sf_num` plan line: estimated 73061 rows vs actual 32301 rows (0.4x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=57075 read=0).

```
Gather  (cost=13786.21..87655.90 rows=1655 width=41) (actual time=19.675..49.934 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=57075
  ->  Parallel Hash Semi Join  (cost=12786.21..86490.40 rows=690 width=41) (actual time=19.704..42.956 rows=20 loops=3)
        Hash Cond: (st.thing_id = s.thing_id)
        Buffers: shared hit=57075
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=7.298..32.193 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=12971
              Buffers: shared hit=27145
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=3.996..3.996 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=15
        ->  Parallel Hash  (cost=11668.63..11668.63 rows=73061 width=41) (actual time=10.237..10.238 rows=32301 loops=3)
              Buckets: 262144  Batches: 1  Memory Usage: 9632kB
              Buffers: shared hit=29828
              ->  Parallel Index Only Scan using sf_num_covering on search_flat s  (cost=0.56..11668.63 rows=73061 width=41) (actual time=0.034..5.647 rows=32301 loops=3)
                    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num > 38.0))
                    Heap Fetches: 0
                    Buffers: shared hit=29828
Planning:
  Buffers: shared hit=23
Planning Time: 0.304 ms
Execution Time: 49.957 ms
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
| e4-scoped-trigram                          |         20 |       21 |       22 |       49 |       62 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): false

Uses table-wide `sf_trgm` bitmap index scan: false
Uses wpath-scoped `sf_trgm_scoped_feature`: true
Top-level PG shared-buffer hit ratio: 100.0% (hit=25474 read=0).

```
Nested Loop  (cost=11110.48..34502.20 rows=27 width=41) (actual time=15.215..21.634 rows=3 loops=1)
  Buffers: shared hit=25474
  ->  HashAggregate  (cost=11109.93..11138.47 rows=2854 width=41) (actual time=6.606..7.002 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=4259
        ->  Bitmap Heap Scan on search_flat s  (cost=33.37..11102.78 rows=2859 width=41) (actual time=0.657..5.979 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Heap Blocks: exact=4253
              Buffers: shared hit=4259
              ->  Bitmap Index Scan on sf_trgm_scoped_feature  (cost=0.00..32.66 rows=2859 width=0) (actual time=0.279..0.279 rows=4260 loops=1)
                    Index Cond: ((val_text)::text ~~* '%234%'::text)
                    Buffers: shared hit=6
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.20 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=21215
Planning:
  Buffers: shared hit=25
Planning Time: 0.926 ms
Execution Time: 21.672 ms
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
| e4-scoped-trigram                          |         20 |       40 |       45 |       50 |       53 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): false

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=73519 read=0).

```
Aggregate  (cost=3610.90..3610.91 rows=1 width=8) (actual time=42.281..42.282 rows=1 loops=1)
  Buffers: shared hit=73519
  ->  Nested Loop Semi Join  (cost=40.03..3610.89 rows=1 width=0) (actual time=42.279..42.280 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=73519
        ->  Nested Loop  (cost=39.34..3540.94 rows=4 width=82) (actual time=6.697..42.243 rows=5 loops=1)
              Buffers: shared hit=73486
              ->  HashAggregate  (cost=38.79..42.88 rows=409 width=41) (actual time=4.781..6.257 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared hit=4141
                    ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.77 rows=409 width=41) (actual time=0.009..2.229 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Fetches: 0
                          Buffers: shared hit=4141
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.002..0.002 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=69345
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.007..0.007 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=33
Planning:
  Buffers: shared hit=70
Planning Time: 0.513 ms
Execution Time: 42.393 ms
```


---

## Review-fix (round 1, Finding 2): clean CONCURRENTLY rebuild with real timing

The DDL line above the `---` separator (`-- 3ms`) was a no-op — `sf_trgm_scoped_feature` already existed from a discarded earlier run before the committed run captured that line, so `CREATE INDEX CONCURRENTLY IF NOT EXISTS` skipped the actual build. Below is a clean `DROP INDEX CONCURRENTLY` + `CREATE INDEX CONCURRENTLY` (no `IF NOT EXISTS`) pair with the real build time and size, plus a re-measurement of shape 3-high against the freshly-built index (20 timed iterations, same protocol as the rest of E4).

```sql
DROP INDEX CONCURRENTLY IF EXISTS sf_trgm_scoped_feature;  -- 12ms
CREATE INDEX CONCURRENTLY sf_trgm_scoped_feature ON search_flat USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops) WHERE wpath = '/features/*/properties/prop0' AND val_text IS NOT NULL;  -- 21931ms
-- size: sf_trgm_scoped_feature = 51 MB
```

### shape 3-high (ilike, high-cardinality feature path) — re-measured against the clean rebuild

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e4-scoped-trigram-rebuild                  |         20 |       14 |       17 |      358 |      360 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: false
Uses wpath-scoped `sf_trgm_scoped_feature`: true
Top-level PG shared-buffer hit ratio: 100.0% (hit=25474 read=0).

```
Nested Loop  (cost=18514.85..56790.14 rows=46 width=41) (actual time=18.176..23.732 rows=3 loops=1)
  Buffers: shared hit=25474
  ->  HashAggregate  (cost=18514.30..18562.41 rows=4811 width=41) (actual time=9.402..9.873 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=4259
        ->  Bitmap Heap Scan on search_flat s  (cost=36.25..18502.24 rows=4824 width=41) (actual time=0.688..8.304 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Heap Blocks: exact=4253
              Buffers: shared hit=4259
              ->  Bitmap Index Scan on sf_trgm_scoped_feature  (cost=0.00..35.04 rows=4824 width=0) (actual time=0.360..0.361 rows=4260 loops=1)
                    Index Cond: ((val_text)::text ~~* '%234%'::text)
                    Buffers: shared hit=6
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..7.96 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=21215
Planning:
  Buffers: shared hit=25
Planning Time: 0.891 ms
Execution Time: 23.768 ms
```

### shape 3-high — second measurement pass (same rebuilt index, NO further DDL)

Immediately re-measures the index built by the DROP+CREATE above, with no changes — isolates whether the previous pass's p95 tail was a one-time fresh-index page-cache-miss cost (paid once per not-yet-touched ILIKE substring) rather than a steady-state regression.

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| e4-scoped-trigram-rebuild-2nd-pass         |         20 |       15 |       17 |       46 |       46 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): false

Uses table-wide `sf_trgm` bitmap index scan: false
Uses wpath-scoped `sf_trgm_scoped_feature`: true
Top-level PG shared-buffer hit ratio: 100.0% (hit=25474 read=0).

```
Nested Loop  (cost=18514.85..56790.14 rows=46 width=41) (actual time=16.476..21.850 rows=3 loops=1)
  Buffers: shared hit=25474
  ->  HashAggregate  (cost=18514.30..18562.41 rows=4811 width=41) (actual time=8.123..8.543 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=4259
        ->  Bitmap Heap Scan on search_flat s  (cost=36.25..18502.24 rows=4824 width=41) (actual time=1.151..7.263 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Heap Blocks: exact=4253
              Buffers: shared hit=4259
              ->  Bitmap Index Scan on sf_trgm_scoped_feature  (cost=0.00..35.04 rows=4824 width=0) (actual time=0.732..0.732 rows=4260 loops=1)
                    Index Cond: ((val_text)::text ~~* '%234%'::text)
                    Buffers: shared hit=6
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..7.96 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=21215
Planning:
  Buffers: shared hit=25
Planning Time: 1.067 ms
Execution Time: 21.925 ms
```
