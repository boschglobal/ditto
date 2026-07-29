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
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.appendResultFile;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.bind;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.drain;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.estimateVsActualRows;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.explainAnalyzeBuffers;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.hasSeqScanOnSearchFlat;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.listOf;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.measure;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.mentionsIndexByName;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.topLevelBufferHitAndRead;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.topPlanNode;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.usesIndexOnlyScan;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.usesTrgmBitmapIndex;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.writeResultFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Task 0.2b Phase-0 read-path <b>mitigation</b> experiments (E0-E5): measures how far the search-on-PostgreSQL
 * plan's OWN reserved levers (per-column statistics targets, realistic memory settings, covering indexes, a
 * wpath-scoped trigram index, and — conditionally — a plan-shape rescue) recover the shapes Task 0.2 red-flagged
 * (shape 1 eq-AND+auth, shape 3 ilike on a high-cardinality path, shape 5 count+auth), at the same 1M-thing /
 * 68.5M-row corpus and the same measurement protocol (3 warmup + 20 timed executions, seeded varying binds, one
 * representative {@code EXPLAIN (ANALYZE, BUFFERS)} per shape). Shape 2 (gt range) is carried through every
 * experiment unchanged as a control — it already passes the 200ms gate at baseline, so a regression there would
 * itself be a finding.
 * <p>
 * Deliberately excluded from every automatic build phase (see {@link CorpusLoadBench}'s Javadoc for why the
 * naming convention matters) — run explicitly, one experiment at a time and IN ORDER (each later experiment
 * assumes the schema/settings changes made by the earlier ones are still in place):
 * <pre>
 * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e0Baseline -DfailIfNoTests=false
 * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e1StatisticsTarget -DfailIfNoTests=false
 * # -- E2 (realistic memory) is a container-level change, done outside the JVM via docker; see bench/README.md --
 * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e2MemoryRemeasure -DfailIfNoTests=false
 * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e3CoveringIndexes -DfailIfNoTests=false
 * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e4ScopedTrigram -DfailIfNoTests=false
 * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e5MaterializedCandidatesRescue -DfailIfNoTests=false  # conditional, see its Javadoc
 * </pre>
 * Each experiment's evidence (SQL, latency table, index-backed/plan-type/trgm/index-only-scan flags, estimate-
 * vs-actual row counts where applicable, PG shared-buffer hit ratio, and the full representative EXPLAIN) is
 * written to its own committed file under {@code src/test/resources/bench/results/mitigations/}. The
 * cross-phase cumulative table and residual-risk narrative live in the Task 0.2b report
 * ({@code .superpowers/sdd/briefs/task-0.2b-report.md}), hand-reconciled from these per-phase files rather than
 * re-derived by code across separate JVM invocations (this bench, like {@code ReadPathBench}, is re-run as a
 * fresh {@code mvn test} process per experiment, so there is no in-memory state to carry a running table
 * across phases without duplicating the "reused historical-constant" pattern for every single number).
 */
public class MitigationBench {

    private static final int WARMUP = 3;
    private static final int TIMED = 20;

    private static final Path RESULTS_DIR = Path.of("src/test/resources/bench/results/mitigations");

    private static final String SF_TRGM_SCOPED_FEATURE_INDEX = "sf_trgm_scoped_feature";
    private static final String SF_NUM_COVERING_INDEX = "sf_num_covering";
    private static final String SF_TEXT_COVERING_INDEX = "sf_text_covering";

    private Connection connection;

    /**
     * <b>Protocol deviation from Task 0.2 (documented, deliberate):</b> {@code prepareThreshold=0} disables
     * pgjdbc's promotion of repeated SQL text to server-side NAMED prepared statements, which in turn prevents
     * PostgreSQL's plancache from ever switching to a GENERIC plan mid-measurement. Task 0.2's "fresh
     * PreparedStatement per execution" methodology did NOT actually prevent that promotion (pgjdbc's query cache
     * is keyed by SQL text at the connection level, not by PreparedStatement object), and E1's statistics bump
     * made the generic plan for the parameterized-wpath ilike shape look so cheap (est. rows=1, cost ~166) that
     * the plancache flipped to it at execution #10 — a plan that walks all ~1.4M {@code sf_text} entries at the
     * bound wpath per execution (~55s each; see
     * {@code results/mitigations/e1-generic-plan-flip-evidence.md} for the proof chain incl. an auto_explain
     * capture). With {@code prepareThreshold=0} every execution is custom-planned with the actual bind values,
     * which is what these experiments are meant to evaluate. The production implication (r2dbc-postgresql also
     * reuses named prepared statements) is recorded in that evidence file as a Task 0.3 design input.
     */
    @Before
    public void connect() throws SQLException {
        final java.util.Properties props = new java.util.Properties();
        props.setProperty("user", BenchConfig.user());
        props.setProperty("password", BenchConfig.password());
        props.setProperty("prepareThreshold", "0");
        connection = DriverManager.getConnection(BenchConfig.jdbcUrl(), props);
        connection.setAutoCommit(true);
    }

    @After
    public void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    // --- shared per-shape measurement (reuses ReadPathBench's SQL/param builders verbatim) -------------------

    private record ShapeEvidence(String label, String sql, LatencyStats stats, String explain, boolean indexBacked,
            String planType, String extraNotes) {
    }

    private ShapeEvidence measureShape1() throws SQLException {
        final String sql = ReadPathBench.shape1Sql();
        final LatencyStats stats = measure(connection, sql, WARMUP, TIMED, ReadPathBench::shape1Params);
        final String explain = explainAnalyzeBuffers(connection, sql, ReadPathBench.shape1Params(0));
        return toEvidence("shape 1 (eq two-predicate AND, auth+gr)", sql, stats, explain,
                estimateNotesFor(explain, "sf_num", "sf_text"));
    }

