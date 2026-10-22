# Corpus checks after the load

Read-only `psql` in the container `ditto-search-bench-pc` right after `load/load.md` (2026-10-22, 00:16–00:19 UTC):
server version, `sf_multi` validity, row counts, the lone wide range (temperature 0..100), the temperature path's
multi-valued rows, the ten `lone-mid` buckets (`lo .. lo + 2.89`, as `ProductionConfigShapes.loneMidFilter`) and the
database size. The bench load creates the production DDL of search schema v3 without the `schema_version` table, hence
the error on that line.

```
SELECT version();
SELECT indisvalid FROM pg_index WHERE indexrelid = 'sf_multi'::regclass;
SELECT * FROM schema_version;
SELECT count(*) FROM search_things;
SELECT count(*) FROM search_flat;
SELECT count(*) FROM search_flat WHERE wpath = '/features/env/properties/temperature' AND val_num BETWEEN 0 AND 100;
SELECT count(*) FROM search_flat WHERE wpath = '/features/env/properties/temperature' AND ord = 1;
SELECT b.lo, count(*) FROM (VALUES (-15),(-10),(-5),(0),(5),(10),(15),(20),(25),(30)) b(lo)
  JOIN search_flat f ON f.wpath = '/features/env/properties/temperature' AND f.val_num BETWEEN b.lo AND b.lo + 2.89
  GROUP BY b.lo ORDER BY b.lo;
SELECT pg_size_pretty(pg_database_size('bench'));
```

```
Pager usage is off.
Timing is on.
                                                          version                                                           
----------------------------------------------------------------------------------------------------------------------------
 PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
(1 row)

Time: 0.459 ms
 indisvalid 
------------
 t
(1 row)

Time: 1.934 ms
ERROR:  relation "schema_version" does not exist
LINE 1: SELECT * FROM schema_version;
                      ^
Time: 0.071 ms
  count  
---------
 1000000
(1 row)

Time: 46.430 ms
  count   
----------
 68513542
(1 row)

Time: 942.065 ms
 count  
--------
 622807
(1 row)

Time: 148.496 ms
 count 
-------
     0
(1 row)

Time: 0.503 ms
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

Time: 79.380 ms
 pg_size_pretty 
----------------
 29 GB
(1 row)

Time: 1.146 ms
```
