# psql checks next to the A/B runs: full-order identity, index-served sorts, JIT

Supplementary, read-only measurements on the same container, corpus and server settings as the A/B runs in this
folder (`ditto-search-bench-pc`, postgres:16 image defaults, 1,000,000 things, subject pair
`{user:sub-000,user:sub-041}`, 29,411 things visible). They back the numbers that the A/B harness runs do not
produce. Each check ran as one `psql` session inside the container, started with
`SET default_transaction_read_only = on` and `SET max_parallel_workers = 0` (leader-only, as the shipped `findAll`
executes). Taken 2026-10-01, 02:45–02:47 UTC, with the path-bound lateral in place.

## 1. Full-order identity of the old and the path-bound sort lateral

Every visible thing is numbered by the full shape-4 `ORDER BY` once with the old lateral (`wpath` only) and once
with the path-bound one. The check compares the two numberings row by row on all five columns the sort reads
and computes an md5 over `thing_id:type_rank:val_num` in rank order for each.

Script (verbatim):

```sql
SET max_parallel_workers = 0;
\echo direction ASC
WITH old_order AS MATERIALIZED (SELECT row_number() OVER (ORDER BY COALESCE(CASE WHEN k0.type_rank = 5 THEN 1 ELSE k0.type_rank END, 1), k0.val_num, k0.val_text, k0.val_bool, st.thing_id) AS pos, st.thing_id, k0.type_rank, k0.val_num, k0.val_text, k0.val_bool FROM search_things st LEFT JOIN LATERAL (SELECT s.type_rank, s.val_num, s.val_text, s.val_bool FROM search_flat s WHERE (s.thing_id = st.thing_id AND s.wpath = '/features/env/properties/temperature') ORDER BY s.type_rank, s.val_num, s.val_text, s.val_bool LIMIT 1) k0 ON true WHERE st.global_read && '{user:sub-000,user:sub-041}'::text[]),
     new_order AS MATERIALIZED (SELECT row_number() OVER (ORDER BY COALESCE(CASE WHEN k0.type_rank = 5 THEN 1 ELSE k0.type_rank END, 1), k0.val_num, k0.val_text, k0.val_bool, st.thing_id) AS pos, st.thing_id, k0.type_rank, k0.val_num, k0.val_text, k0.val_bool FROM search_things st LEFT JOIN LATERAL (SELECT s.type_rank, s.val_num, s.val_text, s.val_bool FROM search_flat s WHERE (s.thing_id = st.thing_id AND s.path = '/features/env/properties/temperature' AND s.wpath = '/features/env/properties/temperature') ORDER BY s.type_rank, s.val_num, s.val_text, s.val_bool LIMIT 1) k0 ON true WHERE st.global_read && '{user:sub-000,user:sub-041}'::text[])
SELECT (SELECT count(*) FROM old_order) AS old_rows, (SELECT count(*) FROM new_order) AS new_rows,
       (SELECT count(*) FROM old_order o FULL JOIN new_order n USING (pos) WHERE o.thing_id IS DISTINCT FROM n.thing_id
          OR o.type_rank IS DISTINCT FROM n.type_rank OR o.val_num IS DISTINCT FROM n.val_num
          OR o.val_text IS DISTINCT FROM n.val_text OR o.val_bool IS DISTINCT FROM n.val_bool) AS mismatches,
       (SELECT md5(string_agg(thing_id || ':' || coalesce(type_rank::text, '') || ':' || coalesce(val_num::text, ''), ',' ORDER BY pos)) FROM old_order) AS old_md5,
       (SELECT md5(string_agg(thing_id || ':' || coalesce(type_rank::text, '') || ':' || coalesce(val_num::text, ''), ',' ORDER BY pos)) FROM new_order) AS new_md5;
\echo direction DESC
WITH old_order AS MATERIALIZED (SELECT row_number() OVER (ORDER BY COALESCE(CASE WHEN k0.type_rank = 5 THEN 1 ELSE k0.type_rank END, 1) DESC, k0.val_num DESC, k0.val_text DESC, k0.val_bool DESC, st.thing_id) AS pos, st.thing_id, k0.type_rank, k0.val_num, k0.val_text, k0.val_bool FROM search_things st LEFT JOIN LATERAL (SELECT s.type_rank, s.val_num, s.val_text, s.val_bool FROM search_flat s WHERE (s.thing_id = st.thing_id AND s.wpath = '/features/env/properties/temperature') ORDER BY s.type_rank DESC, s.val_num DESC, s.val_text DESC, s.val_bool DESC LIMIT 1) k0 ON true WHERE st.global_read && '{user:sub-000,user:sub-041}'::text[]),
     new_order AS MATERIALIZED (SELECT row_number() OVER (ORDER BY COALESCE(CASE WHEN k0.type_rank = 5 THEN 1 ELSE k0.type_rank END, 1) DESC, k0.val_num DESC, k0.val_text DESC, k0.val_bool DESC, st.thing_id) AS pos, st.thing_id, k0.type_rank, k0.val_num, k0.val_text, k0.val_bool FROM search_things st LEFT JOIN LATERAL (SELECT s.type_rank, s.val_num, s.val_text, s.val_bool FROM search_flat s WHERE (s.thing_id = st.thing_id AND s.path = '/features/env/properties/temperature' AND s.wpath = '/features/env/properties/temperature') ORDER BY s.type_rank DESC, s.val_num DESC, s.val_text DESC, s.val_bool DESC LIMIT 1) k0 ON true WHERE st.global_read && '{user:sub-000,user:sub-041}'::text[])
SELECT (SELECT count(*) FROM old_order) AS old_rows, (SELECT count(*) FROM new_order) AS new_rows,
       (SELECT count(*) FROM old_order o FULL JOIN new_order n USING (pos) WHERE o.thing_id IS DISTINCT FROM n.thing_id
          OR o.type_rank IS DISTINCT FROM n.type_rank OR o.val_num IS DISTINCT FROM n.val_num
          OR o.val_text IS DISTINCT FROM n.val_text OR o.val_bool IS DISTINCT FROM n.val_bool) AS mismatches,
       (SELECT md5(string_agg(thing_id || ':' || coalesce(type_rank::text, '') || ':' || coalesce(val_num::text, ''), ',' ORDER BY pos)) FROM old_order) AS old_md5,
       (SELECT md5(string_agg(thing_id || ':' || coalesce(type_rank::text, '') || ':' || coalesce(val_num::text, ''), ',' ORDER BY pos)) FROM new_order) AS new_md5;
```

