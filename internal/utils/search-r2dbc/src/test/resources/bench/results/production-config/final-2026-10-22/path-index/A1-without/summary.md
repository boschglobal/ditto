# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T15:08:58.348899Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 3-low infix ilike, low-cardinality attribute | 42.3 | 69.3 | [43.1,41.8,42.0] | [69.7,45.4,43.6] | 59.9% / 26.1 ms (did not settle) | – | – | PASS (did not settle) | `shape-3-ilike.md` |
| 3-high infix ilike, high-cardinality wildcard feature property | 185.1 | 294.2 | [212.3,176.5,177.4] | [324.6,288.0,286.9] | 13.1% / 37.7 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-3-ilike.md` |
