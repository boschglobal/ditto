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

import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.appendResultFile;
import static org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.writeResultFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;

import org.junit.Test;

/**
 * Task 0.3 Part 1/Part 2 — Phase-0 write-path benchmarks: simulates the production write path (per-thing
 * transaction, exactly the design's v1 shape, plan doc §3.4 step 1/2) against the same 1M-thing / 68.5M-row
 * bench corpus and the tuned DB state Task 0.2b's read gate needs (see {@code
 * results/write-bench/part0-db-normalization.md}).
 * <p>
 * <b>Twin shape (documented simplification):</b> rather than round-tripping through {@link CorpusGenerator}'s
 * full mixed-type/nested/feature shape, each churned thing is rewritten to a flat, uniform ~{@value
 * #LEAVES_PER_TWIN}-attribute numeric twin ({@code /attributes/attr0..attr499}, all type_rank=2/NUMBER) —
 * chosen so the flat-row footprint is exact and controllable (~500 rows/thing, no feature dual-row
 * multiplication) and so Part 2's anti-join comparison can construct an exact "5 of 500 leaves changed" delta
 * per update (a small in-memory per-thing {@code long[500]} state array, {@value #HOT_LEAVES_PER_UPDATE} of
 * which are overwritten with a new monotonic version number on each update; the other {@value
 * #LEAVES_PER_TWIN}-{@value #HOT_LEAVES_PER_UPDATE} leaves are left byte-identical to the previous write, so
 * the anti-join's full-row-identity match — path+wpath+ord+type_rank+value — actually excludes them from
 * churn). Rows are still produced by the real {@link Flattener}, not hand-built, so the flattening semantics
 * exercised are the same ones Task 0.1 tested. The FIRST write to any given pool thing_id has zero row-identity
 * overlap with that thing_id's pre-existing (CorpusGenerator-shaped) flat rows, so it is full churn under BOTH
 * v1 and v1.5 — this is disclosed and quantified in the committed results (repeat touches within a run dilute
 * this quickly at the pool sizes/durations used here).
 * <p>
 * Deliberately excluded from every automatic build phase (same non-{@code *Test}/{@code *IT} naming convention
 * as {@link CorpusLoadBench}/{@link ReadPathBench}/{@link MitigationBench}) — run explicitly:
 * <pre>
 * mvn -pl internal/utils/search-r2dbc test -Dtest=WriteFanoutBench#part0DbNormalization -DfailIfNoTests=false
 * mvn -pl internal/utils/search-r2dbc test -Dtest=WriteFanoutBench#part1SustainedUpsertBlanket -DfailIfNoTests=false
 * mvn -pl internal/utils/search-r2dbc test -Dtest=WriteFanoutBench#part2AntiJoinVsBlanketComparison -DfailIfNoTests=false
 * </pre>
 * All durations/rates/pool sizes/worker counts are overridable via {@code -Dbench.write.*} system properties
 * (see {@link BenchConfig}) — used during development to dry-run this class at e.g. 20s before committing to
 * the full ~10 min / 2x5 min runs; the numbers committed under {@code results/write-bench/} are from the
 * brief's full reduced-scale durations, stated per file.
 */
public class WriteFanoutBench {

    private static final Path RESULTS_DIR = Path.of("src/test/resources/bench/results/write-bench");

    private static final int LEAVES_PER_TWIN = 500;
    private static final int HOT_LEAVES_PER_UPDATE = 5;

    /** Distinct {@code setseed()} values so Part 1's pool and Part 2's combined pool don't need to coordinate
     * across separate JVM invocations to stay (very probably) disjoint — 2000-4000 of 1,000,000 things. */
    private static final double SEED_PART1 = -0.101;
    private static final double SEED_PART2 = -0.202;

    private static final String[] TRACKED_RELATIONS = {
            "search_flat", "search_flat_pkey", "sf_num", "sf_text", "sf_bool", "sf_exists", "sf_trgm",
            "sf_trgm_scoped_feature",
    };

    private enum WriteMode { BLANKET, ANTIJOIN }

    // =============================================================================================================
    // Part 0 — DB state normalization (drop the rejected E3 covering indexes FIRST; see the brief)
    // =============================================================================================================

    @Test
    public void part0DbNormalization() throws Exception {
        try (Connection conn = connect()) {
            final StringBuilder ddlLog = new StringBuilder();
            executeDdl(conn, "DROP INDEX IF EXISTS sf_num_covering", ddlLog);
            executeDdl(conn, "DROP INDEX IF EXISTS sf_text_covering", ddlLog);

            final StringBuilder content = new StringBuilder();
            content.append("# Task 0.3 Part 0 - DB state normalization\n\n");
            content.append("Dropped the Task-0.2b E3 covering indexes (`sf_num_covering`, `sf_text_covering`) "
                    + "FIRST, before any write-path measurement: E3 was a committed NEGATIVE result (no read "
                    + "benefit for the auth-heavy shapes 1/5, whose cost lives in the per-candidate "
                    + "`search_things` probes, not the flat-index lookup — see `task-0.2b-report.md`'s E3 "
                    + "section), so pricing their write cost would bill the design for an index the read gate "
                    + "itself rejected.\n\n"
                    + "KEPT (the combination the read gate actually needs, per `task-0.2b-report.md`'s residual "
                    + "risk paragraph): E1 per-column statistics targets (`wpath=10000`, `val_text=1000`, "
                    + "`val_num=1000`), E2 laptop-class memory settings (`shared_buffers=4GB`, "
                    + "`effective_cache_size=12GB`, `work_mem=64MB`), the E4 wpath-scoped partial trigram "
                    + "(`sf_trgm_scoped_feature`), and the original table-wide `sf_trgm`.\n\n");
            content.append("## DDL executed\n\n```sql\n").append(ddlLog).append("```\n\n");
            content.append("## Final index list (post Part-0) — this is the state Part 1/Part 2 price\n\n");
            content.append("```\n").append(listIndexes(conn)).append("```\n\n");
            content.append("## Settings / statistics targets in effect\n\n").append(captureKnobs(conn)).append('\n');
            content.append("## Row counts (corpus intact)\n\n```\n").append(rowCounts(conn)).append("```\n");

            writeResultFile(RESULTS_DIR.resolve("part0-db-normalization.md"), content.toString());
        }
    }

