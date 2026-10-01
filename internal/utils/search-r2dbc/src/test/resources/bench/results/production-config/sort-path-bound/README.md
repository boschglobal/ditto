# Shape 4 with the path-bound sort lateral (back-to-back A/B, 2026-10-01)

Shape 4 is "sort by `/features/env/properties/temperature` + paging". These files compare, on the corpus and server
of the production-config baseline one level up (`../`), two versions of the sort lateral:

- **A, baseline:** the lateral before the change:
  `… FROM search_flat s WHERE (s.thing_id = st.thing_id AND s.wpath = $1) ORDER BY … LIMIT 1`.
- **B, candidate:** the lateral bound to the full `search_flat` key prefix, i.e. also binding `path` to the sort wpath:
  `… WHERE (s.thing_id = st.thing_id AND s.path = $1 AND s.wpath = $2) …`.

Only `PostgresSortClause.java` differed between A and B. Everything else was the worktree at `f72c63530c` plus
test-only changes, none of which is on the bench path.

- The B runs used the path-bound lateral before its wildcard-wpath guard was added.
- That guard only throws for a `/features/*` sort wpath, and leaves the rendered SQL unchanged.

## How the runs were taken

- **Order:** `A1-baseline` 01:48–01:57, `B1-candidate` 01:57–02:04, `B2-candidate` 02:05–02:12, `A2-baseline`
  02:12–02:19 UTC. That is ABBA, to separate drift from effect.
- **Command:** each run is one `run-benchmark.sh pc-read --rounds 3 --results-dir <dir>`. That is the
  production-config warm protocol of `../` unchanged (runbook: `src/test/resources/bench/README.md`):
  - shape by shape, 3 contiguous rounds;
  - each round = full-rotation warmup + 20 timed iterations through `PostgresThingsSearchPersistence`, nearest rank;
  - leader-only find plans captured right after a shape's rounds.
- **Setup:** the same container `ditto-search-bench-pc` (postgres:16 image defaults: 128 MB `shared_buffers`,
  `jit = on`) and the same load as `../` (no reload, no `ANALYZE`, no writes).
- **No `pc-cold`:** it restarts the container, and the corpus was shared with other measurements. There are no cold
  numbers here.
- **What is kept:** per run, `shape-4-sort-paging.md`, `summary.md`, `environment.md` and `warm.json`, i.e. the
  harness's own output.
  - The other shapes do not sort by a flat path, so the change cannot affect them. Their rows in `summary.md` /
    `warm.json` are incidental repeat samples of unchanged code.
  - They neither supersede nor re-baseline `../`, and later re-measurements must not use them as their baseline.
- **`psql-checks.md`:** read-only supplementary checks on the same corpus, with the exact SQL and outputs:
  - the full-order identity of A and B;
  - `thingId` / `namespace` sort timings;
  - the cursor page with `jit = on` vs `off`.

## Result (all-rounds warm p95, ms; p50 in brackets)

| Variant | A1 | B1 | B2 | A2 | A mean → B mean |
|---|---:|---:|---:|---:|---:|
| page 1 | 577.8 (552.0) | 347.1 (330.8) | 348.9 (329.7) | 598.7 (567.1) | 588 → 348 (−41%) |
| page 100 via OFFSET 2475 | 587.4 (558.3) | 388.5 (341.9) ¹ | 359.2 (337.7) | 598.5 (569.1) | 593 → 374 (−37%) |
| page 100 via the service cursor | 695.8 (670.8) | 682.6 (647.8) | 679.2 (655.5) | 710.0 (678.9) | 703 → 681 (−3%) |
| page 201 via OFFSET 5000 | 596.6 (566.2) | 366.4 (346.4) | 365.6 (343.7) | 609.0 (575.9) | 603 → 366 (−39%) |
| page 201 via the service cursor | 276.8 (259.3) | 275.8 (258.3) | 276.9 (260.9) | 279.5 (263.1) | 278 → 276 (0%) |

¹ B1's OFFSET variant did not settle (round 3 p95 414.3 vs 358.5–360.1). B2 repeats it settled at 359.2.

Every variant still misses the 200 ms read gate. The limitation is documented in `installation-extending.md`
("Sort performance caveat").

## Why the pages behave as they do (from the plans in `shape-4-sort-paging.md`)

**Page 1 / OFFSET pages: one lateral probe per `global_read`-visible thing (29,411) + a top-N sort.**

The figures below are page 1, iteration-0 plan, A1 vs B1.

| | A (wpath only) | B (path + wpath) |
|---|---|---|
| `Index Cond` | `thing_id` + `wpath` | `thing_id` + `path` + `wpath` |
| what the probe reads | every primary-key entry of the thing (~68 rows), filtered on `wpath` | only the sort key's entries |
| estimate per probe | rows 22, cost 289 | rows 1, cost 8.7 |
| plan cost | 8.79M | 0.34M |
| JIT | inline + optimize, ~57–62 ms | neither, ~4–5 ms (one 25 ms outlier) |
| lateral node buffers (`Limit … loops=29411`) | hit 96,349 + read 126,071 | hit 96,186 + read 77,329 |
| plan total buffers (top `Limit`) | hit 96,351 + read 153,214 | hit 96,188 + read 104,472 |

The two buffer rows differ by the bitmap heap scan of `search_things` (read 27,143), which both versions share.

What remains in B, both parts proportional to the number of visible things:

- the bitmap heap scan of `search_things`: ~80 ms, 27,143 pages;
- the 29k probes: ~8 µs each, mostly page reads from the OS cache, because 128 MB `shared_buffers` cannot hold the
  probed part of the 14 GB `search_flat_pkey`.

**Cursor pages:** the resume filter is an ordinary criteria with the sort path's per-field auth. It runs
`EXISTS (… sf.thing_id = st.thing_id AND sf.wpath = …)` probes for every visible thing, rendered by the generic filter
translator (`flatProbe`), which this change does not touch.

- Plus ~205–215 ms of JIT per execution: the plan cost stays above 500k because of the filter's auth expressions.
- In `psql-checks.md` §2, `jit = off` took the cursor page from 669.6 to 459.0 ms p95.
- The lateral only runs for the survivors: 5,040 at page 100, 467 at page 201. So B barely changes the cursor pages.

**Same results:**

- In every run, each variant's cross-check says "same page, same order: true".
- The keyset-vs-OFFSET overlap counts in `environment.md` are identical across A and B.
- Over all 29,411 visible things, the full ASC and DESC orderings of A's and B's lateral are identical: 0 mismatching
  positions over `thing_id`, `type_rank`, `val_num`, `val_text`, `val_bool`, and identical md5 digests
  (`psql-checks.md` §1, with the SQL and output).
