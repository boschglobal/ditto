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

import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.LATENCY_TABLE_HEADER;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.LatencyStats;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.TextArray;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.explainAnalyzeBuffers;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.hasSeqScanOnSearchFlat;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.measure;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.writeResultFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Task 0.2's EXISTS-chain vs semi-join plan-shape comparison: for a 2-predicate AND (shape 1's own predicates)
 * and a 3-predicate variant, at both a "selective+selective" and a "selective+unselective" bind mix, benchmarks
 * the brief's two alternative formulations of the same logical query against the 1M-thing bench corpus.
 * <p>
 * <b>Deliberately excluded from every automatic build phase</b> — run explicitly:
 * <pre>
 * mvn -pl internal/utils/search-r2dbc test -Dtest=PlanShapeComparisonBench -DfailIfNoTests=false
 * </pre>
 */
public class PlanShapeComparisonBench {

    private static final int WARMUP = 3;
    private static final int TIMED = 20;

    private static final Path RESULTS_DIR = Path.of("src/test/resources/bench/results");

    private static final String TEMP_PATH = "/features/env/properties/temperature";
    private static final String WEIGHT_PATH = "/attributes/weightKg";
    private static final String CITY_PATH = "/attributes/location/city";
    private static final String CERTIFIED_PATH = "/attributes/certified";

    private static final double[] TEMP_BUCKET_LO = {-15, -10, -5, 0, 5, 10, 15, 20, 25, 30};
    private static final double[] WEIGHT_THRESHOLDS = {700, 750, 800, 850, 900, 950, 990};
    private static final String[] CITIES = {
            "Stuttgart", "Berlin", "Munich", "Hamburg", "Cologne", "Frankfurt", "Leipzig", "Dresden", "Bonn", "Essen",
    };

    private Connection connection;

