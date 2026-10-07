# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-06T23:03:04.262494Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 1164.5 | 4107.0 | [1205.2,1114.3,1319.8] | [5702.5,1602.2,2722.8] | 255.9% / 4100.3 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 217.0 | 627.0 | [434.3,214.0,208.5] | [639.7,233.1,217.6] | 194.0% / 422.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 1983.7 | 6116.7 | [1425.0,2907.4,1883.5] | [6116.7,6138.7,3921.3] | 56.5% / 2217.4 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 2.8 | 3.4 | [3.1,2.6,2.7] | [3.4,2.8,3.5] | 24.7% / 0.7 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 2.4 | 3.3 | [2.3,3.0,2.2] | [3.1,3.4,2.4] | 39.5% / 1.0 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 749.5 | 1167.0 | [771.9,754.5,733.2] | [1167.0,827.1,1193.4] | 44.3% / 366.3 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 249.1 | 289.3 | [263.9,240.9,248.0] | [296.5,250.9,270.6] | 18.2% / 45.6 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
