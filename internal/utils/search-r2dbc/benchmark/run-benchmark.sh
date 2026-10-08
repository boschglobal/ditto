#!/usr/bin/env bash
#
# Copyright (c) 2026 Contributors to the Eclipse Foundation
#
# See the NOTICE file(s) distributed with this work for additional
# information regarding copyright ownership.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0
#
# SPDX-License-Identifier: EPL-2.0
#
# Orchestrates the Phase-0 things-search-on-PostgreSQL benchmark series end to end.
# See README.md in this directory for methodology, results, and caveats, and
# src/test/resources/bench/README.md for the per-stage details this script automates.
#
# Usage:
#   run-benchmark.sh <stage...> [options]
#
# Stages (in dependency order):
#   up           start the long-lived bench PostgreSQL container (default settings)
#   build        mvn install -DskipTests the bench module + its reactor dependencies
#   load         CorpusLoadBench  — generate + COPY the corpus, build indexes, ANALYZE
#   smoke        PostgresSearchBenchSmokeIT — flattening-rule check on Testcontainers (independent)
#   read         ReadPathBench + PlanShapeComparisonBench (raw read-path numbers)
#   mitigations  MitigationBench E0..E5 in order (automates the E2 container recreate)
#   fixround     optional evidence-fix methods + cold-start probe pair (container restarts)
#   write        WriteFanoutBench part0 -> part1 -> part2
#   all          up load read mitigations write (asks for confirmation first)
#   status       show container / corpus / result-file state
#   down         remove the container (add --purge to also delete the data volume)
#
# Production-config stages (shipped schema + shipped translator; own container, see README.md):
#   pc-up        start the production-config container (postgres:16, image-default server settings,
#                named data volume)
#   pc-load      ProductionConfigLoadBench: same corpus, schema = PostgresSearchSchema.ddlStatements() + sf_multi
#   pc-read      ProductionConfigReadBench#warm: shapes 1-5 via the service parser + SearchQueryAssembler +
#                PostgresThingsSearchPersistence; warm rounds + EXPLAIN (ANALYZE, BUFFERS)
#   pc-cold      ProductionConfigReadBench#cold: per shape, container stop + Docker-VM page-cache drop +
#                start, first execution timed (+ one cold EXPLAIN)
#   pc-bias      ProductionConfigReadBench#corpusBias: read-only corpus-bias counts; run it AFTER pc-cold
#                (it reads the pages the first cold samples need, and the host cache is never dropped)
#   pc-invariance  ProductionConfigReadBench#legInvariance: the requests with a two-sided range, with point legs
#                only and with range legs, must return the same results (invariance.md; fails on a difference)
#   production-config   pc-up pc-load pc-read pc-cold pc-bias (asks for confirmation first)
#   pc-status    show the production-config container / corpus state
#   pc-down      remove the production-config container (add --purge to also delete its data volume)
#
# Production write-path soak stages (shipped write flow, text-heavy twin; own container, see README.md):
#   soak-up      start the soak container (postgres:16 + BENCH_SOAK_PG_ARGS, data volume or host path,
#                port bound to 127.0.0.1); nothing to start when BENCH_SOAK_URL names an external database
#   soak-load    WriteSoakBench#load: production DDL, COPY of the twin corpus exactly as the shipped writer stores
#                it (chunked; --load-from N resumes), production indexes + sf_multi, VACUUM (ANALYZE), catalog check
#   soak-run     WriteSoakBench#soak: warm-up + gated soak through PostgresSearchUpdaterFlow at --rate;
#                windows, vacuum cycles, throughput-gate and bloat-plateau verdicts (summary.md)
#   soak-status  show the soak container / corpus state
#   soak-down    remove the soak container (add --purge to also delete its named data volume)
#
# Options:
#   --count N    corpus size for `load` / `pc-load` (default 1000000)
#   --seed N     corpus seed (default 42)
#   --quick      demo mode: 50k corpus + short write windows (numbers NOT comparable)
#   --yes        skip the duration/disk confirmation prompt
#   --purge      with `down` / `pc-down`: also remove the data volume
#   --rounds N   pc-read: warm rounds (default 3; each = every shape, 3 warmup + 20 timed)
#   --cold-samples N    pc-cold: cold first executions per shape (default 3)
#   --results-dir DIR   pc-* / soak-*: evidence folder, relative to the module or absolute
#                (defaults src/test/resources/bench/results/production-config and .../write-bench/10m-soak:
#                the committed evidence; pass a scratch folder for trial runs)
#   --range-legs MODE   pc-read/pc-cold/pc-invariance: selective range legs: auto (default; as the
#                service: while the system index sf_multi is valid) | off (point legs only)
#   --variants LIST     pc-read: comma list of variant ids to run (default: all), e.g. 1,5,2,ce-a,ce-a-sudo,ce-b,ce-c
#   --soak-seconds N    soak-run: gated period (default 3600; the gate needs >= 3600)
#   --warmup-seconds N  soak-run: warm-up before the gated period, reported but not gated (default 300)
#   --plateau-seconds N soak-run: trailing part of the gated period for the plateau verdict (default 3600)
#   --rate R            soak-run: offered updates/s (default 220)
#   --gate-rate R       soak-run: rate every gated window must reach (default 200)
#   --workers N         soak-run: concurrent writers, at most the shipped pool max-size 25 (default 24)
#   --load-from N       soak-load: resume an interrupted load at corpus index N (default 0 = drop and reload)
#   --maintenance-work-mem V   soak-load: session maintenance_work_mem for the index builds, e.g. 4GB
#
# Environment:
#   MVN          maven binary (default: mvn on PATH); extra Maven flags via MAVEN_ARGS (e.g. MAVEN_ARGS=-o)
#   BENCH_PORT   host port for the bench DB (default 55432)
#   BENCH_CONTAINER  container name (default ditto-search-bench-pg)
#   BENCH_PC_PORT / BENCH_PC_CONTAINER / BENCH_PC_VOLUME   production-config container
#                (defaults 55433 / ditto-search-bench-pc / ditto-search-bench-pc-data)
#   BENCH_SOAK_PORT / BENCH_SOAK_CONTAINER / BENCH_SOAK_VOLUME   soak container (defaults 55434 /
#                ditto-search-bench-soak / ditto-search-bench-soak-data; the volume may be an absolute host path,
#                e.g. an empty sub-directory of a mounted gp3 volume)
#   BENCH_SOAK_PG_ARGS  postgres flags for the soak container, e.g. "-c shared_buffers=16GB -c max_wal_size=16GB"
#   BENCH_SOAK_SHM      shared memory of the soak container (default 1g; keep it above shared_buffers)
#   BENCH_SOAK_URL / BENCH_SOAK_USER / BENCH_SOAK_PASSWORD   an external soak database (JDBC URL; no container)
#   DOCKER_HOST  honoured by docker and by the cold-run harness
#                (OrbStack: unix://$HOME/.orbstack/run/docker.sock)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
MODULE="internal/utils/search-r2dbc"

