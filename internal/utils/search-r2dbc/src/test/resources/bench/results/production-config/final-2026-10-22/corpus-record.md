# Corpus record before the final re-run

Read-only queries on the container `ditto-search-bench-pc` at 2026-10-22T13:44:32Z, before the first measured run
(`corpus-record.sql`: server version, `sf_multi` validity, no `schema_version` table, no extended statistics,
table statistics incl. inserts/updates/deletes since the load, database size).

```
Pager usage is off.
                                                          version                                                           
----------------------------------------------------------------------------------------------------------------------------
 PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
(1 row)

 indisvalid 
------------
 t
(1 row)

 schema_version_table 
----------------------
 
(1 row)

 extended_statistics 
---------------------
                   0
(1 row)

    relname    |         last_analyze          |       last_autoanalyze        | n_tup_ins | n_tup_upd | n_tup_del 
---------------+-------------------------------+-------------------------------+-----------+-----------+-----------
 search_flat   | 2026-10-22 11:16:39.797473+00 |                               |  68513542 |         0 |         0
 search_things | 2026-10-22 11:16:28.581248+00 | 2026-10-22 11:06:40.454703+00 |   1000000 |         0 |         0
(2 rows)

 pg_size_pretty 
----------------
 29 GB
(1 row)

```
