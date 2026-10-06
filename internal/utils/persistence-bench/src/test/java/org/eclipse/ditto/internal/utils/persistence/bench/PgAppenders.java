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
package org.eclipse.ditto.internal.utils.persistence.bench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.ConnectionPoolFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.DefaultPostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;

import com.typesafe.config.ConfigFactory;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Result;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Executes one W3 append in a given {@link PgAppendShape}, timing its steps.
 */
final class PgAppenders {

    /** application_name of every connection that carries churn appends (the wait-event sampler filters on it). */
    static final String CHURN_APPLICATION_NAME = "w3-churn";

    /** {@code PostgresPersistenceOperations#insertEvents}'s event INSERT, verbatim. */
    private static final String R2DBC_INSERT_EVENT = "INSERT INTO things_journal "
            + "(pid, sn, manifest, tags, event, written_at) "
            + "VALUES ($1, $2, $3, $4, $5::jsonb, clock_timestamp())";

    /** {@code PostgresPersistenceOperations#insertEvents}'s high-water-mark upsert, verbatim. */
    private static final String R2DBC_UPSERT_SEQ = "INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) "
            + "ON CONFLICT (pid) DO UPDATE SET highest_sn = "
            + "GREATEST(things_journal_seq.highest_sn, excluded.highest_sn)";

    /** What-if: the event INSERT and the high-water-mark upsert in one statement (one round trip). */
    static final String PG_INSERT_AND_UPSERT_ONE_STATEMENT = "WITH ins AS ("
            + PersistenceShapes.PG_INSERT_EVENT + " RETURNING pid, sn) "
            + "INSERT INTO things_journal_seq (pid, highest_sn) SELECT pid, sn FROM ins "
            + "ON CONFLICT (pid) DO UPDATE SET highest_sn = "
            + "GREATEST(things_journal_seq.highest_sn, excluded.highest_sn)";

    private PgAppenders() {
    }

    /** One worker's append executor. */
    interface PgAppender extends AutoCloseable {

        /**
         * Appends event {@code sn} of {@code pid} and writes each timed step's duration in nanoseconds into
         * {@code phaseNanos}, in {@link PgAppendShape#phases()} order.
         */
        void append(String pid, long sn, String eventJson, long[] phaseNanos) throws Exception;

        /** Writes a snapshot; not part of the shape comparison (pgjdbc for every shape). */
        void saveSnapshot(String pid, long sn, String snapshotJson) throws SQLException;

        @Override
        void close() throws SQLException;
    }

    static PgAppender jdbc(final PgAppendShape shape) throws SQLException {
        if (shape.usesR2dbc()) {
            throw new IllegalArgumentException("not a pgjdbc shape: " + shape.label());
        }
        return new JdbcAppender(shape, BenchConfig.openPg(CHURN_APPLICATION_NAME));
    }

    private static final class JdbcAppender implements PgAppender {

        private final PgAppendShape shape;
        private final Connection connection;
        private final PreparedStatement append;
        private final PreparedStatement upsert;
        private final PreparedStatement snapshot;

        private JdbcAppender(final PgAppendShape shape, final Connection connection) throws SQLException {
            this.shape = shape;
            this.connection = connection;
            // the one-statement what-if commits implicitly (one round trip); every other shape is a transaction
            connection.setAutoCommit(shape == PgAppendShape.ONE_STATEMENT);
            append = connection.prepareStatement(shape == PgAppendShape.ONE_STATEMENT
                    ? PG_INSERT_AND_UPSERT_ONE_STATEMENT
                    : PersistenceShapes.PG_INSERT_EVENT);
            upsert = shape == PgAppendShape.TXN ? connection.prepareStatement(PersistenceShapes.PG_UPSERT_SEQ) : null;
            snapshot = connection.prepareStatement(PersistenceShapes.PG_SAVE_SNAPSHOT);
        }

        @Override
        public void append(final String pid, final long sn, final String eventJson, final long[] phaseNanos)
                throws SQLException {
            long t = System.nanoTime();
            append.setString(1, pid);
            append.setLong(2, sn);
            append.setString(3, CorpusGenerator.MANIFEST_ATTRIBUTE);
            append.setObject(4, connection.createArrayOf("text", new String[0]));
            append.setString(5, eventJson);
            append.executeUpdate();
            t = lap(phaseNanos, 0, t);
            if (shape == PgAppendShape.ONE_STATEMENT) {
                return;
            }
            if (upsert != null) {
                upsert.setString(1, pid);
                upsert.setLong(2, sn);
                upsert.executeUpdate();
                t = lap(phaseNanos, 1, t);
            }
            connection.commit();
            lap(phaseNanos, upsert != null ? 2 : 1, t);
        }

