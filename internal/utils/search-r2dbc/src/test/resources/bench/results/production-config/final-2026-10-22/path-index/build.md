# Per-path trigram index on `features/*/properties/prop0`: build

The exact statement `PathIndex.createStatement()` renders for a trigram index on that path, run with psql in
autocommit on the loaded corpus between `A1-without` and `B1-with`; dropped with `DROP INDEX CONCURRENTLY`
before `A2-without` (`drop` below). psql output, verbatim:

```
Timing is on.
 maintenance_work_mem 
----------------------
 64MB
(1 row)

Time: 0.590 ms
CREATE INDEX
Time: 43413.895 ms (00:43.414)
            relname            | indisvalid | size  |  bytes   
-------------------------------+------------+-------+----------
 sf_path_trgm_ca5f5cd692cb58e9 | t          | 51 MB | 53485568
(1 row)

Time: 7.549 ms
                                                                                            pg_get_indexdef                                                                                            
-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 CREATE INDEX sf_path_trgm_ca5f5cd692cb58e9 ON public.search_flat USING gin (val_text COLLATE "C.utf8" gin_trgm_ops) WHERE ((wpath = '/features/*/properties/prop0'::text) AND (val_text IS NOT NULL))
(1 row)

Time: 1.311 ms
```

## Drop

```
Timing is on.
DROP INDEX
Time: 8.369 ms
 leftover 
----------
        0
(1 row)

Time: 0.515 ms
```
