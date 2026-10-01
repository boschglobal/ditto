# EXPLAIN — with

Database `jdbc:postgresql://localhost:55433/scratch`, 2026-10-01T03:37:34.627717Z.

Managed indexes: `[sf_path_trgm_ca5f5cd692cb58e9:true]`

## 3-high iteration 0: `ilike(features/*/properties/prop0,"*234*")`

```
Limit  (cost=76547.61..76547.61 rows=1 width=90) (actual time=88.154..88.156 rows=1 loops=1)
  Buffers: shared hit=3 read=31401
  ->  Sort  (cost=76547.61..76547.61 rows=1 width=90) (actual time=88.153..88.154 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=31401
        ->  Hash Semi Join  (cost=646.00..76547.60 rows=1 width=90) (actual time=34.026..88.150 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
              Rows Removed by Join Filter: 114
              Buffers: shared hit=3 read=31401
              ->  Bitmap Heap Scan on search_things st  (cost=216.52..76038.84 rows=30131 width=213) (actual time=4.677..71.338 rows=29411 loops=1)
                    Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Heap Blocks: exact=27130
                    Buffers: shared hit=2 read=27143
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..208.99 rows=30131 width=0) (actual time=2.240..2.241 rows=29411 loops=1)
                          Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=428.22..428.22 rows=100 width=49) (actual time=14.354..14.355 rows=4260 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 406kB
                    Buffers: shared hit=1 read=4258
                    ->  Bitmap Heap Scan on search_flat a  (cost=29.33..428.22 rows=100 width=49) (actual time=0.534..14.025 rows=4260 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Heap Blocks: exact=4253
                          Buffers: shared hit=1 read=4258
                          ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..29.30 rows=100 width=0) (actual time=0.250..0.250 rows=4260 loops=1)
                                Index Cond: ((val_text)::text ~~* '%234%'::text)
                                Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.330 ms
Execution Time: 88.178 ms

```

## 3-high iteration 1: `ilike(features/*/properties/prop0,"*246*")`

```
Limit  (cost=74176.22..74176.23 rows=1 width=90) (actual time=94.526..94.528 rows=1 loops=1)
  Buffers: shared hit=3 read=31707
  ->  Sort  (cost=74176.22..74176.23 rows=1 width=90) (actual time=94.526..94.527 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=31707
        ->  Hash Semi Join  (cost=639.10..74176.21 rows=1 width=90) (actual time=39.946..94.522 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false))))))))))
              Rows Removed by Join Filter: 112
              Buffers: shared hit=3 read=31707
              ->  Bitmap Heap Scan on search_things st  (cost=209.63..73670.91 rows=28817 width=213) (actual time=4.704..76.729 rows=29789 loops=1)
                    Recheck Cond: (global_read && '{user:sub-007,user:sub-048}'::text[])
                    Heap Blocks: exact=27464
                    Buffers: shared hit=2 read=27477
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..202.42 rows=28817 width=0) (actual time=2.203..2.203 rows=29789 loops=1)
                          Index Cond: (global_read && '{user:sub-007,user:sub-048}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=428.22..428.22 rows=100 width=49) (actual time=14.949..14.949 rows=4234 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 404kB
                    Buffers: shared hit=1 read=4230
                    ->  Bitmap Heap Scan on search_flat a  (cost=29.33..428.22 rows=100 width=49) (actual time=0.568..14.600 rows=4234 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%246%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Heap Blocks: exact=4225
                          Buffers: shared hit=1 read=4230
                          ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..29.30 rows=100 width=0) (actual time=0.259..0.259 rows=4234 loops=1)
                                Index Cond: ((val_text)::text ~~* '%246%'::text)
                                Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.312 ms
Execution Time: 94.553 ms

```

## 3-high iteration 2: `ilike(features/*/properties/prop0,"*456*")`

