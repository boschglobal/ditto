# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T03:54:56.146596Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 840.8 | 917.2 | [852.6,841.7,826.4] | [922.0,917.2,862.5] | 6.9% / 59.5 ms (settled) | – | – | **MISS** (settled) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 110.3 | 347.1 | [254.3,109.3,108.3] | [363.3,119.2,115.5] | 214.4% / 247.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 44.0 | 47.0 | [45.5,43.1,43.6] | [47.0,46.7,46.2] | 1.8% / 0.8 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 95.7 | 111.1 | [97.1,95.2,94.4] | [113.0,106.3,104.1] | 8.5% / 8.8 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 326.1 | 346.0 | [321.5,326.6,327.5] | [344.7,363.2,342.9] | 5.9% / 20.2 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 335.0 | 354.8 | [334.9,335.8,334.8] | [352.3,354.5,356.3] | 1.1% / 4.0 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 646.2 | 672.3 | [644.0,650.4,640.9] | [692.1,671.7,669.8] | 3.3% / 22.3 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 340.5 | 362.0 | [339.8,340.5,341.0] | [362.0,361.1,358.1] | 1.1% / 3.9 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 257.0 | 274.2 | [256.0,256.7,257.3] | [274.8,273.5,274.2] | 0.5% / 1.3 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 841.9 | 901.9 | [862.9,838.7,827.3] | [937.0,888.4,874.3] | 7.2% / 62.7 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
