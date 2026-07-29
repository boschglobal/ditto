# E1 review-fix (round 1, Finding 3) - wpath-ALONE statistics estimate

The brief asked for the wpath-only statistics-target lever to be measured in isolation FIRST, before escalating to val_text/val_num — the committed `e1-statistics-target.md` only shows the escalated/combined state (wpath=10000 + val_text=1000 + val_num=1000), so the wpath-alone number was never actually committed. This file isolates it: val_text/val_num are reset to the default statistics target (`-1`, which resolves to the system default of 100, pooled table-wide across every wpath) while wpath stays at the already-applied 10000, then shape 1 is re-measured. Afterwards val_text/val_num are RESTORED to 1000 and re-ANALYZEd so the bench DB ends this method in exactly the state `db-end-state.md` documents — the restore is verified below via a `pg_attribute.attstattarget` snapshot taken both in the wpath-alone state and after the restore.

## DDL / settings applied this phase

```sql
ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS -1;  -- 3ms
ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS -1;  -- 0ms
ANALYZE search_flat;  -- 26652ms
ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS 1000;  -- 1ms
ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS 1000;  -- 0ms
ANALYZE search_flat;  -- 25015ms
```

## pg_attribute.attstattarget — wpath-ALONE state (val_text/val_num reset to default)

```
val_num = -1
val_text = -1
wpath = 10000
```

## shape 1 (eq two-predicate AND, auth+gr) — wpath-ALONE statistics state

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
| e1-wpath-alone                             |         20 |       36 |       46 |     1318 |     1372 |

Index-backed (no seq scan on search_flat): true
Top plan node: Nested Loop Semi Join
Warm p95 > 200ms (red flag): true

`sf_num` plan line: estimated 408 rows vs actual 13869 rows (34.0x).
Top-level PG shared-buffer hit ratio: 100.0% (hit=73519 read=0).

```
Nested Loop Semi Join  (cost=40.01..3602.26 rows=1 width=41) (actual time=35.210..35.211 rows=0 loops=1)
  Join Filter: (st.thing_id = s.thing_id)
  Buffers: shared hit=73519
  ->  Nested Loop  (cost=39.31..3532.31 rows=4 width=82) (actual time=4.985..35.171 rows=5 loops=1)
        Buffers: shared hit=73486
        ->  HashAggregate  (cost=38.76..42.84 rows=408 width=41) (actual time=3.428..4.559 rows=13869 loops=1)
              Group Key: s_1.thing_id
              Batches: 1  Memory Usage: 1809kB
              Buffers: shared hit=4141
              ->  Index Only Scan using sf_num_covering on search_flat s_1  (cost=0.56..37.74 rows=408 width=41) (actual time=0.006..1.680 rows=13869 loops=1)
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
Planning Time: 0.290 ms
Execution Time: 35.229 ms
```

## Restore verification — pg_attribute.attstattarget after restore (must equal db-end-state.md: wpath=10000, val_text=1000, val_num=1000)

```
val_num = 1000
val_text = 1000
wpath = 10000
```