    // =============================================================================================================
    // Part 1 — sustained write fan-out benchmark (v1: unconditional upsert + blanket DELETE/unnest-INSERT)
    // =============================================================================================================

    @Test
    public void part1SustainedUpsertBlanket() throws Exception {
        final String intro = "# Task 0.3 Part 1 - sustained write fan-out benchmark (v1 blanket DELETE+INSERT)\n\n"
                + "Simulates the production write path (plan doc §3.4 step 1/2, exactly the design's v1 shape): "
                + "one transaction per thing = (1) `INSERT ... ON CONFLICT (thing_id) DO UPDATE SET <all "
                + "columns>` unconditional full-write upsert of the doc row, no revision predicate; (2) "
                + "`DELETE FROM search_flat WHERE thing_id = ?` then `INSERT INTO search_flat SELECT * FROM "
                + "unnest(...)` — one bind parameter per COLUMN, not per row.\n\n"
                + "**Reduced-scale caveat (explicit, per the user's Phase-0 scale decision):** this run is 1M "
                + "things / ~10 minutes sustained, NOT the plan's original 10M / >=1 hour bloat-plateau gate. "
                + "At 10 minutes NO bloat-plateau claim is possible — the size-growth/dead-tuple trend below is "
                + "reported honestly as a TREND, and the plan's own >=1h plateau gate is explicitly marked "
                + "'not yet run (scheduled full-scale follow-up)' in the findings doc.\n\n";

        runPhaseAndWrite(WriteMode.BLANKET, BenchConfig.writePart1Duration(), "part1-sustained-upsert", intro,
                SEED_PART1, BenchConfig.writePoolSizeEach());
    }

    // =============================================================================================================
    // Part 2 — v1.5 anti-join churn-reduction comparison (same workload, alternative flat-row maintenance)
    // =============================================================================================================

    @Test
    public void part2AntiJoinVsBlanketComparison() throws Exception {
        final int poolSizeEach = BenchConfig.writePoolSizeEach();
        final Duration phaseDuration = BenchConfig.writePart2PhaseDuration();
        final Path resultFile = RESULTS_DIR.resolve("part2-antijoin-vs-blanket-comparison.md");

        try (Connection metaConn = connect()) {
            final List<String> combined = selectPool(metaConn, poolSizeEach * 2, SEED_PART2);
            final List<String> poolA = new ArrayList<>(combined.subList(0, poolSizeEach));
            final List<String> poolB = new ArrayList<>(combined.subList(poolSizeEach, combined.size()));
            final Map<String, ThingMeta> metaA = fetchMeta(metaConn, poolA);
            final Map<String, ThingMeta> metaB = fetchMeta(metaConn, poolB);

            final String intro = "# Task 0.3 Part 2 - v1.5 anti-join churn-reduction comparison\n\n"
                    + "Same workload as Part 1 (500-leaf twins, 5 leaves changed per update, same target "
                    + "rate/worker count), alternative flat-row maintenance for Phase B: instead of blanket "
                    + "DELETE-all+INSERT-all, delete-changed/insert-changed via anti-join against the freshly "
                    + "flattened row set, matching on FULL row identity (path, wpath, ord, type_rank, value) in "
                    + "a single statement with two writable CTEs sharing one `MATERIALIZED` unnest (plan doc "
                    + "§3.4 step 2 note). Two DISJOINT 2000-thing pools (poolA=Phase A blanket, poolB=Phase B "
                    + "anti-join), same corpus, same seed run, each measured for "
                    + phaseDuration + " (a shorter comparison window than Part 1's 10 min, per the brief's "
                    + "explicit '2x5 min acceptable' allowance).\n\n"
                    + "**First-touch caveat (disclosed, not hidden):** the very first write to any pool "
                    + "thing_id replaces that thing's PRE-EXISTING CorpusGenerator-shaped flat rows with the "
                    + "twin shape — zero row-identity overlap, so that specific update is full churn under "
                    + "BOTH v1 and v1.5. Only REPEAT touches to the same thing_id within this run let the "
                    + "anti-join's small-churn advantage show up; at the pool size/duration/rate used here, "
                    + "first-touch updates are a small minority of the total (see the per-phase average "
                    + "deleted/inserted-rows-per-txn figures below for the actual, not assumed, effect).\n\n";
            writeResultFile(resultFile, intro);

            appendResultFile(resultFile, "## Phase A - v1 blanket DELETE+INSERT (poolA, " + poolA.size()
                    + " things)\n\n" + captureKnobs(metaConn) + "\n### 30s samples\n\n");
            final RunResult blanket = runWorkload(WriteMode.BLANKET, poolA, metaA, phaseDuration, resultFile);
            appendResultFile(resultFile, summaryFor("Phase A (v1 blanket)", blanket, phaseDuration));

            appendResultFile(resultFile, "\n## Phase B - v1.5 anti-join delete-changed/insert-changed (poolB, "
                    + poolB.size() + " things)\n\n### 30s samples\n\n");
            final RunResult antiJoin = runWorkload(WriteMode.ANTIJOIN, poolB, metaB, phaseDuration, resultFile);
            appendResultFile(resultFile, summaryFor("Phase B (v1.5 anti-join)", antiJoin, phaseDuration));

            appendResultFile(resultFile, comparisonAndRecommendation(blanket, antiJoin));
        }
    }

