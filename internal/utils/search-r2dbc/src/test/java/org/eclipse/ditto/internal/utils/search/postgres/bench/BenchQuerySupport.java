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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shared measurement/EXPLAIN-capture/result-persistence plumbing for the Task 0.2 read-path benchmarks
 * ({@link ReadPathBench}, {@link PlanShapeComparisonBench}). Deliberately dependency-free (plain JDBC) like
 * the rest of the {@code bench} package.
 */
final class BenchQuerySupport {

    private BenchQuerySupport() {
        throw new AssertionError("no instances");
    }

    /** Marker wrapper telling {@link #bind} to bind this value as a JDBC {@code text[]} array. */
    static final class TextArray {
        final String[] values;

        TextArray(final String... values) {
            this.values = values;
        }
    }

    /** p50/p95/max/min latency in milliseconds over {@code iterations} timed (post-warmup) executions. */
    record LatencyStats(int iterations, long minMs, long p50Ms, long p95Ms, long maxMs) {

        String toMarkdownRow(final String label) {
            return String.format(Locale.ROOT, "| %-42s | %10d | %8d | %8d | %8d | %8d |",
                    label, iterations, minMs, p50Ms, p95Ms, maxMs);
        }
    }

    /** Supplies the bind values for the {@code iteration}-th execution (0-based, warmup executions included). */
    interface ParamsForIteration {
        List<Object> paramsFor(int iteration);
    }

    static final String LATENCY_TABLE_HEADER =
            "| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |\n"
                    + "|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|";

