# Production-config search benchmark — summary

Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped translator (service `QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = force_custom_plan`), 1000000 things (corpus and seed: `load.md`). Warm: 2026-10-22T12:07:12.080436Z, 3 contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is "settled" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: not run, ? samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size path executes them. Server settings: `environment.md`.

Per-path indexes during the warm run: none (production DDL only).

| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | warm p95 (all rounds) vs 200 ms gate | evidence |
|---|---:|---:|---|---|---|---:|---|:---:|---|
| 1 eq + range AND (city = ?, temperature in [lo, lo+0.99]) | 805.2 | 1286.3 | [848.1,789.2,801.4] | [1291.8,900.3,818.3] | 57.9% / 473.5 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-1-eq-and-range.md` |
| 2 gt range on one named feature property | 109.3 | 162.6 | [146.4,107.2,106.4] | [179.4,117.0,110.1] | 63.0% / 69.3 ms (did not settle) | – | – | PASS (did not settle) | `shape-2-gt-range.md` |
| 5 count with shape 1's filter | 832.0 | 974.1 | [828.0,849.7,827.9] | [946.3,983.7,845.5] | 16.3% / 138.2 ms (did not settle) | – | – | **MISS** (did not settle) | `shape-5-count.md` |
| ce-a rare zip equality + wide temperature range (the point leg must win) | 1.8 | 2.0 | [1.8,1.7,1.7] | [2.0,1.9,1.9] | 6.2% / 0.1 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-a-sudo rare zip equality + wide temperature range, sudo (the point leg must win) | 1.4 | 1.6 | [1.5,1.4,1.4] | [1.6,1.6,1.5] | 6.6% / 0.1 ms (settled) | – | – | PASS (settled) | `ce-leg-choice.md` |
| ce-b city equality + 0.09-wide temperature range (the range leg must win) | 793.5 | 835.3 | [801.8,789.5,793.2] | [815.5,800.3,876.7] | 9.5% / 76.4 ms (settled) | – | – | **MISS** (settled) | `ce-leg-choice.md` |
| ce-c lone two-sided temperature range (previously no CTE) | 124.7 | 159.0 | [135.1,117.7,127.1] | [175.8,124.7,134.9] | 41.0% / 51.1 ms (did not settle) | – | – | PASS (did not settle) | `ce-leg-choice.md` |
| lone-wide lone two-sided temperature range 0..100 (~62 % of the things) | 106.0 | 112.0 | [106.4,107.1,104.8] | [108.7,134.6,108.3] | 24.3% / 26.3 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo lone two-sided temperature range 0..100, sudo | 1.3 | 1.5 | [1.4,1.4,1.3] | [1.6,1.5,1.4] | 10.2% / 0.1 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
| lone-wide-count count with the lone range 0..100 | 67.4 | 79.3 | [75.2,66.2,65.1] | [83.1,72.0,68.5] | 21.3% / 14.6 ms (did not settle) | – | – | PASS (did not settle) | `lone-range.md` |
| lone-wide-sudo-count sudo count with the lone range 0..100 | 5155.1 | 5498.1 | [5207.1,5146.9,5148.4] | [5439.8,5776.2,5446.9] | 6.2% / 336.4 ms (settled) | – | – | **MISS** (settled) | `lone-range.md` |
| lone-mid lone two-sided temperature range of ~40,000 things | 120.3 | 475.3 | [254.3,118.9,118.2] | [495.5,123.8,122.3] | 305.3% / 373.2 ms (did not settle) | – | – | **MISS** (did not settle) | `lone-range.md` |
| lone-mid-sudo lone two-sided temperature range of ~40,000 things, sudo | 4.1 | 5.8 | [4.2,4.1,4.0] | [6.1,5.7,5.6] | 8.9% / 0.5 ms (settled) | – | – | PASS (settled) | `lone-range.md` |
