# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T02:12:29.418445Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 853.6 | 989.5 | [901.9,834.5,837.2] | [1283.8,890.4,876.6] | 46.4% / 407.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 114.6 | 361.4 | [259.8,112.7,109.9] | [383.6,121.8,118.8] | 223.0% / 264.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 44.3 | 53.8 | [44.8,45.2,43.2] | [54.2,47.8,45.0] | 20.4% / 9.2 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 196.6 | 338.0 | [202.4,191.8,190.5] | [318.3,343.2,330.7] | 7.8% / 24.9 ms (settled) | – | – | **MISS** (settled) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 567.1 | 598.7 | [573.4,563.1,566.8] | [588.6,591.2,605.7] | 2.9% / 17.1 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 569.1 | 598.5 | [572.5,566.5,569.9] | [597.6,596.7,604.0] | 1.2% / 7.2 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 678.9 | 710.0 | [683.7,676.6,674.4] | [710.0,706.6,705.2] | 0.7% / 4.9 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 575.9 | 609.0 | [569.9,575.5,585.3] | [595.2,602.1,620.8] | 4.3% / 25.6 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 263.1 | 279.5 | [262.7,263.1,263.8] | [277.6,278.8,279.5] | 0.7% / 1.8 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 848.3 | 880.1 | [851.9,837.5,849.1] | [896.5,874.8,878.1] | 2.5% / 21.7 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
