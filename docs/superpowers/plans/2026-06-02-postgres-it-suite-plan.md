# Postgres real IT suite (Plan 1) — Implementation Plan

> **2026-06-18 reconciliation:** This plan (written 2026-06-02) predates the pluggable-persistence refactor and was realigned to the impl plan, which is the source of truth: `docs/superpowers/specs/2026-06-18-pluggable-persistence-plan.md`. Where the two conflict, the impl plan wins. **This IT suite now depends on the pluggable-persistence refactor (impl-plan Phases A–G) landing first** — the refactor dissolves the per-service `*PostgresSnapshotAdapter` classes and the `ditto.extensions.snapshot-adapter` config key (impl-plan C3/E2), generalizes streaming to the `DittoReadJournal` interface (B4), and adds the real Postgres collaborators (`operations()`, `healthCheck()`, `streaming()`, `snapshotCodec()`) that Tasks 7/10 and the new 10A–10C exercise. See the **sequencing gate** in Phase 0. Edits made in this reconciliation: Phase 0 (gate + merge marked done), Task 7 (snapshot-adapter override removed, single-system collapse), Task 10 (override removed, Mongo→Ditto read-journal mock), new Tasks 10A/10B/10C, Tasks 11/12 + Self-Review updated.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reusable Postgres real-flow IT harness and a `things` reference suite (flows A–H) that drives genuine domain events/state through the real `event-adapter-bindings`, the `PersistenceBackendProvider`, and a real persistent actor against a real Postgres — plus make Docker mandatory so a Docker-less run fails instead of silently passing.

**Architecture:** A new test-jar from `internal/utils/persistence-r2dbc` exports an abstract `PostgresEventSourceITBase` (container `@ClassRule`, schema bootstrap, PG-profile `ActorSystem`, row inspectors) and a generic `ProviderWiredTestActor` (resolves plugin IDs through the provider, persists/recovers any event). `things/service` adds it as a test dependency and supplies thin ITs that feed real `ThingEvent`s/`Thing` snapshots. The existing 7 ITs lose their `Assume`-skip so container-absence is a build failure.

**Tech Stack:** Java 25, Pekko 1.6.0 persistence, JUnit 4 (`ExternalResource`/`@ClassRule`), Testcontainers `postgresql`, r2dbc-pool/`io.r2dbc.spi`, Reactor (`Mono`/`Flux`), Maven 3.9.3 (`/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn`), Mockito.

**Conventions:** EPL-2.0 header on new `.java`, year **2026** ("Contributors to the Eclipse Foundation"); `git commit -s` (Signed-off-by required by hook); artifact versions only in `bom/pom.xml`.

**Branch:** The `pg-r2-integration-final` → `postgres-190626-feat-dev` merge is already DONE (`2fc7dd9ba1`, 2026-06-04). Per the Phase 0 **sequencing gate**, branch `postgres-it-suite` off the **refactored** base (after impl-plan Phases A–G land), NOT off `2fc7dd9ba1`. All tasks run on `postgres-it-suite`.

**Maven shorthand used below:** `MVN=/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn`

---

## Phase 0 — Prerequisite (gated on explicit user go-ahead; not a code task)

- [x] **DONE (2026-06-04):** `git merge --no-ff pg-r2-integration-final` → `postgres-190626-feat-dev` landed as merge **`2fc7dd9ba1`**. The round-2 remediation is now on `postgres-190626-feat-dev`.

> **Sequencing gate (2026-06-18 reconciliation):** The pluggable-persistence refactor (impl-plan `docs/superpowers/specs/2026-06-18-pluggable-persistence-plan.md`, Phases A–G) MUST land **before** this IT suite. Tasks 7 and 10 and the new Tasks 10A–10C exercise **post-refactor** classes/keys — the dissolved `snapshot-adapter` key (C3), the real Postgres `operations()`/`healthCheck()`/`streaming()` collaborators (E2), and the `DittoReadJournal`-generalized streaming (B4). Therefore branch `postgres-it-suite` off the **refactored** base, NOT off `2fc7dd9ba1`. (Tasks 1–6, 8, 9 are refactor-agnostic and could in principle land earlier, but keep the suite as one unit for a single clean green run.)