Output (verbatim; first and last lines are the UTC start and end stamps of the run):

```
2026-10-01T02:45:23Z
SET
SET
direction ASC
 old_rows | new_rows | mismatches |             old_md5              |             new_md5              
----------+----------+------------+----------------------------------+----------------------------------
    29411 |    29411 |          0 | 45e4582c0fc78dc6fc3e199ee42191ca | 45e4582c0fc78dc6fc3e199ee42191ca
(1 row)

direction DESC
 old_rows | new_rows | mismatches |             old_md5              |             new_md5              
----------+----------+------------+----------------------------------+----------------------------------
    29411 |    29411 |          0 | 7f3e6b8142300174163b9eefda69caf2 | 7f3e6b8142300174163b9eefda69caf2
(1 row)

2026-10-01T02:45:27Z
```

Result: 29,411 rows on both sides in both directions, 0 mismatching positions, identical digests.

## 2. Timings: index-served sorts and the cursor page with JIT on and off

**Protocol** (smaller than the harness's, documented here):

- Per statement: 3 warmup executions, then 20 timed executions with `\timing on` and the result discarded
  (`\g /dev/null`), nearest-rank percentiles. `psql` uses the simple query protocol and fetches all 26 rows; the
  timings include the round trip inside the container.
- Then one `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` of the statement (excerpts below).
- The cursor page ran in 4 blocks, alternating `SET jit = on` / `SET jit = off` (on, off, on, off), each block with
  its own 3 warmups.

**Statements**, binds inlined. The `thingId` and `namespace` statements were printed through
`SearchQueryAssembler.findAll` (page 100 differs from page 1 only in `OFFSET`). The cursor statement is the B1
`4-keyset` statement of `B1-candidate/shape-4-sort-paging.md` with its binds:

- `sort(+thingId)`, page 1 (`size(25)`):

  ```sql
  SELECT st.thing_id, st.t_modified, st.thing_id AS s0_val FROM search_things st WHERE (true AND st.global_read && '{user:sub-000,user:sub-041}'::text[]) ORDER BY st.thing_id LIMIT 26 OFFSET 0
  ```

- `sort(+thingId)`, page 100 (`limit(2475,25)`):

  ```sql
  SELECT st.thing_id, st.t_modified, st.thing_id AS s0_val FROM search_things st WHERE (true AND st.global_read && '{user:sub-000,user:sub-041}'::text[]) ORDER BY st.thing_id LIMIT 26 OFFSET 2475
  ```

- `sort(+namespace,+thingId)`, page 1:

  ```sql
  SELECT st.thing_id, st.t_modified, st.namespace AS s0_val, st.thing_id AS s1_val FROM search_things st WHERE (true AND st.global_read && '{user:sub-000,user:sub-041}'::text[]) ORDER BY st.namespace, st.thing_id LIMIT 26 OFFSET 0
  ```

- `sort(+features/env/properties/temperature)`, page 100 via the service cursor:

  ```sql
  SELECT st.thing_id, st.t_modified, k0.type_rank AS s0_rank, k0.val_num AS s0_num, k0.val_text AS s0_text, k0.val_bool AS s0_bool, st.thing_id AS s1_val FROM search_things st LEFT JOIN LATERAL (SELECT s.type_rank, s.val_num, s.val_text, s.val_bool FROM search_flat s WHERE (s.thing_id = st.thing_id AND s.path = '/features/env/properties/temperature' AND s.wpath = '/features/env/properties/temperature') ORDER BY s.type_rank, s.val_num, s.val_text, s.val_bool LIMIT 1) k0 ON true WHERE ((true AND ((EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = '/features/env/properties/temperature')) AND (NOT COALESCE((st.policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND COALESCE((st.policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false))))))))))) OR ((NOT (EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = '/features/env/properties/temperature')) AND (NOT COALESCE((st.policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND COALESCE((st.policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false))))))))))) OR (EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = '/features/env/properties/temperature' AND sf.type_rank = 1)) AND (NOT COALESCE((st.policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND (COALESCE((st.policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) OR (NOT COALESCE((st.policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false) AND COALESCE((st.policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[], false)))))))))))) AND st.thing_id > 'org.eclipse.ditto.bench.ns4:thing-767370'))) AND st.global_read && '{user:sub-000,user:sub-041}'::text[]) ORDER BY COALESCE(CASE WHEN k0.type_rank = 5 THEN 1 ELSE k0.type_rank END, 1), k0.val_num, k0.val_text, k0.val_bool, st.thing_id LIMIT 26 OFFSET 0
  ```

**Results** (ms):

| Statement | n | min | p50 | p95 | max |
|---|---:|---:|---:|---:|---:|
| `sort(+thingId)` page 1 | 20 | 0.28 | 0.29 | **0.30** | 0.30 |
| `sort(+thingId)` page 100 via OFFSET 2475 | 20 | 114.84 | 117.26 | **129.40** | 131.48 |
| `sort(+namespace,+thingId)` page 1 | 20 | 0.29 | 0.30 | **0.34** | 0.34 |
| temperature sort, page 100 via cursor, `jit = on`, block 1 | 20 | 607.83 | 631.24 | **669.59** | 676.72 |
| temperature sort, page 100 via cursor, `jit = off`, block 1 | 20 | 417.77 | 436.25 | **460.70** | 461.23 |
| temperature sort, page 100 via cursor, `jit = on`, block 2 | 20 | 611.38 | 623.50 | **659.82** | 695.45 |
| temperature sort, page 100 via cursor, `jit = off`, block 2 | 20 | 417.30 | 437.59 | **458.57** | 458.98 |
| temperature sort, page 100 via cursor, `jit = on`, both blocks | 40 | 607.83 | 630.40 | **669.59** | 695.45 |
| temperature sort, page 100 via cursor, `jit = off`, both blocks | 40 | 417.30 | 437.59 | **458.98** | 461.23 |

Reading:

- `thingId` and `namespace` sorts are served by `search_things_pkey` / `st_namespace` (plans below): page 1 well
  under 1 ms warm. A deep OFFSET still walks the index past every skipped thing and rechecks `global_read` on
  each, so page 100 costs ~115–130 ms here.
- The cursor page spends ~200 ms of each execution in JIT compilation (`JIT … Total` below). With `jit = off` the
  same statement took 459 instead of 670 ms p95 (438 instead of 630 ms p50). The plan is the same in
  both modes.

## 3. Plans (`EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`, verbatim)

### `sort(+thingId)`, page 1

```
                                                                      QUERY PLAN                                                                      
------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=0.55..603.71 rows=26 width=90) (actual time=0.004..0.257 rows=26 loops=1)
   Buffers: shared hit=639
   ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..698991.49 rows=30131 width=90) (actual time=0.003..0.256 rows=26 loops=1)
         Filter: (global_read && '{user:sub-000,user:sub-041}'::text[])
         Rows Removed by Filter: 969
         Buffers: shared hit=639
 Settings: max_parallel_workers = '0'
 Planning:
   Buffers: shared hit=1
 Planning Time: 0.017 ms
 Execution Time: 0.260 ms
(11 rows)
```

### `sort(+thingId)`, page 100

```
                                                                        QUERY PLAN                                                                        
----------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=57416.59..58019.74 rows=26 width=90) (actual time=114.750..115.771 rows=26 loops=1)
   Buffers: shared hit=6292 read=48289
   ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..698991.49 rows=30131 width=90) (actual time=0.025..115.732 rows=2501 loops=1)
         Filter: (global_read && '{user:sub-000,user:sub-041}'::text[])
         Rows Removed by Filter: 81506
         Buffers: shared hit=6292 read=48289
 Settings: max_parallel_workers = '0'
 Planning:
   Buffers: shared read=1
 Planning Time: 0.057 ms
 Execution Time: 115.779 ms
(11 rows)
```

### `sort(+namespace,+thingId)`, page 1

```
                                                                   QUERY PLAN                                                                    
-------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=0.55..595.78 rows=26 width=118) (actual time=0.004..0.253 rows=26 loops=1)
   Buffers: shared hit=636
   ->  Index Scan using st_namespace on search_things st  (cost=0.55..689801.72 rows=30131 width=118) (actual time=0.004..0.252 rows=26 loops=1)
         Filter: (global_read && '{user:sub-000,user:sub-041}'::text[])
         Rows Removed by Filter: 969
         Buffers: shared hit=636
 Settings: max_parallel_workers = '0'
 Planning:
   Buffers: shared hit=1
 Planning Time: 0.019 ms
 Execution Time: 0.256 ms
(11 rows)
```

### cursor page 100, `jit = on`

```
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               QUERY PLAN                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=9468817.01..9468817.08 rows=26 width=114) (actual time=622.238..622.241 rows=26 loops=1)
   Buffers: shared hit=289798 read=106223
   ->  Sort  (cost=9468817.01..9468837.94 rows=8369 width=114) (actual time=419.802..419.804 rows=26 loops=1)
         Sort Key: (COALESCE(CASE WHEN (s.type_rank = 5) THEN 1 ELSE (s.type_rank)::integer END, 1)), s.val_num, s.val_text COLLATE "C", s.val_bool, st.thing_id COLLATE "C"
         Sort Method: top-N heapsort  Memory: 31kB
         Buffers: shared hit=289798 read=106223
         ->  Nested Loop Left Join  (cost=219.81..9468578.48 rows=8369 width=114) (actual time=4.851..419.243 rows=5040 loops=1)
               Buffers: shared hit=289798 read=106223
               ->  Bitmap Heap Scan on search_things st  (cost=211.08..9395391.57 rows=8369 width=49) (actual time=4.841..397.066 rows=5040 loops=1)
                     Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                     Filter: (((SubPlan 1) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)))))))))) OR (((NOT (SubPlan 2)) OR COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR (NOT COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)))))))))) OR ((hashed SubPlan 4) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))) AND (thing_id > 'org.eclipse.ditto.bench.ns4:thing-767370'::text)))
                     Rows Removed by Filter: 24371
                     Heap Blocks: exact=27130
                     Buffers: shared hit=264622 read=101635
                     ->  Bitmap Index Scan on st_global_read  (cost=0.00..208.99 rows=30131 width=0) (actual time=2.301..2.301 rows=29411 loops=1)
                           Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                           Buffers: shared hit=2 read=13
                     SubPlan 1
                       ->  Index Only Scan using search_flat_pkey on search_flat sf  (cost=0.69..205.36 rows=22 width=0) (actual time=0.008..0.008 rows=1 loops=29411)
                             Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                             Heap Fetches: 0
                             Buffers: shared hit=96515 read=74492
                     SubPlan 2
                       ->  Index Only Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..205.36 rows=22 width=0) (actual time=0.002..0.002 rows=1 loops=28906)
                             Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                             Heap Fetches: 0
                             Buffers: shared hit=168105
                     SubPlan 4
                       ->  Gather  (cost=13191.69..1691847.28 rows=54300 width=41) (never executed)
                             Workers Planned: 2
                             Workers Launched: 0
                             ->  Parallel Bitmap Heap Scan on search_flat sf_2  (cost=12191.69..1685417.28 rows=22625 width=41) (never executed)
                                   Recheck Cond: (wpath = '/features/env/properties/temperature'::text)
                                   Filter: (type_rank = 1)
                                   ->  Bitmap Index Scan on sf_exists  (cost=0.00..12178.12 rows=927140 width=0) (never executed)
                                         Index Cond: (wpath = '/features/env/properties/temperature'::text)
               ->  Limit  (cost=8.72..8.73 rows=1 width=20) (actual time=0.004..0.004 rows=1 loops=5040)
                     Buffers: shared hit=25176 read=4588
                     ->  Sort  (cost=8.72..8.73 rows=1 width=20) (actual time=0.004..0.004 rows=1 loops=5040)
                           Sort Key: s.type_rank, s.val_num, s.val_text COLLATE "C", s.val_bool
                           Sort Method: quicksort  Memory: 25kB
                           Buffers: shared hit=25176 read=4588
                           ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..8.71 rows=1 width=20) (actual time=0.003..0.004 rows=1 loops=5040)
                                 Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                                 Buffers: shared hit=25176 read=4588
 Settings: max_parallel_workers = '0'
 Planning:
   Buffers: shared read=1
 Planning Time: 0.254 ms
 JIT:
   Functions: 38
   Options: Inlining true, Optimization true, Expressions true, Deforming true
   Timing: Generation 1.204 ms, Inlining 6.683 ms, Optimization 100.075 ms, Emission 95.682 ms, Total 203.644 ms
 Execution Time: 623.522 ms
(54 rows)
```

### cursor page 100, `jit = off`

```
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               QUERY PLAN                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=9468817.01..9468817.08 rows=26 width=114) (actual time=421.868..421.871 rows=26 loops=1)
   Buffers: shared hit=289798 read=106223
   ->  Sort  (cost=9468817.01..9468837.94 rows=8369 width=114) (actual time=421.868..421.869 rows=26 loops=1)
         Sort Key: (COALESCE(CASE WHEN (s.type_rank = 5) THEN 1 ELSE (s.type_rank)::integer END, 1)), s.val_num, s.val_text COLLATE "C", s.val_bool, st.thing_id COLLATE "C"
         Sort Method: top-N heapsort  Memory: 31kB
         Buffers: shared hit=289798 read=106223
         ->  Nested Loop Left Join  (cost=219.81..9468578.48 rows=8369 width=114) (actual time=4.676..421.346 rows=5040 loops=1)
               Buffers: shared hit=289798 read=106223
               ->  Bitmap Heap Scan on search_things st  (cost=211.08..9395391.57 rows=8369 width=49) (actual time=4.672..399.115 rows=5040 loops=1)
                     Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                     Filter: (((SubPlan 1) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)))))))))) OR (((NOT (SubPlan 2)) OR COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR (NOT COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)))))))))) OR ((hashed SubPlan 4) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))) AND (thing_id > 'org.eclipse.ditto.bench.ns4:thing-767370'::text)))
                     Rows Removed by Filter: 24371
                     Heap Blocks: exact=27130
                     Buffers: shared hit=264622 read=101635
                     ->  Bitmap Index Scan on st_global_read  (cost=0.00..208.99 rows=30131 width=0) (actual time=2.247..2.247 rows=29411 loops=1)
                           Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                           Buffers: shared hit=2 read=13
                     SubPlan 1
                       ->  Index Only Scan using search_flat_pkey on search_flat sf  (cost=0.69..205.36 rows=22 width=0) (actual time=0.008..0.008 rows=1 loops=29411)
                             Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                             Heap Fetches: 0
                             Buffers: shared hit=96515 read=74492
                     SubPlan 2
                       ->  Index Only Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..205.36 rows=22 width=0) (actual time=0.002..0.002 rows=1 loops=28906)
                             Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                             Heap Fetches: 0
                             Buffers: shared hit=168105
                     SubPlan 4
                       ->  Gather  (cost=13191.69..1691847.28 rows=54300 width=41) (never executed)
                             Workers Planned: 2
                             Workers Launched: 0
                             ->  Parallel Bitmap Heap Scan on search_flat sf_2  (cost=12191.69..1685417.28 rows=22625 width=41) (never executed)
                                   Recheck Cond: (wpath = '/features/env/properties/temperature'::text)
                                   Filter: (type_rank = 1)
                                   ->  Bitmap Index Scan on sf_exists  (cost=0.00..12178.12 rows=927140 width=0) (never executed)
                                         Index Cond: (wpath = '/features/env/properties/temperature'::text)
               ->  Limit  (cost=8.72..8.73 rows=1 width=20) (actual time=0.004..0.004 rows=1 loops=5040)
                     Buffers: shared hit=25176 read=4588
                     ->  Sort  (cost=8.72..8.73 rows=1 width=20) (actual time=0.004..0.004 rows=1 loops=5040)
                           Sort Key: s.type_rank, s.val_num, s.val_text COLLATE "C", s.val_bool
                           Sort Method: quicksort  Memory: 25kB
                           Buffers: shared hit=25176 read=4588
                           ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..8.71 rows=1 width=20) (actual time=0.003..0.004 rows=1 loops=5040)
                                 Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                                 Buffers: shared hit=25176 read=4588
 Settings: max_parallel_workers = '0', jit = 'off'
 Planning:
   Buffers: shared read=1
 Planning Time: 0.254 ms
 Execution Time: 421.904 ms
(50 rows)
```

## Appendix: every timed execution (ms, in execution order, `psql` `Time:` lines)

- `thingId-page1`: 0.302, 0.299, 0.287, 0.287, 0.287, 0.290, 0.286, 0.289, 0.294, 0.286, 0.291, 0.287, 0.279, 0.285, 0.278, 0.287, 0.293, 0.279, 0.290, 0.282
- `thingId-page100-offset`: 128.292, 131.482, 118.266, 114.841, 116.995, 115.550, 115.131, 118.915, 120.018, 126.458, 129.400, 126.220, 121.127, 116.684, 115.840, 114.973, 117.631, 117.263, 115.691, 114.893
- `namespace-page1`: 0.345, 0.307, 0.338, 0.317, 0.317, 0.319, 0.302, 0.286, 0.296, 0.293, 0.294, 0.287, 0.306, 0.294, 0.299, 0.290, 0.289, 0.297, 0.289, 0.312
- `cursor-page100-jit-on-r1`: 619.241, 648.838, 622.196, 646.756, 669.587, 641.859, 621.917, 657.650, 613.233, 612.962, 647.475, 616.819, 607.829, 639.641, 666.077, 631.240, 676.716, 630.057, 611.448, 636.939
- `cursor-page100-jit-off-r1`: 426.147, 453.945, 445.495, 437.602, 434.688, 440.503, 456.387, 423.829, 420.867, 419.867, 460.703, 455.746, 423.780, 436.250, 442.398, 461.233, 420.201, 420.765, 417.767, 444.748
- `cursor-page100-jit-on-r2`: 655.331, 634.610, 631.871, 652.149, 613.396, 617.809, 655.190, 613.107, 613.829, 659.819, 614.182, 612.736, 695.453, 620.069, 611.381, 635.443, 630.402, 618.666, 623.499, 654.443
- `cursor-page100-jit-off-r2`: 456.143, 425.688, 437.312, 435.366, 458.574, 446.424, 434.124, 435.241, 437.650, 456.480, 419.580, 417.299, 418.882, 437.761, 451.137, 437.587, 440.435, 433.586, 458.980, 440.025
