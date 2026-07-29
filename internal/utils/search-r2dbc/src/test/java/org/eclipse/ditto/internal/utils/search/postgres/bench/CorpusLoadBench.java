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
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Loads the full-scale (default 1,000,000-thing) corpus into the long-lived bench database via
 * {@link CopyLoader}, then reports load timings, row counts and on-disk sizes.
 * <p>
 * <b>Deliberately excluded from every automatic build phase.</b> {@code CorpusLoadBench}'s name matches
 * neither Surefire's default {@code *Test}/{@code Test*} patterns nor Failsafe's default {@code *IT}/
 * {@code IT*} patterns, so {@code mvn test}/{@code verify}/{@code install} never pick it up — it targets a
 * long-lived, manually started container (see {@code bench/README.md}), not an ephemeral per-test one, since
 * the loaded corpus must survive across the 0.2/0.3 read/write bench tasks. Run it explicitly:
 * <pre>
 * mvn -pl internal/utils/search-r2dbc test -Dtest=CorpusLoadBench -DfailIfNoTests=false \
 *     -Dbench.count=1000000
 * </pre>
 */
public class CorpusLoadBench {

    @Test
    public void loadCorpus() throws Exception {
        final long count = BenchConfig.thingCount();
        final long seed = BenchConfig.seed();
        System.out.printf("=== CorpusLoadBench: loading %,d things (seed=%d) into %s ===%n", count, seed,
                BenchConfig.jdbcUrl());

        try (Connection connection =
                DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(), BenchConfig.password())) {
            connection.setAutoCommit(true);

            final long t0 = System.nanoTime();
            SearchSchemaDdl.dropAll(connection);
            SearchSchemaDdl.applyTables(connection);
            final long t1 = System.nanoTime();

            final long thingsCopied = CopyLoader.loadThings(connection, thingIterator(count, seed));
            final long t2 = System.nanoTime();

            final long flatCopied = CopyLoader.loadFlat(connection, thingIterator(count, seed));
            final long t3 = System.nanoTime();

            SearchSchemaDdl.applyIndexes(connection);
            final long t4 = System.nanoTime();

            analyze(connection);
            final long t5 = System.nanoTime();

            report(connection, thingsCopied, flatCopied, t0, t1, t2, t3, t4, t5);
        }
    }

    private static Iterator<ThingRecord> thingIterator(final long count, final long seed) {
        return new Iterator<>() {
            private long i;

            @Override
            public boolean hasNext() {
                return i < count;
            }

            @Override
            public ThingRecord next() {
                return CorpusGenerator.generate(i++, seed);
            }
        };
    }

    private static void analyze(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE search_things");
            statement.execute("ANALYZE search_flat");
        }
    }

    private static void report(final Connection connection, final long thingsCopied, final long flatCopied,
            final long t0, final long t1, final long t2, final long t3, final long t4, final long t5)
            throws SQLException {

        System.out.printf("schema apply:   %,d ms%n", millis(t0, t1));
        System.out.printf("COPY things:    %,d ms (%,d rows)%n", millis(t1, t2), thingsCopied);
        System.out.printf("COPY flat:      %,d ms (%,d rows)%n", millis(t2, t3), flatCopied);
        System.out.printf("index build:    %,d ms%n", millis(t3, t4));
        System.out.printf("ANALYZE:        %,d ms%n", millis(t4, t5));
        System.out.printf("TOTAL:          %,d ms%n", millis(t0, t5));

        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT (SELECT count(*) FROM search_things), (SELECT count(*) FROM search_flat)")) {
                rs.next();
                System.out.printf("search_things rows: %,d%n", rs.getLong(1));
                System.out.printf("search_flat rows:   %,d%n", rs.getLong(2));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT pg_size_pretty(pg_total_relation_size('search_things')), "
                            + "pg_size_pretty(pg_total_relation_size('search_flat'))")) {
                rs.next();
                System.out.printf("search_things total size (table+indexes+toast): %s%n", rs.getString(1));
                System.out.printf("search_flat total size (table+indexes+toast):   %s%n", rs.getString(2));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) "
                            + "FROM pg_stat_user_indexes WHERE relname IN ('search_things', 'search_flat') "
                            + "ORDER BY relname, indexrelname")) {
                while (rs.next()) {
                    System.out.printf("  index %-20s %s%n", rs.getString(1), rs.getString(2));
                }
            }
        }
    }

    private static long millis(final long fromNanos, final long toNanos) {
        return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
    }

}
