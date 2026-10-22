# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T14:39:58.909439Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 112.7 | 123.8 | [117.4,112.4,110.6] | [120.9,133.9,120.3] | 11.3% / 13.6 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 2.2 | 2.8 | [2.4,2.3,2.1] | [2.8,3.0,2.3] | 27.4% / 0.6 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 69.1 | 89.2 | [82.9,67.1,67.0] | [95.2,71.3,69.6] | 36.8% / 25.6 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 9057.9 | 10338.1 | [9153.9,9009.3,8894.1] | [9891.6,10078.7,10338.1] | 4.5% / 446.5 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 193.5 | 397.9 | [301.8,188.9,183.9] | [436.8,219.6,219.8] | 98.9% / 217.2 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 8.2 | 11.6 | [8.6,7.8,8.1] | [11.8,10.0,11.2] | 18.2% / 1.8 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