MVN="${MVN:-mvn}"
CONTAINER="${BENCH_CONTAINER:-ditto-search-bench-pg}"
PORT="${BENCH_PORT:-55432}"
IMAGE="postgres:16"

PC_CONTAINER="${BENCH_PC_CONTAINER:-ditto-search-bench-pc}"
PC_PORT="${BENCH_PC_PORT:-55433}"
PC_VOLUME="${BENCH_PC_VOLUME:-ditto-search-bench-pc-data}"

SOAK_CONTAINER="${BENCH_SOAK_CONTAINER:-ditto-search-bench-soak}"
SOAK_PORT="${BENCH_SOAK_PORT:-55434}"
SOAK_VOLUME="${BENCH_SOAK_VOLUME:-ditto-search-bench-soak-data}"
SOAK_PG_ARGS="${BENCH_SOAK_PG_ARGS:-}"
SOAK_SHM="${BENCH_SOAK_SHM:-1g}"
SOAK_URL="${BENCH_SOAK_URL:-}"

COUNT=1000000
SEED=42
PC_ROUNDS=3
PC_COLD_SAMPLES=3
PC_RESULTS_DIR=""
PC_RANGE_LEGS=auto
PC_VARIANTS=""
SOAK_SECONDS=""
SOAK_WARMUP=""
SOAK_PLATEAU=""
SOAK_RATE=""
SOAK_GATE_RATE=""
SOAK_WORKERS=""
SOAK_LOAD_FROM=""
SOAK_MWM=""
QUICK=0
YES=0
PURGE=0
STAGES=()

