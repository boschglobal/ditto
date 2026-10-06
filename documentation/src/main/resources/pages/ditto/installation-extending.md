---
title: Extending Ditto
tags: [getting_started, installation]
keywords: running, start, run, docker, docker-compose, extension, custom, configuration, logging
permalink: installation-extending.html
---

You extend Ditto by implementing custom `DittoExtensionPoint` interfaces and loading them into the service via Pekko's classloader.

{% include callout.html content="**TL;DR**: Create a Java class implementing a `DittoExtensionPoint` interface, configure it in a `<service>-extension.conf` file, and add the JAR to `/opt/ditto/extensions` in the Docker container." type="primary" %}

## Overview

Ditto provides extension points throughout its codebase, marked by interfaces that extend `DittoExtensionPoint`. You can replace the default behavior at these points by providing your own implementation.

## How it works

### Creating an extension

Your implementation needs a public constructor that accepts an `ActorSystem` and `Config` parameter, which Pekko's classloader uses for reflection-based instantiation:

```java
public CustomExtension(final ActorSystem actorSystem, final Config config) {}
```

### Configuring an extension

Tell Pekko's classloader which implementation to use by adding the extension's `CONFIG_KEY` to:
* A `<service-name>-extension.conf` file for service-specific extensions
* The `reference.conf` for a global scope

Each extension configuration has two parts:
* `extension-class`: The fully qualified class name of your implementation
* `extension-config`: Custom configuration for the extension (optional)

```hocon
ditto.extensions.signal-enrichment-provider {
  extension-class = org.eclipse.ditto.gateway.service.endpoints.utils.DefaultGatewaySignalEnrichmentProvider
  extension-config = {
    cache {
      enabled = true
      maximum-size = 20000
      expire-after-create = 2m
    }
  }
}
```

If your extension needs no custom configuration, use the shorthand form:

```hocon
ditto.extensions.signal-enrichment-provider = org.eclipse.ditto.gateway.service.endpoints.utils.DefaultGatewaySignalEnrichmentProvider
```

## Configuration

### Adjusting service configuration

For simple configuration changes, use [system properties](operating-configuration.html). For extensive changes, create a [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) file named `<ditto-service-name>-extension.conf` and place it in the Docker container's working directory:

| Service | Extension config path |
|---------|----------------------|
| Policies | `/opt/ditto/policies-extension.conf` |
| Things | `/opt/ditto/things-extension.conf` |
| Search | `/opt/ditto/search-extension.conf` |
| Connectivity | `/opt/ditto/connectivity-extension.conf` |
| Gateway | `/opt/ditto/gateway-extension.conf` |

These files can contain any configuration from the [service config files](operating-configuration.html).

