# Read-only check: the multi-valued branch's estimate decides the main plan of shape 1 (2026-10-07)

Run on the corpus of this folder after the ABBA series, with `psql` in `ditto-search-bench-pc`. Not harness output:
a supplementary check, as `../sort-path-bound/psql-checks.md`.

**Statement.** The shape-1 find exactly as `B2-range-legs/shape-1-eq-and-range.md` renders it
for iteration 0 (subject pair `user:sub-000`/`user:sub-041`, `LIMIT 26 OFFSET 0`), with the binds inlined as literals
(the bench pool plans with `force_custom_plan`, i.e. with the values), leader-only (`SET max_parallel_workers = 0`),
`EXPLAIN (ANALYZE, BUFFERS)` twice per variant; the second (warm) plan is shown.

- **As shipped:** the range leg with its multi-valued branch (`… UNION ALL … sfc.ord = 1`), in `sel_pick` and in
  `sel_leg`.
- **Without branch: the same statement without the two multi-valued branches.** It is **not** a valid leg (it loses
  things whose array elements straddle the range, see the `SelectiveLegCte` Javadoc); it only isolates what the
  branch's estimate does to the plan.

| | 1st execution | 2nd execution | `sel_leg` estimate (actual) | how the city conjunct runs |
|---|---:|---:|---:|---|
| as shipped | 14,151 ms ¹ | **747 ms** | 33,452 (13,869) | `Merge Semi Join` with a `Bitmap Heap Scan` of all 89,901 Stuttgart rows |
| without branch | 148 ms | **128 ms** | 1,657 (13,869) | `Nested Loop Semi Join`: per-thing probes |

¹ The first as-shipped execution ran right after the Docker VM had stopped answering for some hours under host swap; the city
scan was read cold.