    private ShapeEvidence measureShape2() throws SQLException {
        final String sql = ReadPathBench.shape2Sql();
        final LatencyStats stats = measure(connection, sql, WARMUP, TIMED, ReadPathBench::shape2Params);
        final String explain = explainAnalyzeBuffers(connection, sql, ReadPathBench.shape2Params(0));
        return toEvidence("shape 2 (gt range, CONTROL — must not regress)", sql, stats, explain,
                estimateNotesFor(explain, "sf_num"));
    }

    private ShapeEvidence measureShape3High() throws SQLException {
        final String sql = ReadPathBench.ilikeSql();
        final LatencyStats stats = measure(connection, sql, WARMUP, TIMED,
                i -> ReadPathBench.shape3Params(i, ReadPathBench.COMMON_FEATURE_TEXT_PATH));
        final String explain =
                explainAnalyzeBuffers(connection, sql, ReadPathBench.shape3Params(0, ReadPathBench.COMMON_FEATURE_TEXT_PATH));
        final boolean trgmGeneral = usesTrgmBitmapIndex(explain);
        final boolean trgmScoped = mentionsIndexByName(explain, SF_TRGM_SCOPED_FEATURE_INDEX);
        final String note = "Uses table-wide `sf_trgm` bitmap index scan: " + trgmGeneral
                + "\nUses wpath-scoped `" + SF_TRGM_SCOPED_FEATURE_INDEX + "`: " + trgmScoped;
        return toEvidence("shape 3-high (ilike, high-cardinality feature path — RED FLAGGED at baseline)", sql,
                stats, explain, note);
    }

    private ShapeEvidence measureShape5() throws SQLException {
        final String sql = ReadPathBench.shape5Sql();
        final LatencyStats stats = measure(connection, sql, WARMUP, TIMED, ReadPathBench::shape1Params);
        final String explain = explainAnalyzeBuffers(connection, sql, ReadPathBench.shape1Params(0));
        return toEvidence("shape 5 (count + auth — RED FLAGGED at baseline)", sql, stats, explain,
                estimateNotesFor(explain, "sf_num", "sf_text"));
    }

    private ShapeEvidence toEvidence(final String label, final String sql, final LatencyStats stats,
            final String explain, final String extraNotesPrefix) {
        final boolean indexBacked = !hasSeqScanOnSearchFlat(explain);
        final String bufferNote = bufferHitRatioNote(explain);
        final String notes = (extraNotesPrefix.isEmpty() ? "" : extraNotesPrefix + "\n") + bufferNote;
        return new ShapeEvidence(label, sql, stats, explain, indexBacked, topPlanNode(explain), notes);
    }

