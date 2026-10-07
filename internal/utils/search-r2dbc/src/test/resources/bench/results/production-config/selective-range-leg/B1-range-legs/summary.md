# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-06T22:53:12.762199Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 1197.9 | 3390.2 | [1172.1,1530.8,1457.0] | [1731.4,6163.7,3377.3] | 256.0% / 4432.3 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 230.5 | 697.6 | [537.4,226.5,216.9] | [753.7,313.4,226.2] | 233.2% / 527.5 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 1495.0 | 5751.1 | [2666.2,1271.8,1164.2] | [5751.1,6013.0,2342.1] | 156.7% / 3670.9 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 3.0 | 4.4 | [3.2,2.7,3.1] | [5.0,4.4,3.5] | 43.4% / 1.5 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 2.3 | 2.7 | [2.3,2.2,2.2] | [2.7,2.7,2.4] | 11.7% / 0.3 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 742.3 | 938.9 | [743.5,710.7,743.7] | [835.3,1211.2,800.5] | 51.3% / 410.7 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 255.7 | 584.0 | [290.4,250.3,246.9] | [659.3,304.0,269.7] | 144.4% / 389.5 ms (did not settle) | – | – | **MISS** (did not settle) | `ce-leg-choice.md` |