```
Limit  (cost=112441.72..112444.76 rows=26 width=90) (actual time=103.471..104.023 rows=1 loops=1)
  Buffers: shared hit=3 read=31410
  ->  Gather Merge  (cost=112441.72..112451.06 rows=80 width=90) (actual time=96.022..96.574 rows=1 loops=1)
        Workers Planned: 2
        Workers Launched: 0
        Buffers: shared hit=3 read=31410
        ->  Sort  (cost=111441.70..111441.80 rows=40 width=90) (actual time=95.696..95.697 rows=1 loops=1)
              Sort Key: st.thing_id COLLATE "C"
              Sort Method: quicksort  Memory: 25kB
              Buffers: shared hit=3 read=31410
              ->  Parallel Hash Semi Join  (cost=37945.41..111440.63 rows=40 width=90) (actual time=50.096..95.692 rows=1 loops=1)
                    Hash Cond: (st.thing_id = a.thing_id)
                    Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false))))))))))
                    Rows Removed by Join Filter: 145
                    Buffers: shared hit=3 read=31410
                    ->  Parallel Bitmap Heap Scan on search_things st  (cost=209.62..73460.78 rows=12007 width=213) (actual time=7.872..78.105 rows=29469 loops=1)
                          Recheck Cond: (global_read && '{user:sub-014,user:sub-055}'::text[])
                          Heap Blocks: exact=27198
                          Buffers: shared hit=2 read=27211
                          ->  Bitmap Index Scan on st_global_read  (cost=0.00..202.42 rows=28817 width=0) (actual time=5.358..5.358 rows=29469 loops=1)
                                Index Cond: (global_read && '{user:sub-014,user:sub-055}'::text[])
                                Buffers: shared hit=2 read=13
                    ->  Parallel Hash  (cost=37683.25..37683.25 rows=4203 width=49) (actual time=14.790..14.790 rows=4199 loops=1)
                          Buckets: 16384  Batches: 1  Memory Usage: 512kB
                          Buffers: shared hit=1 read=4199
                          ->  Parallel Bitmap Heap Scan on search_flat a  (cost=43.96..37683.25 rows=4203 width=49) (actual time=0.999..14.183 rows=4199 loops=1)
                                Recheck Cond: (((val_text)::text ~~* '%456%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                                Heap Blocks: exact=4194
                                Buffers: shared hit=1 read=4199
                                ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..41.44 rows=10088 width=0) (actual time=0.666..0.666 rows=4199 loops=1)
                                      Index Cond: ((val_text)::text ~~* '%456%'::text)
                                      Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.331 ms
JIT:
  Functions: 20
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.487 ms, Inlining 0.000 ms, Optimization 0.292 ms, Emission 7.185 ms, Total 7.965 ms
Execution Time: 104.614 ms

```

## 3-high iteration 3: `ilike(features/*/properties/prop0,"*567*")`

```
Limit  (cost=74593.89..74593.90 rows=1 width=90) (actual time=93.375..93.376 rows=0 loops=1)
  Buffers: shared hit=3 read=31363
  ->  Sort  (cost=74593.89..74593.90 rows=1 width=90) (actual time=93.374..93.375 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=31363
        ->  Hash Semi Join  (cost=640.30..74593.88 rows=1 width=90) (actual time=93.371..93.372 rows=0 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false))))))))))
              Rows Removed by Join Filter: 141
              Buffers: shared hit=3 read=31363
              ->  Bitmap Heap Scan on search_things st  (cost=210.83..74087.98 rows=29046 width=213) (actual time=4.501..75.900 rows=29475 loops=1)
                    Recheck Cond: (global_read && '{user:sub-021,user:sub-062}'::text[])
                    Heap Blocks: exact=27091
                    Buffers: shared hit=2 read=27104
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..203.57 rows=29046 width=0) (actual time=2.214..2.214 rows=29475 loops=1)
                          Index Cond: (global_read && '{user:sub-021,user:sub-062}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=428.22..428.22 rows=100 width=49) (actual time=14.582..14.582 rows=4264 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 406kB
                    Buffers: shared hit=1 read=4259
                    ->  Bitmap Heap Scan on search_flat a  (cost=29.33..428.22 rows=100 width=49) (actual time=0.571..14.258 rows=4264 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%567%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Heap Blocks: exact=4254
                          Buffers: shared hit=1 read=4259
                          ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..29.30 rows=100 width=0) (actual time=0.261..0.261 rows=4264 loops=1)
                                Index Cond: ((val_text)::text ~~* '%567%'::text)
                                Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.326 ms
Execution Time: 93.401 ms

```

