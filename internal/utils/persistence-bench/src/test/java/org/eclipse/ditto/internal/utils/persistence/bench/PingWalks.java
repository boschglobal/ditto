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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.pekko.NotUsed;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.ConnectionPoolFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.DefaultPostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;
import org.eclipse.ditto.internal.utils.persistence.postgres.readjournal.PostgresReadJournal;

import com.typesafe.config.ConfigFactory;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;

/** Read-journal walks for the persistence-ping stages: clients, one walk, its check and its statistics. */
final class PingWalks {

    static final int BATCH = 500;                                   // the shipped read-journal-batch-size
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(10);   // the shipped ping interval: 10 retries per window
    static final String WINDOW_PARAMETER_TYPES = "text, text, integer";
    static final String PRIORITY_PARAMETER_TYPES = "text";

    /** {@code failure}: empty when the walk completed, else the cause of its failed stream. */
    record Walk(String label, List<String> delivered, long nanos, List<StatementTimer.Statement> statements,
            String failure) {}

    record Check(int expected, int delivered, List<String> missing, List<String> extra, List<String> duplicates,
            boolean ascending) {

        List<String> problems() {
            final List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add(missing.size() + " missing, first " + head(missing));
            }
            if (!extra.isEmpty()) {
                problems.add(extra.size() + " extra, first " + head(extra));
            }
            if (!duplicates.isEmpty()) {
                problems.add(duplicates.size() + " duplicates, first " + head(duplicates));
            }
            if (!ascending) {
                problems.add("not in strictly ascending pid order");
            }
            return problems;
        }