- [ ] After the refactor lands: `git checkout <refactored-base> && git checkout -b postgres-it-suite`.
- [ ] Sanity: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc verify` is GREEN on the refactored base before adding any new ITs (Docker running).

> Do NOT start Task 1 until the refactor has landed, the IT-suite branch is cut off the refactored base, and the baseline IT run is green.

---

## Task 1: Make Docker mandatory (remove the silent-skip from the 7 existing ITs)

Removes the `try { POSTGRES.start(); } catch (Throwable t) { Assume.assumeNoException(...); }` guard everywhere so container-absence FAILS (W4/L-3). Each edit drops the try/catch (keep the bare `start()`) and the now-unused `import org.junit.Assume;`.

**Files (all under `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/`):**
- Modify: `PostgresPersistenceIT.java`, `PostgresParityMatrixIT.java`, `PostgresPluginLifecycleIT.java`, `PostgresProviderBootstrapIT.java`, `PgBouncerTransactionPoolingIT.java`, `schema/PostgresSchemaManagerIT.java`, `migration/PostgresMigratorIT.java`

- [ ] **Step 1: Edit `PostgresPersistenceIT.java`** — replace the guard (was `:75-81`):

```java
    @BeforeClass
    public static void startContainer() {
        POSTGRES.start();
```
Also delete the line `import org.junit.Assume;`.

- [ ] **Step 2: Edit `PostgresParityMatrixIT.java`** (was `:106-112`):

```java
    @BeforeClass
    public static void startContainer() {
        POSTGRES.start();
```
Delete `import org.junit.Assume;`.

- [ ] **Step 3: Edit `PostgresPluginLifecycleIT.java`** (was `:65-71`):

```java
    @BeforeClass
    public static void startContainerAndBootSchema() {
        POSTGRES.start();
```
Delete `import org.junit.Assume;`.

- [ ] **Step 4: Edit `PostgresProviderBootstrapIT.java`** (was `:66-74`, note `@Before` instance scope):

```java
    @Before
    public void startContainer() {
        postgres.start();
        verifyFactory = postgres.newConnectionFactory();
    }
```
Delete `import org.junit.Assume;`.

- [ ] **Step 5: Edit `schema/PostgresSchemaManagerIT.java`** (was `:43-49`):

```java
    @BeforeClass
    public static void startContainer() {
        POSTGRES.start();
        connectionFactory = POSTGRES.newConnectionFactory();
    }
```
Delete `import org.junit.Assume;`.

- [ ] **Step 6: Edit `migration/PostgresMigratorIT.java`** (was `:66-72`):

```java
    @BeforeClass
    public static void startContainer() {
        POSTGRES.start();
```
Delete `import org.junit.Assume;`.

- [ ] **Step 7: Edit `PgBouncerTransactionPoolingIT.java`** — remove the outer `try`/`catch` around the two `.start()` calls (was `:76-104`); keep the container construction and both `postgres.start();` / `pgbouncer.start();`. Delete `import org.junit.Assume;`. The method body becomes (verbatim, sans try/catch wrapper):

```java
    @BeforeClass
    public static void start() {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(PostgresDbResource.DEFAULT_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withDatabaseName(PG_DB)
                .withUsername(PG_USER)
                .withPassword(PG_PASSWORD);
        postgres.start();

        pgbouncer = new GenericContainer<>(DockerImageName.parse("edoburu/pgbouncer:latest"))
                .withNetwork(network)
                .withNetworkAliases("pgbouncer")
                .withEnv("DB_HOST", "postgres")
                .withEnv("DB_PORT", "5432")
                .withEnv("DB_USER", PG_USER)
                .withEnv("DB_PASSWORD", PG_PASSWORD)
                .withEnv("DB_NAME", PG_DB)
                .withEnv("POOL_MODE", "transaction")
                .withEnv("AUTH_TYPE", "scram-sha-256")
                .withEnv("MAX_CLIENT_CONN", "100")
                .withExposedPorts(PGBOUNCER_PORT)
                .waitingFor(Wait.forListeningPort());
        pgbouncer.start();
    }
```

- [ ] **Step 8: Compile + run the module ITs with Docker up**

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc verify`
Expected: BUILD SUCCESS, IT count unchanged, **0 skips** for the Postgres ITs (was 1 skip from the `@Ignore`d migrator-Mongo test — that one is `@Ignore`, not `Assume`, so it stays skipped; the *container-gated* skips must be gone).

- [ ] **Step 9: Prove it now FAILS without Docker** (sentinel check)

Run (Docker stopped): `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc verify`
Expected: BUILD FAILURE (container start throws — no longer an assumption-skip). Restart Docker afterwards.

- [ ] **Step 10: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres
git commit -s -m "test(persistence): require Docker for Postgres ITs (remove silent Assume-skip)"
```

---

## Task 2: Export the harness as a test-jar from `persistence-r2dbc`

`persistence-r2dbc/pom.xml` has no `<build>` section. Add a `maven-jar-plugin` `test-jar` execution that ships the harness package so `things/service` can consume it (mirrors `internal/utils/persistence/pom.xml:228-244`).

**Files:** Modify `internal/utils/persistence-r2dbc/pom.xml`

- [ ] **Step 1: Add a `<build>` block** immediately before `</project>` (after `</dependencies>` at line 125):

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>test-jar</goal>
                        </goals>
                        <configuration>
                            <includes>
                                <include>org/eclipse/ditto/internal/utils/persistence/postgres/PostgresDbResource*</include>
                                <include>org/eclipse/ditto/internal/utils/persistence/postgres/it/**</include>
                            </includes>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
```

(The harness classes created in Task 3 live in the new package `...postgres.it`, so the `it/**` include carries them; `PostgresDbResource` is included explicitly.)

- [ ] **Step 2: Build the test-jar**

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc clean install -DskipTests`
Expected: BUILD SUCCESS, and `target/` contains `ditto-internal-utils-persistence-r2dbc-<ver>-tests.jar`.

- [ ] **Step 3: Commit**

```bash
git add internal/utils/persistence-r2dbc/pom.xml
git commit -s -m "build(persistence): publish persistence-r2dbc test-jar for the Postgres IT harness"
```

---

## Task 3: `ProviderWiredTestActor` — generic actor that resolves plugin IDs via the provider

A persistent actor whose `journalPluginId()`/`snapshotPluginId()` resolve **through `PersistenceBackendProvider`** for a given entity type (the real C-3 path), persists any event object handed to it, snapshots any state object, and reports recovered events + snapshot to a probe. Entity-agnostic (no `things` dependency).

**Files:**
- Create: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/ProviderWiredTestActor.java`

- [ ] **Step 1: Write the actor**

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.internal.utils.persistence.postgres.it;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.Props;
import org.apache.pekko.persistence.AbstractPersistentActor;
import org.apache.pekko.persistence.RecoveryCompleted;
import org.apache.pekko.persistence.SnapshotOffer;
import org.eclipse.ditto.internal.utils.config.ScopedConfig;
import org.eclipse.ditto.internal.utils.persistence.api.PersistenceBackendProvider;

/**
 * Generic persistent actor for Postgres real-flow ITs. Unlike the legacy {@code BootTestActor}, it does NOT hardcode
 * plugin IDs: it resolves {@link #journalPluginId()}/{@link #snapshotPluginId()} through the real
 * {@link PersistenceBackendProvider} for the given entity type (the C-3 path). It persists whatever event object it is
 * told to and reports recovered state to a probe.
 */
public final class ProviderWiredTestActor extends AbstractPersistentActor {

    /** Command: persist {@code event}, then reply {@code "persisted"} to the sender. */
    public record Persist(Object event) {}

    /** Command: take a snapshot of {@code state}, then reply {@code "snapshotted"} on success. */
    public record Snapshot(Object state) {}

    /** Reported to {@code probe} on {@link RecoveryCompleted}: the replayed events and the offered snapshot (if any). */
    public record Recovered(List<Object> events, @Nullable Object snapshot) {}

    private final String persistenceId;
    private final String entityType;
    private final ActorRef probe;
    private final List<Object> recoveredEvents = new ArrayList<>();
    @Nullable private Object recoveredSnapshot;
    @Nullable private ActorRef pendingSnapshotSender;

    private ProviderWiredTestActor(final String persistenceId, final String entityType, final ActorRef probe) {
        this.persistenceId = persistenceId;
        this.entityType = entityType;
        this.probe = probe;
    }

    public static Props props(final String persistenceId, final String entityType, final ActorRef probe) {
        return Props.create(ProviderWiredTestActor.class, persistenceId, entityType, probe);
    }

    private PersistenceBackendProvider backendProvider() {
        return PersistenceBackendProvider.get(context().system(),
                ScopedConfig.dittoExtension(context().system().settings().config()));
    }

    @Override
    public String persistenceId() {
        return persistenceId;
    }

    @Override
    public String journalPluginId() {
        return backendProvider().getJournalPluginId(entityType);
    }

    @Override
    public String snapshotPluginId() {
        return backendProvider().getSnapshotPluginId(entityType);
    }

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder()
                .match(SnapshotOffer.class, offer -> recoveredSnapshot = offer.snapshot())
                .match(RecoveryCompleted.class, completed ->
                        probe.tell(new Recovered(List.copyOf(recoveredEvents), recoveredSnapshot), getSelf()))
                .matchAny(recoveredEvents::add)
                .build();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Persist.class, cmd -> {
                    final ActorRef who = getSender();
                    persist(cmd.event(), persisted -> who.tell("persisted", getSelf()));
                })
                .match(Snapshot.class, cmd -> {
                    pendingSnapshotSender = getSender();
                    saveSnapshot(cmd.state());
                })
                .match(org.apache.pekko.persistence.SaveSnapshotSuccess.class, ok -> {
                    if (pendingSnapshotSender != null) {
                        pendingSnapshotSender.tell("snapshotted", getSelf());
                        pendingSnapshotSender = null;
                    }
                })
                .match(org.apache.pekko.persistence.SaveSnapshotFailure.class, fail -> {
                    if (pendingSnapshotSender != null) {
                        pendingSnapshotSender.tell(new org.apache.pekko.actor.Status.Failure(fail.cause()), getSelf());
                        pendingSnapshotSender = null;
                    }
                })
                .build();
    }
}
```

- [ ] **Step 2: Compile**

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/ProviderWiredTestActor.java
git commit -s -m "test(persistence): ProviderWiredTestActor (resolves plugin IDs via provider)"
```

---

## Task 4: `PostgresEventSourceITBase` — abstract harness (container, schema, config, row inspectors)

The reusable base every per-service real-flow IT extends. Entity-agnostic. Owns the `@ClassRule` container, bootstraps the schema, builds the PG-profile `ActorSystem` (loading `ditto-postgres-persistence.conf` so the `event-adapter-bindings` + provider `plugin-ids` are present), and exposes row inspectors that read `tags`/`manifest`/`event`/`written_at` (journal) and `snapshot`/`lifecycle`/`written_at` (snaps).

**Files:**
- Create: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/PostgresEventSourceITBase.java`

- [ ] **Step 1: Write the base class**

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.internal.utils.persistence.postgres.it;

import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.internal.utils.persistence.postgres.PostgresDbResource;
import org.eclipse.ditto.internal.utils.persistence.postgres.schema.PostgresSchemaManager;
import org.junit.AfterClass;
import org.junit.ClassRule;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reusable base for Postgres real-flow integration tests. Subclasses supply the entity type and drive real domain
 * events/state through {@link ProviderWiredTestActor}; this base owns the container, schema bootstrap, the PG-profile
 * actor system, and row-level inspectors so assertions read the actual persisted columns (tags/manifest/lifecycle/
 * written_at), not just the payload.
 */
public abstract class PostgresEventSourceITBase {

    @ClassRule
    public static final PostgresDbResource POSTGRES = new PostgresDbResource();

    private static ConnectionFactory ddlFactory;
    private static ActorSystem system;

    /** A persisted journal row. */
    public record JournalRow(long sn, String manifest, List<String> tags, String event, @Nullable Object writtenAt) {}

    /** A persisted snapshot row. */
    public record SnapshotRow(long sn, String snapshot, @Nullable String lifecycle, @Nullable Object writtenAt) {}

    /** Bootstrap the schema + boot the PG-profile actor system once per IT class. Call from a {@code @BeforeClass}. */
    protected static synchronized ActorSystem bootSystem(final String actorSystemName) {
        if (system == null) {
            ddlFactory = POSTGRES.newConnectionFactory();
            PostgresSchemaManager.of(ddlFactory).bootstrap();
            system = ActorSystem.create(actorSystemName, pgProfileConfig());
        }
        return system;
    }

    @AfterClass
    public static synchronized void shutdownSystem() {
        if (system != null) {
            org.apache.pekko.testkit.javadsl.TestKit.shutdownActorSystem(system);
            system = null;
        }
        ddlFactory = null;
    }

    protected static ActorSystem system() {
        return system;
    }

    /**
     * The PG-profile config: connection settings inline (pointing at the container) + the real
     * {@code ditto-postgres-persistence.conf} (provider selection, plugin-ids, event-adapter-bindings) +
     * {@code ditto-postgresql.conf} defaults + the application classpath.
     */
    protected static Config pgProfileConfig() {
        final String inline = ""
                + "ditto.postgresql {\n"
                + "  uri = \"r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getPort() + "/"
                + POSTGRES.getDatabaseName() + "\"\n"
                + "  username = \"" + PostgresDbResource.DDL_USER + "\"\n"
                + "  password = \"" + PostgresDbResource.DDL_PASSWORD + "\"\n"
                + "  ssl.mode = \"disable\"\n"
                + "}\n"
                // pool warm-up + connect can exceed the 3s TestKit default; give recovery room.
                + "pekko.test.single-expect-default = 25s\n";
        return ConfigFactory.parseString(inline)
                .withFallback(ConfigFactory.parseResources("ditto-postgres-persistence.conf"))
                .withFallback(ConfigFactory.parseResources("ditto-postgresql.conf"))
                .withFallback(ConfigFactory.load())
                .resolve();
    }

    // ----- row inspectors (read the real columns; entity-prefixed table, e.g. "things") -----

    protected static List<JournalRow> journalRows(final String entityPrefix, final String pid) {
        final String table = entityPrefix + "_journal";
        return blockList("SELECT sn, manifest, tags, event::text AS ev, written_at FROM " + table
                        + " WHERE pid = '" + pid + "' ORDER BY sn",
                row -> new JournalRow(
                        row.get("sn", Long.class),
                        row.get("manifest", String.class),
                        List.of((String[]) row.get("tags", String[].class)),
                        row.get("ev", String.class),
                        row.get("written_at")));
    }

    protected static List<SnapshotRow> snapshotRows(final String entityPrefix, final String pid) {
        final String table = entityPrefix + "_snaps";
        return blockList("SELECT sn, snapshot::text AS snap, lifecycle, written_at FROM " + table
                        + " WHERE pid = '" + pid + "' ORDER BY sn, written_at",
                row -> new SnapshotRow(
                        row.get("sn", Long.class),
                        row.get("snap", String.class),
                        row.get("lifecycle", String.class),
                        row.get("written_at")));
    }

    private static <T> List<T> blockList(final String sql, final java.util.function.Function<Row, T> mapper) {
        final List<T> result = Mono.usingWhen(Mono.from(ddlFactory.create()),
                        conn -> Flux.from(conn.createStatement(sql).execute())
                                .flatMap(r -> r.map((row, meta) -> mapper.apply(row)))
                                .collectList(),
                        Connection::close)
                .block(Duration.ofSeconds(20));
        return result == null ? List.of() : result;
    }

    protected static void runDdl(final String sql) {
        Mono.usingWhen(Mono.from(ddlFactory.create()),
                        conn -> Flux.from(conn.createStatement(sql).execute()).flatMap(Result::getRowsUpdated).then(),
                        Connection::close)
                .block(Duration.ofSeconds(30));
    }
}
```

- [ ] **Step 2: Compile**

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Rebuild the test-jar** (so `things/service` sees the new classes)

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/PostgresEventSourceITBase.java
git commit -s -m "test(persistence): PostgresEventSourceITBase real-flow IT harness"
```

---

## Task 5: Wire `things/service` test scope to the harness

Add the three test deps so `things/service` ITs can boot the Postgres plugins + harness. No cycle: `persistence-r2dbc` does not depend on `things/service`.

**Files:** Modify `things/service/pom.xml`

- [ ] **Step 1: Add dependencies** (in the `<dependencies>` block, near the other `ditto-internal-utils-persistence` test deps around line 205):

```xml
        <dependency>
            <groupId>org.eclipse.ditto</groupId>
            <artifactId>ditto-internal-utils-persistence-r2dbc</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.ditto</groupId>
            <artifactId>ditto-internal-utils-persistence-r2dbc</artifactId>
            <scope>test</scope>
            <type>test-jar</type>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Resolve + compile things/service test sources**

Run: `$MVN -o -pl :ditto-things-service test-compile`
Expected: BUILD SUCCESS (deps resolve; `PostgresDbResource`/`PostgresEventSourceITBase`/`ProviderWiredTestActor` importable).

- [ ] **Step 3: Commit**

```bash
git add things/service/pom.xml
git commit -s -m "build(things): add persistence-r2dbc + testcontainers test deps for Postgres ITs"
```

---

## Task 6: `ThingPostgresRoundTripIT` — flows A (boot via provider), B (event round-trip + tags), C (recover)

A concrete IT in `things/service` extending the harness. Persists a real `ThingCreated` through `ProviderWiredTestActor` (which resolves `ditto-postgres-things-journal` via the provider, whose `event-adapter-bindings` route `ThingEvent` → `ThingPostgresEventAdapter`), asserts the JSONB row carries the event AND the journal tags, then restarts and asserts the event is replayed as a real `ThingEvent`.

**Files:**
- Create: `things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresRoundTripIT.java`

- [ ] **Step 1: Write the IT**

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.PoisonPill;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.PostgresEventSourceITBase;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.ProviderWiredTestActor;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Attributes;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.eclipse.ditto.things.model.signals.events.ThingCreated;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Real-flow IT for the {@code things} Postgres backend: a genuine {@link ThingCreated} is persisted through the
 * configured {@code event-adapter-bindings} (provider-resolved journal plugin), its JSONB row + tags are asserted,
 * and it is replayed across a restart as a real {@code ThingEvent}. Covers flows A (boot via provider), B (event
 * round-trip + tags), C (recover across restart).
 */
public final class ThingPostgresRoundTripIT extends PostgresEventSourceITBase {

    private static final String ENTITY_TYPE = "thing";   // provider plugin-id key (singular)
    private static final String ENTITY_PREFIX = "things"; // table prefix (plural)
    private static final Set<String> JOURNAL_TAGS = Set.of("always-alive", "priority");

    @BeforeClass
    public static void boot() {
        bootSystem("ThingPostgresRoundTripIT");
    }

    private static ThingCreated sampleEvent(final ThingId id) {
        final Thing thing = Thing.newBuilder()
                .setId(id)
                .setCreated(Instant.parse("2021-02-24T14:17:37.581679843Z"))
                .setModified(Instant.parse("2021-02-24T14:17:37.581679843Z"))
                .setLifecycle(ThingLifecycle.ACTIVE)
                .setRevision(1)
                .setPolicyId(PolicyId.of(id))
                .setAttributes(Attributes.newBuilder().set("hello", "cloud").build())
                .build();
        final DittoHeaders headers = DittoHeaders.newBuilder().journalTags(JOURNAL_TAGS).build();
        return ThingCreated.of(thing, 1L, Instant.parse("2021-02-24T14:17:37.581679843Z"), headers, null);
    }

    @Test
    public void persistsRealThingEventThroughTheAdapterBindingWithTags() {
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:roundtrip-b");
        final String pid = "thing:" + id;
        final ThingCreated event = sampleEvent(id);

        new TestKit(sys) {{
            final ActorRef actor = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            actor.tell(new ProviderWiredTestActor.Persist(event), getRef());
            expectMsgEquals("persisted");
        }};

        // Flow B: the JSONB row carries the event payload AND the journal tags + the event-type manifest.
        final List<JournalRow> rows = journalRows(ENTITY_PREFIX, pid);
        assertThat(rows).hasSize(1);
        final JournalRow row = rows.get(0);
        assertThat(row.sn()).isEqualTo(1L);
        assertThat(row.manifest()).isEqualTo(event.getType());
        assertThat(row.tags()).containsExactlyInAnyOrderElementsOf(JOURNAL_TAGS);
        assertThat(row.event()).contains("\"hello\"").contains("cloud");
    }

    @Test
    public void recoversRealThingEventAcrossRestart() {
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:roundtrip-c");
        final String pid = "thing:" + id;
        final ThingCreated event = sampleEvent(id);

        new TestKit(sys) {{
            final ActorRef first = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            // first instance recovers empty
            expectMsgClass(ProviderWiredTestActor.Recovered.class);
            first.tell(new ProviderWiredTestActor.Persist(event), getRef());
            expectMsgEquals("persisted");
            watch(first);
            first.tell(PoisonPill.getInstance(), getRef());
            expectTerminated(first);

            // Flow C: a fresh instance replays the event through fromJournal as a real ThingEvent.
            final ActorRef second = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            final ProviderWiredTestActor.Recovered recovered = expectMsgClass(ProviderWiredTestActor.Recovered.class);
            assertThat(recovered.events()).hasSize(1);
            assertThat(recovered.events().get(0)).isInstanceOf(ThingEvent.class);
            final ThingCreated replayed = (ThingCreated) recovered.events().get(0);
            assertThat(replayed.getThing().getEntityId()).contains(id);
        }};
    }
}
```

> **Note on flow A:** booting `ProviderWiredTestActor` at all proves flow A — its `journalPluginId()` resolves through `PersistenceBackendProvider.getJournalPluginId("thing")`. If the provider/plugin-ids are misconfigured, `actorOf` + first recover throws and both tests fail. No separate test method needed.

- [ ] **Step 2: Run the IT (Docker up)**

Run: `$MVN -o -pl :ditto-things-service verify -Dit.test=ThingPostgresRoundTripIT -DfailIfNoTests=false`
Expected: PASS. If `recoversRealThingEventAcrossRestart` returns a bare `String` instead of a `ThingEvent`, the `java.lang.String` read-binding is missing for things — that is exactly the C-1 regression this IT guards; fix the binding in `ditto-postgres-persistence.conf` before proceeding.

- [ ] **Step 3: Verify non-vacuity** — temporarily change `JOURNAL_TAGS` assertion to `containsExactly("nope")` and confirm the test FAILS, then revert. (Proves the row inspector really reads the stored tags.)

- [ ] **Step 4: Commit**

```bash
git add things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresRoundTripIT.java
git commit -s -m "test(things): Postgres real-flow IT — event round-trip + tags + recover (A/B/C)"
```

---

## Task 7: `ThingPostgresSnapshotIT` — flow D (snapshot round-trip; JSONB not BSON)

Saves a real `Thing` snapshot through the `PostgresSnapshotStore`, restarts, and asserts the recovered snapshot is a real `Thing` and the stored column is JSONB (not BSON).

> **2026-06-18 reconciliation:** post-refactor there is no `ditto.extensions.snapshot-adapter` key and no per-service `*PostgresSnapshotAdapter` class (impl-plan C3 collapses the key **and** dissolves the per-service adapters; E2 only relocates the *generic* `PostgresSnapshotAdapter`). The JSONB snapshot **codec** is now selected by the active provider — already the Postgres provider here, because `pgProfileConfig()` loads `ditto-postgres-persistence.conf`. The per-JVM `SnapshotSerializer` (Thing↔JSON) comes from **the neutral selection C3 produces** (`provider.snapshotCodec()` + the configured neutral `SnapshotSerializer`). With no backend-specific override needed, `ProviderWiredTestActor` is a plain persistent actor needing no cluster, so this IT reuses the base `system()` directly. **⟵ gated on impl-plan C3 final key** — when C3 is implemented, substitute the real neutral selection key/config if any is required (none if the active provider's `snapshotCodec()` is sufficient).

**Files:**
- Create: `things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresSnapshotIT.java`

- [ ] **Step 1: Write the IT** (reuses the base `system()` from `pgProfileConfig()`; no override, no separate cluster system)

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.PoisonPill;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.PostgresEventSourceITBase;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.ProviderWiredTestActor;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Real-flow IT for the {@code things} Postgres SNAPSHOT path: a real {@link Thing} is snapshotted through
 * {@code PostgresSnapshotStore} and recovered across a restart. The JSONB snapshot codec is selected by the active
 * (Postgres) provider via {@code pgProfileConfig()}; the per-JVM neutral {@code SnapshotSerializer} comes from the
 * neutral selection impl-plan C3 produces (no backend-specific adapter). Covers flow D (snapshot round-trip; JSONB
 * not BSON).
 */
public final class ThingPostgresSnapshotIT extends PostgresEventSourceITBase {

    private static final String ENTITY_TYPE = "thing";
    private static final String ENTITY_PREFIX = "things";

    @BeforeClass
    public static void boot() {
        // Post-refactor there is no snapshot-adapter override and ProviderWiredTestActor needs no cluster, so this IT
        // reuses the shared base system() built from pgProfileConfig() (active Postgres provider selects the JSONB
        // codec; the neutral per-JVM SnapshotSerializer comes from impl-plan C3). ⟵ gated on impl-plan C3 final key.
        bootSystem("ThingPostgresSnapshotIT");
    }

    private static Thing sampleThing(final ThingId id) {
        return Thing.newBuilder().setId(id).setLifecycle(ThingLifecycle.ACTIVE).setRevision(7)
                .setPolicyId(PolicyId.of(id)).build();
    }

    @Test
    public void snapshotRoundTripsAsJsonb() {
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:snapshot-d");
        final String pid = "thing:" + id;
        final Thing thing = sampleThing(id);

        new TestKit(sys) {{
            final ActorRef first = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            expectMsgClass(ProviderWiredTestActor.Recovered.class);
            first.tell(new ProviderWiredTestActor.Snapshot(thing), getRef());
            expectMsgEquals("snapshotted");
            watch(first);
            first.tell(PoisonPill.getInstance(), getRef());
            expectTerminated(first);

            final ActorRef second = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            final ProviderWiredTestActor.Recovered recovered = expectMsgClass(ProviderWiredTestActor.Recovered.class);
            assertThat(recovered.snapshot()).isInstanceOf(Thing.class);
            assertThat(((Thing) recovered.snapshot()).getEntityId()).contains(id);
        }};

        // Stored column is JSONB text (not BSON binary): valid JSON object beginning with '{'.
        final List<SnapshotRow> rows = snapshotRows(ENTITY_PREFIX, pid);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).snapshot().trim()).startsWith("{");
    }
}
```

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-things-service verify -Dit.test=ThingPostgresSnapshotIT -DfailIfNoTests=false`
Expected: PASS. If the recovered snapshot is not a `Thing` (e.g. a `ClassCastException` or a raw String), the neutral snapshot-serializer selection (impl-plan C3) is not reaching `PostgresSnapshotStore` — investigate how `PostgresSnapshotStore` resolves its serializer/codec through `provider.snapshotCodec()` and fix the neutral config; this IT is the guard for that contract.

- [ ] **Step 3: Commit**

```bash
git add things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresSnapshotIT.java
git commit -s -m "test(things): Postgres real-flow IT — snapshot round-trip (JSONB not BSON) via provider codec (D)"
```

---

## Task 8: `ThingPostgresDurabilityIT` — flow E (hard crash) + flow F (concurrent same-(pid,sn))

Flow E: kill the actor with `Kill` (an uncaught failure → no graceful snapshot-on-stop) after a persisted event, then recover and assert no lost/duplicated sequence numbers. Flow F: at the actor level, an identical re-write of `(pid,sn)` is idempotent; a divergent payload at the same `(pid,sn)` fails the write (H-6 end-to-end) — exercised by two actors targeting the same `pid`.

**Files:**
- Create: `things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresDurabilityIT.java`

- [ ] **Step 1: Write the IT**

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Kill;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.PostgresEventSourceITBase;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.ProviderWiredTestActor;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.eclipse.ditto.things.model.signals.events.ThingCreated;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Real-flow durability IT for {@code things} Postgres: hard crash (Kill) then recover with no lost/duplicated
 * sequence numbers (E), and actor-level same-(pid,sn) writes — idempotent on identical payload, fatal on divergent
 * payload (F / H-6 end-to-end).
 */
public final class ThingPostgresDurabilityIT extends PostgresEventSourceITBase {

    private static final String ENTITY_TYPE = "thing";
    private static final String ENTITY_PREFIX = "things";

    @BeforeClass
    public static void boot() {
        bootSystem("ThingPostgresDurabilityIT");
    }

    private static ThingCreated event(final ThingId id, final long rev, final String attrValue) {
        final Thing thing = Thing.newBuilder().setId(id).setLifecycle(ThingLifecycle.ACTIVE).setRevision(rev)
                .setPolicyId(PolicyId.of(id))
                .setAttributes(org.eclipse.ditto.things.model.Attributes.newBuilder()
                        .set("v", attrValue).build())
                .build();
        return ThingCreated.of(thing, rev, Instant.parse("2021-02-24T14:17:37.581679843Z"),
                DittoHeaders.newBuilder().journalTags(Set.of("always-alive")).build(), null);
    }

    @Test
    public void hardCrashThenRecoverHasNoLostOrDuplicatedSequenceNumbers() {
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:durability-e");
        final String pid = "thing:" + id;

        new TestKit(sys) {{
            final ActorRef actor = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            expectMsgClass(ProviderWiredTestActor.Recovered.class);
            actor.tell(new ProviderWiredTestActor.Persist(event(id, 1L, "one")), getRef());
            expectMsgEquals("persisted");
            // hard crash: Kill triggers an ActorKilledException (no graceful PoisonPill path / no snapshot-on-stop).
            watch(actor);
            actor.tell(Kill.getInstance(), getRef());
            expectTerminated(actor);

            final ActorRef recovered = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            final ProviderWiredTestActor.Recovered r = expectMsgClass(ProviderWiredTestActor.Recovered.class);
            assertThat(r.events()).hasSize(1);
        }};

        final List<JournalRow> rows = journalRows(ENTITY_PREFIX, pid);
        assertThat(rows).extracting(JournalRow::sn).containsExactly(1L); // exactly one row, no dup sn
    }

    @Test
    public void divergentSamePidSnWriteIsFatalAtActorLevel() {
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:durability-f");
        final String pid = "thing:" + id;

        new TestKit(sys) {{
            final ActorRef a = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            expectMsgClass(ProviderWiredTestActor.Recovered.class);
            a.tell(new ProviderWiredTestActor.Persist(event(id, 1L, "original")), getRef());
            expectMsgEquals("persisted");
            watch(a);
            a.tell(org.apache.pekko.actor.PoisonPill.getInstance(), getRef());
            expectTerminated(a);

            // A new instance recovers to sn=1, then a DIVERGENT write at the same (pid, sn=1) must fail the write,
            // stopping the actor (H-6 fatal contract). We assert the actor terminates rather than acking.
            final ActorRef b = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            expectMsgClass(ProviderWiredTestActor.Recovered.class);
            watch(b);
            b.tell(new ProviderWiredTestActor.Persist(event(id, 1L, "DIVERGENT")), getRef());
            // No "persisted" ack: the failed write fails persistence => onPersistFailure stops the actor.
            expectTerminated(b);
        }};

        // The divergent payload must NOT have overwritten the original row.
        final List<JournalRow> rows = journalRows(ENTITY_PREFIX, pid);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).event()).contains("original");
    }
}
```

> **Note (F semantics):** the divergent write at an existing `(pid,sn)` makes `PostgresJournal.writeMessages` return a failed Future → Pekko `WriteMessageFailure` → `onPersistFailure` stops the actor. The test asserts `expectTerminated(b)` and that the original row is intact. If instead `b` acks `"persisted"`, the H-6 contract regressed.

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-things-service verify -Dit.test=ThingPostgresDurabilityIT -DfailIfNoTests=false`
Expected: PASS. If `divergentSamePidSnWriteIsFatalAtActorLevel` times out on `expectTerminated`, capture whether `b` acked instead — that means the divergent-duplicate fatal path is not wired to actor failure; investigate `PostgresJournal` write-failure propagation.

- [ ] **Step 3: Commit**

```bash
git add things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresDurabilityIT.java
git commit -s -m "test(things): Postgres real-flow IT — hard-crash recover + divergent-write fatal (E/F)"
```

---

## Task 9: `ThingPostgresTagWakeIT` — flow G (ping/wake by journal tag) + flow H (fresh-DB bootstrap)

Flow H: prove a fresh (un-pre-seeded) database auto-creates the schema through the real boot path before the first write. Flow G: prove journal tags persisted for an "always-alive" entity are queryable via the read journal's tag-ordered PID query (the input `PersistencePingActor` uses to re-activate entities) — asserted at the read-journal altitude rather than booting the full ping machinery.

**Files:**
- Create: `things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresTagWakeIT.java`

- [ ] **Step 1: Write the IT**

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.PostgresEventSourceITBase;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.ProviderWiredTestActor;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.eclipse.ditto.things.model.signals.events.ThingCreated;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Real-flow IT for {@code things} Postgres: journal tags are persisted so the ping/wake input query returns the
 * always-alive pid (G), and the schema is bootstrapped through the real boot path before the first write (H).
 */
public final class ThingPostgresTagWakeIT extends PostgresEventSourceITBase {

    private static final String ENTITY_TYPE = "thing";
    private static final String ENTITY_PREFIX = "things";

    @BeforeClass
    public static void boot() {
        bootSystem("ThingPostgresTagWakeIT");
    }

    private static ThingCreated alwaysAlive(final ThingId id) {
        final Thing thing = Thing.newBuilder().setId(id).setLifecycle(ThingLifecycle.ACTIVE).setRevision(1)
                .setPolicyId(PolicyId.of(id)).build();
        return ThingCreated.of(thing, 1L, Instant.parse("2021-02-24T14:17:37.581679843Z"),
                DittoHeaders.newBuilder().journalTags(Set.of("always-alive", "priority-5")).build(), null);
    }

    @Test
    public void schemaIsBootstrappedSoTheFirstWriteSucceedsOnAFreshDatabase() {
        // bootSystem() already ran the real PostgresSchemaManager.bootstrap() in @BeforeClass; the first write below
        // hitting a real table (not "relation does not exist") proves H end-to-end.
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:bootstrap-h");
        final String pid = "thing:" + id;
        new TestKit(sys) {{
            final ActorRef actor = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            actor.tell(new ProviderWiredTestActor.Persist(alwaysAlive(id)), getRef());
            expectMsgEquals("persisted");
        }};
        assertThat(journalRows(ENTITY_PREFIX, pid)).hasSize(1);
    }

    @Test
    public void persistedTagsArePresentSoPingWakeCanReactivateTheEntity() {
        final ActorSystem sys = system();
        final ThingId id = ThingId.of("postgres.it:wake-g");
        final String pid = "thing:" + id;
        new TestKit(sys) {{
            final ActorRef actor = sys.actorOf(ProviderWiredTestActor.props(pid, ENTITY_TYPE, getRef()));
            actor.tell(new ProviderWiredTestActor.Persist(alwaysAlive(id)), getRef());
            expectMsgEquals("persisted");
        }};
        // The wake input: the always-alive tag is stored on the journal row (the column PersistencePingActor's
        // read-journal query filters on). Assert the tag landed; a missing tag = ping/wake would never re-activate.
        final List<JournalRow> rows = journalRows(ENTITY_PREFIX, pid);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tags()).contains("always-alive");
    }
}
```

> **Scope note for G:** per the design, this asserts the *input contract* for ping/wake (tags persisted + queryable), not a full `PersistencePingActor` boot. If a later phase wants the full ping cycle, it extends this class. Keeping it at the read-journal altitude avoids pulling the ping machinery into Plan 1.

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-things-service verify -Dit.test=ThingPostgresTagWakeIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresTagWakeIT.java
git commit -s -m "test(things): Postgres real-flow IT — tag persistence for ping/wake + fresh-DB bootstrap (G/H)"
```

---

## Task 10: `ThingPersistenceActorPostgresSmokeIT` — real `ThingPersistenceActor` boots + recovers on Postgres

The "smoke" vehicle: boot the genuine `ThingPersistenceActor` on the Postgres profile, send `CreateThing`, restart the actor, and `RetrieveThing` to prove the real domain actor recovered from Postgres. Mirrors the mock set from `PersistenceActorTestBase`/`ThingPersistenceOperationsActorIT`.

> **2026-06-18 reconciliation:** the `ditto.extensions.snapshot-adapter` override is removed (impl-plan C3 dissolves the key; the active Postgres provider + neutral C3 selection drive the codec). This IT KEEPS its own cluster `ActorSystem` (the real `ThingPersistenceActor` genuinely needs cluster). The read-journal arg is mocked against the **`DittoReadJournal` interface** (not the concrete `MongoReadJournal`): impl-plan B4 generalizes streaming to `DittoReadJournal` and G1 ArchUnit-bans `MongoReadJournal` in service code. **Checkpoint:** confirm `ThingPersistenceActor.props(...)` signature is unchanged by the refactor (impl-plan C1 keeps actor names/persistence-ids and does not call out the props arg list as changing). If C3 reroutes the real actor's plugin-id resolution through `provider.pluginConfig()` instead of `getJournalPluginId`/`getSnapshotPluginId`, mirror that change in `ProviderWiredTestActor` (Task 3) so it stays a faithful proxy of the C3 path.

**Files:**
- Create: `things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPersistenceActorPostgresSmokeIT.java`

- [ ] **Step 1: Write the IT**

```java
/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.PoisonPill;
import org.apache.pekko.actor.Props;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.headers.DittoHeaderDefinition;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.api.DittoReadJournal;
import org.eclipse.ditto.internal.utils.persistence.postgres.it.PostgresEventSourceITBase;
import org.eclipse.ditto.internal.utils.pubsub.DistributedPub;
import org.eclipse.ditto.internal.utils.pubsub.extractors.AckExtractor;
import org.eclipse.ditto.policies.enforcement.PolicyEnforcerProvider;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.modify.CreateThing;
import org.eclipse.ditto.things.model.signals.commands.modify.CreateThingResponse;
import org.eclipse.ditto.things.model.signals.commands.query.RetrieveThing;
import org.eclipse.ditto.things.model.signals.commands.query.RetrieveThingResponse;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;
import org.eclipse.ditto.things.service.common.config.DittoThingsConfig;
import org.eclipse.ditto.things.service.persistence.actors.ThingPersistenceActor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import com.typesafe.config.ConfigFactory;

/**
 * Smoke IT: the genuine {@link ThingPersistenceActor} boots on the Postgres profile, persists a CreateThing, and
 * recovers it across a restart — proving the real domain actor (not just a test actor) round-trips through Postgres.
 */
public final class ThingPersistenceActorPostgresSmokeIT extends PostgresEventSourceITBase {

    private static ActorSystem sys;
    private static DittoThingsConfig thingsConfig;

    @BeforeClass
    public static void boot() {
        bootSystem("ThingPersistenceActorPostgresSmokeIT-bootstrap");
        // The real ThingPersistenceActor needs cluster; KEEP this IT's own cluster ActorSystem. No snapshot-adapter
        // override (impl-plan C3 dissolves the key; the active Postgres provider + neutral C3 selection drive the codec).
        sys = ActorSystem.create("ThingPersistenceActorPostgresSmokeIT", ConfigFactory.parseString(
                        "pekko.actor.provider = cluster\n"
                        + "pekko.remote.artery.canonical.hostname = 127.0.0.1\n"
                        + "pekko.remote.artery.canonical.port = 0\n"
                        + "pekko.cluster.seed-nodes = []\n")
                .withFallback(pgProfileConfig()).resolve());
        thingsConfig = DittoThingsConfig.of(
                org.eclipse.ditto.internal.utils.config.DefaultScopedConfig.dittoScoped(sys.settings().config()));
    }

    @AfterClass
    public static void shutdownSmokeSystem() {
        if (sys != null) {
            TestKit.shutdownActorSystem(sys);
            sys = null;
        }
    }

    private static Props props(final ThingId id) {
        final PolicyEnforcerProvider policyEnforcerProvider = Mockito.mock(PolicyEnforcerProvider.class,
                Mockito.withSettings().defaultAnswer(inv -> CompletableFuture.completedFuture(Optional.empty())));
        // B4 generalizes streaming to the DittoReadJournal interface; mock the interface (G1 bans MongoReadJournal in
        // service code). ThingPersistenceActor.props(...) already takes a DittoReadJournal, so the interface mock suffices.
        final DittoReadJournal readJournal = Mockito.mock(DittoReadJournal.class);
        final DistributedPub<ThingEvent<?>> pub = new DistributedPub<>() {
            @Override public ActorRef getPublisher() { return ActorRef.noSender(); }
            @Override public Object wrapForPublication(final ThingEvent<?> m, final CharSequence k) { return m; }
            @Override public <S extends ThingEvent<?>> Object wrapForPublicationWithAcks(final S m,
                    final CharSequence k, final AckExtractor<S> e) { return m; }
        };
        return ThingPersistenceActor.props(id, readJournal, thingsConfig.getThingConfig(), pub, null,
                policyEnforcerProvider);
    }

    private static DittoHeaders sudo() {
        return DittoHeaders.newBuilder().putHeader(DittoHeaderDefinition.DITTO_SUDO.getKey(), "true").build();
    }

    @Test
    public void realThingPersistenceActorRecoversFromPostgres() {
        final ThingId id = ThingId.of("postgres.it:smoke-1");
        new TestKit(sys) {{
            final ActorRef first = sys.actorOf(props(id), "first-" + id.toString().replace(':', '_'));
            first.tell(CreateThing.of(Thing.newBuilder().setId(id).setPolicyId(PolicyId.of(id)).build(), null, sudo()),
                    getRef());
            expectMsgClass(CreateThingResponse.class);
            watch(first);
            first.tell(PoisonPill.getInstance(), getRef());
            expectTerminated(first);

            final ActorRef second = sys.actorOf(props(id), "second-" + id.toString().replace(':', '_'));
            second.tell(RetrieveThing.of(id, sudo()), getRef());
            final RetrieveThingResponse response = expectMsgClass(RetrieveThingResponse.class);
            assertThat(response.getThing().getEntityId()).contains(id);
        }};
    }
}
```

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-things-service verify -Dit.test=ThingPersistenceActorPostgresSmokeIT -DfailIfNoTests=false`
Expected: PASS. The real actor uses the Postgres journal/snapshot via the profile; if recovery returns `ThingNotAccessibleException` on the second instance, the real actor is not reading back from Postgres — investigate the profile wiring (this is the highest-fidelity guard).

- [ ] **Step 3: Commit**

```bash
git add things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPersistenceActorPostgresSmokeIT.java
git commit -s -m "test(things): Postgres smoke IT — real ThingPersistenceActor recovers from Postgres"
```

---

> **Tasks 10A–10C (2026-06-18 reconciliation) — coverage for the new Postgres collaborators.** The refactor (impl-plan E2) adds **real** Postgres `operations()`, `healthCheck()`, and `streaming()` collaborators whose impl-plan Verification section requires IT coverage; Plan 1 (06-02) predates them. Each new task **depends on the refactor landing** (per the Phase 0 gate), extends the existing `PostgresEventSourceITBase` reusing its `journalRows`/`snapshotRows`/`runDdl` inspectors, carries the EPL-2.0 header (year 2026), commits with `git commit -s`, and runs with `$MVN -o ... verify -Dit.test=<IT> -DfailIfNoTests=false` plus a non-vacuity check. The code shown is a **labelled skeleton — finalize against the E2/C3 API when the refactor lands** (these target post-refactor APIs that do not exist yet; do not treat the signatures as final).

---

## Task 10A: `PostgresPurgeOpsIT` — purge-ops delete the right rows and nothing else (E2, r2dbc module)

Drives the new entity-agnostic `PostgresNamespacePersistenceOperations` / `PostgresEntitiesPersistenceOperations` (impl-plan E2), resolved via `provider.operations("thing")`. Lives in `internal/utils/persistence-r2dbc` (the collaborators are entity-agnostic, so no `things` dependency). This is the impl-plan "`PurgeNamespace`/`PurgeEntities` actually delete Postgres rows" gate.

**Files:**
- Create: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/PostgresPurgeOpsIT.java`

- [ ] **Step 1: Write the IT** — seed → act → assert (skeleton; finalize against the E2 API when the refactor lands)

**Seed:** for entity type `"thing"`, persist journal **and** snapshot rows for **two namespaces** (e.g. `ns.a:keep` and `ns.b:purge`) and **two entity IDs** within a namespace — drive them through `ProviderWiredTestActor` (real write path) or `runDdl` for raw seeding. Confirm via `journalRows("things", pid)` / `snapshotRows("things", pid)` that all seed rows are present before acting.

**Act:** obtain the ops collaborator via the provider — `final var ops = provider.operations("thing");` (returns `NamespacePersistenceOperations` / `EntityPersistenceOperations` per impl-plan E2). Run, materializing the returned `Source`/`CompletionStage`:
- `PurgeNamespace` for `ns.b` (namespace-ops path), then
- `PurgeEntities` for a specific entity ID in `ns.a` (entity-ops path).

**Assert (the "and nothing else" gate):**
- `journalRows("things", "thing:ns.b:purge")` and `snapshotRows(...)` → **empty** (purged-namespace rows gone, both journal and snaps).
- the purged entity's rows in `ns.a` → **empty**.
- the untargeted `ns.a:keep` journal+snapshot rows → **still present, byte-for-byte** (assert sn/manifest/event survive).

```java
// SKELETON — finalize the ops resolution + Purge command construction against impl-plan E2 when the refactor lands.
// final PersistenceBackendProvider provider = PersistenceBackendProvider.get(system(), ...);
// final var ops = provider.operations("thing");                 // E2: PostgresNamespace/EntitiesPersistenceOperations
// runBlocking(ops.purgeNamespace("ns.b"));                       // exact method/return type per E2
// runBlocking(ops.purgeEntities(List.of(EntityId.of("ns.a", "drop"))));
// assertThat(journalRows("things", "thing:ns.b:purge")).isEmpty();
// assertThat(snapshotRows("things", "thing:ns.b:purge")).isEmpty();
// assertThat(journalRows("things", "thing:ns.a:keep")).hasSize(<seeded>);   // survivor untouched
```

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc verify -Dit.test=PostgresPurgeOpsIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 3: Verify non-vacuity** — temporarily comment out the `PurgeNamespace` call and confirm the "purged rows empty" assertion FAILS (proving the purge, not the seed-absence, is what empties the rows); then revert.

- [ ] **Step 4: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/PostgresPurgeOpsIT.java
git commit -s -m "test(persistence): Postgres purge-ops IT — PurgeNamespace/PurgeEntities delete only targeted rows (E2)"
```

---

## Task 10B: `PostgresHealthCheckIT` — real `SELECT 1` health, UP then DOWN (E2, r2dbc module)

Boots `provider.healthCheck()` Props (impl-plan: real `SELECT 1` on the pool), asserts status **UP**, then points the check at a dead/closed pool (or stops the container after boot) and asserts **DOWN** — proving it is a real query, not a constant. Kept at the health-actor protocol altitude. Lives in `internal/utils/persistence-r2dbc`.

**Files:**
- Create: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/PostgresHealthCheckIT.java`

- [ ] **Step 1: Write the IT** — seed → act → assert (skeleton; finalize against the E2 health-actor protocol when the refactor lands)

**Seed:** none beyond the booted `@ClassRule` container + bootstrapped schema (the health check is entity-agnostic and reads no domain rows).

**Act (UP):** `final Props health = provider.healthCheck();` spawn the actor in `system()`, send the health-retrieve message, await the status reply.

**Assert (UP):** the reply reports status UP (a real `SELECT 1` succeeded against the live pool).

**Act/Assert (DOWN):** point the check at a dead/closed factory **or** stop the container after boot, send the retrieve message again, and assert the reply reports DOWN — distinguishing a real query from a hard-coded "UP".

```java
// SKELETON — finalize Props acquisition + health message/status types against impl-plan E2 when the refactor lands.
// final Props health = provider.healthCheck();                  // E2: real SELECT 1 health-actor Props
// final ActorRef checker = system().actorOf(health);
// checker.tell(<RetrieveHealth>, getRef());
// assertThat(<reply>.getStatus()).isEqualTo(UP);
// POSTGRES.stop();  // or swap to a closed ConnectionFactory
// checker.tell(<RetrieveHealth>, getRef());
// assertThat(<reply>.getStatus()).isEqualTo(DOWN);
```

> **Non-vacuity is built into the test:** the DOWN branch (after killing the pool) IS the proof the check runs a real query — if the actor reported UP against a dead pool the test fails. Run the DOWN assertion last (or in its own method) so the container teardown does not affect other ITs in the class.

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc verify -Dit.test=PostgresHealthCheckIT -DfailIfNoTests=false`
Expected: PASS (UP against live pool, DOWN against dead pool).

- [ ] **Step 3: Verify non-vacuity** — confirm the DOWN assertion really exercises a dead pool (e.g. temporarily skip the `POSTGRES.stop()` / pool-close step and confirm the DOWN assertion FAILS because the live pool still answers UP); then revert.

- [ ] **Step 4: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/it/PostgresHealthCheckIT.java
git commit -s -m "test(persistence): Postgres health-check IT — real SELECT 1 reports UP then DOWN (E2)"
```

---

## Task 10C: `ThingPostgresStreamingIT` — snapshot streaming via the generalized actor + Postgres read journal (B4/E2, things/service)

Exercises the generalized streaming actor + `PostgresReadJournal.getNewestSnapshotsAbove` (impl-plan B4/E2), resolved via `provider.streaming("thing")`. Asserts the streamed result shape matches what `ThingsMetadataSource` consumes (impl-plan Risks: verify the `SudoStreamSnapshots` result shape). Lives in `things/service` (it feeds real `Thing` snapshots).

> **Must set `read-journal.entity = "things"` in the profile.** The Postgres `getReadJournal()` requires it with **no default** — add a per-entity `read-journal.entity` parameter to `pgProfileConfig()` (e.g. an overload `pgProfileConfig(String readJournalEntity)`), or set it inline in this IT on top of `pgProfileConfig()`. (The smoke IT mocks the read journal, so it is unaffected.)

**Files:**
- Create: `things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresStreamingIT.java`

- [ ] **Step 1: Write the IT** — seed → act → assert (skeleton; finalize against the B4/E2 streaming API when the refactor lands)

**Seed:** ≥2 `Thing` snapshots for distinct pids (e.g. `thing:ns:stream-1`, `thing:ns:stream-2`) via `ProviderWiredTestActor.Snapshot(thing)` (real snapshot-write path); confirm with `snapshotRows("things", pid)` that both rows exist.

**Act:** `final Props streaming = provider.streaming("thing");` spawn the streaming actor in a system whose profile sets `read-journal.entity = "things"`; send `SudoStreamSnapshots`; collect the streamed elements.

**Assert:** the streamed result shape matches what `ThingsMetadataSource` consumes (≥2 elements, each carrying the pid + the newest snapshot/revision in the shape the consumer reads). Assert the element type/fields against the `SudoStreamSnapshots` result contract, not just count.

```java
// SKELETON — finalize streaming Props + SudoStreamSnapshots result element shape against impl-plan B4/E2.
// final Config profile = pgProfileConfig("things");             // read-journal.entity = "things" (REQUIRED, no default)
// final Props streaming = provider.streaming("thing");          // E2: generalized actor + Postgres read journal (B4)
// final ActorRef streamer = sys.actorOf(streaming);
// streamer.tell(SudoStreamSnapshots.of(...), getRef());
// final var elements = drain(<stream>);
// assertThat(elements).hasSizeGreaterThanOrEqualTo(2);
// assertThat(elements).allSatisfy(e -> /* pid + newest-snapshot shape ThingsMetadataSource expects */);
```

- [ ] **Step 2: Run (Docker up)**

Run: `$MVN -o -pl :ditto-things-service verify -Dit.test=ThingPostgresStreamingIT -DfailIfNoTests=false`
Expected: PASS. If `getReadJournal()` throws on boot, `read-journal.entity` is unset for this IT — set it to `"things"` per the profile note above.

- [ ] **Step 3: Verify non-vacuity** — seed only ONE snapshot and confirm the `hasSizeGreaterThanOrEqualTo(2)` assertion FAILS (proving the stream really reflects the seeded rows, not a constant); then restore the two-snapshot seed.

- [ ] **Step 4: Commit**

```bash
git add things/service/src/test/java/org/eclipse/ditto/things/service/persistence/postgres/ThingPostgresStreamingIT.java
git commit -s -m "test(things): Postgres streaming IT — SudoStreamSnapshots via generalized actor + Postgres read journal (B4/E2)"
```

---

## Task 11: Full-module verification

- [ ] **Step 1: persistence-r2dbc module** — `$MVN -o -pl :ditto-internal-utils-persistence-r2dbc verify` → BUILD SUCCESS, 0 container-skips. Includes the 2 new entity-agnostic ITs that live in this module: `PostgresPurgeOpsIT` (10A) and `PostgresHealthCheckIT` (10B).
- [ ] **Step 2: things-service module** — `$MVN -o -pl :ditto-things-service verify` → BUILD SUCCESS. The 6 new things ITs all run and pass: `ThingPostgresRoundTripIT`, `ThingPostgresSnapshotIT`, `ThingPostgresDurabilityIT`, `ThingPostgresTagWakeIT`, `ThingPersistenceActorPostgresSmokeIT`, and `ThingPostgresStreamingIT` (10C).
- [ ] **Step 3:** Capture the IT counts (run vs skipped) in the commit message; confirm no new skips. **Total new ITs across both modules: 8** — 6 in `things/service` (the 5 original + 10C) and 2 in `persistence-r2dbc` (10A + 10B).
- [ ] **Step 4: Commit** (if any incidental fixes were needed):

```bash
git add -A
git commit -s -m "test(persistence,things): green full-module verify for Postgres real-flow IT suite (Plan 1)"
```

---

## Task 12: Beads epic + tickets

Track the suite and the never-ticketed test-quality findings.

- [ ] **Step 1: Create the epic**

```bash
bd create "Postgres backend real IT suite" --description "Reusable real-flow IT harness + per-entity coverage; see docs/superpowers/specs/2026-06-02-postgres-it-suite-design.md" --label forge
```

- [ ] **Step 2: Create findings tickets** (one each), then link each as blocked-by the epic.

> **2026-06-18 reconciliation:** `L-3` is **DONE** (close it — Task 1 requires Docker). `H-13` and `L-2` are now **satisfied by this plan** and are **NOT created** (deleted): `H-13` (e2e concurrent/hard-crash/multi-entity recovery) is landed for things by flows E/F (Task 8) plus the new ops/health/streaming coverage (10A/10B/10C); `L-2` ("read-journal does-not-throw stub → real-data assertions") is satisfied by 10C's real streamed-data assertions. Only the genuinely-deferred tickets remain.

```bash
bd create "M-11: redirect ThingAdapterSerializerParityTest to the Postgres adapter through the binding" --description "Parity test currently exercises the Mongo adapter; name oversells. Deferred quick-win." --label forge
bd create "L-1: snapshot DELETED-lifecycle exclusion tested with a real DELETED row" --label forge
bd create "L-4: Migrator --verify compares tags/manifest/lifecycle/written_at (later phase)" --label forge
bd create "L-5: verifySchema misreports unknown collection as 'schema missing'" --label forge
bd create "L-15: Pekko-driven post-snapshot event replay coverage" --label forge
# Replication of flows E/F + ops/health/streaming to policies/connections/wot is tracked under the epic (later phases).
# L-3 (require-docker): DONE in Task 1 — close it, do not recreate.
# H-13 / L-2: satisfied by this plan (flows E/F + 10A/10B/10C) — deleted, not recreated.
```

- [ ] **Step 3: Wire dependencies + link to existing defects** — `bd link <epic-id> --blocks <each-finding-id>`; comment the relevant `ditto-postgres-*` defect tickets pointing at the epic. Run `bd list` to confirm.

- [ ] **Step 4:** No commit needed (beads DB is git-tracked under `.beads/`; `bd` commits itself, or `git add .beads && git commit -s -m "chore(beads): IT-suite epic + findings tickets"`).

---

## Self-Review

**Spec coverage (against `2026-06-02-postgres-it-suite-design.md`):**
- Harness + test-jar → Tasks 2, 4 ✅
- `ProviderWiredTestActor` via provider (C-3) → Task 3 ✅
- Flows A/B/C → Task 6; D (snapshot, JSONB via active provider + neutral C3 selection, no per-service adapter) → Task 7; E/F → Task 8; G/H → Task 9 ✅
- Real-`ThingPersistenceActor` smoke (the "both" vehicle, `DittoReadJournal` mock) → Task 10 ✅
- New Postgres collaborators (impl-plan E2): purge-ops → Task 10A; health `SELECT 1` → Task 10B; snapshot streaming (B4) → Task 10C ✅
- Docker-gate sweep (always-require-Docker) → Task 1 ✅
- `things/service` pom deps → Task 5 ✅
- Epic + findings tickets (L-3 closed; H-13/L-2 satisfied+deleted) → Task 12 ✅
- Phase 0 merge **DONE** (`2fc7dd9ba1`); **sequencing gate** = refactor (impl-plan A–G) lands before this suite ✅
- Deferred (policies/connections/wot, migration faults, scale, PgBouncer rework) → explicitly out of scope; G kept at read-journal altitude per design.

**Placeholder scan:** Tasks 1–10 each have concrete code or an exact command + expected result. **No `ditto.extensions.snapshot-adapter` override and no `*PostgresSnapshotAdapter` reference remain** (impl-plan C3/E2 dissolve them); flow D's JSONB codec comes from the active Postgres provider + the neutral C3 selection, and the smoke IT mocks the `DittoReadJournal` interface (not `MongoReadJournal`). The genuine integration-uncertainty points (string read-binding in flow C, neutral snapshot-codec selection in flow D, divergent-write fatal propagation in flow F) are written as concrete tests with an explicit "if it fails, this is the contract to investigate" note — that is the IT doing its job, not a placeholder. Tasks **10A–10C are labelled skeletons** that target post-refactor APIs (E2/B4/C3) which do not yet exist; each carries a concrete seed→act→assert spec, a run command, a non-vacuity check, and a "finalize against the … API when the refactor lands" marker — this is a deliberate gated dependency on the refactor, not an unscoped TBD.

**Type consistency:** `ProviderWiredTestActor.Persist(Object)`, `.Snapshot(Object)`, `.Recovered(List<Object>, Object)` used identically across Tasks 6–10. `PostgresEventSourceITBase.JournalRow`/`SnapshotRow` accessors (`sn()/manifest()/tags()/event()`, `snapshot()/lifecycle()`) match their record definitions. `bootSystem(String)`/`system()`/`journalRows(prefix,pid)`/`snapshotRows(prefix,pid)` signatures consistent. Entity type `"thing"` (provider key) vs prefix `"things"` (table) used consistently and called out.

---

## Execution Handoff

After Phase 0 is done, execute task-by-task. Recommended: **subagent-driven-development** (fresh subagent per task, two-stage review between tasks) since each task is independently verifiable; or **executing-plans** for inline batch execution with checkpoints.
