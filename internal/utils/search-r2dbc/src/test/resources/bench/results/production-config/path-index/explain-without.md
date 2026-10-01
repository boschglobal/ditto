# EXPLAIN — without

Database `jdbc:postgresql://localhost:55433/scratch`, 2026-10-01T04:00:49.645214Z.

Managed indexes: `[null]`

## 3-high iteration 0: `ilike(features/*/properties/prop0,"*234*")`

```
Limit  (cost=82078.26..82078.26 rows=1 width=90) (actual time=248.439..248.441 rows=1 loops=1)
  Buffers: shared hit=3 read=58079
  ->  Sort  (cost=82078.26..82078.26 rows=1 width=90) (actual time=248.438..248.439 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=58079
        ->  Hash Semi Join  (cost=6176.65..82078.25 rows=1 width=90) (actual time=149.198..248.434 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))))))
              Rows Removed by Join Filter: 114
              Buffers: shared hit=3 read=58079
              ->  Bitmap Heap Scan on search_things st  (cost=216.52..76038.84 rows=30131 width=213) (actual time=5.236..122.363 rows=29411 loops=1)
                    Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Heap Blocks: exact=27130
                    Buffers: shared hit=2 read=27143
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..208.99 rows=30131 width=0) (actual time=2.696..2.696 rows=29411 loops=1)
                          Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=5958.88..5958.88 rows=100 width=49) (actual time=118.675..118.675 rows=4260 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 406kB
                    Buffers: shared hit=1 read=30936
                    ->  Bitmap Heap Scan on search_flat a  (cost=134.04..5958.88 rows=100 width=49) (actual time=5.748..118.082 rows=4260 loops=1)
                          Recheck Cond: ((val_text)::text ~~* '%234%'::text)
                          Filter: (wpath = '/features/*/properties/prop0'::text)
                          Rows Removed by Filter: 40038
                          Heap Blocks: exact=30920
                          Buffers: shared hit=1 read=30936
                          ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.802..2.802 rows=44298 loops=1)
                                Index Cond: ((val_text)::text ~~* '%234%'::text)
                                Buffers: shared hit=1 read=16
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.348 ms
Execution Time: 248.467 ms

```

## 3-high iteration 1: `ilike(features/*/properties/prop0,"*246*")`

```
Limit  (cost=79706.87..79706.88 rows=1 width=90) (actual time=192.512..192.514 rows=1 loops=1)
  Buffers: shared hit=3 read=58292
  ->  Sort  (cost=79706.87..79706.88 rows=1 width=90) (actual time=192.511..192.512 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=58292
        ->  Hash Semi Join  (cost=6169.75..79706.86 rows=1 width=90) (actual time=138.195..192.507 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-007,user:sub-048}'::text[]), false))))))))))
              Rows Removed by Join Filter: 112
              Buffers: shared hit=3 read=58292
              ->  Bitmap Heap Scan on search_things st  (cost=209.63..73670.91 rows=28817 width=213) (actual time=4.879..76.741 rows=29789 loops=1)
                    Recheck Cond: (global_read && '{user:sub-007,user:sub-048}'::text[])
                    Heap Blocks: exact=27464
                    Buffers: shared hit=2 read=27477
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..202.42 rows=28817 width=0) (actual time=2.265..2.265 rows=29789 loops=1)
                          Index Cond: (global_read && '{user:sub-007,user:sub-048}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=5958.88..5958.88 rows=100 width=49) (actual time=112.821..112.821 rows=4234 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 404kB
                    Buffers: shared hit=1 read=30815
                    ->  Bitmap Heap Scan on search_flat a  (cost=134.04..5958.88 rows=100 width=49) (actual time=5.508..112.285 rows=4234 loops=1)
                          Recheck Cond: ((val_text)::text ~~* '%246%'::text)
                          Filter: (wpath = '/features/*/properties/prop0'::text)
                          Rows Removed by Filter: 39849
                          Heap Blocks: exact=30799
                          Buffers: shared hit=1 read=30815
                          ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.642..2.642 rows=44083 loops=1)
                                Index Cond: ((val_text)::text ~~* '%246%'::text)
                                Buffers: shared hit=1 read=16
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.342 ms
Execution Time: 192.541 ms

```

