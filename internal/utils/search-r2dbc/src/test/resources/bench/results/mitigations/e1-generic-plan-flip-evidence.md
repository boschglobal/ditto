# E1 side-finding — plancache generic-plan flip on parameterized-wpath ilike (the "55s anomaly")

## What was observed

E1 (statistics bump on wpath to 10000 + val_text/val_num to 1000) made shape 3-high's measured
latency explode from ~2.1-2.3s p50 (E0) to ~55-59s p50, uniformly, in BOTH full E1 harness runs
(run 1: 56.5-59.2s; run 2: 53.2-57.4s across all 20 timed iterations) — while the same-session
representative EXPLAIN showed 0.4s, and standalone reproductions (psql literal binds, psql
parameterized PREPARE/EXECUTE, fresh pgjdbc probe) all measured 0.3-1.6s.

## Root cause (proven, not conjectured)

A minimal pgjdbc probe replaying the harness sequence (shape1 x23, shape2 x23, shape3 x23 on one
connection, fresh PreparedStatement objects per execution, same SQL text) reproduced it exactly:
shape-3 iterations 0-8 ran 0.25-1.1s, and iterations 9-22 ALL ran 55.6-58.6s. Iteration 9 is the
10th execution of that SQL text on the connection: pgjdbc's connection-level query cache promotes
the repeated SQL text to a server-side NAMED prepared statement after prepareThreshold=5 executions
(fresh PreparedStatement objects do NOT prevent this — the cache is keyed by SQL text), and
PostgreSQL's plancache then switches the named statement to a GENERIC plan after 5 further custom
executions once the generic plan's estimated cost undercuts the average custom cost.

The E1 statistics bump is what made the generic plan look cheap: with wpath's statistics target at
10000, the average-wpath selectivity used for an UNKNOWN $2 wpath parameter drops far enough that
the generic plan is costed at ~166 total (rows=1) — and that generic plan probes sf_text by
wpath=$2 ONLY and applies ILIKE as an executor filter, walking ALL ~1.4M rows at the bound wpath
(1,393,722 buffer reads/execution, 'Rows Removed by Filter: 1,396,684'). auto_explain capture of a
real 55.6s execution (generic plan, parameter values logged) below.

## Why this matters beyond the bench

r2dbc-postgresql (the production driver in this design) also caches server-side prepared
statements, so the SAME flip can occur in production for any translator-generated statement that
binds wpath/pattern as parameters and is executed >~10 times per connection — i.e. every hot search
query. The translator must either inline wpath (it is not user data in the dangerous sense — it is
a normalized path — but inlining still needs escaping discipline), or set
plan_cache_mode=force_custom_plan on search sessions/statements. Recorded as a Task 0.3 design
input. From E1's authoritative re-measure onward, this harness pins custom plans via pgjdbc
prepareThreshold=0 (documented protocol deviation from Task 0.2: 0.2's numbers carried the same
latent hazard, but pre-bump statistics kept the generic plan unattractive, so no 0.2/E0 number is
invalidated — E0's shapes were re-checked to be flip-free by their latency shapes and matching 0.2
numbers).

## auto_explain capture of one 55.6s generic-plan execution

```
2026-07-04 11:03:07.194 UTC [1848] LOG:  duration: 55637.027 ms  plan:
	Query Text: SELECT st.thing_id FROM search_things st
	WHERE st.global_read && $1
	  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = $2 AND s.val_text COLLATE "C.utf8" ILIKE $3 ESCAPE '\')
	  AND (NOT COALESCE( (st.policy_auth #> $4) ?| $5, false)
	AND ( COALESCE( (st.policy_auth #> $6) ?| $7, false)
	      OR ( NOT COALESCE( (st.policy_auth #> $8) ?| $9, false)
	           AND ( COALESCE( (st.policy_auth #> $10) ?| $11, false)
	                 OR ( NOT COALESCE( (st.policy_auth #> $12) ?| $13, false)
	                      AND COALESCE( (st.policy_auth #> $14) ?| $15, false) ) ) ) ))
	Query Parameters: $1 = '{user:sub-063,user:sub-104}', $2 = '/features/*/properties/prop0', $3 = '%246%', $4 = '{attributes,location,·r}', $5 = '{user:sub-063,user:sub-104}', $6 = '{attributes,location,·g}', $7 = '{user:sub-063,user:sub-104}', $8 = '{attributes,·r}', $9 = '{user:sub-063,user:sub-104}', $10 = '{attributes,·g}', $11 = '{user:sub-063,user:sub-104}', $12 = '{·r}', $13 = '{user:sub-063,user:sub-104}', $14 = '{·g}', $15 = '{user:sub-063,user:sub-104}'
	Nested Loop  (cost=157.94..166.00 rows=1 width=41) (actual rows=2 loops=1)
	  Buffers: shared hit=30286 read=1393722
	  ->  HashAggregate  (cost=157.39..157.40 rows=1 width=41) (actual rows=4220 loops=1)
	        Group Key: s.thing_id
	        Batches: 1  Memory Usage: 745kB
	        Buffers: shared hit=17175 read=1385733
	        ->  Index Scan using sf_text on search_flat s  (cost=0.56..157.39 rows=1 width=41) (actual rows=4234 loops=1)
	              Index Cond: (wpath = ($2)::text)
	              Filter: ((val_text)::text ~~* like_escape(($3)::text, '\'::text))
	              Rows Removed by Filter: 1396684
	              Buffers: shared hit=17175 read=1385733
	  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=41) (actual rows=0 loops=4220)
	        Index Cond: (thing_id = s.thing_id)
	        Filter: ((global_read && $1) AND (NOT COALESCE(((policy_auth #> $4) ?| $5), false)) AND (COALESCE(((policy_auth #> $6) ?| $7), false) OR ((NOT COALESCE(((policy_auth #> $8) ?| $9), false)) AND (COALESCE(((policy_auth #> $10) ?| $11), false) OR ((NOT COALESCE(((policy_auth #> $12) ?| $13), false)) AND COALESCE(((policy_auth #> $14) ?| $15), false))))))
	        Rows Removed by Filter: 1
	        Buffers: shared hit=13111 read=7989
2026-07-04 11:04:03.670 UTC [1848] LOG:  duration: 56474.018 ms  plan:
	Query Text: SELECT st.thing_id FROM search_things st
	WHERE st.global_read && $1
	  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = $2 AND s.val_text COLLATE "C.utf8" ILIKE $3 ESCAPE '\')
```