        @Override
        public void saveSnapshot(final String pid, final long sn, final String snapshotJson) throws SQLException {
            writeSnapshot(snapshot, pid, sn, snapshotJson);
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    /**
     * The production write path: a pool built by the production {@code ConnectionPoolFactory} with the shipped
     * pool defaults, shared by all workers as one service instance shares its pool.
     */
    static final class R2dbcPath implements AutoCloseable {

        private final ConnectionPool pool;
        private final PostgresPersistenceOperations operations;

        private R2dbcPath(final ConnectionPool pool, final PostgresPersistenceOperations operations) {
            this.pool = pool;
            this.operations = operations;
        }

        static R2dbcPath open() {
            final String uri = BenchConfig.pgUrl().replaceFirst("^jdbc:", "r2dbc:")
                    + "?applicationName=" + CHURN_APPLICATION_NAME;
            final PostgresConfig config = DefaultPostgresConfig.of(ConfigFactory.parseString("postgresql {\n"
                    + "  uri = \"" + uri + "\"\n"
                    + "  username = \"" + BenchConfig.pgUser() + "\"\n"
                    + "  password = \"" + BenchConfig.pgPassword() + "\"\n"
                    + "  ssl.mode = \"disable\"\n"
                    + "}\n"));
            final ConnectionPool pool = ConnectionPoolFactory.createConnectionPool(config);
            final DittoPostgresClient client = DittoPostgresClient.forConnectionPool(pool, config);
            return new R2dbcPath(pool, PostgresPersistenceOperations.of(client, "things"));
        }

        PgAppender appender(final PgAppendShape shape) {
            return switch (shape) {
                case R2DBC -> new R2dbcAppender(this, false);
                case R2DBC_PHASED -> new R2dbcAppender(this, true);
                default -> throw new IllegalArgumentException("not an r2dbc shape: " + shape.label());
            };
        }

        /** Pool gauges, e.g. to show that no borrow waited for a connection. */
        String poolMetrics() {
            return pool.getMetrics()
                    .map(m -> String.format("acquired=%d allocated=%d idle=%d pendingAcquire=%d maxAllocated=%d",
                            m.acquiredSize(), m.allocatedSize(), m.idleSize(), m.pendingAcquireSize(),
                            m.getMaxAllocatedSize()))
                    .orElse("n/a");
        }

        /** {pendingAcquire, allocated}, or -1s when the pool exposes no metrics. */
        int[] pendingAndAllocated() {
            return pool.getMetrics()
                    .map(m -> new int[]{m.pendingAcquireSize(), m.allocatedSize()})
                    .orElse(new int[]{-1, -1});
        }

        @Override
        public void close() {
            pool.dispose();
        }
    }

    private static final class R2dbcAppender implements PgAppender {

        private final R2dbcPath path;
        private final boolean phased;
        private Connection snapshotConnection;
        private PreparedStatement snapshot;

        private R2dbcAppender(final R2dbcPath path, final boolean phased) {
            this.path = path;
            this.phased = phased;
        }

        @Override
        public void append(final String pid, final long sn, final String eventJson, final long[] phaseNanos) {
            if (!phased) {
                path.operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(pid, sn,
                        CorpusGenerator.MANIFEST_ATTRIBUTE, List.of(), eventJson))).block();
                return;
            }
            long t = System.nanoTime();
            final io.r2dbc.spi.Connection connection = Mono.from(path.pool.create()).block();
            t = lap(phaseNanos, 0, t);
            try {
                Mono.from(connection.beginTransaction()).block();
                t = lap(phaseNanos, 1, t);
                Flux.from(connection.createStatement(R2DBC_INSERT_EVENT)
                                .bind(0, pid)
                                .bind(1, sn)
                                .bind(2, CorpusGenerator.MANIFEST_ATTRIBUTE)
                                .bind(3, new String[0])
                                .bind(4, eventJson)
                                .execute())
                        .flatMap(Result::getRowsUpdated)
                        .blockLast();
                t = lap(phaseNanos, 2, t);
                Flux.from(connection.createStatement(R2DBC_UPSERT_SEQ)
                                .bind(0, pid)
                                .bind(1, sn)
                                .execute())
                        .flatMap(Result::getRowsUpdated)
                        .blockLast();
                t = lap(phaseNanos, 3, t);
                Mono.from(connection.commitTransaction()).block();
                t = lap(phaseNanos, 4, t);
            } finally {
                Mono.from(connection.close()).block();
                lap(phaseNanos, 5, t);
            }
        }

        @Override
        public void saveSnapshot(final String pid, final long sn, final String snapshotJson) throws SQLException {
            if (snapshotConnection == null) {
                snapshotConnection = BenchConfig.openPg("w3-snapshot");
                snapshot = snapshotConnection.prepareStatement(PersistenceShapes.PG_SAVE_SNAPSHOT);
            }
            writeSnapshot(snapshot, pid, sn, snapshotJson);
        }

        @Override
        public void close() throws SQLException {
            if (snapshotConnection != null) {
                snapshotConnection.close();
            }
        }
    }

    private static void writeSnapshot(final PreparedStatement snapshot, final String pid, final long sn,
            final String snapshotJson) throws SQLException {
        snapshot.setString(1, pid);
        snapshot.setLong(2, sn);
        snapshot.setString(3, snapshotJson);
        snapshot.setString(4, null);
        snapshot.setObject(5, Instant.now().atOffset(ZoneOffset.UTC));
        snapshot.executeUpdate();
    }

    private static long lap(final long[] phaseNanos, final int phase, final long since) {
        final long now = System.nanoTime();
        phaseNanos[phase] = now - since;
        return now;
    }
}
