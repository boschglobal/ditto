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
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Production-config mode, step 1: loads the SAME deterministic corpus as {@link CorpusLoadBench} (same
 * {@link CorpusGenerator}, same seed/count defaults, same {@link CopyLoader} and bench {@link Flattener} — see
 * {@code BenchCorpusFlattenerParityTest} for how its rows relate to the shipped flattener) into a schema created ONLY
 * from the shipped {@code PostgresSearchSchema.ddlStatements()} ({@link ProductionConfigSchema}): no statistics
 * targets, no covering indexes, no scoped trigram index.
 * <p>
 * Steps: drop the search tables → production DDL minus its {@code CREATE INDEX} statements → {@code COPY} things →
 * {@code COPY} flat rows → the production {@code CREATE INDEX} statements → {@code VACUUM (ANALYZE)} (the steady state
 * autovacuum reaches on its own for an insert-only table, taken deterministically here so no background autovacuum runs
 * during the read measurements) → catalog check against the production DDL (fails the run on any divergence) → sizes.
 * Evidence lands in {@code <bench.pc.resultsDir>/load.md}.
 * </p>
 * <p>
 * <b>Deliberately excluded from every automatic build phase</b> (the name matches no Surefire/Failsafe pattern). Run
 * via {@code benchmark/run-benchmark.sh pc-load} or explicitly:
 * </p>
 * <pre>
 * mvn -pl internal/utils/search-r2dbc test -Dtest=ProductionConfigLoadBench -DfailIfNoTests=false \
 *     -DBENCH_PG_URL=jdbc:postgresql://localhost:55433/bench
 * </pre>
 */
public class ProductionConfigLoadBench {

    @Test
    public void loadCorpus() throws Exception {
        final long count = BenchConfig.thingCount();
        final long seed = BenchConfig.seed();
        System.out.printf("=== ProductionConfigLoadBench: loading %,d things (seed=%d) into %s ===%n", count, seed,
                BenchConfig.jdbcUrl());

        try (Connection connection =
                DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(), BenchConfig.password())) {
            connection.setAutoCommit(true);

            final long t0 = System.nanoTime();
            ProductionConfigSchema.dropAll(connection);
            ProductionConfigSchema.applyPreLoad(connection);
            final long t1 = System.nanoTime();
            final long thingsCopied = CopyLoader.loadThings(connection, thingIterator(count, seed));
            final long t2 = System.nanoTime();
            final long flatCopied = CopyLoader.loadFlat(connection, thingIterator(count, seed));
            final long t3 = System.nanoTime();
            ProductionConfigSchema.applyPostLoad(connection);
            final long t4 = System.nanoTime();
            vacuumAnalyze(connection);
            final long t5 = System.nanoTime();

            final ProductionConfigSchema.CatalogCheck check = ProductionConfigSchema.verify(connection);
            final String report = report(connection, count, seed, thingsCopied, flatCopied, check,
                    new long[] {t0, t1, t2, t3, t4, t5});
            System.out.println(report);
            BenchQuerySupport.writeResultFile(BenchConfig.productionConfigResultsDir().resolve("load.md"), report);
            if (!check.matchesProduction()) {
                throw new AssertionError("catalog diverges from the production DDL: " + check);
            }
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

    private static void vacuumAnalyze(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (final String table : ProductionConfigSchema.tableNames()) {
                statement.execute("VACUUM (ANALYZE) " + table);
            }
        }
    }

