# Corpus checks after the load

Read-only `psql` in the container `ditto-search-bench-pc` right after `load.md` (2026-10-22, 11:17 UTC): server version,
`sf_multi` validity, the `schema_version` table (the bench load writes none), row counts, the lone wide range
(temperature 0..100), the temperature path's multi-valued rows, the city of shapes 1/5, the ten `lone-mid` buckets
(`lo .. lo + 2.89`, as `ProductionConfigShapes.loneMidFilter`), extended statistics, the last `ANALYZE` and the
database size.

````sql
\pset pager off
\timing on
SELECT version();
SELECT indisvalid FROM pg_index WHERE indexrelid = 'sf_multi'::regclass;
SELECT to_regclass('schema_version') AS schema_version_table;   -- the bench load writes none
SELECT count(*) FROM search_things;
SELECT count(*) FROM search_flat;
SELECT count(*) FROM search_flat WHERE wpath = '/features/env/properties/temperature' AND val_num BETWEEN 0 AND 100;
SELECT count(*) FROM search_flat WHERE wpath = '/features/env/properties/temperature' AND ord = 1;
SELECT count(*) FROM search_flat WHERE wpath = '/attributes/location/city' AND val_text = 'Stuttgart';
SELECT b.lo, count(*) FROM (VALUES (-15),(-10),(-5),(0),(5),(10),(15),(20),(25),(30)) b(lo)
  JOIN search_flat f ON f.wpath = '/features/env/properties/temperature' AND f.val_num BETWEEN b.lo AND b.lo + 2.89
  GROUP BY b.lo ORDER BY b.lo;
SELECT count(*) AS extended_statistics FROM pg_statistic_ext;
SELECT relname, last_analyze, last_autoanalyze FROM pg_stat_user_tables
  WHERE relname IN ('search_things', 'search_flat') ORDER BY relname;
SELECT pg_size_pretty(pg_database_size('bench'));
````

````
Pager usage is off.
Timing is on.
                                                          version                                                           
----------------------------------------------------------------------------------------------------------------------------
 PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
(1 row)

Time: 0.917 ms
 indisvalid 
------------
 t
(1 row)

Time: 1.411 ms
 schema_version_table 
----------------------
 
(1 row)

Time: 0.075 ms
  count  
---------
 1000000
(1 row)

Time: 59.506 ms
  count   
----------
 68513542
(1 row)

Time: 1066.523 ms (00:01.067)
 count  
--------
 622807
(1 row)

Time: 161.445 ms
 count 
-------
     0
(1 row)

Time: 0.858 ms
 count 
-------
 89901
(1 row)

Time: 6.805 ms
 lo  | count 
-----+-------
 -15 | 40368
 -10 | 40009
  -5 | 40349
   0 | 40110
   5 | 40061
  10 | 40338
  15 | 40083
  20 | 40389
  25 | 39571
  30 | 40331
(10 rows)

Time: 80.996 ms
 extended_statistics 
---------------------
                   0
(1 row)

Time: 0.250 ms
    relname    |         last_analyze          |       last_autoanalyze        
---------------+-------------------------------+-------------------------------
 search_flat   | 2026-10-22 11:16:39.797473+00 | 
 search_things | 2026-10-22 11:16:28.581248+00 | 2026-10-22 11:06:40.454703+00
(2 rows)

Time: 2.840 ms
 pg_size_pretty 
----------------
 29 GB
(1 row)

Time: 0.702 ms
````
