# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T02:05:10.302469Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 831.2 | 920.9 | [842.9,819.9,819.1] | [951.8,857.8,868.7] | 11.0% / 94.0 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 113.8 | 346.4 | [200.1,109.1,110.2] | [375.3,122.7,119.1] | 215.1% / 256.2 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 43.8 | 46.9 | [44.8,43.3,43.6] | [47.3,45.2,45.7] | 4.7% / 2.1 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 188.6 | 316.9 | [201.2,188.6,187.7] | [316.9,301.1,298.7] | 6.1% / 18.2 ms (settled) | – | – | **MISS** (settled) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 329.7 | 348.9 | [329.7,330.4,324.3] | [344.5,345.9,349.8] | 1.5% / 5.2 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 337.7 | 359.2 | [337.1,338.6,334.5] | [355.3,361.0,355.9] | 1.6% / 5.8 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 655.5 | 679.2 | [646.5,657.7,650.3] | [679.0,711.9,673.3] | 5.7% / 38.7 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 343.7 | 365.6 | [340.5,342.9,343.7] | [365.6,362.4,363.2] | 0.9% / 3.2 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 260.9 | 276.9 | [260.3,260.7,261.1] | [275.0,275.8,277.5] | 0.9% / 2.5 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 875.1 | 1379.2 | [1049.1,870.6,839.3] | [1473.4,924.6,878.6] | 67.7% / 594.9 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
