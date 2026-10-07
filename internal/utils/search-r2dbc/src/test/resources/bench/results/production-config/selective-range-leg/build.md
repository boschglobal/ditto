# Concurrent build of the system index sf_multi on the 1M corpus (2026-10-06)

The corpus and server of this folder (`load/load.md`: 1,000,000 things, 68,513,542 `search_flat` rows; container
`ditto-search-bench-pc`, postgres:16 image defaults, `maintenance_work_mem` 64MB). The loader had built `sf_multi`
with a plain `CREATE INDEX` after the COPY (offline load). It was dropped and rebuilt the way the service builds it,
with the same definition, so the catalog check still matches:

```
docker exec ditto-search-bench-pc psql -U bench -d bench -c '\timing on' -c 'DROP INDEX sf_multi' \
  -c 'CREATE INDEX CONCURRENTLY sf_multi ON search_flat (wpath, thing_id) WHERE ord = 1' \
  -c "SELECT pg_size_pretty(pg_relation_size('sf_multi')), (SELECT count(*) FROM search_flat WHERE ord = 1)"
```

| | value |
|---|---:|
| `CREATE INDEX CONCURRENTLY sf_multi …` (no concurrent writes) | **71.8 s** (71,805 ms) |
| index size | **213 MB** |
| `search_flat` rows with `ord = 1` (index entries) | 2,330,008 of 68,513,542 (3.4 %) |
| `pg_index.indisvalid` afterwards | true |
| definition | `CREATE INDEX sf_multi ON public.search_flat USING btree (wpath, thing_id) WHERE (ord = 1)` |

For comparison (`load/load.md`): `sf_exists (wpath)` 754 MB, `sf_num` 1050 MB, `sf_trgm` 304 MB. A
`CREATE INDEX CONCURRENTLY` reads the whole `search_flat` heap twice (build and validation scan), so its duration grows
with the whole table, however few rows have `ord = 1`. The write-path cost of maintaining `sf_multi` was not measured
here.
