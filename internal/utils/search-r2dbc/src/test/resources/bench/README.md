# Phase-0 things-search-on-PostgreSQL bench harness

This is a **de-risking benchmark spike, not production code**. Everything under
`internal/utils/search-r2dbc/src/test` targets a throwaway `search_things` / `search_flat` schema
(`search-schema.sql` in this directory) used to measure whether a PostgreSQL-backed things-search read/write
path is viable before phases A-H build the real `SearchPersistenceProvider`.

## Starting the bench database

The 1,000,000-thing corpus loaded by `CorpusLoadBench` must survive across bench tasks (0.1 loads it, 0.2/0.3
re-run read/write benchmarks against it) — do **not** use an ephemeral per-test container for it. Start a
long-lived, host-mapped container once:

```
docker run -d --name ditto-search-bench-pg -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench -p 55432:5432 postgres:16
```

Stop/remove it with `docker rm -f ditto-search-bench-pg` once the bench series is done.

## Running the loader

`CorpusLoadBench` is a plain JUnit test class whose name deliberately does **not** match Surefire's
`*Test`/`Test*` or Failsafe's `*IT`/`IT*` include patterns, so `mvn test` / `mvn verify` / `mvn install` never
run it — it must be invoked explicitly:

```
mvn -pl internal/utils/search-r2dbc test -Dtest=CorpusLoadBench -DfailIfNoTests=false \
    -DBENCH_PG_URL=jdbc:postgresql://localhost:55432/bench -DBENCH_PG_USER=bench -DBENCH_PG_PASSWORD=bench \
    -Dbench.count=1000000
```

Connection defaults (used when the properties above are omitted) are `jdbc:postgresql://localhost:55432/bench`
with user/password `bench`/`bench`, matching the `docker run` one-liner above. `bench.count` defaults to
1,000,000 and `bench.seed` defaults to `42` (both are read as JVM system properties, e.g. via `-D`, or as
same-named environment variables for the `BENCH_PG_*` connection settings).

The loader drops and recreates `search_things`/`search_flat` (idempotent), streams the generated corpus
through two `COPY ... FROM STDIN` passes (things, then flat rows — regenerating the same deterministic corpus
for each pass rather than materializing it in memory), builds the B-tree/GIN indexes **after** the bulk load,
`ANALYZE`s both tables, and prints row counts, `pg_relation_size` for every table/index, and elapsed time for
each phase.

## Running the Task 0.2 read-path benchmarks

`ReadPathBench` (the five canonical query shapes) and `PlanShapeComparisonBench` (EXISTS-chain vs semi-join,
2- and 3-predicate variants, at two selectivity mixes) follow the same CI-exclusion naming convention as
`CorpusLoadBench` and target the same long-lived bench DB (the corpus loaded above must be present). Run them
explicitly:

```
mvn -pl internal/utils/search-r2dbc test -Dtest=ReadPathBench -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=PlanShapeComparisonBench -DfailIfNoTests=false
```

(single shapes: `-Dtest=ReadPathBench#shape1EqTwoPredicateAnd` etc. — note `ReadPathBench`'s class-level
summary file is only complete on a full-class run). Each shape performs 3 warmup + 20 timed executions with
varying-but-deterministic binds, captures one representative `EXPLAIN (ANALYZE, BUFFERS)` plan, and rewrites
its committed results file under `src/test/resources/bench/results/` — those files are the durable Phase-0
evidence Task 0.3 synthesizes, so commit refreshed outputs together with whatever change regenerated them.

## Running the Task 0.2b mitigation experiments

`MitigationBench` (same CI-exclusion naming convention) measures how far the plan's own reserved levers recover
the shapes Task 0.2 red-flagged. Run the experiments **one at a time, in order** — each later experiment assumes
the earlier ones' schema/settings changes are still in place:

```
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e0Baseline -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e1StatisticsTarget -DfailIfNoTests=false
# E2: container-level change, see below, then:
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e2MemoryRemeasure -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e3CoveringIndexes -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e4ScopedTrigram -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e5MaterializedCandidatesRescue -DfailIfNoTests=false  # conditional, see Javadoc
```

