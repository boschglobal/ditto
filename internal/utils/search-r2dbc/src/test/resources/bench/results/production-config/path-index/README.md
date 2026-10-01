# Shape 3-high with a per-path trigram index (back-to-back A/B, 2026-10-01)

Shape 3-high is infix `ilike` on `features/*/properties/prop0`, a property of every feature: 4,544,614 of the
68,513,542 `search_flat` rows. These files compare, on a copy of the corpus of the production-config baseline one level
up (`../`), the shipped schema without and with the per-path index that
`ditto.postgresql.search.path-indexes.paths = [ { path = "features/*/properties/prop0", kind = "trigram" } ]` builds:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS sf_path_trgm_ca5f5cd692cb58e9 ON search_flat
    USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops)
    WHERE wpath = E'/features/*/properties/prop0' AND val_text IS NOT NULL
```

## How the runs were taken

- **Database:** a scratch copy of the loaded corpus (`CREATE DATABASE … TEMPLATE … STRATEGY = FILE_COPY`, 162 s,
  29 GB) in the same container `ditto-search-bench-pc` (postgres:16 image defaults: 128 MB `shared_buffers`,
  `maintenance_work_mem` 64 MB, `jit = on`). File copy keeps the heap, the indexes and the planner statistics of
  `../`; the copy was dropped afterwards and the original corpus was not touched. The scratch database's name is
  shortened to `scratch` in these files.
- **Order:** `A1-without` 03:26–03:34, index build 03:36, `B1-with` 03:48–03:54, `B2-with` 03:54–04:00, index dropped,
  `A2-without` 04:01–04:07 UTC. That is ABBA, to separate drift from effect.
- **Timed runs:** each is one `ProductionConfigReadBench#warm` with `--rounds 3` against the copy, i.e. the
  production-config warm protocol of `../` unchanged (shape by shape, 3 contiguous rounds; each round = full-rotation
  warmup + 20 timed iterations through `PostgresThingsSearchPersistence`, nearest rank; leader-only find plans captured
  right after a shape's rounds). The B runs list the per-path index under "per-path indexes" in `environment.md`; it
  is not part of the production DDL and therefore not a catalog difference. These runs predate two harness changes:
  `environment.md` now says "matches production: production DDL + N per-path index(es)" instead of `true` when such an
  index is present, and `summary.md` names the per-path indexes of the run. In the files here the B runs' index is
  visible in `environment.md` only.
- **Build:** the production `PathIndexMaintenance`, run right after the production schema bootstrap on the copy
  (the bootstrap ran no DDL on the loaded tables and wrote the `schema_version` row), through the shipped search
  profile's connection settings: `build.md` (outcome, the service log lines incl. progress, and all index sizes).
- **Plans:** `explain-with.md` / `explain-without.md`: `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` of all 8 3-high patterns
  and of 3-low iteration 0 (another path), on the shipped pool (`plan_cache_mode = force_custom_plan`), leader-only as
  the shipped `findAll` executes, after one pass over the rotation; plus `EXPLAIN (GENERIC_PLAN)` of `*890*`.
- **What is kept:** per run `summary.md`, `environment.md`, `shape-3-ilike.md` and `warm.json`. The other shapes do not
  filter on this path; their rows in `summary.md` / `warm.json` are incidental repeat samples of unchanged code (their
  run-to-run spread is that of `../`, e.g. shape 1/2/5 round 1).
- **No cold runs:** the container was shared with other measurements.

## Result: shape 3-high, all-rounds warm p95 in ms (p50 in brackets)

| Run | index | all-rounds p95 | p95 per round | settled? | 200 ms gate |
|---|---|---:|---|---|:---:|
| A1 | without | **305.8** (197.6) | 346.5 · 296.4 · 298.1 | no (16.9%) | MISS |
| B1 | with | **111.2** (96.6) | 112.2 · 109.3 · 106.2 | yes | PASS |
| B2 | with | **111.1** (95.7) | 113.0 · 106.3 · 104.1 | yes | PASS |
| A2 | without | **325.5** (187.8) | 316.1 · 348.5 · 314.1 | no (10.9%) | MISS |

A mean 315.7 → B mean 111.2 ms p95 (−65%); p50 192.7 → 96.2 ms. `../` measured 391.5 (199.0) on the same corpus
without the index. 3-low (`attributes/vendor`, another path) is unchanged: 52.5 / 57.2 / 47.0 / 47.5 ms.

Per pattern (round 3, ms): `*890*` 296.5 / 106.2 / 101.9 / 328.8; `*456*` 298.1 / 107.9 / 104.0 / 309.1; `*234*`
182.3 / 98.3 / 92.4 / 192.4.

## Plans

Without the index (`explain-without.md`), as in `../`: `sf_trgm` returns ~44k candidates for every pattern (44,082
for `*890*`, 44,298 for `*234*`), filtered to the wpath on the heap. For `*890*` and `*456*` the `val_text`
estimate (150,268 vs 1,488 rows) flips the plan to `BitmapAnd(sf_trgm, sf_exists)`, which reads all 4,544,614
`sf_exists` entries of the wpath (that scan alone 110–126 ms of a 296–345 ms execution); the flat heap scan then
still visits 28,309 blocks for `*890*`. The `*456*` flip, inferred in `../`, is captured here.

With the index (`explain-with.md`), all 8 patterns:

- `Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9` with `Index Cond: ((val_text)::text ~~* '%…%'::text)`: it
  returns only the matches on this path (4,223 rows for `*890*`, not ~44k), 0.25–0.75 ms;
- no `sf_exists`, no `BitmapAnd`, no `sf_trgm` (the predicate `wpath = …` is implied by the partial index);
- the flat bitmap heap scan visits 4,211 blocks for `*890*` (28,309 without the index) in ~16 ms;
- what is left is the `global_read` bitmap heap scan both plans share (~27,600 blocks, ~80 ms);
- executions 88–112 ms.

3-low iteration 0 (`ilike(attributes/vendor,…)`) keeps `BitmapAnd(sf_trgm, sf_exists)`: the per-path index is not a
candidate for another path. Its EXPLAIN in `explain-with.md` took 737 ms because the pass before it warmed only
3-high; the timed 3-low runs above are unchanged.

`EXPLAIN (GENERIC_PLAN)` of `*890*` (the plan a non-custom plancache entry would use) does not use the per-path index:
with `wpath = $1` the partial predicate is not provable. The search pool's `plan_cache_mode = force_custom_plan`
(`ditto.postgresql.force-custom-plan`) is what makes it usable.

## Build cost

From `build.md`:

- `CREATE INDEX CONCURRENTLY` on 68.5 M rows (1,327,064 heap blocks, 10 GB heap): **44 s** with
  `maintenance_work_mem` 64 MB, on a page cache that held much of the heap (the build log shows "building index",
  95% of the blocks, after 30 s).
- Size **51 MB** (53,485,568 bytes), vs 304 MB for the table-wide `sf_trgm`.
- Not measured here: the write overhead of the extra GIN on writes to this path, and a build on a cold page cache.
