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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

/**
 * M1 and M1b: W3-shaped churn while the cleanup sweep runs.
 * <ul>
 * <li>M1 ({@link #m1CleanupDuringMassSweep()}): on a corpus whose cleanup backlog is still there, after C3's preamble,
 * one unthrottled sweep pass over the whole backlog (exactly C3's sweep) starts with the churn and runs to its end; no
 * VACUUM FULL or compact runs before it. The churn has W3's shape, so the same backend's W3 evidence is its no-sweep
 * control. The evidence splits the churn windows into those that ended while the sweep was still deleting and those
 * after it.</li>
 * <li>M1b ({@link #m1CleanupUnderTraffic()}), the 2026-07 M1: on the post-sweep corpus a sweeper cycles over the pid
 * stream until the churn ends and finds only the churn's own debris (the mixed stage concentrates the churn).</li>
 * </ul>
 */
public final class MixedChurnBench {

    static final String MASS_SWEEP_PREFIX = "thing:bench.churn-m1m";
    static final String POST_SWEEP_PREFIX = "thing:bench.churn-m1";
    private static final long MASS_SWEEP_JOIN_MILLIS = Duration.ofHours(4).toMillis();
    private static final long CYCLE_JOIN_MILLIS = 120_000L;

    record Params(int duration, int rate, int workers, int pidsPerWorker, int interval) {

        static Params fromConfig() {
            return new Params(BenchConfig.writeDurationSeconds(), BenchConfig.writeTargetRate(),
                    BenchConfig.writeWorkers(), BenchConfig.writePidsPerWorker(), BenchConfig.sampleIntervalSeconds());
        }

        String churnLine(final String sweep) {
            return String.format("- churn: %d/s target, %d workers x %d pid(s)/worker, %d s, %d s windows; sweep: %s%n",
                    rate, workers, pidsPerWorker, duration, interval, sweep);
        }
    }

    /** One sweep pass; {@code stop} ends it between pids. */
    @FunctionalInterface
    interface Pass {
        SweepEngine.SweepResult run(AtomicBoolean stop) throws Exception;
    }

    record SweptChurn(Instant churnStart, ChurnEngine.ChurnResult churn, List<SweepEngine.SweepResult> passes) {}

