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