For example, the [gateway.conf](https://github.com/eclipse-ditto/ditto/blob/master/gateway/service/src/main/resources/gateway.conf)
contains the following health-check configuration:

```hocon
ditto {
  gateway {
    health-check {
      cluster-roles = {
        enabled = true
        enabled = ${?HEALTH_CHECK_ROLES_ENABLED}

        expected = [
          "policies",
          "things",
          "search",
          "gateway",
          "connectivity"
        ]
      }
    }
  }
}
```

To remove the "connectivity" role from the health check (e.g. when not starting `ditto-connectivity`
at all), create a `gateway-extension.conf` with:

```hocon
ditto.gateway.health-check.cluster-roles = {
  expected = [
    "policies",
    "things",
    "search",
    "gateway"
  ]
}
```

Then mount the file into the container at `/opt/ditto/gateway-extension.conf`.

### Adding JARs to the classpath

Ditto Docker images automatically add all JARs from these directories to the classpath:

* `/opt/ditto`
* `/opt/ditto/extensions`

Build your extension as a JAR (including extension classes and config files) and place it in the `extensions` directory.

## Examples

### Custom API routes

1. Create a new implementation of `CustomApiRoutesProvider`, overriding the `unauthorized(*)` and `authorized(*)` functions to return custom HTTP API routes.
2. Build the project into a `gateway-extension.jar`.
3. Add the JAR to the container:
   ```bash
   docker cp gateway-extension.jar container_id:/opt/ditto/extensions/
   ```
4. Create a `gateway-extension.conf`:
   ```hocon
   ditto.extensions.custom-api-routes-provider = org.company.project.gateway.service.endpoints.utils.MyCustomApiRoutesProvider
   ```
5. Add the config to the container:
   ```bash
   docker cp gateway-extension.conf container_id:/opt/ditto/
   ```

Alternatively, mount both files via docker-compose:

```yaml
connectivity:
  image: docker.io/eclipse/ditto-gateway:${DITTO_VERSION:-latest}
  environment:
    - TZ=Europe/Berlin
    - JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=/opt/ditto/logback.xml
  volumes:
    - ./gateway-extension.conf:/opt/ditto/gateway-extension.conf
    - ./logback.xml:/opt/ditto/logback.xml
    - ./gateway-extension.jar:/opt/ditto/extensions/gateway-extension.jar
```

### PostgreSQL persistence backend

By default, Ditto stores Things, Policies, and Connectivity data in MongoDB.
You can switch any service to a PostgreSQL backend by dropping in the extension JARs and mounting a service-specific activation config.
MongoDB remains the default — this is strictly opt-in.

**PostgreSQL version:** PostgreSQL 16 or newer (tested: 16, 17, 18). Older versions log a warning at startup, but the service still starts; technically, persistence needs PostgreSQL ≥ 10 and search PostgreSQL ≥ 13.

#### 1. Provide the extension JARs

The extension JARs are **not** included in the default Ditto images.
The PostgreSQL backend ships as **layered drop-in JARs** built from three Maven modules — a shared **base** JAR carrying all third-party runtime (R2DBC driver/pool, reactor, netty, scram) and **thin** backend JARs carrying only Ditto implementation classes:

| Module / JAR | Contents | Mounted by |
|---|---|---|
| `ditto-postgres-client-extension` (base) | shared `postgres-client` infra + ALL third-party (r2dbc-postgresql, r2dbc-pool, reactor, netty incl. resolver-dns, scram) | every Postgres service |
| `ditto-postgres-persistence-extension` (thin) | only the event-sourcing persistence classes | things / policies / connectivity |
| `ditto-postgres-search-extension` (thin) | only the thing-search classes | thing-search (only if search runs on Postgres — see the search section below) |

A persistence service therefore mounts **two** JARs: the base plus the matching thin JAR. Build them (from the ditto repo root) and drop them into the extensions directory:

```bash
# build the base + the persistence thin JAR (each is a shaded JAR; the thin one rides on top of the base)
mvn -pl :ditto-postgres-client-extension,:ditto-postgres-persistence-extension -am -DskipTests package

# copy BOTH into the container (or use a volume mount — see docker-compose example below)
docker cp \
  internal/utils/postgres-client-extension/target/ditto-postgres-client-extension-<version>.jar \
  container_id:/opt/ditto/extensions/
docker cp \
  internal/utils/postgres-persistence-extension/target/ditto-postgres-persistence-extension-<version>.jar \
  container_id:/opt/ditto/extensions/
```

{% include note.html content="All mounted PostgreSQL extension JARs **must come from the same Ditto release**. A boot self-check (a version marker in each JAR) fails fast on a version mismatch, and a thin JAR mounted without its base fails fast with an actionable *requires base `ditto-postgres-client-extension`* error." %}

#### 2. Create a service activation config

Create a HOCON overlay file (e.g. `things-postgres.conf`) for each service you want to migrate.
The top-level `include` statement is **required outside any `ditto {}` block** because the Postgres persistence config ships its own top-level Pekko plugin blocks that Pekko must see at the config root.

```hocon
# things-postgres.conf
# Injected via: HOSTING_ENVIRONMENT=filebased
#               HOSTING_ENVIRONMENT_FILE_LOCATION=/opt/ditto/things-postgres.conf

# 1. Re-include the service base settings (filebased replaces the normal environment layer)
include classpath("things-dev")

# 2. TOP-LEVEL include — NOT inside ditto {} — because ditto-postgres-persistence.conf ships its
#    own top-level Pekko plugin blocks (ditto-postgres-things-journal { }, ...) that Pekko must
#    see at the config root. This single include wires the provider selection, the per-entity
#    Pekko plugin blocks, AND the ditto.postgresql.* client/pool/SSL defaults (the client config
#    used to live in a separate ditto-postgresql.conf; it is now folded into this one file).
include classpath("ditto-postgres-persistence")

# Narrow Pekko's auto-start lists to this service's own entity. ditto-postgres-persistence.conf
# defaults these lists to all 4 entities (things/policies/connections/wot), which only boots where
# every entity's plugin-dispatcher is defined (an all-in-one config). A single-entity service such
# as things only defines the thing-* dispatchers, and Pekko resolves every auto-started plugin's
# dispatcher eagerly at Persistence-extension init — an un-narrowed list crashes the first
# persistent actor on a missing dispatcher for the other entities. This narrowing step is REQUIRED
# for every service overlay, not optional.
pekko.persistence.journal.auto-start-journals               = [ "ditto-postgres-things-journal" ]
pekko.persistence.snapshot-store.auto-start-snapshot-stores = [ "ditto-postgres-things-snapshots" ]

# Placeholder Mongo collection names so Mongo-era ops actors construct harmlessly
ditto-postgres-things-journal.overrides   { journal-collection = "things_journal"; metadata-collection = "things_metadata" }
ditto-postgres-things-snapshots.overrides { snaps-collection = "things_snaps" }
```

This overlay also activates the Postgres persistence backend provider via:

```hocon
ditto.extensions.persistence-backend-provider {
  extension-class = org.eclipse.ditto.internal.utils.persistence.postgres.PostgresPersistenceBackendProvider
}
```

That key is already set inside `ditto-postgres-persistence.conf` (shipped inside the extension JAR); you do not need to repeat it unless you want to override it.

Snapshot encoding switches automatically when the Postgres backend is active — no per-service snapshot adapter override is needed.

#### 3. Set environment variables

| Variable | Purpose | Example value |
|---|---|---|
| `HOSTING_ENVIRONMENT` | Switches Ditto's config loader to file-based mode | `filebased` |
| `HOSTING_ENVIRONMENT_FILE_LOCATION` | Path to the overlay config inside the container | `/opt/ditto/things-postgres.conf` |
| `POSTGRES_URI` | R2DBC connection URI | `r2dbc:postgresql://postgres:5432/ditto` |
| `POSTGRES_USER` | Database user | `ditto` |
| `POSTGRES_PASSWORD` | Database password | `ditto` |
| `POSTGRES_SSL_MODE` | TLS mode (`disable`, `require`, `verify-full`) | `disable` (dev) / `verify-full` (prod) |
| `POSTGRES_POOL_MAX_SIZE` | Connections per service instance (default `25`), see **Connection budget** below | `25` |

Schema tables are created automatically on first boot.

**Statement timeout and PgBouncer.** `ditto.postgresql.statement-timeout` (`POSTGRES_STATEMENT_TIMEOUT`, default `60s`) is sent as a connection startup parameter. PgBouncer, in session and transaction mode, refuses the connection unless `statement_timeout` is listed in `track_extra_parameters` (the value then applies) or in `ignore_startup_parameters` (the value is then dropped). This was verified with PgBouncer 1.26.0; how PgBouncer tracks a parameter that PostgreSQL does not report to clients, such as `statement_timeout`, may differ between versions. The driver also always sends `extra_float_digits`, so `ignore_startup_parameters` must list it in either case, e.g. `ignore_startup_parameters = extra_float_digits,statement_timeout`. For the case in which the value is dropped, the provisioning script `deployment/postgres/provisioning/01-roles-and-grants.sql` gives the runtime role `ditto_app` a `statement_timeout` default of `60s`, which PostgreSQL applies to every server session opened as that role; PgBouncer must therefore log in as the client's role (no `user=` in its `[databases]` entry; an `auth_user=` is fine). Because of that default, `statement-timeout = 0` (no startup parameter) means "use the role default", not "no timeout". The DDL role deliberately has no such default, so the schema bootstrap and per-path index builds are not cut off by it; without a separate DDL role (`POSTGRES_DDL_USER`), the bootstrap runs as the runtime role and its default applies. Details, including how to lift the timeout: `deployment/postgres/README.md`.

**Persistence ping.** The persistence ping (policies: `always-alive` policies in ID order; connectivity: all connections in priority order; by default one entity per second) runs no statement, transaction or snapshot on PostgreSQL while it waits, so `statement_timeout` applies to one short statement at a time. The ID order reads windows of `persistence-ping.read-journal-batch-size` entity ids (default 500) and keeps the tagged ones: a window costs the same wherever it lies and however the tagged entities are spread (about 1 ms at 1,000,000 entities with 2 events each), and a walk takes (all ids / batch size) statements and reads every journal row of the entities it examines, once per walk: with many entities, few of them always-alive, or long event histories, size the ping `interval` for that (10,000,000 policies: about 20,000 statements per walk, about 20–160 s of database time at 2 events per entity). The priority order reads all connections in one statement per walk, held in memory for the walk (about 80–100 bytes per connection, about 10 MB for 100,000). In ID order an entity that starts to qualify after the walk's position (the last window read) is pinged in this walk, otherwise in the next one, and one deleted before the walk reaches it is not pinged; the priority order pings the connections as of its statement. A failed statement is retried from the same position with the MongoDB read journal's back-off (1 s doubling to 128 s, 10 % jitter, 10 retries for the default `interval` of `10m`; unlike MongoDB, slow failures count too, so the retries end) and logged at WARN with its SQLSTATE; when the retries are exhausted, the walk ends with a WARN and the next one starts at the next `interval`, in ID order after the last persistence id the failed walk reached (kept in memory, so after a restart from the beginning); if that continued walk fails before it reaches a new persistence id (a failure that recurs there, or an outage still going on), the position is given up and the next walk starts from the beginning. A persistence id that is no entity id is skipped with a WARN instead of failing the walk. At one entity per second, 1,000,000 always-alive policies take about 11.6 days per walk on either backend; to walk faster, raise `persistence-ping.rate.entities` (e.g. `POLICIES_PERSISTENCE_PING_RATE_ENTITIES`, `RECONNECT_RATE_ENTITIES`). Details: `deployment/postgres/README.md`, section "Persistence ping on PostgreSQL".

**Circuit breakers and overload.** The PostgreSQL journal and snapshot-store plugins use the MongoDB plugins' Pekko circuit-breaker settings and the same environment variables: journal 5 failures, 5 s call timeout, 3 s reset (`BREAKER_MAXTRIES`, `BREAKER_TIMEOUT`, `BREAKER_RESET`); snapshot store 5 failures, 10 s call timeout, 6 s reset (`SNAPSHOT_BREAKER_MAXTRIES`, `SNAPSHOT_BREAKER_TIMEOUT`, `SNAPSHOT_BREAKER_RESET`). The Helm chart always sets these variables (`<service>.config.mongodb.journalCircuitBreaker` / `snapsCircuitBreaker`), so with Helm its values apply to either backend. A borrow that the connection pool rejects (`pool.pending-acquire-max-size` reached) or that times out counts as a breaker failure; every recovery starts with a snapshot load, so under overload the snapshot-store breaker usually opens first, and while it is open every recovery in that service fails at once. On either backend, a request whose entity cannot be recovered or written because of overload is answered with HTTP 503 (the entity's `…unavailable` error, `things:thing.notcreatable` for a thing create whose implicit policy is unavailable, or `enforcement.timeout`), not 500; retry it later.

