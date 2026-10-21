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
#   config       print the docker run lines (limits, server arguments) the options resolve to; starts nothing
#   up           start the long-lived bench containers of --backend (postgres:16 tuned, mongo:7.0 WT-cache 4GB) with
#                the same --cpus/--memory limits; refuses a container created with other limits or server arguments
#   relimit      recreate the containers of --backend with this run's limits and server arguments on their data
#                volumes (keeps the loaded corpus); a container that already matches is left as it is
#   build        mvn install -DskipTests the bench module + its reactor dependencies
#   load         PgLoadBench + MongoLoadBench — generate + bulk-load the corpus, build indexes
#   settle       PostgreSQL: wait until things_journal and things_snaps were vacuumed since the load and no vacuum
#                runs (BENCH_SETTLE_TIMEOUT, default 3600 s); MongoDB: nothing to wait for
#   smoke        LoaderSmokeIT + PlanCaptureIT on Testcontainers (independent of the bench DBs)
#   recovery     RecoveryBench      — R1 latest snapshot, R2 replay (tail+full), R3 highest sn
#   write        AppendBench W1, SnapshotBench W2, AppendBench W3 sustained churn
#   wcheck       W1 single append + W2 snapshot write only (fixed probe pids: once per load, or a new BENCH_PROBE_SUFFIX)
#   w3           AppendBench#w3SustainedChurn only (once per load, or a new BENCH_W3_PID_PREFIX)
#   cleanup      CleanupShapeBench  — C1 pid-stream page, C2 single-pid cleanup
#   c1           CleanupShapeBench#c1PidStreamPage only (read-only)
#   c2           CleanupShapeBench#c2SinglePidCleanup only (DESTRUCTIVE for its 46 pids)
#   retention    CleanupShapeBench  — C1 with the production min age (--retention, default 3d) vs 0;
#                load with --fresh-share first: on the backdated corpus every row qualifies
#   payload-whatif  CleanupShapeBench — NOT PRODUCTION what-if: the C1 page with vs without the snapshot
#                payload, PG only (--backend pg), at --retention and 0, on the `retention` stage's corpus
#   sweep        CleanupSweepBench  — C3 full sweep (DESTRUCTIVE: empties cleanup backlog), C4 proof
#   c3           CleanupSweepBench#c3FullSweep only (DESTRUCTIVE)
#   c4           CleanupSweepBench#c4BeforeAfterProof only (run after c3)
#   window       CleanupSweepBench#c5CreditPacedWindow: credit-paced sweep bounded by BENCH_SWEEP_MAX_SECONDS
#                (DESTRUCTIVE for the pids it reaches; --backend pg)
#   mixed-sweep  MixedChurnBench    — M1: W3-shaped churn during one unthrottled sweep pass over the whole backlog
#                (DESTRUCTIVE like c3: needs the loaded backlog, i.e. no sweep since the load; no VACUUM FULL before it)
#   mixed        MixedChurnBench    — M1b: concentrated churn while a sweeper cycles (post-sweep corpus)
#   all          up load settle recovery write cleanup mixed-sweep load settle sweep mixed (asks for confirmation
#                first; C3/C4 get their own load, so C4 never replays pids that C2 cleaned)
#   status       show container / limits / corpus / result-file state
#   down         remove the containers of --backend (default both; add --purge to also delete their data volumes)
#
# Options:
#   --count N      corpus size in pids for `load` (default 1000000; see disk note below)
#   --seed N       corpus seed (default 42)
#   --quick        demo mode: 50k pids + short windows + credits-pace fidelity (numbers NOT comparable)
#   --backend B    pg | mongo | both (default both)
#   --cpus N       CPU limit per container, the same for both backends (default 4; 0 = no limit)
#   --memory G     memory limit per container in whole GiB, the same for both backends (default 8; 0 = no limit,
#                  else >= 6). It is the container's cgroup limit: it covers the server's buffers/cache, its
#                  processes and the page cache its reads leave; no container swap. PostgreSQL's
#                  effective_cache_size follows it (3/4 of the limit; 12GB without one)
#   --fresh-share S  share of pids whose rows are all younger than the retention (default 0 = all
#                    rows backdated to 2026-01-01); pass the same value to `load` and `retention`
#   --fresh-base T   ISO-8601 instant the fresh-only pids' rows are written after (default: the
#                    previous UTC midnight); pin it when the backends are loaded on different days
#   --retention D    history-retention-duration for `retention` (default 3d, the things.conf default)
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
#   BENCH_CPUS, BENCH_MEMORY_GB  defaults of --cpus / --memory
#
# Scenario settings are environment variables read by BenchConfig (this script rejects -D options):
#   BENCH_ITERATIONS, BENCH_WARMUPS, BENCH_SAMPLE_INTERVAL, BENCH_SWEEP_PACE, BENCH_C4_SETTLE_TIMEOUT
#   BENCH_EVIDENCE_LABEL      suffix for every evidence file name, <name>-<label>.md (default: none)
#   BENCH_PG_RELOPTIONS       replaces the schema's storage parameters of things_journal/things_snaps at load
#   BENCH_C3_VACUUM_FIRST     true: VACUUM (ANALYZE) the corpus before C3/C5
#   BENCH_C4_OFFLINE_RECLAIM  false: skip C4's VACUUM FULL / compact
#   BENCH_SWEEP_MAX_SECONDS   bound of the window stage
#   BENCH_C1_VACUUM_TIMEOUT   C1 stages: bound of the wait for a quiet PG server, seconds (default 3600)
#   BENCH_SETTLE_TIMEOUT      bound of the settle stage, seconds (default 3600)
#   BENCH_PROBE_SUFFIX        appended to the W1/W2 probe pids, so wcheck can run again on one load (default: none)
#   BENCH_RESULTS_DIR         evidence folder (default: the committed results folder); point trial runs elsewhere
# Pass the same --count to every stage (the default is 1000000; C4 picks its pids from it).

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
CPUS="${BENCH_CPUS:-4}"
MEMORY_GB="${BENCH_MEMORY_GB:-8}"
STAGES=()