# E2 "realistic memory" settings — applied from the mitigations stage onward.
PG_TUNED_ARGS=(-c shared_buffers=4GB -c effective_cache_size=12GB -c work_mem=64MB)

log()  { printf '\n== %s ==\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --count) COUNT="$2"; shift 2 ;;
        --seed)  SEED="$2";  shift 2 ;;
        --quick) QUICK=1;    shift ;;
        --yes)   YES=1;      shift ;;
        --purge) PURGE=1;    shift ;;
        --rounds) PC_ROUNDS="$2"; shift 2 ;;
        --cold-samples) PC_COLD_SAMPLES="$2"; shift 2 ;;
        --results-dir) PC_RESULTS_DIR="$2"; shift 2 ;;
        --range-legs) PC_RANGE_LEGS="$2"; shift 2 ;;   # pc-read/pc-cold/pc-invariance: auto (default) | off
        --variants) PC_VARIANTS="$2"; shift 2 ;;       # pc-read: comma list of variant ids (default: all)
        --soak-seconds) SOAK_SECONDS="$2"; shift 2 ;;
        --warmup-seconds) SOAK_WARMUP="$2"; shift 2 ;;
        --plateau-seconds) SOAK_PLATEAU="$2"; shift 2 ;;
        --rate) SOAK_RATE="$2"; shift 2 ;;
        --gate-rate) SOAK_GATE_RATE="$2"; shift 2 ;;
        --workers) SOAK_WORKERS="$2"; shift 2 ;;
        --load-from) SOAK_LOAD_FROM="$2"; shift 2 ;;
        --maintenance-work-mem) SOAK_MWM="$2"; shift 2 ;;
        -h|--help) sed -n '15,96p' "${BASH_SOURCE[0]}"; exit 0 ;;
        -*) die "unknown option: $1" ;;
        *) STAGES+=("$1"); shift ;;
    esac
done
[ ${#STAGES[@]} -gt 0 ] || { sed -n '15,96p' "${BASH_SOURCE[0]}"; exit 1; }

if [ "$QUICK" = 1 ] && [ "$COUNT" = 1000000 ]; then
    COUNT=50000
fi

# every stage but `build` needs docker, except the soak stages against an external database (BENCH_SOAK_URL)
NEEDS_DOCKER=0
for stage in "${STAGES[@]}"; do
    case "$stage" in
        build) ;;
        soak-*) [ -n "$SOAK_URL" ] || NEEDS_DOCKER=1 ;;
        *) NEEDS_DOCKER=1 ;;
    esac
done
if [ "$NEEDS_DOCKER" = 1 ]; then
    command -v docker >/dev/null || die "docker not found on PATH"
fi
command -v "$MVN" >/dev/null || die "maven not found (set MVN=/path/to/mvn)"

# Connection coordinates: BenchConfig reads these as env vars (sysprop fallback),
# so exporting them reaches the forked surefire JVM regardless of property plumbing.
export BENCH_PG_URL="jdbc:postgresql://localhost:${PORT}/bench"
export BENCH_PG_USER="bench"
export BENCH_PG_PASSWORD="bench"

container_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; }
container_running() { docker ps    --format '{{.Names}}' | grep -qx "$CONTAINER"; }

