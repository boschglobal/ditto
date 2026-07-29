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
# Options:
#   --count N    corpus size for `load` (default 1000000)
#   --seed N     corpus seed (default 42)
#   --quick      demo mode: 50k corpus + short write windows (numbers NOT comparable)
#   --yes        skip the duration/disk confirmation prompt
#   --purge      with `down`: also remove the data volume
#
# Environment:
#   MVN          maven binary (default: mvn on PATH)
#   BENCH_PORT   host port for the bench DB (default 55432)
#   BENCH_CONTAINER  container name (default ditto-search-bench-pg)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
MODULE="internal/utils/search-r2dbc"

MVN="${MVN:-mvn}"
CONTAINER="${BENCH_CONTAINER:-ditto-search-bench-pg}"
PORT="${BENCH_PORT:-55432}"
IMAGE="postgres:16"

COUNT=1000000
SEED=42
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
        -h|--help) sed -n '15,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
        -*) die "unknown option: $1" ;;
        *) STAGES+=("$1"); shift ;;
    esac
done
[ ${#STAGES[@]} -gt 0 ] || { sed -n '15,45p' "${BASH_SOURCE[0]}"; exit 1; }

if [ "$QUICK" = 1 ] && [ "$COUNT" = 1000000 ]; then
    COUNT=50000
fi

command -v docker >/dev/null || die "docker not found on PATH"
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
        all)
            confirm_full_run
            stage_up; stage_load; stage_read; stage_mitigations; stage_write
            log "benchmark series complete"
            stage_status
            ;;
        *) die "unknown stage: $stage" ;;
    esac
done