### Review round 1 fixes (2026-07-04)

Three additional methods, run once each, that correct/extend the committed E1/E4/E5 evidence (see the Task
0.2b report's "Fix report (review round 1)" section for what each fixes and why):

```
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e1WpathAloneEstimates -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e4ScopedTrigramCleanRebuild -DfailIfNoTests=false
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e4ScopedTrigramCleanRebuildSecondPass -DfailIfNoTests=false
# Cold-start probe: restart the container (see below) IMMEDIATELY before EACH of the next two, in order:
docker restart ditto-search-bench-pg   # + wait for readiness (pg_isready)
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e5ColdStartProbeExistsForm -DfailIfNoTests=false
docker restart ditto-search-bench-pg   # + wait for readiness (pg_isready)
mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e5ColdStartProbeCteForm -DfailIfNoTests=false
```

`e1WpathAloneEstimates` and `e4ScopedTrigramCleanRebuild(SecondPass)` are self-contained (no container
restart) and end with the bench DB back in the documented state (`results/mitigations/db-end-state.md`) — the
first restores `val_text`/`val_num` statistics targets to 1000 after isolating the wpath-alone estimate, the
second leaves `sf_trgm_scoped_feature` rebuilt-but-identical (same DDL, same size). The cold-start probe pair
restarts the container (which does NOT touch its data volume, so settings/indexes/stats all survive — see
`bench/README.md`'s "Realistic memory (E2)" section) purely to empty PostgreSQL's own shared-buffer pool
before each form's first execution.

Evidence files land in `results/mitigations/` (committed). The cumulative E0→E5 table lives in the Task 0.2b
report. Note: `MitigationBench` connects with pgjdbc `prepareThreshold=0` (custom plans pinned) — a
documented protocol deviation from `ReadPathBench`; see `results/mitigations/e1-generic-plan-flip-evidence.md`
for the plancache generic-plan flip that forced it (and its production implication for r2dbc statement
caching).

### Realistic memory (E2)

E2 restarts the bench container with laptop-class memory settings. The corpus lives in the container's named
data volume, so recreate the container **reusing that volume** (find its name via
`docker inspect ditto-search-bench-pg --format '{{ (index .Mounts 0).Name }}'`; do NOT pass `-v` to
`docker rm`):

```
VOL=$(docker inspect ditto-search-bench-pg --format '{{ (index .Mounts 0).Name }}')
docker stop ditto-search-bench-pg && docker rm ditto-search-bench-pg
docker run -d --name ditto-search-bench-pg -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench \
    -e POSTGRES_DB=bench -p 55432:5432 -v "$VOL":/var/lib/postgresql/data postgres:16 \
    postgres -c shared_buffers=4GB -c effective_cache_size=12GB -c work_mem=64MB
```

E1's per-column statistics targets survive this restart (they live in the data directory, not in runtime GUCs).

## Production-config mode (shipped schema + shipped translator)

Everything above measures a hand-written-SQL spike; the E1-E5 ladder additionally ran on bench-only extras
(statistics targets, covering indexes, a scoped trigram index, tuned memory — `results/mitigations/db-end-state.md`).
The production-config mode measures what SHIPS instead:

| | production-config mode |
|---|---|
| Schema | The production schema (`ProductionConfigSchema`): `PostgresSearchSchema.ddlStatements()` plus the system index `sf_multi` (`SystemSearchIndex`, search schema v3, which the service builds `CONCURRENTLY` after its bootstrap): tables before the COPY, the production `CREATE INDEX` statements and then `sf_multi` (a plain build, the load is offline) after it, then `VACUUM (ANALYZE)`. A catalog check (`load.md`, `environment.md`; `ProductionConfigSchemaIT`) compares every index definition, column, constraint, reloption, per-column statistics target and extended statistics object with a reference built from the production DDL and `sf_multi` in a rolled-back scratch schema, and fails the run on any difference. A corpus loaded with search schema v2 (no `sf_multi`) therefore fails the check and needs a reload. An operator-configured per-path trigram index (`sf_path_trgm_*`, `ditto.postgresql.search.path-indexes`) is not a difference: it is listed in `environment.md` ("production DDL + N per-path index(es)") and in `summary.md`. |
| Corpus | the original generator, seed and count (`CorpusGenerator`, 42, 1,000,000) and the same COPY loader, so the numbers stay comparable. `BenchCorpusFlattenerParityTest` proves the bench flattener's rows equal the shipped `ThingFlattener`'s, except one known divergence (a `null` array element: bench `type_rank` 1, shipped 5) that no benchmarked shape touches. |
| Queries | RQL requests (`ProductionConfigShapes`) parsed by the query parser the search service builds (`SearchRootActor.getQueryParser`: production simple-field mappings, page-size limits, the PostgreSQL provider's `QueryBuilderFactory`); cursor paging through the service's `ThingsSearchCursor` (`BenchSearchQueryPath`, whose copy of the `SearchActor` flow is pinned against the real `SearchActor` by `BenchSearchQueryPathTest`); SQL rendered by `SearchQueryAssembler` / `CreateSqlVisitor` / `AuthFilterSqlBuilder` — no hand-written SQL. |
| Execution | `PostgresThingsSearchPersistence.findAll` / `count` over a pool built by the production `ConnectionPoolFactory` from the shipped `ditto-postgres-search` profile (`ProductionSearchDb`): `plan_cache_mode = force_custom_plan` as a startup GUC, 60 s `statement_timeout`, unnamed statements, fetch size 100. Only uri/user/password and `ssl.mode = disable` are overridden. |
| Server | its own container `ditto-search-bench-pc` (postgres:16, host port 55433, named volume `ditto-search-bench-pc-data`) with the image-default server settings of the original raw E0 run (no `-c` flags). |
| Protocol | warm: shape by shape, `--rounds` contiguous rounds (default 3), each a warmup over the shape's whole filter rotation (every city / threshold / pattern the timed iterations use: 10 / 7 / 8 iterations, at least 3) followed by 20 timed iterations (the original bench's binds 3..22); nearest-rank p50/p95 per round and over all rounds; a shape is "settled" when its per-round p95s lie within 10% or 10 ms of each other, and "did not settle" otherwise (a statement about the numbers; the cause differs per shape); timed iterations 3..n−1 repeat the warmup's requests (filter and subject pair), and in round 1 iterations ≥ n meet their subject pair for the first time; right after a shape's rounds `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` of its iteration 0 and slowest iteration, then a cross-check against the persistence's own result. Plans run in the timed path's execution mode: finds leader-only (`SET LOCAL max_parallel_workers = 0` — the shipped `findAll`'s fetch size makes PostgreSQL run parallel plans leader-only), counts as they are. Cold: per shape `--cold-samples` (default 3) × (container stop, Docker-VM page cache dropped from a `--privileged` container, start, fresh pool, first execution timed + 2 repeats) and one cold EXPLAIN. The macOS host's own file cache is not dropped (needs root), so cold numbers are "PostgreSQL- and VM-cold, host-cache state unknown". |

One command (from the repo root; OrbStack needs `DOCKER_HOST`, the cold run shells out to `docker`):

```
DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock \
  internal/utils/search-r2dbc/benchmark/run-benchmark.sh production-config --yes
```

or stage by stage, in this order: `pc-up`, `pc-load`, `pc-read` (`--rounds N`), `pc-cold` (`--cold-samples N`),
`pc-bias`; plus `pc-status`, `pc-down [--purge]`.

**Selective legs.** `--range-legs auto|off` (`pc-read`, `pc-cold`, `pc-invariance`; property
`bench.pc.rangeLegs`): `auto` (default) renders and executes every statement as the service does, i.e. with selective
range legs while the system index `sf_multi` is valid (the harness checks it once with the service's
`SystemIndexProbe`); `off` forces point legs only, the statement as before range legs. `environment.md` records which
one ran ("Selective range legs: on (sf_multi valid)", "off (sf_multi missing or invalid)" or "off (forced:
bench.pc.rangeLegs=off)"). `--variants 1,5,2,ce-a,…` (`pc-read`; `bench.pc.variants`) runs only those variants. The
counter-examples of the leg choice are permanent variants in `ce-leg-choice.md`: `ce-a` (a rare zip equality with a
wide temperature range: the point leg must win), `ce-a-sudo` (the same without auth), `ce-b` (shape 1's city with a
0.09-wide temperature range: the range leg must win) and `ce-c` (a lone two-sided temperature range). Stage
`pc-invariance` (`ProductionConfigReadBench#legInvariance`) runs every request of shapes 1, 5 and the counter-examples
over its whole rotation, plus two `attributes/region` array filters, with point legs only and with range legs, and
compares the request's result and the full ordered sudo match list (count + md5): `invariance.md`, and the stage
fails on any difference. **Run `pc-bias` after `pc-cold`, never before:** its read-only counts
read exactly the pages the first cold samples need (the fixed subject pair's `search_things` blocks, Stuttgart's heap,
a full `search_things` scan) and the cold protocol cannot drop the macOS host cache, so running it first would warm
those samples. The corpus stays in the named volume across stages and container restarts, so later sessions
re-run only `pc-read` → `pc-cold` → `pc-bias`. `--results-dir` redirects the evidence (default
`results/production-config/`, relative to the module), e.g. for a re-run next to the baseline. Evidence files:
`load.md`, `environment.md`, `shape-*.md` (request, rendered SQL + binds, warm table per round, per-iteration
latencies, EXPLAIN of iteration 0 and of the slowest iteration),
`cold.md` (cold samples + cold EXPLAINs), `corpus-bias.md` (read-only counts of how the corpus's independently
drawn auth attributes bias the shapes, written by `pc-bias` after `pc-cold`) and `summary.md` (regenerated
from `warm.json` + `cold.json`).

## The Testcontainers smoke test

`PostgresSearchBenchSmokeIT` (note the `IT` suffix — runs under Failsafe) starts its own short-lived
Testcontainers PostgreSQL instance, applies the schema, loads a small (5k-thing) corpus plus one hand-built
edge-case thing, and asserts the flattening rules (feature-leaf dual rows, array-of-array stub, `ord`
enumeration, empty-object row) hold after a real `COPY` round-trip. It self-skips (via `Assume`) if Docker is
unreachable, so it is safe to run in any CI environment.

## Running the Task 0.3 write-path benchmarks

`WriteFanoutBench` (same CI-exclusion naming convention) simulates the production write path — unconditional
doc-row upsert + blanket DELETE/unnest-INSERT flat rewrite (v1), and a v1.5 anti-join delete-changed/
insert-changed alternative — against the same long-lived 1M-thing corpus. Run in order:

```
# Part 0: drop the Task 0.2b E3 covering indexes (rejected negative result) before pricing write cost.
mvn -pl internal/utils/search-r2dbc test -Dtest=WriteFanoutBench#part0DbNormalization -DfailIfNoTests=false

# Part 1: ~10-minute sustained blanket-upsert run (reduced scale; see the bench-results doc for why).
mvn -pl internal/utils/search-r2dbc test -Dtest=WriteFanoutBench#part1SustainedUpsertBlanket -DfailIfNoTests=false \
    -Dbench.write.workers=16

# Part 2: 2x5-minute blanket-vs-anti-join comparison (disjoint pools, same run).
mvn -pl internal/utils/search-r2dbc test -Dtest=WriteFanoutBench#part2AntiJoinVsBlanketComparison -DfailIfNoTests=false \
    -Dbench.write.workers=16
```

All durations/rate/pool-size/worker-count knobs are overridable via `-Dbench.write.*` system properties (see
`BenchConfig`) for a short dry run — e.g. `-Dbench.write.part1Seconds=25 -Dbench.write.sampleIntervalSeconds=10
-Dbench.write.poolSize=200 -Dbench.write.workers=4 -Dbench.write.targetRate=50`. The committed results under
`results/write-bench/` are from the brief's full reduced-scale durations (16 workers, 200 upd/s target, 2,000-
thing pools, 10 min / 2x5 min) — see `docs/superpowers/specs/2026-07-03-postgres-search-bench-results.md` for
the full synthesis and the provisional gate verdict.