## 3-high iteration 2: `ilike(features/*/properties/prop0,"*456*")`

```
Limit  (cost=173550.68..173550.74 rows=26 width=90) (actual time=295.689..295.692 rows=1 loops=1)
  Buffers: shared hit=3 read=59674
  ->  Sort  (cost=173550.68..173550.92 rows=97 width=90) (actual time=288.084..288.086 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=59674
        ->  Hash Semi Join  (cost=98782.01..173547.91 rows=97 width=90) (actual time=243.403..288.077 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-014,user:sub-055}'::text[]), false))))))))))
              Rows Removed by Join Filter: 145
              Buffers: shared hit=3 read=59674
              ->  Bitmap Heap Scan on search_things st  (cost=209.62..73670.91 rows=28817 width=213) (actual time=4.672..73.170 rows=29469 loops=1)
                    Recheck Cond: (global_read && '{user:sub-014,user:sub-055}'::text[])
                    Heap Blocks: exact=27198
                    Buffers: shared hit=2 read=27211
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..202.42 rows=28817 width=0) (actual time=2.244..2.244 rows=29469 loops=1)
                          Index Cond: (global_read && '{user:sub-014,user:sub-055}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=98446.29..98446.29 rows=10088 width=49) (actual time=212.284..212.284 rows=4199 loops=1)
                    Buckets: 16384  Batches: 1  Memory Usage: 465kB
                    Buffers: shared hit=1 read=32463
                    ->  Bitmap Heap Scan on search_flat a  (cost=60718.73..98446.29 rows=10088 width=49) (actual time=115.392..211.541 rows=4199 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%456%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Rows Removed by Index Recheck: 37244
                          Heap Blocks: exact=28498
                          Buffers: shared hit=1 read=32463
                          ->  BitmapAnd  (cost=60718.73..60718.73 rows=10088 width=0) (actual time=112.776..112.776 rows=0 loops=1)
                                Buffers: shared hit=1 read=3965
                                ->  Bitmap Index Scan on sf_trgm  (cost=0.00..304.30 rows=150268 width=0) (actual time=2.524..2.524 rows=44376 loops=1)
                                      Index Cond: ((val_text)::text ~~* '%456%'::text)
                                      Buffers: shared hit=1 read=16
                                ->  Bitmap Index Scan on sf_exists  (cost=0.00..60409.13 rows=4599542 width=0) (actual time=109.834..109.834 rows=4544614 loops=1)
                                      Index Cond: (wpath = '/features/*/properties/prop0'::text)
                                      Buffers: shared read=3949
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.338 ms
JIT:
  Functions: 19
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.509 ms, Inlining 0.000 ms, Optimization 0.306 ms, Emission 7.330 ms, Total 8.145 ms
Execution Time: 296.442 ms

```

## 3-high iteration 3: `ilike(features/*/properties/prop0,"*567*")`

```
Limit  (cost=80124.54..80124.55 rows=1 width=90) (actual time=181.937..181.938 rows=0 loops=1)
  Buffers: shared hit=3 read=58018
  ->  Sort  (cost=80124.54..80124.55 rows=1 width=90) (actual time=181.936..181.938 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=58018
        ->  Hash Semi Join  (cost=6170.96..80124.53 rows=1 width=90) (actual time=181.933..181.935 rows=0 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-021,user:sub-062}'::text[]), false))))))))))
              Rows Removed by Join Filter: 141
              Buffers: shared hit=3 read=58018
              ->  Bitmap Heap Scan on search_things st  (cost=210.83..74087.98 rows=29046 width=213) (actual time=4.647..72.410 rows=29475 loops=1)
                    Recheck Cond: (global_read && '{user:sub-021,user:sub-062}'::text[])
                    Heap Blocks: exact=27091
                    Buffers: shared hit=2 read=27104
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..203.57 rows=29046 width=0) (actual time=2.197..2.197 rows=29475 loops=1)
                          Index Cond: (global_read && '{user:sub-021,user:sub-062}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=5958.88..5958.88 rows=100 width=49) (actual time=106.966..106.966 rows=4264 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 406kB
                    Buffers: shared hit=1 read=30914
                    ->  Bitmap Heap Scan on search_flat a  (cost=134.04..5958.88 rows=100 width=49) (actual time=5.660..106.511 rows=4264 loops=1)
                          Recheck Cond: ((val_text)::text ~~* '%567%'::text)
                          Filter: (wpath = '/features/*/properties/prop0'::text)
                          Rows Removed by Filter: 39858
                          Heap Blocks: exact=30898
                          Buffers: shared hit=1 read=30914
                          ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.867..2.867 rows=44122 loops=1)
                                Index Cond: ((val_text)::text ~~* '%567%'::text)
                                Buffers: shared hit=1 read=16
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.305 ms
Execution Time: 181.961 ms

```

