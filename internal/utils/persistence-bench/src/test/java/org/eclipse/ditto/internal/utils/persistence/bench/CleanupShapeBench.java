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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LongSummaryStatistics;
import java.util.Optional;
import java.util.function.IntFunction;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.ditto.json.JsonFactory;
import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

/**
 * Group C shapes. C1: one getNewestSnapshotsAbove page (the PG loose index scan vs the
 * MongoReadJournal aggregation), cleanup parameters (includeDeleted=true, no pid filter, batch 100)
 * with the bench's history-retention pin minAge=0; the C1 retention variant repeats it with the
 * production min age (things.conf history-retention-duration, default 3d) next to minAge=0. The C1
 * payload pair times the PG page before the payload-free change (with the snapshot payload) against the
 * production page, to show what the change saves, in a chosen server cache state (as is, cold, warm).
 * C2: the full single-pid cleanup sequence Cleanup.java performs — min-event-sn, batched event
 * range deletes tiling to snapshotSn-1, min-snapshot-sn, batched snapshot range deletes keeping
 * the latest. NOTE (plan deviation 2): no journal_seq.deleted_to update — the real cleanup path
 * does not touch it.
 */
public final class CleanupShapeBench {

    /** Server cache state the C1 payload pair establishes before timing ({@code bench.c1.cacheState}). */
    enum CacheState {
        /** No preparation: whatever the server held when the stage started (the 2026-10-20 runs). */
        ASIS("asis", "as is: no preparation, whatever the server held when the stage started"),
        /**
         * The server was recreated right before the stage ({@code run-benchmark.sh recreate}): shared buffers start
         * empty; the VM's page cache is not controlled. The run fails unless timing read blocks into shared buffers.
         */
        COLD("cold", "cold: the server was recreated right before the stage (shared buffers started empty; the VM's "
                + "page cache is not controlled, its hits show as reads too; the read time per block tells page-cache "
                + "reads from disk reads)"),
        /**
         * One untimed pass over the whole pid stream with the statement before the change (payload included: index,
         * heap and TOAST blocks) before timing: every page in shared buffers.
         */
        WARM("warm", "warm: one untimed pass over the whole pid stream with the statement before the change "
                + "(payload included: index, heap and TOAST blocks) before timing");

        final String word;
        final String description;

        CacheState(final String word, final String description) {
            this.word = word;
            this.description = description;
        }

