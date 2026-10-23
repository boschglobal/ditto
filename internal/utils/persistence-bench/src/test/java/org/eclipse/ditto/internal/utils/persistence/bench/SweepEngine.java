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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.bson.Document;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Driver-level transcription of PersistenceCleanupActor's sweep: page the pid stream
 * (getNewestSnapshotHeadsAbove's payload-free page since 2026-10-23, batch 100, lowerBound = last raw row's pid,
 * until an empty page), and
 * per pid run the Cleanup.java delete plan (events tiling to snapshotSn-1, then snapshots keeping
 * the latest), one delete batch in flight at a time (the actor's inputBuffer(1,1)). Pacing:
 * unthrottled (default — measures backend capability) or credits (3 batches per 3 s — the
 * always-open credit gate of this branch, plan deviation 3). Responsibility filter is 1-of-1.
 */
public final class SweepEngine {

    private SweepEngine() {
    }

    public record SweepSample(int windowIndex, long pidsDone, long rowsDeleted, double pidsPerSec,
            double rowsPerSec, long deadTuples, long journalTotalBytes, long snapsTotalBytes, String vacuum) {}

    /** A pid whose whole delete plan ran inside the sweep; the pid in progress when a deadline hits is not one. */
    public record SweptPid(String pid, long snapshotSn, long eventRowsDeleted) {}

    public record SweepResult(long pids, long eventRowsDeleted, long snapRowsDeleted, long batches,
            double durationSeconds, List<SweepSample> samples, List<SweptPid> sweptPids, boolean deadlineReached,
            Instant finishedAt) {}

    /** Wall-clock bound of a sweep; a zero or negative bound never expires. Package-private for SweepDeadlineTest. */
    static final class Deadline {
        private final boolean bounded;
        private final long deadlineNanos;
        private boolean reached;

        Deadline(final Duration bound) {
            bounded = !bound.isZero() && !bound.isNegative();
            deadlineNanos = System.nanoTime() + (bounded ? bound.toNanos() : 0L);
        }

        /** Reads the clock; once the bound has passed, stays reached. */
        boolean reached() {
            if (bounded && !reached && System.nanoTime() - deadlineNanos >= 0) {
                reached = true;
            }
            return reached;
        }

        /** Whether a check during the sweep found the bound passed (no clock read). */
        boolean wasReached() {
            return reached;
        }
    }

    interface VacuumProbe {
        String inProgress() throws Exception;
    }

    /** Package-private for CreditPacerTest; interval is injectable for the same reason. */
    static final class CreditPacer {
        private final boolean enabled;
        private final long intervalNanos;
        private long windowStart = System.nanoTime();
        private int inWindow;

        CreditPacer(final boolean enabled, final Duration interval) {
            this.enabled = enabled;
            intervalNanos = interval.toNanos();
        }

        void acquire() {
            if (!enabled) {
                return;
            }
            if (inWindow >= PersistenceShapes.CREDITS_PER_BATCH) {
                final long windowEnd = windowStart + intervalNanos;
                long now;
                while ((now = System.nanoTime()) < windowEnd) {
                    LockSupport.parkNanos(windowEnd - now);
                }
                windowStart = System.nanoTime();
                inWindow = 0;
            }
            inWindow++;
        }
    }

    private static final class Counters {
        long pids;
        long eventRows;
        long snapRows;
        long batches;
        long lastPids;
        long lastRows;
        final List<SweepSample> samples = new ArrayList<>();
        final long startNanos = System.nanoTime();
        long nextSampleNanos = startNanos + BenchConfig.sampleIntervalSeconds() * 1_000_000_000L;
        int windowIndex;
        final Deadline deadline;
        final List<SweptPid> swept = new ArrayList<>();
        VacuumProbe vacuumProbe = () -> "—";

        Counters(final Duration bound) {
            deadline = new Deadline(bound);
        }

        void maybeSample(final StatsProbe probe) throws Exception {
            if (System.nanoTime() < nextSampleNanos) {
                return;
            }
            final double interval = BenchConfig.sampleIntervalSeconds();
            final long[] s = probe.stats();
            final long rows = eventRows + snapRows;
            samples.add(new SweepSample(windowIndex++, pids, rows,
                    (pids - lastPids) / interval, (rows - lastRows) / interval, s[0], s[1], s[2],
                    vacuumProbe.inProgress()));
            System.out.printf("[bench] sweep window %d: pids=%d rows=%d (%.0f rows/s) dead=%d%n",
                    windowIndex - 1, pids, rows, (rows - lastRows) / interval, s[0]);
            lastPids = pids;
            lastRows = rows;
            nextSampleNanos += BenchConfig.sampleIntervalSeconds() * 1_000_000_000L;
        }

        SweepResult finish() {
            return new SweepResult(pids, eventRows, snapRows, batches,
                    (System.nanoTime() - startNanos) / 1e9, samples, List.copyOf(swept), deadline.wasReached(),
                    Instant.now());
        }
    }

    interface StatsProbe {
        long[] stats() throws Exception;   // {deadTuples, journalBytes, snapsBytes}
    }

    /** {@link #sweepPg(Connection, boolean, AtomicBoolean, Duration)} without a wall-clock bound. */
    public static SweepResult sweepPg(final Connection c, final boolean creditsPace,
            final AtomicBoolean stop) throws Exception {
        return sweepPg(c, creditsPace, stop, Duration.ZERO);
    }

    /**
     * Sweeps until the pid stream is exhausted, {@code stop} is set, or {@code bound} (if positive) has passed. The
     * bound is checked before every delete batch, between pids and between pages; a credit-paced batch whose check
     * passed may still start up to one credit interval (3 s) after the bound, because it then waits for its credit.
     * The pid in progress when the bound hits is not counted as swept.
     */
    public static SweepResult sweepPg(final Connection c, final boolean creditsPace,
            final AtomicBoolean stop, final Duration bound) throws Exception {
        final CreditPacer pacer = new CreditPacer(creditsPace, PersistenceShapes.CREDIT_INTERVAL);
        final Counters counters = new Counters(bound);
        counters.vacuumProbe = () -> PgVacuumProbe.formatProgress(PgVacuumProbe.progress(c));
        final StatsProbe probe = () -> pgStats(c);
        try (PreparedStatement page = c.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE);
                PreparedStatement minEv = c.prepareStatement(PersistenceShapes.PG_MIN_EVENT_SN);
                PreparedStatement minSnap = c.prepareStatement(PersistenceShapes.PG_MIN_SNAP_SN);
                PreparedStatement delEv = c.prepareStatement(PersistenceShapes.PG_DELETE_EVENTS);
                PreparedStatement delSnap = c.prepareStatement(PersistenceShapes.PG_DELETE_SNAPS)) {
            String lowerBound = "";
            while (!stop.get() && !counters.deadline.reached()) {
                page.setString(1, lowerBound);
                page.setString(2, "");
                page.setString(3, "");
                page.setBoolean(4, true);
                page.setString(5, "0 seconds");
                page.setInt(6, PersistenceShapes.READS_PER_QUERY);
                page.setBoolean(7, true);
                page.setString(8, "0 seconds");
                final List<String[]> rows = new ArrayList<>();
                try (ResultSet rs = page.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[]{rs.getString("pid"), String.valueOf(rs.getLong("sn"))});
                    }
                }
                if (rows.isEmpty()) {
                    break;
                }
                lowerBound = rows.get(rows.size() - 1)[0];
                for (final String[] row : rows) {
                    if (stop.get() || counters.deadline.reached()) {
                        break;
                    }
                    final String pid = row[0];
                    final long snapshotSn = Long.parseLong(row[1]);
                    minEv.setString(1, pid);
                    Long evMin = null;
                    try (ResultSet rs = minEv.executeQuery()) {
                        if (rs.next()) {
                            final long v = rs.getLong("sn");
                            evMin = rs.wasNull() ? null : v;
                        }
                    }
                    long pidEventRows = 0;
                    if (evMin != null) {
                        for (final CleanupPlanner.Range r : CleanupPlanner.eventDeleteRanges(
                                evMin, snapshotSn, PersistenceShapes.WRITES_PER_CREDIT)) {
                            if (counters.deadline.reached()) {
                                break;
                            }
                            pacer.acquire();
                            delEv.setString(1, pid);
                            delEv.setLong(2, r.fromSn());
                            delEv.setLong(3, r.toSn());
                            final int deleted = delEv.executeUpdate();
                            counters.eventRows += deleted;
                            pidEventRows += deleted;
                            counters.batches++;
                            counters.maybeSample(probe);
                        }
                    }
                    if (counters.deadline.reached()) {
                        break;   // this pid's delete plan may be incomplete: it is not counted as swept
                    }
                    minSnap.setString(1, pid);
                    Long snMin = null;
                    try (ResultSet rs = minSnap.executeQuery()) {
                        if (rs.next()) {
                            final long v = rs.getLong("sn");
                            snMin = rs.wasNull() ? null : v;
                        }
                    }
                    if (snMin != null) {
                        for (final CleanupPlanner.Range r : CleanupPlanner.snapshotDeleteRanges(
                                snMin, snapshotSn, false, false, PersistenceShapes.WRITES_PER_CREDIT)) {
                            if (counters.deadline.reached()) {
                                break;
                            }
                            pacer.acquire();
                            delSnap.setString(1, pid);
                            delSnap.setLong(2, r.fromSn());
                            delSnap.setLong(3, r.toSn());
                            counters.snapRows += delSnap.executeUpdate();
                            counters.batches++;
                            counters.maybeSample(probe);
                        }
                    }
                    if (counters.deadline.reached()) {
                        break;
                    }
                    counters.pids++;
                    counters.swept.add(new SweptPid(pid, snapshotSn, pidEventRows));
                    counters.maybeSample(probe);
                }
            }
        }
        return counters.finish();
    }

    /** {@link #sweepMongo(MongoDatabase, boolean, AtomicBoolean, Duration)} without a wall-clock bound. */
    public static SweepResult sweepMongo(final MongoDatabase db, final boolean creditsPace,
            final AtomicBoolean stop) throws Exception {
        return sweepMongo(db, creditsPace, stop, Duration.ZERO);
    }

    /** The MongoDB counterpart of {@link #sweepPg(Connection, boolean, AtomicBoolean, Duration)}. */
    public static SweepResult sweepMongo(final MongoDatabase db, final boolean creditsPace,
            final AtomicBoolean stop, final Duration bound) throws Exception {
        final MongoCollection<Document> journal = db.getCollection(PersistenceShapes.JOURNAL);
        final MongoCollection<Document> snaps = db.getCollection(PersistenceShapes.SNAPS);
        final CreditPacer pacer = new CreditPacer(creditsPace, PersistenceShapes.CREDIT_INTERVAL);
        final Counters counters = new Counters(bound);
        final StatsProbe probe = () -> mongoStats(db);
        String lowerBound = "";
        while (!stop.get() && !counters.deadline.reached()) {
            // the aggregation emits ONE doc: {_id:null, m:<max pid of the raw page>, i:[items...]}
            Document pageDoc = null;
            for (final Document d : snaps.aggregate(
                    PersistenceShapes.newestSnapshotsPipeline(lowerBound, PersistenceShapes.READS_PER_QUERY))) {
                pageDoc = d;
            }
            if (pageDoc == null) {
                break;
            }
            lowerBound = pageDoc.getString("m");
            final List<Document> items = pageDoc.getList("i", Document.class);
            for (final Document item : items) {
                if (stop.get() || counters.deadline.reached()) {
                    break;
                }
                final String pid = item.getString("_id");
                final long snapshotSn = item.getLong("sn");
                final Document minEvDoc = journal.find(new Document("pid", pid))
                        .sort(new Document("to", 1)).limit(1).projection(new Document("to", 1)).first();
                long pidEventRows = 0;
                if (minEvDoc != null) {
                    for (final CleanupPlanner.Range r : CleanupPlanner.eventDeleteRanges(
                            minEvDoc.getLong("to"), snapshotSn, PersistenceShapes.WRITES_PER_CREDIT)) {
                        if (counters.deadline.reached()) {
                            break;
                        }
                        pacer.acquire();
                        final long deleted = journal.deleteMany(
                                PersistenceShapes.deleteEventsFilter(pid, r.fromSn(), r.toSn()))
                                .getDeletedCount();
                        counters.eventRows += deleted;
                        pidEventRows += deleted;
                        counters.batches++;
                        counters.maybeSample(probe);
                    }
                }
                if (counters.deadline.reached()) {
                    break;   // this pid's delete plan may be incomplete: it is not counted as swept
                }
                final Document minSnapDoc = snaps.find(new Document("pid", pid))
                        .sort(new Document("sn", 1)).limit(1).projection(new Document("sn", 1)).first();
                if (minSnapDoc != null) {
                    for (final CleanupPlanner.Range r : CleanupPlanner.snapshotDeleteRanges(
                            minSnapDoc.getLong("sn"), snapshotSn, false, false,
                            PersistenceShapes.WRITES_PER_CREDIT)) {
                        if (counters.deadline.reached()) {
                            break;
                        }
                        pacer.acquire();
                        counters.snapRows += snaps.deleteMany(
                                PersistenceShapes.deleteSnapshotsFilter(pid, r.fromSn(), r.toSn()))
                                .getDeletedCount();
                        counters.batches++;
                        counters.maybeSample(probe);
                    }
                }
                if (counters.deadline.reached()) {
                    break;
                }
                counters.pids++;
                counters.swept.add(new SweptPid(pid, snapshotSn, pidEventRows));
                counters.maybeSample(probe);
            }
        }
        return counters.finish();
    }

    static long[] pgStats(final Connection c) throws Exception {
        long dead = 0;
        long journalBytes = 0;
        long snapsBytes = 0;
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT relname, n_dead_tup, pg_total_relation_size('public.'||relname) "
                                + "FROM pg_stat_user_tables "
                                + "WHERE relname IN ('things_journal','things_snaps')")) {
            while (rs.next()) {
                dead += rs.getLong(2);
                if ("things_journal".equals(rs.getString(1))) {
                    journalBytes = rs.getLong(3);
                } else {
                    snapsBytes = rs.getLong(3);
                }
            }
        }
        return new long[]{dead, journalBytes, snapsBytes};
    }

    static long[] mongoStats(final MongoDatabase db) {
        final Document j = db.runCommand(new Document("collStats", PersistenceShapes.JOURNAL));
        final Document s = db.runCommand(new Document("collStats", PersistenceShapes.SNAPS));
        return new long[]{-1L,
                ((Number) j.getOrDefault("storageSize", 0)).longValue()
                        + ((Number) j.getOrDefault("totalIndexSize", 0)).longValue(),
                ((Number) s.getOrDefault("storageSize", 0)).longValue()
                        + ((Number) s.getOrDefault("totalIndexSize", 0)).longValue()};
    }

    static String samplesMarkdown(final SweepResult result) {
        final List<List<String>> rows = new ArrayList<>();
        for (final SweepSample s : result.samples()) {
            rows.add(List.of(String.valueOf(s.windowIndex()), String.valueOf(s.pidsDone()),
                    String.valueOf(s.rowsDeleted()), String.format("%.1f", s.pidsPerSec()),
                    String.format("%.0f", s.rowsPerSec()),
                    s.deadTuples() < 0 ? "—" : String.valueOf(s.deadTuples()),
                    String.format("%.1f MB", s.journalTotalBytes() / 1048576.0),
                    String.format("%.1f MB", s.snapsTotalBytes() / 1048576.0),
                    s.vacuum()));
        }
        return BenchProtocol.table(List.of("window", "pids done", "rows deleted", "pids/s", "rows/s",
                "dead tuples", "journal size", "snaps size", "autovacuum in progress"), rows);
    }
}
