# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T12:29:24.714959Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 525.9 | 1379.3 | [665.9,495.0,528.9] | [1516.7,525.9,927.1] | 188.4% / 990.8 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 5 count with shape 1's filter | 490.9 | 550.5 | [487.5,500.7,490.3] | [552.1,550.5,498.2] | 10.8% / 53.9 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
