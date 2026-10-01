# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` only), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-01T01:48:24.179546Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 837.5 | 1350.9 | [972.0,810.6,828.8] | [1505.0,866.2,873.0] | 73.8% / 638.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 113.9 | 439.8 | [295.8,113.8,108.8] | [463.0,117.7,115.0] | 302.6% / 348.0 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 3-low infix ilike, low-cardinality attribute | 43.9 | 47.8 | [44.3,44.3,43.1] | [46.7,47.8,49.7] | 6.4% / 3.0 ms (settled) | – | – | PASS (settled) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 194.4 | 317.1 | [214.5,184.9,185.0] | [357.5,307.1,302.9] | 18.0% / 54.6 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-3-ilike.md` |
| 4-page1 sort by temperature, page 1 | 552.0 | 577.8 | [549.2,552.4,550.3] | [574.0,579.3,574.5] | 0.9% / 5.3 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset sort by temperature, page 100 via OFFSET | 558.3 | 587.4 | [555.7,562.6,558.3] | [590.6,584.9,584.4] | 1.1% / 6.2 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset sort by temperature, page 100 via the service cursor | 670.8 | 695.8 | [675.6,667.5,663.2] | [689.7,700.7,694.6] | 1.6% / 11.0 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-offset-5000 sort by temperature, page 201 via OFFSET (populated anchor) | 566.2 | 596.6 | [563.6,565.4,566.2] | [596.6,595.1,595.0] | 0.3% / 1.6 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 4-keyset-5000 sort by temperature, page 201 via the service cursor (populated anchor) | 259.3 | 276.8 | [257.9,258.8,259.6] | [276.3,276.4,276.8] | 0.2% / 0.5 ms (settled) | – | – | **MISS** (settled) | `shape-4-sort-paging.md` |
| 5 count with shape 1's filter | 880.0 | 3733.4 | [1829.2,867.6,830.4] | [3999.4,1141.2,880.0] | 354.5% / 3119.4 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
