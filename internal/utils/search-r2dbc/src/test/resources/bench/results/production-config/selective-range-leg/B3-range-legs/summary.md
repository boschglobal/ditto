# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-06T23:33:01.931281Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 2753.9 | 7636.8 | [2740.7,2105.4,3390.2] | [8130.0,6352.6,7568.7] | 28.0% / 1777.4 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 216.2 | 720.7 | [545.4,208.1,212.8] | [754.7,226.7,264.3] | 232.9% / 528.0 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 2750.4 | 6164.2 | [2263.8,2172.9,3949.0] | [5853.2,3800.3,6632.5] | 74.5% / 2832.1 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 2.9 | 3.4 | [3.1,2.7,2.8] | [3.5,3.0,3.1] | 16.5% / 0.5 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 2.4 | 2.9 | [2.4,2.4,2.4] | [2.7,2.9,2.9] | 11.1% / 0.3 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 739.2 | 1662.4 | [867.8,729.8,732.2] | [1833.5,1430.4,789.5] | 132.2% / 1044.0 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 255.5 | 296.0 | [261.7,245.7,258.9] | [305.8,255.8,289.8] | 19.6% / 50.0 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
