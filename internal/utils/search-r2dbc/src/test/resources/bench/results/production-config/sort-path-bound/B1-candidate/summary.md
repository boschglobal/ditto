# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T01:57:54.909805Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 860.6 | 970.4 | [906.2,817.1,861.2] | [979.7,857.1,948.0] | 14.3% / 122.6 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 117.1 | 402.2 | [270.4,114.2,109.2] | [423.9,124.8,120.2] | 252.5% / 303.7 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 45.5 | 56.5 | [47.5,43.6,46.4] | [62.4,45.7,51.5] | 36.4% / 16.7 ms (did not settle) | – | – | PASS (did not settle) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 197.0 | 312.2 | [204.5,189.7,189.1] | [332.8,303.3,299.7] | 11.1% / 33.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 330.8 | 347.1 | [330.2,324.9,330.8] | [346.0,346.1,350.3] | 1.2% / 4.3 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 341.9 | 388.5 | [340.0,338.8,351.5] | [360.1,358.5,414.3] | 15.6% / 55.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 647.8 | 682.6 | [651.1,647.7,647.2] | [682.6,686.8,669.3] | 2.6% / 17.4 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 346.4 | 366.4 | [347.8,344.1,342.7] | [367.5,366.1,366.4] | 0.4% / 1.5 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 258.3 | 275.8 | [258.3,258.0,259.1] | [275.6,274.5,277.1] | 1.0% / 2.6 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 834.0 | 870.3 | [848.2,831.3,826.4] | [884.6,865.5,858.8] | 3.0% / 25.8 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