    /**
     * Builds the "estimated vs actual rows" note for each named index/node marker present in {@code explain},
     * quantifying the pooled-statistics misestimate the Task 0.2 report attributed the red flags to. Absent
     * markers (e.g. a marker for an index the planner didn't use this run) are silently skipped, not treated as
     * an error — which index gets chosen is itself one of the things these experiments measure.
     */
    private static String estimateNotesFor(final String explain, final String... nodeMarkers) {
        final StringBuilder sb = new StringBuilder();
        for (final String marker : nodeMarkers) {
            final long[] est = estimateVsActualRows(explain, marker);
            if (est != null) {
                final double ratio = est[0] == 0 ? Double.POSITIVE_INFINITY : (double) est[1] / est[0];
                sb.append(String.format(Locale.ROOT, "`%s` plan line: estimated %d rows vs actual %d rows (%.1fx).%n",
                        marker, est[0], est[1], ratio));
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String bufferHitRatioNote(final String explain) {
        final long[] hitAndRead = topLevelBufferHitAndRead(explain);
        if (hitAndRead == null) {
            return "Top-level Buffers line: none reported.";
        }
        final long hit = hitAndRead[0];
        final long read = hitAndRead[1];
        final long total = hit + read;
        final String ratio = total == 0 ? "n/a (no buffers touched)"
                : String.format(Locale.ROOT, "%.1f%%", 100.0 * hit / total);
        return String.format(Locale.ROOT, "Top-level PG shared-buffer hit ratio: %s (hit=%d read=%d).", ratio, hit,
                read);
    }

    private void writePhaseFile(final String fileBaseName, final String title, final String phaseNote,
            final String ddlLog, final List<ShapeEvidence> shapes) throws IOException {
        final StringBuilder content = new StringBuilder();
        content.append("# ").append(title).append("\n\n");
        content.append(phaseNote).append("\n\n");
        if (!ddlLog.isEmpty()) {
            content.append("## DDL / settings applied this phase\n\n```sql\n").append(ddlLog).append("```\n\n");
        }
        for (final ShapeEvidence s : shapes) {
            content.append("## ").append(s.label()).append("\n\n");
            content.append("```sql\n").append(s.sql()).append("\n```\n\n");
            content.append(LATENCY_TABLE_HEADER).append('\n');
            content.append(s.stats().toMarkdownRow(fileBaseName)).append('\n');
            content.append("\nIndex-backed (no seq scan on search_flat): ").append(s.indexBacked()).append('\n');
            content.append("Top plan node: ").append(s.planType()).append('\n');
            content.append("Warm p95 > 200ms (red flag): ").append(s.stats().p95Ms() > 200).append('\n');
            if (!s.extraNotes().isEmpty()) {
                content.append('\n').append(s.extraNotes()).append('\n');
            }
            content.append("\n```\n").append(s.explain()).append("```\n\n");
        }
        writeResultFile(RESULTS_DIR.resolve(fileBaseName + ".md"), content.toString());
    }

    // --- E0: baseline re-measure (unchanged schema/settings) --------------------------------------------------

    @Test
    public void e0Baseline() throws Exception {
        final List<ShapeEvidence> shapes = List.of(measureShape1(), measureShape2(), measureShape3High(), measureShape5());
        writePhaseFile("e0-baseline", "E0 - baseline re-measure (unchanged schema/settings)",
                "Re-run of shapes 1/2/3-high/5 with NO schema or configuration changes, to anchor today's numbers "
                        + "before any Task 0.2b mitigation lever is applied. Same protocol and seeded binds as "
                        + "Task 0.2 (3 warmup + 20 timed executions per shape, one representative "
                        + "`EXPLAIN (ANALYZE, BUFFERS)`).",
                "", shapes);
    }

    // --- E1: per-column statistics on wpath (escalating to val_text/val_num if needed) -----------------------

    /**
     * Applies the plan's reserved "bump {@code default_statistics_target} on {@code wpath}" lever (plan doc
     * §5 risk row "Planner regressions at scale"), then re-measures. If shape 1/5's {@code sf_num}/{@code
     * sf_text} plan-line estimate-vs-actual ratio is still grossly wrong (outside 0.2x-5x) after the wpath-only
     * bump, escalates per the brief's explicit fallback ("also try val_text/val_num at 1000 if wpath alone
     * doesn't move estimates") and re-measures again — the DDL log and phase note record exactly which bumps
     * were applied and why.
     */
    @Test
    public void e1StatisticsTarget() throws Exception {
        final StringBuilder ddlLog = new StringBuilder();
        executeDdl("ALTER TABLE search_flat ALTER COLUMN wpath SET STATISTICS 10000", ddlLog);
        executeDdl("ANALYZE search_flat", ddlLog);

        List<ShapeEvidence> shapes = List.of(measureShape1(), measureShape2(), measureShape3High(), measureShape5());
        final boolean escalated = isGrosslyMisestimated(shapes);
        if (escalated) {
            executeDdl("ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS 1000", ddlLog);
            executeDdl("ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS 1000", ddlLog);
            executeDdl("ANALYZE search_flat", ddlLog);
            shapes = List.of(measureShape1(), measureShape2(), measureShape3High(), measureShape5());
        }

        writePhaseFile("e1-statistics-target", "E1 - per-column statistics on wpath"
                        + (escalated ? " + val_text/val_num escalation" : ""),
                "Lever: bump wpath's per-column statistics target from the default 100 to 10000 (plan doc §5 "
                        + "\"Planner regressions at scale\" risk row) so the planner's row estimate for a "
                        + "per-wpath predicate stops being pooled across all wpaths sharing val_num/val_text's "
                        + "table-wide histograms (Task 0.2's root cause #1, 38-80x misestimates). ANALYZE re-run "
                        + "after the ALTER so the new target takes effect immediately (not on the next "
                        + "autovacuum). Escalated to also bump val_text/val_num to statistics target 1000: "
                        + escalated + " (escalation trigger: shape 1/5's sf_num/sf_text plan-line estimate-vs-"
                        + "actual ratio still outside 0.2x-5x after the wpath-only bump).",
                ddlLog.toString(), shapes);
    }

    /**
     * Executes one DDL statement on its own short-lived {@link Statement} (autoCommit stays {@code true} so this
     * is never nested inside another statement's implicit transaction — required for {@code CREATE INDEX
     * CONCURRENTLY} in E3/E4), timing it and appending an executed-SQL line (with elapsed ms) to {@code ddlLog}.
     */
    private void executeDdl(final String ddl, final StringBuilder ddlLog) throws SQLException {
        try (Statement st = connection.createStatement()) {
            final long t0 = System.nanoTime();
            st.execute(ddl);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            ddlLog.append(ddl).append(";  -- ").append(ms).append("ms\n");
        }
    }

    private static boolean isGrosslyMisestimated(final List<ShapeEvidence> shapes) {
        for (final ShapeEvidence s : shapes) {
            if (s.label().startsWith("shape 1") || s.label().startsWith("shape 5")) {
                long[] est = estimateVsActualRows(s.explain(), "sf_num");
                if (est == null) {
                    est = estimateVsActualRows(s.explain(), "sf_text");
                }
                if (est != null && est[0] > 0) {
                    final double ratio = (double) est[1] / est[0];
                    if (ratio > 5.0 || ratio < 0.2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // --- E2: realistic memory (container-level change; this method only re-measures) --------------------------

    /**
     * E2 is a container-level change (restart {@code ditto-search-bench-pg} with laptop-class
     * {@code shared_buffers}/{@code effective_cache_size}/{@code work_mem}), which cannot be done from inside
     * this JVM — see {@code bench/README.md}'s "Realistic memory (E2)" section for the exact
     * {@code docker stop}/{@code rm}/{@code run} sequence used (reusing the existing named volume, so the E1
     * statistics settings persist in the reused data directory). Run this method only AFTER that container
     * recreation, purely to re-measure with the new settings in effect.
     */
    @Test
    public void e2MemoryRemeasure() throws Exception {
        final List<ShapeEvidence> shapes = List.of(measureShape1(), measureShape2(), measureShape3High(), measureShape5());
        writePhaseFile("e2-memory-remeasure", "E2 - realistic memory (container restarted with laptop-class settings)",
                "Lever: `ditto-search-bench-pg` recreated (docker stop + rm, WITHOUT `-v` so the existing named "
                        + "data volume is preserved, then docker run reusing that same volume) with "
                        + "`-c shared_buffers=4GB -c effective_cache_size=12GB -c work_mem=64MB` in place of the "
                        + "postgres:16 image defaults (128MB/4GB/4MB) — see bench/README.md for the exact command "
                        + "and this task's report for confirmation that E1's per-column statistics targets "
                        + "survived the restart (they are stored in pg_attribute/pg_statistic inside the reused "
                        + "data directory, not runtime GUCs). No schema DDL in this method; measurement only.",
                "", shapes);
    }

    // --- E3: covering indexes (INCLUDE (thing_id) on sf_num/sf_text) --------------------------------------------

    @Test
    public void e3CoveringIndexes() throws Exception {
        final StringBuilder ddlLog = new StringBuilder();
        executeDdl("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + SF_NUM_COVERING_INDEX
                + " ON search_flat (wpath, val_num) INCLUDE (thing_id) WHERE val_num IS NOT NULL", ddlLog);
        executeDdl("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + SF_TEXT_COVERING_INDEX
                + " ON search_flat (wpath, val_text) INCLUDE (thing_id) WHERE val_text IS NOT NULL", ddlLog);
        ddlLog.append("-- sizes: ").append(relationSize(SF_NUM_COVERING_INDEX)).append(" / ")
                .append(relationSize(SF_TEXT_COVERING_INDEX)).append('\n');

        final ShapeEvidence shape1 = measureShape1();
        final ShapeEvidence shape5 = measureShape5();
        final String indexOnlyNote1 = "Index-only scan on `" + SF_NUM_COVERING_INDEX + "`: "
                + usesIndexOnlyScan(shape1.explain(), SF_NUM_COVERING_INDEX) + "; on `" + SF_TEXT_COVERING_INDEX
                + "`: " + usesIndexOnlyScan(shape1.explain(), SF_TEXT_COVERING_INDEX);

        final List<ShapeEvidence> shapes = List.of(
                appendNote(shape1, indexOnlyNote1),
                measureShape2(),
                measureShape3High(),
                appendNote(shape5, "Index-only scan on `" + SF_NUM_COVERING_INDEX + "`: "
                        + usesIndexOnlyScan(shape5.explain(), SF_NUM_COVERING_INDEX) + "; on `"
                        + SF_TEXT_COVERING_INDEX + "`: " + usesIndexOnlyScan(shape5.explain(), SF_TEXT_COVERING_INDEX)));

        writePhaseFile("e3-covering-indexes", "E3 - covering indexes (INCLUDE (thing_id) on sf_num/sf_text)",
                "Lever: `CREATE INDEX CONCURRENTLY` variants of sf_num/sf_text with `INCLUDE (thing_id)` (plan doc "
                        + "§3.2 \"Phase-0 slot\" comment), so the flat-side EXISTS probe can in principle resolve "
                        + "wpath+value->thing_id without a search_flat heap fetch. This does NOT touch the "
                        + "per-candidate recheck cost that Task 0.2's root-cause #2 attributes to "
                        + "`search_things`/DOC-row probes — quantified honestly below, shape by shape.",
                ddlLog.toString(), shapes);
    }

    private static ShapeEvidence appendNote(final ShapeEvidence s, final String extra) {
        return new ShapeEvidence(s.label(), s.sql(), s.stats(), s.explain(), s.indexBacked(), s.planType(),
                s.extraNotes() + "\n" + extra);
    }

    private String relationSize(final String relationName) throws SQLException {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT pg_size_pretty(pg_relation_size('" + relationName + "'))")) {
            return rs.next() ? relationName + " = " + rs.getString(1) : relationName + " = (not found)";
        }
    }

    // --- E4: wpath-scoped trigram (partial trigram index on the hot high-card path) ----------------------------

    /**
     * Chooses option (b) from the brief — a partial trigram index scoped to the exact high-cardinality path
     * ({@link ReadPathBench#COMMON_FEATURE_TEXT_PATH}) that shape 3's Variant B red-flags — over option (a)
     * (a {@code btree_gin} composite {@code (wpath, val_text gin_trgm_ops)} GIN spanning all 68.5M rows).
     * <p>
     * <b>Justification:</b> this experiment's job is to prove/disprove the mechanism ("does scoping the
     * trigram candidate set by wpath fix the red flag"), not to ship a general solution — a full composite
     * {@code btree_gin} index over every row would cost substantially more build time for the same proof, and
     * whether the real implementation generalizes via per-wpath partial indexes (impractical — wpaths are
     * dynamic) or a single composite GIN (the more likely production answer) is Task 0.3's design call, not
     * this experiment's. The partial index is the cheaper, equally conclusive way to answer "does wpath-scoping
     * the trigram candidate set work at all", which is what E4 measures.
     */
    @Test
    public void e4ScopedTrigram() throws Exception {
        final StringBuilder ddlLog = new StringBuilder();
        executeDdl("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + SF_TRGM_SCOPED_FEATURE_INDEX
                + " ON search_flat USING gin ((val_text COLLATE \"C.utf8\") gin_trgm_ops)"
                + " WHERE wpath = '" + ReadPathBench.COMMON_FEATURE_TEXT_PATH + "' AND val_text IS NOT NULL", ddlLog);
        ddlLog.append("-- size: ").append(relationSize(SF_TRGM_SCOPED_FEATURE_INDEX)).append('\n');

        final List<ShapeEvidence> shapes = List.of(measureShape1(), measureShape2(), measureShape3High(), measureShape5());

        writePhaseFile("e4-scoped-trigram", "E4 - wpath-scoped trigram (partial index, option (b))",
                "Lever: a partial trigram GIN index scoped to the exact high-cardinality path "
                        + "`" + ReadPathBench.COMMON_FEATURE_TEXT_PATH + "` (option (b) from the brief — see "
                        + "this method's Javadoc for why over option (a)'s table-wide btree_gin composite). "
                        + "Measures whether the planner picks the scoped index over the unscoped table-wide "
                        + "`sf_trgm` for this wpath, and whether that closes shape 3-high's red flag.",
                ddlLog.toString(), shapes);
    }

    // --- E5 (conditional): plan-shape rescue via a materialized selective-leg CTE ------------------------------

    /**
     * Only run if E1+E2 left shapes 1/5 over ~500ms warm p95 (per the brief's trigger) — see the Task 0.2b
     * report for whether this method was actually executed. Forces the selective leg (the temperature
     * BETWEEN predicate, ~1.4% selective per Task 0.2's verified selectivity) into a
     * {@code MATERIALIZED} CTE first, then checks the unselective leg (city equality, ~9%/city) via a plain
     * {@code EXISTS}, then joins the candidate set back to {@code search_things} for the {@code global_read}
     * filter and auth recheck — the strongest structural (not tuning-knob) option named in the brief for
     * shape 1/5's red flag.
     */
    /** Shape 1's materialized-CTE rescue form (extracted so the E5 cold-start probe, review round 1, can reuse it verbatim). */
    static String shape1RescueSql() {
        return "WITH cand AS MATERIALIZED (\n"
                + "    SELECT s.thing_id FROM search_flat s WHERE s.wpath = ? AND s.val_num BETWEEN ? AND ?\n"
                + ")\n"
                + "SELECT st.thing_id FROM search_things st\n"
                + "JOIN cand ON cand.thing_id = st.thing_id\n"
                + "WHERE st.global_read && ?\n"
                + "  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id"
                + " AND s.wpath = '" + ReadPathBench.CITY_PATH + "' AND s.val_text = ?)\n"
                + "  AND (" + AuthRecheck.SQL + ")";
    }

    /** Shape 5's materialized-CTE rescue form (count variant of {@link #shape1RescueSql()}). */
    static String shape5RescueSql() {
        return "WITH cand AS MATERIALIZED (\n"
                + "    SELECT s.thing_id FROM search_flat s WHERE s.wpath = ? AND s.val_num BETWEEN ? AND ?\n"
                + ")\n"
                + "SELECT count(*) FROM search_things st\n"
                + "JOIN cand ON cand.thing_id = st.thing_id\n"
                + "WHERE st.global_read && ?\n"
                + "  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id"
                + " AND s.wpath = '" + ReadPathBench.CITY_PATH + "' AND s.val_text = ?)\n"
                + "  AND (" + AuthRecheck.SQL + ")";
    }

    @Test
    public void e5MaterializedCandidatesRescue() throws Exception {
        final String shape1RescueSql = shape1RescueSql();
        final String shape5RescueSql = shape5RescueSql();

        final LatencyStats stats1 = measure(connection, shape1RescueSql, WARMUP, TIMED, MitigationBench::rescueParams);
        final String explain1 = explainAnalyzeBuffers(connection, shape1RescueSql, rescueParams(0));
        final ShapeEvidence evidence1 = toEvidence("shape 1 RESCUE (materialized selective-leg CTE)",
                shape1RescueSql, stats1, explain1, "");

        final LatencyStats stats5 = measure(connection, shape5RescueSql, WARMUP, TIMED, MitigationBench::rescueParams);
        final String explain5 = explainAnalyzeBuffers(connection, shape5RescueSql, rescueParams(0));
        final ShapeEvidence evidence5 = toEvidence("shape 5 RESCUE (materialized selective-leg CTE)",
                shape5RescueSql, stats5, explain5, "");

        writePhaseFile("e5-materialized-cte-rescue", "E5 - plan-shape rescue via materialized selective-leg CTE",
                "Only runs because E1+E2 left shapes 1/5 over ~500ms warm p95 (see the Task 0.2b report's "
                        + "trigger evaluation). Forces the selective temperature-BETWEEN leg into a "
                        + "`MATERIALIZED` CTE first, checks the unselective city-equality leg via `EXISTS`, then "
                        + "joins back to `search_things` for `global_read`+auth. This is a translator-level "
                        + "strategy (query SHAPE), not a tuning knob — see the report for whether it is required.",
                "", List.of(evidence1, evidence5));
    }

    private static List<Object> rescueParams(final int iteration) {
        // Re-derive the same bind values as ReadPathBench#shape1Params, but in this query's placeholder order:
        // (TEMP_PATH implicit in literal, temp-lo, temp-hi, subjects, city) then the 12 auth-recheck params.
        final double[] tempBucketLo = {-15, -10, -5, 0, 5, 10, 15, 20, 25, 30};
        final String[] cities = {
                "Stuttgart", "Berlin", "Munich", "Hamburg", "Cologne", "Frankfurt", "Leipzig", "Dresden", "Bonn",
                "Essen",
        };
        final double lo = tempBucketLo[iteration % tempBucketLo.length];
        final String city = cities[iteration % cities.length];
        final int a = (iteration * 7) % 200;
        final int b = (iteration * 7 + 41) % 200;
        final String[] subjects = {
                String.format(Locale.ROOT, "user:sub-%03d", a), String.format(Locale.ROOT, "user:sub-%03d", b),
        };
        final List<Object> params =
                listOf(ReadPathBench.TEMP_PATH, BigDecimal.valueOf(lo), BigDecimal.valueOf(lo + 0.99),
                        new TextArray(subjects), city);
        params.addAll(AuthRecheck.params(subjects));
        return params;
    }

    // =============================================================================================================
    // Review round 1 fixes (2026-07-04) — three findings against the committed E1/E4/E5 evidence. Each method
    // below is additive: it does not replace the original committed experiment, it corrects/extends it in place,
    // per the review's required fix. See the Task 0.2b report's "Fix report (review round 1)" section for the
    // measured outcomes.
    // =============================================================================================================

    // --- Finding 3: E1's wpath-ALONE estimate (the brief asked for this lever measured in isolation FIRST,
    // before escalating to val_text/val_num — the committed E1 file only shows the escalated/combined state) ----

    /**
     * Review round 1, Finding 3. Isolates the wpath-only statistics lever: resets {@code val_text}/{@code
     * val_num} to the default target ({@code -1}, i.e. 100 — pooled across all wpaths) while leaving {@code
     * wpath} at the already-applied 10000, ANALYZEs, and captures shape 1's per-index-scan estimate-vs-actual.
     * Then RESTORES {@code val_text}/{@code val_num} to 1000 and re-ANALYZEs — the documented db-end-state.md
     * targets Task 0.3 depends on — and snapshots {@code pg_attribute.attstattarget} both before and after so
     * the restore is independently verifiable from the committed file, not just asserted.
     */
    @Test
    public void e1WpathAloneEstimates() throws Exception {
        final StringBuilder ddlLog = new StringBuilder();
        executeDdl("ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS -1", ddlLog);
        executeDdl("ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS -1", ddlLog);
        executeDdl("ANALYZE search_flat", ddlLog);

        final String wpathAloneAttrs = statisticsTargetsSnapshot();

        final String sql = ReadPathBench.shape1Sql();
        final LatencyStats stats = measure(connection, sql, WARMUP, TIMED, ReadPathBench::shape1Params);
        final String explain = explainAnalyzeBuffers(connection, sql, ReadPathBench.shape1Params(0));
        final ShapeEvidence shape1 = toEvidence("shape 1 (eq two-predicate AND, auth+gr) — wpath-ALONE statistics state",
                sql, stats, explain, estimateNotesFor(explain, "sf_num", "sf_text"));

        // Restore the documented end state (db-end-state.md): wpath=10000 (untouched by this method),
        // val_text=1000, val_num=1000.
        executeDdl("ALTER TABLE search_flat ALTER COLUMN val_text SET STATISTICS 1000", ddlLog);
        executeDdl("ALTER TABLE search_flat ALTER COLUMN val_num SET STATISTICS 1000", ddlLog);
        executeDdl("ANALYZE search_flat", ddlLog);
        final String restoredAttrs = statisticsTargetsSnapshot();

        final StringBuilder content = new StringBuilder();
        content.append("# E1 review-fix (round 1, Finding 3) - wpath-ALONE statistics estimate\n\n");
        content.append("The brief asked for the wpath-only statistics-target lever to be measured in isolation "
                + "FIRST, before escalating to val_text/val_num — the committed `e1-statistics-target.md` only "
                + "shows the escalated/combined state (wpath=10000 + val_text=1000 + val_num=1000), so the "
                + "wpath-alone number was never actually committed. This file isolates it: val_text/val_num are "
                + "reset to the default statistics target (`-1`, which resolves to the system default of 100, "
                + "pooled table-wide across every wpath) while wpath stays at the already-applied 10000, then "
                + "shape 1 is re-measured. Afterwards val_text/val_num are RESTORED to 1000 and re-ANALYZEd so "
                + "the bench DB ends this method in exactly the state `db-end-state.md` documents — the restore "
                + "is verified below via a `pg_attribute.attstattarget` snapshot taken both in the wpath-alone "
                + "state and after the restore.\n\n");
        content.append("## DDL / settings applied this phase\n\n```sql\n").append(ddlLog).append("```\n\n");
        content.append("## pg_attribute.attstattarget — wpath-ALONE state (val_text/val_num reset to default)\n\n");
        content.append("```\n").append(wpathAloneAttrs).append("```\n\n");
        content.append("## ").append(shape1.label()).append("\n\n");
        content.append("```sql\n").append(shape1.sql()).append("\n```\n\n");
        content.append(LATENCY_TABLE_HEADER).append('\n');
        content.append(shape1.stats().toMarkdownRow("e1-wpath-alone")).append('\n');
        content.append("\nIndex-backed (no seq scan on search_flat): ").append(shape1.indexBacked()).append('\n');
        content.append("Top plan node: ").append(shape1.planType()).append('\n');
        content.append("Warm p95 > 200ms (red flag): ").append(shape1.stats().p95Ms() > 200).append('\n');
        if (!shape1.extraNotes().isEmpty()) {
            content.append('\n').append(shape1.extraNotes()).append('\n');
        }
        content.append("\n```\n").append(shape1.explain()).append("```\n\n");
        content.append("## Restore verification — pg_attribute.attstattarget after restore "
                + "(must equal db-end-state.md: wpath=10000, val_text=1000, val_num=1000)\n\n");
        content.append("```\n").append(restoredAttrs).append("```\n");

        writeResultFile(RESULTS_DIR.resolve("e1-wpath-alone-estimates.md"), content.toString());
    }

    private String statisticsTargetsSnapshot() throws SQLException {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT attname, attstattarget FROM pg_attribute WHERE attrelid = 'search_flat'::regclass"
                                + " AND attname IN ('wpath','val_text','val_num') ORDER BY attname")) {
            final StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append(" = ").append(rs.getInt(2)).append('\n');
            }
            return sb.toString();
        }
    }

    // --- Finding 2: E4's committed DDL log timed a no-op (index pre-existed from a discarded run) -----------------

    /**
     * Review round 1, Finding 2. The committed {@code e4-scoped-trigram.md} DDL log shows
     * {@code CREATE INDEX CONCURRENTLY IF NOT EXISTS sf_trgm_scoped_feature ... -- 3ms}, which is a no-op: the
     * index already existed (from a discarded earlier run) when that statement executed, so {@code IF NOT
     * EXISTS} skipped the actual build and the "3ms" timed nothing. This method drops the index and rebuilds it
     * cleanly with {@code CONCURRENTLY} (no {@code IF NOT EXISTS}, so a real build happens), capturing the real
     * elapsed time and size, then re-measures shape 3-high to confirm the ~47x improvement holds against the
     * genuinely-freshly-built index. Leaves the index in place afterwards (Task 0.3's documented end state
     * requires it present).
     */
    @Test
    public void e4ScopedTrigramCleanRebuild() throws Exception {
        final StringBuilder ddlLog = new StringBuilder();
        executeDdl("DROP INDEX CONCURRENTLY IF EXISTS " + SF_TRGM_SCOPED_FEATURE_INDEX, ddlLog);
        executeDdl("CREATE INDEX CONCURRENTLY " + SF_TRGM_SCOPED_FEATURE_INDEX
                + " ON search_flat USING gin ((val_text COLLATE \"C.utf8\") gin_trgm_ops)"
                + " WHERE wpath = '" + ReadPathBench.COMMON_FEATURE_TEXT_PATH + "' AND val_text IS NOT NULL", ddlLog);
        ddlLog.append("-- size: ").append(relationSize(SF_TRGM_SCOPED_FEATURE_INDEX)).append('\n');

        final ShapeEvidence shape3 = measureShape3High();

        final StringBuilder content = new StringBuilder();
        content.append("\n---\n\n");
        content.append("## Review-fix (round 1, Finding 2): clean CONCURRENTLY rebuild with real timing\n\n");
        content.append("The DDL line above the `---` separator (`-- 3ms`) was a no-op — `sf_trgm_scoped_feature` "
                + "already existed from a discarded earlier run before the committed run captured that line, so "
                + "`CREATE INDEX CONCURRENTLY IF NOT EXISTS` skipped the actual build. Below is a clean "
                + "`DROP INDEX CONCURRENTLY` + `CREATE INDEX CONCURRENTLY` (no `IF NOT EXISTS`) pair with the "
                + "real build time and size, plus a re-measurement of shape 3-high against the freshly-built "
                + "index (20 timed iterations, same protocol as the rest of E4).\n\n");
        content.append("```sql\n").append(ddlLog).append("```\n\n");
        content.append("### shape 3-high (ilike, high-cardinality feature path) — re-measured against the clean rebuild\n\n");
        content.append(LATENCY_TABLE_HEADER).append('\n');
        content.append(shape3.stats().toMarkdownRow("e4-scoped-trigram-rebuild")).append('\n');
        content.append("\nIndex-backed (no seq scan on search_flat): ").append(shape3.indexBacked()).append('\n');
        content.append("Top plan node: ").append(shape3.planType()).append('\n');
        content.append("Warm p95 > 200ms (red flag): ").append(shape3.stats().p95Ms() > 200).append('\n');
        if (!shape3.extraNotes().isEmpty()) {
            content.append('\n').append(shape3.extraNotes()).append('\n');
        }
        content.append("\n```\n").append(shape3.explain()).append("```\n");

        appendResultFile(RESULTS_DIR.resolve("e4-scoped-trigram.md"), content.toString());
    }

    /**
     * Review round 1, Finding 2 (addendum). {@link #e4ScopedTrigramCleanRebuild()}'s own 20-timed-iteration
     * measurement, taken immediately against the freshly-{@code CREATE INDEX CONCURRENTLY}-built index, showed
     * a p95 back over the 200ms bar (min/p50 far under it, one or two high outliers). Root cause: shape 3-high's
     * bind values cycle through 8 distinct ILIKE substrings ({@code ReadPathBench.ILIKE_SUBSTRINGS}), but the
     * shared 3-iteration warmup only touches 3 of them before a brand-new GIN index has any of its trigram
     * lexeme pages cache-resident — the timed loop's first encounter of each of the other 5 substrings pays a
     * page-cache-miss tax against the fresh index, which a 3-iteration warmup was never designed to cover (Task
     * 0.2's original measurement against the long-lived {@code sf_trgm} index never hit this because that index
     * had been resident for the whole bench session). This method re-measures the SAME already-built index
     * (no DDL — proves the previous run's own timed loop already fully warmed all 8 substrings) to show the
     * true steady-state p95 once that one-time fresh-index tax is paid.
     */
    @Test
    public void e4ScopedTrigramCleanRebuildSecondPass() throws Exception {
        final ShapeEvidence shape3 = measureShape3High();

        final StringBuilder content = new StringBuilder();
        content.append("\n### shape 3-high — second measurement pass (same rebuilt index, NO further DDL)\n\n");
        content.append("Immediately re-measures the index built by the DROP+CREATE above, with no changes — "
                + "isolates whether the previous pass's p95 tail was a one-time fresh-index page-cache-miss cost "
                + "(paid once per not-yet-touched ILIKE substring) rather than a steady-state regression.\n\n");
        content.append(LATENCY_TABLE_HEADER).append('\n');
        content.append(shape3.stats().toMarkdownRow("e4-scoped-trigram-rebuild-2nd-pass")).append('\n');
        content.append("\nIndex-backed (no seq scan on search_flat): ").append(shape3.indexBacked()).append('\n');
        content.append("Top plan node: ").append(shape3.planType()).append('\n');
        content.append("Warm p95 > 200ms (red flag): ").append(shape3.stats().p95Ms() > 200).append('\n');
        if (!shape3.extraNotes().isEmpty()) {
            content.append('\n').append(shape3.extraNotes()).append('\n');
        }
        content.append("\n```\n").append(shape3.explain()).append("```\n");

        appendResultFile(RESULTS_DIR.resolve("e4-scoped-trigram.md"), content.toString());
    }

    // --- Finding 1: E5's "cold-start: 3.6s vs 0.48s (7.5x)" claim had zero committed evidence ----------------------

    /**
     * Review round 1, Finding 1. The report's E5 narrative claimed a cold-start comparison ("EXISTS-form shape 1
     * first execution 3.6s vs CTE-form 0.48s, 7.5x") with no committed artifact backing it. This pair of methods
     * is the fix — run ONE AT A TIME with a {@code docker restart ditto-search-bench-pg} immediately before each
     * (see {@code bench/README.md}), so both forms see a symmetric "PG shared-buffers empty" starting condition:
     * <pre>
     * docker restart ditto-search-bench-pg          # + wait for readiness
     * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e5ColdStartProbeExistsForm -DfailIfNoTests=false
     * docker restart ditto-search-bench-pg          # + wait for readiness
     * mvn -pl internal/utils/search-r2dbc test -Dtest=MitigationBench#e5ColdStartProbeCteForm -DfailIfNoTests=false
     * </pre>
     * <b>Disclosed caveat (per the brief):</b> a container restart clears PostgreSQL's own shared-buffer pool
     * (the postgres process restarts), but it does NOT clear the Docker Desktop Linux VM's page cache for the
     * underlying data-volume files — so this is a "PG-buffer-cold, host-OS-cache-state-unknown" probe, not a
     * guaranteed true-disk-cold probe. The committed file states plainly whether the previously-claimed 7.5x
     * reproduced under this protocol.
     * <p>
     * Each method's first execution is captured via {@code EXPLAIN (ANALYZE, BUFFERS)} itself (so the actual
     * first-touch execution is the one measured, not burned as an unmeasured warmup), followed by 3 plain timed
     * executions to show the warmup slope. Both append to the same committed file
     * ({@code results/mitigations/e5-cold-start-probe.md}).
     */
    @Test
    public void e5ColdStartProbeExistsForm() throws Exception {
        final String intro = "# E5 review-fix (round 1, Finding 1) — committed cold-start probe\n\n"
                + "Fixes the report's uncommitted claim (\"EXISTS-form shape 1 first execution 3.6s vs CTE-form "
                + "0.48s, 7.5x\") by running each form's first execution against a freshly `docker restart`-ed "
                + "`ditto-search-bench-pg` (PG shared-buffers empty). **Caveat:** a container restart does NOT "
                + "clear the Docker Desktop Linux VM's page cache for the data-volume files underneath "
                + "PostgreSQL, so this measures PG-buffer-cold / host-OS-cache-state-unknown, not guaranteed "
                + "disk-cold I/O — both forms are measured under the identical caveat, so the comparison between "
                + "them is still fair even if the absolute numbers undersell true cold-disk cost. Protocol: "
                + "`docker restart ditto-search-bench-pg`, wait for readiness, then run this method (its first "
                + "execution — captured via `EXPLAIN (ANALYZE, BUFFERS)` itself — is the cold measurement; 3 "
                + "more plain executions follow to show the warmup slope), then restart again and run "
                + "`e5ColdStartProbeCteForm`.\n\n";
        coldStartProbe(intro, "EXISTS-form (shape 1, the form used throughout E0-E4)", ReadPathBench.shape1Sql(),
                ReadPathBench::shape1Params);
    }

    @Test
    public void e5ColdStartProbeCteForm() throws Exception {
        coldStartProbe("", "CTE-form (shape 1 RESCUE, materialized selective-leg CTE — E5's structural lever)",
                shape1RescueSql(), MitigationBench::rescueParams);
    }

    private void coldStartProbe(final String maybeIntro, final String label, final String sql,
            final BenchQuerySupport.ParamsForIteration params) throws SQLException, IOException {
        final String firstExplain = explainAnalyzeBuffers(connection, sql, params.paramsFor(0));
        final long[] firstBuffers = topLevelBufferHitAndRead(firstExplain);
        final Double firstExecutionMs = extractExecutionTimeMs(firstExplain);

        final List<Long> subsequentMs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(connection, ps, params.paramsFor(i));
                final long t0 = System.nanoTime();
                drain(ps);
                subsequentMs.add((System.nanoTime() - t0) / 1_000_000L);
            }
        }

        final StringBuilder content = new StringBuilder();
        content.append(maybeIntro);
        content.append("## ").append(label).append('\n');
        content.append("First execution (post-restart, PG shared-buffers empty), from EXPLAIN's own `Execution "
                + "Time` line: ").append(firstExecutionMs != null ? firstExecutionMs + " ms" : "n/a").append('\n');
        if (firstBuffers != null) {
            content.append("First-execution top-level buffers: hit=").append(firstBuffers[0]).append(" read=")
                    .append(firstBuffers[1]).append(" (a non-trivial `read=` count is the PG-buffer-cold signal; "
                            + "it says nothing about the host OS page cache underneath).\n");
        }
        content.append("Subsequent 3 executions (warmup slope, plain timed, ms): ").append(subsequentMs).append("\n\n");
        content.append("```sql\n").append(sql).append("\n```\n\n");
        content.append("Cold (first) execution EXPLAIN (ANALYZE, BUFFERS):\n\n```\n").append(firstExplain).append("```\n\n");

        appendResultFile(RESULTS_DIR.resolve("e5-cold-start-probe.md"), content.toString());
    }

    private static Double extractExecutionTimeMs(final String explainText) {
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(explainText);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

}
