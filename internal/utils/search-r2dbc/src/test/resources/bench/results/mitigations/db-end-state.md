# Bench-DB end state after Task 0.2b (the state Task 0.3's write benchmarks run against)

Container `ditto-search-bench-pg` (postgres:16, host port 55432, data on named volume
`3f12076b21648e1b2dc135ab818bebe353e108a3e682c5c8578072a510d4ed94`), recreated during E2 with:

```
postgres -c shared_buffers=4GB -c effective_cache_size=12GB -c work_mem=64MB
```

(the flags live in the container's command, so they survive `docker restart` — verified; they do NOT survive
a `docker rm` + plain re-run, use the README's E2 one-liner to recreate). **Review round 1, Finding 1** restarted
this same container twice (for the `e5-cold-start-probe.md` symmetric cold-start measurement) — settings,
indexes, statistics targets and row counts were re-verified identical afterwards (see the review-fix
verification note at the end of this file).

Corpus intact: 1,000,000 `search_things` rows / 68,513,542 `search_flat` rows (verified post-E5, re-verified
after review round 1's fixes below).

Per-column statistics targets (persisted in the data directory, re-ANALYZEd):
`wpath=10000`, `val_text=1000`, `val_num=1000` (all other columns default 100). **Review round 1, Finding 3**
temporarily reset `val_text`/`val_num` to the default target (`-1`) to isolate the wpath-alone estimate (see
`e1-wpath-alone-estimates.md`), then restored both to 1000 and re-ANALYZEd — verified via
`pg_attribute.attstattarget` to equal the values above at the end of that method; the values in this file are
unchanged from before that round.

Indexes now present (Task 0.2b ADDED the three marked NEW — kept deliberately: Task 0.3 must price their
write-side cost, since the read gate only passes WITH them):

```
search_flat_pkey | 14 GB
search_things_pkey | 110 MB
sf_bool | 87 MB
sf_exists | 754 MB
sf_num | 1050 MB
sf_num_covering | 1848 MB
sf_text | 633 MB
sf_text_covering | 1670 MB
sf_trgm | 304 MB
sf_trgm_scoped_feature | 51 MB
st_delete_at | 456 kB
st_global_read | 9616 kB
st_modified | 21 MB
st_namespace | 91 MB
st_policy | 61 MB
st_referenced_pols | 2568 kB
```

- NEW: `sf_num_covering` — (wpath, val_num) INCLUDE (thing_id) WHERE val_num IS NOT NULL — 1848 MB, built in 50.7s (CONCURRENTLY)
- NEW: `sf_text_covering` — (wpath, val_text) INCLUDE (thing_id) WHERE val_text IS NOT NULL — 1670 MB, built in 41.9s (CONCURRENTLY)
- NEW: `sf_trgm_scoped_feature` — partial trigram GIN on val_text scoped to wpath='/features/*/properties/prop0' — 51 MB. **Corrected (review round 1, Finding 2):** the originally-committed "26.9s" build time was a no-op timing artifact (the index already existed from a discarded run when that `CREATE INDEX CONCURRENTLY IF NOT EXISTS` executed — see `e4-scoped-trigram.md`'s "Review-fix (round 1, Finding 2)" section). The index was dropped and rebuilt cleanly for this review round: real build time **21.9s** (CONCURRENTLY), size unchanged at 51 MB. Present at the end of this review round exactly as before.

Original Task 0.1 indexes (sf_num/sf_text/sf_bool/sf_exists/sf_trgm + search_things') all still present —
nothing was dropped, so Task 0.3 sees a superset. If Task 0.3 wants the un-mitigated write cost, drop the
three NEW indexes first and reset the statistics targets to default (`ALTER TABLE search_flat ALTER COLUMN
wpath SET STATISTICS -1;` etc. + ANALYZE).

## Review round 1 end-state re-verification (2026-07-04)

After all three review-fix experiments (wpath-alone estimate + restore, scoped-trigram clean rebuild, cold-start
probe with two container restarts), re-queried directly against `ditto-search-bench-pg`:

```
-- settings
 effective_cache_size | 1572864   (12GB)
 shared_buffers       | 524288    (4GB)
 work_mem             | 65536     (64MB)

-- pg_attribute.attstattarget on search_flat
 val_num  | 1000
 val_text | 1000
 wpath    | 10000

-- pg_indexes sizes (unchanged from the table above, incl. sf_trgm_scoped_feature still 51 MB post-rebuild)
-- (full list identical to the table above; re-run verified byte-for-byte identical index-name/size pairs)

-- row counts
 search_things | 1000000
 search_flat   | 68513542
```

Exactly matches the state documented above — the review-fix experiments are fully additive/restorative and
leave the bench DB in precisely the state Task 0.3 depends on.
