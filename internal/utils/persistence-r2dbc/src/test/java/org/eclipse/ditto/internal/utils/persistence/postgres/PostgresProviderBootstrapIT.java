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
package org.eclipse.ditto.internal.utils.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.ditto.internal.utils.config.ScopedConfig;
import org.eclipse.ditto.internal.utils.persistence.api.PersistenceBackendProvider;
import org.eclipse.ditto.internal.utils.persistence.postgres.schema.PostgresSchema;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Integration test for the H-1 wiring: the persistence schema is bootstrapped through the REAL boot seam — the
 * backend-agnostic {@link PersistenceBackendProvider#bootstrapSchema()} that each service {@code RootActor} calls during
 * boot — and that seam genuinely creates the schema on a fresh database and FAILS BOOT when it cannot.
 * <p>
 * Before this wiring the bootstrap had ZERO production call sites: a fresh database had no tables and the first journal
 * write hit {@code relation does not exist}. The fix added {@code bootstrapSchema()} to the
 * {@link PersistenceBackendProvider} interface (Mongo = no-op default, Postgres overrides) and made every RootActor call
 * it after {@code PersistenceBackendProvider.get(...)} and before {@code getReadJournal()} / before the persistent-actor
 * shard regions start.
 * </p>
 * <p>
 * Crucially this test does NOT call {@code provider.bootstrapSchema()} on a concrete
 * {@code PostgresPersistenceBackendProvider} directly. It resolves the provider through
 * {@link PersistenceBackendProvider#get(ActorSystem, Config)} — the exact extension-loading seam the RootActors use —
 * and invokes {@code bootstrapSchema()} on the returned <em>interface</em>. So it crosses the boot seam where the H-1
 * bug lived: it would fail if the bootstrap were not reachable through that production path.
 * </p>
 * <p>
 * Runs under failsafe ({@code *IT}); skipped offline / when no Docker daemon is reachable.
 * </p>
 */
public final class PostgresProviderBootstrapIT {

    private final PostgresDbResource postgres = new PostgresDbResource();
    private ActorSystem system;
    private ConnectionFactory verifyFactory;

    @Before
    public void startContainer() {
        try {
            postgres.start();
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker/Testcontainers unavailable — skipping PostgresProviderBootstrapIT", t);
        }
        verifyFactory = postgres.newConnectionFactory();
    }

    @After
    public void stop() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
            system = null;
        }
        postgres.stop();
    }

    /**
     * Boots the Postgres persistence profile and resolves the provider through {@link PersistenceBackendProvider#get},
     * then bootstraps through the interface seam — exactly what every RootActor does. Asserts the schema did not exist
     * before, exists after, and that re-running the same seam is idempotent.
     */
    @Test
    public void bootstrapThroughProviderSeamCreatesAllTables() {
        system = bootSystem(postgres.getHost(), postgres.getPort());

        // Resolve the provider via the SAME extension-loading seam the RootActors use, on the SAME profile config.
        final PersistenceBackendProvider provider = PersistenceBackendProvider.get(system,
                ScopedConfig.dittoExtension(system.settings().config()));

        // Sanity: a fresh DB has no tables before the wired boot path runs.
        for (final String entity : PostgresSchema.ENTITIES) {
            assertThat(tableExists(entity + "_journal"))
                    .as("fresh DB must NOT have <%s_journal> before bootstrap", entity)
                    .isFalse();
        }

        // The wired boot seam: call bootstrapSchema() on the PersistenceBackendProvider *interface*, just like
        // ThingsRootActor / PoliciesRootActor / ConnectivityRootActor do before getReadJournal().
        provider.bootstrapSchema();

        // After the wired bootstrap, every entity's three tables + schema_version exist.
        for (final String entity : PostgresSchema.ENTITIES) {
            assertThat(tableExists(entity + "_journal")).as("<%s_journal> after bootstrap", entity).isTrue();
            assertThat(tableExists(entity + "_journal_seq")).as("<%s_journal_seq> after bootstrap", entity).isTrue();
            assertThat(tableExists(entity + "_snaps")).as("<%s_snaps> after bootstrap", entity).isTrue();
        }
        assertThat(tableExists("schema_version")).isTrue();

        // Re-running through the same seam is idempotent (IF NOT EXISTS + checksum match).
        provider.bootstrapSchema();
        assertThat(tableExists("things_journal")).isTrue();
    }

    /**
     * The 'fail boot if the schema cannot be established' guarantee: when the database is unreachable, the wired boot
     * seam throws (so a RootActor calling it would fail boot) and no tables are silently created. This is the regression
     * guard for the actual H-1 finding — the boot path must fail fast rather than serve traffic against an absent
     * schema.
     */
    @Test
    public void bootstrapThroughProviderSeamFailsBootWhenDatabaseUnreachable() {
        // A port nothing listens on -> connection refused. ssl.mode=disable so no boot guard fires; the failure is the
        // unreachable DB, which is exactly what 'tables absent and no bootstrap possible' looks like at boot.
        final int deadPort = postgres.getPort() == 1 ? 2 : 1;
        system = bootSystem(postgres.getHost(), deadPort);

        final PersistenceBackendProvider provider = PersistenceBackendProvider.get(system,
                ScopedConfig.dittoExtension(system.settings().config()));

        // Boot must FAIL FAST: bootstrapSchema() throws when it cannot reach the DB / establish the schema. A RootActor
        // propagates this and the service refuses to boot, instead of serving traffic against a tableless database.
        assertThatThrownBy(provider::bootstrapSchema).isInstanceOf(RuntimeException.class);

        // And nothing was created on the real container (the dead-port system never touched it).
        assertThat(tableExists("things_journal"))
                .as("no tables may be created when the wired bootstrap fails")
                .isFalse();
    }

    private ActorSystem bootSystem(final String host, final int port) {
        // Load the real Postgres persistence profile (selects PostgresPersistenceBackendProvider + plugin-id mappings +
        // ditto.postgresql.* client defaults) and point ditto.postgresql.* at the container as the DDL role.
        // ssl.mode=disable keeps the boot guard quiet against the plaintext test container. This is the same wiring a
        // service boots with.
        final Config config = ConfigFactory.parseString(""
                + "ditto.postgresql {\n"
                + "  uri = \"r2dbc:postgresql://" + host + ":" + port + "/" + postgres.getDatabaseName() + "\"\n"
                + "  username = \"" + PostgresDbResource.DDL_USER + "\"\n"
                + "  password = \"" + PostgresDbResource.DDL_PASSWORD + "\"\n"
                + "  ssl.mode = \"disable\"\n"
                + "  pool.ddl-credentials {\n"
                + "    username = \"" + PostgresDbResource.DDL_USER + "\"\n"
                + "    password = \"" + PostgresDbResource.DDL_PASSWORD + "\"\n"
                + "  }\n"
                + "}\n")
                .withFallback(ConfigFactory.parseResources("ditto-postgres-persistence.conf"))
                .withFallback(ConfigFactory.load())
                .resolve();
        return ActorSystem.create("PostgresProviderBootstrapIT", config);
    }

    private boolean tableExists(final String table) {
        return Boolean.parseBoolean(scalar(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = '" + table + "')"));
    }

    private String scalar(final String sql) {
        return Mono.usingWhen(Mono.from(verifyFactory.create()),
                        conn -> Flux.from(conn.createStatement(sql).execute())
                                .flatMap(result -> result.map((row, meta) -> String.valueOf(row.get(0))))
                                .next(),
                        Connection::close)
                .block();
    }
}