        private static List<String> head(final List<String> pids) {
            return pids.subList(0, Math.min(20, pids.size()));
        }
    }

    private PingWalks() {}

    /** Config of the production client for the bench database; the applicationName tags its backends. */
    static PostgresConfig postgresConfig(final String jdbcUrl, final String user, final String password,
            final String applicationName) {
        final String uri = jdbcUrl.replaceFirst("\\?.*$", "").replaceFirst("^jdbc:", "r2dbc:")
                + "?applicationName=" + applicationName;
        return DefaultPostgresConfig.of(ConfigFactory.parseString("postgresql {\n"
                + "  uri = \"" + uri + "\"\n"
                + "  username = \"" + user + "\"\n"
                + "  password = \"" + password + "\"\n"
                + "  ssl.mode = \"disable\"\n"
                + "}\n"));
    }

    /** Exactly the production pool ({@code ConnectionPoolFactory}), as the services build it. */
    static DittoPostgresClient productionClient(final PostgresConfig config) {
        return DittoPostgresClient.forConnectionPool(ConnectionPoolFactory.createConnectionPool(config), config);
    }

    /**
     * The production connection options ({@code createDdlConnectionFactory}: statement_timeout, fetch size, prepared
     * statement cache, connect timeout, SSL) and pool limits, with the timing listener where production has its
     * metrics proxy.
     */
    static DittoPostgresClient timedClient(final PostgresConfig config, final StatementTimer timer) {
        final PostgresConfig.PoolConfig pool = config.getPoolConfig();
        final ConnectionPoolConfiguration poolConfiguration = ConnectionPoolConfiguration
                .builder(timer.decorate(ConnectionPoolFactory.createDdlConnectionFactory(config)))
                .name("ping-walk")
                .initialSize(pool.getInitialSize())
                .maxSize(pool.getMaxSize())
                .maxAcquireTime(pool.getMaxAcquireTime())
                .acquireRetry(pool.getAcquireRetry())
                .build();
        return DittoPostgresClient.forConnectionPool(new ConnectionPool(poolConfiguration), config);
    }

    static PostgresReadJournal readJournal(final DittoPostgresClient client, final String entity) {
        return PostgresReadJournal.of(PostgresPersistenceOperations.of(client, entity));
    }

    static String showStatementTimeout(final DittoPostgresClient client, final Materializer mat) throws Exception {
        return client.executeSql("SHOW statement_timeout", null, (row, meta) -> row.get(0, String.class))
                .runWith(Sink.head(), mat).toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /**
     * Runs one walk to completion or failure, unthrottled; a failed walk keeps the pids it delivered and its cause.
     * Its statements are the timer's statements that read a journal table: every window and the priority statement
     * do; any housekeeping statement of the client would not.
     */
    static Walk walk(final String label, final Source<String, NotUsed> source, final StatementTimer timer,
            final Materializer mat, final Duration timeout) throws Exception {
        timer.clear();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final long start = System.nanoTime();
        String failure = "";
        try {
            source.runForeach(delivered::add, mat).toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            failure = StatementTimer.describe(e.getCause());
        }
        final long nanos = System.nanoTime() - start;
        return new Walk(label, List.copyOf(delivered), nanos,
                timer.statements().stream().filter(s -> s.sql().contains("_journal")).toList(), failure);
    }

    static Check check(final List<String> delivered, final List<String> expectedSorted, final boolean idOrder) {
        final Set<String> expected = new HashSet<>(expectedSorted);
        final Set<String> seen = new HashSet<>();
        final List<String> extra = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        for (final String pid : delivered) {
            if (!seen.add(pid)) {
                duplicates.add(pid);
            } else if (!expected.contains(pid)) {
                extra.add(pid);
            }
        }
        final List<String> missing = expectedSorted.stream().filter(pid -> !seen.contains(pid)).toList();
        boolean ascending = true;
        for (int i = 1; idOrder && i < delivered.size(); i++) {
            ascending &= delivered.get(i - 1).compareTo(delivered.get(i)) < 0;
        }
        return new Check(expectedSorted.size(), delivered.size(), missing, extra, duplicates, ascending);
    }

    /**
     * Why a measured walk fails: the walk failed, its check found a problem, a statement failed, or its slowest
     * statement ({@code maxNanos}) reached the statement_timeout.
     */
    static List<String> offenders(final Walk walk, final Check check, final long maxNanos, final long timeoutNanos) {
        final List<String> offenders = new ArrayList<>();
        if (!walk.failure().isEmpty()) {
            offenders.add(walk.label() + ": the walk failed: " + walk.failure());
        }
        check.problems().forEach(p -> offenders.add(walk.label() + ": " + p));
        walk.statements().stream().filter(s -> !s.success())
                .forEach(s -> offenders.add(walk.label() + ": failed statement " + s.failure()));
        if (maxNanos >= timeoutNanos) {
            offenders.add(walk.label() + ": a statement reached statement_timeout");
        }
        return offenders;
    }

    /** Pids whose priority is above their predecessor's: the priority order must not rise. */
    static List<String> priorityViolations(final List<String> delivered, final Map<String, Integer> priorityOf) {
        final List<String> violations = new ArrayList<>();
        for (int i = 1; i < delivered.size(); i++) {
            final Integer previous = priorityOf.get(delivered.get(i - 1));
            final Integer current = priorityOf.get(delivered.get(i));
            if (previous != null && current != null && current > previous) {
                violations.add(delivered.get(i));
            }
        }
        return violations;
    }

    /** Nearest-rank percentile of sorted values. */
    static long percentile(final List<Long> sorted, final int percentile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        final int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Median of each tenth of the values, in walk order. */
    static List<Long> tenthMedians(final List<Long> nanos) {
        final List<Long> medians = new ArrayList<>();
        for (int tenth = 0; tenth < 10; tenth++) {
            final List<Long> slice = nanos.subList(tenth * nanos.size() / 10, (tenth + 1) * nanos.size() / 10);
            medians.add(percentile(slice.stream().sorted().toList(), 50));
        }
        return medians;
    }

    /** The cursor ($1) of window k: the last pid of window k - 1, "" for the first. */
    static String windowCursor(final List<String> allPidsSorted, final int window, final int batch) {
        return window == 0 ? "" : allPidsSorted.get(window * batch - 1);
    }

    /** EXPLAIN (ANALYZE, BUFFERS) of a production statement as a custom plan for the given bind values. */
    static String explain(final Connection connection, final String sql, final String parameterTypes,
            final List<Object> binds) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SET plan_cache_mode = force_custom_plan");
            st.execute("PREPARE ping_explain (" + parameterTypes + ") AS " + sql);
            try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, BUFFERS) EXECUTE ping_explain ("
                    + binds.stream().map(PingWalks::sqlLiteral).collect(Collectors.joining(", ")) + ")")) {
                final StringBuilder plan = new StringBuilder();
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
                return plan.toString();
            } finally {
                st.execute("DEALLOCATE ping_explain");
            }
        }
    }

    private static String sqlLiteral(final Object value) {
        return value instanceof Number ? value.toString() : "'" + value.toString().replace("'", "''") + "'";
    }

    static double ms(final long nanos) {
        return nanos / 1_000_000.0;
    }
}
