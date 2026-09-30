# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-09-30T18:22:05.756508Z, 3 contiguous rounds per shape × (3 warmup + 20 timed); a round's first-touch reads of binds its warmup did not cover land in that round. Cold: 2026-09-30T18:30:48.747296Z, 3 samples per shape (PostgreSQL- and Docker-VM-cold; the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | steady-state p50 / p95 ms (rounds 2+) | cold 1st exec ms (median; host cache not dropped) | cold samples | warm p95 vs 200 ms gate | evidence |
|---|---:|---:|---|---|---:|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 918.3 | 5791.2 | [1645.5,999.0,858.9] | [6783.5,1253.2,893.6] | 874.1 / 1249.1 | 1564.3 | [13319.1,1564.3,1400.9] | **MISS** | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 121.6 | 411.4 | [335.2,117.8,113.8] | [420.9,134.4,124.4] | 116.9 / 133.7 | 223.7 | [963.7,223.7,216.2] | **MISS** (steady state: PASS) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 46.2 | 100.1 | [52.4,45.2,46.5] | [103.2,47.0,49.8] | 45.5 / 49.4 | 81.2 | [366.4,74.0,81.2] | PASS | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 204.2 | 828.5 | [240.7,192.7,194.6] | [898.0,320.2,314.7] | 193.4 / 320.2 | 346.6 | [1671.8,338.4,346.6] | **MISS** | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 580.5 | 612.5 | [576.1,584.7,569.7] | [604.8,622.7,600.9] | 580.5 / 612.5 | 1316.0 | [1955.4,1316.0,1156.4] | **MISS** | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 583.4 | 613.7 | [584.0,582.2,579.0] | [612.7,613.8,612.7] | 582.2 / 613.7 | 1123.0 | [1123.0,1253.7,1101.6] | **MISS** | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 691.1 | 726.5 | [689.5,685.3,693.7] | [721.4,726.5,728.0] | 691.1 / 726.8 | 1194.3 | [1194.3,1255.7,1132.9] | **MISS** | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 594.6 | 620.3 | [595.4,602.2,591.4] | [617.5,627.3,618.6] | 592.4 / 626.2 | 1190.8 | [1190.8,1308.7,1146.4] | **MISS** | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 266.0 | 290.7 | [268.7,264.5,266.0] | [292.6,286.5,290.0] | 265.7 / 288.5 | 407.8 | [407.8,447.4,403.2] | **MISS** | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 941.1 | 4009.2 | [1126.1,900.1,892.7] | [4513.7,1852.2,1596.6] | 900.1 / 1852.2 | 1462.5 | [1489.4,1386.5,1462.5] | **MISS** | `shape-5-count.md` |
