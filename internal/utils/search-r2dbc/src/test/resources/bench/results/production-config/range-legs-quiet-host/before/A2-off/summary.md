# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T01:47:53.584859Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 831.2 | 1052.8 | [821.8,852.3,827.4] | [940.6,1089.4,905.6] | 20.3% / 183.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 109.3 | 124.9 | [113.9,109.1,106.8] | [127.7,121.5,114.3] | 11.8% / 13.4 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 824.5 | 864.8 | [824.5,821.7,828.3] | [913.6,864.8,857.6] | 6.5% / 56.0 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.8 | 2.3 | [1.9,1.6,1.8] | [2.4,1.7,2.3] | 40.2% / 0.7 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.4 | 1.6 | [1.5,1.4,1.3] | [1.7,1.5,1.5] | 11.4% / 0.2 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 806.0 | 841.9 | [796.7,807.5,806.0] | [836.6,841.9,842.8] | 0.7% / 6.3 ms (settled) | – | – | **MISS** (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 115.5 | 199.3 | [127.6,114.7,114.2] | [223.6,129.4,122.3] | 82.8% / 101.3 ms (did not settle) | – | – | PASS (did not settle) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 105.7 | 113.6 | [105.0,106.8,104.8] | [113.6,112.4,113.8] | 1.3% / 1.4 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 1.3 | 1.5 | [1.3,1.3,1.3] | [1.6,1.3,1.4] | 16.8% / 0.2 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 64.8 | 72.2 | [64.8,64.9,64.4] | [72.2,70.2,71.2] | 2.8% / 2.0 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 6000.9 | 6186.8 | [6001.8,5996.0,5999.8] | [6122.9,6288.9,6060.5] | 3.8% / 228.4 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 117.8 | 147.4 | [139.9,117.2,117.5] | [151.4,125.0,122.3] | 23.8% / 29.1 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 3.8 | 5.0 | [3.9,3.7,3.5] | [5.0,4.9,4.7] | 5.9% / 0.3 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
