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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

/**
 * C3: full cleanup sweep (DESTRUCTIVE — that is its job). C4: before/after proof (autovacuum settle with vacuum
 * progress and wait samples, post-sweep replay on distinct pids, natural sizes, optional OFFLINE reclamation). C5: a
 * bounded credit-paced window, see the method.
 */
public final class CleanupSweepBench {

    @Test
    public void c3FullSweep() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final long firstIdx = ScenarioPlans.firstSnapshottedPidIndex(gen);
        final boolean credits = "credits".equals(BenchConfig.sweepPace());
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("C3 — full cleanup sweep"))
                .append("- pace: ").append(credits ? "credits (3 batches / 3 s — Ditto's default gate)"
                        : "unthrottled (sequential, one batch in flight — measures backend capability)")
                .append("\n\n");
        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg()) {
                md.append(pgPreamble(c));
                final String plans = "### PG plan (first page of the pid stream, before the sweep)\n\n"
                        + ScenarioPlans.pgPage(c, "")
                        + "\n### PG plan (cleanup of the first snapshotted corpus pid, before the sweep; deletes "
                        + "rolled back)\n\n"
                        + ScenarioPlans.pgCleanupPid(c, gen.pid(firstIdx), gen.latestSnapshotSn(firstIdx));
                final SweepEngine.SweepResult r = SweepEngine.sweepPg(c, credits, new AtomicBoolean(false));
                md.append(sweepSection("PostgreSQL", r)).append(plans).append('\n');
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                final String plans = "### Mongo plan (first page of the pid stream, before the sweep)\n\n"
                        + ScenarioPlans.mongoPage(db, "")
                        + "\n### Mongo plan (cleanup of the first snapshotted corpus pid, before the sweep; explain "
                        + "deletes nothing)\n\n"
                        + ScenarioPlans.mongoCleanupPid(db, gen.pid(firstIdx), gen.latestSnapshotSn(firstIdx));
                final SweepEngine.SweepResult r = SweepEngine.sweepMongo(db, credits, new AtomicBoolean(false));
                md.append(sweepSection("MongoDB", r)).append(plans).append('\n');
            }
        }
        md.append("\nAt Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the "
                + "same sweep would need [total batches / 1 per second] — quoted per backend above. "
                + "Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs "
                + "(plan deviation 3).\n");
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("c3-full-sweep"), md.toString());
    }

    /** The vacuum-relevant server settings and the storage parameters in effect. */
    private static String settingsLines(final Connection c) throws Exception {
        return "- server: " + PgVacuumProbe.settings(c) + "\n"
                + "- storage parameters: " + PgVacuumProbe.reloptions(c) + "\n";
    }

    /** {@link #settingsLines}, then the optional settle VACUUM (bench.c3.vacuumFirst) with its duration. */
    static String pgPreamble(final Connection c) throws Exception {
        final StringBuilder sb = new StringBuilder(settingsLines(c));
        if (BenchConfig.c3VacuumFirst()) {
            final long t0 = System.nanoTime();
            try (Statement st = c.createStatement()) {
                for (final String table : PgVacuumProbe.TABLES) {
                    st.execute("VACUUM (ANALYZE) " + table);
                }
            }
            sb.append(String.format("- corpus settled before the sweep: manual VACUUM (ANALYZE) of %s, %.1f s "
                    + "(bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)%n",
                    String.join(", ", PgVacuumProbe.TABLES), (System.nanoTime() - t0) / 1e9));
        }
        return sb.append('\n').toString();
    }

    private static String sweepSection(final String backend, final SweepEngine.SweepResult r) {
        return "## " + backend + "\n\n"
                + String.format("- pids processed: %d%n- event rows deleted: %d%n- snapshot rows deleted: %d%n"
                        + "- delete batches: %d%n- duration: %.1f s (%.0f rows/s), finished at %s%s%n"
                        + "- at default credit pace this would take ~%.1f hours%n%n",
                        r.pids(), r.eventRowsDeleted(), r.snapRowsDeleted(), r.batches(),
                        r.durationSeconds(),
                        (r.eventRowsDeleted() + r.snapRowsDeleted()) / Math.max(1.0, r.durationSeconds()),
                        r.finishedAt(),
                        r.deadlineReached() ? " (window bound reached; the pid in progress is not counted)" : "",
                        r.batches() / 3600.0)
                + SweepEngine.samplesMarkdown(r) + "\n";
    }

    @Test
    public void c4BeforeAfterProof() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final int n = BenchConfig.warmups() + BenchConfig.iterations();
        // distinct pids only: at 250k there are 26 OUTLIER pids, and a second full-scan read of a swept pid skips the
        // dead index entries the first read marked, so it would measure something else
        final List<Long> median = RecoveryBench.pickDistinctPids(gen, CorpusGenerator.PidClass.MEDIAN, n, 0);
        final List<Long> p99 = RecoveryBench.pickDistinctPids(gen, CorpusGenerator.PidClass.P99, n, 0);
        final List<Long> outlier = RecoveryBench.pickDistinctPids(gen, CorpusGenerator.PidClass.OUTLIER, n, 0);
        // the plan pid is a P99 pid no timed row reads, so its replay plan is a first read after the sweep (a second
        // read of a swept pid skips the dead index entries the first one marked)
        final List<Long> p99WithSpare = RecoveryBench.pickDistinctPids(gen, CorpusGenerator.PidClass.P99, n + 1, 0);
        final boolean untimedPlanPid = p99WithSpare.size() > n;
        final long planIdx = untimedPlanPid ? p99WithSpare.get(n) : p99.get(0);
        final String planWhen = untimedPlanPid ? "an untimed P99 pid, its first read after the sweep"
                : "the first timed P99 pid, a second read (no untimed P99 pid at this scale)";
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader(
                "C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)"));

        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg()) {
                // no VACUUM here: C4 measures the state the sweep and autovacuum leave behind
                md.append("## PostgreSQL\n\n").append(settingsLines(c))
                        .append("\n### Autovacuum settle\n\n").append(settlePg(c));
                md.append("\n### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)\n\n");
                final BenchProtocol.Timing tailMedian = RecoveryBench.pgReplayTail(c, gen, median);
                final BenchProtocol.Timing tailP99 = RecoveryBench.pgReplayTail(c, gen, p99);
                final BenchProtocol.Timing tailOutlier = RecoveryBench.pgReplayTail(c, gen, outlier);
                final BenchProtocol.Timing fullOutlier = RecoveryBench.pgReplayFull(c, gen, outlier);
                final BenchProtocol.Timing fullP99 = RecoveryBench.pgReplayFull(c, gen, p99);
                md.append(RecoveryBench.resultTable(List.of(
                        RecoveryBench.sideBySide(withN("R2a tail MEDIAN", tailMedian), tailMedian, null),
                        RecoveryBench.sideBySide(withN("R2a tail P99", tailP99), tailP99, null),
                        RecoveryBench.sideBySide(withN("R2a tail OUTLIER", tailOutlier), tailOutlier, null),
                        RecoveryBench.sideBySide(withN("R2b full-scan OUTLIER (post-sweep: only the tail remains)",
                                fullOutlier), fullOutlier, null),
                        RecoveryBench.sideBySide(withN("R2b full-scan P99 (post-sweep: only the tail remains)",
                                fullP99), fullP99, null))));
                md.append("\n### PG plan (R2b full-scan replay of ").append(planWhen).append(")\n\n")
                        .append(ScenarioPlans.pgReplay(c, gen.pid(planIdx), 1L));
                md.append("\n### Natural sizes (files do NOT shrink after deletes — see note)\n\n")
                        .append(PgLoadBench.sizesMarkdown(c));
                if (BenchConfig.c4OfflineReclaim()) {
                    md.append("\n### OFFLINE reclamation (VACUUM FULL — not part of normal operation)\n\n");
                    try (Statement st = c.createStatement()) {
                        st.execute("VACUUM FULL things_journal");
                        st.execute("VACUUM FULL things_snaps");
                        st.execute("ANALYZE things_journal");
                        st.execute("ANALYZE things_snaps");
                    }
                    md.append(PgLoadBench.sizesMarkdown(c));
                } else {
                    md.append("\n### OFFLINE reclamation\n\n- skipped (bench.c4.offlineReclaim=false)\n");
                }
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                md.append("\n## MongoDB\n\n### Post-sweep replay (distinct class pids from corpus index 0; n = timed "
                        + "reads)\n\n");
                final BenchProtocol.Timing tailMedian = RecoveryBench.mongoReplayTail(db, gen, median);
                final BenchProtocol.Timing tailP99 = RecoveryBench.mongoReplayTail(db, gen, p99);
                final BenchProtocol.Timing tailOutlier = RecoveryBench.mongoReplayTail(db, gen, outlier);
                final BenchProtocol.Timing fullOutlier = RecoveryBench.mongoReplayFull(db, gen, outlier);
                final BenchProtocol.Timing fullP99 = RecoveryBench.mongoReplayFull(db, gen, p99);
                md.append(RecoveryBench.resultTable(List.of(
                        RecoveryBench.sideBySide(withN("R2a tail MEDIAN", tailMedian), null, tailMedian),
                        RecoveryBench.sideBySide(withN("R2a tail P99", tailP99), null, tailP99),
                        RecoveryBench.sideBySide(withN("R2a tail OUTLIER", tailOutlier), null, tailOutlier),
                        RecoveryBench.sideBySide(withN("R2b full-scan OUTLIER (post-sweep)", fullOutlier), null,
                                fullOutlier),
                        RecoveryBench.sideBySide(withN("R2b full-scan P99 (post-sweep)", fullP99), null, fullP99))));
                md.append("\n### Mongo plan (R2b full-scan replay of ").append(planWhen).append(")\n\n")
                        .append(ScenarioPlans.mongoReplay(db, gen.pid(planIdx), 1L));
                md.append("\n### Natural sizes\n\n").append(MongoLoadBench.sizesMarkdown(db));
                if (BenchConfig.c4OfflineReclaim()) {
                    md.append("\n### OFFLINE reclamation (compact — blocks, standalone-only convenience)\n\n");
                    for (final String coll : List.of(PersistenceShapes.JOURNAL, PersistenceShapes.SNAPS)) {
                        try {
                            db.runCommand(new Document("compact", coll));
                        } catch (final RuntimeException e) {
                            md.append("- compact ").append(coll).append(" failed: ").append(e.getMessage())
                                    .append('\n');
                        }
                    }
                    md.append(MongoLoadBench.sizesMarkdown(db));
                } else {
                    md.append("\n### OFFLINE reclamation\n\n- skipped (bench.c4.offlineReclaim=false)\n");
                }
            }
        }
        md.append("\nDeletes free space inside data files (reusable) but do not shrink them — the "
                + "honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the "
                + "live/dead tuple counts above.\n");
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("c4-before-after"), md.toString());
    }

    private static String withN(final String label, final BenchProtocol.Timing timing) {
        return label + " (n=" + timing.n() + ")";
    }

    private static String settlePg(final Connection c) throws Exception {
        final long timeoutMillis = BenchConfig.settleTimeoutSeconds() * 1_000L;
        final long start = System.currentTimeMillis();
        final StringBuilder timeline = new StringBuilder("- settle started at ").append(Instant.ofEpochMilli(start))
                .append(", timeout ").append(BenchConfig.settleTimeoutSeconds()).append(" s\n");
        while (true) {
            long live = 0;
            long dead = 0;
            long autovac = 0;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT sum(n_live_tup), sum(n_dead_tup), sum(autovacuum_count) "
                                    + "FROM pg_stat_user_tables "
                                    + "WHERE relname IN ('things_journal','things_snaps')")) {
                if (rs.next()) {
                    live = rs.getLong(1);
                    dead = rs.getLong(2);
                    autovac = rs.getLong(3);
                }
            }
            final long elapsed = (System.currentTimeMillis() - start) / 1000;
            timeline.append(String.format("- t+%ds: live=%d dead=%d autovacuum_count=%d | vacuum: %s",
                    elapsed, live, dead, autovac, PgVacuumProbe.formatProgress(PgVacuumProbe.progress(c))));
            if (dead < Math.max(100_000L, live / 100L)) {
                timeline.append("\n- settled at t+").append(elapsed).append("s (dead < max(100k, 1% of live))\n");
                break;
            }
            if (System.currentTimeMillis() - start > timeoutMillis) {
                timeline.append("\n- TIMEOUT waiting for autovacuum — dead tuples still high, note in README\n");
                break;
            }
            // the 15 s tick, spent sampling what the autovacuum workers wait for, once per second
            final Map<String, Integer> waits = new TreeMap<>();
            for (int i = 0; i < 15; i++) {
                for (final String wait : PgVacuumProbe.autovacuumWaits(c)) {
                    waits.merge(wait, 1, Integer::sum);
                }
                Thread.sleep(1_000L);
            }
            timeline.append(" | waits: ").append(PgVacuumProbe.formatWaits(waits)).append('\n');
        }
        return timeline.append('\n').append(PgVacuumProbe.tableStatsMarkdown(c)).toString();
    }

    /**
     * C5: a bounded window of the cleanup sweep at Ditto's credit pace (at most 100 rows/s), in production order. It
     * records the dead tuples and the autovacuum activity the window caused, then replays (from sn 1) the pids it
     * swept: alternate pids as the window left them, the others after a manual VACUUM of things_journal. Tests
     * whether the production pace leaves C4's post-sweep replay slowdown behind.
     */
    @Test
    public void c5CreditPacedWindow() throws Exception {
        final Duration window = BenchConfig.sweepMaxDuration();
        if (window.isZero() || window.isNegative()) {
            throw new IllegalStateException(
                    "C5 needs a bounded window: set bench.sweep.maxSeconds (BENCH_SWEEP_MAX_SECONDS)");
        }
        if (!BenchConfig.runPg() || BenchConfig.runMongo()) {
            throw new IllegalStateException("C5 is PostgreSQL-only: run it with --backend pg");
        }
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("C5 — bounded credit-paced cleanup window"))
                .append("- pace: credits (3 batches / 3 s — Ditto's default gate), window ")
                .append(window.toSeconds()).append(" s, sample interval ")
                .append(BenchConfig.sampleIntervalSeconds()).append(" s\n");
        try (Connection c = BenchConfig.openPg()) {
            md.append(pgPreamble(c));
            final SweepEngine.SweepResult r = SweepEngine.sweepPg(c, true, new AtomicBoolean(false), window);
            md.append(sweepSection("PostgreSQL", r));
            md.append("### Tables at the end of the window\n\n").append(PgVacuumProbe.tableStatsMarkdown(c))
                    .append("\n- autovacuum in progress: ")
                    .append(PgVacuumProbe.formatProgress(PgVacuumProbe.progress(c))).append("\n\n");
            md.append(windowReplay(c, r.sweptPids()));
        }
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("c5-credit-paced-window"), md.toString());
    }

    private static String windowReplay(final Connection c, final List<SweepEngine.SweptPid> swept) throws Exception {
        // only pids with a deleted event range leave dead entries a replay from sn 1 must walk; alternating keeps
        // both halves alike in depth and in when they were swept
        final List<SweepEngine.SweptPid> withRange =
                swept.stream().filter(p -> p.eventRowsDeleted() >= 100).toList();
        final int perHalf = BenchConfig.warmups() + BenchConfig.iterations();
        final List<SweepEngine.SweptPid> asLeft = new ArrayList<>();
        final List<SweepEngine.SweptPid> afterVacuum = new ArrayList<>();
        for (int i = 0; i < withRange.size(); i++) {
            final List<SweepEngine.SweptPid> half = i % 2 == 0 ? asLeft : afterVacuum;
            if (half.size() < perHalf) {
                half.add(withRange.get(i));
            }
        }
        if (asLeft.size() <= BenchConfig.warmups() || afterVacuum.size() <= BenchConfig.warmups()) {
            return "- only " + withRange.size()
                    + " swept pids with a deleted event range: too few to time the replay\n";
        }
        final BenchProtocol.Timing left = replayFromSn1(c, asLeft);
        final long t0 = System.nanoTime();
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM things_journal");
        }
        final double vacuumSeconds = (System.nanoTime() - t0) / 1e9;
        final BenchProtocol.Timing vacuumed = replayFromSn1(c, afterVacuum);
        return "### Full-scan replay (from sn 1) of the pids the window swept\n\n"
                + BenchProtocol.table(List.of("swept pids", "n", "mean deleted events per pid",
                                "PG p50 | p95 | max (ms)"),
                        List.of(List.of("as the window left them", String.valueOf(left.n()),
                                        String.format("%.0f", meanDeleted(asLeft)), left.cells()),
                                List.of("after a manual VACUUM things_journal", String.valueOf(vacuumed.n()),
                                        String.format("%.0f", meanDeleted(afterVacuum)), vacuumed.cells())))
                + String.format("%n- manual VACUUM things_journal between the two halves: %.1f s (protocol step, "
                        + "not production)%n- every pid is read once (first touch); %d swept pids had a deleted "
                        + "event range%n", vacuumSeconds, withRange.size());
    }

    private static BenchProtocol.Timing replayFromSn1(final Connection c, final List<SweepEngine.SweptPid> pids)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(PersistenceShapes.PG_REPLAY)) {
            return BenchProtocol.measure(BenchConfig.warmups(),
                    Math.min(BenchConfig.iterations(), pids.size() - BenchConfig.warmups()), iteration -> {
                        ps.setString(1, pids.get(iteration).pid());
                        ps.setLong(2, 1L);
                        ps.setLong(3, Long.MAX_VALUE);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rs.getString("event");
                            }
                        }
                    });
        }
    }

    private static double meanDeleted(final List<SweepEngine.SweptPid> pids) {
        return pids.stream().mapToLong(SweepEngine.SweptPid::eventRowsDeleted).average().orElse(0.0);
    }
}
