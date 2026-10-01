# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T04:01:06.340479Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 836.1 | 887.5 | [844.8,822.7,826.3] | [894.1,868.5,870.9] | 2.9% / 25.6 ms (settled) | – | – | **MISS** (settled) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 110.4 | 128.6 | [117.9,108.6,108.5] | [131.7,115.9,116.1] | 13.6% / 15.8 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 43.9 | 47.5 | [44.0,43.5,44.7] | [48.2,44.7,47.5] | 7.9% / 3.5 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 187.8 | 325.5 | [195.8,188.9,185.3] | [316.1,348.5,314.1] | 10.9% / 34.4 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 335.6 | 361.9 | [336.3,332.7,335.6] | [363.0,357.5,361.9] | 1.5% / 5.5 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 349.0 | 372.4 | [352.2,345.7,348.5] | [381.0,368.9,371.6] | 3.3% / 12.1 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 660.8 | 690.7 | [653.8,660.2,664.1] | [688.0,690.7,682.5] | 1.2% / 8.2 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 353.8 | 376.2 | [353.8,352.8,353.9] | [374.5,376.9,375.8] | 0.6% / 2.4 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 259.2 | 276.8 | [260.2,260.8,258.4] | [283.5,276.0,276.8] | 2.7% / 7.5 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 911.6 | 1977.1 | [919.8,966.3,863.6] | [1348.4,2009.2,1122.8] | 79.0% / 886.5 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
