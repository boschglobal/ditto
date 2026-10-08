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

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.ThingFlattener;
import org.eclipse.ditto.thingsearch.persistence.api.model.SearchIndexDocument;

/**
 * Loads the write-soak corpus with the production DDL ({@link ProductionConfigSchema}).
 * <ol>
 *     <li>Tables and reloptions.</li>
 *     <li>Per chunk, a {@code COPY search_things} and a {@code COPY search_flat}, each committing on its own, so a
 *     long load can resume at a chunk boundary.</li>
 *     <li>The production indexes and {@code sf_multi}, then {@code VACUUM (ANALYZE)}.</li>
 *     <li>The catalog check.</li>
 * </ol>
 * <p>
 * The rows are exactly what the shipped writer stores for revision 1 of each twin: {@code ThingFlattener} rows, and
 * the doc-row columns {@code PostgresSearchUpdaterFlow} binds ({@link CopyTextFormat#documentLine}). So the first soak
 * write of a thing is a normal leaf diff, not a rewrite of foreign rows.
 * </p>
 */
final class SoakCorpus {

    private static final String THINGS_COPY = "COPY search_things " + CopyLoader.THINGS_COLUMNS + " FROM STDIN";
    private static final String FLAT_COPY = "COPY search_flat " + CopyLoader.FLAT_COLUMNS + " FROM STDIN";
    private static final long PROGRESS_EVERY = 100_000L;
    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]*(kB|MB|GB)");

    record LoadReport(long things, long flatRows, ProductionConfigSchema.CatalogCheck catalog, String markdown) {}

    private SoakCorpus() {
        throw new AssertionError("no instances");
    }

    /**
     * Loads things {@code [from, count)}: from 0 the search tables are dropped and recreated first; a later
     * {@code from} resumes an interrupted load ({@link #requireResumable}), and {@code from = count} only repeats a
     * post-load that failed after the last COPY. Post-load (indexes, vacuum, catalog check) always runs at the end.
     */
    static LoadReport load(final Connection connection, final SoakTwin twin, final long count, final long from,
            final int chunk, @Nullable final String maintenanceWorkMem, final Consumer<String> progress)
            throws SQLException, IOException {
        if (from < 0 || from > count) {
            throw new IllegalArgumentException("from must lie in [0, " + count + "], got " + from);
        }
        if (chunk < 1) {
            throw new IllegalArgumentException("chunk must be at least 1, got " + chunk);
        }
        if (maintenanceWorkMem != null && !MEMORY.matcher(maintenanceWorkMem).matches()) {
            throw new IllegalArgumentException("maintenanceWorkMem must look like 512MB or 4GB, got <"
                    + maintenanceWorkMem + ">");
        }
        final long t0 = System.nanoTime();
        if (from == 0) {
            ProductionConfigSchema.dropAll(connection);
            ProductionConfigSchema.applyPreLoad(connection);
        } else {
            requireResumable(connection, from);
        }
        final long t1 = System.nanoTime();
        final long[] copied = copy(connection, twin, from, count, chunk, progress);
        final long t2 = System.nanoTime();
        if (maintenanceWorkMem != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET maintenance_work_mem = '" + maintenanceWorkMem + "'");
            }
        }
        progress.accept("building the production indexes");
        ProductionConfigSchema.applyPostLoad(connection);
        final long t3 = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            for (final String table : ProductionConfigSchema.tableNames()) {
                statement.execute("VACUUM (ANALYZE) " + table);
            }
        }
        final long t4 = System.nanoTime();
        final ProductionConfigSchema.CatalogCheck catalog = ProductionConfigSchema.verify(connection);
        final String markdown = report(connection, twin.seed(), count, from, chunk, maintenanceWorkMem, copied,
                catalog, new long[] {t0, t1, t2, t3, t4});
        return new LoadReport(copied[0], copied[1], catalog, markdown);
    }

    /** COPYs revision 1 of things {@code [from, to)}; @return {things, flat rows} copied. */
    static long[] copy(final Connection connection, final SoakTwin twin, final long from, final long to,
            final int chunk, final Consumer<String> progress) throws SQLException, IOException {
        long things = 0;
        long flatRows = 0;
        final long started = System.nanoTime();
        for (long start = from; start < to; start += chunk) {
            final long end = Math.min(to, start + chunk);
            final List<SearchIndexDocument> documents = new ArrayList<>((int) (end - start));
            for (long index = start; index < end; index++) {
                documents.add(twin.writeModel(index, 1).getDocument());
            }
            things += CopyLoader.copyIn(connection, THINGS_COPY,
                    documents.stream().map(CopyTextFormat::documentLine).iterator());
            flatRows += CopyLoader.copyIn(connection, FLAT_COPY, flatLines(documents));
            if (end / PROGRESS_EVERY != start / PROGRESS_EVERY || end == to) {
                progress.accept(String.format(Locale.ROOT, "loaded things [%,d, %,d) of %,d: %,d flat rows, %.0f s",
                        from, end, to, flatRows, (System.nanoTime() - started) / 1e9));
            }
        }
        return new long[] {things, flatRows};
    }

    /**
     * @throws IllegalStateException unless {@code search_things} holds exactly {@code from} rows and thing
     * {@code from - 1} has its flat rows (a load interrupted between the two COPYs of a chunk cannot resume).
     */
    static void requireResumable(final Connection connection, final long from) throws SQLException {
        final long things = scalar(connection, "SELECT count(*) FROM search_things");
        if (things != from) {
            throw new IllegalStateException("cannot resume at " + from + ": search_things holds " + things
                    + " rows");
        }
        final String last = SoakTwin.thingId(from - 1);
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM search_flat WHERE thing_id = ?")) {
            ps.setString(1, last);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("cannot resume at " + from + ": " + last + " has no flat rows "
                            + "(the chunk's search_flat COPY did not complete); delete that chunk's things or reload");
                }
            }
        }
    }

    /**
     * Refuses a corpus a soak would not measure: every soak starts each thing at revision 2 from the twin's seed, and
     * the shipped upsert has no revision predicate. So on a corpus an earlier soak has written, the writers would
     * replay that soak's documents and change no flat row; on a corpus of another seed, every first write would
     * rewrite all of a thing's leaves.
     *
     * @throws IllegalStateException unless things 0 and {@code count - 1} exist and thing {@code count} does not
     * (primary-key lookups), no thing is past revision 1 (one scan of {@code search_things}), and thing 0's stored
     * document is the twin's revision 1.
     */
    static void requireCorpusOf(final Connection connection, final SoakTwin twin, final long count)
            throws SQLException {
        // primary-key lookups rather than count(*), which would read the whole table right before a soak
        if (!exists(connection, SoakTwin.thingId(0)) || !exists(connection, SoakTwin.thingId(count - 1))
                || exists(connection, SoakTwin.thingId(count))) {
            throw new IllegalStateException("search_things does not hold the soak-twin corpus of " + count
                    + " things (" + SoakTwin.thingId(0) + " .. " + SoakTwin.thingId(count - 1)
                    + " and nothing beyond); load it with soak-load --count " + count);
        }
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT thing_id, revision FROM search_things WHERE revision <> 1 LIMIT 1")) {
            if (rs.next()) {
                throw new IllegalStateException("search_things is not a freshly loaded corpus: " + rs.getString(1)
                        + " is at revision " + rs.getLong(2) + ", so a soak would replay an earlier soak's writes; "
                        + "reload it with soak-load --count " + count + " before the next soak");
            }
        }
        final String first = SoakTwin.thingId(0);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT thing = CAST(? AS jsonb) FROM search_things WHERE thing_id = ?")) {
            ps.setString(1, twin.writeModel(0, 1).getDocument().thing().toString());
            ps.setString(2, first);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    throw new IllegalStateException(first + " is not revision 1 of the soak twin with seed "
                            + twin.seed() + "; load the corpus with that seed or soak with the seed it was loaded "
                            + "with");
                }
            }
        }
    }

    private static Iterator<String> flatLines(final List<SearchIndexDocument> documents) {
        return documents.stream()
                .flatMap(document -> ThingFlattener.flatten(document).stream())
                .map(CopyTextFormat::flatLine)
                .iterator();
    }

    private static boolean exists(final Connection connection, final String thingId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM search_things WHERE thing_id = ?")) {
            ps.setString(1, thingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long scalar(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String report(final Connection connection, final long seed, final long count, final long from,
            final int chunk, @Nullable final String maintenanceWorkMem, final long[] copied,
            final ProductionConfigSchema.CatalogCheck catalog, final long[] t) throws SQLException {
        final SoakAnalysis.Snapshot sizes = SoakDbStats.snapshot(connection);
        final long database = scalar(connection, "SELECT pg_database_size(current_database())");
        final long flatRows = scalar(connection,
                "SELECT greatest(reltuples, 0)::bigint FROM pg_class WHERE oid = 'search_flat'::regclass");
        final StringBuilder sb = new StringBuilder("# Production write-path soak: corpus load\n\n");
        sb.append(String.format(Locale.ROOT, "- corpus: %,d soak-twin things (seed %d), revision 1, loaded with "
                        + "the production DDL; this run copied things [%,d, %,d)%s in chunks of %,d%n", count, seed,
                from, count, from == 0 ? "" : " (resumed)", chunk));
        sb.append(String.format(Locale.ROOT, "- copied this run: %,d search_things rows, %,d search_flat rows%n",
                copied[0], copied[1]));
        sb.append(String.format(Locale.ROOT, "- search_flat: %,d rows (reltuples after VACUUM), %.1f per thing%n",
                flatRows, (double) flatRows / count));
        sb.append(String.format(Locale.ROOT, "- database: %s, %,d bytes per thing%n", SoakReport.human(database),
                database / count));
        sb.append(String.format(Locale.ROOT, "- session maintenance_work_mem for the index builds: %s%n",
                maintenanceWorkMem == null ? "server setting" : maintenanceWorkMem));
        sb.append(String.format(Locale.ROOT, "- catalog check: %s (%d items compared)%n%n",
                catalog.matchesProduction() ? "matches the production DDL" : "DIVERGES: " + catalog.differences(),
                catalog.comparedItems()));
        sb.append("| phase | ms |\n|---|---:|\n");
        sb.append(String.format(Locale.ROOT, "| %s | %d |%n", from == 0 ? "drop + pre-load DDL" : "resume check",
                (t[1] - t[0]) / 1_000_000));
        sb.append(String.format(Locale.ROOT, "| COPY (things + flat, per chunk) | %d |%n", (t[2] - t[1]) / 1_000_000));
        sb.append(String.format(Locale.ROOT, "| production indexes + sf_multi | %d |%n", (t[3] - t[2]) / 1_000_000));
        sb.append(String.format(Locale.ROOT, "| VACUUM (ANALYZE) | %d |%n", (t[4] - t[3]) / 1_000_000));
        sb.append(String.format(Locale.ROOT, "| **total** | **%d** |%n%n", (t[4] - t[0]) / 1_000_000));
        sb.append("| relation | live tuples | heap | indexes | total |\n|---|---:|---:|---:|---:|\n");
        sizes.relations().values().forEach(r -> sb.append(String.format(Locale.ROOT, "| %s | %,d | %s | %s | %s |%n",
                r.relation(), r.liveTuples(), SoakReport.human(r.heapBytes()), SoakReport.human(r.indexBytes()),
                SoakReport.human(r.totalBytes()))));
        sb.append("\n| index | size |\n|---|---:|\n");
        sizes.indexBytes().forEach((index, bytes) -> sb.append("| ").append(index).append(" | ")
                .append(SoakReport.human(bytes)).append(" |\n"));
        sb.append("\n| setting | value |\n|---|---|\n");
        for (final Map.Entry<String, String> e : SoakDbStats.settings(connection).entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        return sb.toString();
    }

}