    private static String report(final Connection connection, final long count, final long seed,
            final long thingsCopied, final long flatCopied, final ProductionConfigSchema.CatalogCheck check,
            final long[] t) throws SQLException {
        final StringBuilder sb = new StringBuilder();
        sb.append("# Production-config corpus load\n\n");
        sb.append("Generated by `ProductionConfigLoadBench` on ").append(Instant.now()).append(" against `")
                .append(BenchConfig.jdbcUrl()).append("`.\n\n");
        sb.append("Corpus: `CorpusGenerator`, count=").append(count).append(", seed=").append(seed)
                .append(" (the original Phase-0 corpus). Schema: `PostgresSearchSchema.ddlStatements()` only ")
                .append("(tables before the COPY, the production `CREATE INDEX` statements after it), then ")
                .append("`VACUUM (ANALYZE)` of every search table.\n\n");
        sb.append("| phase | ms |\n|---|---:|\n");
        sb.append("| drop + production DDL (extension, tables) | ").append(ms(t[0], t[1])).append(" |\n");
        sb.append("| COPY search_things (").append(thingsCopied).append(" rows) | ").append(ms(t[1], t[2]))
                .append(" |\n");
        sb.append("| COPY search_flat (").append(flatCopied).append(" rows) | ").append(ms(t[2], t[3]))
                .append(" |\n");
        sb.append("| production CREATE INDEX statements | ").append(ms(t[3], t[4])).append(" |\n");
        sb.append("| VACUUM (ANALYZE) | ").append(ms(t[4], t[5])).append(" |\n");
        sb.append("| **total** | **").append(ms(t[0], t[5])).append("** |\n\n");

        try (Statement statement = connection.createStatement()) {
            sb.append("## Server\n\n");
            try (ResultSet rs = statement.executeQuery("SELECT version()")) {
                rs.next();
                sb.append("`").append(rs.getString(1)).append("`\n\n");
            }
            sb.append("## Sizes\n\n| relation | kind | size | bytes |\n|---|---|---:|---:|\n");
            try (ResultSet rs = statement.executeQuery("SELECT c.relname, CASE c.relkind WHEN 'r' THEN 'table (heap)' "
                    + "WHEN 'i' THEN 'index' WHEN 't' THEN 'toast' ELSE c.relkind::text END, "
                    + "pg_size_pretty(pg_relation_size(c.oid)), pg_relation_size(c.oid) "
                    + "FROM pg_class c LEFT JOIN pg_index i ON i.indexrelid = c.oid "
                    + "LEFT JOIN pg_class t ON t.oid = i.indrelid "
                    + "WHERE c.relname IN ('search_things', 'search_flat', 'search_sync') "
                    + "OR t.relname IN ('search_things', 'search_flat', 'search_sync') "
                    + "ORDER BY pg_relation_size(c.oid) DESC")) {
                while (rs.next()) {
                    sb.append("| ").append(rs.getString(1)).append(" | ").append(rs.getString(2)).append(" | ")
                            .append(rs.getString(3)).append(" | ").append(rs.getLong(4)).append(" |\n");
                }
            }
            try (ResultSet rs = statement.executeQuery("SELECT "
                    + "pg_size_pretty(pg_total_relation_size('search_things')), "
                    + "pg_size_pretty(pg_total_relation_size('search_flat')), "
                    + "pg_size_pretty(pg_database_size(current_database())), "
                    + "pg_database_size(current_database())")) {
                rs.next();
                sb.append("\nTotal incl. indexes + TOAST: search_things ").append(rs.getString(1))
                        .append(", search_flat ").append(rs.getString(2)).append("; database ")
                        .append(rs.getString(3)).append(" (").append(rs.getLong(4)).append(" bytes).\n\n");
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT (SELECT count(*) FROM search_things), (SELECT count(*) FROM search_flat)")) {
                rs.next();
                sb.append("Row counts: search_things ").append(rs.getLong(1)).append(", search_flat ")
                        .append(rs.getLong(2)).append(".\n\n");
            }
        }

        sb.append("## Catalog check against the production DDL\n\n");
        sb.append("Every index definition, column, constraint, reloption, per-column statistics target and extended ")
                .append("statistics object of the search tables, compared with a reference built from ")
                .append("`PostgresSearchSchema.ddlStatements()` in a rolled-back scratch schema.\n\n");
        sb.append("- matches production: **").append(check.productionVerdict()).append("** (")
                .append(check.comparedItems()).append(" items compared)\n");
        sb.append("- differences: ").append(check.differences()).append("\n\n");
        sb.append("```\n");
        check.indexDefinitions().forEach(def -> sb.append(def).append('\n'));
        sb.append("```\n");
        return sb.toString();
    }

    private static long ms(final long fromNanos, final long toNanos) {
        return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
    }

}
