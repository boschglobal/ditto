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
# Core-persistence benchmark: PostgreSQL vs MongoDB (journal, snapshots, cleanup).
# Design: docs/superpowers/specs/2026-07-10-postgres-persistence-bench-design.md
#
# Usage:
#   run-benchmark.sh <stage...> [options]
#
# Stages (in dependency order):
#   up           start BOTH long-lived bench containers (postgres:16 tuned, mongo:7.0 WT-cache 4GB)
#   build        mvn install -DskipTests the bench module + its reactor dependencies
#   load         PgLoadBench + MongoLoadBench — generate + bulk-load the corpus, build indexes
#   smoke        LoaderSmokeIT on Testcontainers (independent of the bench DBs)
#   recovery     RecoveryBench      — R1 latest snapshot, R2 replay (tail+full), R3 highest sn
#   write        AppendBench W1, SnapshotBench W2, AppendBench W3 sustained churn
#   cleanup      CleanupShapeBench  — C1 pid-stream page, C2 single-pid cleanup
#   sweep        CleanupSweepBench  — C3 full sweep (DESTRUCTIVE: empties cleanup backlog), C4 proof
#   mixed        MixedChurnBench    — M1 cleanup concurrent with churn (post-sweep corpus)
#   all          up load recovery write cleanup sweep mixed (asks for confirmation first)
#   status       show container / corpus / result-file state
#   down         remove both containers (add --purge to also delete the data volumes)
#
# Options:
#   --count N      corpus size in pids for `load` (default 1000000; see disk note below)
#   --seed N       corpus seed (default 42)
#   --quick        demo mode: 50k pids + short windows + credits-pace fidelity (numbers NOT comparable)
#   --backend B    pg | mongo | both (default both)
#   --yes          skip the duration/disk confirmation prompt
#   --purge        with `down`: also remove the data volumes
#
# Disk note: at --count 1000000 the pinned depth distribution yields ~139M journal rows
# (~60-75 GB per backend, ~130-150 GB total). --count 500000 (~70M rows) matches the design's
# ~40 GB-per-backend budget. Ordering: `sweep` (C3) irreversibly deletes the cleanup backlog —
# rerun `load` to restore the corpus.
#
# Environment:
#   MVN                    maven binary (default: mvn on PATH)
#   BENCH_PG_PORT          host port for PostgreSQL (default 55433)
#   BENCH_MONGO_PORT       host port for MongoDB (default 57017)
#   BENCH_PG_CONTAINER     container name (default ditto-persistence-bench-pg)
#   BENCH_MONGO_CONTAINER  container name (default ditto-persistence-bench-mongo)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
MODULE="internal/utils/persistence-bench"

MVN="${MVN:-mvn}"
PG_CONTAINER="${BENCH_PG_CONTAINER:-ditto-persistence-bench-pg}"
MONGO_CONTAINER="${BENCH_MONGO_CONTAINER:-ditto-persistence-bench-mongo}"
PG_PORT="${BENCH_PG_PORT:-55433}"
MONGO_PORT="${BENCH_MONGO_PORT:-57017}"
PG_IMAGE="postgres:16"
MONGO_IMAGE="mongo:7.0"

COUNT=1000000
SEED=42
QUICK=0
YES=0
PURGE=0
BACKEND=both
STAGES=()

# Laptop-class settings, applied from the start (search-bench E2 finding adopted as protocol).
PG_ARGS=(-c shared_buffers=4GB -c effective_cache_size=12GB -c work_mem=64MB)
# Mirrors deployment/docker/docker-compose.yml plus a 4GB WT cache for memory symmetry with PG.
MONGO_ARGS=(--storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4)

usage() { sed -n '14,52p' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --count) COUNT="$2"; shift 2 ;;
        --seed) SEED="$2"; shift 2 ;;
        --backend) BACKEND="$2"; shift 2 ;;
        --quick) QUICK=1; shift ;;
        --yes) YES=1; shift ;;
        --purge) PURGE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "unknown option: $1" >&2; usage; exit 1 ;;
        *) STAGES+=("$1"); shift ;;
    esac