## 3-high iteration 4: `ilike(features/*/properties/prop0,"*789*")`

```
Limit  (cost=81606.44..81606.45 rows=1 width=90) (actual time=186.040..186.042 rows=1 loops=1)
  Buffers: shared hit=3 read=57797
  ->  Sort  (cost=81606.44..81606.45 rows=1 width=90) (actual time=186.039..186.040 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=57797
        ->  Hash Semi Join  (cost=6175.26..81606.43 rows=1 width=90) (actual time=160.599..186.036 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-028,user:sub-069}'::text[]), false))))))))))
              Rows Removed by Join Filter: 124
              Buffers: shared hit=3 read=57797
              ->  Bitmap Heap Scan on search_things st  (cost=215.14..75567.72 rows=29867 width=213) (actual time=4.594..73.751 rows=29363 loops=1)
                    Recheck Cond: (global_read && '{user:sub-028,user:sub-069}'::text[])
                    Heap Blocks: exact=27050
                    Buffers: shared hit=2 read=27063
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..207.67 rows=29867 width=0) (actual time=2.184..2.184 rows=29363 loops=1)
                          Index Cond: (global_read && '{user:sub-028,user:sub-069}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=5958.88..5958.88 rows=100 width=49) (actual time=109.590..109.591 rows=4181 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 400kB
                    Buffers: shared hit=1 read=30734
                    ->  Bitmap Heap Scan on search_flat a  (cost=134.04..5958.88 rows=100 width=49) (actual time=5.286..109.104 rows=4181 loops=1)
                          Recheck Cond: ((val_text)::text ~~* '%789%'::text)
                          Filter: (wpath = '/features/*/properties/prop0'::text)
                          Rows Removed by Filter: 39812
                          Heap Blocks: exact=30718
                          Buffers: shared hit=1 read=30734
                          ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.497..2.497 rows=43993 loops=1)
                                Index Cond: ((val_text)::text ~~* '%789%'::text)
                                Buffers: shared hit=1 read=16
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.299 ms
Execution Time: 186.064 ms

```

## 3-high iteration 5: `ilike(features/*/properties/prop0,"*321*")`

```
Limit  (cost=80304.70..80304.71 rows=1 width=90) (actual time=200.492..200.494 rows=0 loops=1)
  Buffers: shared hit=3 read=58420
  ->  Sort  (cost=80304.70..80304.71 rows=1 width=90) (actual time=200.491..200.493 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=58420
        ->  Hash Semi Join  (cost=6171.47..80304.69 rows=1 width=90) (actual time=200.488..200.490 rows=0 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-035,user:sub-076}'::text[]), false))))))))))
              Rows Removed by Join Filter: 110
              Buffers: shared hit=3 read=58420
              ->  Bitmap Heap Scan on search_things st  (cost=211.35..74267.87 rows=29145 width=213) (actual time=4.745..75.014 rows=29720 loops=1)
                    Recheck Cond: (global_read && '{user:sub-035,user:sub-076}'::text[])
                    Heap Blocks: exact=27491
                    Buffers: shared hit=2 read=27504
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..204.06 rows=29145 width=0) (actual time=2.287..2.287 rows=29720 loops=1)
                          Index Cond: (global_read && '{user:sub-035,user:sub-076}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=5958.88..5958.88 rows=100 width=49) (actual time=122.778..122.779 rows=4113 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 394kB
                    Buffers: shared hit=1 read=30916
                    ->  Bitmap Heap Scan on search_flat a  (cost=134.04..5958.88 rows=100 width=49) (actual time=5.377..122.012 rows=4113 loops=1)
                          Recheck Cond: ((val_text)::text ~~* '%321%'::text)
                          Filter: (wpath = '/features/*/properties/prop0'::text)
                          Rows Removed by Filter: 39989
                          Heap Blocks: exact=30900
                          Buffers: shared hit=1 read=30916
                          ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.596..2.596 rows=44102 loops=1)
                                Index Cond: ((val_text)::text ~~* '%321%'::text)
                                Buffers: shared hit=1 read=16
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.302 ms
Execution Time: 200.516 ms

```