wait_ready() {
    local i
    for i in $(seq 1 60); do
        if docker exec "$CONTAINER" pg_isready -U bench -d bench >/dev/null 2>&1; then
            sleep 1  # pg_isready can answer during a crash-recovery restart window
            docker exec "$CONTAINER" pg_isready -U bench -d bench >/dev/null 2>&1 && return 0
        fi
        sleep 1
    done
    die "bench database did not become ready within 60s"
}

start_container() { # args: extra postgres -c flags (may be empty)
    docker run -d --name "$CONTAINER" \
        -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
        -p "${PORT}:5432" "${@:2}" "$IMAGE" ${1:+postgres $1} >/dev/null
}

run_bench() { # args: -Dtest spec, extra -D props...
    local spec="$1"; shift
    log "mvn -Dtest=${spec}"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" test -Dtest="$spec" -DfailIfNoTests=false "$@")
}

stage_up() {
    if container_running; then
        log "container ${CONTAINER} already running"
    elif container_exists; then
        log "starting existing container ${CONTAINER}"
        docker start "$CONTAINER" >/dev/null
    else
        log "creating container ${CONTAINER} (postgres:16, port ${PORT}, default settings)"
        start_container ""
    fi
    wait_ready
    log "bench DB ready on localhost:${PORT}"
}

# Recreate the container with the E2 memory settings, REUSING the data volume so the
# loaded corpus (and E1's persisted statistics targets) survive.
recreate_tuned() {
    container_exists || die "container ${CONTAINER} does not exist — run 'up' + 'load' first"
    local vol
    vol=$(docker inspect "$CONTAINER" --format '{{ (index .Mounts 0).Name }}')
    [ -n "$vol" ] || die "could not determine data volume of ${CONTAINER}"
    log "recreating ${CONTAINER} with E2 memory settings (volume ${vol} preserved)"
    docker stop "$CONTAINER" >/dev/null && docker rm "$CONTAINER" >/dev/null
    docker run -d --name "$CONTAINER" \
        -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
        -p "${PORT}:5432" -v "${vol}:/var/lib/postgresql/data" \
        "$IMAGE" postgres "${PG_TUNED_ARGS[@]}" >/dev/null
    wait_ready
}

restart_for_cold_start() {
    log "restarting ${CONTAINER} to empty PostgreSQL shared buffers"
    docker restart "$CONTAINER" >/dev/null
    wait_ready
}

stage_build() {
    log "building ${MODULE} + reactor dependencies (guards against stale sibling-worktree snapshots in ~/.m2)"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" -am install -DskipTests -q)
}

stage_load() {
    stage_up
    log "loading corpus: count=${COUNT} seed=${SEED} (drops + recreates the bench schema)"
    run_bench CorpusLoadBench -Dbench.count="$COUNT" -Dbench.seed="$SEED"
}

stage_smoke() {
    log "Testcontainers smoke test (independent of the bench DB; self-skips without Docker)"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" verify \
        -Dit.test=PostgresSearchBenchSmokeIT -Dtest=FlattenerTest -DfailIfNoTests=false)
}

stage_read() {
    stage_up
    run_bench ReadPathBench
    run_bench PlanShapeComparisonBench
}

stage_mitigations() {
    stage_up
    # Order matters: each experiment assumes the previous ones' schema/settings changes.
    run_bench 'MitigationBench#e0Baseline'
    run_bench 'MitigationBench#e1StatisticsTarget'
    recreate_tuned    # E2 is a container-level change
    run_bench 'MitigationBench#e2MemoryRemeasure'
    run_bench 'MitigationBench#e3CoveringIndexes'
    run_bench 'MitigationBench#e4ScopedTrigram'
    run_bench 'MitigationBench#e5MaterializedCandidatesRescue'
}