    private static String comparisonAndRecommendation(final RunResult blanket, final RunResult antiJoin) {
        final double blanketAvgDel = blanket.successCount() == 0 ? 0
                : blanket.deletedRows() / (double) blanket.successCount();
        final double blanketAvgIns = blanket.successCount() == 0 ? 0
                : blanket.insertedRows() / (double) blanket.successCount();
        final double antiAvgDel = antiJoin.successCount() == 0 ? 0
                : antiJoin.deletedRows() / (double) antiJoin.successCount();
        final double antiAvgIns = antiJoin.successCount() == 0 ? 0
                : antiJoin.insertedRows() / (double) antiJoin.successCount();

        final StringBuilder sb = new StringBuilder();
        sb.append("\n## Comparison\n\n");
        sb.append("| metric | v1 blanket (Phase A) | v1.5 anti-join (Phase B) |\n");
        sb.append("|---|---:|---:|\n");
        sb.append(String.format(Locale.ROOT, "| txn p50/p95/max ms | %d / %d / %d | %d / %d / %d |%n",
                blanket.overallP50(), blanket.overallP95(), blanket.overallMax(),
                antiJoin.overallP50(), antiJoin.overallP95(), antiJoin.overallMax()));
        sb.append(String.format(Locale.ROOT, "| avg flat rows deleted/txn | %.1f (~full rewrite; avg diluted by first-touch) | %.1f |%n",
                blanketAvgDel, antiAvgDel));
        sb.append(String.format(Locale.ROOT, "| avg flat rows inserted/txn | %.1f (~full rewrite; avg diluted by first-touch) | %.1f |%n",
                blanketAvgIns, antiAvgIns));
        sb.append(String.format(Locale.ROOT, "| achieved rate | %.1f/s | %.1f/s |%n",
                blanket.successCount() / (double) BenchConfig.writePart2PhaseDuration().toSeconds(),
                antiJoin.successCount() / (double) BenchConfig.writePart2PhaseDuration().toSeconds()));
        sb.append('\n');
        sb.append("(Index-size-growth and dead-tuple trend deltas for each phase are in that phase's own 30s "
                + "sample series above; WAL-bytes-per-window is included in each sample line.)\n\n");
        sb.append("**Recommendation and rationale:** filled in by hand in the findings doc "
                + "(`docs/superpowers/specs/2026-07-03-postgres-search-bench-results.md`) from these committed "
                + "numbers, since the recommendation needs to reference both phases' full sample series (index "
                + "growth / dead tuples / WAL), not just the per-txn row-count averages above.\n");
        return sb.toString();
    }

    private static String summaryFor(final String label, final RunResult r, final Duration duration) {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n### ").append(label).append(" - summary\n\n");
        sb.append("- total successful txns: ").append(r.successCount()).append('\n');
        sb.append("- total errors: ").append(r.errorCount()).append('\n');
        sb.append(String.format(Locale.ROOT, "- achieved average rate: %.1f/s (target %.1f/s, duration %ds)%n",
                r.successCount() / (double) duration.toSeconds(), BenchConfig.writeTargetRate(), duration.toSeconds()));
        sb.append("- overall txn latency: p50=").append(r.overallP50()).append("ms p95=").append(r.overallP95())
                .append("ms max=").append(r.overallMax()).append("ms\n");
        if (r.deletedRows() >= 0) {
            sb.append(String.format(Locale.ROOT,
                    "- total flat rows deleted: %d, inserted: %d (avg %.1f deleted + %.1f inserted per txn)%n",
                    r.deletedRows(), r.insertedRows(),
                    r.successCount() == 0 ? 0 : r.deletedRows() / (double) r.successCount(),
                    r.successCount() == 0 ? 0 : r.insertedRows() / (double) r.successCount()));
        }
        return sb.toString();
    }

    // =============================================================================================================
    // Shared phase runner (used by Part 1; Part 2 calls runWorkload twice directly for its two-phase comparison)
    // =============================================================================================================

    private void runPhaseAndWrite(final WriteMode mode, final Duration duration, final String fileBaseName,
            final String intro, final double seedValue, final int poolSize) throws Exception {
        final Path resultFile = RESULTS_DIR.resolve(fileBaseName + ".md");
        try (Connection metaConn = connect()) {
            final List<String> pool = selectPool(metaConn, poolSize, seedValue);
            final Map<String, ThingMeta> metaMap = fetchMeta(metaConn, pool);

            final StringBuilder header = new StringBuilder();
            header.append(intro);
            header.append("Pool: ").append(pool.size()).append(" distinct thing_ids (random, seeded via `SELECT "
                    + "setseed(").append(seedValue).append(")` then `ORDER BY random()`).\n\n");
            header.append("Worker count: ").append(BenchConfig.writeWorkers()).append(", target aggregate rate: ")
                    .append(BenchConfig.writeTargetRate()).append("/s, duration: ").append(duration).append(".\n\n");
            header.append("Twin shape: ").append(LEAVES_PER_TWIN)
                    .append(" flat rows/thing (flat numeric attributes, no features), ").append(HOT_LEAVES_PER_UPDATE)
                    .append(" leaves mutated per update.\n\n");
            header.append(captureKnobs(metaConn)).append('\n');
            header.append("## 30s samples\n\n");
            writeResultFile(resultFile, header.toString());

            final RunResult result = runWorkload(mode, pool, metaMap, duration, resultFile);
            appendResultFile(resultFile, summaryFor("Run", result, duration));
        }
    }