**Connection budget.** Each instance of a PostgreSQL-backed service holds one connection pool: the journal, snapshot store and read journal of policies, things and connectivity share it, and things-search on PostgreSQL has its own. A pool holds at most `ditto.postgresql.pool.max-size` connections (`POSTGRES_POOL_MAX_SIZE`, default `25`). The pools of different services and replicas do not coordinate, so size them together, counting the extra pods of a rolling update: the sum over policies, things, connectivity and things-search (when on PostgreSQL) of `(replicas + maxSurge) × max-size`, plus reserved connections, must not exceed PostgreSQL's `max_connections`. `maxSurge` is the number of extra pods a Deployment starts while it rolls: the Helm chart rolls these four services with `maxSurge: 1` and `maxUnavailable: 0`, and a `helm upgrade` that changes all four rolls them at the same time; while the old pod hands its entities over, its pool is still open, and the new pod recovers them with a pool of its own. Reserved connections are `superuser_reserved_connections`, monitoring, `psql`, and, per service instance, the connections Ditto opens outside its pool: a DDL connection for the schema bootstrap and the schema self-heal, and in things-search the per-path index maintenance at every start (two connections while an index builds). Example: two Kubernetes nodes with one replica each of the four services and `max_connections = 300`, about 20 of them kept for administration: 4 × 25 = 100 in steady state and 4 × (1 + 1) × 25 = 200 during an upgrade, both ≤ 280; scaling things and policies to 3 replicas and things-search to 2 gives (3 + 3 + 1 + 2) × 25 = 225 in steady state but (4 + 4 + 2 + 3) × 25 = 325 during an upgrade, which does not fit; at `max-size = 20` the same replicas take 180 and 260. A stock PostgreSQL (`max_connections = 100`, 3 of them superuser-reserved) holds the three persistence services at one replica each only in steady state (75): rolling them takes up to 150 at once, and still 100 one at a time. So for a stock server, and for things-search or more replicas, raise `max_connections`, lower `max-size` per service or put PgBouncer in front. Beyond `max_connections`, PostgreSQL refuses connections (SQLSTATE 53300, "too many clients"), and recoveries and writes fail. A pool opens connections on demand, at most `max-size` at a time, so a burst can take its whole share at once; idle connections are closed after 30 minutes. Override the size per service with `POSTGRES_POOL_MAX_SIZE` (the pending-acquire queue follows at 2 × `max-size`), with Helm via the service's `extraEnv`:

```yaml
things:
  extraEnv:
    - name: POSTGRES_POOL_MAX_SIZE
      value: "40"
```

Raise a service's `max-size` only when its pool is the measured bottleneck (`ditto_persistence_pool_pending` above 0 for long, "PostgreSQL pool overload" for journal writes, `PoolAcquirePendingLimitException` for recoveries, reads and search, or acquire-timeout errors while PostgreSQL has headroom), and re-check the sum first. `25` is a starting point; confirm production sizing by load testing. For many replicas, put PgBouncer in transaction mode in front of PostgreSQL. Sizing behind PgBouncer and the PgBouncer settings Ditto needs: `deployment/postgres/README.md`, section "Connection budget".

#### 4. Docker Compose volume mount example

```yaml
things:
  image: docker.io/eclipse/ditto-things:${DITTO_VERSION:-latest}
  environment:
    - HOSTING_ENVIRONMENT=filebased
    - HOSTING_ENVIRONMENT_FILE_LOCATION=/opt/ditto/things-postgres.conf
    - POSTGRES_URI=r2dbc:postgresql://postgres:5432/ditto
    - POSTGRES_USER=ditto
    - POSTGRES_PASSWORD=ditto
    - POSTGRES_SSL_MODE=disable   # or verify-full in production with a CA cert
  volumes:
    # 1. Drop-in extension JARs (NOT in the default image — must be provided by the operator; base + persistence thin JAR)
    - ./ditto-postgres-client-extension.jar:/opt/ditto/extensions/ditto-postgres-client-extension.jar
    - ./ditto-postgres-persistence-extension.jar:/opt/ditto/extensions/ditto-postgres-persistence-extension.jar
    # 2. Activation overlay config
    - ./things-postgres.conf:/opt/ditto/things-postgres.conf
```

Repeat the same pattern for the Policies and Connectivity services, substituting the appropriate plugin IDs, overlay conf name, and collection name overrides for each service.

{% include note.html content="Data migration from MongoDB is the operator's responsibility. No automated migration tooling is provided." %}

### PostgreSQL search backend

Thing-search can **optionally** run its search index on PostgreSQL instead of MongoDB, independently of which backend the persistence services use. MongoDB remains the default search backend — this is strictly opt-in and only affects the `things-search` service.