## 3-high iteration 4: `ilike(features/*/properties/prop0,"*789*")`

```
Limit  (cost=76075.79..76075.79 rows=1 width=90) (actual time=88.682..88.684 rows=1 loops=1)
  Buffers: shared hit=3 read=31238
  ->  Sort  (cost=76075.79..76075.79 rows=1 width=90) (actual time=88.682..88.683 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=31238
        ->  Hash Semi Join  (cost=644.61..76075.78 rows=1 width=90) (actual time=63.701..88.678 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false))))))))))
              Rows Removed by Join Filter: 124
              Buffers: shared hit=3 read=31238
              ->  Bitmap Heap Scan on search_things st  (cost=215.14..75567.72 rows=29867 width=213) (actual time=4.454..72.301 rows=29363 loops=1)
                    Recheck Cond: (global_read && '{user:sub-028,user:sub-069}'::text[])
                    Heap Blocks: exact=27050
                    Buffers: shared hit=2 read=27063
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..207.67 rows=29867 width=0) (actual time=2.126..2.126 rows=29363 loops=1)
                          Index Cond: (global_read && '{user:sub-028,user:sub-069}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=428.22..428.22 rows=100 width=49) (actual time=13.887..13.888 rows=4181 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 400kB
                    Buffers: shared hit=1 read=4175
                    ->  Bitmap Heap Scan on search_flat a  (cost=29.33..428.22 rows=100 width=49) (actual time=0.564..13.576 rows=4181 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%789%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Heap Blocks: exact=4170
                          Buffers: shared hit=1 read=4175
                          ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..29.30 rows=100 width=0) (actual time=0.268..0.268 rows=4181 loops=1)
                                Index Cond: ((val_text)::text ~~* '%789%'::text)
                                Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.338 ms
Execution Time: 88.708 ms

```

## 3-high iteration 5: `ilike(features/*/properties/prop0,"*321*")`

```
Limit  (cost=74774.05..74774.05 rows=1 width=90) (actual time=90.533..90.534 rows=0 loops=1)
  Buffers: shared hit=3 read=31616
  ->  Sort  (cost=74774.05..74774.05 rows=1 width=90) (actual time=90.532..90.533 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=31616
        ->  Hash Semi Join  (cost=640.82..74774.04 rows=1 width=90) (actual time=90.529..90.530 rows=0 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false))))))))))
              Rows Removed by Join Filter: 110
              Buffers: shared hit=3 read=31616
              ->  Bitmap Heap Scan on search_things st  (cost=211.35..74267.87 rows=29145 width=213) (actual time=4.692..73.668 rows=29720 loops=1)
                    Recheck Cond: (global_read && '{user:sub-035,user:sub-076}'::text[])
                    Heap Blocks: exact=27491
                    Buffers: shared hit=2 read=27504
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..204.06 rows=29145 width=0) (actual time=2.365..2.365 rows=29720 loops=1)
                          Index Cond: (global_read && '{user:sub-035,user:sub-076}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=428.22..428.22 rows=100 width=49) (actual time=14.383..14.384 rows=4113 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 394kB
                    Buffers: shared hit=1 read=4112
                    ->  Bitmap Heap Scan on search_flat a  (cost=29.33..428.22 rows=100 width=49) (actual time=0.547..14.054 rows=4113 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%321%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Heap Blocks: exact=4107
                          Buffers: shared hit=1 read=4112
                          ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..29.30 rows=100 width=0) (actual time=0.253..0.253 rows=4113 loops=1)
                                Index Cond: ((val_text)::text ~~* '%321%'::text)
                                Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.330 ms
Execution Time: 90.559 ms

```

## 3-high iteration 6: `ilike(features/*/properties/prop0,"*135*")`

