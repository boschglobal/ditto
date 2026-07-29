# Task 0.3 Part 0 - DB state normalization

Dropped the Task-0.2b E3 covering indexes (`sf_num_covering`, `sf_text_covering`) FIRST, before any write-path measurement: E3 was a committed NEGATIVE result (no read benefit for the auth-heavy shapes 1/5, whose cost lives in the per-candidate `search_things` probes, not the flat-index lookup — see `task-0.2b-report.md`'s E3 section), so pricing their write cost would bill the design for an index the read gate itself rejected.

KEPT (the combination the read gate actually needs, per `task-0.2b-report.md`'s residual risk paragraph): E1 per-column statistics targets (`wpath=10000`, `val_text=1000`, `val_num=1000`), E2 laptop-class memory settings (`shared_buffers=4GB`, `effective_cache_size=12GB`, `work_mem=64MB`), the E4 wpath-scoped partial trigram (`sf_trgm_scoped_feature`), and the original table-wide `sf_trgm`.

## DDL executed

```sql
DROP INDEX IF EXISTS sf_num_covering;  -- 2ms
DROP INDEX IF EXISTS sf_text_covering;  -- 0ms
```

## Final index list (post Part-0) — this is the state Part 1/Part 2 price

```
search_flat_pkey | 14 GB
search_things_pkey | 110 MB
sf_bool | 87 MB
sf_exists | 754 MB
sf_num | 1050 MB
sf_text | 633 MB
sf_trgm | 304 MB
sf_trgm_scoped_feature | 51 MB
st_delete_at | 456 kB
st_global_read | 9616 kB
st_modified | 21 MB
st_namespace | 91 MB
st_policy | 61 MB
st_referenced_pols | 2568 kB
```

## Settings / statistics targets in effect

GIN / autovacuum / memory knobs in effect (recorded per the brief; defaults are fine, they just must be recorded):

```
gin_pending_list_limit = 4MB
autovacuum_vacuum_scale_factor = 0.2
autovacuum_vacuum_cost_limit = -1
autovacuum_vacuum_cost_delay = 2ms
autovacuum_naptime = 1min
shared_buffers = 4GB
effective_cache_size = 12GB
work_mem = 64MB
sf_trgm reloptions (fastupdate; empty = default ON) = (none — all defaults)
sf_trgm_scoped_feature reloptions = (none — all defaults)
search_flat reloptions (autovacuum overrides; empty = using the global defaults above) = (none — all defaults)
statistics targets: val_num=1000 val_text=1000 wpath=10000
```

## Row counts (corpus intact)

```
search_things = 1000000
search_flat = 68513542
```
