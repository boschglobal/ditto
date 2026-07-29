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
        return "# " + title + "\n\n"
                + "- generated: " + Instant.now() + "\n"
                + "- corpus: count=" + BenchConfig.count() + " seed=" + BenchConfig.seed() + "\n"
                + "- backend(s): " + BenchConfig.backend() + "\n"
                + "- protocol: " + BenchConfig.warmups() + " warmup + " + BenchConfig.iterations()
                + " timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0\n"
                + "- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)\n\n";
    }

    public static void writeEvidence(final String fileName, final String content) {
        final Path dir = Paths.get("src", "test", "resources", "bench", "results");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("[bench] evidence written: " + dir.resolve(fileName));
    }

    /**
     * Renders a JDBC "?"-placeholder SQL template (e.g. a {@code PersistenceShapes} constant) with
     * positional literals substituted in, for {@link #pgExplain(Connection, String)} which requires
     * literals already inlined (EXPLAIN cannot bind parameters). Numbers ({@link Number} instances)
     * render as plain text; everything else (String, OffsetDateTime, ...) is single-quoted with
     * embedded single quotes doubled, e.g. "O'Brien" becomes 'O''Brien'. These SQL templates contain
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
