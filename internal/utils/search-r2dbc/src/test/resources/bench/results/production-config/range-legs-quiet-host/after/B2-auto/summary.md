# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T11:55:43.199328Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 124.7 | 165.2 | [135.8,122.2,120.8] | [169.8,145.5,135.3] | 25.5% / 34.5 ms (did not settle) | – | – | PASS (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 110.3 | 163.9 | [140.5,107.9,105.4] | [175.9,121.1,115.1] | 52.8% / 60.8 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 119.6 | 132.2 | [120.2,119.1,119.5] | [129.0,130.8,134.0] | 3.8% / 5.0 ms (settled) | – | – | PASS (settled) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.9 | 2.3 | [2.0,1.9,1.8] | [2.4,2.2,2.0] | 19.9% / 0.4 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.6 | 2.0 | [1.8,1.6,1.6] | [2.8,1.8,1.7] | 62.2% / 1.1 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 16.8 | 18.3 | [17.9,16.6,16.7] | [18.5,17.8,17.7] | 4.4% / 0.8 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 115.0 | 147.3 | [124.5,113.9,114.8] | [151.7,123.5,123.1] | 23.3% / 28.6 ms (did not settle) | – | – | PASS (did not settle) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 104.8 | 114.0 | [104.8,104.3,104.6] | [113.3,114.0,113.1] | 0.8% / 0.9 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 1.2 | 1.4 | [1.3,1.2,1.2] | [1.5,1.3,1.3] | 18.2% / 0.2 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 68.5 | 83.5 | [77.5,67.6,64.3] | [92.1,74.3,68.4] | 34.6% / 23.7 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 5128.2 | 5719.8 | [5118.0,5132.1,5173.1] | [5461.1,6469.8,5599.9] | 18.5% / 1008.7 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 116.1 | 510.4 | [457.0,115.6,113.9] | [545.2,120.2,118.6] | 359.6% / 426.6 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 4.0 | 5.8 | [3.7,4.1,3.8] | [5.9,5.8,5.1] | 14.0% / 0.7 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