    // =============================================================================================================
    // Workload engine: spins up N worker threads (rate-limited to an aggregate target) + one sampler thread,
    // joins them, returns the run's overall stats. The sampler writes its own 30s samples DIRECTLY to
    // resultFile as it goes (not buffered and dumped at the end) so progress is visible on disk mid-run.
    // =============================================================================================================

    private RunResult runWorkload(final WriteMode mode, final List<String> pool, final Map<String, ThingMeta> metaMap,
            final Duration duration, final Path resultFile) throws InterruptedException {

        final int workers = BenchConfig.writeWorkers();
        final RateLimiter limiter = new RateLimiter(BenchConfig.writeTargetRate());
        final AtomicLong versionCounter = new AtomicLong();
        final LongAdder successCounter = new LongAdder();
        final LongAdder errorCounter = new LongAdder();
        final LongAdder deletedRowsAdder = new LongAdder();
        final LongAdder insertedRowsAdder = new LongAdder();
        final ConcurrentLinkedQueue<Long> sampleWindowLatencies = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> overallLatencies = new ConcurrentLinkedQueue<>();

        final long deadlineNanos = System.nanoTime() + duration.toNanos();
        final List<List<String>> shards = shard(pool, workers);

        final List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            final Worker worker = new Worker(shards.get(w), metaMap, mode, limiter, versionCounter, deadlineNanos,
                    sampleWindowLatencies, overallLatencies, successCounter, errorCounter, deletedRowsAdder,
                    insertedRowsAdder, new SplittableRandom(BenchConfig.seed() + 1000L + w));
            threads.add(new Thread(worker, "write-bench-worker-" + w));
        }
        final Thread sampler = new Thread(
                () -> samplerLoop(deadlineNanos, BenchConfig.writeSampleInterval(), resultFile, successCounter,
                        errorCounter, sampleWindowLatencies),
                "write-bench-sampler");

        for (final Thread t : threads) {
            t.start();
        }
        sampler.start();
        for (final Thread t : threads) {
            t.join();
        }
        sampler.join();