stage_fixround() {
    stage_up
    run_bench 'MitigationBench#e1WpathAloneEstimates'
    run_bench 'MitigationBench#e4ScopedTrigramCleanRebuild'
    run_bench 'MitigationBench#e4ScopedTrigramCleanRebuildSecondPass'
    restart_for_cold_start
    run_bench 'MitigationBench#e5ColdStartProbeExistsForm'
    restart_for_cold_start
    run_bench 'MitigationBench#e5ColdStartProbeCteForm'
}

stage_write() {
    stage_up
    local props=(-Dbench.write.workers=16)
    if [ "$QUICK" = 1 ]; then
        props=(-Dbench.write.part1Seconds=25 -Dbench.write.part2PhaseSeconds=25
               -Dbench.write.sampleIntervalSeconds=10 -Dbench.write.poolSize=200
               -Dbench.write.workers=4 -Dbench.write.targetRate=50)
    fi
    run_bench 'WriteFanoutBench#part0DbNormalization'
    run_bench 'WriteFanoutBench#part1SustainedUpsertBlanket' "${props[@]}"
    run_bench 'WriteFanoutBench#part2AntiJoinVsBlanketComparison' "${props[@]}"
}

stage_status() {
    if container_running; then
        echo "container: ${CONTAINER} RUNNING (port ${PORT})"
        docker exec "$CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'things: '  || count(*) FROM search_things" 2>/dev/null || echo "schema: not loaded"
        docker exec "$CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'flat rows: ' || count(*) FROM search_flat" 2>/dev/null || true
    elif container_exists; then
        echo "container: ${CONTAINER} exists but is STOPPED"
    else
        echo "container: ${CONTAINER} does not exist"
    fi
    echo "results:   ${ROOT}/${MODULE}/src/test/resources/bench/results/ (diff against git to compare runs)"
}

stage_down() {
    if container_exists; then
        local vol=""
        [ "$PURGE" = 1 ] && vol=$(docker inspect "$CONTAINER" --format '{{ (index .Mounts 0).Name }}')
        docker rm -f "$CONTAINER" >/dev/null
        echo "removed container ${CONTAINER}"
        if [ "$PURGE" = 1 ] && [ -n "$vol" ]; then
            docker volume rm "$vol" >/dev/null && echo "removed volume ${vol}"
        fi
    else
        echo "container ${CONTAINER} does not exist"
    fi
}

# --- production-config mode (shipped schema + shipped translator) ----------------------------------------------

pc_env() {
    export BENCH_PG_URL="jdbc:postgresql://localhost:${PC_PORT}/bench"
    export BENCH_PG_CONTAINER="$PC_CONTAINER"
    export BENCH_PG_IMAGE="$IMAGE"
}

pc_run_bench() { # args: -Dtest spec, extra -D props...
    local spec="$1"; shift
    local props=("$@" -Dbench.pc.rangeLegs="$PC_RANGE_LEGS")
    if [ -n "$PC_VARIANTS" ]; then
        props+=(-Dbench.pc.variants="$PC_VARIANTS")
    fi
    if [ -n "$PC_RESULTS_DIR" ]; then
        run_bench "$spec" "${props[@]}" -Dbench.pc.resultsDir="$PC_RESULTS_DIR"
    else
        run_bench "$spec" "${props[@]}"
    fi
}

pc_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$PC_CONTAINER"; }
pc_running() { docker ps    --format '{{.Names}}' | grep -qx "$PC_CONTAINER"; }

stage_pc_up() {
    if pc_running; then
        log "container ${PC_CONTAINER} already running"
    elif pc_exists; then
        log "starting existing container ${PC_CONTAINER}"
        docker start "$PC_CONTAINER" >/dev/null
    else
        # Server settings: the postgres:16 image defaults (shared_buffers=128MB, effective_cache_size=4GB,
        # work_mem=4MB, ...), i.e. the settings of the original raw E0 run, for comparability. No -c flags.
        log "creating container ${PC_CONTAINER} (postgres:16, port ${PC_PORT}, volume ${PC_VOLUME}, image-default settings)"
        docker run -d --name "$PC_CONTAINER" \
            -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
            -p "${PC_PORT}:5432" -v "${PC_VOLUME}:/var/lib/postgresql/data" "$IMAGE" >/dev/null
    fi
    local saved="$CONTAINER"
    CONTAINER="$PC_CONTAINER"; wait_ready; CONTAINER="$saved"
    log "production-config bench DB ready on localhost:${PC_PORT}"
}