## 3-high iteration 6: `ilike(features/*/properties/prop0,"*135*")`

```
Limit  (cost=79586.98..79586.99 rows=1 width=90) (actual time=185.497..185.498 rows=0 loops=1)
  Buffers: shared hit=3 read=58225
  ->  Sort  (cost=79586.98..79586.99 rows=1 width=90) (actual time=185.496..185.497 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=58225
        ->  Hash Semi Join  (cost=6169.40..79586.97 rows=1 width=90) (actual time=185.492..185.494 rows=0 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-042,user:sub-083}'::text[]), false))))))))))
              Rows Removed by Join Filter: 135
              Buffers: shared hit=3 read=58225
              ->  Bitmap Heap Scan on search_things st  (cost=209.28..73551.19 rows=28751 width=213) (actual time=4.283..72.069 rows=29556 loops=1)
                    Recheck Cond: (global_read && '{user:sub-042,user:sub-083}'::text[])
                    Heap Blocks: exact=27293
                    Buffers: shared hit=2 read=27306
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..202.09 rows=28751 width=0) (actual time=2.052..2.052 rows=29556 loops=1)
                          Index Cond: (global_read && '{user:sub-042,user:sub-083}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=5958.88..5958.88 rows=100 width=49) (actual time=110.879..110.879 rows=4164 loops=1)
                    Buckets: 8192 (originally 1024)  Batches: 1 (originally 1)  Memory Usage: 398kB
                    Buffers: shared hit=1 read=30919
                    ->  Bitmap Heap Scan on search_flat a  (cost=134.04..5958.88 rows=100 width=49) (actual time=5.359..110.326 rows=4164 loops=1)
                          Recheck Cond: ((val_text)::text ~~* '%135%'::text)
                          Filter: (wpath = '/features/*/properties/prop0'::text)
                          Rows Removed by Filter: 39937
                          Heap Blocks: exact=30903
                          Buffers: shared hit=1 read=30919
                          ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.577..2.577 rows=44101 loops=1)
                                Index Cond: ((val_text)::text ~~* '%135%'::text)
                                Buffers: shared hit=1 read=16
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.334 ms
Execution Time: 185.523 ms

```

## 3-high iteration 7: `ilike(features/*/properties/prop0,"*890*")`