In both, the multi-valued branch on `/features/env/properties/temperature` is estimated at 31,795 rows (the planner's
`ord = 1` fraction × the path's rows) and returns 0: the temperature is never an array in this corpus. That estimate
alone raises `sel_leg` from 1,657 to 33,452 rows and turns the per-thing probes into the whole-city scan.

## As shipped, second execution

```
 Limit  (cost=16533.50..16912.45 rows=1 width=90) (actual time=746.355..746.360 rows=0 loops=1)
   Buffers: shared hit=50825 read=124311, temp read=453 written=500
   CTE sel_pick
     ->  Subquery Scan on pc  (cost=72.31..337.17 rows=1 width=1) (actual time=6.947..6.948 rows=1 loops=1)
           Buffers: shared hit=3933 read=207
           ->  Aggregate  (cost=72.31..72.32 rows=1 width=8) (actual time=4.042..4.043 rows=1 loops=1)
                 Buffers: shared hit=3 read=68
                 ->  Limit  (cost=0.56..57.02 rows=1223 width=4) (actual time=0.028..3.108 rows=50000 loops=1)
                       Buffers: shared hit=3 read=68
                       ->  Index Only Scan using sf_text on search_flat sfc_2  (cost=0.56..57.02 rows=1223 width=4) (actual time=0.028..1.654 rows=50000 loops=1)
                             Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                             Heap Fetches: 0
                             Buffers: shared hit=3 read=68
           SubPlan 1
             ->  Aggregate  (cost=264.84..264.85 rows=1 width=8) (actual time=2.902..2.903 rows=1 loops=1)
                   Buffers: shared hit=3930 read=139
                   ->  Limit  (cost=0.56..224.56 rows=3223 width=4) (actual time=0.031..2.645 rows=13869 loops=1)
                         Buffers: shared hit=3930 read=139
                         ->  Append  (cost=0.56..2240.44 rows=32229 width=4) (actual time=0.031..2.268 rows=13869 loops=1)
                               Buffers: shared hit=3930 read=139
                               ->  Index Only Scan using sf_num on search_flat sfc  (cost=0.56..30.33 rows=434 width=4) (actual time=0.031..1.859 rows=13869 loops=1)
                                     Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                                     Heap Fetches: 0
                                     Buffers: shared hit=3930 read=135
                               ->  Index Only Scan using sf_multi on search_flat sfc_1  (cost=0.56..2048.97 rows=31795 width=4) (actual time=0.017..0.017 rows=0 loops=1)
                                     Index Cond: (wpath = '/features/env/properties/temperature'::text)
                                     Heap Fetches: 0
                                     Buffers: shared read=4
   CTE sel_leg
     ->  Append  (cost=41.12..8798.49 rows=33452 width=41) (actual time=9.420..68.201 rows=13869 loops=1)
           Buffers: shared hit=4100 read=13972
           ->  Result  (cost=41.12..4840.08 rows=1223 width=41) (actual time=6.948..6.949 rows=0 loops=1)
                 One-Time Filter: $2
                 Buffers: shared hit=3933 read=207
                 InitPlan 3 (returns $2)
                   ->  CTE Scan on sel_pick sp  (cost=0.00..0.02 rows=1 width=1) (actual time=6.947..6.947 rows=1 loops=1)
                         Buffers: shared hit=3933 read=207
                 ->  Bitmap Heap Scan on search_flat sfc_3  (cost=41.12..4840.08 rows=1223 width=41) (never executed)
                       Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                       ->  Bitmap Index Scan on sf_text  (cost=0.00..40.79 rows=1223 width=0) (never executed)
                             Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
           ->  Result  (cost=22.12..1742.17 rows=434 width=41) (actual time=2.472..60.724 rows=13869 loops=1)
                 One-Time Filter: (NOT $3)
                 Buffers: shared hit=163 read=13765
                 InitPlan 4 (returns $3)
                   ->  CTE Scan on sel_pick sp_1  (cost=0.00..0.02 rows=1 width=1) (actual time=0.000..0.000 rows=1 loops=1)
                 ->  Bitmap Heap Scan on search_flat sfc_4  (cost=22.12..1742.17 rows=434 width=41) (actual time=2.471..59.786 rows=13869 loops=1)
                       Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                       Heap Blocks: exact=13810
                       Buffers: shared hit=163 read=13765
                       ->  Bitmap Index Scan on sf_num  (cost=0.00..21.99 rows=434 width=0) (actual time=1.362..1.362 rows=13869 loops=1)
                             Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                             Buffers: shared hit=118
           ->  Result  (cost=0.58..2048.99 rows=31795 width=41) (actual time=0.010..0.010 rows=0 loops=1)
                 One-Time Filter: (NOT $4)
                 Buffers: shared hit=4
                 InitPlan 5 (returns $4)
                   ->  CTE Scan on sel_pick sp_2  (cost=0.00..0.02 rows=1 width=1) (actual time=0.001..0.001 rows=1 loops=1)
                 ->  Index Only Scan using sf_multi on search_flat sfc_5  (cost=0.58..2048.99 rows=31795 width=41) (actual time=0.008..0.008 rows=0 loops=1)
                       Index Cond: (wpath = '/features/env/properties/temperature'::text)
                       Heap Fetches: 0
                       Buffers: shared hit=4
   ->  Nested Loop Semi Join  (cost=7397.83..7776.78 rows=1 width=90) (actual time=746.354..746.355 rows=0 loops=1)
         Join Filter: (sl.thing_id = sf_2.thing_id)
         Buffers: shared hit=50825 read=124311, temp read=453 written=500
         ->  Nested Loop Semi Join  (cost=7397.14..7507.15 rows=1 width=163) (actual time=746.354..746.355 rows=0 loops=1)
               Buffers: shared hit=50825 read=124311, temp read=453 written=500
               ->  Merge Semi Join  (cost=7396.45..7402.75 rows=1 width=122) (actual time=746.354..746.354 rows=0 loops=1)
                     Merge Cond: (sl.thing_id = sf.thing_id)
                     Buffers: shared hit=50825 read=124311, temp read=453 written=500
                     ->  Sort  (cost=2493.67..2493.76 rows=36 width=81) (actual time=182.403..182.404 rows=5 loops=1)
                           Sort Key: st.thing_id COLLATE "C"
                           Sort Method: quicksort  Memory: 25kB
                           Buffers: shared hit=50824 read=36593
                           ->  Nested Loop  (cost=753.22..2492.74 rows=36 width=81) (actual time=80.452..182.391 rows=5 loops=1)
                                 Buffers: shared hit=50824 read=36593
                                 ->  HashAggregate  (cost=752.67..754.67 rows=200 width=32) (actual time=72.701..74.584 rows=13869 loops=1)
                                       Group Key: sl.thing_id
                                       Batches: 1  Memory Usage: 1825kB
                                       Buffers: shared hit=4100 read=13972
                                       ->  CTE Scan on sel_leg sl  (cost=0.00..669.04 rows=33452 width=32) (actual time=9.421..69.672 rows=13869 loops=1)
                                             Buffers: shared hit=4100 read=13972
                                 ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.69 rows=1 width=49) (actual time=0.008..0.008 rows=0 loops=13869)
                                       Index Cond: (thing_id = sl.thing_id)
                                       Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,city,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,city,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
                                       Rows Removed by Filter: 1
                                       Buffers: shared hit=46724 read=22621
                     ->  Sort  (cost=4902.78..4905.83 rows=1223 width=41) (actual time=560.932..562.740 rows=61454 loops=1)
                           Sort Key: sf.thing_id COLLATE "C"
                           Sort Method: external merge  Disk: 3992kB
                           Buffers: shared hit=1 read=87718, temp read=453 written=500
                           ->  Bitmap Heap Scan on search_flat sf  (cost=41.10..4840.06 rows=1223 width=41) (actual time=9.945..534.480 rows=89901 loops=1)
                                 Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                 Rows Removed by Index Recheck: 1933925
                                 Heap Blocks: exact=50982 lossy=36654
                                 Buffers: shared hit=1 read=87718
                                 ->  Bitmap Index Scan on sf_text  (cost=0.00..40.79 rows=1223 width=0) (actual time=5.440..5.440 rows=89901 loops=1)
                                       Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                                       Buffers: shared hit=1 read=82
               ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..270.89 rows=5 width=41) (never executed)
                     Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                     Filter: (val_num >= '-15.0'::numeric)
         ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..269.62 rows=1 width=41) (never executed)
               Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
               Filter: (val_num <= '-14.01'::numeric)
 Planning:
   Buffers: shared hit=49 read=21
 Planning Time: 0.961 ms
 Execution Time: 746.947 ms
(109 rows)
```

## Without branch, second execution

```
 Limit  (cost=9100.76..9100.77 rows=1 width=90) (actual time=128.034..128.037 rows=0 loops=1)
   Buffers: shared hit=51320 read=36154
   CTE sel_pick
     ->  Subquery Scan on pc  (cost=72.31..76.38 rows=1 width=1) (actual time=6.228..6.229 rows=1 loops=1)
           Buffers: shared hit=3971 read=165
           ->  Aggregate  (cost=72.31..72.32 rows=1 width=8) (actual time=3.928..3.928 rows=1 loops=1)
                 Buffers: shared hit=24 read=47
                 ->  Limit  (cost=0.56..57.02 rows=1223 width=4) (actual time=0.017..2.998 rows=50000 loops=1)
                       Buffers: shared hit=24 read=47
                       ->  Index Only Scan using sf_text on search_flat sfc_1  (cost=0.56..57.02 rows=1223 width=4) (actual time=0.016..1.565 rows=50000 loops=1)
                             Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                             Heap Fetches: 0
                             Buffers: shared hit=24 read=47
           SubPlan 1
             ->  Aggregate  (cost=4.05..4.06 rows=1 width=8) (actual time=2.299..2.300 rows=1 loops=1)
                   Buffers: shared hit=3947 read=118
                   ->  Limit  (cost=0.56..3.51 rows=43 width=4) (actual time=0.020..2.037 rows=13869 loops=1)
                         Buffers: shared hit=3947 read=118
                         ->  Index Only Scan using sf_num on search_flat sfc  (cost=0.56..30.33 rows=434 width=4) (actual time=0.020..1.638 rows=13869 loops=1)
                               Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                               Heap Fetches: 0
                               Buffers: shared hit=3947 read=118
   CTE sel_leg
     ->  Append  (cost=41.12..6590.53 rows=1657 width=41) (actual time=8.780..50.433 rows=13869 loops=1)
           Buffers: shared hit=4092 read=13972
           ->  Result  (cost=41.12..4840.08 rows=1223 width=41) (actual time=6.230..6.230 rows=0 loops=1)
                 One-Time Filter: $2
                 Buffers: shared hit=3971 read=165
                 InitPlan 3 (returns $2)
                   ->  CTE Scan on sel_pick sp  (cost=0.00..0.02 rows=1 width=1) (actual time=6.229..6.229 rows=1 loops=1)
                         Buffers: shared hit=3971 read=165
                 ->  Bitmap Heap Scan on search_flat sfc_2  (cost=41.12..4840.08 rows=1223 width=41) (never executed)
                       Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
                       ->  Bitmap Index Scan on sf_text  (cost=0.00..40.79 rows=1223 width=0) (never executed)
                             Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
           ->  Result  (cost=22.12..1742.17 rows=434 width=41) (actual time=2.550..43.809 rows=13869 loops=1)
                 One-Time Filter: (NOT $3)
                 Buffers: shared hit=121 read=13807
                 InitPlan 4 (returns $3)
                   ->  CTE Scan on sel_pick sp_1  (cost=0.00..0.02 rows=1 width=1) (actual time=0.000..0.000 rows=1 loops=1)
                 ->  Bitmap Heap Scan on search_flat sfc_3  (cost=22.12..1742.17 rows=434 width=41) (actual time=2.549..43.347 rows=13869 loops=1)
                       Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                       Heap Blocks: exact=13810
                       Buffers: shared hit=121 read=13807
                       ->  Bitmap Index Scan on sf_num  (cost=0.00..21.99 rows=434 width=0) (actual time=1.463..1.463 rows=13869 loops=1)
                             Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
                             Buffers: shared hit=118
   ->  Sort  (cost=2433.85..2433.86 rows=1 width=90) (actual time=128.033..128.034 rows=0 loops=1)
         Sort Key: st.thing_id COLLATE "C"
         Sort Method: quicksort  Memory: 25kB
         Buffers: shared hit=51320 read=36154
         ->  Nested Loop Semi Join  (cost=39.91..2433.84 rows=1 width=90) (actual time=128.027..128.028 rows=0 loops=1)
               Join Filter: (sl.thing_id = sf_2.thing_id)
               Buffers: shared hit=51320 read=36154
               ->  Nested Loop Semi Join  (cost=39.22..2164.21 rows=1 width=163) (actual time=128.027..128.028 rows=0 loops=1)
                     Join Filter: (sl.thing_id = sf.thing_id)
                     Buffers: shared hit=51320 read=36154
                     ->  Nested Loop Semi Join  (cost=38.52..1897.25 rows=1 width=122) (actual time=57.502..128.003 rows=5 loops=1)
                           Buffers: shared hit=51290 read=36151
                           ->  Nested Loop  (cost=37.83..1785.42 rows=2 width=81) (actual time=57.479..127.907 rows=5 loops=1)
                                 Buffers: shared hit=51279 read=36130
                                 ->  HashAggregate  (cost=37.28..39.28 rows=200 width=32) (actual time=53.293..54.545 rows=13869 loops=1)
                                       Group Key: sl.thing_id
                                       Batches: 1  Memory Usage: 1825kB
                                       Buffers: shared hit=4092 read=13972
                                       ->  CTE Scan on sel_leg sl  (cost=0.00..33.14 rows=1657 width=32) (actual time=8.781..51.275 rows=13869 loops=1)
                                             Buffers: shared hit=4092 read=13972
                                 ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.69 rows=1 width=49) (actual time=0.005..0.005 rows=0 loops=13869)
                                       Index Cond: (thing_id = sl.thing_id)
                                       Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,city,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (NOT COALESCE(((policy_auth #> '{features,env,properties,temperature,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,city,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))) AND (COALESCE(((policy_auth #> '{features,env,properties,temperature,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,properties,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,properties,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,env,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,env,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{features,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{features,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
                                       Rows Removed by Filter: 1
                                       Buffers: shared hit=47187 read=22158
                           ->  Index Scan using search_flat_pkey on search_flat sf_1  (cost=0.69..288.42 rows=5 width=41) (actual time=0.018..0.018 rows=1 loops=5)
                                 Index Cond: ((thing_id = sl.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                                 Filter: (val_num >= '-15.0'::numeric)
                                 Buffers: shared hit=11 read=21
                     ->  Index Scan using search_flat_pkey on search_flat sf  (cost=0.69..266.95 rows=1 width=41) (actual time=0.004..0.004 rows=0 loops=5)
                           Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/attributes/location/city'::text))
                           Filter: (val_text = 'Stuttgart'::text)
                           Rows Removed by Filter: 1
                           Buffers: shared hit=30 read=3
               ->  Index Scan using search_flat_pkey on search_flat sf_2  (cost=0.69..269.62 rows=1 width=41) (never executed)
                     Index Cond: ((thing_id = sf_1.thing_id) AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
                     Filter: (val_num <= '-14.01'::numeric)
 Planning:
   Buffers: shared hit=59 read=11
 Planning Time: 0.741 ms
 Execution Time: 128.494 ms
(88 rows)
```
