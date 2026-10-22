# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T00:37:15.548941Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 507.8 | 557.0 | [525.8,500.8,503.4] | [586.1,531.3,539.1] | 10.3% / 54.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 111.4 | 141.2 | [126.5,109.0,107.9] | [143.9,118.3,114.2] | 26.1% / 29.7 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 482.7 | 512.2 | [480.2,480.9,483.2] | [506.6,511.6,512.2] | 1.1% / 5.6 ms (settled) | – | – | **MISS** (settled) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.9 | 2.5 | [2.1,1.8,1.9] | [2.9,1.9,2.1] | 53.4% / 1.0 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.6 | 1.8 | [1.6,1.6,1.6] | [1.8,1.8,1.8] | 0.4% / 0.0 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 392.2 | 439.2 | [393.7,390.4,389.1] | [449.0,417.6,417.8] | 7.5% / 31.5 ms (settled) | – | – | **MISS** (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 115.7 | 128.3 | [115.7,115.2,114.3] | [128.1,125.7,128.3] | 2.1% / 2.6 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 5701.3 | 5781.9 | [5695.3,5713.7,5697.2] | [5781.9,5758.0,5778.5] | 0.4% / 23.9 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 3248.9 | 3309.0 | [3249.9,3250.6,3245.7] | [3284.5,3309.0,3259.8] | 1.5% / 49.2 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 5747.8 | 5801.1 | [5739.6,5751.2,5741.1] | [5791.8,5818.8,5798.6] | 0.5% / 27.0 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 9235.6 | 9348.1 | [9228.6,9231.0,9246.0] | [9647.1,9285.8,9346.9] | 3.9% / 361.3 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 336.6 | 367.4 | [337.0,335.1,336.2] | [358.5,367.9,364.1] | 2.6% / 9.4 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 137.2 | 147.7 | [135.4,137.3,136.5] | [143.9,147.7,147.6] | 2.7% / 3.8 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
