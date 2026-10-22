# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T14:11:47.827225Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 108.9 | 195.9 | [153.1,104.9,108.3] | [207.0,108.9,113.6] | 90.1% / 98.1 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 2.4 | 2.9 | [2.5,2.7,1.9] | [2.9,3.3,2.4] | 39.2% / 0.9 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 78.3 | 280.7 | [262.5,69.2,78.3] | [288.7,73.8,107.1] | 291.3% / 214.9 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 5232.2 | 5809.0 | [5197.3,5170.1,5476.2] | [5539.9,5415.6,6169.0] | 13.9% / 753.5 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 135.7 | 533.7 | [442.6,135.7,123.5] | [553.7,183.2,128.7] | 330.0% / 424.9 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 4.6 | 6.5 | [4.4,4.6,4.6] | [6.2,6.1,6.6] | 7.7% / 0.5 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