done
[ ${#STAGES[@]} -gt 0 ] || { usage; exit 1; }
if [ "$QUICK" = 1 ] && [ "$COUNT" = 1000000 ]; then COUNT=50000; fi

export BENCH_PG_URL="jdbc:postgresql://localhost:${PG_PORT}/bench"
export BENCH_PG_USER="bench"
export BENCH_PG_PASSWORD="bench"
export BENCH_MONGO_URI="mongodb://localhost:${MONGO_PORT}"
export BENCH_MONGO_DB="bench"
export BENCH_COUNT="$COUNT"
export BENCH_SEED="$SEED"
export BENCH_BACKEND="$BACKEND"

log() { echo "[bench] $*"; }
die() { echo "[bench] ERROR: $*" >&2; exit 1; }

container_exists() { docker ps -a --format '{{.Names}}' | grep -qx "$1"; }
container_running() { docker ps --format '{{.Names}}' | grep -qx "$1"; }

wait_pg() {
    for _ in $(seq 1 60); do
        if docker exec "$PG_CONTAINER" pg_isready -U bench -d bench >/dev/null 2>&1; then
            sleep 1
            docker exec "$PG_CONTAINER" pg_isready -U bench -d bench >/dev/null 2>&1 && return 0
        fi
        sleep 1
    done
    die "PostgreSQL container did not become ready"
}

wait_mongo() {
    for _ in $(seq 1 60); do
        if docker exec "$MONGO_CONTAINER" mongosh --quiet --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    die "MongoDB container did not become ready"
}

stage_up() {
    if [ "$BACKEND" != mongo ]; then
        if container_running "$PG_CONTAINER"; then
            log "${PG_CONTAINER} already running"
        elif container_exists "$PG_CONTAINER"; then
            log "starting existing ${PG_CONTAINER}"
            docker start "$PG_CONTAINER" >/dev/null
            wait_pg
        else
            log "creating ${PG_CONTAINER} (port ${PG_PORT}, ${PG_ARGS[*]})"
            docker run -d --name "$PG_CONTAINER" \
                -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench \
                -p "${PG_PORT}:5432" "$PG_IMAGE" postgres "${PG_ARGS[@]}" >/dev/null
            wait_pg
        fi
    fi
    if [ "$BACKEND" != pg ]; then
        if container_running "$MONGO_CONTAINER"; then
            log "${MONGO_CONTAINER} already running"
        elif container_exists "$MONGO_CONTAINER"; then
            log "starting existing ${MONGO_CONTAINER}"
            docker start "$MONGO_CONTAINER" >/dev/null
            wait_mongo
        else
            log "creating ${MONGO_CONTAINER} (port ${MONGO_PORT}, ${MONGO_ARGS[*]})"
            docker run -d --name "$MONGO_CONTAINER" \
                -p "${MONGO_PORT}:27017" "$MONGO_IMAGE" mongod "${MONGO_ARGS[@]}" >/dev/null
            wait_mongo
        fi
    fi
}

run_bench() { # args: -Dtest spec, extra -D props...
    local spec="$1"; shift
    log "mvn -Dtest=${spec}"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" test -Dtest="$spec" -DfailIfNoTests=false "$@")
}

quick_props() {
    if [ "$QUICK" = 1 ]; then
        echo "-Dbench.iterations=5 -Dbench.write.durationSeconds=30 -Dbench.write.targetRate=100" \
             "-Dbench.write.workers=4 -Dbench.sample.intervalSeconds=10 -Dbench.c4.settleTimeoutSeconds=120"
    fi
}

stage_build() {
    log "building ${MODULE} + reactor dependencies"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" -am install -DskipTests -q)
}

stage_load() {
    stage_up
    log "loading corpus: count=${COUNT} seed=${SEED} backend=${BACKEND} (drops + recreates tables/collections)"
    [ "$BACKEND" = mongo ] || run_bench PgLoadBench
    [ "$BACKEND" = pg ] || run_bench MongoLoadBench
}

stage_smoke() {
    log "Testcontainers smoke test (independent of the bench DBs; self-skips without Docker)"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" verify \
        -Dit.test=LoaderSmokeIT -Dtest=CorpusGeneratorTest -DfailIfNoTests=false)
}

stage_recovery() {
    stage_up
    # shellcheck disable=SC2046
    run_bench RecoveryBench $(quick_props)
}

stage_write() {
    stage_up
    # shellcheck disable=SC2046
    run_bench 'AppendBench#w1SingleAppend' $(quick_props)
    # shellcheck disable=SC2046
    run_bench SnapshotBench $(quick_props)
    # shellcheck disable=SC2046
    run_bench 'AppendBench#w3SustainedChurn' $(quick_props)
}

stage_cleanup() {
    stage_up
    # shellcheck disable=SC2046
    run_bench 'CleanupShapeBench#c1PidStreamPage' $(quick_props)
    # shellcheck disable=SC2046
    run_bench 'CleanupShapeBench#c2SinglePidCleanup' $(quick_props)
}

stage_sweep() {
    stage_up
    log "C3 full sweep — DESTRUCTIVE: deletes journal/snapshot rows below each latest snapshot"
    log "pace: ${BENCH_SWEEP_PACE:-unthrottled} (export BENCH_SWEEP_PACE=credits for Ditto's real 100-rows/s gate — days at full scale)"
    # shellcheck disable=SC2046
    run_bench 'CleanupSweepBench#c3FullSweep' $(quick_props)
    # shellcheck disable=SC2046
    run_bench 'CleanupSweepBench#c4BeforeAfterProof' $(quick_props)
}

stage_mixed() {
    stage_up
    # M1-only churn concentration: with the default pid spread no churn pid can reach the
    # 500-event snapshot threshold (ops/(workers*pidsPerWorker) < 500 even at full scale), so the
    # concurrent sweeper would never find churn debris to delete. pidsPerWorker=1 concentrates the
    # same op rate onto one pid per worker; the W3-comparability caveat is stated in the evidence.
    # shellcheck disable=SC2046
    run_bench 'MixedChurnBench#m1CleanupUnderTraffic' $(quick_props) -Dbench.write.pidsPerWorker=1
}

stage_status() {
    for c in "$PG_CONTAINER" "$MONGO_CONTAINER"; do
        if container_running "$c"; then echo "container: $c RUNNING";
        elif container_exists "$c"; then echo "container: $c exists but is STOPPED";
        else echo "container: $c does not exist"; fi
    done
    if container_running "$PG_CONTAINER"; then
        docker exec "$PG_CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'pg journal rows:   ' || count(*) FROM things_journal" 2>/dev/null || echo "pg: schema not loaded"
        docker exec "$PG_CONTAINER" psql -U bench -d bench -Atc \
            "SELECT 'pg snapshot rows:  ' || count(*) FROM things_snaps" 2>/dev/null || echo "pg: snapshot table not loaded"
    fi
    if container_running "$MONGO_CONTAINER"; then
        docker exec "$MONGO_CONTAINER" mongosh --quiet bench --eval \
            'print("mongo journal docs: " + db.things_journal.countDocuments()); print("mongo snapshot docs: " + db.things_snaps.countDocuments())' 2>/dev/null \
            || echo "mongo: collections not loaded"
    fi
    echo "results:   ${ROOT}/${MODULE}/src/test/resources/bench/results/ (diff against git to compare runs)"
    echo "corpus state note: if 'sweep' has run, journal rows are far below the loaded count — rerun 'load' for a fresh corpus."
}

stage_down() {
    for c in "$PG_CONTAINER" "$MONGO_CONTAINER"; do
        if container_exists "$c"; then
            local vols=""
            if [ "$PURGE" = 1 ]; then
                # Collect every volume-type mount, not just index 0 — the official mongo image
                # declares TWO anonymous volumes (/data/configdb, /data/db) and the data lives
                # in the second one.
                vols=$(docker inspect "$c" --format \
                    '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}}{{"\n"}}{{end}}{{end}}' 2>/dev/null || true)
            fi
            log "removing $c"
            docker rm -f "$c" >/dev/null
            if [ -n "$vols" ]; then
                while IFS= read -r vol; do
                    [ -n "$vol" ] || continue
                    log "removing volume $vol"
                    docker volume rm "$vol" >/dev/null || true
                done <<< "$vols"
            fi
        fi
    done
}

confirm_full_run() {
    [ "$YES" = 1 ] && return 0
    [ "$QUICK" = 1 ] && return 0
    cat <<EOF
[bench] Full run at count=${COUNT}:
  - ~139 events/pid mean at the pinned distribution => ~$((COUNT / 7200))M journal rows approx per backend
    (count=1000000 => ~139M rows, ~60-75 GB per backend, ~130-150 GB total;
     count=500000  => ~70M rows, matches the design's ~40 GB-per-backend budget)
  - duration: load 1-2 h, recovery/write/cleanup ~1 h, sweep 1-3 h (incl. autovacuum settle), mixed ~25 min
  - the 'sweep' stage is destructive (that is its job); rerun 'load' afterwards for a fresh corpus
EOF
    printf "Proceed? [y/N] "
    read -r answer
    [ "$answer" = y ] || [ "$answer" = Y ] || die "aborted"
}

for stage in "${STAGES[@]}"; do
    case "$stage" in
        up) stage_up ;;
        build) stage_build ;;
        load) stage_load ;;
        smoke) stage_smoke ;;
        recovery) stage_recovery ;;
        write) stage_write ;;
        cleanup) stage_cleanup ;;
        sweep) stage_sweep ;;
        mixed) stage_mixed ;;
        all) confirm_full_run; stage_up; stage_load; stage_recovery; stage_write; stage_cleanup; stage_sweep; stage_mixed ;;
        status) stage_status ;;
        down) stage_down ;;
        *) die "unknown stage: $stage" ;;
    esac
done
