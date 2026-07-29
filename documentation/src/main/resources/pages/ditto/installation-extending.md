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

Schema tables are created automatically on first boot.

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
| `ditto.postgresql.force-custom-plan` | `true` (for search) | `POSTGRES_SEARCH_FORCE_CUSTOM_PLAN` | Binds `plan_cache_mode=force_custom_plan` on the search JVM's pool so `wpath`-parameterized read statements re-plan against their actual bound path instead of latching onto a generic plan. |

Pool sizing, SSL and credentials are **not** search-specific — they come from the shared `ditto.postgresql.*` client block (the same one the persistence backend uses), tuned via `POSTGRES_URI` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DDL_*` and `POSTGRES_SSL_MODE`.

#### Prerequisites, migration and known limitations

* **`pg_trgm` extension:** the search schema requires the `pg_trgm` extension (used for `like`/`ilike` trigram indexes). It is a *trusted* extension on PostgreSQL ≥ 13, so `CREATE EXTENSION IF NOT EXISTS pg_trgm` on the target database succeeds for the DDL role without superuser; the schema bootstrap fails with an actionable error if the role is not permitted to create it.
* **Migration = re-index only:** the search index is a rebuildable projection of the things data, so the cutover to Postgres is simply to point search at an empty PostgreSQL database and let the background-sync stream regenerate the index. There is no copy tooling and none is needed. Search results are incomplete until the initial re-index (background sync) finishes — plan this re-index window into the cutover.
* **`like`/`ilike` performance caveat:** sub-3-character `like`/`ilike` patterns and high-cardinality-path `ilike` cannot be fully served by the trigram index and degrade to a scan — mirroring MongoDB's own unanchored-regex degradation. The slow-query log catches abuse. Per-path scoped trigram indexes are under evaluation as a possible future mitigation but are not part of this release.
* **Mongo-only search knobs:** search settings specific to MongoDB (e.g. per-metric MongoDB index hints, custom MongoDB search indexes) are ignored (logged with a WARN) when the PostgreSQL search backend is active.

{% include note.html content="A `search-update-mapper` custom extension implementation must be re-typed to the backend-neutral write model to work with this release — see the release notes for the `SearchUpdateMapper` breaking change and migration guide. Custom `search-update-observer` implementations need no change." %}

## Further reading

* [Operating - Configuration](operating-configuration.html)
* [Architecture Overview](architecture-overview.html)
