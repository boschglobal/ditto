# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T14:28:47.812379Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 115.9 | 285.3 | [155.2,109.1,113.0] | [463.4,120.6,118.7] | 290.5% / 344.7 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 2.3 | 3.3 | [2.4,2.5,1.9] | [2.8,3.5,2.3] | 49.9% / 1.2 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 82.3 | 226.5 | [185.7,81.9,71.8] | [244.2,97.3,81.1] | 201.0% / 163.1 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 5120.1 | 5717.7 | [5035.1,5238.6,5088.7] | [5196.4,5516.3,5853.2] | 12.6% / 656.8 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 120.3 | 473.0 | [422.3,123.2,113.0] | [520.6,135.6,118.7] | 338.7% / 401.9 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 4.7 | 6.4 | [4.2,4.5,5.0] | [5.9,6.3,6.5] | 10.9% / 0.6 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
