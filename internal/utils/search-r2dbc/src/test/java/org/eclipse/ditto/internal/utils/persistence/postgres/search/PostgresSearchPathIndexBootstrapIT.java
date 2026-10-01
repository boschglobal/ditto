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
package org.eclipse.ditto.internal.utils.persistence.postgres.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.config.DittoConfigError;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.PostgresSchemaManager;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.SchemaBootException;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.testkit.PostgresDbResource;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.PathIndex;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.PathIndexKind;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.PathIndexMaintenance;
import org.eclipse.ditto.rql.query.expression.ThingsFieldExpressionFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link PostgresSearchPersistenceProvider#bootstrapSchema()} and the per-path indexes, against a real PostgreSQL 16
 * (Testcontainers): the maintenance starts in the background only after a successful bootstrap, never after a refused
 * one, does nothing against a newer stored schema, and an invalid entry fails the start before any database work.
 * <p>
 * Skipped when no Docker daemon is reachable.
 * </p>
 */
public final class PostgresSearchPathIndexBootstrapIT {

    private static final PostgresDbResource POSTGRES = new PostgresDbResource();
    private static ConnectionFactory connectionFactory;

    private static final PathIndex PROP0 = PathIndex.of("features/*/properties/prop0", PathIndexKind.TRIGRAM,
            ThingsFieldExpressionFactory.of(Map.of()));
    private static final String PROP0_CONFIG = "[ { path = \"features/*/properties/prop0\", kind = trigram } ]";

