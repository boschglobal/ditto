# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T03:48:10.332036Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 840.2 | 1207.0 | [888.4,860.6,813.1] | [1166.9,1549.3,846.3] | 83.1% / 703.0 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 113.2 | 362.9 | [271.5,110.5,108.6] | [379.0,161.8,116.8] | 224.4% / 262.2 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 43.8 | 57.2 | [47.6,44.2,43.2] | [57.7,45.8,44.3] | 30.1% / 13.3 ms (did not settle) | – | – | PASS (did not settle) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 96.6 | 111.2 | [100.1,93.7,93.6] | [112.2,109.3,106.2] | 5.7% / 6.0 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 327.6 | 348.7 | [330.5,322.7,325.0] | [351.1,348.7,344.0] | 2.1% / 7.1 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 331.9 | 394.6 | [335.0,330.9,325.2] | [394.6,483.7,345.9] | 39.8% / 137.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 646.6 | 672.4 | [645.1,646.6,647.6] | [666.6,671.5,673.1] | 1.0% / 6.5 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 338.4 | 358.0 | [338.7,338.4,335.8] | [359.6,355.3,358.0] | 1.2% / 4.3 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 257.7 | 275.4 | [257.4,257.6,258.5] | [273.0,275.4,274.4] | 0.9% / 2.4 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 869.2 | 1240.1 | [872.7,903.0,856.0] | [994.4,1451.3,994.4] | 45.9% / 456.9 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