# Laptop-class buffer sizes from the start (search-bench E2 finding adopted as protocol); PG_ARGS, with an
# effective_cache_size that follows --memory, is set after the options are parsed.
# Mirrors deployment/docker/docker-compose.yml plus a 4GB WT cache, the same size as PG's shared_buffers.
MONGO_ARGS=(--storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4)

usage() { awk 'NR >= 14 { if (/^#/) print; else exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --count) COUNT="$2"; shift 2 ;;
        --seed) SEED="$2"; shift 2 ;;
        --backend) BACKEND="$2"; shift 2 ;;
        --cpus) CPUS="$2"; shift 2 ;;
        --memory) MEMORY_GB="$2"; shift 2 ;;
        --fresh-share) export BENCH_FRESH_SHARE="$2"; shift 2 ;;
        --fresh-base) export BENCH_FRESH_BASE="$2"; shift 2 ;;
        --retention) export BENCH_C1_RETENTION="$2"; shift 2 ;;
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

# Symmetric container limits: the same --cpus/--memory for both backends; 0 = no limit (the setup before the limits).
[[ "$CPUS" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--cpus must be a number (0 = no limit), got '${CPUS}'"
[[ "$MEMORY_GB" =~ ^[0-9]+$ ]] || die "--memory must be whole GiB (0 = no limit), got '${MEMORY_GB}'"
if [ "$MEMORY_GB" -ne 0 ] && [ "$MEMORY_GB" -lt 6 ]; then
    die "--memory ${MEMORY_GB}: must be 0 or >= 6 GiB (the 4 GB shared_buffers / WiredTiger cache plus >= 2 GiB for the page cache and the server processes)"
fi
LIMIT_ARGS=()
if [ "$CPUS" != 0 ]; then LIMIT_ARGS+=(--cpus "$CPUS"); fi
if [ "$MEMORY_GB" -ne 0 ]; then LIMIT_ARGS+=(--memory "${MEMORY_GB}g" --memory-swap "${MEMORY_GB}g"); fi
# The planner's cache estimate follows the limit: shared_buffers plus the file cache the cgroup leaves, taken as 3/4 of
# the limit; without a limit the value of the runs before the limits.
if [ "$MEMORY_GB" -eq 0 ]; then ECS=12GB; else ECS="$((MEMORY_GB * 1024 * 3 / 4))MB"; fi
PG_ARGS=(-c shared_buffers=4GB -c "effective_cache_size=${ECS}" -c work_mem=64MB)
# ${a[@]+"${a[@]}"}: an empty LIMIT_ARGS stays safe under set -u on bash 3.2 too
PG_RUN_ARGS=(-d --name "$PG_CONTAINER" ${LIMIT_ARGS[@]+"${LIMIT_ARGS[@]}"}
    -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench -p "${PG_PORT}:5432")
PG_CMD=("$PG_IMAGE" postgres "${PG_ARGS[@]}")
MONGO_RUN_ARGS=(-d --name "$MONGO_CONTAINER" ${LIMIT_ARGS[@]+"${LIMIT_ARGS[@]}"} -p "${MONGO_PORT}:27017")
MONGO_CMD=("$MONGO_IMAGE" mongod "${MONGO_ARGS[@]}")

# "<NanoCpus> <Memory bytes> <server args>" of container $1, as docker inspect reports them
actual_config() { docker inspect "$1" --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{join .Args " "}}'; }
# the same for a container created from this run's options
expected_limits() { awk -v c="$CPUS" -v m="$MEMORY_GB" 'BEGIN { printf "%.0f %.0f", c * 1e9, m * 1073741824 }'; }
expected_pg_config() { echo "$(expected_limits) postgres ${PG_ARGS[*]}"; }
expected_mongo_config() { echo "$(expected_limits) mongod ${MONGO_ARGS[*]}"; }

check_config() { # container expected-config: a container keeps what it was created with, so refuse a silent mismatch
    local actual
    actual=$(actual_config "$1")
    [ "$actual" = "$2" ] || die "$1 runs with '${actual}', this run asks for '$2' (--cpus ${CPUS} --memory ${MEMORY_GB}): run 'relimit' (keeps the data volumes) or 'down' first"
}

volume_args() { # "-v <volume>:<destination>" for every volume mount of container $1
    docker inspect "$1" --format '{{range .Mounts}}{{if eq .Type "volume"}}-v {{.Name}}:{{.Destination}} {{end}}{{end}}'
}

limits_line() { # limits and server args of the running bench containers, as docker inspect reports them
    local line="" c nano mem args
    for c in "$PG_CONTAINER" "$MONGO_CONTAINER"; do
        container_running "$c" || continue
        read -r nano mem args <<< "$(actual_config "$c")"
        line+="${line:+; }${c}: cpus=$(awk -v n="$nano" 'BEGIN { if (n == 0) print "unlimited"; else print n / 1e9 }')"
        if [ "$mem" = 0 ]; then line+=", memory=unlimited"; else line+=", memory=$((mem / 1073741824)) GiB"; fi
        line+=", ${args}"
    done
    echo "${line:-no bench container running}"
}

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

create_pg() { # [extra docker run args, e.g. the -v of an existing data volume]
    log "creating ${PG_CONTAINER}: docker run ${PG_RUN_ARGS[*]} $* ${PG_CMD[*]}"
    docker run "${PG_RUN_ARGS[@]}" "$@" "${PG_CMD[@]}" >/dev/null
    wait_pg
}

create_mongo() { # [extra docker run args]
    log "creating ${MONGO_CONTAINER}: docker run ${MONGO_RUN_ARGS[*]} $* ${MONGO_CMD[*]}"
    docker run "${MONGO_RUN_ARGS[@]}" "$@" "${MONGO_CMD[@]}" >/dev/null
    wait_mongo
}

up_one() { # container pg|mongo expected-config
    if ! container_exists "$1"; then
        "create_$2"
        return 0
    fi
    check_config "$1" "$3"
    if container_running "$1"; then
        log "$1 already running"
    else
        log "starting existing $1"
        docker start "$1" >/dev/null
        "wait_$2"
    fi
}

stage_up() {
    [ "$BACKEND" = mongo ] || up_one "$PG_CONTAINER" pg "$(expected_pg_config)"
    [ "$BACKEND" = pg ] || up_one "$MONGO_CONTAINER" mongo "$(expected_mongo_config)"
}

relimit_one() { # container pg|mongo expected-config
    local vargs=()
    if ! container_exists "$1"; then
        log "relimit: $1 does not exist; creating it"
        "create_$2"
        return 0
    fi
    if [ "$(actual_config "$1")" = "$3" ]; then
        log "relimit: $1 already runs with '$3'"
        container_running "$1" || { docker start "$1" >/dev/null; "wait_$2"; }
        return 0
    fi
    read -r -a vargs <<< "$(volume_args "$1")"
    [ ${#vargs[@]} -gt 0 ] || die "relimit: $1 has no data volume to keep; use 'down' and 'load' instead"
    log "relimit: stopping $1 (its volumes stay: ${vargs[*]})"
    docker stop -t 300 "$1" >/dev/null
    docker rm "$1" >/dev/null
    "create_$2" "${vargs[@]}"
}

stage_relimit() {
    [ "$BACKEND" = mongo ] || relimit_one "$PG_CONTAINER" pg "$(expected_pg_config)"
    [ "$BACKEND" = pg ] || relimit_one "$MONGO_CONTAINER" mongo "$(expected_mongo_config)"
}

stage_config() { # what up / relimit would create; touches no container
    log "pg:    docker run ${PG_RUN_ARGS[*]} ${PG_CMD[*]}"
    log "mongo: docker run ${MONGO_RUN_ARGS[*]} ${MONGO_CMD[*]}"
}

run_bench() { # args: -Dtest spec, extra -D props...
    local spec="$1"; shift
    BENCH_CONTAINER_LIMITS="$(limits_line)"
    export BENCH_CONTAINER_LIMITS
    log "mvn -Dtest=${spec} (containers: ${BENCH_CONTAINER_LIMITS})"
    (cd "$ROOT" && "$MVN" -pl "$MODULE" test -Dtest="$spec" -DfailIfNoTests=false "$@")
}

quick_props() {
    if [ "$QUICK" = 1 ]; then
        echo "-Dbench.iterations=5 -Dbench.write.durationSeconds=30 -Dbench.write.targetRate=100" \
             "-Dbench.write.workers=4 -Dbench.sample.intervalSeconds=10 -Dbench.c4.settleTimeoutSeconds=120" \
             "-Dbench.c1.vacuumTimeoutSeconds=120"
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
        -Dit.test=LoaderSmokeIT,PlanCaptureIT -Dtest=CorpusGeneratorTest -DfailIfNoTests=false)
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

stage_c1() {
    stage_up
    # shellcheck disable=SC2046
    run_bench 'CleanupShapeBench#c1PidStreamPage' $(quick_props)
}

stage_c2() {
    stage_up
    log "C2 single-pid cleanup — DESTRUCTIVE for the pids it cleans (23 P99 + 23 OUTLIER at full scale)"
    # shellcheck disable=SC2046
    run_bench 'CleanupShapeBench#c2SinglePidCleanup' $(quick_props)
}

stage_settle() { # PostgreSQL: wait for the post-load autovacuum; MongoDB has none
    if [ "$BACKEND" = mongo ]; then
        log "settle: MongoDB has no autovacuum; nothing to wait for"
        return 0
    fi
    stage_up
    local timeout="${BENCH_SETTLE_TIMEOUT:-3600}" start now state quiet=0
    start=$(date +%s)
    log "settle: waiting until things_journal and things_snaps were vacuumed since the load and no vacuum runs (timeout ${timeout} s)"
    while true; do
        # "<tables with rows inserted since their creation and never vacuumed> <vacuums in progress>"
        state=$(docker exec "$PG_CONTAINER" psql -U bench -d bench -XAtc \
            "SELECT (SELECT count(*) FROM pg_stat_user_tables WHERE relname IN ('things_journal', 'things_snaps')
                     AND last_vacuum IS NULL AND last_autovacuum IS NULL AND n_ins_since_vacuum > 0)
                    || ' ' || (SELECT count(*) FROM pg_stat_progress_vacuum)")
        now=$(date +%s)
        if [ "$state" = "0 0" ]; then quiet=$((quiet + 1)); else quiet=0; fi
        if [ "$quiet" -ge 2 ]; then
            log "settle: quiet after $((now - start)) s"
            return 0
        fi
        [ $((now - start)) -lt "$timeout" ] \
            || die "settle: not quiet after ${timeout} s (never-vacuumed tables, vacuums running: ${state})"
        sleep 30
    done
}

stage_retention() {
    stage_up
    log "C1 with retention ${BENCH_C1_RETENTION:-3d} on a corpus with fresh share ${BENCH_FRESH_SHARE:-0}"
    # shellcheck disable=SC2046
    run_bench 'CleanupShapeBench#c1PidStreamPageWithRetention' $(quick_props)
}

stage_payload_whatif() {
    [ "$BACKEND" = pg ] || die "payload-whatif is PostgreSQL only: pass --backend pg"
    stage_up
    log "C1 payload what-if (NOT PRODUCTION): retention ${BENCH_C1_RETENTION:-3d} and 0"
    # shellcheck disable=SC2046
    run_bench 'CleanupShapeBench#c1PayloadFreeWhatIf' $(quick_props)
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

stage_c3() {
    stage_up
    log "C3 full sweep — DESTRUCTIVE: deletes journal/snapshot rows below each latest snapshot"
    # shellcheck disable=SC2046
    run_bench 'CleanupSweepBench#c3FullSweep' $(quick_props)
}

stage_wcheck() {
    stage_up
    # W1 inserts and W2 upserts fixed probe pids: run once per load
    # shellcheck disable=SC2046
    run_bench 'AppendBench#w1SingleAppend' $(quick_props)
    # shellcheck disable=SC2046
    run_bench SnapshotBench $(quick_props)
}

stage_w3() {
    stage_up
    # W3 inserts its churn pids from sn 1: once per load, or a new BENCH_W3_PID_PREFIX
    # shellcheck disable=SC2046
    run_bench 'AppendBench#w3SustainedChurn' $(quick_props)
}

stage_c4() {
    stage_up
    # shellcheck disable=SC2046
    run_bench 'CleanupSweepBench#c4BeforeAfterProof' $(quick_props)
}

stage_window() {
    stage_up
    [ "$BACKEND" = pg ] || die "the window stage is PostgreSQL-only: pass --backend pg"
    [ -n "${BENCH_SWEEP_MAX_SECONDS:-}" ] || die "the window stage needs BENCH_SWEEP_MAX_SECONDS"
    log "C5 credit-paced window of ${BENCH_SWEEP_MAX_SECONDS} s — DESTRUCTIVE for the pids it reaches"
    # shellcheck disable=SC2046
    run_bench 'CleanupSweepBench#c5CreditPacedWindow' $(quick_props)
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

stage_mixed_sweep() {
    stage_up
    log "M1 during the mass sweep — DESTRUCTIVE like c3: needs the loaded backlog (no sweep since the load); W3's churn shape"
    # shellcheck disable=SC2046
    run_bench 'MixedChurnBench#m1CleanupDuringMassSweep' $(quick_props)
}

stage_status() {
    for c in "$PG_CONTAINER" "$MONGO_CONTAINER"; do
        if container_running "$c"; then echo "container: $c RUNNING";
        elif container_exists "$c"; then echo "container: $c exists but is STOPPED";
        else echo "container: $c does not exist"; fi
    done
    echo "limits:    $(limits_line)"
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
    echo "results:   ${BENCH_RESULTS_DIR:-${ROOT}/${MODULE}/src/test/resources/bench/results/} (diff against git to compare runs)"
    echo "corpus state note: if 'sweep' has run, journal rows are far below the loaded count — rerun 'load' for a fresh corpus."
}

stage_down() {
    local c containers=()
    [ "$BACKEND" = mongo ] || containers+=("$PG_CONTAINER")
    [ "$BACKEND" = pg ] || containers+=("$MONGO_CONTAINER")
    for c in "${containers[@]}"; do
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
  - duration: load 1-2 h twice (sweep gets its own load), settle up to 1 h per PG load, recovery/write/cleanup ~1 h, mixed-sweep ~30 min, sweep 1-3 h (incl. autovacuum settle), mixed ~25 min
  - the 'sweep' stage is destructive (that is its job); rerun 'load' afterwards for a fresh corpus
EOF
    printf "Proceed? [y/N] "
    read -r answer
    [ "$answer" = y ] || [ "$answer" = Y ] || die "aborted"
}

for stage in "${STAGES[@]}"; do
    case "$stage" in
        config) stage_config ;;
        up) stage_up ;;
        relimit) stage_relimit ;;
        build) stage_build ;;
        load) stage_load ;;
        settle) stage_settle ;;
        smoke) stage_smoke ;;
        recovery) stage_recovery ;;
        write) stage_write ;;
        cleanup) stage_cleanup ;;
        c1) stage_c1 ;;
        c2) stage_c2 ;;
        retention) stage_retention ;;
        payload-whatif) stage_payload_whatif ;;
        sweep) stage_sweep ;;
        c3) stage_c3 ;;
        wcheck) stage_wcheck ;;
        w3) stage_w3 ;;
        c4) stage_c4 ;;
        window) stage_window ;;
        mixed-sweep) stage_mixed_sweep ;;
        mixed) stage_mixed ;;
        all) confirm_full_run; stage_up; stage_load; stage_settle; stage_recovery; stage_write; stage_cleanup; stage_mixed_sweep; stage_load; stage_settle; stage_sweep; stage_mixed ;;
        status) stage_status ;;
        down) stage_down ;;
        *) die "unknown stage: $stage" ;;
    esac
done