stage_pc_load() {
    stage_pc_up; pc_env
    log "production-config load: count=${COUNT} seed=${SEED} (drops + recreates the search tables from the production DDL)"
    pc_run_bench ProductionConfigLoadBench -Dbench.count="$COUNT" -Dbench.seed="$SEED"
}

stage_pc_read() {
    stage_pc_up; pc_env
    pc_run_bench 'ProductionConfigReadBench#warm' -Dbench.pc.rounds="$PC_ROUNDS"
}

stage_pc_cold() {
    stage_pc_up; pc_env
    pc_run_bench 'ProductionConfigReadBench#cold' -Dbench.pc.coldSamples="$PC_COLD_SAMPLES"
}

# Read-only corpus-bias counts. Runs after pc-cold: it reads exactly the pages the first cold samples need (the
# fixed subject pair's search_things blocks, Stuttgart's heap, a full search_things scan), and the cold protocol
# cannot drop the macOS host cache, so running it before pc-cold would warm those samples.
stage_pc_bias() {
    stage_pc_up; pc_env
    pc_run_bench 'ProductionConfigReadBench#corpusBias'
}

# Selective-leg invariance: every request with a two-sided range, rendered with point legs only and with range legs,
# must return the same results (writes invariance.md, fails on a difference).
stage_pc_invariance() {
    stage_pc_up; pc_env
    pc_run_bench 'ProductionConfigReadBench#legInvariance'
}

stage_pc_status() {
    if pc_running; then
        echo "container: ${PC_CONTAINER} RUNNING (port ${PC_PORT}, volume ${PC_VOLUME})"
        docker exec "$PC_CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'things: ' || count(*) FROM search_things" 2>/dev/null || echo "schema: not loaded"
        docker exec "$PC_CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'database: ' || pg_size_pretty(pg_database_size('bench'))" 2>/dev/null || true
    elif pc_exists; then
        echo "container: ${PC_CONTAINER} exists but is STOPPED"
    else
        echo "container: ${PC_CONTAINER} does not exist"
    fi
}

stage_pc_down() {
    if pc_exists; then
        docker rm -f "$PC_CONTAINER" >/dev/null
        echo "removed container ${PC_CONTAINER}"
    else
        echo "container ${PC_CONTAINER} does not exist"
    fi
    if [ "$PURGE" = 1 ]; then
        if docker volume inspect "$PC_VOLUME" >/dev/null 2>&1; then
            docker volume rm "$PC_VOLUME" >/dev/null || die "could not remove volume ${PC_VOLUME}"
            echo "removed volume ${PC_VOLUME}"
        else
            echo "volume ${PC_VOLUME} does not exist"
        fi
    fi
}

# --- production write-path soak (shipped write flow, text-heavy twin) --------------------------------------------

soak_env() {
    export BENCH_PG_URL="${SOAK_URL:-jdbc:postgresql://localhost:${SOAK_PORT}/bench}"
    export BENCH_PG_USER="${BENCH_SOAK_USER:-bench}"
    export BENCH_PG_PASSWORD="${BENCH_SOAK_PASSWORD:-bench}"
}

