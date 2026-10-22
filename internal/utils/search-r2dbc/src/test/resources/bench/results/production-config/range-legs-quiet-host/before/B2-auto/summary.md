# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T01:12:31.264581Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 509.7 | 607.1 | [527.4,499.5,507.4] | [626.0,528.2,532.2] | 18.5% / 97.7 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 112.0 | 128.3 | [122.3,106.3,110.6] | [131.3,118.3,116.0] | 13.2% / 15.3 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 490.4 | 518.4 | [488.4,486.6,494.4] | [508.2,520.2,516.0] | 2.4% / 12.0 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.9 | 3.0 | [2.0,1.9,1.9] | [2.2,2.2,3.1] | 38.2% / 0.8 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.6 | 1.8 | [1.7,1.6,1.6] | [1.9,1.7,1.7] | 13.5% / 0.2 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 392.2 | 417.7 | [393.1,385.4,393.1] | [417.0,408.4,438.1] | 7.3% / 29.6 ms (settled) | – | – | **MISS** (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 116.4 | 127.7 | [117.1,116.3,116.4] | [130.3,127.7,126.2] | 3.2% / 4.0 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 5752.6 | 5927.0 | [5733.0,5742.1,5771.9] | [5816.5,5949.1,5814.3] | 2.3% / 134.9 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 3253.8 | 3293.1 | [3250.3,3253.8,3252.3] | [3312.2,3283.0,3280.7] | 1.0% / 31.5 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 5734.6 | 5806.5 | [5729.7,5719.8,5741.2] | [5806.5,5779.5,5811.5] | 0.6% / 32.0 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 9222.7 | 9296.1 | [9190.7,9222.7,9231.6] | [9324.8,9295.6,9294.6] | 0.3% / 30.1 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 334.8 | 365.3 | [335.3,334.8,334.2] | [365.3,359.0,368.1] | 2.5% / 9.1 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 139.7 | 161.6 | [135.8,136.3,155.0] | [144.8,147.1,164.7] | 13.7% / 19.8 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