```
Limit  (cost=74056.33..74056.33 rows=1 width=90) (actual time=93.128..93.129 rows=0 loops=1)
  Buffers: shared hit=3 read=31473
  ->  Sort  (cost=74056.33..74056.33 rows=1 width=90) (actual time=93.127..93.128 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=31473
        ->  Hash Semi Join  (cost=638.75..74056.32 rows=1 width=90) (actual time=93.123..93.125 rows=0 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false))))))))))
              Rows Removed by Join Filter: 135
              Buffers: shared hit=3 read=31473
              ->  Bitmap Heap Scan on search_things st  (cost=209.28..73551.19 rows=28751 width=213) (actual time=4.092..75.970 rows=29556 loops=1)
                    Recheck Cond: (global_read && '{user:sub-042,user:sub-083}'::text[])
                    Heap Blocks: exact=27293
                    Buffers: shared hit=2 read=27306
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..202.09 rows=28751 width=0) (actual time=2.000..2.000 rows=29556 loops=1)
                          Index Cond: (global_read && '{user:sub-042,user:sub-083}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=428.22..428.22 rows=100 width=49) (actual time=14.239..14.239 rows=4164 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 398kB
                    Buffers: shared hit=1 read=4167
                    ->  Bitmap Heap Scan on search_flat a  (cost=29.33..428.22 rows=100 width=49) (actual time=0.544..13.914 rows=4164 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%135%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Heap Blocks: exact=4162
                          Buffers: shared hit=1 read=4167
                          ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..29.30 rows=100 width=0) (actual time=0.252..0.252 rows=4164 loops=1)
                                Index Cond: ((val_text)::text ~~* '%135%'::text)
                                Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.327 ms
Execution Time: 93.156 ms

```

## 3-high iteration 7: `ilike(features/*/properties/prop0,"*890*")`

```
Limit  (cost=114635.28..114638.31 rows=26 width=90) (actual time=111.271..111.600 rows=1 loops=1)
  Buffers: shared hit=3 read=31849
  ->  Gather Merge  (cost=114635.28..114645.08 rows=84 width=90) (actual time=103.159..103.487 rows=1 loops=1)
        Workers Planned: 2
        Workers Launched: 0
        Buffers: shared hit=3 read=31849
        ->  Sort  (cost=113635.26..113635.36 rows=42 width=90) (actual time=102.936..102.938 rows=1 loops=1)
              Sort Key: st.thing_id COLLATE "C"
              Sort Method: quicksort  Memory: 25kB
              Buffers: shared hit=3 read=31849
              ->  Parallel Hash Semi Join  (cost=37951.79..113634.12 rows=42 width=90) (actual time=25.469..102.932 rows=1 loops=1)
                    Hash Cond: (st.thing_id = a.thing_id)
                    Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false))))))))))
                    Rows Removed by Join Filter: 109
                    Buffers: shared hit=3 read=31849
                    ->  Parallel Bitmap Heap Scan on search_things st  (cost=216.00..75642.32 rows=12513 width=213) (actual time=5.252..83.317 rows=30018 loops=1)
                          Recheck Cond: (global_read && '{user:sub-049,user:sub-090}'::text[])
                          Heap Blocks: exact=27620
                          Buffers: shared hit=2 read=27633
                          ->  Bitmap Index Scan on st_global_read  (cost=0.00..208.49 rows=30031 width=0) (actual time=2.779..2.780 rows=30018 loops=1)
                                Index Cond: (global_read && '{user:sub-049,user:sub-090}'::text[])
                                Buffers: shared hit=2 read=13
                    ->  Parallel Hash  (cost=37683.25..37683.25 rows=4203 width=49) (actual time=16.162..16.162 rows=4223 loops=1)
                          Buckets: 16384  Batches: 1  Memory Usage: 512kB
                          Buffers: shared hit=1 read=4216
                          ->  Parallel Bitmap Heap Scan on search_flat a  (cost=43.96..37683.25 rows=4203 width=49) (actual time=1.094..15.520 rows=4223 loops=1)
                                Recheck Cond: (((val_text)::text ~~* '%890%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                                Heap Blocks: exact=4211
                                Buffers: shared hit=1 read=4216
                                ->  Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9  (cost=0.00..41.44 rows=10088 width=0) (actual time=0.741..0.741 rows=4223 loops=1)
                                      Index Cond: ((val_text)::text ~~* '%890%'::text)
                                      Buffers: shared hit=1 read=5
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=23
Planning Time: 0.333 ms
JIT:
  Functions: 20
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.533 ms, Inlining 0.000 ms, Optimization 0.324 ms, Emission 7.815 ms, Total 8.672 ms
Execution Time: 112.184 ms

```

