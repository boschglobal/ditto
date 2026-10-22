# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T14:59:11.198706Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 105.9 | 193.2 | [154.3,105.8,103.4] | [206.8,108.1,107.2] | 92.8% / 99.5 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 2.1 | 2.5 | [2.1,2.1,1.9] | [2.5,3.3,2.3] | 41.4% / 1.0 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 67.8 | 272.7 | [225.7,67.8,63.3] | [283.9,70.3,67.3] | 321.7% / 216.6 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 4869.6 | 5013.8 | [4869.7,4865.6,4876.6] | [5188.0,4918.2,4978.4] | 5.5% / 269.8 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 112.2 | 200.8 | [180.6,112.2,111.1] | [214.3,130.2,115.3] | 85.9% / 99.0 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 4.2 | 6.1 | [4.4,3.9,4.4] | [6.0,5.7,6.1] | 5.6% / 0.3 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