        static CacheState parse(final String word) {
            for (final CacheState state : values()) {
                if (state.word.equals(word)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("bench.c1.cacheState: asis, cold or warm, got '" + word + "'");
        }
    }

    /**
     * things_snaps block reads and hits (heap, indexes, TOAST table and its index; pg_statio_user_tables) and the
     * database's block read time in ms (pg_stat_database.blk_read_time, counted only for sessions with
     * track_io_timing on). A read is a block that was not in shared buffers; where it came from (the VM's page cache
     * or the disk) shows in the read time.
     */
    record PgIo(long heapRead, long heapHit, long idxRead, long idxHit, long toastRead, long toastHit,
            double readTimeMs) {

        PgIo minus(final PgIo o) {
            return new PgIo(heapRead - o.heapRead, heapHit - o.heapHit, idxRead - o.idxRead, idxHit - o.idxHit,
                    toastRead - o.toastRead, toastHit - o.toastHit, readTimeMs - o.readTimeMs);
        }

        long reads() {
            return heapRead + idxRead + toastRead;
        }

        long hits() {
            return heapHit + idxHit + toastHit;
        }

        String line() {
            return String.format(Locale.ROOT,
                    "heap %d read / %d hit, index %d / %d, toast %d / %d; %d reads in all, read time %.1f ms",
                    heapRead, heapHit, idxRead, idxHit, toastRead, toastHit, reads(), readTimeMs);
        }
    }

    /** A {@link PgIo} snapshot after the session's pending statistics were flushed (pg_stat_force_next_flush). */
    static PgIo pgIo(final Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT pg_stat_force_next_flush()");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT s.heap_blks_read, s.heap_blks_hit, s.idx_blks_read, "
                + "s.idx_blks_hit, coalesce(s.toast_blks_read, 0) + coalesce(s.tidx_blks_read, 0) AS toast_read, "
                + "coalesce(s.toast_blks_hit, 0) + coalesce(s.tidx_blks_hit, 0) AS toast_hit, "
                + "(SELECT d.blk_read_time FROM pg_stat_database d WHERE d.datname = current_database()) AS read_ms "
                + "FROM pg_statio_user_tables s WHERE s.relname = 'things_snaps'");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("no things_snaps statistics: is the corpus loaded?");
            }
            return new PgIo(rs.getLong("heap_blks_read"), rs.getLong("heap_blks_hit"), rs.getLong("idx_blks_read"),
                    rs.getLong("idx_blks_hit"), rs.getLong("toast_read"), rs.getLong("toast_hit"),
                    rs.getDouble("read_ms"));
        }
    }

    /** "<pg_postmaster_start_time UTC>Z, N s before this sample": dates the recreate of a cold run. */
    static String pgServerStart(final Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_postmaster_start_time() AT TIME ZONE 'UTC', "
                + "extract(epoch FROM now() - pg_postmaster_start_time())::bigint");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1) + "Z, " + rs.getLong(2) + " s before this sample";
        }
    }

    /** What one untimed pass over the whole pid stream read. */
    record WarmPass(int pages, long pids, double seconds, PgIo blocks) {

        String line() {
            return String.format(Locale.ROOT, "%d pages / %d pids with the statement before the change at minAge 0 "
                    + "in %.1f s; things_snaps blocks during the pass: %s", pages, pids, seconds, blocks.line());
        }
    }

    /**
     * One keyset pass over the whole pid stream with the statement it is given at minAge 0; with the statement before
     * the change (payload included) every index, heap and TOAST block either page touches is in shared buffers
     * afterwards (the corpus's things_snaps is about 300 MB against 4 GB of shared buffers). Untimed: not part of any
     * Timing.
     */
    static WarmPass pgWarmPass(final Connection c, final PreparedStatement withPayload, final int limit)
            throws SQLException {
        final PgIo before = pgIo(c);
        final long t0 = System.nanoTime();
        int pages = 0;
        long pids = 0;
        String lowerBound = "";
        while (true) {
            final List<NewestSnapshotOracle.PidSn> page = pgPage(withPayload, lowerBound, Duration.ZERO, limit);
            if (page.isEmpty()) {
                break;
            }
            pages++;
            pids += page.size();
            lowerBound = page.get(page.size() - 1).pid();
            if (page.size() < limit) {
                break;
            }
        }
        final double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
        return new WarmPass(pages, pids, seconds, pgIo(c).minus(before));
    }

    /** The failure a cache state's block counts during timing imply, if any: cold must read, warm (almost) not. */
    static Optional<String> cacheStateCheck(final CacheState state, final PgIo duringTiming) {
        return switch (state) {
            case COLD -> duringTiming.reads() == 0
                    ? Optional.of("cold state not established: things_snaps had no buffer read during timing (the "
                            + "server was not recreated right before the stage, or something read the pages first)")
                    : Optional.empty();
            case WARM -> duringTiming.reads() * 100 > duringTiming.hits()
                    ? Optional.of("warm state not established: " + duringTiming.reads() + " buffer reads against "
                            + duringTiming.hits() + " hits during timing (more than 1 %)")
                    : Optional.empty();
            case ASIS -> Optional.empty();
        };
    }

    /**
     * The min ages of the payload pair's two timed pairs, in order. Both pairs read the same lower bounds, so only the
     * first meets cold shared buffers: the cold state times minAge 0 (the C1 row's filter) first; the other states
     * keep the order of the 2026-10-20 runs (retention, then 0).
     */
    static List<Duration> pairOrder(final CacheState state, final Duration retention) {
        return state == CacheState.COLD ? List.of(Duration.ZERO, retention) : List.of(retention, Duration.ZERO);
    }

    /** Sink for {@link #pgPageDecoded}'s parse results, so the JIT cannot drop the parse. */
    private static long parsedFields;

    /** Deterministic lower bounds spread over the pid space; iteration 0 = "" (the first page). */
    static List<String> c1LowerBounds(final CorpusGenerator gen) {
        final int n = BenchConfig.warmups() + BenchConfig.iterations();
        final List<String> lowerBounds = new ArrayList<>(n);
        lowerBounds.add("");
        for (int i = 1; i < n; i++) {
            lowerBounds.add(gen.pid((long) i * (gen.pidCount() / n)));
        }
        return lowerBounds;
    }

    @Test
    public void c1PidStreamPage() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final List<String> lowerBounds = c1LowerBounds(gen);
        BenchProtocol.Timing pgT = null;
        BenchProtocol.Timing mongoT = null;
        final StringBuilder plans = new StringBuilder();
        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg();
                    PreparedStatement ps = c.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)) {
                pgT = BenchProtocol.measure(iteration -> {
                    ps.setString(1, lowerBounds.get(iteration % lowerBounds.size()));
                    ps.setString(2, "");
                    ps.setString(3, "");
                    ps.setBoolean(4, true);          // minAge 0 -> age filter disabled
                    ps.setString(5, "0 seconds");
                    ps.setInt(6, PersistenceShapes.READS_PER_QUERY);
                    ps.setBoolean(7, true);          // phase-2 age filter disabled (mirrors bind 4)
                    ps.setString(8, "0 seconds");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString("pid");
                            rs.getLong("sn");
                        }
                    }
                });
                final String mid = lowerBounds.get(lowerBounds.size() / 2);
                plans.append("### PG plan (mid-corpus lower bound)\n\n").append(BenchProtocol.pgExplain(c,
                        BenchProtocol.inlineLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE,
                                mid, "", "", true, "0 seconds", PersistenceShapes.READS_PER_QUERY,
                                true, "0 seconds")));
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                final MongoCollection<Document> snaps = db.getCollection(PersistenceShapes.SNAPS);
                mongoT = BenchProtocol.measure(iteration -> snaps
                        .aggregate(PersistenceShapes.newestSnapshotsPipeline(
                                lowerBounds.get(iteration % lowerBounds.size()),
                                PersistenceShapes.READS_PER_QUERY))
                        .forEach(doc -> doc.getList("i", Document.class).size()));
                plans.append("\n### Mongo plan (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.mongoExplainAggregate(db, PersistenceShapes.SNAPS,
                                PersistenceShapes.newestSnapshotsPipeline(
                                        lowerBounds.get(lowerBounds.size() / 2),
                                        PersistenceShapes.READS_PER_QUERY)));
            }
        }
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("c1-pid-stream-page"),
                BenchProtocol.runHeader("C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)")
                        + RecoveryBench.resultTable(List.of(
                                RecoveryBench.sideBySide("one page, varying lower bound", pgT, mongoT)))
                        + "\n" + plans
                        + "\nCleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), "
                        + "minAge=0 (the bench's history-retention pin: age predicates disabled; production "
                        + "things cleanup passes history-retention-duration, default 3d — see the C1 retention "
                        + "variant), pidFilter='' — what Cleanup.java passes with that pin. PG statement: "
                        + "PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE, the payload-free page production issues for "
                        + "Cleanup since 2026-10-23; c1 evidence written before then timed the page with the "
                        + "payload.\n");
    }

    /**
     * C1 with the production min age. Cleanup passes history-retention-duration as minAgeFromNow
     * (things.conf default 3d), which turns the age predicate on in both steps of the PG query and
     * adds an {@code _id} bound to the Mongo {@code $match}. Run it on a corpus loaded with a fresh
     * share ({@code -Dbench.freshShare=0.3}): on the backdated corpus every row qualifies and the
     * variant measures predicate overhead only. Both variants (minAge 0 and the retention) run
     * interleaved on the same lower bounds; every page is then checked against
     * {@link NewestSnapshotOracle}, and the corpus's age mix against the generator — the run fails
     * (after writing its evidence) if either differs. One evidence file per backend selection.
     */
    @Test
    public void c1PidStreamPageWithRetention() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final Duration retention = BenchConfig.c1Retention();
        final String spec = BenchConfig.c1RetentionSpec();
        final NewestSnapshotOracle oracle = NewestSnapshotOracle.of(gen);
        final List<String> lowerBounds = c1LowerBounds(gen);
        final String mid = lowerBounds.get(lowerBounds.size() / 2);
        final List<String> failures = new ArrayList<>();
        final StringBuilder body = new StringBuilder();

        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg();
                    PreparedStatement ps = c.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)) {
                final String gate = awaitPgQuiet(c);
                final String vacuumsBefore = pgVacuumsInProgress(c);
                final long[] mix = pgAgeMix(c, retention);
                final BenchProtocol.Timing[] t = BenchProtocol.measurePair(
                        BenchConfig.warmups(), BenchConfig.iterations(),
                        iteration -> pgPage(ps, lowerBounds.get(iteration % lowerBounds.size()), Duration.ZERO,
                                PersistenceShapes.READS_PER_QUERY),
                        iteration -> pgPage(ps, lowerBounds.get(iteration % lowerBounds.size()), retention,
                                PersistenceShapes.READS_PER_QUERY));
                final String vacuumsAfter = pgVacuumsInProgress(c);
                final String pages = checkPages("PG", lowerBounds, retention, failures,
                        (lb, minAge) -> pgPage(ps, lb, minAge, PersistenceShapes.READS_PER_QUERY),
                        (lb, ageFilter) -> oracle.pgPage(lb, ageFilter, PersistenceShapes.READS_PER_QUERY));
                body.append("## PostgreSQL\n\n")
                        .append(variantTable(spec, t))
                        .append('\n').append(pages)
                        .append(ageMixLine("PG", spec, mix, oracle, failures))
                        .append("- quiet-server gate before timing: ").append(gate).append('\n')
                        .append("- vacuums in progress at timing start: ").append(vacuumsBefore)
                        .append("; at timing end: ").append(vacuumsAfter).append("\n\n")
                        .append(PgLoadBench.sizesMarkdown(c))
                        .append("\n### PG plan — minAge 0 (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.pgExplain(c, pgPageLiterals(mid, Duration.ZERO)))
                        .append("\n### PG plan — retention ").append(spec).append(" (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.pgExplain(c, pgPageLiterals(mid, retention)))
                        .append('\n');
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                final MongoCollection<Document> snaps = db.getCollection(PersistenceShapes.SNAPS);
                final long[] mix = mongoAgeMix(snaps, retention, Instant.now());
                final BenchProtocol.Timing[] t = BenchProtocol.measurePair(
                        BenchConfig.warmups(), BenchConfig.iterations(),
                        iteration -> mongoPage(snaps, lowerBounds.get(iteration % lowerBounds.size()),
                                Duration.ZERO, PersistenceShapes.READS_PER_QUERY),
                        iteration -> mongoPage(snaps, lowerBounds.get(iteration % lowerBounds.size()),
                                retention, PersistenceShapes.READS_PER_QUERY));
                final String pages = checkPages("Mongo", lowerBounds, retention, failures,
                        (lb, minAge) -> mongoPage(snaps, lb, minAge, PersistenceShapes.READS_PER_QUERY),
                        (lb, ageFilter) -> oracle.mongoPage(lb, ageFilter, PersistenceShapes.READS_PER_QUERY));
                body.append("## MongoDB\n\n")
                        .append(variantTable(spec, t))
                        .append('\n').append(pages)
                        .append(ageMixLine("Mongo", spec, mix, oracle, failures)).append('\n')
                        .append(MongoLoadBench.sizesMarkdown(db))
                        .append("\n### Mongo plan — minAge 0 (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.mongoExplainAggregate(db, PersistenceShapes.SNAPS,
                                PersistenceShapes.newestSnapshotsPipeline(mid, PersistenceShapes.READS_PER_QUERY,
                                        Duration.ZERO, Instant.now())))
                        .append("\n### Mongo plan — retention ").append(spec)
                        .append(" (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.mongoExplainAggregate(db, PersistenceShapes.SNAPS,
                                PersistenceShapes.newestSnapshotsPipeline(mid, PersistenceShapes.READS_PER_QUERY,
                                        retention, Instant.now())))
                        .append('\n');
            }
        }

        final String backendSuffix = "both".equals(BenchConfig.backend()) ? "" : "-" + BenchConfig.backend();
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("c1-pid-stream-page-retention" + spec + backendSuffix),
                BenchProtocol.runHeader("C1 — cleanup pid-stream page with the production min age (retention "
                        + spec + " vs 0, batch 100)")
                        + BenchProtocol.freshCorpusLine(gen)
                        + (failures.isEmpty() ? "" : "**RESULT CHECK FAILED — do not quote these numbers:**\n"
                        + failures.stream().map(f -> "- " + f + "\n").reduce("", String::concat) + "\n")
                        + body
                        + "What is compared: the binds Cleanup.java passes (includeDeleted=true, pidFilter='', "
                        + "minAgeFromNow = history-retention-duration). PG "
                        + "(PostgresPersistenceOperations.getNewestSnapshotHeadsAbove, the payload-free page): a "
                        + "non-zero min age sets the "
                        + "no-age-filter flag to false, so 'written_at < now() - interval' filters rows in BOTH "
                        + "steps (step 1: a pid with no old-enough row takes no page slot; step 2: the newest "
                        + "old-enough row wins); the page is " + PersistenceShapes.READS_PER_QUERY
                        + " distinct pids. Mongo (MongoReadJournal.toMongoFilter): a non-zero min age ANDs "
                        + "'_id < ObjectId.getSmallestWithDate(now - minAge)' to the pid bound in $match, before "
                        + "$sort/$limit/$group; $limit counts snapshot ROWS, so a Mongo page spans fewer pids. "
                        + "written_at and the _id timestamp are both the snapshot's write time (corpus snapshot "
                        + "_ids embed it). Timing: the two variants interleaved per lower bound, alternating "
                        + "which runs first.\n");
        if (!failures.isEmpty()) {
            throw new AssertionError("C1 retention result check failed: " + failures);
        }
    }

    /**
     * Times the PG C1 cleanup page before the payload-free change
     * ({@link PersistenceShapes#PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD}) against the production page
     * ({@link PersistenceShapes#PG_NEWEST_SNAPSHOTS_ABOVE}), interleaved per lower bound
     * ({@link BenchProtocol#measurePair}), at the C1 retention ({@code bench.c1.retention}, default 3d) and at minAge 0
     * (in the order of {@link #pairOrder}): what the change saves. A third pair, at the retention, reads the statement
     * before the change as the bench client does (payload transferred, not decoded) and decoded and JSON-parsed as
     * PostgresReadJournal does for a stream with fields: a bench-side estimate of that client cost. PostgreSQL only;
     * {@code bench.evidenceLabel} numbers the runs of a series. After timing, every page of both statements is checked
     * against {@link NewestSnapshotOracle}; the run fails (after writing its evidence) if a page or the age mix
     * differs, if the quiet-server gate timed out, or if a vacuum was running when timing started. The server cache
     * state before timing follows {@code bench.c1.cacheState} ({@link CacheState}); the evidence records what the
     * timed statements read into shared buffers and how long those reads took.
     */
    @Test
    public void c1PayloadPair() throws Exception {
        if (!"pg".equals(BenchConfig.backend())) {
            throw new IllegalStateException("the C1 payload pair is PostgreSQL only: run it with --backend pg");
        }
        final CorpusGenerator gen = BenchConfig.corpus();
        final Duration retention = BenchConfig.c1Retention();
        final String spec = BenchConfig.c1RetentionSpec();
        if (retention.isZero()) {
            throw new IllegalArgumentException("the payload pair compares a non-zero retention with minAge 0: "
                    + spec);
        }
        final CacheState cacheState = BenchConfig.c1CacheState();
        final int limit = PersistenceShapes.READS_PER_QUERY;
        final NewestSnapshotOracle oracle = NewestSnapshotOracle.of(gen);
        final List<String> lowerBounds = c1LowerBounds(gen);
        final IntFunction<String> lbAt = iteration -> lowerBounds.get(iteration % lowerBounds.size());
        final String mid = lowerBounds.get(lowerBounds.size() / 2);
        final List<String> failures = new ArrayList<>();
        final StringBuilder body = new StringBuilder("## PostgreSQL\n\n");

        try (Connection c = BenchConfig.openPg();
                PreparedStatement withPayload =
                        c.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD);
                PreparedStatement production = c.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)) {
            try (Statement st = c.createStatement()) {
                st.execute("SET track_io_timing = on");   // this session only: blk_read_time and EXPLAIN I/O timings
            }
            final String gate = awaitPgQuiet(c);
            if (!gate.startsWith("quiet")) {
                failures.add("the quiet-server gate timed out: " + gate);
            }
            final String vacuumsBefore = pgVacuumsInProgress(c);
            if (!"none".equals(vacuumsBefore)) {
                failures.add("a vacuum was in progress at timing start: " + vacuumsBefore);
            }
            final String serverStart = pgServerStart(c);
            // nothing but the statements under test reads things_snaps between the two I/O snapshots: the age mix
            // (a full scan of things_snaps that would warm the heap) is read after timing
            final String pass = cacheState == CacheState.WARM ? pgWarmPass(c, withPayload, limit).line() : "none";
            final PgIo ioBefore = pgIo(c);
            final List<List<String>> rows = new ArrayList<>();
            for (final Duration minAge : pairOrder(cacheState, retention)) {
                final BenchProtocol.Timing[] t = BenchProtocol.measurePair(
                        BenchConfig.warmups(), BenchConfig.iterations(),
                        iteration -> pgPage(withPayload, lbAt.apply(iteration), minAge, limit),
                        iteration -> pgPage(production, lbAt.apply(iteration), minAge, limit));
                rows.add(List.of(minAgeLabel(spec, minAge), t[0].cells(), t[1].cells(), payloadShare(t[0], t[1])));
            }
            final BenchProtocol.Timing[] decode = BenchProtocol.measurePair(
                    BenchConfig.warmups(), BenchConfig.iterations(),
                    iteration -> pgPage(withPayload, lbAt.apply(iteration), retention, limit),
                    iteration -> pgPageDecoded(withPayload, lbAt.apply(iteration), retention, limit));
            final PgIo ioDuring = pgIo(c).minus(ioBefore);
            final String vacuumsAfter = pgVacuumsInProgress(c);
            cacheStateCheck(cacheState, ioDuring).ifPresent(failures::add);
            final long[] mix = pgAgeMix(c, retention);

            final String pagesWith = checkPages("PG statement before the change", lowerBounds, retention, failures,
                    (lb, minAge) -> pgPage(withPayload, lb, minAge, limit),
                    (lb, ageFilter) -> oracle.pgPage(lb, ageFilter, limit));
            final String pagesProduction = checkPages("PG production page", lowerBounds, retention, failures,
                    (lb, minAge) -> pgPage(production, lb, minAge, limit),
                    (lb, ageFilter) -> oracle.pgPage(lb, ageFilter, limit));
            body.append(BenchProtocol.table(List.of("min age (one page, varying lower bound)",
                            "statement before the change, with payload: p50 | p95 | max (ms)",
                            "production page, payload-free: p50 | p95 | max (ms)",
                            "payload share of the page before the change: p50 / p95"), rows))
                    .append('\n')
                    .append(BenchProtocol.table(List.of("statement before the change at "
                                    + minAgeLabel(spec, retention) + ", client handling", "p50 | p95 | max (ms)"),
                            List.of(List.of("payload transferred, not decoded (the C1 bench client)",
                                            decode[0].cells()),
                                    List.of("payload decoded and JSON-parsed as PostgresReadJournal does for a "
                                            + "stream with fields (pgjdbc estimate)", decode[1].cells()))))
                    .append('\n')
                    .append(pagesWith.replace("- result check", "- statement before the change: result check"))
                    .append(pagesProduction.replace("- result check", "- production page: result check"))
                    .append(payloadLines(c, lowerBounds, retention, spec, limit))
                    .append(ageMixLine("PG", spec, mix, oracle, failures,
                            "read after timing (its full scan of things_snaps would warm the heap before a cold run)"))
                    .append("- cache state: ").append(cacheState.description).append("; server started ")
                    .append(serverStart).append("; warm pass before timing: ").append(pass).append('\n')
                    .append("- things_snaps blocks during timing (pg_statio_user_tables; read = not in shared buffers; "
                            + "read time = pg_stat_database.blk_read_time of this session's reads, track_io_timing "
                            + "on): ").append(ioDuring.line()).append('\n')
                    .append("- quiet-server gate before timing: ").append(gate).append('\n')
                    .append("- vacuums in progress at timing start: ").append(vacuumsBefore)
                    .append("; at timing end: ").append(vacuumsAfter).append('\n')
                    .append("- server settings: ").append(PgVacuumProbe.settings(c)).append('\n')
                    .append("- storage parameters: ").append(PgVacuumProbe.reloptions(c)).append("\n\n")
                    .append(PgLoadBench.sizesMarkdown(c));
            for (final Duration minAge : List.of(retention, Duration.ZERO)) {
                body.append("\n### PG plan — production page (payload-free), ").append(minAgeLabel(spec, minAge))
                        .append(" (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.pgExplain(c,
                                pgPageLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE, mid, minAge)))
                        .append("\n### PG plan — statement before the change (with payload), ")
                        .append(minAgeLabel(spec, minAge)).append(" (mid-corpus lower bound)\n\n")
                        .append(BenchProtocol.pgExplain(c,
                                pgPageLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD, mid,
                                        minAge)));
            }
            body.append(literalStatements(spec, mid, retention));
        }

        BenchProtocol.writeEvidence(payloadPairEvidenceName(spec, cacheState, BenchConfig.evidenceLabel()),
                BenchProtocol.runHeader("C1 payload pair — the PG cleanup page before the payload-free change and "
                        + "the production page (retention " + spec + " and 0, batch 100)")
                        + BenchProtocol.freshCorpusLine(gen)
                        + (failures.isEmpty() ? "" : "**RESULT CHECK FAILED — do not quote these numbers:**\n"
                        + failures.stream().map(f -> "- " + f + "\n").reduce("", String::concat) + "\n")
                        + body
                        + "\nWhat is compared: the production page is the statement "
                        + "PostgresPersistenceOperations.getNewestSnapshotHeadsAbove issues for Cleanup "
                        + "(includeDeleted=true, pidFilter='', minAgeFromNow = history-retention-duration; a stream "
                        + "without snapshot fields), transcribed as PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE: no "
                        + "snapshot column. The statement before the change, "
                        + "PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD, is that SQL plus the outer "
                        + "s.snapshot and the lateral snapshot::text (Cleanup issued it until 2026-10-23; production "
                        + "still issues it for a stream with fields): the same binds and plan nodes. "
                        + "Both are read through pgjdbc taking pid and sn only; the decoded variant also reads "
                        + "every payload of the statement before the change as a String and parses it with "
                        + "JsonFactory.newObject, as PostgresReadJournal.toSnapshotEntry does (an estimate: "
                        + "production reads through r2dbc). Timing: each pair interleaved per lower bound, "
                        + "alternating which runs first; pairs in the order retention, minAge 0, decode (cold: minAge "
                        + "0, retention, decode, as only the first pair meets cold shared buffers; the later pairs "
                        + "re-read the same lower bounds); result checks, payload sizes, plans and statements after "
                        + "timing. MongoDB is not run: its cleanup pipeline projects "
                        + "the payload away (sn and lifecycle only). Cache state (bench.c1.cacheState): as is = no "
                        + "preparation; cold = the server was recreated right before the stage (run-benchmark.sh "
                        + "recreate), so shared buffers started empty while the VM's page cache is not controlled, "
                        + "and the run fails unless timing read blocks; warm = one untimed pass over the whole pid "
                        + "stream with the statement before the change first. The things_snaps block line counts "
                        + "what the timed statements read into shared buffers (pg_statio_user_tables) and the time "
                        + "those reads took (pg_stat_database.blk_read_time, track_io_timing on in this session).\n");
        if (!failures.isEmpty()) {
            throw new AssertionError("C1 payload pair result check failed: " + failures);
        }
    }

    interface PageQuery {
        List<NewestSnapshotOracle.PidSn> run(String lowerBound, Duration minAge) throws Exception;
    }

    interface ExpectedPage {
        List<NewestSnapshotOracle.PidSn> of(String lowerBound, boolean ageFilter);
    }

    /** Runs every lower bound once per variant (untimed) and compares with the oracle. */
    private static String checkPages(final String backend, final List<String> lowerBounds, final Duration retention,
            final List<String> failures, final PageQuery query, final ExpectedPage expected) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (final Duration minAge : List.of(Duration.ZERO, retention)) {
            int matching = 0;
            final List<Integer> sizes = new ArrayList<>();
            for (final String lb : lowerBounds) {
                final List<NewestSnapshotOracle.PidSn> actual = query.run(lb, minAge);
                sizes.add(actual.size());
                if (actual.equals(expected.of(lb, !minAge.isZero()))) {
                    matching++;
                } else {
                    failures.add(backend + " minAge " + minAge + ": page above '" + lb + "' differs from the "
                            + "generator's expected page");
                }
            }
            sb.append(String.format("- result check, minAge %s: %d of %d pages equal the generator's expected "
                            + "page; pids per page min/mean/max %d / %.1f / %d%n", minAge, matching,
                    lowerBounds.size(), sizes.stream().mapToInt(Integer::intValue).min().orElse(0),
                    sizes.stream().mapToInt(Integer::intValue).average().orElse(0.0),
                    sizes.stream().mapToInt(Integer::intValue).max().orElse(0)));
        }
        return sb.toString();
    }

    private static String variantTable(final String spec, final BenchProtocol.Timing[] t) {
        return BenchProtocol.table(List.of("variant (one page, varying lower bound)", "p50 | p95 | max (ms)"),
                List.of(List.of("minAge 0 (age filter off — the committed C1 shape)", t[0].cells()),
                        List.of("retention " + spec + " (production min age)", t[1].cells())));
    }

    private static String ageMixLine(final String backend, final String spec, final long[] mix,
            final NewestSnapshotOracle oracle, final List<String> failures) {
        return ageMixLine(backend, spec, mix, oracle, failures, "at timing start");
    }

    private static String ageMixLine(final String backend, final String spec, final long[] mix,
            final NewestSnapshotOracle oracle, final List<String> failures, final String when) {
        if (mix[0] != oracle.snapshottedPids() || mix[1] != oracle.freshOnlySnapshottedPids()) {
            failures.add(backend + " age mix differs from the generator: " + mix[0] + " snapshotted / " + mix[1]
                    + " without an old-enough row vs expected " + oracle.snapshottedPids() + " / "
                    + oracle.freshOnlySnapshottedPids());
        }
        return "- age mix " + when + ": " + mix[0] + " pids with snapshots (expected " + oracle.snapshottedPids()
                + "), " + mix[1] + " of them without a snapshot older than " + spec + " (expected "
                + oracle.freshOnlySnapshottedPids() + ")\n";
    }

    private static String minAgeLabel(final String spec, final Duration minAge) {
        return minAge.isZero() ? "minAge 0" : "retention " + spec;
    }

    /** "p50 / p95" share of the page time the payload accounts for: 1 - without / with, whole percent. */
    static String payloadShare(final BenchProtocol.Timing withPayload, final BenchProtocol.Timing withoutPayload) {
        return String.format(Locale.ROOT, "%.0f %% / %.0f %%",
                100.0 * (1.0 - withoutPayload.p50Ms() / withPayload.p50Ms()),
                100.0 * (1.0 - withoutPayload.p95Ms() / withPayload.p95Ms()));
    }

    /**
     * The state word sits before the label (asis: none). Runs before the payload-free change wrote
     * {@code c1-payload-free-…} files under the same rule.
     */
    static String payloadPairEvidenceName(final String retentionSpec, final CacheState state, final String label) {
        final String base = "c1-payload-pair-retention" + retentionSpec + "-pg";
        return BenchProtocol.labelled(state == CacheState.ASIS ? base : base + "-" + state.word, label) + ".md";
    }

    /** Untimed: payload kB per page of the statement before the change over all lower bounds, per min age. */
    private static String payloadLines(final Connection c, final List<String> lowerBounds, final Duration retention,
            final String spec, final int limit) throws SQLException {
        final StringBuilder sb = new StringBuilder();
        for (final Duration minAge : List.of(retention, Duration.ZERO)) {
            final List<Long> bytes = new ArrayList<>(lowerBounds.size());
            for (final String lb : lowerBounds) {
                bytes.add(pgPagePayloadBytes(c, lb, minAge, limit)[1]);
            }
            final LongSummaryStatistics s = bytes.stream().mapToLong(Long::longValue).summaryStatistics();
            sb.append(String.format(Locale.ROOT, "- payload per page of the statement before the change "
                            + "(octet_length of snapshot::text, untimed), %s: min/mean/max %.0f / %.0f / %.0f kB%n",
                    minAgeLabel(spec, minAge),
                    s.getMin() / 1024.0, s.getAverage() / 1024.0, s.getMax() / 1024.0));
        }
        return sb.toString();
    }

    /** The four timed statements with literals at the mid-corpus lower bound, for EXPLAIN on another server. */
    private static String literalStatements(final String spec, final String mid, final Duration retention) {
        final StringBuilder sb = new StringBuilder("\n### Statements with literals (mid-corpus lower bound)\n");
        for (final Duration minAge : List.of(retention, Duration.ZERO)) {
            sb.append("\nproduction page, ").append(minAgeLabel(spec, minAge)).append(":\n\n```sql\n")
                    .append(pgPageLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE, mid, minAge))
                    .append(";\n```\n")
                    .append("\nstatement before the change, ").append(minAgeLabel(spec, minAge))
                    .append(":\n\n```sql\n")
                    .append(pgPageLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD, mid, minAge))
                    .append(";\n```\n");
        }
        return sb.toString();
    }

    /**
     * Binds Cleanup's page: the binds of PostgresPersistenceOperations.getNewestSnapshotHeadsAbove (the same as
     * getNewestSnapshotsAbove's) for this min age.
     */
    static void bindPgPage(final PreparedStatement ps, final String lowerBound, final Duration minAge,
            final int limit) throws SQLException {
        final boolean noAgeFilter = minAge.isZero();
        final String interval = minAge.toSeconds() + " seconds";
        ps.setString(1, lowerBound);
        ps.setString(2, "");                 // no pid regex
        ps.setString(3, "");
        ps.setBoolean(4, noAgeFilter);
        ps.setString(5, interval);
        ps.setInt(6, limit);
        ps.setBoolean(7, noAgeFilter);       // the same age predicate again in step 2
        ps.setString(8, interval);
    }

    /**
     * One PG cleanup page as (pid, sn); with the statement before the change the payload columns are transferred but
     * never decoded or parsed.
     */
    static List<NewestSnapshotOracle.PidSn> pgPage(final PreparedStatement ps, final String lowerBound,
            final Duration minAge, final int limit) throws SQLException {
        bindPgPage(ps, lowerBound, minAge, limit);
        final List<NewestSnapshotOracle.PidSn> page = new ArrayList<>(limit);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                page.add(new NewestSnapshotOracle.PidSn(rs.getString("pid"), rs.getLong("sn")));
            }
        }
        return page;
    }

    /**
     * One PG cleanup page of the statement before the change read with its payload: every payload read as a String
     * and parsed with {@code JsonFactory.newObject}, as PostgresReadJournal.toSnapshotEntry does for a stream with
     * fields (and did for Cleanup before the payload-free change). Through pgjdbc, not r2dbc: a bench-side estimate of
     * that client cost, not a production number.
     */
    static List<NewestSnapshotOracle.PidSn> pgPageDecoded(final PreparedStatement ps, final String lowerBound,
            final Duration minAge, final int limit) throws SQLException {
        bindPgPage(ps, lowerBound, minAge, limit);
        final List<NewestSnapshotOracle.PidSn> page = new ArrayList<>(limit);
        long fields = 0;
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fields += JsonFactory.newObject(rs.getString("snapshot")).getSize();
                page.add(new NewestSnapshotOracle.PidSn(rs.getString("pid"), rs.getLong("sn")));
            }
        }
        parsedFields += fields;
        return page;
    }

    /**
     * Untimed: [rows, payload bytes] of one page of the statement before the change (octet_length of
     * snapshot::text).
     */
    static long[] pgPagePayloadBytes(final Connection c, final String lowerBound, final Duration minAge,
            final int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) AS n, "
                + "coalesce(sum(octet_length(snapshot)), 0) AS bytes FROM ("
                + PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD + ") page")) {
            bindPgPage(ps, lowerBound, minAge, limit);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong("n"), rs.getLong("bytes")};
            }
        }
    }

    private static String pgPageLiterals(final String lowerBound, final Duration minAge) {
        return pgPageLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE, lowerBound, minAge);
    }

    private static String pgPageLiterals(final String sql, final String lowerBound, final Duration minAge) {
        final String interval = minAge.toSeconds() + " seconds";
        return BenchProtocol.inlineLiterals(sql, lowerBound, "", "",
                minAge.isZero(), interval, PersistenceShapes.READS_PER_QUERY, minAge.isZero(), interval);
    }

    /** One Mongo cleanup page as (pid, sn): the items of the single $group-by-null result document. */
    static List<NewestSnapshotOracle.PidSn> mongoPage(final MongoCollection<Document> snaps, final String lowerBound,
            final Duration minAge, final int limit) {
        final List<NewestSnapshotOracle.PidSn> page = new ArrayList<>();
        snaps.aggregate(PersistenceShapes.newestSnapshotsPipeline(lowerBound, limit, minAge, Instant.now()))
                .forEach(doc -> doc.getList("i", Document.class).forEach(item ->
                        page.add(new NewestSnapshotOracle.PidSn(item.getString("_id"), item.getLong("sn")))));
        return page;
    }

    /** [pids with snapshots, of them pids without a row older than minAge], as PG sees them now. */
    static long[] pgAgeMix(final Connection c, final Duration minAge) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) AS pids, "
                + "count(*) FILTER (WHERE NOT qualifies) AS fresh_only FROM ("
                + "SELECT pid, bool_or(written_at < now() - ?::interval) AS qualifies "
                + "FROM things_snaps GROUP BY pid) t")) {
            ps.setString(1, minAge.toSeconds() + " seconds");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong("pids"), rs.getLong("fresh_only")};
            }
        }
    }

    /** The same as {@link #pgAgeMix} on Mongo, with the min-age bound MongoReadJournal puts on {@code _id}. */
    static long[] mongoAgeMix(final MongoCollection<Document> snaps, final Duration minAge, final Instant now) {
        final long pids = count(snaps.distinct("pid", String.class));
        final long qualifying = count(snaps.distinct("pid",
                Filters.lt("_id", ObjectId.getSmallestWithDate(Date.from(now.minus(minAge)))), String.class));
        return new long[]{pids, pids - qualifying};
    }

    private static long count(final Iterable<?> values) {
        long n = 0;
        for (final Object ignored : values) {
            n++;
        }
        return n;
    }

    /**
     * Waits, bounded by bench.c1.vacuumTimeoutSeconds, until things_journal and things_snaps have each been vacuumed
     * since the load (the step-1 index-only scan then runs on a set visibility map, as autovacuum keeps it in
     * production, and the insert-triggered things_journal autovacuum after a load is over) and no vacuum is in
     * progress.
     *
     * @return "quiet after N s (…)" or "TIMED OUT after N s (…)", with the last observed state.
     */
    private static String awaitPgQuiet(final Connection c) throws Exception {
        final long start = System.nanoTime();
        final long deadline = start + BenchConfig.c1VacuumTimeoutSeconds() * 1_000_000_000L;
        String notVacuumed = pgTablesNotYetVacuumed(c);
        String running = pgVacuumsInProgress(c);
        while (!isQuiet(notVacuumed, running) && System.nanoTime() < deadline) {
            Thread.sleep(5_000L);
            notVacuumed = pgTablesNotYetVacuumed(c);
            running = pgVacuumsInProgress(c);
        }
        return (isQuiet(notVacuumed, running) ? "quiet" : "TIMED OUT") + " after "
                + (System.nanoTime() - start) / 1_000_000_000L + " s (not yet vacuumed: " + notVacuumed
                + "; vacuums in progress: " + running + "); things_snaps: " + pgSnapsVacuumState(c);
    }

    static boolean isQuiet(final String tablesNotYetVacuumed, final String vacuumsInProgress) {
        return "none".equals(tablesNotYetVacuumed) && "none".equals(vacuumsInProgress);
    }

    /** "none", or the corpus tables (of things_journal, things_snaps) never vacuumed since they were created. */
    private static String pgTablesNotYetVacuumed(final Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(string_agg(t.relname, ', ' "
                + "ORDER BY t.relname), 'none') FROM unnest(ARRAY['things_journal', 'things_snaps']) AS t(relname) "
                + "LEFT JOIN pg_stat_user_tables s ON s.relname = t.relname "
                + "WHERE s.relid IS NULL OR (s.last_vacuum IS NULL AND s.last_autovacuum IS NULL)");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String pgSnapsVacuumState(final Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT s.last_vacuum, s.last_autovacuum, "
                + "cl.relallvisible, cl.relpages FROM pg_stat_user_tables s "
                + "JOIN pg_class cl ON cl.oid = s.relid WHERE s.relname = 'things_snaps'");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return "never vacuumed (no things_snaps statistics)";
            }
            final String vacuumed = rs.getString("last_vacuum");
            final String autovacuumed = rs.getString("last_autovacuum");
            final String pages = "relallvisible " + rs.getLong("relallvisible") + " of relpages " + rs.getLong("relpages");
            return vacuumed == null && autovacuumed == null
                    ? "never vacuumed; " + pages
                    : "last_vacuum " + vacuumed + ", last_autovacuum " + autovacuumed + "; " + pages;
        }
    }

    private static String pgVacuumsInProgress(final Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(string_agg(cl.relname || ' (' || p.phase "
                + "|| ')', ', '), 'none') FROM pg_stat_progress_vacuum p LEFT JOIN pg_class cl ON cl.oid = p.relid");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    public void c2SinglePidCleanup() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        // scan from mid-corpus: disjoint from RecoveryBench's pids (which scan from 0) at full scale
        final List<Long> p99 = gen.findPidIndexes(CorpusGenerator.PidClass.P99, 23, gen.pidCount() / 2);
        final List<Long> outlier = gen.findPidIndexes(CorpusGenerator.PidClass.OUTLIER, 23, gen.pidCount() / 2);

        final List<List<String>> rows = new ArrayList<>();
        final StringBuilder notes = new StringBuilder();
        final StringBuilder plans = new StringBuilder();
        for (final Object[] entry : List.of(new Object[]{"P99", p99}, new Object[]{"OUTLIER", outlier})) {
            final String label = (String) entry[0];
            @SuppressWarnings("unchecked") final List<Long> found = (List<Long>) entry[1];
            // adaptive protocol: each pid is cleaned exactly once (a re-clean would be a no-op and distort)
            final int warm = Math.min(BenchConfig.warmups(), Math.max(0, found.size() / 4));
            final int timed = found.size() - warm;
            notes.append(String.format("- %s: %d pids found (%d warmup + %d timed)%n",
                    label, found.size(), warm, timed));

            CleanupResultPair pgR = null;
            CleanupResultPair mongoR = null;
            // one plan per class and backend: on the first warm-up pid before its (untimed) cleanup; without a warm-up
            // pid (tiny corpora) after the loop on the last pid, whose backlog is gone by then
            final boolean planBefore = warm > 0;
            final long planIdx = found.isEmpty() ? -1L : (planBefore ? found.get(0) : found.get(found.size() - 1));
            final String planWhen = planBefore ? "first warm-up pid, before its cleanup"
                    : "last pid, after its cleanup (no warm-up pid at this scale)";
            if (BenchConfig.runPg()) {
                try (Connection c = BenchConfig.openPg()) {
                    String plan = planIdx >= 0 && planBefore
                            ? ScenarioPlans.pgCleanupPid(c, gen.pid(planIdx), gen.latestSnapshotSn(planIdx)) : null;
                    pgR = pgCleanupClass(c, gen, found, warm);
                    if (planIdx >= 0 && !planBefore) {
                        plan = ScenarioPlans.pgCleanupPid(c, gen.pid(planIdx), gen.latestSnapshotSn(planIdx));
                    }
                    if (plan != null) {
                        plans.append("### PG plan (").append(label).append(", ").append(planWhen)
                                .append("; deletes rolled back)\n\n").append(plan).append('\n');
                    }
                }
            }
            if (BenchConfig.runMongo()) {
                try (MongoClient client = BenchConfig.openMongo()) {
                    final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                    String plan = planIdx >= 0 && planBefore
                            ? ScenarioPlans.mongoCleanupPid(db, gen.pid(planIdx), gen.latestSnapshotSn(planIdx)) : null;
                    mongoR = mongoCleanupClass(db, gen, found, warm);
                    if (planIdx >= 0 && !planBefore) {
                        plan = ScenarioPlans.mongoCleanupPid(db, gen.pid(planIdx), gen.latestSnapshotSn(planIdx));
                    }
                    if (plan != null) {
                        plans.append("### Mongo plan (").append(label).append(", ").append(planWhen)
                                .append("; explain deletes nothing)\n\n").append(plan).append('\n');
                    }
                }
            }
            rows.add(RecoveryBench.sideBySide(label + " total per-pid",
                    pgR == null ? null : pgR.totals(), mongoR == null ? null : mongoR.totals()));
            rows.add(RecoveryBench.sideBySide(label + " per delete batch (100 rows)",
                    pgR == null ? null : pgR.batches(), mongoR == null ? null : mongoR.batches()));
        }
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("c2-single-pid-cleanup"),
                BenchProtocol.runHeader("C2 — single-pid cleanup (batched deletes, batch size 100)")
                        + RecoveryBench.resultTable(rows) + "\n" + notes
                        + "\nSequence per pid (Cleanup.java 1:1): min event sn -> event ranges tiling to "
                        + "snapshotSn-1 -> min snapshot sn -> snapshot ranges keeping the latest. "
                        + "No journal_seq.deleted_to update — the real cleanup path does not touch it "
                        + "(plan deviation 2). "
                        + "C2 consumes these pids' cleanup backlog; rerun 'load' for a pristine corpus.\n"
                        + "\n" + plans);
    }

    record CleanupResultPair(BenchProtocol.Timing totals, BenchProtocol.Timing batches) {}

    private static CleanupResultPair pgCleanupClass(final Connection c, final CorpusGenerator gen,
            final List<Long> pidIndexes, final int warmups) throws Exception {
        final List<Double> totals = new ArrayList<>();
        final List<Double> batchLatencies = new ArrayList<>();
        try (PreparedStatement minEv = c.prepareStatement(PersistenceShapes.PG_MIN_EVENT_SN);
                PreparedStatement minSnap = c.prepareStatement(PersistenceShapes.PG_MIN_SNAP_SN);
                PreparedStatement delEv = c.prepareStatement(PersistenceShapes.PG_DELETE_EVENTS);
                PreparedStatement delSnap = c.prepareStatement(PersistenceShapes.PG_DELETE_SNAPS)) {
            for (int i = 0; i < pidIndexes.size(); i++) {
                final long idx = pidIndexes.get(i);
                final String pid = gen.pid(idx);
                final long snapshotSn = gen.latestSnapshotSn(idx);
                final boolean timed = i >= warmups;
                final long t0 = System.nanoTime();
                final Long evMin = queryMin(minEv, pid);
                if (evMin != null) {
                    for (final CleanupPlanner.Range r : CleanupPlanner.eventDeleteRanges(
                            evMin, snapshotSn, PersistenceShapes.WRITES_PER_CREDIT)) {
                        final long b0 = System.nanoTime();
                        delEv.setString(1, pid);
                        delEv.setLong(2, r.fromSn());
                        delEv.setLong(3, r.toSn());
                        delEv.executeUpdate();
                        if (timed) {
                            batchLatencies.add((System.nanoTime() - b0) / 1_000_000.0);
                        }
                    }
                }
                final Long snMin = queryMin(minSnap, pid);
                if (snMin != null) {
                    for (final CleanupPlanner.Range r : CleanupPlanner.snapshotDeleteRanges(
                            snMin, snapshotSn, false, false, PersistenceShapes.WRITES_PER_CREDIT)) {
                        final long b0 = System.nanoTime();
                        delSnap.setString(1, pid);
                        delSnap.setLong(2, r.fromSn());
                        delSnap.setLong(3, r.toSn());
                        delSnap.executeUpdate();
                        if (timed) {
                            batchLatencies.add((System.nanoTime() - b0) / 1_000_000.0);
                        }
                    }
                }
                if (timed) {
                    totals.add((System.nanoTime() - t0) / 1_000_000.0);
                }
            }
        }
        return new CleanupResultPair(BenchProtocol.of(totals), BenchProtocol.of(batchLatencies));
    }

    private static CleanupResultPair mongoCleanupClass(final MongoDatabase db, final CorpusGenerator gen,
            final List<Long> pidIndexes, final int warmups) {
        final MongoCollection<Document> journal = db.getCollection(PersistenceShapes.JOURNAL);
        final MongoCollection<Document> snaps = db.getCollection(PersistenceShapes.SNAPS);
        final List<Double> totals = new ArrayList<>();
        final List<Double> batchLatencies = new ArrayList<>();
        for (int i = 0; i < pidIndexes.size(); i++) {
            final long idx = pidIndexes.get(i);
            final String pid = gen.pid(idx);
            final long snapshotSn = gen.latestSnapshotSn(idx);
            final boolean timed = i >= warmups;
            final long t0 = System.nanoTime();
            final Document minEvDoc = journal.find(new Document("pid", pid))
                    .sort(new Document("to", 1)).limit(1).projection(new Document("to", 1)).first();
            if (minEvDoc != null) {
                for (final CleanupPlanner.Range r : CleanupPlanner.eventDeleteRanges(
                        minEvDoc.getLong("to"), snapshotSn, PersistenceShapes.WRITES_PER_CREDIT)) {
                    final long b0 = System.nanoTime();
                    journal.deleteMany(PersistenceShapes.deleteEventsFilter(pid, r.fromSn(), r.toSn()));
                    if (timed) {
                        batchLatencies.add((System.nanoTime() - b0) / 1_000_000.0);
                    }
                }
            }
            final Document minSnapDoc = snaps.find(new Document("pid", pid))
                    .sort(new Document("sn", 1)).limit(1).projection(new Document("sn", 1)).first();
            if (minSnapDoc != null) {
                for (final CleanupPlanner.Range r : CleanupPlanner.snapshotDeleteRanges(
                        minSnapDoc.getLong("sn"), snapshotSn, false, false,
                        PersistenceShapes.WRITES_PER_CREDIT)) {
                    final long b0 = System.nanoTime();
                    snaps.deleteMany(PersistenceShapes.deleteSnapshotsFilter(pid, r.fromSn(), r.toSn()));
                    if (timed) {
                        batchLatencies.add((System.nanoTime() - b0) / 1_000_000.0);
                    }
                }
            }
            if (timed) {
                totals.add((System.nanoTime() - t0) / 1_000_000.0);
            }
        }
        return new CleanupResultPair(BenchProtocol.of(totals), BenchProtocol.of(batchLatencies));
    }

    private static Long queryMin(final PreparedStatement ps, final String pid) throws Exception {
        ps.setString(1, pid);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                final long v = rs.getLong("sn");
                return rs.wasNull() ? null : v;
            }
            return null;
        }
    }
}
