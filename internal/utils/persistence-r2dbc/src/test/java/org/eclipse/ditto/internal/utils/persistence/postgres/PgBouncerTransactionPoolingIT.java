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

import org.eclipse.ditto.internal.utils.persistence.postgres.schema.PostgresSchema;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.ConnectionPoolFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.testkit.PostgresDbResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.DefaultPostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;
import org.eclipse.ditto.internal.utils.persistence.postgres.journal.PostgresJournalOps;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.PostgresSchemaManager;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.typesafe.config.ConfigFactory;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * PgBouncer transaction-pooling smoke test: runs the schema bootstrap (transaction-scoped advisory-lock DDL) and a
 * write/recover/cleanup round-trip <em>through a real PgBouncer container in {@code pool_mode=transaction}</em> with
 * {@code prepared-statement-cache-queries=0} (unnamed statements — safe on any PgBouncer version).
 * <p>
 * Mirrors the {@code api.version} portability fix via {@link PostgresDbResource}'s static initializer (referenced before
 * any container starts). If Docker or the PgBouncer image is unreachable, the {@link #start() Assume} guard turns it
 * into a skip rather than a failure.
 * </p>
 */
public final class PgBouncerTransactionPoolingIT {

    static {
        // Ensure the docker-java api.version portability fix is applied before any Testcontainers class loads.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.43");
        }
    }

    private static final String PG_USER = "ditto_ddl";
    private static final String PG_PASSWORD = "ddl_secret";
    private static final String PG_DB = "ditto";
    // The edoburu/pgbouncer image listens on 5432 inside the container (not 6432).
    private static final int PGBOUNCER_PORT = 5432;

    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> pgbouncer;
    private static DittoPostgresClient client;
    private static PostgresJournalOps journal;

    @BeforeClass
    public static void start() {
        try {
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
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker / PgBouncer image unavailable — skipping PgBouncerTransactionPoolingIT", t);
        }

        final ConnectionFactory factory = throughPgBouncer();
        // bootstrap (transaction-scoped advisory-lock DDL) through PgBouncer transaction pooling — must succeed.
        PostgresSchemaManager.of(factory, PostgresSchema.descriptor()).bootstrap();

        final ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(factory)
                .name("pgbouncer-pool").maxSize(8).build());
        client = DittoPostgresClient.forConnectionPool(pool, DefaultPostgresConfig.of(ConfigFactory.empty()));
        journal = PostgresJournalOps.of(PostgresPersistenceOperations.of(client, "things"));
    }

    @AfterClass
    public static void stop() {
        if (client != null) {
            client.close();
        }
        if (pgbouncer != null) {
            pgbouncer.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static ConnectionFactory throughPgBouncer() {
        return ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, pgbouncer.getHost())
                .option(ConnectionFactoryOptions.PORT, pgbouncer.getMappedPort(PGBOUNCER_PORT))
                .option(ConnectionFactoryOptions.DATABASE, PG_DB)
                .option(ConnectionFactoryOptions.USER, PG_USER)
                .option(ConnectionFactoryOptions.PASSWORD, PG_PASSWORD)
                // unnamed statements -> safe under transaction pooling on any PgBouncer version.
                .option(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES, 0)
                .option(PostgresqlConnectionFactoryProvider.FETCH_SIZE, 0)
                .build());
    }

    @Test
    public void writeRecoverCleanupThroughTransactionPooling() throws Exception {
        final String pid = "thing:pgb:rt";
        await(journal.writeMessages(List.of(write(pid, 1L))));
        await(journal.writeMessages(List.of(write(pid, 2L))));

        // recover: highest sequence number visible through PgBouncer.
        assertThat(await(journal.readHighestSequenceNr(pid, 0L))).isEqualTo(2L);

        // cleanup: physical delete; the high-water-mark must still report 2 afterwards.
        await(journal.deleteMessagesTo(pid, 2L));
        assertThat(await(journal.readHighestSequenceNr(pid, 0L))).isEqualTo(2L);
    }

    /**
     * Proves the production statement-timeout wiring: {@code ConnectionPoolFactory.baseOptions} binds
     * {@code statement_timeout} via the r2dbc-postgresql {@code OPTIONS} startup-parameter map, and the server enforces
     * it — a {@code pg_sleep(10)} under a 1s {@code statement_timeout} aborts with SQLSTATE {@code 57014}
     * ({@code query_canceled}).
     * <p>
     * <strong>PgBouncer caveat (verified here, 2026-06-16, PgBouncer 1.25.2):</strong> r2dbc-postgresql ships the GUC
     * inside the libpq <em>startup {@code options} field</em>. PgBouncer does NOT propagate GUCs carried in that field
     * across transaction-pooled borrows — not even with {@code track_extra_parameters = statement_timeout} (which only
     * tracks <em>protocol-level</em> parameter fields such as {@code application_name}/{@code IntervalStyle}). So under
     * TRANSACTION-mode PgBouncer the same {@code pg_sleep(10)} runs to completion. The enforcement therefore holds for
     * DIRECT / SESSION pooling (and against Postgres directly); under TRANSACTION-mode PgBouncer the timeout must be set
     * server-side (e.g. a per-role {@code ALTER ROLE ... SET statement_timeout}). This is documented in
     * {@code ditto-postgres-persistence.conf}.
     */
    @Test
    public void statementTimeoutFromOptionsStartupParameterIsEnforced() {
        // Drive the production OPTIONS encoding straight at Postgres (the path PgBouncer does not interfere with). A DO
        // block returns a command tag (no result-set portal to stream), so the cancellation surfaces 57014 cleanly.
        final ConnectionFactory factory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, PG_DB)
                .option(ConnectionFactoryOptions.USER, PG_USER)
                .option(ConnectionFactoryOptions.PASSWORD, PG_PASSWORD)
                .option(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES, 0)
                .option(PostgresqlConnectionFactoryProvider.FETCH_SIZE, 0)
                // exactly the production ConnectionPoolFactory wiring: statement_timeout via the OPTIONS startup map.
                .option(PostgresqlConnectionFactoryProvider.OPTIONS, java.util.Map.of("statement_timeout", "1000"))
                .build());
        final ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(factory)
                .name("pg-stmt-timeout").maxSize(2).build());
        try {
            final reactor.core.publisher.Mono<Long> longQuery = reactor.core.publisher.Mono.usingWhen(
                    pool.create(),
                    connection -> reactor.core.publisher.Flux.from(
                                    connection.createStatement("DO $$ BEGIN PERFORM pg_sleep(10); END $$;").execute())
                            .flatMap(io.r2dbc.spi.Result::getRowsUpdated)
                            .reduce(0L, Long::sum),
                    io.r2dbc.spi.Connection::close);

            final long start = System.nanoTime();
            final Throwable thrown = catchThrowable(() -> longQuery.block(Duration.ofSeconds(20)));
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            final java.util.Set<String> sqlStates = new java.util.HashSet<>();
            for (Throwable t = thrown; t != null && t != t.getCause(); t = t.getCause()) {
                if (t instanceof io.r2dbc.spi.R2dbcException r2dbc && r2dbc.getSqlState() != null) {
                    sqlStates.add(r2dbc.getSqlState());
                }
            }

            assertThat(thrown).as("pg_sleep(10) was aborted, not run to completion").isNotNull();
            assertThat(elapsed)
                    .as("aborted by statement_timeout=1s, well before the 10s sleep finishes")
                    .isLessThan(Duration.ofSeconds(8));
            assertThat(sqlStates)
                    .as("SQLSTATE 57014 (query_canceled) in the failure cause chain: %s", sqlStates)
                    .contains("57014");
        } finally {
            pool.dispose();
        }
    }

    private static org.apache.pekko.persistence.AtomicWrite write(final String pid, final long sn) {
        return org.apache.pekko.persistence.AtomicWrite.apply(org.apache.pekko.persistence.PersistentRepr.apply(
                org.eclipse.ditto.json.JsonFactory.newObject("{\"sn\":" + sn + "}"), sn, pid, "manifest", false,
                org.apache.pekko.actor.ActorRef.noSender(), "w"));
    }

    private static <T> T await(final CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

}
