# E3 - covering indexes (INCLUDE (thing_id) on sf_num/sf_text)

Lever: `CREATE INDEX CONCURRENTLY` variants of sf_num/sf_text with `INCLUDE (thing_id)` (plan doc §3.2 "Phase-0 slot" comment), so the flat-side EXISTS probe can in principle resolve wpath+value->thing_id without a search_flat heap fetch. This does NOT touch the per-candidate recheck cost that Task 0.2's root-cause #2 attributes to `search_things`/DOC-row probes — quantified honestly below, shape by shape.

## DDL / settings applied this phase

```sql
CREATE INDEX CONCURRENTLY sf_num_covering ON search_flat (wpath, val_num) INCLUDE (thing_id) WHERE val_num IS NOT NULL;  -- 50731ms
CREATE INDEX CONCURRENTLY sf_text_covering ON search_flat (wpath, val_text) INCLUDE (thing_id) WHERE val_text IS NOT NULL;  -- 41908ms
-- sizes: sf_num_covering = 1848 MB / sf_text_covering = 1670 MB
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
| e3-covering-indexes                        |         20 |       42 |       52 |     1549 |     1645 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=73519 read=0).
Index-only scan on `sf_num_covering`: true; on `sf_text_covering`: false

```
Nested Loop Semi Join  (cost=40.03..3610.89 rows=1 width=41) (actual time=34.018..34.019 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=73519
  ->  Nested Loop  (cost=39.34..3540.94 rows=4 width=82) (actual time=4.929..33.981 rows=5 loops=1)
        Buffers: shared hit=73486
        ->  HashAggregate  (cost=38.79..42.88 rows=409 width=41) (actual time=3.499..4.634 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared hit=4141
              ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.77 rows=409 width=41) (actual time=0.006..1.809 rows=13869 loops=1)
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
Planning Time: 0.346 ms
Execution Time: 34.034 ms
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
| e3-covering-indexes                        |         20 |       46 |       61 |      238 |      316 |

Index-backed (no seq scan on search_flat): true
Top plan node: Gather
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 73061 rows vs actual 32301 rows (0.4x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=57065 read=0).

```
Gather  (cost=13786.21..87655.90 rows=1655 width=41) (actual time=18.193..43.724 rows=59 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=57065
  ->  Parallel Hash Semi Join  (cost=12786.21..86490.40 rows=690 width=41) (actual time=16.687..37.420 rows=20 loops=3)
        Hash Cond: (st.thing_id = s.thing_id)
        Buffers: shared hit=57065
        ->  Parallel Bitmap Heap Scan on search_things st  (cost=204.32..73653.33 rows=3928 width=41) (actual time=6.463..28.097 rows=191 loops=3)
              Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
              Filter: ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
              Rows Removed by Filter: 9613
              Heap Blocks: exact=12676
              Buffers: shared hit=27145
              ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=3.611..3.611 rows=29411 loops=1)
                    Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Buffers: shared hit=15
        ->  Parallel Hash  (cost=11668.63..11668.63 rows=73061 width=41) (actual time=8.858..8.859 rows=32301 loops=3)
              Buckets: 262144  Batches: 1  Memory Usage: 9632kB
              Buffers: shared hit=29818
              ->  Parallel Index Only Scan using sf_num_covering on search_flat s  (cost=0.56..11668.63 rows=73061 width=41) (actual time=0.024..5.057 rows=32301 loops=3)
                    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num > 38.0))
                    Heap Fetches: 0
                    Buffers: shared hit=29818
Planning:
  Buffers: shared hit=23
Planning Time: 0.278 ms
Execution Time: 43.746 ms
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
| e3-covering-indexes                        |         20 |       56 |      245 |     1903 |     1905 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop
Warm p95 > 200ms (red flag): true

Uses table-wide `sf_trgm` bitmap index scan: true
Uses wpath-scoped `sf_trgm_scoped_feature`: false
Top-level PG shared-buffer hit ratio: 100.0% (hit=39668 read=0).

```
Nested Loop  (cost=70767.46..94159.18 rows=27 width=41) (actual time=244.626..250.221 rows=3 loops=1)
  Buffers: shared hit=39668
  ->  HashAggregate  (cost=70766.91..70795.45 rows=2854 width=41) (actual time=236.748..237.106 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 721kB
        Buffers: shared hit=18453
        ->  Bitmap Heap Scan on search_flat s  (cost=59690.35..70759.76 rows=2859 width=41) (actual time=219.033..236.098 rows=4260 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
              Rows Removed by Index Recheck: 15698
              Heap Blocks: exact=14487
              Buffers: shared hit=18453
              ->  BitmapAnd  (cost=59690.35..59690.35 rows=2859 width=0) (actual time=217.720..217.720 rows=0 loops=1)
                    Buffers: shared hit=3966
                    ->  Bitmap Index Scan on sf_trgm  (cost=0.00..181.56 rows=43238 width=0) (actual time=2.686..2.686 rows=44298 loops=1)
                          Index Cond: ((val_text)::text ~~* '%234%'::text)
                          Buffers: shared hit=17
                    ->  Bitmap Index Scan on sf_exists  (cost=0.00..59507.11 rows=4531006 width=0) (actual time=209.266..209.266 rows=4544614 loops=1)
                          Index Cond: (wpath = '/features/*/properties/prop0'::text)
                          Buffers: shared hit=3949
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.20 rows=1 width=41) (actual time=0.003..0.003 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=21215
Planning:
  Buffers: shared hit=24
Planning Time: 0.723 ms
Execution Time: 250.242 ms
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
| e3-covering-indexes                        |         20 |       34 |       38 |       43 |       43 |

Index-backed (no seq scan on search_flat): true
Top plan node: Aggregate
Warm p95 > 200ms (red flag): false

`sf_num` plan line: estimated 409 rows vs actual 13869 rows (33.9x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=73519 read=0).
Index-only scan on `sf_num_covering`: true; on `sf_text_covering`: false

```
Aggregate  (cost=3610.90..3610.91 rows=1 width=8) (actual time=33.701..33.702 rows=1 loops=1)
  Buffers: shared hit=73519
  ->  Nested Loop Semi Join  (cost=40.03..3610.89 rows=1 width=0) (actual time=33.700..33.700 rows=0 loops=1)
        Join Filter: (st.thing_id = s.thing_id)
        Buffers: shared hit=73519
        ->  Nested Loop  (cost=39.34..3540.94 rows=4 width=82) (actual time=4.700..33.671 rows=5 loops=1)
              Buffers: shared hit=73486
              ->  HashAggregate  (cost=38.79..42.88 rows=409 width=41) (actual time=3.332..4.423 rows=13869 loops=1)
                    Group Key: s_1.thing_id
                    Batches: 1  Memory Usage: 1809kB
                    Buffers: shared hit=4141
                    ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.77 rows=409 width=41) (actual time=0.007..1.730 rows=13869 loops=1)
                          Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                          Heap Fetches: 0
                          Buffers: shared hit=4141
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.55 rows=1 width=41) (actual time=0.002..0.002 rows=0 loops=13869)
                    Index Cond: (thing_id = s_1.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=69345
        ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..17.47 rows=1 width=41) (actual time=0.005..0.006 rows=0 loops=5)
              Index Cond: ((thing_id = s_1.thing_id) AND (wpath = '/attributes/location/city'::text))
              Filter: (val_text = 'Stuttgart'::text)
              Rows Removed by Filter: 1
              Buffers: shared hit=33
Planning:
  Buffers: shared hit=70
Planning Time: 0.350 ms
Execution Time: 33.720 ms
```