        final List<Long> sortedAll = new ArrayList<>(overallLatencies);
        Collections.sort(sortedAll);
        return new RunResult(successCounter.sum(), errorCounter.sum(), percentile(sortedAll, 50),
                percentile(sortedAll, 95), sortedAll.isEmpty() ? 0 : sortedAll.get(sortedAll.size() - 1),
                deletedRowsAdder.sum(), insertedRowsAdder.sum());
    }

    private record RunResult(long successCount, long errorCount, long overallP50, long overallP95, long overallMax,
            long deletedRows, long insertedRows) {
    }

    private static List<List<String>> shard(final List<String> pool, final int workers) {
        final List<List<String>> shards = new ArrayList<>();
        final int base = pool.size() / workers;
        int start = 0;
        for (int w = 0; w < workers; w++) {
            final int end = (w == workers - 1) ? pool.size() : start + base;
            shards.add(new ArrayList<>(pool.subList(start, end)));
            start = end;
        }
        return shards;
    }

    // =============================================================================================================
    // Worker: one JDBC connection, one thing per iteration, rate-limited, blanket OR anti-join flat-row rewrite.
    // =============================================================================================================

    private static final class Worker implements Runnable {

        private final List<String> shard;
        private final Map<String, ThingMeta> metaMap;
        private final WriteMode mode;
        private final RateLimiter limiter;
        private final AtomicLong versionCounter;
        private final long deadlineNanos;
        private final ConcurrentLinkedQueue<Long> sampleWindowLatencies;
        private final ConcurrentLinkedQueue<Long> overallLatencies;
        private final LongAdder successCounter;
        private final LongAdder errorCounter;
        private final LongAdder deletedRowsAdder;
        private final LongAdder insertedRowsAdder;
        private final SplittableRandom rng;

        Worker(final List<String> shard, final Map<String, ThingMeta> metaMap, final WriteMode mode,
                final RateLimiter limiter, final AtomicLong versionCounter, final long deadlineNanos,
                final ConcurrentLinkedQueue<Long> sampleWindowLatencies,
                final ConcurrentLinkedQueue<Long> overallLatencies, final LongAdder successCounter,
                final LongAdder errorCounter, final LongAdder deletedRowsAdder, final LongAdder insertedRowsAdder,
                final SplittableRandom rng) {
            this.shard = shard;
            this.metaMap = metaMap;
            this.mode = mode;
            this.limiter = limiter;
            this.versionCounter = versionCounter;
            this.deadlineNanos = deadlineNanos;
            this.sampleWindowLatencies = sampleWindowLatencies;
            this.overallLatencies = overallLatencies;
            this.successCounter = successCounter;
            this.errorCounter = errorCounter;
            this.deletedRowsAdder = deletedRowsAdder;
            this.insertedRowsAdder = insertedRowsAdder;
            this.rng = rng;
        }

        @Override
        public void run() {
            final Map<String, TwinState> localState = new HashMap<>();
            try (Connection conn = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(),
                    BenchConfig.password())) {
                conn.setAutoCommit(false);
                try (PreparedStatement upsertPs = conn.prepareStatement(UPSERT_SQL);
                        PreparedStatement deletePs = conn.prepareStatement(DELETE_FLAT_SQL);
                        PreparedStatement insertPs = conn.prepareStatement(INSERT_FLAT_SQL);
                        PreparedStatement antiJoinPs = conn.prepareStatement(ANTIJOIN_SQL)) {

                    if (shard.isEmpty()) {
                        return;
                    }
                    while (System.nanoTime() < deadlineNanos) {
                        try {
                            limiter.acquire();
                        } catch (final InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        final String thingId = shard.get(rng.nextInt(shard.size()));
                        final ThingMeta meta = metaMap.get(thingId);
                        final TwinState state = localState.computeIfAbsent(thingId, k -> new TwinState(meta.revision()));

                        final long t0 = System.nanoTime();
                        try {
                            final int[] counts = executeTxn(conn, upsertPs, deletePs, insertPs, antiJoinPs, mode,
                                    thingId, meta, state, versionCounter);
                            final long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                            sampleWindowLatencies.add(elapsedMs);
                            overallLatencies.add(elapsedMs);
                            successCounter.increment();
                            deletedRowsAdder.add(counts[0]);
                            insertedRowsAdder.add(counts[1]);
                        } catch (final SQLException e) {
                            errorCounter.increment();
                            try {
                                conn.rollback();
                            } catch (final SQLException ignore) {
                                // connection likely broken; loop condition / next iteration will surface it
                            }
                        }
                    }
                }
            } catch (final SQLException e) {
                throw new IllegalStateException("write-bench worker connection failure", e);
            }
        }
    }

    private static final class TwinState {
        private final long[] values = new long[LEAVES_PER_TWIN];
        private long revision;

        TwinState(final long initialRevision) {
            this.revision = initialRevision;
        }
    }

    /**
     * Mutates {@link TwinState} ({@value #HOT_LEAVES_PER_UPDATE} of {@value #LEAVES_PER_TWIN} leaves get a new
     * monotonic version number; the rest are left byte-identical to the previous write), builds the twin's
     * {@code thing} JSON + flat rows via the real {@link Flattener}, and executes one full write-path
     * transaction (doc-row upsert + blanket-or-anti-join flat rewrite) — see class Javadoc for the twin-shape
     * rationale.
     *
     * @return {@code [deletedFlatRowCount, insertedFlatRowCount]} (both always {@value #LEAVES_PER_TWIN} for
     * {@link WriteMode#BLANKET}; the anti-join's actual small counts for {@link WriteMode#ANTIJOIN}).
     */
    private static int[] executeTxn(final Connection conn, final PreparedStatement upsertPs,
            final PreparedStatement deletePs, final PreparedStatement insertPs, final PreparedStatement antiJoinPs,
            final WriteMode mode, final String thingId, final ThingMeta meta, final TwinState state,
            final AtomicLong versionCounter) throws SQLException {

        final long v = versionCounter.incrementAndGet();
        final int base = Math.floorMod(thingId.hashCode() ^ (int) v, LEAVES_PER_TWIN);
        for (int k = 0; k < HOT_LEAVES_PER_UPDATE; k++) {
            state.values[(base + k * 97) % LEAVES_PER_TWIN] = v;
        }
        state.revision++;

        final Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i < LEAVES_PER_TWIN; i++) {
            attributes.put("attr" + i, state.values[i]);
        }
        final Map<String, Object> thingMap = new LinkedHashMap<>();
        thingMap.put("attributes", attributes);
        final List<FlatRow> flatRows = Flattener.flatten(thingId, thingMap);
        final String thingJson = JsonWriter.write(thingMap);

        final int n = flatRows.size();
        final String[] paths = new String[n];
        final String[] wpaths = new String[n];
        final String[] fIds = new String[n];
        final Integer[] ords = new Integer[n];
        final Short[] typeRanks = new Short[n];
        final Boolean[] valBools = new Boolean[n];
        final BigDecimal[] valNums = new BigDecimal[n];
        final String[] valTexts = new String[n];
        for (int i = 0; i < n; i++) {
            final FlatRow r = flatRows.get(i);
            paths[i] = r.path();
            wpaths[i] = r.wpath();
            fIds[i] = r.fId();
            ords[i] = r.ord();
            typeRanks[i] = r.typeRank();
            valBools[i] = r.valBool();
            valNums[i] = r.valNum();
            valTexts[i] = r.valText();
        }

        bindUpsert(conn, upsertPs, thingId, meta, state.revision, thingJson);
        upsertPs.executeUpdate();

        final int deletedCount;
        final int insertedCount;
        if (mode == WriteMode.BLANKET) {
            deletePs.setString(1, thingId);
            deletedCount = deletePs.executeUpdate();
            insertPs.setString(1, thingId);
            bindArrays(conn, insertPs, 2, paths, wpaths, fIds, ords, typeRanks, valBools, valNums, valTexts);
            insertedCount = insertPs.executeUpdate();
        } else {
            bindArrays(conn, antiJoinPs, 1, paths, wpaths, fIds, ords, typeRanks, valBools, valNums, valTexts);
            antiJoinPs.setString(9, thingId);
            antiJoinPs.setString(10, thingId);
            antiJoinPs.setString(11, thingId);
            try (ResultSet rs = antiJoinPs.executeQuery()) {
                rs.next();
                deletedCount = rs.getInt(1);
                insertedCount = rs.getInt(2);
            }
        }
        conn.commit();
        return new int[] {deletedCount, insertedCount};
    }

    private static void bindUpsert(final Connection conn, final PreparedStatement ps, final String thingId,
            final ThingMeta meta, final long revision, final String thingJson) throws SQLException {
        ps.setString(1, thingId);
        ps.setString(2, meta.namespace());
        ps.setLong(3, revision);
        if (meta.policyId() != null) {
            ps.setString(4, meta.policyId());
        } else {
            ps.setNull(4, Types.VARCHAR);
        }
        if (meta.policyRev() != null) {
            ps.setLong(5, meta.policyRev());
        } else {
            ps.setNull(5, Types.BIGINT);
        }
        ps.setNull(6, Types.OTHER); // referenced_policies jsonb — not exercised by this write-mechanics bench
        ps.setArray(7, conn.createArrayOf("text", meta.globalRead()));
        ps.setString(8, thingJson); // ?::jsonb cast in SQL
        ps.setNull(9, Types.OTHER); // policy_auth
        ps.setNull(10, Types.OTHER); // features_auth
        ps.setTimestamp(11, Timestamp.from(Instant.now()));
        ps.setNull(12, Types.TIMESTAMP); // delete_at
    }

    private static void bindArrays(final Connection conn, final PreparedStatement ps, final int start,
            final String[] paths, final String[] wpaths, final String[] fIds, final Integer[] ords,
            final Short[] typeRanks, final Boolean[] valBools, final BigDecimal[] valNums, final String[] valTexts)
            throws SQLException {
        int i = start;
        ps.setArray(i++, conn.createArrayOf("text", paths));
        ps.setArray(i++, conn.createArrayOf("text", wpaths));
        ps.setArray(i++, conn.createArrayOf("text", fIds));
        ps.setArray(i++, conn.createArrayOf("int4", ords));
        ps.setArray(i++, conn.createArrayOf("int2", typeRanks));
        ps.setArray(i++, conn.createArrayOf("bool", valBools));
        ps.setArray(i++, conn.createArrayOf("numeric", valNums));
        ps.setArray(i, conn.createArrayOf("text", valTexts));
    }

    // --- v1 blanket / v1.5 anti-join SQL (JDBC "?" positional placeholders — see each constant's comment for the
    // exact bind order; this is JDBC via java.sql, not R2DBC, so it is "?" not "$n") -----------------------------

    private static final String UPSERT_SQL =
            "INSERT INTO search_things "
                    + "(thing_id, namespace, revision, policy_id, policy_rev, referenced_policies, global_read, "
                    + "thing, policy_auth, features_auth, t_modified, delete_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, now()) "
                    + "ON CONFLICT (thing_id) DO UPDATE SET "
                    + "namespace = EXCLUDED.namespace, revision = EXCLUDED.revision, "
                    + "policy_id = EXCLUDED.policy_id, policy_rev = EXCLUDED.policy_rev, "
                    + "referenced_policies = EXCLUDED.referenced_policies, global_read = EXCLUDED.global_read, "
                    + "thing = EXCLUDED.thing, policy_auth = EXCLUDED.policy_auth, "
                    + "features_auth = EXCLUDED.features_auth, t_modified = EXCLUDED.t_modified, "
                    + "delete_at = EXCLUDED.delete_at, updated_at = EXCLUDED.updated_at";

    private static final String DELETE_FLAT_SQL = "DELETE FROM search_flat WHERE thing_id = ?";

    /** Binds: 1=thing_id, 2..9=the 8 unnest arrays (paths,wpaths,f_ids,ords,type_ranks,val_bools,val_nums,val_texts). */
    private static final String INSERT_FLAT_SQL =
            "INSERT INTO search_flat (thing_id, path, wpath, f_id, ord, type_rank, val_bool, val_num, val_text) "
                    + "SELECT ?, u.path, u.wpath, u.f_id, u.ord, u.type_rank, u.val_bool, u.val_num, u.val_text "
                    + "FROM unnest(?::text[], ?::text[], ?::text[], ?::int[], ?::smallint[], ?::boolean[], "
                    + "?::numeric[], ?::text[]) AS u(path, wpath, f_id, ord, type_rank, val_bool, val_num, val_text)";

    /**
     * v1.5 delete-changed/insert-changed via anti-join, single statement, two writable CTEs sharing one
     * {@code MATERIALIZED} unnest CTE (computed once). PostgreSQL data-modifying CTEs in one statement all see
     * the SAME pre-statement snapshot of the tables they touch (verified on PG 16) — so {@code inserted}'s
     * {@code NOT EXISTS} against {@code search_flat} is unaffected by {@code deleted}'s concurrent removals
     * within the same statement, which is exactly what "match on full row identity" requires: unchanged rows
     * (same path/wpath/ord/type_rank/value) are excluded from BOTH the delete and the insert; only the
     * {@value #HOT_LEAVES_PER_UPDATE} changed leaves are deleted (old value) and re-inserted (new value).
     * Binds: 1-8 = the 8 unnest arrays (same order as {@link #INSERT_FLAT_SQL}), 9 = thing_id (delete filter),
     * 10 = thing_id (insert SELECT value), 11 = thing_id (insert's NOT EXISTS filter).
     */
    private static final String ANTIJOIN_SQL =
            "WITH new_rows AS MATERIALIZED ("
                    + "  SELECT * FROM unnest(?::text[], ?::text[], ?::text[], ?::int[], ?::smallint[], ?::boolean[], "
                    + "?::numeric[], ?::text[])"
                    + "    AS u(path, wpath, f_id, ord, type_rank, val_bool, val_num, val_text)"
                    + "), "
                    + "deleted AS ("
                    + "  DELETE FROM search_flat sf"
                    + "  WHERE sf.thing_id = ?"
                    + "    AND NOT EXISTS ("
                    + "      SELECT 1 FROM new_rows n"
                    + "      WHERE n.path = sf.path AND n.wpath = sf.wpath AND n.ord = sf.ord"
                    + "        AND n.type_rank = sf.type_rank"
                    + "        AND n.val_bool IS NOT DISTINCT FROM sf.val_bool"
                    + "        AND n.val_num IS NOT DISTINCT FROM sf.val_num"
                    + "        AND n.val_text IS NOT DISTINCT FROM sf.val_text)"
                    + "  RETURNING 1"
                    + "), "
                    + "inserted AS ("
                    + "  INSERT INTO search_flat (thing_id, path, wpath, f_id, ord, type_rank, val_bool, val_num, val_text)"
                    + "  SELECT ?, n.path, n.wpath, n.f_id, n.ord, n.type_rank, n.val_bool, n.val_num, n.val_text"
                    + "  FROM new_rows n"
                    + "  WHERE NOT EXISTS ("
                    + "    SELECT 1 FROM search_flat sf"
                    + "    WHERE sf.thing_id = ? AND sf.path = n.path AND sf.wpath = n.wpath AND sf.ord = n.ord"
                    + "      AND sf.type_rank = n.type_rank"
                    + "      AND sf.val_bool IS NOT DISTINCT FROM n.val_bool"
                    + "      AND sf.val_num IS NOT DISTINCT FROM n.val_num"
                    + "      AND sf.val_text IS NOT DISTINCT FROM n.val_text)"
                    + "  RETURNING 1"
                    + ") "
                    + "SELECT (SELECT count(*) FROM deleted) AS deleted_count, "
                    + "(SELECT count(*) FROM inserted) AS inserted_count";

    // =============================================================================================================
    // Sampler: every writeSampleInterval() during the run, appends one sample block DIRECTLY to resultFile.
    // =============================================================================================================

    private static void samplerLoop(final long deadlineNanos, final Duration interval, final Path resultFile,
            final LongAdder successCounter, final LongAdder errorCounter,
            final ConcurrentLinkedQueue<Long> sampleWindowLatencies) {
        try (Connection conn = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(),
                BenchConfig.password())) {
            long lastSampleNanos = System.nanoTime();
            long lastSuccess = 0;
            long lastWalBytes = currentWalBytes(conn);
            int sampleIdx = 0;
            final long runStartNanos = System.nanoTime();

            while (true) {
                final long now = System.nanoTime();
                final long remainingNanos = deadlineNanos - now;
                final long sleepNanos = Math.min(interval.toNanos(), Math.max(0L, remainingNanos));
                if (sleepNanos > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                }
                sampleIdx++;
                final long sampledAtNanos = System.nanoTime();
                final double elapsedSecSinceLast = (sampledAtNanos - lastSampleNanos) / 1_000_000_000.0;
                final double elapsedSecSinceStart = (sampledAtNanos - runStartNanos) / 1_000_000_000.0;
                final long currentSuccess = successCounter.sum();
                final long currentErrors = errorCounter.sum();
                final double achievedRate =
                        elapsedSecSinceLast <= 0 ? 0 : (currentSuccess - lastSuccess) / elapsedSecSinceLast;

                final List<Long> window = new ArrayList<>();
                Long l;
                while ((l = sampleWindowLatencies.poll()) != null) {
                    window.add(l);
                }
                Collections.sort(window);

                final long walBytes = currentWalBytes(conn);
                final long walDelta = walBytes - lastWalBytes;

                appendResultFile(resultFile,
                        formatSample(conn, sampleIdx, elapsedSecSinceStart, elapsedSecSinceLast, currentSuccess,
                                currentErrors, achievedRate, window, walDelta));

                lastSampleNanos = sampledAtNanos;
                lastSuccess = currentSuccess;
                lastWalBytes = walBytes;

                if (sampledAtNanos >= deadlineNanos) {
                    break;
                }
            }
        } catch (final SQLException | IOException | InterruptedException e) {
            throw new IllegalStateException("write-bench sampler failure", e);
        }
    }

    private static String formatSample(final Connection conn, final int idx, final double elapsedSecSinceStart,
            final double windowSec, final long totalSuccess, final long totalErrors, final double achievedRate,
            final List<Long> windowLatenciesMs, final long walDeltaBytes) throws SQLException {

        final long p50 = percentile(windowLatenciesMs, 50);
        final long p95 = percentile(windowLatenciesMs, 95);
        final long max = windowLatenciesMs.isEmpty() ? 0 : windowLatenciesMs.get(windowLatenciesMs.size() - 1);

        final StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "### Sample #%d (t+%.0fs)%n%n", idx, elapsedSecSinceStart));
        sb.append(String.format(Locale.ROOT,
                "- window %.1fs: %d txns, achieved rate %.1f/s; cumulative success=%d errors=%d%n",
                windowSec, windowLatenciesMs.size(), achievedRate, totalSuccess, totalErrors));
        sb.append(String.format(Locale.ROOT, "- txn latency (this window): p50=%dms p95=%dms max=%dms%n", p50, p95,
                max));
        sb.append(String.format(Locale.ROOT, "- WAL bytes this window: %d (%.1f MB)%n", walDeltaBytes,
                walDeltaBytes / 1024.0 / 1024.0));

        sb.append("- relation sizes (bytes): ");
        for (final String rel : TRACKED_RELATIONS) {
            sb.append(rel).append('=').append(relationSizeBytes(conn, rel)).append(' ');
        }
        sb.append('\n');

        final TableStats ts = tableStats(conn, "search_flat");
        sb.append(String.format(Locale.ROOT,
                "- search_flat pg_stat_user_tables: n_live_tup=%d n_dead_tup=%d autovacuum_count=%d "
                        + "last_autovacuum=%s n_tup_ins=%d n_tup_del=%d%n%n",
                ts.nLiveTup(), ts.nDeadTup(), ts.autovacuumCount(), ts.lastAutovacuum(), ts.nTupIns(), ts.nTupDel()));
        return sb.toString();
    }

    private record TableStats(long nLiveTup, long nDeadTup, long autovacuumCount, String lastAutovacuum,
            long nTupIns, long nTupDel) {
    }

    private static TableStats tableStats(final Connection conn, final String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n_live_tup, n_dead_tup, autovacuum_count, last_autovacuum, n_tup_ins, n_tup_del "
                        + "FROM pg_stat_user_tables WHERE relname = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new TableStats(-1, -1, -1, "n/a", -1, -1);
                }
                final Timestamp lastAv = rs.getTimestamp(4);
                return new TableStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        lastAv != null ? lastAv.toString() : "never", rs.getLong(5), rs.getLong(6));
            }
        }
    }

    private static long relationSizeBytes(final Connection conn, final String relationName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_relation_size(?::regclass)")) {
            ps.setString(1, relationName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long currentWalBytes(final Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long percentile(final List<Long> sortedMillis, final int pct) {
        if (sortedMillis.isEmpty()) {
            return 0;
        }
        final int idx = Math.max(0,
                Math.min((int) Math.ceil(pct / 100.0 * sortedMillis.size()) - 1, sortedMillis.size() - 1));
        return sortedMillis.get(idx);
    }

    // =============================================================================================================
    // Pool selection / thing metadata / DB knob capture (Part 0 + phase headers)
    // =============================================================================================================

    private static List<String> selectPool(final Connection conn, final int count, final double seedValue)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT setseed(" + seedValue + ")");
        }
        final List<String> ids = new ArrayList<>(count);
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT thing_id FROM search_things ORDER BY random() LIMIT ?")) {
            ps.setInt(1, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    private record ThingMeta(String namespace, long revision, @Nullable String policyId, @Nullable Long policyRev,
            String[] globalRead) {
    }

    private static Map<String, ThingMeta> fetchMeta(final Connection conn, final List<String> thingIds)
            throws SQLException {
        final Map<String, ThingMeta> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT thing_id, namespace, revision, policy_id, policy_rev, global_read FROM search_things "
                        + "WHERE thing_id = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("text", thingIds.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String thingId = rs.getString(1);
                    final String namespace = rs.getString(2);
                    final long revision = rs.getLong(3);
                    final String policyId = rs.getString(4);
                    final long policyRevRaw = rs.getLong(5);
                    final Long policyRev = rs.wasNull() ? null : policyRevRaw;
                    final Array arr = rs.getArray(6);
                    final String[] globalRead = arr != null ? (String[]) arr.getArray() : new String[0];
                    result.put(thingId, new ThingMeta(namespace, revision, policyId, policyRev, globalRead));
                }
            }
        }
        return result;
    }

    private static String captureKnobs(final Connection conn) throws SQLException {
        final StringBuilder sb = new StringBuilder();
        sb.append("GIN / autovacuum / memory knobs in effect (recorded per the brief; defaults are fine, they "
                + "just must be recorded):\n\n```\n");
        for (final String setting : new String[] {
                "gin_pending_list_limit", "autovacuum_vacuum_scale_factor", "autovacuum_vacuum_cost_limit",
                "autovacuum_vacuum_cost_delay", "autovacuum_naptime", "shared_buffers", "effective_cache_size",
                "work_mem",
        }) {
            sb.append(setting).append(" = ").append(showSetting(conn, setting)).append('\n');
        }
        sb.append("sf_trgm reloptions (fastupdate; empty = default ON) = ").append(reloptions(conn, "sf_trgm"))
                .append('\n');
        sb.append("sf_trgm_scoped_feature reloptions = ").append(reloptions(conn, "sf_trgm_scoped_feature"))
                .append('\n');
        sb.append("search_flat reloptions (autovacuum overrides; empty = using the global defaults above) = ")
                .append(reloptions(conn, "search_flat")).append('\n');
        sb.append("statistics targets: ").append(statisticsTargets(conn)).append('\n');
        sb.append("```\n");
        return sb.toString();
    }

    private static String showSetting(final Connection conn, final String setting) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String reloptions(final Connection conn, final String relName) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT reloptions FROM pg_class WHERE relname = ?")) {
            ps.setString(1, relName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "(relation not found)";
                }
                final String opts = rs.getString(1);
                return opts != null ? opts : "(none — all defaults)";
            }
        }
    }

    private static String statisticsTargets(final Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT attname, attstattarget FROM pg_attribute WHERE attrelid = 'search_flat'::regclass "
                                + "AND attname IN ('wpath','val_text','val_num') ORDER BY attname")) {
            final StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append('=').append(rs.getInt(2)).append(' ');
            }
            return sb.toString().stripTrailing();
        }
    }

    private static String listIndexes(final Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT indexname, pg_size_pretty(pg_relation_size(indexname::regclass)) FROM pg_indexes "
                                + "WHERE tablename IN ('search_flat','search_things') ORDER BY indexname")) {
            final StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append(" | ").append(rs.getString(2)).append('\n');
            }
            return sb.toString();
        }
    }

    private static String rowCounts(final Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            final StringBuilder sb = new StringBuilder();
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM search_things")) {
                rs.next();
                sb.append("search_things = ").append(rs.getLong(1)).append('\n');
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM search_flat")) {
                rs.next();
                sb.append("search_flat = ").append(rs.getLong(1)).append('\n');
            }
            return sb.toString();
        }
    }

    private static void executeDdl(final Connection conn, final String ddl, final StringBuilder ddlLog)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            final long t0 = System.nanoTime();
            st.execute(ddl);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            ddlLog.append(ddl).append(";  -- ").append(ms).append("ms\n");
        }
    }

    private static Connection connect() throws SQLException {
        final Connection conn = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(),
                BenchConfig.password());
        conn.setAutoCommit(true);
        return conn;
    }

}