    @BeforeClass
    public static void startContainer() {
        try {
            POSTGRES.start();
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker/Testcontainers unavailable — skipping PostgresSearchPathIndexBootstrapIT",
                    t);
        }
        connectionFactory = POSTGRES.newConnectionFactory();
        PostgresSchemaManager.of(connectionFactory, PostgresSearchSchema.descriptor()).bootstrap();
    }

    @AfterClass
    public static void stopContainer() {
        POSTGRES.stop();
    }

    @After
    public void restoreSchemaVersionAndDropManagedIndexes() {
        query("UPDATE schema_version SET version = " + PostgresSearchSchema.VERSION + ", min_compatible_version = "
                + PostgresSearchSchema.MIN_COMPATIBLE_VERSION + " WHERE component = '"
                + PostgresSearchSchema.COMPONENT + "'");
        for (final String name : query("SELECT relname::text FROM pg_class WHERE starts_with(relname, 'sf_path_')")) {
            query("DROP INDEX " + name);
        }
    }

    @Test
    public void aSuccessfulBootstrapStartsTheMaintenanceInTheBackground() {
        withProvider(PROP0_CONFIG, provider -> {
            provider.bootstrapSchema();

            final PathIndexMaintenance.Outcome outcome = await(provider.pathIndexMaintenanceOutcome());
            assertThat(outcome.status()).isEqualTo(PathIndexMaintenance.Status.APPLIED);
            assertThat(outcome.built()).containsExactly(PROP0.name());
            assertThat(query("SELECT indisvalid::text FROM pg_index WHERE indexrelid = '" + PROP0.name()
                    + "'::regclass")).containsExactly("true");
        });
    }

    @Test
    public void theShippedDefaultLeavesExistingPerPathIndexesAlone() {
        // the profile leaves paths unset: an instance without the list neither builds nor drops (no mass drop when an
        // environment variable is missing in one deployment)
        query(PROP0.createStatement());
        withProvider(null, provider -> {
            provider.bootstrapSchema();

            final PathIndexMaintenance.Outcome outcome = await(provider.pathIndexMaintenanceOutcome());
            assertThat(outcome.status()).isEqualTo(PathIndexMaintenance.Status.NOT_CONFIGURED);
            assertThat(managedIndexCount()).isEqualTo(1);
        });
    }

    @Test
    public void anExplicitlyEmptyListDropsThePerPathIndexes() {
        query(PROP0.createStatement());
        withProvider("[]", provider -> {
            provider.bootstrapSchema();

            final PathIndexMaintenance.Outcome outcome = await(provider.pathIndexMaintenanceOutcome());
            assertThat(outcome.dropped()).containsExactly(PROP0.name());
            assertThat(managedIndexCount()).isZero();
        });
    }

    @Test
    public void aRefusedBootstrapBuildsNothing() {
        // a newer stored schema this code is not compatible with: the bootstrap refuses to boot
        query("UPDATE schema_version SET version = 99, min_compatible_version = 99 WHERE component = '"
                + PostgresSearchSchema.COMPONENT + "'");
        withProvider(PROP0_CONFIG, provider -> {
            assertThatThrownBy(provider::bootstrapSchema).isInstanceOf(SchemaBootException.class);

            assertThat(provider.pathIndexMaintenanceOutcome()).isNull();
            assertThat(managedIndexCount()).isZero();
        });
    }

    @Test
    public void aCompatibleNewerSchemaIsLeftToTheNewerCode() {
        // rolling upgrade: the stored schema is newer but compatible, so the bootstrap runs no DDL
        query("UPDATE schema_version SET version = " + (PostgresSearchSchema.VERSION + 1)
                + ", min_compatible_version = " + PostgresSearchSchema.VERSION + " WHERE component = '"
                + PostgresSearchSchema.COMPONENT + "'");
        withProvider(PROP0_CONFIG, provider -> {
            provider.bootstrapSchema();

            final PathIndexMaintenance.Outcome outcome = await(provider.pathIndexMaintenanceOutcome());
            assertThat(outcome.status()).isEqualTo(PathIndexMaintenance.Status.SCHEMA_VERSION_MISMATCH);
            assertThat(managedIndexCount()).isZero();
        });
    }

    @Test
    public void anInvalidEntryFailsTheStartBeforeAnyDatabaseWork() {
        query("UPDATE schema_version SET checksum = 'untouched' WHERE component = '" + PostgresSearchSchema.COMPONENT
                + "'");
        try {
            withProvider("[ { path = \"thingId\", kind = trigram } ]", provider -> {
                assertThatThrownBy(provider::bootstrapSchema)
                        .isInstanceOf(DittoConfigError.class)
                        .hasMessageContaining("thingId");
                assertThat(provider.pathIndexMaintenanceOutcome()).isNull();
            });
            // the bootstrap never ran: it would have refused on the checksum or rewritten it
            assertThat(query("SELECT checksum FROM schema_version WHERE component = '"
                    + PostgresSearchSchema.COMPONENT + "'")).containsExactly("untouched");
        } finally {
            query("UPDATE schema_version SET checksum = '"
                    + PostgresSchemaManager.checksum(PostgresSearchSchema.descriptor()) + "' WHERE component = '"
                    + PostgresSearchSchema.COMPONENT + "'");
        }
    }

    private static void withProvider(@Nullable final String paths,
            final Consumer<PostgresSearchPersistenceProvider> body) {
        final Config config = ConfigFactory.parseString(
                        "ditto.postgresql {\n"
                                + "  uri = \"r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getPort() + "/"
                                + POSTGRES.getDatabaseName() + "\"\n"
                                + "  username = \"" + PostgresDbResource.DDL_USER + "\"\n"
                                + "  password = \"" + PostgresDbResource.DDL_PASSWORD + "\"\n"
                                + "  ssl.mode = disable\n"
                                + (paths == null ? "" : "  search.path-indexes.paths = " + paths + "\n")
                                + "}\n")
                .withFallback(ConfigFactory.load("postgres-search-test.conf"));
        final ActorSystem system = ActorSystem.create("PostgresSearchPathIndexBootstrapIT", config);
        try {
            body.accept(new PostgresSearchPersistenceProvider(system, ConfigFactory.empty()));
        } finally {
            TestKit.shutdownActorSystem(system);
        }
    }

    private static PathIndexMaintenance.Outcome await(
            final CompletableFuture<PathIndexMaintenance.Outcome> outcome) {
        assertThat(outcome).isNotNull();
        try {
            return outcome.get(2L, TimeUnit.MINUTES);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int managedIndexCount() {
        return Integer.parseInt(query("SELECT count(*)::text FROM pg_class WHERE starts_with(relname, 'sf_path_')")
                .get(0));
    }

    private static List<String> query(final String sql) {
        final List<String> result = new ArrayList<>();
        Flux.usingWhen(Mono.from(connectionFactory.create()),
                        connection -> Flux.from(connection.createStatement(sql).execute())
                                .flatMap(r -> r.map((row, meta) -> {
                                    final Object value = row.get(0);
                                    return value == null ? "" : value.toString();
                                })),
                        Connection::close)
                .doOnNext(result::add)
                .blockLast();
        return result;
    }

}