    @Test
    public void m1CleanupDuringMassSweep() throws Exception {
        if ("credits".equals(BenchConfig.sweepPace())) {
            throw new IllegalStateException(
                    "M1 during the mass sweep runs C3's unthrottled sweep: unset BENCH_SWEEP_PACE");
        }
        final Params p = Params.fromConfig();
        final CorpusGenerator gen = BenchConfig.corpus();
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader(
                "M1 — live traffic during the mass cleanup sweep (loaded backlog, no VACUUM FULL before it)"))
                .append(p.churnLine("one unthrottled pass over the whole cleanup backlog (C3's sweep), started with "
                        + "the churn and run to its end"))
                .append("- churn pid prefix ").append(MASS_SWEEP_PREFIX).append("; the churn has W3's shape, so the "
                        + "same backend's w3-sustained-churn evidence is the no-sweep control\n\n");
        if (BenchConfig.runPg()) {
            md.append("## PostgreSQL\n\n");
            try (Connection c = BenchConfig.openPg()) {
                md.append(backlogLine(pgSnapshotRows(c), gen)).append(CleanupSweepBench.pgPreamble(c));
                final String plans = pgPlans(c, gen, MASS_SWEEP_PREFIX, "before the sweep and the churn started");
                final SweptChurn run = sweepWithChurn(stop -> SweepEngine.sweepPg(c, false, stop), false,
                        () -> ChurnEngine.runPg(MASS_SWEEP_PREFIX, p.duration(), p.rate(), p.workers(),
                                p.pidsPerWorker()));
                md.append(massSweepSection(run, p)).append(plans).append('\n');
            }
        }
        if (BenchConfig.runMongo()) {
            md.append("## MongoDB\n\n");
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                md.append(backlogLine(db.getCollection(PersistenceShapes.SNAPS).countDocuments(), gen)).append('\n');
                final String plans = mongoPlans(db, gen, "before the sweep and the churn started");
                final SweptChurn run = sweepWithChurn(stop -> SweepEngine.sweepMongo(db, false, stop), false,
                        () -> ChurnEngine.runMongo(MASS_SWEEP_PREFIX, p.duration(), p.rate(), p.workers(),
                                p.pidsPerWorker()));
                md.append(massSweepSection(run, p)).append(plans).append('\n');
            }
        }
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("m1-cleanup-during-mass-sweep"), md.toString());
    }

    @Test
    public void m1CleanupUnderTraffic() throws Exception {
        final Params p = Params.fromConfig();
        final boolean credits = "credits".equals(BenchConfig.sweepPace());
        final CorpusGenerator gen = BenchConfig.corpus();
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader(
                "M1b — cleanup under live traffic (post-sweep corpus)"))
                .append(p.churnLine((credits ? "credits" : "unthrottled") + ", cycling continuously"))
                .append("- churn is CONCENTRATED (pidsPerWorker=1 via the mixed stage) so pids cross the "
                        + "500-event snapshot threshold and the sweeper finds live debris; W3 spreads the same "
                        + "rate over workers*100 pids, so per-pid contention differs — compare latency windows "
                        + "directionally, not 1:1\n\n");
        if (BenchConfig.runPg()) {
            final String plans;
            try (Connection c = BenchConfig.openPg()) {
                plans = pgPlans(c, gen, POST_SWEEP_PREFIX, "before the churn started");
            }
            try (Connection c = BenchConfig.openPg()) {
                final SweptChurn run = sweepWithChurn(stop -> SweepEngine.sweepPg(c, credits, stop), true,
                        () -> ChurnEngine.runPg(POST_SWEEP_PREFIX, p.duration(), p.rate(), p.workers(),
                                p.pidsPerWorker()));
                md.append(cyclingSection("PostgreSQL", run)).append(plans).append('\n');
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                final String plans = mongoPlans(db, gen, "before the churn started");
                final SweptChurn run = sweepWithChurn(stop -> SweepEngine.sweepMongo(db, credits, stop), true,
                        () -> ChurnEngine.runMongo(POST_SWEEP_PREFIX, p.duration(), p.rate(), p.workers(),
                                p.pidsPerWorker()));
                md.append(cyclingSection("MongoDB", run)).append(plans).append('\n');
            }
        }
        md.append("\nInterference read-out: compare each backend's churn p50/p95/max windows against the same "
                + "backend's w3-sustained-churn evidence (churn without a sweep).\n");
        BenchProtocol.writeEvidence(BenchProtocol.evidenceName("m1-cleanup-under-traffic"), md.toString());
    }

    /**
     * Runs the sweeper on its own (daemon) thread next to the churn on this thread. {@code cycle = false}: one pass,
     * awaited to its end even after the churn finished (bounded at 4 h). {@code cycle = true}: passes until the churn
     * ends. A sweeper failure fails the run; a churn failure stops the sweeper and is rethrown.
     */
    static SweptChurn sweepWithChurn(final Pass pass, final boolean cycle,
            final Callable<ChurnEngine.ChurnResult> churn) throws Exception {
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<SweepEngine.SweepResult> passes = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread sweeper = new Thread(() -> {
            try {
                do {
                    passes.add(pass.run(stop));
                } while (cycle && !stop.get());
            } catch (final Throwable e) {
                failure.set(e);
            }
        }, "m1-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
        final Instant churnStart = Instant.now();
        final ChurnEngine.ChurnResult churnResult;
        try {
            churnResult = churn.call();
        } catch (final Exception e) {
            stop.set(true);   // a failed churn ends the run: do not leave the sweeper deleting
            throw e;
        }
        if (cycle) {
            stop.set(true);   // M1b: the sweeper cycles until the churn ends
        }
        sweeper.join(cycle ? CYCLE_JOIN_MILLIS : MASS_SWEEP_JOIN_MILLIS);
        if (sweeper.isAlive()) {
            stop.set(true);
            throw new IllegalStateException("the sweeper was still running after the churn and the join bound");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("the sweeper failed: the churn ran without its sweep", failure.get());
        }
        synchronized (passes) {
            return new SweptChurn(churnStart, churnResult, List.copyOf(passes));
        }
    }

    private static String massSweepSection(final SweptChurn run, final Params p) {
        final SweepEngine.SweepResult s = run.passes().get(0);
        final ChurnEngine.ChurnResult churn = run.churn();
        final Instant sweepStart = s.finishedAt().minusMillis((long) (s.durationSeconds() * 1_000));
        final double sweepEnd = Duration.between(run.churnStart(), s.finishedAt()).toMillis() / 1_000.0;
        final MassSweepWindows.Split split = MassSweepWindows.split(churn.samples(), p.interval(), sweepEnd);
        final long churnPidsSwept =
                s.sweptPids().stream().filter(sp -> sp.pid().startsWith(MASS_SWEEP_PREFIX)).count();
        return String.format("- churn: %d ops (+%d snapshots), achieved %.1f/s over %d s%n",
                churn.totalOps(), churn.totalSnapshots(), churn.achievedRate(), p.duration())
                + String.format("- mass sweep: %d pids, %d event + %d snapshot rows deleted, %d delete batches, "
                                + "%.1f s (%.0f rows/s); started %s, finished %s = %.0f s after the churn started (the "
                                + "churn ran %d s)%n", s.pids(), s.eventRowsDeleted(), s.snapRowsDeleted(),
                        s.batches(), s.durationSeconds(),
                        (s.eventRowsDeleted() + s.snapRowsDeleted()) / Math.max(1.0, s.durationSeconds()),
                        sweepStart, s.finishedAt(), sweepEnd, p.duration())
                + "- churn pids swept by the mass sweep: " + churnPidsSwept + " (expected 0)\n"
                + "- churn windows that ended while the mass sweep was still deleting: "
                + MassSweepWindows.describe(split.during(), p.interval()) + "\n"
                + "- churn windows after the mass sweep had finished: "
                + MassSweepWindows.describe(split.after(), p.interval()) + "\n\n"
                + "Churn windows (on the churn's clock):\n\n" + ChurnEngine.samplesMarkdown(churn) + "\n"
                + "Mass sweep windows (on the sweeper's clock, which starts just before the churn's):\n\n"
                + SweepEngine.samplesMarkdown(s) + "\n";
    }

    private static String cyclingSection(final String backend, final SweptChurn run) {
        final long rows = run.passes().stream().mapToLong(r -> r.eventRowsDeleted() + r.snapRowsDeleted()).sum();
        return "## " + backend + "\n\n"
                + String.format("- churn: %d ops (+%d snapshots), achieved %.1f/s%n"
                                + "- concurrent sweep: %d full cycles, %d rows deleted%n%n",
                        run.churn().totalOps(), run.churn().totalSnapshots(), run.churn().achievedRate(),
                        run.passes().size(), rows)
                + ChurnEngine.samplesMarkdown(run.churn()) + "\n";
    }

    private static String backlogLine(final long snapshotRows, final CorpusGenerator gen) {
        if (!MassSweepWindows.backlogPresent(snapshotRows, gen.totalSnapshots())) {
            throw new IllegalStateException("things_snaps holds " + snapshotRows + " rows, the corpus generates "
                    + gen.totalSnapshots() + ": M1 during the mass sweep needs the un-swept backlog. Run `load` (with "
                    + "the same --count) first.");
        }
        return "- backlog before the run: " + snapshotRows + " snapshot rows (the corpus generates "
                + gen.totalSnapshots() + ")\n";
    }

    private static long pgSnapshotRows(final Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM things_snaps")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String pgPlans(final Connection c, final CorpusGenerator gen, final String churnPrefix,
            final String when) throws SQLException {
        final long first = ScenarioPlans.firstSnapshottedPidIndex(gen);
        final String churnPid = ChurnEngine.churnPid(churnPrefix, 0, 0);
        final long sn = ScenarioPlans.pgNextSn(c, churnPid);
        return "### PG plan (C1-shaped page at the start of the pid stream, " + when + ")\n\n"
                + ScenarioPlans.pgPage(c, "")
                + "\n### PG plan (cleanup of the first snapshotted corpus pid, " + when + "; deletes rolled back)\n\n"
                + ScenarioPlans.pgCleanupPid(c, gen.pid(first), gen.latestSnapshotSn(first))
                + "\n### PG plan (churn append of " + churnPid + " at sn " + sn + ", " + when + "; rolled back)\n\n"
                + ScenarioPlans.pgAppend(c, churnPid, sn);
    }

    private static String mongoPlans(final MongoDatabase db, final CorpusGenerator gen, final String when) {
        final long first = ScenarioPlans.firstSnapshottedPidIndex(gen);
        return "### Mongo plan (C1-shaped page at the start of the pid stream, " + when + ")\n\n"
                + ScenarioPlans.mongoPage(db, "")
                + "\n### Mongo plan (cleanup of the first snapshotted corpus pid, " + when
                + "; explain deletes nothing)\n\n"
                + ScenarioPlans.mongoCleanupPid(db, gen.pid(first), gen.latestSnapshotSn(first))
                + "\n### Mongo plan (churn append)\n\n" + ScenarioPlans.mongoAppend();
    }
}
