#!/usr/bin/env bash
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
# Launch the COMPOUND (Policies + Things + Gateway) on the local PostgreSQL backend from the CLI.
# Policies + Things persist to Postgres; Gateway is stateless routing (no persistence) and just needs the dev
# config with pre-authentication enabled so HTTP PUT/GET work without a token.
#
# Each service logs to /tmp/<svc>-pg.log. Postgres + a one-time build are prerequisites (see README).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"
MVN="${MVN:-/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn}"
OVERLAY_DIR="${REPO_ROOT}/deployment/postgres-local"

R2DBC_JAR="internal/utils/persistence-r2dbc/target/ditto-internal-utils-persistence-r2dbc-0-SNAPSHOT.jar"
CP_CACHE="/tmp/ditto-r2dbc-cp.txt"
if [[ ! -s "$CP_CACHE" ]]; then
  "$MVN" -q -pl :ditto-internal-utils-persistence-r2dbc dependency:build-classpath \
    -Dmdep.outputFile="$CP_CACHE" -Dmdep.includeScope=runtime >/dev/null
fi
R2DBC_DEPS="$(cat "$CP_CACHE")"

common_pg_env() {
  export POSTGRES_SSL_MODE=disable
  export POSTGRES_URI="${POSTGRES_URI:-r2dbc:postgresql://localhost:5432/ditto}"
  export POSTGRES_USER="${POSTGRES_USER:-ditto}"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-ditto}"
  export POSTGRES_DDL_USER="${POSTGRES_DDL_USER:-ditto}"
  export POSTGRES_DDL_PASSWORD="${POSTGRES_DDL_PASSWORD:-ditto}"
}

start_pg_service() { # name allinone-jar main-class overlay-file extra-env-assignments...
  local name="$1" jar="$2" main="$3" overlay="$4"; shift 4
  # r2dbc deps FIRST (newer reactor-core etc. must win over the uber-jar's bundled old copy).
  local cp="${R2DBC_JAR}:${R2DBC_DEPS}:${jar}"
  ( common_pg_env
    export HOSTING_ENVIRONMENT=filebased
    export HOSTING_ENVIRONMENT_FILE_LOCATION="$overlay"
    for kv in "$@"; do export "$kv"; done
    exec java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
      -Xms512m -Xmx512m -cp "$cp" "$main"
  ) > "/tmp/${name}-pg.log" 2>&1 &
  echo "  ${name} pid $! -> /tmp/${name}-pg.log"
}

start_dev_service() { # name allinone-jar main-class extra-env...
  local name="$1" jar="$2" main="$3"; shift 3
  ( for kv in "$@"; do export "$kv"; done
    exec java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
      -Xms512m -Xmx512m -jar "$jar" "$main" 2>/dev/null || \
    exec java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
      -Xms512m -Xmx512m -cp "$jar" "$main"
  ) > "/tmp/${name}-pg.log" 2>&1 &
  echo "  ${name} pid $! -> /tmp/${name}-pg.log"
}

echo "Starting Policies (Postgres) ..."
# No snapshot-adapter override: AbstractPersistenceActor composes the per-service snapshot-serializer with the
# Postgres provider's JSONB snapshot codec automatically once the Postgres persistence profile is included.
start_pg_service policies \
  "policies/service/target/ditto-policies-service-0-SNAPSHOT-allinone.jar" \
  org.eclipse.ditto.policies.service.starter.PoliciesService \
  "${OVERLAY_DIR}/policies-postgres.conf"

echo "Waiting for Policies cluster to form (founder) ..."
sleep 20

echo "Starting Things (Postgres) ..."
# No snapshot-adapter override: the Postgres provider's JSONB snapshot codec is selected automatically.
# things-postgres.conf joins the Policies-founded cluster at 2552 (no seed-nodes override).
start_pg_service things \
  "things/service/target/ditto-things-service-0-SNAPSHOT-allinone.jar" \
  org.eclipse.ditto.things.service.starter.ThingsService \
  "${OVERLAY_DIR}/things-postgres.conf"

echo "Starting Gateway (dev, pre-auth) ..."
HOSTING_ENVIRONMENT="" start_dev_service gateway \
  "gateway/service/target/ditto-gateway-service-0-SNAPSHOT-allinone.jar" \
  org.eclipse.ditto.gateway.service.starter.GatewayService \
  "ENABLE_PRE_AUTHENTICATION=true" "DEVOPS_SECURED=false"

echo "All started. Gateway REST on http://localhost:8080  (use header 'x-ditto-pre-authenticated: nginx:ditto')."
