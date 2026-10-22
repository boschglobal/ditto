# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T00:21:40.978758Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 830.6 | 1039.2 | [855.0,846.9,796.1] | [1074.0,1029.1,844.7] | 27.1% / 229.3 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 110.0 | 349.0 | [258.2,106.7,107.0] | [372.7,119.6,115.6] | 222.3% / 257.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 814.2 | 847.4 | [808.8,806.7,814.2] | [847.1,841.4,847.4] | 0.7% / 6.0 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.8 | 2.0 | [1.8,1.6,1.8] | [2.1,1.9,2.0] | 13.1% / 0.2 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.5 | 1.7 | [1.5,1.5,1.4] | [1.7,1.7,1.6] | 6.2% / 0.1 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 802.4 | 847.0 | [801.3,796.9,810.2] | [841.0,841.1,873.1] | 3.8% / 32.1 ms (settled) | – | – | **MISS** (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 116.9 | 218.5 | [128.4,114.5,115.3] | [265.5,120.8,119.5] | 122.2% / 146.0 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 104.2 | 110.6 | [103.8,103.5,104.5] | [110.6,112.5,109.6] | 2.7% / 2.9 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 1.2 | 1.4 | [1.3,1.2,1.2] | [1.6,1.3,1.3] | 27.1% / 0.3 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 65.5 | 70.1 | [66.4,64.0,64.3] | [70.4,69.4,68.0] | 3.6% / 2.5 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 6006.9 | 6108.7 | [6014.8,5997.8,6002.0] | [6109.7,6107.4,6084.6] | 0.4% / 25.2 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 116.7 | 156.8 | [133.4,115.9,115.3] | [160.4,124.8,123.5] | 29.9% / 36.9 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 3.7 | 4.9 | [3.8,3.7,3.7] | [5.1,4.9,4.7] | 8.1% / 0.4 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
