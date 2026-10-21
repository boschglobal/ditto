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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.json.JsonWriterSettings;

import com.mongodb.client.MongoDatabase;

/**
 * Shared measurement protocol (3 warmups + 20 timed executions by default, nearest-rank
 * percentiles), markdown evidence writer, and plan capture for both backends.
 */
public final class BenchProtocol {

    static final String DEFAULT_RESULTS_DIR = "src/test/resources/bench/results";

    private BenchProtocol() {
    }

    public interface Op {
        void run(int iteration) throws Exception;
    }

    public record Timing(int n, double p50Ms, double p95Ms, double maxMs, double meanMs,
            List<Double> samplesMs) {

        /** "p50 | p95 | max" cells, two decimals. */
        public String cells() {
            return String.format("%.2f | %.2f | %.2f", p50Ms, p95Ms, maxMs);
        }
    }

    public static Timing measure(final Op op) {
        return measure(BenchConfig.warmups(), BenchConfig.iterations(), op);
    }

    public static Timing measure(final int warmups, final int iterations, final Op op) {
        try {
            int iteration = 0;
            for (int w = 0; w < warmups; w++) {
                op.run(iteration++);
            }
            final List<Double> samples = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                final long t0 = System.nanoTime();
                op.run(iteration++);
                samples.add((System.nanoTime() - t0) / 1_000_000.0);
            }
            return of(samples);
        } catch (final Exception e) {
            throw new IllegalStateException("bench op failed", e);
        }
    }

    /**
     * Times two ops on the same iterations, interleaved so that neither always runs on the cache the
     * other left behind: even iterations run {@code a} first, odd ones {@code b} first. Both ops get
     * the same iteration index (e.g. the same lower bound).
     *
     * @return the timings of {@code a} and {@code b}, in that order.
     */
    public static Timing[] measurePair(final int warmups, final int iterations, final Op a, final Op b) {
        try {
            final List<Double> samplesA = new ArrayList<>(iterations);
            final List<Double> samplesB = new ArrayList<>(iterations);
            for (int iteration = 0; iteration < warmups + iterations; iteration++) {
                final boolean timed = iteration >= warmups;
                final boolean aFirst = iteration % 2 == 0;
                runTimed(aFirst ? a : b, iteration, timed ? (aFirst ? samplesA : samplesB) : null);
                runTimed(aFirst ? b : a, iteration, timed ? (aFirst ? samplesB : samplesA) : null);
            }
            return new Timing[]{of(samplesA), of(samplesB)};
        } catch (final Exception e) {
            throw new IllegalStateException("bench op failed", e);
        }
    }

    private static void runTimed(final Op op, final int iteration, final List<Double> samples) throws Exception {
        final long t0 = System.nanoTime();
        op.run(iteration);
        if (samples != null) {
            samples.add((System.nanoTime() - t0) / 1_000_000.0);
        }
    }

    public static Timing of(final List<Double> samplesMs) {
        final List<Double> sorted = new ArrayList<>(samplesMs);
        sorted.sort(Double::compareTo);
        final double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new Timing(sorted.size(),
                percentile(sorted, 50.0),
                percentile(sorted, 95.0),
                sorted.get(sorted.size() - 1),
                mean,
                sorted);
    }

    /** Nearest-rank percentile on an ascending-sorted list. */
    public static double percentile(final List<Double> sortedAscending, final double pct) {
        final int n = sortedAscending.size();
        final int rank = (int) Math.ceil(pct / 100.0 * n);
        return sortedAscending.get(Math.max(0, Math.min(n - 1, rank - 1)));
    }

    public static String table(final List<String> header, final List<List<String>> rows) {
        final StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", escapeCells(header))).append(" |\n");
        sb.append("|").append("---|".repeat(header.size())).append("\n");
        for (final List<String> row : rows) {
            sb.append("| ").append(String.join(" | ", escapeCells(row))).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * Escapes '|' inside each cell so a cell that itself contains "a | b" (e.g. {@link Timing#cells()}
     * or a multi-metric header label) still renders as ONE logical GFM column; otherwise the header
     * row would parse with more columns than the delimiter row and GitHub would render garbage.
     */
    private static List<String> escapeCells(final List<String> cells) {
        return cells.stream().map(cell -> cell.replace("|", "\\|")).toList();
    }

    /** Standard evidence-file preamble recording all run parameters. */
    public static String runHeader(final String title) {
        final String label = BenchConfig.evidenceLabel();
        return "# " + title + "\n\n"
                + "- generated: " + Instant.now() + "\n"
                + "- corpus: count=" + BenchConfig.count() + " seed=" + BenchConfig.seed() + freshCorpusSuffix() + "\n"
                + "- backend(s): " + BenchConfig.backend() + "\n"
                + containersLine(BenchConfig.containerLimits())
                + "- protocol: " + BenchConfig.warmups() + " warmup + " + BenchConfig.iterations()
                + " timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0\n"
                + "- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)\n"
                + (label.isEmpty() ? "" : "- evidence label: " + label + "\n")
                + "\n";
    }

    static String containersLine(final String limits) {
        return "- containers: "
                + (limits.isBlank() ? "not recorded (not started through benchmark/run-benchmark.sh)" : limits) + "\n";
    }

    /** Empty for the backdated corpus, so evidence headers of share-0 runs keep their format. */
    private static String freshCorpusSuffix() {
        final double share = BenchConfig.freshShare();
        return share > 0.0
                ? " freshShare=" + share + " freshBase=" + Instant.ofEpochMilli(BenchConfig.freshBaseMillis())
                : "";
    }

    /**
     * {@code base.md}, or {@code base-<label>.md} with {@code bench.evidenceLabel}, so that a labelled run never
     * overwrites committed evidence.
     */
    public static String evidenceName(final String base) {
        return labelled(base, BenchConfig.evidenceLabel()) + ".md";
    }

    static String labelled(final String base, final String label) {
        return label.isEmpty() ? base : base + "-" + label;
    }

    /**
     * The evidence file name for a corpus: {@code base.md} for the backdated corpus, a fresh-share
     * suffix otherwise, so a fresh-share run never overwrites the backdated corpus's evidence; then the
     * {@code bench.evidenceLabel} suffix, if any.
     */
    public static String corpusEvidenceName(final String base, final CorpusGenerator gen) {
        return corpusEvidenceName(base, gen, BenchConfig.evidenceLabel());
    }

    static String corpusEvidenceName(final String base, final CorpusGenerator gen, final String label) {
        final String corpusBase = gen.freshShare() > 0.0 ? base + "-fresh-share-" + gen.freshShare() : base;
        return labelled(corpusBase, label) + ".md";
    }

    /** One evidence line with the corpus's fresh-only pid counts; empty for the backdated corpus. */
    public static String freshCorpusLine(final CorpusGenerator gen) {
        if (gen.freshShare() <= 0.0) {
            return "";
        }
        long fresh = 0;
        long snapshotted = 0;
        long freshSnapshotted = 0;
        for (long i = 0; i < gen.pidCount(); i++) {
            final boolean isFresh = gen.isFreshOnly(i);
            final boolean hasSnapshots = gen.snapshotCount(i) > 0;
            fresh += isFresh ? 1 : 0;
            snapshotted += hasSnapshots ? 1 : 0;
            freshSnapshotted += isFresh && hasSnapshots ? 1 : 0;
        }
        return "- fresh-only pids (share " + gen.freshShare() + ", rows at "
                + Instant.ofEpochMilli(gen.freshBaseMillis()) + " + sn seconds; all others at "
                + Instant.ofEpochMilli(CorpusGenerator.BASE_TIMESTAMP_MILLIS) + " + sn seconds): "
                + fresh + " of " + gen.pidCount() + "; with snapshots: " + freshSnapshotted + " of "
                + snapshotted + "\n\n";
    }

    public static void writeEvidence(final String fileName, final String content) {
        final Path file = evidencePath(BenchConfig.resultsDir(), fileName);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("[bench] evidence written: " + file);
    }

    /** {@code <dir>/<fileName>}; a name may contain a sub-folder (e.g. w3-latency/x.md). */
    static Path evidencePath(final String dir, final String fileName) {
        return Paths.get(dir).resolve(fileName);
    }

    /**
     * Renders a JDBC "?"-placeholder SQL template (e.g. a {@code PersistenceShapes} constant) with
     * positional literals substituted in, for {@link #pgExplain(Connection, String)} which requires
     * literals already inlined (EXPLAIN cannot bind parameters). Numbers ({@link Number} instances)
     * render as plain text; {@code null} renders as SQL NULL; everything else (String, OffsetDateTime, ...) is
     * single-quoted with embedded single quotes doubled, e.g. "O'Brien" becomes 'O''Brien'. These SQL templates contain
     * no string literals with '?' in them, so counting '?' occurrences is an exact placeholder count.
     *
     * @throws IllegalArgumentException if the number of '?' placeholders differs from args.length
     */
    public static String inlineLiterals(final String sqlTemplate, final Object... args) {
        final long placeholders = sqlTemplate.chars().filter(c -> c == '?').count();
        if (placeholders != args.length) {
            throw new IllegalArgumentException(
                    "sqlTemplate has " + placeholders + " '?' placeholders but " + args.length
                            + " args were given");
        }
        final StringBuilder sb = new StringBuilder(sqlTemplate.length() + 16);
        int argIndex = 0;
        for (int i = 0; i < sqlTemplate.length(); i++) {
            final char c = sqlTemplate.charAt(i);
            if (c == '?') {
                sb.append(renderLiteral(args[argIndex++]));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String renderLiteral(final Object arg) {
        if (arg == null) {
            return "NULL";
        }
        if (arg instanceof Number) {
            return String.valueOf(arg);
        }
        return "'" + String.valueOf(arg).replace("'", "''") + "'";
    }

    /** One representative EXPLAIN (ANALYZE, BUFFERS); pass SQL with literals already inlined. */
    public static String pgExplain(final Connection connection, final String sqlWithLiterals) {
        final StringBuilder sb = new StringBuilder("```\n");
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sqlWithLiterals)) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('\n');
            }
        } catch (final SQLException e) {
            sb.append("EXPLAIN failed: ").append(e.getMessage()).append('\n');
        }
        return sb.append("```\n").toString();
    }

    /**
     * EXPLAIN (ANALYZE, BUFFERS) of one or more write statements inside ONE transaction that is always rolled back.
     * ANALYZE executes the statements, so this shows a write's real plan on the bench corpus without changing it: the
     * rollback discards the rows; what any aborted transaction leaves (a dead tuple per inserted row, a consumed
     * identity value) stays. The plan shows the executor's work, not the commit's WAL flush. Pass SQL with literals
     * inlined. A failing statement shows up as "EXPLAIN failed" (see pgExplain); the statements after it then report
     * the aborted transaction. The connection's autocommit mode is restored.
     */
    public static String pgExplainRolledBack(final Connection connection, final String... sqlWithLiterals) {
        final StringBuilder sb = new StringBuilder();
        try {
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (final String sql : sqlWithLiterals) {
                    sb.append(pgExplain(connection, sql));
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        } catch (final SQLException e) {
            throw new IllegalStateException("rolled-back EXPLAIN could not manage its transaction", e);
        }
        return sb.toString();
    }

    public static String mongoExplainFind(final MongoDatabase db, final String collection,
            final Document filter, final Document sort, final Document projection, final Integer limit) {
        final Document find = new Document("find", collection).append("filter", filter);
        if (sort != null) {
            find.append("sort", sort);
        }
        if (projection != null) {
            find.append("projection", projection);
        }
        if (limit != null) {
            find.append("limit", limit);
        }
        return runExplain(db, find);
    }

    public static String mongoExplainAggregate(final MongoDatabase db, final String collection,
            final List<? extends org.bson.conversions.Bson> pipeline) {
        final List<Document> stages = pipeline.stream()
                .map(s -> Document.parse(s.toBsonDocument(Document.class,
                        com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson()))
                .toList();
        final Document aggregate = new Document("aggregate", collection)
                .append("pipeline", stages)
                .append("cursor", new Document());
        return runExplain(db, aggregate);
    }

    /** deleteMany(filter) as the explain command runs it; explain deletes nothing. */
    public static String mongoExplainDelete(final MongoDatabase db, final String collection, final Document filter) {
        return runExplain(db, deleteExplainCommand(collection, filter));
    }

    /** replaceOne(filter, replacement, upsert=true) as the explain command runs it; explain writes nothing. */
    public static String mongoExplainReplaceUpsert(final MongoDatabase db, final String collection,
            final Document filter, final Document replacement) {
        return runExplain(db, updateExplainCommand(collection, filter, replacement));
    }

    /** For the write shapes MongoDB's explain command has no form for (insert, compact). */
    public static String mongoNotExplainable(final String operation) {
        return "```\nnot explainable: " + operation
                + " (MongoDB's explain command has no insert or compact form) — timed only\n```\n";
    }

    static Document deleteExplainCommand(final String collection, final Document filter) {
        return new Document("delete", collection)
                .append("deletes", List.of(new Document("q", filter).append("limit", 0)));
    }

    static Document updateExplainCommand(final String collection, final Document filter, final Document replacement) {
        return new Document("update", collection)
                .append("updates", List.of(new Document("q", filter).append("u", replacement)
                        .append("upsert", true).append("multi", false)));
    }

    /** nWouldDelete / nWouldModify / nWouldUpsert of a write explain's top stage; empty for reads. */
    static Document writeCounters(final Document executionStats) {
        final Document counters = new Document();
        if (executionStats.get("executionStages") instanceof Document stages) {
            for (final String key : List.of("nWouldDelete", "nWouldModify", "nWouldUpsert")) {
                if (stages.containsKey(key)) {
                    counters.append(key, stages.get(key));
                }
            }
        }
        return counters;
    }

    private static String runExplain(final MongoDatabase db, final Document innerCommand) {
        try {
            final Document result = db.runCommand(
                    new Document("explain", innerCommand).append("verbosity", "executionStats"));
            final StringBuilder sb = new StringBuilder("```json\n");
            final JsonWriterSettings pretty = JsonWriterSettings.builder().indent(true).build();
            final Document queryPlanner = extract(result, "queryPlanner");
            final Document executionStats = extract(result, "executionStats");
            if (queryPlanner != null && queryPlanner.get("winningPlan") instanceof Document winning) {
                sb.append("// winningPlan\n").append(winning.toJson(pretty)).append('\n');
            }
            if (executionStats != null) {
                final Document summary = new Document();
                for (final String key : List.of("nReturned", "executionTimeMillis",
                        "totalKeysExamined", "totalDocsExamined")) {
                    if (executionStats.containsKey(key)) {
                        summary.append(key, executionStats.get(key));
                    }
                }
                summary.putAll(writeCounters(executionStats));
                sb.append("// executionStats (summary)\n").append(summary.toJson(pretty)).append('\n');
            }
            if (queryPlanner == null && executionStats == null) {
                final String full = result.toJson(pretty);
                sb.append(full, 0, Math.min(full.length(), 6_000)).append('\n');
            }
            return sb.append("```\n").toString();
        } catch (final RuntimeException e) {
            return "```\nexplain failed: " + e.getMessage() + "\n```\n";
        }
    }

    /** Digs queryPlanner/executionStats out of both top-level and $cursor-nested (aggregate) explains. */
    private static Document extract(final Document explainResult, final String key) {
        if (explainResult.get(key) instanceof Document direct) {
            return direct;
        }
        if (explainResult.get("stages") instanceof List<?> stages && !stages.isEmpty()
                && stages.get(0) instanceof Document first
                && first.get("$cursor") instanceof Document cursor
                && cursor.get(key) instanceof Document nested) {
            return nested;
        }
        return null;
    }
}