**PostgreSQL version:** PostgreSQL 16 or newer (tested: 16, 17, 18). Older versions log a warning at startup, but the service still starts; technically, persistence needs PostgreSQL ≥ 10 and search PostgreSQL ≥ 13 (from 13 on, `pg_trgm` is a trusted extension, see [Prerequisites](#prerequisites-migration-and-known-limitations)).

#### 1. Provide the extension JARs

Running search on Postgres mounts **two** JARs: the SAME shared `ditto-postgres-client-extension` base the persistence services use, plus the thin `ditto-postgres-search-extension` (search read/write/aggregation classes only):

```bash
mvn -pl :ditto-postgres-client-extension,:ditto-postgres-search-extension -am -DskipTests package

docker cp \
  internal/utils/postgres-client-extension/target/ditto-postgres-client-extension-<version>.jar \
  container_id:/opt/ditto/extensions/
docker cp \
  internal/utils/postgres-search-extension/target/ditto-postgres-search-extension-<version>.jar \
  container_id:/opt/ditto/extensions/
```

#### 2. Create the search activation config

Create a HOCON overlay (e.g. `search-postgres.conf`) with two top-level includes — the search service defaults, then the opt-in Postgres search profile which swaps the `search-persistence-provider` extension and pulls in the shared `ditto.postgresql.*` client/pool/SSL defaults:

```hocon
# search-postgres.conf
# Injected via: HOSTING_ENVIRONMENT=filebased
#               HOSTING_ENVIRONMENT_FILE_LOCATION=/opt/ditto/search-postgres.conf

# 1. Re-include the search service base settings (Mongo search backend by default)
include classpath("search")

# 2. TOP-LEVEL include — opt-in PostgreSQL search backend + shared client/pool/SSL defaults.
#    Swaps ditto.extensions.search-persistence-provider to the PostgreSQL provider; search.conf's own
#    ditto.mongodb defaults legitimately remain present (the overlay only swaps the one extension key).
include classpath("ditto-postgres-search")
```

Environment variables are the same `POSTGRES_*` set as for the persistence backend (`POSTGRES_URI`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_SSL_MODE`). Compose mount:

```yaml
things-search:
  image: docker.io/eclipse/ditto-things-search:${DITTO_VERSION:-latest}
  environment:
    - HOSTING_ENVIRONMENT=filebased
    - HOSTING_ENVIRONMENT_FILE_LOCATION=/opt/ditto/search-postgres.conf
    - POSTGRES_URI=r2dbc:postgresql://postgres:5432/ditto
    - POSTGRES_USER=ditto
    - POSTGRES_PASSWORD=ditto
    - POSTGRES_SSL_MODE=disable   # or verify-full in production with a CA cert
  volumes:
    # base + search thin JAR (both from the same Ditto release)
    - ./ditto-postgres-client-extension.jar:/opt/ditto/extensions/ditto-postgres-client-extension.jar
    - ./ditto-postgres-search-extension.jar:/opt/ditto/extensions/ditto-postgres-search-extension.jar
    - ./search-postgres.conf:/opt/ditto/search-postgres.conf
```

#### Operational tunables

The PostgreSQL search backend adds a small set of search-specific knobs on top of the shared client block. All have safe defaults; override them only if a deployment needs to.

| Config path | Default | Env override | Purpose |
|---|---|---|---|
| `ditto.postgresql.search.reaper.interval` | `30s` | `POSTGRES_SEARCH_REAPER_INTERVAL` | How often the `delete_at` reaper attempts a tick (Postgres has no TTL index; the reaper replaces Mongo's TTL deletion). |
| `ditto.postgresql.search.reaper.batch-size` | `1000` | `POSTGRES_SEARCH_REAPER_BATCH_SIZE` | Rows deleted per reap batch (`DELETE … FOR UPDATE SKIP LOCKED` LIMIT), bounding one statement's lock footprint. |
| `ditto.postgresql.search.reaper.max-batches-per-tick` | `50` | `POSTGRES_SEARCH_REAPER_MAX_BATCHES_PER_TICK` | Safety valve: at most this many batches per tick, so one tick's transaction is never held open against an arbitrarily large backlog. |
| `ditto.postgresql.force-custom-plan` | `true` (for search) | `POSTGRES_SEARCH_FORCE_CUSTOM_PLAN` | Binds `plan_cache_mode=force_custom_plan` on the search JVM's pool so `wpath`-parameterized read statements re-plan against their actual bound path instead of latching onto a generic plan. Also required for the per-path indexes below to be used. |
| `ditto.postgresql.search.path-indexes.paths` | unset (not managed) | `POSTGRES_SEARCH_PATH_INDEXES` (a JSON array) | Per-path search indexes, see [Per-path search indexes](#per-path-search-indexes-optional). Unset: none is built or dropped; `[]`: all are dropped. |
| `ditto.postgresql.search.path-indexes.direct-uri` | `""` (use `ditto.postgresql.uri`) | `POSTGRES_SEARCH_PATH_INDEXES_DIRECT_URI` | A direct (non-PgBouncer) r2dbc URI of the same database for building the per-path indexes. Required with `pooler-mode = transaction`. |

Pool sizing, SSL and credentials are **not** search-specific — they come from the shared `ditto.postgresql.*` client block (the same one the persistence backend uses), tuned via `POSTGRES_URI` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DDL_*`, `POSTGRES_SSL_MODE` and `POSTGRES_POOL_MAX_SIZE`. The search service's pool counts toward the same connection budget as the persistence services' (see **Connection budget** in the persistence section above).

#### Per-path search indexes (optional)

`like`/`ilike` with a pattern of at least three letters or digits is served by one table-wide trigram index, `sf_trgm`, over the text values of **all** paths. On a path that many things carry, typically a property of every feature (`features/*/properties/…`), such a query first collects the matching values of all paths and then discards those on other paths, and the planner may additionally intersect them with a scan of every row of that path. For such hot paths you can configure a per-path trigram index: a partial index restricted to one path, which the planner uses instead of `sf_trgm` for `like`/`ilike` on exactly that path. It is the PostgreSQL counterpart of the MongoDB custom search indexes.

```hocon
ditto.postgresql.search.path-indexes {
  paths = [
    { path = "features/*/properties/serialNumber", kind = "trigram" }
    { path = "attributes/location/city", kind = "trigram" }
  ]
}
```

The same list as an environment variable, or as system properties:

```shell
POSTGRES_SEARCH_PATH_INDEXES='[{"path":"features/*/properties/serialNumber","kind":"trigram"}]'

-Dditto.postgresql.search.path-indexes.paths.0.path=features/*/properties/serialNumber
-Dditto.postgresql.search.path-indexes.paths.0.kind=trigram
```

* `path`: the search field exactly as you write it in an RQL filter: `attributes/…`, `features/<featureId>/properties/…`, `features/*/properties/…`, `…/desiredProperties/…`, `features/<featureId or *>/definition`, or a field mapped to one of these. `features/*/properties/x` and `features/env/properties/x` are different paths: index the form your queries use. `thingId` and `namespace` are columns of `search_things`, not paths, and are rejected.
* `kind`: `trigram`, the only kind so far.
* An invalid entry (an unknown field, a missing or unknown `kind`, a duplicate) fails the start of the search service.
* **Unset versus empty.** While `paths` is not set (the default; a blank `POSTGRES_SEARCH_PATH_INDEXES` counts as not set), the service manages no per-path index: it builds none and drops none, and logs (INFO) which `sf_path_*` indexes exist. Only a set list is applied, and an explicitly empty list (`paths = []`, or `POSTGRES_SEARCH_PATH_INDEXES='[]'`) drops every `sf_path_*` index.

For each entry the search service builds:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS sf_path_trgm_<hash> ON search_flat
    USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops)
    WHERE wpath = '<the path as stored in search_flat>' AND val_text IS NOT NULL;
```

* **Name:** `sf_path_trgm_` followed by 16 hex digits of the SHA-256 of the index definition. It is the same on every instance and at every start; the service log names the path of every index it builds.
* **Semantics:** the indexed expression is the one of `sf_trgm`, so the prerequisites and the matching are the same (the `pg_trgm` extension and the `C.utf8` collation, see the `like`/`ilike` notes below). The index changes which index serves a query, never which things match.
* **Planning:** the planner can only use a per-path index when it sees the queried path as a value, i.e. with custom plans. Keep `ditto.postgresql.force-custom-plan` at its search default `true`; under a generic plan the per-path indexes are never used.

**How and when the indexes are built.** They are not part of the versioned (checksummed) search schema.

* After every successful schema bootstrap, the search service compares the configured list with the `sf_path_*` indexes on `search_flat`, in the background: the service starts serving right away, and until a new index is valid, queries on its path keep using `sf_trgm`.
* If nothing has to change, that costs one catalog query: no lock, no DDL.
* Otherwise one instance takes a session advisory lock (the others skip), sets `statement_timeout = 0` and `lock_timeout = 0` for its own session, and builds one index at a time with `CREATE INDEX CONCURRENTLY`. The build by itself does not block reads or writes of the running instances. The build phase and progress are logged every 30 seconds.
* Nothing is changed while the stored search schema is newer than the running code (during a rolling upgrade the newer code manages the indexes), and nothing at all when the schema bootstrap refused to start.
* **Repair:** a `CREATE INDEX CONCURRENTLY` that fails or is cancelled, or whose database backend is killed, leaves an INVALID index behind, which `IF NOT EXISTS` would skip forever and which still costs write overhead. The service drops it right away if it can, and otherwise drops and rebuilds it at the next start of any instance.
* If the service stops during a build, PostgreSQL keeps building until the build is done, unless a search instance that starts meanwhile deadlocks the build (see below); meanwhile no other instance starts a second one.

**Do not start, restart or roll search instances while a per-path index is built or dropped.** Every start of a search instance runs the schema bootstrap, which re-runs `CREATE INDEX IF NOT EXISTS` for the built-in indexes of `search_things` and `search_flat` in one transaction. On `search_flat` those statements wait for the lock that a running build (or a `DROP INDEX CONCURRENTLY` of a removed path) holds for its whole duration. Measured on PostgreSQL 16:

* The starting instance waits in its bootstrap. Meanwhile **writes to `search_things` and `search_flat` stall for all search instances** (the waiting bootstrap already holds a lock on `search_things`, and new writes to `search_flat` queue behind its pending lock request); reads continue. This lasts up to the bootstrap's statement timeout (`ditto.postgresql.statement-timeout`, 60 s by default). Then that start fails, and the next start attempt stalls writes again, until the build is done.
* Or PostgreSQL's deadlock detector aborts the **build** instead (when the build reaches its last phase while the bootstrap waits): the start succeeds, the index is left INVALID, and it is dropped and rebuilt at the next start of an instance (another full build).
* This also happens when a single instance is restarted during its own build: PostgreSQL keeps building, and the restarted instance's bootstrap waits for that build as described.

What to do:

* Add or remove paths at a quiet time, and roll the change out so that no further search instance starts while the build runs: for example, scale the search service to one instance, change the list, wait until the build has finished, then scale back up. Later restarts with an unchanged list build and drop nothing, with one exception: if a build failed and left an INVALID index that could not be dropped right away, the next start repairs it, which is a full build again; treat that start like a path change (no further starts until `Built search path index …`).
* Do not restart or roll search instances (and avoid node drains) while a build or drop is running.
* To see whether one is running:
  * in the log of the instance that builds: `Building search path index sf_path_… (i/n)` and a progress line every 30 seconds, until `Built search path index …` and `Search path index changes applied`;
  * in PostgreSQL: `SELECT pid, phase, blocks_done, blocks_total FROM pg_stat_progress_create_index WHERE relid = 'search_flat'::regclass;` returns a row while a build runs, and `SELECT pid, query FROM pg_stat_activity WHERE query LIKE 'DROP INDEX CONCURRENTLY IF EXISTS sf_path_%';` while a drop runs. An `sf_path_*` index with `pg_index.indisvalid = false` is being built, or is the leftover of a failed build.

**A direct database connection is required.** The build runs on a dedicated, non-pooled connection (never one of the search pool) as the DDL role (`ddl-credentials`, else the runtime role), which must own `search_flat`. The advisory lock, the session settings and the build have to run in **one** server session. Transaction-mode PgBouncer cannot carry that: it may run every statement on a different server connection, so the lock could stay behind on a pooled server connection, `statement_timeout = 0` could leak into other clients' transactions, and the build would run under the server's timeout.

* With a direct connection or session-mode pooling, leave `direct-uri` empty.
* With `ditto.postgresql.pooler-mode = transaction`, set `ditto.postgresql.search.path-indexes.direct-uri` (`POSTGRES_SEARCH_PATH_INDEXES_DIRECT_URI`) to a direct r2dbc URI of the same database (the credentials and SSL settings of `ditto.postgresql` apply to it). Without it, the service logs an error and changes no index.
* A `statement_timeout` or `lock_timeout` role default on the DDL role is overridden for the build session only.

**Removing a path drops its index.** At the next start the service drops (`DROP INDEX CONCURRENTLY`) every `sf_path_*` index whose path is no longer in the (set) list, as Ditto drops a MongoDB custom search index that is removed from `custom-indexes`. A per-path index costs maintenance on every write to its path, so a forgotten one would cost write throughput without serving any query.

* Only per-path indexes are dropped. This rests on the names: the service only drops indexes of `search_flat` whose name starts with `sf_path_`, a prefix no other index of the search schema uses, and the hash in the name keeps different paths and definitions apart. Do not give your own indexes that prefix.
* **All search instances that share the database must use the same list** (one configuration source, changed in one rollout). With different lists, each instance drops at its start what its list lacks and builds what it adds, so the indexes are dropped and rebuilt again at every start, and each rebuild reads the whole table. During a rolling change this happens once: an instance with the old list does not rebuild a dropped index before its own next start.
* An instance that must not manage the indexes (for example a blue/green twin on the same database) leaves `paths` unset; it then only logs at INFO which `sf_path_*` indexes it found and leaves them alone.

**Build time and cost.** On the 1M-thing search benchmark corpus (68.5 million `search_flat` rows, 4.5 million of them on `/features/*/properties/prop0`; PostgreSQL 16 defaults, `maintenance_work_mem` 64 MB, a laptop VM), building the index for `features/*/properties/prop0` took **44 seconds**, with much of the table in the page cache, and the index is **51 MB** (the table-wide `sf_trgm`: 304 MB). Infix `ilike` on that path then took **111.2 / 111.1 ms p95** in two runs (96.6 / 95.7 ms p50), instead of **305.8 / 325.5 ms p95** (197.6 / 187.8 ms p50) in two runs without it. Measurements and plans: `internal/utils/search-r2dbc/src/test/resources/bench/results/production-config/path-index/README.md`.

* A build reads the whole `search_flat` heap twice (build and validation scan), however few rows the path has, so its duration grows with the whole table.
* A larger `maintenance_work_mem` for the DDL role speeds up GIN builds, e.g. `ALTER ROLE <ddl role> SET maintenance_work_mem = '512MB'`.
* Every write to a configured path also updates its index.

#### Prerequisites, migration and known limitations

* **`pg_trgm` extension:** the search schema requires the `pg_trgm` extension (used for `like`/`ilike` trigram indexes). It is a *trusted* extension on PostgreSQL ≥ 13, so `CREATE EXTENSION IF NOT EXISTS pg_trgm` on the target database succeeds for the DDL role without superuser; the schema bootstrap fails with an actionable error if the role is not permitted to create it.
* **Migration = re-index only:** the search index is a rebuildable projection of the things data, so the cutover to Postgres is simply to point search at an empty PostgreSQL database and let the background-sync stream regenerate the index. There is no copy tooling and none is needed. Search results are incomplete until the initial re-index (background sync) finishes — plan this re-index window into the cutover.
* **Speeding up very large re-indexes (optional):** every row written during the initial re-index pays incremental maintenance on the `sf_trgm` trigram GIN index, and a bulk GIN build after the data is loaded is much faster than the same work done insert-by-insert. For very large populations an operator may `DROP INDEX sf_trgm` before starting the backfill and recreate it once the re-index has caught up:

  ```sql
  CREATE INDEX CONCURRENTLY IF NOT EXISTS sf_trgm ON search_flat
      USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops) WHERE val_text IS NOT NULL;
  ```

  While the index is absent, `like`/`ilike` queries fall back to scans — everything else is unaffected. Recreate it **before** the next search-service restart: the schema bootstrap re-runs `CREATE INDEX IF NOT EXISTS sf_trgm` at every start, so a restart would otherwise rebuild it as a plain (blocking, non-concurrent) build against the by-then-populated table. This is purely an optimisation — by default GIN's `fastupdate` pending list amortises per-insert cost, and the re-index works fine with the index in place.
* **`like`/`ilike` performance caveat:** sub-3-character `like`/`ilike` patterns cannot be served by the trigram index and degrade to a scan — mirroring MongoDB's own unanchored-regex degradation. The slow-query log catches abuse. `ilike` on a path that many things carry is slow with the table-wide `sf_trgm` alone: on the 1M-thing benchmark corpus, infix `ilike` on `features/*/properties/prop0` took **305.8–391.5 ms p95** in three runs (most patterns about 200 ms, some 300–420 ms when the planner adds a scan of all rows of the path). Configure a [per-path search index](#per-path-search-indexes-optional) for such paths.
* **Sort performance caveat:** sorting by any field other than `thingId` or `namespace` (an attribute, a feature property such as `sort(+features/env/properties/temperature)`, `_modified`, …) cannot be served by an index. To return any page, the search looks up the sort value of **every** thing that matches the filter and is visible to the caller, then orders them. The cost grows with the number of matching visible things, not with the page size, and neither `limit(offset,size)` nor cursor paging reduces it.
  * On the 1M-thing search benchmark (PostgreSQL 16 defaults: 128 MB `shared_buffers`, `jit = on`; no filter; about 29,400 things visible to the caller), one sorted page took about **350 ms p95** for page 1 and about **360–390 ms p95** for page 100 via `limit`. Page 100 via the cursor took about **680 ms p95**. That is above the 200 ms target the other read shapes are measured against.
  * The cursor page costs more because its resume filter is evaluated for every visible thing.
  * For fast sorted searches, sort by `thingId` (the default) or `namespace`. Both are index-served: on the same corpus, page 1 took 0.3 ms p95 (warm). Deep pages via `limit` still cost in proportion to the skipped things (about 130 ms p95 at page 100). Otherwise, narrow the matching set with a selective filter.
  * About 200 ms of the cursor page is JIT compilation (`jit = on` is the PostgreSQL default). With `jit = off`, that page took about 460 instead of 670 ms p95 on the same corpus.
  * Measurements, SQL and plans: `internal/utils/search-r2dbc/src/test/resources/bench/results/production-config/sort-path-bound/` (`README.md` and `psql-checks.md`).
* **Mongo-only search knobs:** search settings specific to MongoDB (e.g. per-metric MongoDB index hints, custom MongoDB search indexes) are ignored (logged with a WARN) when the PostgreSQL search backend is active. Per-path PostgreSQL indexes are configured separately, see [Per-path search indexes](#per-path-search-indexes-optional).

{% include note.html content="A `search-update-mapper` custom extension implementation must be re-typed to the backend-neutral write model to work with this release — see the release notes for the `SearchUpdateMapper` breaking change and migration guide. Custom `search-update-observer` implementations need no change." %}

#### Background-sync and updater-stream settings — identical on PostgreSQL

The `ditto.search.updater` settings listed below are consumed by the backend-neutral service layer
(`SearchUpdaterRootActor`, `BackgroundSyncActor`, `ThingUpdater`, `PolicyModificationForwarder`, `EnforcementFlow`)
and therefore behave exactly the same whether the index lives in MongoDB or PostgreSQL. The `ditto-postgres-search`
profile does not define or override any `ditto.search.*` key, so the usual environment overrides keep working
unchanged. (`stream.persistence.with-acks-writeConcern` is a MongoDB write concern and has no effect on PostgreSQL.)

| Setting (`ditto.search.updater.…`) | Env override | On PostgreSQL |
|---|---|---|
| `event-processing-active` | `EVENT_PROCESSING_ACTIVE` | identical (service-level flag, not backend-specific) |
| `background-sync.enabled` | `BACKGROUND_SYNC_ENABLED` | identical — gates the background-sync actor's wake-up |
| `background-sync.quiet-period` | `BACKGROUND_SYNC_QUIET_PERIOD` | identical — start-up delay and bookmark interval; the bookmark is the single row of the `search_sync` table (the MongoDB capped-collection equivalent) |
| `background-sync.tolerance-window` | `BACKGROUND_SYNC_TOLERANCE_WINDOW` | identical — compares against the thing's `_modified` timestamp, which the PostgreSQL backend stores in `search_things.t_modified` (`timestamptz`, microsecond precision) |
| `stream.write-interval` | `THINGS_SEARCH_UPDATER_STREAM_WRITE_INTERVAL` | identical — the per-thing write tick lives in the updater actor; both backends receive one write per tick |
| `stream.policy-cache.retry-delay`, `stream.thing-cache.retry-delay` | `THINGS_SEARCH_UPDATER_STREAM_POLICY_CACHE_RETRY_DELAY`, `THINGS_SEARCH_UPDATER_STREAM_THING_CACHE_RETRY_DELAY` | identical — enforcer-cache retry back-off in the shared enforcement flow (the policy-cache delay is parsed but currently not consumed; only the thing-cache delay drives the retry) |

The only PostgreSQL-specific search tunables are the ones listed under *Operational tunables* above (including the per-path indexes); there is no
Postgres-side override of any background-sync or stream setting.

## Further reading

* [Operating - Configuration](operating-configuration.html)
* [Architecture Overview](architecture-overview.html)