## 3-low iteration 0 (another path): `ilike(attributes/vendor,"*234*")`

```
Limit  (cost=4051.01..4051.01 rows=1 width=90) (actual time=737.369..737.370 rows=0 loops=1)
  Buffers: shared hit=764 read=7642
  ->  Sort  (cost=4051.01..4051.01 rows=1 width=90) (actual time=737.368..737.369 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=764 read=7642
        ->  Nested Loop  (cost=3999.88..4051.00 rows=1 width=90) (actual time=737.366..737.366 rows=0 loops=1)
              Buffers: shared hit=764 read=7642
              ->  HashAggregate  (cost=3999.33..3999.39 rows=6 width=41) (actual time=684.443..684.487 rows=291 loops=1)
                    Group Key: sf.thing_id
                    Batches: 1  Memory Usage: 85kB
                    Buffers: shared hit=1 read=6950
                    ->  Bitmap Heap Scan on search_flat sf  (cost=3975.26..3999.31 rows=6 width=41) (actual time=35.744..684.277 rows=291 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/attributes/vendor'::text))
                          Rows Removed by Index Recheck: 7657
                          Heap Blocks: exact=6667
                          Buffers: shared hit=1 read=6950
                          ->  BitmapAnd  (cost=3975.26..3975.26 rows=6 width=0) (actual time=30.047..30.047 rows=0 loops=1)
                                Buffers: shared hit=1 read=283
                                ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=6.885..6.885 rows=44298 loops=1)
                                      Index Cond: ((val_text)::text ~~* '%234%'::text)
                                      Buffers: shared hit=1 read=16
                                ->  Bitmap Index Scan on sf_exists  (cost=0.00..3841.00 rows=292324 width=0) (actual time=21.783..21.783 rows=305613 loops=1)
                                      Index Cond: (wpath = '/attributes/vendor'::text)
                                      Buffers: shared read=267
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=49) (actual time=0.182..0.182 rows=0 loops=291)
                    Index Cond: (thing_id = sf.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,vendor,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,vendor,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=763 read=692
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.314 ms
Execution Time: 737.396 ms

```

## 3-high `*890*` — generic plan (`EXPLAIN (GENERIC_PLAN)`, what a non-custom plancache entry would run)

```
Limit  (cost=32769.64..32769.65 rows=1 width=90)
  ->  Sort  (cost=32769.64..32769.64 rows=1 width=90)
        Sort Key: st.thing_id COLLATE "C"
        ->  Hash Semi Join  (cost=1082.38..32769.63 rows=1 width=90)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $3, $4, $5]) ?| $6), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $7, $8, $9]) ?| $10), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $11, $12]) ?| $13), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $14, $15]) ?| $16), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $17, $18]) ?| $19), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $20, $21]) ?| $22), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $23, $24]) ?| $25), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $26, $27]) ?| $28), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $29]) ?| $30), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, $31]) ?| $32), false))))))))))
              ->  Bitmap Heap Scan on search_things st  (cost=81.66..31742.66 rows=9998 width=213)
                    Recheck Cond: (global_read && $33)
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..79.16 rows=9998 width=0)
                          Index Cond: (global_read && $33)
              ->  Hash  (cost=999.84..999.84 rows=71 width=49)
                    ->  Bitmap Heap Scan on search_flat a  (cost=716.15..999.84 rows=71 width=49)
                          Recheck Cond: ((wpath = $1) AND ((val_text)::text ~~* like_escape($2, '\'::text)))
                          ->  BitmapAnd  (cost=716.15..716.15 rows=71 width=0)
                                ->  Bitmap Index Scan on sf_exists  (cost=0.00..191.47 rows=14253 width=0)
                                      Index Cond: (wpath = $1)
                                ->  Bitmap Index Scan on sf_trgm  (cost=0.00..524.40 rows=342568 width=0)
                                      Index Cond: ((val_text)::text ~~* like_escape($2, '\'::text))
```
