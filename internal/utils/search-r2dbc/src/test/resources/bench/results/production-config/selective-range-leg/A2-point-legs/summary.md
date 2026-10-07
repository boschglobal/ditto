# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-06T23:14:06.336851Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 3206.3 | 6567.8 | [3594.4,3296.6,2837.5] | [6567.8,6291.5,6128.2] | 7.2% / 439.6 ms (settled) | – | – | **MISS** (settled) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 246.5 | 604.2 | [467.7,262.1,214.3] | [665.6,300.2,224.6] | 196.4% / 441.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 3333.8 | 7354.2 | [3190.4,2759.0,3853.7] | [7269.3,4310.4,9488.3] | 120.1% / 5177.9 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 2.5 | 4.0 | [2.5,3.2,2.4] | [3.0,5.0,2.9] | 71.4% / 2.1 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.8 | 2.5 | [2.2,1.7,1.7] | [2.6,1.9,2.0] | 40.2% / 0.7 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 2710.1 | 6304.1 | [2964.4,2653.1,2349.4] | [6374.9,5149.9,4470.4] | 42.6% / 1904.4 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 227.8 | 571.7 | [540.6,228.3,225.7] | [577.9,236.1,230.7] | 150.6% / 347.3 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