```
Limit  (cost=175795.04..175795.11 rows=26 width=90) (actual time=344.247..344.249 rows=1 loops=1)
  Buffers: shared hit=3 read=59907
  ->  Sort  (cost=175795.04..175795.30 rows=101 width=90) (actual time=336.827..336.828 rows=1 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=3 read=59907
        ->  Hash Semi Join  (cost=98788.38..175792.17 rows=101 width=90) (actual time=257.895..336.821 rows=1 loops=1)
              Hash Cond: (st.thing_id = a.thing_id)
              Join Filter: ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, 'prop0'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'properties'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'id'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND (COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, 'features'::text, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false) OR ((NOT COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·r'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false)) AND COALESCE((jsonb_extract_path(st.features_auth, VARIADIC ARRAY[a.f_id, '·g'::text]) ?| '{user:sub-049,user:sub-090}'::text[]), false))))))))))
              Rows Removed by Join Filter: 109
              Buffers: shared hit=3 read=59907
              ->  Bitmap Heap Scan on search_things st  (cost=216.00..75861.29 rows=30031 width=213) (actual time=4.325..83.661 rows=30018 loops=1)
                    Recheck Cond: (global_read && '{user:sub-049,user:sub-090}'::text[])
                    Heap Blocks: exact=27620
                    Buffers: shared hit=2 read=27633
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..208.49 rows=30031 width=0) (actual time=2.046..2.046 rows=30018 loops=1)
                          Index Cond: (global_read && '{user:sub-049,user:sub-090}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Hash  (cost=98446.29..98446.29 rows=10088 width=49) (actual time=249.494..249.494 rows=4223 loops=1)
                    Buckets: 16384  Batches: 1  Memory Usage: 466kB
                    Buffers: shared hit=1 read=32274
                    ->  Bitmap Heap Scan on search_flat a  (cost=60718.73..98446.29 rows=10088 width=49) (actual time=131.868..248.320 rows=4223 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%890%'::text) AND (wpath = '/features/*/properties/prop0'::text))
                          Rows Removed by Index Recheck: 36919
                          Heap Blocks: exact=28309
                          Buffers: shared hit=1 read=32274
                          ->  BitmapAnd  (cost=60718.73..60718.73 rows=10088 width=0) (actual time=129.159..129.160 rows=0 loops=1)
                                Buffers: shared hit=1 read=3965
                                ->  Bitmap Index Scan on sf_trgm  (cost=0.00..304.30 rows=150268 width=0) (actual time=2.537..2.537 rows=44082 loops=1)
                                      Index Cond: ((val_text)::text ~~* '%890%'::text)
                                      Buffers: shared hit=1 read=16
                                ->  Bitmap Index Scan on sf_exists  (cost=0.00..60409.13 rows=4599542 width=0) (actual time=126.126..126.126 rows=4544614 loops=1)
                                      Index Cond: (wpath = '/features/*/properties/prop0'::text)
                                      Buffers: shared read=3949
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.295 ms
JIT:
  Functions: 19
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.491 ms, Inlining 0.000 ms, Optimization 0.299 ms, Emission 7.163 ms, Total 7.952 ms
Execution Time: 345.052 ms

```

## 3-low iteration 0 (another path): `ilike(attributes/vendor,"*234*")`

```
Limit  (cost=4051.01..4051.01 rows=1 width=90) (actual time=48.505..48.506 rows=0 loops=1)
  Buffers: shared hit=764 read=7642
  ->  Sort  (cost=4051.01..4051.01 rows=1 width=90) (actual time=48.504..48.505 rows=0 loops=1)
        Sort Key: st.thing_id COLLATE "C"
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=764 read=7642
        ->  Nested Loop  (cost=3999.88..4051.00 rows=1 width=90) (actual time=48.501..48.502 rows=0 loops=1)
              Buffers: shared hit=764 read=7642
              ->  HashAggregate  (cost=3999.33..3999.39 rows=6 width=41) (actual time=45.250..45.293 rows=291 loops=1)
                    Group Key: sf.thing_id
                    Batches: 1  Memory Usage: 85kB
                    Buffers: shared hit=1 read=6950
                    ->  Bitmap Heap Scan on search_flat sf  (cost=3975.26..3999.31 rows=6 width=41) (actual time=22.080..45.173 rows=291 loops=1)
                          Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/attributes/vendor'::text))
                          Rows Removed by Index Recheck: 7657
                          Heap Blocks: exact=6667
                          Buffers: shared hit=1 read=6950
                          ->  BitmapAnd  (cost=3975.26..3975.26 rows=6 width=0) (actual time=21.259..21.259 rows=0 loops=1)
                                Buffers: shared hit=1 read=283
                                ->  Bitmap Index Scan on sf_trgm  (cost=0.00..134.01 rows=1488 width=0) (actual time=2.950..2.950 rows=44298 loops=1)
                                      Index Cond: ((val_text)::text ~~* '%234%'::text)
                                      Buffers: shared hit=1 read=16
                                ->  Bitmap Index Scan on sf_exists  (cost=0.00..3841.00 rows=292324 width=0) (actual time=17.354..17.354 rows=305613 loops=1)
                                      Index Cond: (wpath = '/attributes/vendor'::text)
                                      Buffers: shared read=267
              ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=49) (actual time=0.011..0.011 rows=0 loops=291)
                    Index Cond: (thing_id = sf.thing_id)
                    Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,vendor,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,vendor,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
                    Rows Removed by Filter: 1
                    Buffers: shared hit=763 read=692
Settings: plan_cache_mode = 'force_custom_plan', max_parallel_workers = '0'
Planning:
  Buffers: shared hit=2 read=22
Planning Time: 0.307 ms
Execution Time: 48.785 ms

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
