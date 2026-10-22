# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T12:23:20.782103Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| ce-c lone two-sided temperature range (previously no CTE) | 127.3 | 318.5 | [165.5,130.1,118.4] | [323.7,147.9,127.2] | 154.6% / 196.6 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| lone-wide-count count with the lone range 0..100 | 67.6 | 71.5 | [68.5,66.3,68.4] | [72.1,71.3,71.3] | 1.1% / 0.8 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