soak_run_bench() { # args: -Dtest spec
    local spec="$1"
    local props=(-Dbench.count="$COUNT" -Dbench.seed="$SEED")
    if [ -n "$PC_RESULTS_DIR" ]; then props+=(-Dbench.soak.resultsDir="$PC_RESULTS_DIR"); fi
    if [ -n "$SOAK_SECONDS" ]; then props+=(-Dbench.soak.seconds="$SOAK_SECONDS"); fi
    if [ -n "$SOAK_WARMUP" ]; then props+=(-Dbench.soak.warmupSeconds="$SOAK_WARMUP"); fi
    if [ -n "$SOAK_PLATEAU" ]; then props+=(-Dbench.soak.plateauSeconds="$SOAK_PLATEAU"); fi
    if [ -n "$SOAK_RATE" ]; then props+=(-Dbench.soak.targetRate="$SOAK_RATE"); fi
    if [ -n "$SOAK_GATE_RATE" ]; then props+=(-Dbench.soak.gateRate="$SOAK_GATE_RATE"); fi
    if [ -n "$SOAK_WORKERS" ]; then props+=(-Dbench.soak.workers="$SOAK_WORKERS"); fi
    if [ -n "$SOAK_LOAD_FROM" ]; then props+=(-Dbench.soak.loadFrom="$SOAK_LOAD_FROM"); fi
    if [ -n "$SOAK_MWM" ]; then props+=(-Dbench.soak.maintenanceWorkMem="$SOAK_MWM"); fi
    run_bench "$spec" "${props[@]}"
}

soak_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$SOAK_CONTAINER"; }
soak_running() { docker ps    --format '{{.Names}}' | grep -qx "$SOAK_CONTAINER"; }

stage_soak_up() {
    if [ -n "$SOAK_URL" ]; then
        log "external soak database ${SOAK_URL}: no container to start"
        return 0
    fi
    if soak_running; then
        log "container ${SOAK_CONTAINER} already running"
    elif soak_exists; then
        log "starting existing container ${SOAK_CONTAINER}"
        docker start "$SOAK_CONTAINER" >/dev/null
    else
        log "creating container ${SOAK_CONTAINER} (postgres:16, 127.0.0.1:${SOAK_PORT}, data ${SOAK_VOLUME}, flags: ${SOAK_PG_ARGS:-image defaults})"
        # shellcheck disable=SC2086 # BENCH_SOAK_PG_ARGS is a list of postgres flags
        docker run -d --name "$SOAK_CONTAINER" \
            -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
            --shm-size="$SOAK_SHM" -p "127.0.0.1:${SOAK_PORT}:5432" \
            -v "${SOAK_VOLUME}:/var/lib/postgresql/data" "$IMAGE" postgres $SOAK_PG_ARGS >/dev/null
    fi
    local saved="$CONTAINER"
    CONTAINER="$SOAK_CONTAINER"; wait_ready; CONTAINER="$saved"
    log "soak bench DB ready on 127.0.0.1:${SOAK_PORT}"
}

stage_soak_load() {
    stage_soak_up; soak_env
    if [ "$COUNT" -gt 1000000 ] && [ "$YES" != 1 ]; then
        printf '\n  soak-load of %s things: hours of loading and roughly the load.md bytes-per-thing x count of disk.\n' "$COUNT"
        printf 'Proceed? [y/N] '
        read -r reply
        case "$reply" in y|Y|yes|YES) ;; *) die "aborted" ;; esac
    fi
    log "write-soak load: count=${COUNT} seed=${SEED} from=${SOAK_LOAD_FROM:-0} (from 0 drops + recreates the search tables)"
    soak_run_bench 'WriteSoakBench#load'
}

stage_soak_run() {
    stage_soak_up; soak_env
    log "write-soak run: count=${COUNT} rate=${SOAK_RATE:-220} gated=${SOAK_SECONDS:-3600}s warm-up=${SOAK_WARMUP:-300}s"
    soak_run_bench 'WriteSoakBench#soak'
}

stage_soak_status() {
    if [ -n "$SOAK_URL" ]; then
        echo "external soak database: ${SOAK_URL}"
    elif soak_running; then
        echo "container: ${SOAK_CONTAINER} RUNNING (127.0.0.1:${SOAK_PORT}, data ${SOAK_VOLUME})"
        docker exec "$SOAK_CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'things: ' || count(*) FROM search_things" 2>/dev/null || echo "schema: not loaded"
        docker exec "$SOAK_CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'database: ' || pg_size_pretty(pg_database_size('bench'))" 2>/dev/null || true
    elif soak_exists; then
        echo "container: ${SOAK_CONTAINER} exists but is STOPPED"
    else
        echo "container: ${SOAK_CONTAINER} does not exist"
    fi
}

