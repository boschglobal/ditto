# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-09-30T16:30:08.798733Z, 3 rounds × 20 timed. Cold: 2026-09-30T16:42:08.617529Z, 3 samples per shape. Server settings: `environment.md`. "Cold": see `cold.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p95 per round | steady-state p50 / p95 ms (rounds 2+) | cold 1st exec ms (median) | cold samples | warm p95 vs 200 ms gate | evidence |
|---|---:|---:|---|---:|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 862.9 | 4097.3 | [4735.2,918.7,920.0] | 850.1 / 920.0 | 1509.6 | [5473.4,1393.6,1509.6] | **MISS** | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 346.6 | 427.7 | [402.4,416.3,475.6] | 358.9 / 456.9 | 245.4 | [273.6,245.4,194.0] | **MISS** | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 46.0 | 185.7 | [191.6,116.0,170.2] | 46.1 / 132.1 | 77.5 | [371.6,73.5,77.5] | PASS | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 298.5 | 1292.8 | [1311.6,983.6,878.3] | 298.5 / 970.4 | 571.6 | [868.1,463.1,571.6] | **MISS** | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 542.6 | 576.6 | [568.3,584.5,565.1] | 543.2 / 580.1 | 1206.2 | [1283.4,1206.2,1086.2] | **MISS** | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 550.5 | 578.5 | [574.7,572.5,578.5] | 549.9 / 578.5 | 1093.3 | [1136.0,1093.3,1092.8] | **MISS** | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 665.4 | 688.7 | [690.2,676.8,688.7] | 662.0 / 686.1 | 1016.6 | [1138.9,1016.6,1010.5] | **MISS** | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 556.6 | 585.8 | [584.5,588.6,579.3] | 559.7 / 585.8 | 1144.1 | [1224.7,1144.1,1110.4] | **MISS** | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 259.8 | 281.9 | [277.1,282.9,273.2] | 258.7 / 281.9 | 387.5 | [418.1,387.5,373.9] | **MISS** | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 1444.7 | 5677.0 | [4894.7,5289.4,5681.1] | 1379.1 / 5681.1 | 1326.4 | [1326.4,1343.3,1313.0] | **MISS** | `shape-5-count.md` |
