# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T03:26:56.575216Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 875.8 | 1249.6 | [947.8,876.6,839.9] | [1338.5,1123.8,930.2] | 43.9% / 408.3 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 113.6 | 397.2 | [306.7,111.3,109.4] | [405.2,122.7,114.6] | 253.6% / 290.6 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 45.8 | 52.5 | [46.9,44.9,45.3] | [52.5,64.3,48.0] | 34.0% / 16.3 ms (did not settle) | – | – | PASS (did not settle) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 197.6 | 305.8 | [206.3,191.5,192.9] | [346.5,296.4,298.1] | 16.9% / 50.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 334.2 | 373.0 | [329.8,346.7,329.2] | [371.1,378.0,361.7] | 4.5% / 16.3 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 341.5 | 365.1 | [348.4,337.6,337.9] | [410.9,359.7,358.5] | 14.6% / 52.4 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 652.5 | 671.2 | [650.2,652.7,645.3] | [669.7,672.2,671.1] | 0.4% / 2.4 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 345.4 | 366.9 | [342.6,346.5,341.9] | [365.6,366.9,366.6] | 0.4% / 1.4 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 260.4 | 277.3 | [263.8,259.2,258.4] | [282.4,273.4,276.6] | 3.3% / 9.0 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 846.0 | 896.0 | [856.5,837.1,829.1] | [934.9,883.4,874.0] | 7.0% / 60.9 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