    @Before
    public void connect() throws SQLException {
        connection = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(), BenchConfig.password());
        connection.setAutoCommit(true);
    }

    @After
    public void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    /** One flat-row predicate: its own wpath, its own {@code f.}-aliased predicate SQL, and varying binds. */
    private record PredicateSpec(String wpath, String predSql, IntFunction<List<Object>> paramsForIteration,
            String description) {
    }

    private static PredicateSpec tempBucketPredicate() {
        return new PredicateSpec(TEMP_PATH, "f.val_num BETWEEN ? AND ?",
                i -> List.of(bd(TEMP_BUCKET_LO[i % TEMP_BUCKET_LO.length]),
                        bd(TEMP_BUCKET_LO[i % TEMP_BUCKET_LO.length] + 0.99)),
                "temperature in [bucket,bucket+0.99] — selective, ~1.4%");
    }

    private static PredicateSpec weightPredicate() {
        return new PredicateSpec(WEIGHT_PATH, "f.val_num > ?",
                i -> List.of(bd(WEIGHT_THRESHOLDS[i % WEIGHT_THRESHOLDS.length])),
                "weightKg > threshold — selective, ~4-9%");
    }

    private static PredicateSpec cityPredicate() {
        return new PredicateSpec(CITY_PATH, "f.val_text = ?",
                i -> List.of(CITIES[i % CITIES.length]),
                "location/city = <one of 10 cities> — unselective, ~9%/city");
    }

    private static PredicateSpec certifiedPredicate() {
        return new PredicateSpec(CERTIFIED_PATH, "f.val_bool = ?",
                i -> List.of(Boolean.TRUE),
                "certified = true — selective, ~2% (of full population; ~49% of the ~4% present)");
    }

    @Test
    public void twoPredicateSelectiveSelective() throws Exception {
        runMix("2-pred-SS", "2-predicate, selective+selective (temperature bucket AND weightKg>threshold)",
                List.of(tempBucketPredicate(), weightPredicate()));
    }

    @Test
    public void twoPredicateSelectiveUnselective() throws Exception {
        runMix("2-pred-SU", "2-predicate, selective+unselective (city AND temperature bucket — shape 1's own WHERE)",
                List.of(cityPredicate(), tempBucketPredicate()));
    }

    @Test
    public void threePredicateSelectiveSelective() throws Exception {
        runMix("3-pred-SS", "3-predicate, all-selective (temperature bucket AND weightKg>threshold AND certified)",
                List.of(tempBucketPredicate(), weightPredicate(), certifiedPredicate()));
    }

    @Test
    public void threePredicateSelectiveUnselective() throws Exception {
        runMix("3-pred-SU", "3-predicate, selective+unselective mix (city AND temperature bucket AND weightKg)",
                List.of(cityPredicate(), tempBucketPredicate(), weightPredicate()));
    }

    private void runMix(final String fileSuffix, final String title, final List<PredicateSpec> predicates)
            throws Exception {

        final String existsSql = existsChainSql(predicates);
        final String semiJoinSql = semiJoinSql(predicates);

        final LatencyStats existsStats =
                measure(connection, existsSql, WARMUP, TIMED, i -> existsChainParams(predicates, i));
        final String existsExplain = explainAnalyzeBuffers(connection, existsSql, existsChainParams(predicates, 0));

        final LatencyStats semiJoinStats =
                measure(connection, semiJoinSql, WARMUP, TIMED, i -> semiJoinParams(predicates, i));
        final String semiJoinExplain = explainAnalyzeBuffers(connection, semiJoinSql, semiJoinParams(predicates, 0));

        final StringBuilder content = new StringBuilder();
        content.append("# ").append(title).append('\n');
        for (final PredicateSpec p : predicates) {
            content.append("- `").append(p.wpath()).append("`: ").append(p.description()).append('\n');
        }
        content.append('\n');

        content.append("## EXISTS-chain\n\n```sql\n").append(existsSql).append("\n```\n\n")
                .append(LATENCY_TABLE_HEADER).append('\n').append(existsStats.toMarkdownRow("EXISTS-chain"))
                .append('\n').append("\nIndex-backed (no seq scan on search_flat): ")
                .append(!hasSeqScanOnSearchFlat(existsExplain)).append('\n')
                .append("\n```\n").append(existsExplain).append("```\n\n");

        content.append("## Semi-join (thing_id IN + GROUP BY/HAVING count FILTER)\n\n```sql\n").append(semiJoinSql)
                .append("\n```\n\n").append(LATENCY_TABLE_HEADER).append('\n')
                .append(semiJoinStats.toMarkdownRow("semi-join")).append('\n')
                .append("\nIndex-backed (no seq scan on search_flat): ")
                .append(!hasSeqScanOnSearchFlat(semiJoinExplain)).append('\n')
                .append("\n```\n").append(semiJoinExplain).append("```\n\n");

        content.append("## Head-to-head (p50 / p95 / max, ms)\n\n");
        content.append("| strategy | p50 | p95 | max |\n|---|---:|---:|---:|\n");
        content.append("| EXISTS-chain | ").append(existsStats.p50Ms()).append(" | ").append(existsStats.p95Ms())
                .append(" | ").append(existsStats.maxMs()).append(" |\n");
        content.append("| semi-join | ").append(semiJoinStats.p50Ms()).append(" | ").append(semiJoinStats.p95Ms())
                .append(" | ").append(semiJoinStats.maxMs()).append(" |\n");

        writeResultFile(RESULTS_DIR.resolve("comparison-" + fileSuffix + ".md"), content.toString());
    }

    private static String existsChainSql(final List<PredicateSpec> predicates) {
        final StringBuilder sql = new StringBuilder("SELECT st.thing_id FROM search_things st\n")
                .append("WHERE st.global_read && ?\n");
        for (final PredicateSpec p : predicates) {
            sql.append("  AND EXISTS (SELECT 1 FROM search_flat f WHERE f.thing_id = st.thing_id AND f.wpath = ?")
                    .append(" AND ").append(p.predSql()).append(")\n");
        }
        sql.append("  AND (").append(AuthRecheck.SQL).append(")");
        return sql.toString();
    }

    private static String semiJoinSql(final List<PredicateSpec> predicates) {
        final StringBuilder inner = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                inner.append(" OR ");
            }
            inner.append("(f.wpath = ? AND ").append(predicates.get(i).predSql()).append(")");
        }
        final StringBuilder having = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                having.append("\n       AND ");
            }
            having.append("count(*) FILTER (WHERE f.wpath = ? AND ").append(predicates.get(i).predSql())
                    .append(") > 0");
        }
        return "SELECT st.thing_id FROM search_things st\n"
                + "WHERE st.global_read && ?\n"
                + "  AND st.thing_id IN (\n"
                + "    SELECT thing_id FROM search_flat f\n"
                + "    WHERE " + inner + "\n"
                + "    GROUP BY thing_id\n"
                + "    HAVING " + having + "\n"
                + "  )\n"
                + "  AND (" + AuthRecheck.SQL + ")";
    }

    private static List<Object> existsChainParams(final List<PredicateSpec> predicates, final int iteration) {
        final String[] subjects = subjectsFor(iteration);
        final List<Object> params = new ArrayList<>();
        params.add(new TextArray(subjects));
        for (final PredicateSpec p : predicates) {
            params.add(p.wpath());
            params.addAll(p.paramsForIteration().apply(iteration));
        }
        params.addAll(AuthRecheck.params(subjects));
        return params;
    }

    private static List<Object> semiJoinParams(final List<PredicateSpec> predicates, final int iteration) {
        final String[] subjects = subjectsFor(iteration);
        final List<Object> params = new ArrayList<>();
        params.add(new TextArray(subjects));
        // WHERE OR list.
        for (final PredicateSpec p : predicates) {
            params.add(p.wpath());
            params.addAll(p.paramsForIteration().apply(iteration));
        }
        // HAVING FILTER list — same conditions repeated (plain `?` has no named-param reuse).
        for (final PredicateSpec p : predicates) {
            params.add(p.wpath());
            params.addAll(p.paramsForIteration().apply(iteration));
        }
        params.addAll(AuthRecheck.params(subjects));
        return params;
    }

    private static BigDecimal bd(final double v) {
        return BigDecimal.valueOf(v);
    }

    /**
     * <b>Honesty note (review round 1):</b> cycles the 200-subject pool uniformly — the corpus generator has no
     * common/rare subject-pair gradient, so every cycled pair has statistically identical selectivity
     * characteristics (see {@code ReadPathBench#subjectsFor}'s Javadoc for the same note).
     */
    private static String[] subjectsFor(final int iteration) {
        final int a = (iteration * 7) % 200;
        final int b = (iteration * 7 + 41) % 200;
        return new String[] {String.format(Locale.ROOT, "user:sub-%03d", a),
                String.format(Locale.ROOT, "user:sub-%03d", b)};
    }

}