stage_soak_down() {
    if [ -n "$SOAK_URL" ]; then
        log "external soak database: nothing to remove"
        return 0
    fi
    if soak_exists; then
        docker rm -f "$SOAK_CONTAINER" >/dev/null
        echo "removed container ${SOAK_CONTAINER}"
    else
        echo "container ${SOAK_CONTAINER} does not exist"
    fi
    if [ "$PURGE" = 1 ]; then
        if docker volume inspect "$SOAK_VOLUME" >/dev/null 2>&1; then
            docker volume rm "$SOAK_VOLUME" >/dev/null || die "could not remove volume ${SOAK_VOLUME}"
            echo "removed volume ${SOAK_VOLUME}"
        else
            echo "${SOAK_VOLUME} is not a named Docker volume (a host path is not removed by this script)"
        fi
    fi
}

confirm_production_config_run() {
    [ "$YES" = 1 ] && return 0
    printf '\n  Production-config search benchmark at count=%s:\n' "$COUNT"
    printf '    - wall clock : roughly 25-30 minutes at 1M (load ~12, %s warm rounds ~10, cold runs ~3)\n' "$PC_ROUNDS"
    printf '    - disk       : ~45 GB in the Docker data volume %s\n' "$PC_VOLUME"
    printf '    - results overwrite the evidence under %s/src/test/resources/bench/results/production-config/\n\n' \
        "$MODULE"
    printf 'Proceed? [y/N] '
    read -r reply
    case "$reply" in y|Y|yes|YES) ;; *) die "aborted" ;; esac
}

confirm_full_run() {
    [ "$YES" = 1 ] && return 0
    [ "$QUICK" = 1 ] && return 0
    cat <<EOF

  Full benchmark series at count=${COUNT}:
    - wall clock : roughly 2-3 hours
    - disk       : ~30 GB corpus + write-bench churn -> budget >= 40 GB free in Docker's data volume
    - results overwrite the committed evidence files under ${MODULE}/src/test/resources/bench/results/

EOF
    printf 'Proceed? [y/N] '
    read -r reply
    case "$reply" in y|Y|yes|YES) ;; *) die "aborted" ;; esac
}

for stage in "${STAGES[@]}"; do
    case "$stage" in
        up)          stage_up ;;
        build)       stage_build ;;
        load)        stage_load ;;
        smoke)       stage_smoke ;;
        read)        stage_read ;;
        mitigations) stage_mitigations ;;
        fixround)    stage_fixround ;;
        write)       stage_write ;;
        status)      stage_status ;;
        down)        stage_down ;;
        pc-up)       stage_pc_up ;;
        pc-load)     stage_pc_load ;;
        pc-read)     stage_pc_read ;;
        pc-cold)     stage_pc_cold ;;
        pc-bias)     stage_pc_bias ;;
        pc-invariance) stage_pc_invariance ;;
        pc-status)   stage_pc_status ;;
        pc-down)     stage_pc_down ;;
        soak-up)     stage_soak_up ;;
        soak-load)   stage_soak_load ;;
        soak-run)    stage_soak_run ;;
        soak-status) stage_soak_status ;;
        soak-down)   stage_soak_down ;;
        production-config)
            confirm_production_config_run
            stage_pc_up; stage_pc_load; stage_pc_read; stage_pc_cold; stage_pc_bias
            log "production-config benchmark complete"
            stage_pc_status
            ;;
        all)
            confirm_full_run
            stage_up; stage_load; stage_read; stage_mitigations; stage_write
            log "benchmark series complete"
            stage_status
            ;;
        *) die "unknown stage: $stage" ;;
    esac
done