    /**
     * Runs {@code warmupCount} untimed executions followed by {@code timedCount} timed executions of {@code sql},
     * fully draining each {@link ResultSet} (so timings include fetch, not just planning/execute-start) and
     * returning p50/p95/max/min over the timed portion only.
     * <p>
     * <b>Methodology note:</b> a fresh {@link PreparedStatement} is created for every single execution rather
     * than one being reused across iterations. Reusing one {@code PreparedStatement} across ~23 varying-bind
     * executions crosses pgjdbc's default {@code prepareThreshold=5}: after the 5th execution pgjdbc switches to
     * a server-side NAMED prepared statement, and PostgreSQL's planner may then flip to a single generic plan
     * shared across all subsequent bind values instead of a per-bind custom plan. Preparing fresh per execution
     * removes that JDBC-driver-specific variable and matches how R2DBC call sites typically bind-and-execute.
     * (Empirically, for shape 1 the reuse made no measurable difference at this corpus — p50 6.6s reused vs
     * 6.5s fresh, because the dominant cost was cold-cache page fetches under the same plan — so this is kept
     * as the methodologically-clean variant, not as a performance fix.)
     */
    static LatencyStats measure(final Connection connection, final String sql, final int warmupCount,
            final int timedCount, final ParamsForIteration paramsSupplier) throws SQLException {

        for (int i = 0; i < warmupCount; i++) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(connection, ps, paramsSupplier.paramsFor(i));
                drain(ps);
            }
        }
        final long[] millis = new long[timedCount];
        for (int i = 0; i < timedCount; i++) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(connection, ps, paramsSupplier.paramsFor(warmupCount + i));
                final long t0 = System.nanoTime();
                drain(ps);
                millis[i] = (System.nanoTime() - t0) / 1_000_000L;
            }
        }
        Arrays.sort(millis);
        return new LatencyStats(timedCount, millis[0], percentile(millis, 50), percentile(millis, 95),
                millis[millis.length - 1]);
    }

    private static long percentile(final long[] sortedMillis, final int pct) {
        final int idx = Math.max(0,
                Math.min((int) Math.ceil(pct / 100.0 * sortedMillis.length) - 1, sortedMillis.length - 1));
        return sortedMillis[idx];
    }

    /**
     * Package-private (not {@code private}) so {@code MitigationBench}'s review-fix (round 1, Finding 1) cold-start
     * probe can drain individual executions itself without going through {@link #measure} — that method's
     * warmup/timed-percentile shape doesn't fit "one first execution captured via EXPLAIN, then 2-3 raw
     * executions to show the warmup slope".
     */
    static int drain(final PreparedStatement ps) throws SQLException {
        int n = 0;
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                n++;
            }
        }
        return n;
    }

    /** Binds {@code params} positionally (1-based) onto {@code ps}, materializing {@link TextArray} as JDBC arrays. */
    static void bind(final Connection connection, final PreparedStatement ps, final List<Object> params)
            throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            final Object p = params.get(i);
            final int idx = i + 1;
            if (p instanceof TextArray ta) {
                ps.setArray(idx, connection.createArrayOf("text", ta.values));
            } else if (p instanceof BigDecimal bd) {
                ps.setBigDecimal(idx, bd);
            } else if (p instanceof Boolean b) {
                ps.setBoolean(idx, b);
            } else if (p instanceof Integer n) {
                ps.setInt(idx, n);
            } else if (p instanceof Long n) {
                ps.setLong(idx, n);
            } else if (p instanceof String s) {
                ps.setString(idx, s);
            } else {
                ps.setObject(idx, p);
            }
        }
    }

    /** Runs {@code EXPLAIN (ANALYZE, BUFFERS) <sql>} with {@code params} bound, returning the plan text verbatim. */
    static String explainAnalyzeBuffers(final Connection connection, final String sql, final List<Object> params)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
            bind(connection, ps, params);
            final StringBuilder sb = new StringBuilder();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * @return {@code true} if any plan line is a {@code Seq Scan on search_flat} node (the shapes 1-3/5 gate:
     * no sequential scan of the 68M-row flat table).
     */
    static boolean hasSeqScanOnSearchFlat(final String explainText) {
        for (final String rawLine : explainText.split("\n")) {
            String line = rawLine.trim();
            while (line.startsWith("->")) {
                line = line.substring(2).trim();
            }
            if (line.regionMatches(true, 0, "Seq Scan on search_flat", 0, "Seq Scan on search_flat".length())) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern TRGM_BITMAP_SCAN =
            java.util.regex.Pattern.compile("Bitmap Index Scan on sf_trgm\\b");

    /**
     * @return {@code true} if any plan line is a {@code Bitmap Index Scan on sf_trgm} node — word-bounded so the
     * Task 0.2b scoped variant ({@code sf_trgm_scoped_feature}) does NOT match (an early MitigationBench E4 run
     * printed a false "uses table-wide sf_trgm: true" because of a plain substring match here; {@code \b} after
     * {@code sf_trgm} does not match the following underscore of the scoped index's name).
     */
    static boolean usesTrgmBitmapIndex(final String explainText) {
        return TRGM_BITMAP_SCAN.matcher(explainText).find();
    }

    /**
     * @return {@code true} if any plan line mentions the given index name (used by MitigationBench, Task 0.2b,
     * to check whether a newly created index — e.g. a covering or scoped-trgm variant — was actually picked by
     * the planner instead of the pre-existing index of the same family).
     */
    static boolean mentionsIndexByName(final String explainText, final String indexName) {
        for (final String rawLine : explainText.split("\n")) {
            if (rawLine.contains(indexName)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} if any plan line is an {@code Index Only Scan} node mentioning {@code indexName}. */
    static boolean usesIndexOnlyScan(final String explainText, final String indexName) {
        for (final String rawLine : explainText.split("\n")) {
            final String line = rawLine.trim();
            if (line.contains("Index Only Scan") && line.contains(indexName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the plan's top (outermost) node label, e.g. {@code "Aggregate"} or {@code "Nested Loop"} — the text
     * before the first {@code "  ("} on the first non-blank line, or {@code "?"} if the format is unrecognized.
     * Used by MitigationBench (Task 0.2b) to record a one-word "plan type" column per cumulative-table row
     * without re-deriving detailed structure.
     */
    static String topPlanNode(final String explainText) {
        for (final String rawLine : explainText.split("\n")) {
            final String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            final int parenIdx = line.indexOf('(');
            return parenIdx > 0 ? line.substring(0, parenIdx).trim() : line;
        }
        return "?";
    }

    /**
     * Extracts the planner's estimated row count and the actually-observed row count for the first plan line
     * containing {@code nodeMarker} (e.g. an index name), by matching {@code rows=<est> width=} (the planner
     * estimate) and {@code actual time=...rows=<act> loops=} (the runtime observation) on that same line.
     *
     * @return a two-element {@code [estimatedRows, actualRows]} array, or {@code null} if no matching line, or no
     * line with both patterns, is found. Used by MitigationBench (Task 0.2b, E1) to quantify statistics-target
     * misestimate before/after {@code ALTER ... SET STATISTICS} + {@code ANALYZE}.
     */
    static long[] estimateVsActualRows(final String explainText, final String nodeMarker) {
        final java.util.regex.Pattern estPattern = java.util.regex.Pattern.compile("rows=(\\d+) width");
        final java.util.regex.Pattern actPattern =
                java.util.regex.Pattern.compile("actual time=\\S+ rows=(\\d+) loops");
        for (final String line : explainText.split("\n")) {
            if (!line.contains(nodeMarker)) {
                continue;
            }
            final java.util.regex.Matcher est = estPattern.matcher(line);
            final java.util.regex.Matcher act = actPattern.matcher(line);
            if (est.find() && act.find()) {
                return new long[] {Long.parseLong(est.group(1)), Long.parseLong(act.group(1))};
            }
        }
        return null;
    }

    /**
     * Extracts the first {@code "Buffers: shared hit=<H> ... read=<R> ..."} occurrence — the plan's outermost
     * (top-level) node, since {@code EXPLAIN} prints the root node before its indented children — as
     * {@code [hit, read]} (either may be {@code 0} if that keyword is absent from the line). Returns {@code null}
     * if no {@code Buffers:} line is found at all. Used by MitigationBench (Task 0.2b, especially E2) to report
     * PostgreSQL's own shared-buffer cache hit ratio, following the same "top-level Buffers line" convention
     * already used in the Task 0.2 report's methodology notes.
     */
    static long[] topLevelBufferHitAndRead(final String explainText) {
        for (final String rawLine : explainText.split("\n")) {
            final String line = rawLine.trim();
            if (line.startsWith("Buffers:")) {
                long hit = 0;
                long read = 0;
                final java.util.regex.Matcher hitMatcher = java.util.regex.Pattern.compile("hit=(\\d+)").matcher(line);
                if (hitMatcher.find()) {
                    hit = Long.parseLong(hitMatcher.group(1));
                }
                final java.util.regex.Matcher readMatcher = java.util.regex.Pattern.compile("read=(\\d+)").matcher(line);
                if (readMatcher.find()) {
                    read = Long.parseLong(readMatcher.group(1));
                }
                return new long[] {hit, read};
            }
        }
        return null;
    }

    static void writeResultFile(final Path path, final String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Appends {@code content} to an existing committed results file (creating it if absent) — used by
     * single-method review-fix reruns that add evidence to a file without clobbering the rest of it (see
     * {@link ReadPathBench}'s shape-4 populated-branch keyset verification).
     */
    static void appendResultFile(final Path path, final String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static List<Object> listOf(final Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

}
