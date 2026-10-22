# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T11:26:56.710026Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 985.9 | 1341.4 | [985.9,1046.1,864.3] | [1341.4,1274.5,1208.9] | 11.0% / 132.6 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 110.2 | 415.2 | [276.6,108.7,107.3] | [453.5,111.7,110.8] | 309.4% / 342.7 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 835.4 | 1258.6 | [835.4,811.6,929.8] | [4302.6,828.7,1050.9] | 419.2% / 3473.9 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 2.0 | 2.4 | [2.2,2.0,1.9] | [2.6,2.2,2.3] | 16.4% / 0.4 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.5 | 1.7 | [1.5,1.4,1.5] | [1.7,1.6,1.6] | 5.2% / 0.1 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 821.9 | 868.4 | [825.9,820.2,821.8] | [882.3,856.6,858.7] | 3.0% / 25.7 ms (settled) | – | – | **MISS** (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 120.2 | 271.0 | [150.3,119.4,116.7] | [334.3,131.6,124.1] | 169.3% / 210.1 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 107.1 | 114.7 | [107.3,106.6,105.9] | [114.7,114.4,113.3] | 1.2% / 1.4 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 1.4 | 1.9 | [1.4,1.3,1.3] | [1.6,1.4,2.2] | 57.9% / 0.8 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 67.0 | 75.2 | [68.6,66.3,66.9] | [83.8,71.0,70.5] | 19.0% / 13.4 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 5294.5 | 5732.5 | [5288.7,5283.0,5310.1] | [5547.7,5571.6,5929.6] | 6.9% / 382.0 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 126.4 | 495.0 | [439.6,124.7,126.1] | [512.1,132.5,139.5] | 286.4% / 379.6 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 4.7 | 6.1 | [4.8,4.8,4.5] | [6.0,6.2,5.7] | 8.4% / 0.5 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
