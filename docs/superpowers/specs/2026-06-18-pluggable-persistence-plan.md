# Pluggable Persistence Layer — Implementation Plan

**Date:** 2026-06-18 · **Branch:** `postgres-190626-feat-dev`

## Context

Eclipse Ditto's event-sourcing persistence is MongoDB, wired directly into many shared and
service-level code paths and into HOCON. A PostgreSQL backend already exists on this branch
(`internal/utils/persistence-r2dbc`), but it is not cleanly switchable: several shared/boot paths are
hardwired to Mongo (namespace/entity purge-ops actors, `MongoHealthChecker`, snapshot-streaming
actors, connectivity's cluster-sharding remember-store), the backend selection is scattered across
many config knobs, and the r2dbc module is absent from the published "allinone" jars.

**Goal:** route the event-sourcing persistence touchpoints for `things`, `policies`, `connectivity`
through one abstraction selected by a single per-deployment choice; keep **MongoDB bundled as the lean
default**; distribute **PostgreSQL as an optional drop-in extension JAR** placed in Ditto's existing
`/opt/ditto/extensions/` classpath directory, so the public Docker images stay single-flavor and carry
no unused Postgres driver. The refactor is **behaviour-preserving on Mongo**. The DB is chosen before
deployment; data migration is the operator's responsibility — **no migration tooling is built**.

## Scope

**In scope** — event-sourcing persistence for things/policies/connectivity: journal & snapshot plugin
IDs, read journal, namespace/entity purge-ops, persistence health-check, snapshot-streaming,
cluster-sharding remember-store selection, per-service snapshot-adapter selection, and the
`pekko.persistence.*.auto-start-*` lists.

**Out of scope** — `thingsearch` search index (stays MongoDB), MongoDB driver metrics, connectivity
encryption-migration actors (intentionally retain Mongo), data migration tooling.

## Decisions

1. **Build on** `postgres-190626-feat-dev`; keep `persistence-api`, `PersistenceBackendProvider`, the
   r2dbc module. MongoDB stays the default backend.
2. **Abstraction shape:** `PersistenceBackendProvider` is a composite root (single selection key
   `ditto.extensions.persistence-backend-provider`) returning small, focused collaborators.
3. **Postgres collaborators are REAL — no no-ops.** The only permitted shared no-op is the existing
   `bootstrapSchema()` default that Mongo also inherits. (`PostgresReadJournal` is already a complete
   `DittoReadJournal` — a contract test forbids `UnsupportedOperationException` — so streaming/health/
   purge are all implementable for real.) PostgreSQL runs fully standalone, with no MongoDB required.
4. **Switchability proof = three layers of architectural enforcement** (no second test backend built):
   (a) ArchUnit boundary rules; (b) a boot-time self-check that fails fast if the resolved
   journal/snapshot plugin classes don't match the selected provider's backend family; (c) a HOCON
   lint asserting the Postgres profile carries no Mongo plugin classes/IDs. All existing tests stay
   green; Mongo behaviour is identical.
5. **Packaging:** PostgreSQL ships as a self-contained **extension JAR** dropped into
   `/opt/ditto/extensions/` (already on the image `CLASSPATH`), selected per-deployment by the Postgres
   profile/overlay conf. It is NOT a dependency of any service module and NOT bundled in published
   images.
6. **`reactor-core` pinned to `3.5.20`** in `bom/pom.xml` (mandatory: the bundled `3.5.0` lacks
   `Mono.singleOptional()` that r2dbc calls, and the allinone is first on the classpath). reactor-core
   is NOT excluded from the extension shade — the allinone copy wins by classpath order; bundling both
   at the same version is the safe belt-and-suspenders.
7. **Selection split (honest):** Provider-driven via Java — plugin IDs, read journal, operations,
   health, streaming, snapshot codec, and remember-store IDs (via `ClusterShardingSettings`). Carried
   by the Postgres profile/overlay conf (because Pekko reads them from static HOCON) — the
   `auto-start` lists, `event-adapter-bindings`, and journal/snapshot plugin class blocks. The operator
   flips one unit (include the Postgres profile + provider key + connection), not N scattered knobs.

## Architecture — composite root + collaborators

Single selection key: `ditto.extensions.persistence-backend-provider`. `PersistenceBackendProvider`
keeps its current methods and gains accessors returning focused collaborators. Each collaborator has a
Mongo impl (in the bundled `internal/utils/persistence` module) and a Postgres impl (in the r2dbc
extension jar):

| Accessor | Returns | Mongo impl (bundled) | Postgres impl (extension) |
|---|---|---|---|
| `pluginConfig()` | journal/snapshot IDs + per-service auto-start IDs + remember-store IDs | convention/HOCON | from `extension-config.plugin-ids` |
| `operations(entityType)` | `NamespacePersistenceOperations` / `EntityPersistenceOperations` | `MongoNamespace/EntitiesPersistenceOperations` | new `PostgresNamespace/EntitiesPersistenceOperations` (real SQL) |
| `healthCheck()` | health-actor `Props` | `MongoHealthChecker` | real `SELECT 1` on the pool |
| `streaming(entityType)` | snapshot-streaming `Props` | the generalized actor + Mongo read journal | the generalized actor + Postgres read journal |
| `snapshotCodec()` | snapshot (de)serialization envelope | BSON | JSONB |
| `readJournal()` / `bootstrapSchema()` | unchanged | unchanged | unchanged |

**Enforcement zone (package-based):** Mongo/BSON references are permitted only in
`internal/utils/persistence` and the thingsearch search-index packages; r2dbc/Postgres references are
permitted only in the r2dbc module/extension. `persistence-api` is backend-neutral.

## Implementation phases

Legend: **[S]** sequential prerequisite · **[P]** parallelizable. Critical path: A → (B*, E1) → C* → D* → G.

### Phase A — API surface + neutral relocations [S]
- **A1.** In `persistence-api` add `PersistencePluginConfig` (journal/snapshot IDs + per-service
  auto-start ID accessor + remember-store ID accessor), `PersistenceOperationsFactory`, `SnapshotCodec`;
  add provider accessors with `UnsupportedOperationException` defaults so backends compile incrementally.
- **A2.** Relocate to a neutral package in `persistence-api`: `NamespacePersistenceOperations`,
  `EntityPersistenceOperations` (+ config interfaces), `SnapshotSerializer`, `EventSerializer`,
  `SnapshotAdapter`, **and `EventConfig` + `DefaultEventConfig`** (`EventSerializer.java:28` imports
  `...mongo.config.EventConfig`, so it must move too). Verify each carries no Mongo import afterward.
- **A3.** Decouple shared `persistent-actors`: `AbstractPersistenceActor.java:66-68` imports
  `AbstractMongoEventAdapter`, `...mongo.config.ActivityCheckConfig`, `...mongo.config.SnapshotConfig`.
  Move `ActivityCheckConfig`/`SnapshotConfig` to a neutral package and extract the
  `HISTORICAL_EVENT_HEADERS` constant (used at `:1083`) to a neutral home, so `persistent-actors` no
  longer imports `...persistence.mongo..`.

### Phase B — Mongo implementations (in the bundled `internal/utils/persistence` module) [P after A]
- **B1.** `MongoPersistencePluginConfig` — journal/snapshot IDs + `getAutoStartIds()` returning the
  **exact shipped per-service set** (things → things journal/snaps only, **excluding WoT** —
  `things.conf:609-613`) + the dedicated `*-remember-*` IDs for connectivity.
- **B2.** `MongoPersistenceOperationsFactory` returning a **lazy** `Props`/factory (must not eagerly
  build `MongoClientWrapper`; client construction stays at actor instantiation, as today).
- **B3.** `healthCheck()` → `MongoHealthChecker.props()`.
- **B4.** Generalize the streaming actors to the abstraction: change `SnapshotStreamingActor` (and
  `DefaultPersistenceStreamingActor`) to depend on the `DittoReadJournal` interface instead of the
  concrete `MongoReadJournal` (it only calls `getNewestSnapshotsAbove`, `SnapshotStreamingActor.java:211`),
  and move it to a neutral module so both backends reuse it. Make the close-lifecycle backend-specific
  (Mongo closes its client at `:167`; Postgres has nothing to close — pool owned by
  `PostgresClientExtension`). Mongo `streaming(entityType)` returns it wired with the Mongo read journal.
- **B5.** `MongoSnapshotCodec` — the BSON envelope extracted from `AbstractMongoSnapshotAdapter`. (The
  codec/serializer split is already implemented and parity-tested by `SnapshotAdapterParityTest`.)

### Phase C — Service rewiring (per service, parallel) [P after B]
- **C1.** Root actors call `provider.healthCheck()` / `provider.operations(entityType)` /
  `provider.streaming(entityType)`; keep `startChildActor(<same ACTOR_NAME>, props)` so actor
  names/persistence-ids are unchanged. Sites: `ThingsRootActor:159-162,195-196,198-199`,
  `PoliciesRootActor:82-83,120-121,150`, `ConnectivityRootActor:92-95,126-133,199-203`.
- **C2.** Ops rewire: change the shared `AbstractPersistenceOperationsActor` base to define the
  `Closeable`/`postStop` contract generically (Mongo closes its client; Postgres ops use the shared
  pool, so their `close()` is legitimately empty — the resource isn't owned there). Route
  policies/connectivity ops actors off their hardcoded Mongo plugin-id constants
  (`PolicyPersistenceActor.JOURNAL_PLUGIN_ID`, `ConnectionPersistenceActor.*`) onto
  `provider.operations(entityType)`.
- **C3.** Collapse the per-service `*MongoSnapshotAdapter`/`*PostgresSnapshotAdapter` pair + the
  `ditto.extensions.snapshot-adapter` key into the neutral selection. **Preserve the single-per-JVM,
  non-overridable adapter at `AbstractPersistenceActor:143`** and the fact that **WoT shares the Thing
  adapter** (no WoT-specific adapter exists). The neutral adapter = `provider.snapshotCodec()` + the
  JVM's configured `SnapshotSerializer`. Test: the things JVM round-trips both `Thing` and
  `WotValidationConfig` snapshots identically.

### Phase D — Pekko-native couplings [P after B1]
- **D1.** Keep the static `pekko.persistence.*.auto-start-*` lists; the Postgres profile sets them to
  the Postgres plugin IDs. `provider.pluginConfig().getAutoStartIds()` exists to author/validate the
  `*-pg-dev.conf` and feed the boot self-check (G2); it returns the exact shipped per-service set.
  Test: the configured auto-start set equals the shipped static list per service (WoT excluded for things).
- **D2.** Remember-store: at `ConnectivityRootActor:214` use
  `ClusterShardingSettings…withJournalPluginId(jid).withSnapshotPluginId(sid)` from
  `provider.pluginConfig().getRememberStorePluginIds("connection")` (these setters exist in Pekko 1.6.0
  and feed `EventSourcedRememberEntitiesShardStore`). Mongo returns the dedicated `*-remember-*` IDs
  (`connectivity.conf:1175-1176,1306+`). The Postgres profile sets
  `pekko.cluster.sharding.remember-entities-store = ddata` (a real Pekko mode — no Mongo dependency).

### Phase E — Packaging, reactor pin, generic relocation [P after A]
- **E1.** Pin `io.projectreactor:reactor-core = 3.5.20` in `bom/pom.xml`; declare+pin the extension-only
  transitives the shade must package: `reactor-pool`, `reactor-netty-core`, `com.ongres.scram:client`/
  `common`. Verify the pin reaches the things/policies/connectivity allinones (`dependency:tree`).
- **E2.** Relocate the **generic** backend serializers `PostgresSnapshotAdapter` +
  `AbstractPostgresEventAdapter` from `internal/utils/persistence` into `internal/utils/persistence-r2dbc`
  (possible only after A2). Do **not** move the per-service `*PostgresSnapshotAdapter`/`*PostgresEventAdapter`
  (they import service models → Maven cycle); Phase C dissolves them. Add the **real** Postgres
  collaborators to the r2dbc module: `SnapshotCodec` (JSONB), `PersistencePluginConfig`, a
  `PersistenceOperationsFactory` backed by new `PostgresNamespacePersistenceOperations` +
  `PostgresEntitiesPersistenceOperations` (SQL DELETE-by-namespace/entity reusing the
  `PostgresPersistenceOperations` DELETE primitives at `:229,320,334,521,532`), a real `SELECT 1` health
  Props, and the Postgres `streaming(entityType)` wiring of the generalized actor (B4).
- **E3.** New shaded extension module `internal/utils/persistence-r2dbc-extension` (`jar`, depends on the
  r2dbc module): shade the r2dbc module + relocated generic serializers + profile resources +
  r2dbc-spi/pool/postgresql/proxy + reactor-pool + reactor-netty-core + scram (+ optional
  `netty-transport-native-epoll/kqueue`). **Exclude** everything already in the allinone: `pekko*`, all
  `ditto*` except the r2dbc module, **netty core** (`io.netty:netty-buffer/handler/transport/codec/common`),
  slf4j/logback, jackson, typesafe-config. **Keep reactor-core** (allinone wins by order). Transformers:
  `ServicesResourceTransformer` (required for r2dbc `META-INF/services` driver discovery) +
  `AppendingTransformer(reference.conf)`; no Main-Class. Library jar, publishes via inherited
  `distributionManagement`. Add a Maven `enforcer` `bannedDependencies` rule forbidding the r2dbc/extension
  modules in the three service POMs (so it can never leak into the allinone).

### Phase F — Local-dev config + IntelliJ run configs [P after C/D]
- **F1.** Thin `*-pg-dev.conf` in each `src/main/resources` (next to `*-dev.conf`): top-level
  `include classpath("<svc>-dev")` + `classpath("ditto-postgres-persistence")` +
  `classpath("ditto-postgresql")`, the provider key, the Postgres `event-adapter-bindings` + auto-start
  overrides, `remember-entities-store = ddata` (connectivity), connection settings.
- **F2.** `.run/*(Postgres).run.xml` use `HOSTING_ENVIRONMENT=filebased` +
  `HOSTING_ENVIRONMENT_FILE_LOCATION=$PROJECT_DIR$/<svc>/service/src/main/resources/<svc>-pg-dev.conf`
  (there is no `-Dconfig.resource` in Ditto; the DEVELOPMENT path is hardcoded to `<svc>-dev`).
- **F3.** Supersede `deployment/postgres-local/*-postgres.conf`.

### Phase G — Enforcement: ArchUnit + boot self-check + HOCON lint [S, after B+C+D]
- **G1.** ArchUnit (`archunit-junit5` added to BOM). **Rule 1:** ban `com.mongodb..`, `org.bson..`,
  exact `pekko.contrib.persistence.mongodb..` (legacy `pekko.`, not `org.apache.pekko.` — add a
  meta-test that it matches `MongoReadJournal`), `MongoReadJournal`, `pekko-contrib-mongodb-persistence-*`
  outside the Mongo zone (`...persistence.mongo..` + the thingsearch search-index packages only).
  **Rule 2:** ban `io.r2dbc..`/`org.postgresql..`/`...persistence.postgres..` in service code, the Mongo
  module, and `persistence-api`. **Rule 3:** `persistence-api` references neither backend. **Dependency
  rule:** `persistent-actors` may not depend on `...persistence.mongo..`. **Whitelist:**
  `connectivity...persistence.migration..` + the `MongoClientWrapper` use at `ConnectivityRootActor`
  (encryption-migration is out of scope and intentionally retains Mongo).
- **G2.** Boot-time active-backend self-check: assert the resolved journal/snapshot plugin classes +
  read-journal match the selected provider's expected backend family; fail fast on mismatch.
- **G3.** HOCON lint over the effective `*-pg-dev.conf`/extension profile: assert no
  `pekko.contrib.persistence.mongodb` class strings, no `pekko-contrib-mongodb-persistence-*` IDs, and
  that `read-journal.entity` is set per service.

### Phase H — Operator docs + Helm [P]
- **H1.** Helm: documented `extraVolumes`/`extraVolumeMounts` mounting the extension jar into
  `/opt/ditto/extensions/` (already on `CLASSPATH` — no command-line edit) + the overlay conf;
  initContainer to stage the jar (`deployment/helm/...` already scaffolds `extraVolumes`).
- **H2.** Rewrite `docs/postgres-snapshot-adapter-operator-guide.md` to the drop-in-jar + single-profile
  flow; add a PostgreSQL example to `documentation/.../installation-extending.md`; update
  `deployment/postgres-local/README.md`.

## Critical files
- `internal/utils/persistence-api/.../api/PersistenceBackendProvider.java` (+ new interfaces, neutral relocations).
- `internal/utils/persistence/.../serializer/EventSerializer.java`, `.../mongo/config/EventConfig.java` (A2).
- `internal/utils/persistent-actors/.../AbstractPersistenceActor.java` (A3 decoupling; `:143` adapter selection).
- `internal/utils/persistence/.../mongo/MongoPersistenceBackendProvider.java` + Mongo collaborators (B1–B5).
- `internal/utils/persistence/.../mongo/SnapshotStreamingActor.java` (B4 generalize to `DittoReadJournal`).
- `things/Policies/ConnectivityRootActor.java` (C1; D2 at `ConnectivityRootActor:214`).
- `internal/utils/persistence-r2dbc/` (real Postgres collaborators, E2) + new `-extension` module (E3); `bom/pom.xml` (E1); service POMs (enforcer).
- `things.conf:609`, `policies.conf:291`, `connectivity.conf:1175,1227,1306+`; new `*-pg-dev.conf`; `.run/*(Postgres).run.xml`.

## Verification
- **ArchUnit (G1) + boot self-check (G2) + HOCON lint (G3)** all green — the three-layer switchability proof.
- **Full unit + Testcontainers IT suite green on Mongo** (E1 rebuilds every allinone — run the whole build).
- **Behaviour-identical tests:** per-collaborator (Mongo provider returns pre-refactor plugin IDs,
  `MongoHealthChecker`, streaming/ops actors); auto-start set == shipped static list per service (WoT
  excluded); things-JVM adapter round-trips Thing + WotValidationConfig snapshots identically;
  connections still restore via remember-entities after restart; boot-order test (bootstrapSchema before
  any pool/read-journal materialization).
- **Postgres collaborators (real):** a search-sync IT streaming snapshots end-to-end; a purge-ops test
  asserting `PostgresNamespace/EntitiesPersistenceOperations` SQL deletes the right journal + snapshot
  rows and nothing else; a `SELECT 1` health test.
- **Post-build gate:** `javap -p` confirms the bundled `reactor.core.publisher.Mono` has
  `singleOptional()` in each service allinone; the extension jar retains
  `META-INF/services/io.r2dbc.spi.ConnectionFactoryProvider`.
- **End-to-end local Postgres (single-profile proof):** docker compose Postgres up; build
  `:ditto-things-service,:ditto-internal-utils-persistence-r2dbc-extension -am`; boot via the IntelliJ
  "(Postgres)" run config and via CLI with the extension jar in `extensions/`; write → restart →
  recover, confirm JSONB snapshots; confirm flipping only the Postgres profile switches backends and
  Postgres boots with **no Mongo running** — health=`SELECT 1`, search-sync streaming returns real
  snapshots, `PurgeNamespace`/`PurgeEntities` actually delete Postgres rows, remember-store uses `ddata`.

## Risks / runtime checks
- **Generalized streaming actor:** verify it behaves identically on Mongo, and that
  `PostgresReadJournal.getNewestSnapshotsAbove` returns the `SudoStreamSnapshots` result shape the
  consumer (`ThingsMetadataSource`) expects; handle the close-lifecycle per backend.
- **reactor-core pin** rebuilds all service jars — verify the Mongo reactive path still works and the
  pin actually reaches every allinone.
- **Extension shade:** confirm no netty/reactor-core split-class `LinkageError` (allinone wins by order)
  and that the r2dbc `ServiceLoader` file survives the shade.
- **Postgres purge-ops:** verify the SQL deletes journal + snapshot rows for a namespace/entity and
  nothing else; verify the ops actor `Closeable`/`postStop` contract holds for the Postgres factory.
- **D1/D2 Pekko APIs:** auto-start stays Pekko-native (no `private[pekko]` calls); confirm
  `ClusterShardingSettings.withJournalPluginId/withSnapshotPluginId` wiring at boot.
- **Uncommitted working tree** (~30 modified r2dbc files) — sequence so the refactor doesn't collide.
