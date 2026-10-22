# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T11:43:49.988361Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 125.7 | 159.8 | [137.4,122.6,121.8] | [178.7,136.5,134.6] | 32.8% / 44.1 ms (did not settle) | – | – | PASS (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 110.3 | 177.2 | [143.0,108.0,108.2] | [192.8,113.7,116.0] | 69.5% / 79.0 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 122.8 | 135.4 | [121.8,123.3,123.7] | [135.0,133.5,137.8] | 3.2% / 4.3 ms (settled) | – | – | PASS (settled) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.9 | 2.3 | [2.1,1.9,1.8] | [2.2,2.3,2.0] | 15.4% / 0.3 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.6 | 1.9 | [1.6,1.6,1.5] | [1.9,1.9,1.7] | 12.0% / 0.2 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 17.4 | 18.8 | [17.0,18.2,16.6] | [17.5,18.9,17.6] | 7.9% / 1.4 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 116.7 | 148.9 | [116.5,118.6,115.0] | [156.3,125.3,123.0] | 27.1% / 33.3 ms (did not settle) | – | – | PASS (did not settle) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 105.8 | 114.8 | [106.1,105.6,105.8] | [113.3,114.9,113.8] | 1.4% / 1.6 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 1.2 | 1.5 | [1.4,1.2,1.1] | [1.6,1.3,1.3] | 18.2% / 0.2 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 68.4 | 130.1 | [104.1,67.4,65.4] | [135.7,72.8,71.4] | 90.0% / 64.3 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 5097.1 | 5517.3 | [5077.9,5095.5,5100.3] | [5553.1,5434.5,5407.1] | 2.7% / 146.0 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 123.9 | 448.0 | [377.3,123.4,118.0] | [473.9,132.1,126.1] | 275.9% / 347.8 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 3.9 | 5.6 | [3.8,3.9,3.8] | [5.6,5.6,5.5] | 3.0% / 0.2 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
