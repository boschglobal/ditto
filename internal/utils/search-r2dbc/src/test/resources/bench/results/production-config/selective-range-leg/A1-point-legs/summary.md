# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-06T22:38:43.161401Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 2263.2 | 7033.5 | [3142.7,1877.3,2055.5] | [8119.0,3337.7,3287.2] | 147.0% / 4831.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 212.6 | 564.3 | [411.1,208.9,209.1] | [642.3,214.7,231.0] | 199.2% / 427.7 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 1878.0 | 3099.9 | [1945.4,2031.1,1684.2] | [2681.2,4541.2,2421.7] | 87.5% / 2119.5 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 2.4 | 2.8 | [2.5,2.3,2.4] | [2.8,2.5,3.3] | 29.7% / 0.8 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.8 | 2.1 | [1.9,1.9,1.7] | [2.2,2.1,1.8] | 21.0% / 0.4 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 1719.8 | 2875.7 | [1718.6,1703.8,1757.9] | [3957.4,2562.8,2285.1] | 73.2% / 1672.3 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 239.0 | 685.1 | [632.3,228.7,222.2] | [734.0,294.3,278.4] | 163.7% / 455.6 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
