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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.mongodb.client.MongoClient;

/**
 * M1: the C3 sweep loop running concurrently with W3 churn for the churn duration. Runs against
 * the post-sweep corpus (design ordering): the sweeper cycles continuously (quiet-period 0),
 * re-scanning the pid stream and cleaning churn pids as they cross the 500-event snapshot
 * threshold. Compare the churn samples here against w3-sustained-churn.md for interference.
 */
public final class MixedChurnBench {

    @Test
    public void m1CleanupUnderTraffic() throws Exception {
        final int duration = BenchConfig.writeDurationSeconds();
        final int rate = BenchConfig.writeTargetRate();
        final int workers = BenchConfig.writeWorkers();
        final int pidsPerWorker = BenchConfig.writePidsPerWorker();
        final boolean credits = "credits".equals(BenchConfig.sweepPace());
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("M1 — cleanup under live traffic"))
                .append(String.format("- churn: %d/s target, %d workers x %d pid(s)/worker, %d s; sweep pace: %s, "
                        + "cycling continuously%n", rate, workers, pidsPerWorker, duration,
                        credits ? "credits" : "unthrottled"))
                .append("- churn is CONCENTRATED (pidsPerWorker=1 via the mixed stage) so pids cross the "
                        + "500-event snapshot threshold and the sweeper finds live debris; W3 spreads the same "
                        + "rate over workers*100 pids, so per-pid contention differs — compare latency windows "
                        + "directionally, not 1:1\n\n");

        if (BenchConfig.runPg()) {
            final AtomicBoolean stop = new AtomicBoolean(false);
            final AtomicLong sweepRows = new AtomicLong();
            final AtomicLong sweepCycles = new AtomicLong();
            final List<SweepEngine.SweepResult> cycleResults = new ArrayList<>();
            final Thread sweeper = new Thread(() -> {
                try (Connection c = BenchConfig.openPg()) {
                    while (!stop.get()) {
                        final SweepEngine.SweepResult r = SweepEngine.sweepPg(c, credits, stop);
                        synchronized (cycleResults) {
                            cycleResults.add(r);
                        }
                        sweepRows.addAndGet(r.eventRowsDeleted() + r.snapRowsDeleted());
                        sweepCycles.incrementAndGet();
                    }
                } catch (final Exception e) {
                    throw new IllegalStateException("pg sweeper failed", e);
                }
            }, "pg-sweeper");
            sweeper.start();
            final ChurnEngine.ChurnResult churn =
                    ChurnEngine.runPg("thing:bench.churn-m1", duration, rate, workers, pidsPerWorker);
            stop.set(true);
            sweeper.join(120_000);
            md.append(section("PostgreSQL", churn, sweepCycles.get(), sweepRows.get()));
        }
        if (BenchConfig.runMongo()) {
            final AtomicBoolean stop = new AtomicBoolean(false);
            final AtomicLong sweepRows = new AtomicLong();
            final AtomicLong sweepCycles = new AtomicLong();
            final Thread sweeper = new Thread(() -> {
                try (MongoClient client = BenchConfig.openMongo()) {
                    while (!stop.get()) {
                        final SweepEngine.SweepResult r = SweepEngine.sweepMongo(
                                client.getDatabase(BenchConfig.mongoDbName()), credits, stop);
                        sweepRows.addAndGet(r.eventRowsDeleted() + r.snapRowsDeleted());
                        sweepCycles.incrementAndGet();
                    }
                } catch (final Exception e) {
                    throw new IllegalStateException("mongo sweeper failed", e);
                }
            }, "mongo-sweeper");
            sweeper.start();
            final ChurnEngine.ChurnResult churn =
                    ChurnEngine.runMongo("thing:bench.churn-m1", duration, rate, workers, pidsPerWorker);
            stop.set(true);
            sweeper.join(120_000);
            md.append(section("MongoDB", churn, sweepCycles.get(), sweepRows.get()));
        }
        md.append("\nInterference read-out: compare each backend's churn p50/p95/max windows against "
                + "the same backend's windows in w3-sustained-churn.md (churn without sweep).\n");
        BenchProtocol.writeEvidence("m1-cleanup-under-traffic.md", md.toString());
    }

    private static String section(final String backend, final ChurnEngine.ChurnResult churn,
            final long sweepCycles, final long sweepRows) {
        return "## " + backend + "\n\n"
                + String.format("- churn: %d ops (+%d snapshots), achieved %.1f/s%n"
                        + "- concurrent sweep: %d full cycles, %d rows deleted%n%n",
                        churn.totalOps(), churn.totalSnapshots(), churn.achievedRate(),
                        sweepCycles, sweepRows)
                + ChurnEngine.samplesMarkdown(churn) + "\n";
    }
}
