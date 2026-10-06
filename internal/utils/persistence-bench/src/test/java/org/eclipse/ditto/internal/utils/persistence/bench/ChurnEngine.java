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
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.bson.Document;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReplaceOptions;

/**
 * Sustained-churn engine (W3 protocol, reused by M1): N workers, each cycling its own pool of
 * churn pids round-robin, rate-paced appends in the exact plugin write shape (PG: event INSERT +
 * journal_seq upsert in one txn; Mongo: 1-doc ordered bulkWrite JOURNALED + async realtime insert),
 * plus a snapshot write every 500 events per pid. A sampler thread captures 30 s windows.
 * <p>
 * Profiling knobs (W3 only, see {@link PgAppendShape}): the PG append shape, the Mongo journal write
 * concern and a pg_stat_activity wait-event sampler. Every run also records server-side counter
 * deltas, and PG runs the heap placement of the run's rows.
 * </p>
 */
public final class ChurnEngine {

    private ChurnEngine() {
    }

    public record Sample(int windowIndex, long ops, double p50Ms, double p95Ms, double maxMs,
            long walBytesDelta, long journalTotalBytes, long snapsTotalBytes, long deadTuples) {}

    /**
     * @param profile markdown with the run's server-side evidence and, for PostgreSQL, the per-step timings
     * (printed by W3; M1 prints only the windows).
     */
    public record ChurnResult(String backend, long totalOps, long totalSnapshots, double achievedRate,
            List<Sample> samples, String profile) {}

    /** The canonical shape: the plugin's append transaction on pgjdbc, no wait-event sampler, no stagger. */
    public static ChurnResult runPg(final String pidPrefix, final int durationSeconds,
            final int targetRate, final int workers, final int pidsPerWorker) throws Exception {
        return runPg(pidPrefix, durationSeconds, targetRate, workers, pidsPerWorker, PgAppendShape.TXN, 0, false);
    }

    public static ChurnResult runPg(final String pidPrefix, final int durationSeconds,
            final int targetRate, final int workers, final int pidsPerWorker, final PgAppendShape shape,
            final int waitSampleMillis, final boolean stagger) throws Exception {
        final Recorder recorder = new Recorder();
        final AtomicLong snapshots = new AtomicLong();
        final List<Thread> threads = new ArrayList<>();
        final PhaseRecorder phaseRecorder = new PhaseRecorder();
        final List<List<String>> windowPhaseRows = new ArrayList<>();
        final List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        final int phaseCount = shape.phases().size();
        final PgAppenders.R2dbcPath r2dbc = shape.usesR2dbc() ? PgAppenders.R2dbcPath.open() : null;
        final Schedule schedule = new Schedule(workers, targetRate, durationSeconds, stagger);
        for (int w = 0; w < workers; w++) {
            final int worker = w;
            final Thread t = new Thread(() -> {
                try (PgAppenders.PgAppender appender =
                        r2dbc != null ? r2dbc.appender(shape) : PgAppenders.jdbc(shape)) {
                    final long[] sns = new long[pidsPerWorker];
                    final double nanosPerOp = schedule.nanosPerOp;
                    final long endNanos = schedule.endNanos;
                    final long start = schedule.start(worker);
                    long opIdx = 0;
                    int pidCursor = 0;
                    while (System.nanoTime() < endNanos) {
                        final long scheduled = start + (long) (opIdx * nanosPerOp);
                        long now;
                        while ((now = System.nanoTime()) < scheduled) {
                            LockSupport.parkNanos(Math.min(scheduled - now, 1_000_000L));
                        }
                        final int k = pidCursor;
                        pidCursor = (pidCursor + 1) % pidsPerWorker;
                        final String pid = churnPid(pidPrefix, worker, k);
                        final long sn = ++sns[k];
                        final long t0 = System.nanoTime();
                        final long[] phases = new long[phaseCount];
                        appender.append(pid, sn, churnEventJson(pid, sn), phases);
                        if (sn % CorpusGenerator.SNAPSHOT_EVERY == 0) {
                            appender.saveSnapshot(pid, sn, churnSnapshotJson(pid, sn));
                            snapshots.incrementAndGet();
                        }
                        recorder.record((System.nanoTime() - t0) / 1_000_000.0);
                        if (phaseCount > 0) {
                            phaseRecorder.record(phases);
                        }
                        opIdx++;
                    }
                } catch (final Exception e) {
                    failures.add(e);
                    throw new IllegalStateException("pg churn worker failed", e);
                }
            }, "pg-churn-" + w);
            threads.add(t);
        }
        final List<Sample> samples = new ArrayList<>();
        final StringBuilder profile = new StringBuilder();
        try (Connection sampler = BenchConfig.openPg()) {
            final Map<String, Double> before = PgServerProbe.counters(sampler);
            final long blocksAtStart = PgServerProbe.relationBlocks(sampler, PersistenceShapes.JOURNAL);
            final PgServerProbe.WaitSampler waits =
                    waitSampleMillis > 0 ? new PgServerProbe.WaitSampler(waitSampleMillis) : null;
            final AtomicReference<Map<String, Double>> lastCounters = new AtomicReference<>(before);
            final AtomicLong lastOps = new AtomicLong();
            try {
                runAndSample(threads, durationSeconds, recorder, samples,
                        windowIndex -> {
                            failFast(failures, "pg");
                            final List<String> row = windowPhaseRow(shape, windowIndex, phaseRecorder.swap());
                            final Map<String, Double> now = PgServerProbe.counters(sampler);
                            final long ops = recorder.total();
                            row.addAll(PgServerProbe.windowCells(lastCounters.getAndSet(now), now,
                                    ops - lastOps.getAndSet(ops)));
                            windowPhaseRows.add(row);
                            System.out.println("[bench] window " + windowIndex + " steps/server: " + row);
                            return pgWindowStats(sampler, windowIndex);
                        });
            } finally {
                if (waits != null) {
                    waits.close();
                }
                if (r2dbc != null) {
                    profile.append("- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the "
                            + "end of the run: ").append(r2dbc.poolMetrics()).append('\n');
                    r2dbc.close();
                }
            }
            failFast(failures, "pg");
            // closed backends report their statistics on exit; give that a moment before the final read
            Thread.sleep(2_000);
            final Map<String, Double> after = PgServerProbe.counters(sampler);
            final List<String> pids = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                for (int k = 0; k < pidsPerWorker; k++) {
                    pids.add(churnPid(pidPrefix, w, k));
                }
            }
            profile.append(schedule.markdown())
                    .append(PgServerProbe.placementMarkdown(sampler, pids, blocksAtStart)).append('\n')
                    .append(phaseMarkdown(shape, phaseRecorder.all())).append('\n');
            if (!windowPhaseRows.isEmpty()) {
                final List<String> header = new ArrayList<>(List.of("window"));
                shape.phases().forEach(phase -> header.add(phase + " p50 ms"));
                header.addAll(PgServerProbe.WINDOW_HEADERS);
                profile.append("Per-window step p50s, WAL flushes and buffer write-back:\n\n")
                        .append(BenchProtocol.table(header, windowPhaseRows)).append('\n');
            }
            profile.append("Server-side counters over the run:\n\n")
                    .append(PgServerProbe.deltaMarkdown(before, after, recorder.total()));
            if (waits != null) {
                profile.append("\nWait events of the churn backends (pg_stat_activity, every ")
                        .append(waitSampleMillis).append(" ms):\n\n").append(waits.markdown());
            }
        }
        return finish("pg", recorder, snapshots.get(), durationSeconds, samples, profile.toString());
    }

    /** A run with a dead worker measures fewer workers than it reports: abort it instead of writing evidence. */
    private static void failFast(final List<Exception> failures, final String backend) {
        if (!failures.isEmpty()) {
            throw new IllegalStateException(failures.size() + " " + backend + " churn worker(s) failed",
                    failures.get(0));
        }
    }

    static String churnPid(final String pidPrefix, final int worker, final int k) {
        return pidPrefix + ":w" + worker + "-p" + k;
    }

    /** p50/p95/mean per timed step over all ops of the run; empty when the shape times only the total. */
    static String phaseMarkdown(final PgAppendShape shape, final List<long[]> samples) {
        if (shape.phases().isEmpty()) {
            return "- per-step timings: n/a for shape " + shape.label() + " (total only)\n";
        }
        final List<List<String>> rows = new ArrayList<>();
        for (int p = 0; p < shape.phases().size(); p++) {
            final List<Double> ms = stepMillis(samples, p);
            if (ms.isEmpty()) {
                continue;
            }
            final BenchProtocol.Timing t = BenchProtocol.of(ms);
            rows.add(List.of(shape.phases().get(p), String.valueOf(t.n()), String.format("%.3f", t.p50Ms()),
                    String.format("%.3f", t.p95Ms()), String.format("%.3f", t.meanMs())));
        }
        return "Per-step client timings over the whole run (shape " + shape.label() + "):\n\n"
                + BenchProtocol.table(List.of("step", "n", "p50 ms", "p95 ms", "mean ms"), rows);
    }

    private static List<String> windowPhaseRow(final PgAppendShape shape, final int windowIndex,
            final List<long[]> samples) {
        final List<String> row = new ArrayList<>(List.of(String.valueOf(windowIndex)));
        for (int p = 0; p < shape.phases().size(); p++) {
            final List<Double> ms = stepMillis(samples, p);
            row.add(ms.isEmpty() ? "—" : String.format("%.3f", BenchProtocol.of(ms).p50Ms()));
        }
        return row;
    }

    private static List<Double> stepMillis(final List<long[]> samples, final int step) {
        final List<Double> ms = new ArrayList<>(samples.size());
        for (final long[] phases : samples) {
            ms.add(phases[step] / 1_000_000.0);
        }
        return ms;
    }

    /** Step timings of every op (whole run) and of the current sampling window. */
    static final class PhaseRecorder {

        private final List<long[]> all = new ArrayList<>();
        private List<long[]> window = new ArrayList<>();

        synchronized void record(final long[] phases) {
            all.add(phases);
            window.add(phases);
        }

        synchronized List<long[]> swap() {
            final List<long[]> old = window;
            window = new ArrayList<>();
            return old;
        }

        synchronized List<long[]> all() {
            return new ArrayList<>(all);
        }
    }

    /**
     * The workers' op schedules: worker w issues op k at {@code start(w) + k * nanosPerOp}. By default each worker
     * starts its schedule when its connection is ready, as W3 always did; staggered, the starts are spread evenly
     * over one period from a common base, so the target rate arrives as an even stream instead of in bursts.
     */
    static final class Schedule {

        /** Lead time for the staggered common base, so every worker's connection is open before it. */
        private static final long STAGGER_LEAD_NANOS = 1_000_000_000L;

        final double nanosPerOp;
        final long endNanos;
        private final boolean stagger;
        private final long base;
        private final long[] starts;

        Schedule(final int workers, final int targetRate, final int durationSeconds, final boolean stagger) {
            this.nanosPerOp = 1_000_000_000.0 * workers / targetRate;
            this.stagger = stagger;
            final long now = System.nanoTime();
            this.base = now + (stagger ? STAGGER_LEAD_NANOS : 0L);
            this.endNanos = base + durationSeconds * 1_000_000_000L;
            this.starts = new long[workers];
        }

        long start(final int worker) {
            final long start = stagger ? base + (long) (worker * nanosPerOp / starts.length) : System.nanoTime();
            synchronized (starts) {
                starts[worker] = start;
            }
            return start;
        }

        /** Each worker's schedule offset within one period, relative to the earliest worker, ascending. */
        String markdown() {
            final long[] copy;
            synchronized (starts) {
                copy = starts.clone();
            }
            final long min = java.util.Arrays.stream(copy).min().orElse(0L);
            final String offsets = java.util.Arrays.stream(copy)
                    .mapToDouble(s -> ((s - min) % (long) nanosPerOp) / 1_000_000.0)
                    .sorted()
                    .mapToObj(ms -> String.format("%.2f", ms))
                    .collect(java.util.stream.Collectors.joining(", "));
            return String.format("- worker schedules (%s): offsets within the %.1f ms period: %s ms%n",
                    stagger ? "staggered evenly" : "each worker starts when its connection is ready",
                    nanosPerOp / 1_000_000.0, offsets);
        }
    }

    public static ChurnResult runMongo(final String pidPrefix, final int durationSeconds,
            final int targetRate, final int workers, final int pidsPerWorker) throws Exception {
        return runMongo(pidPrefix, durationSeconds, targetRate, workers, pidsPerWorker, WriteConcern.JOURNALED,
                false);
    }

    public static ChurnResult runMongo(final String pidPrefix, final int durationSeconds,
            final int targetRate, final int workers, final int pidsPerWorker, final WriteConcern journalWriteConcern,
            final boolean stagger) throws Exception {
        final Recorder recorder = new Recorder();
        final AtomicLong snapshots = new AtomicLong();
        final List<Thread> threads = new ArrayList<>();
        final List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        final Schedule schedule = new Schedule(workers, targetRate, durationSeconds, stagger);
        try (MongoClient client = BenchConfig.openMongo()) {
            final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
            final MongoCollection<Document> journal =
                    db.getCollection(PersistenceShapes.JOURNAL).withWriteConcern(journalWriteConcern);
            final MongoCollection<Document> realtime =
                    db.getCollection(PersistenceShapes.REALTIME).withWriteConcern(WriteConcern.JOURNALED);
            final MongoCollection<Document> snaps =
                    db.getCollection(PersistenceShapes.SNAPS).withWriteConcern(WriteConcern.JOURNALED);
            final ExecutorService realtimeExecutor = Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "realtime-append");
                t.setDaemon(true);
                return t;
            });
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                final Thread t = new Thread(() -> {
                    try {
                        mongoWorker(worker, pidPrefix, pidsPerWorker, schedule, journal, realtime, snaps,
                                realtimeExecutor, recorder, snapshots);
                    } catch (final RuntimeException e) {
                        failures.add(e);
                        throw e;
                    }
                }, "mongo-churn-" + w);
                threads.add(t);
            }
            final List<Sample> samples = new ArrayList<>();
            final Map<String, Double> before = MongoServerProbe.counters(db);
            runAndSample(threads, durationSeconds, recorder, samples,
                    windowIndex -> {
                        failFast(failures, "mongo");
                        return mongoWindowStats(db, windowIndex);
                    });
            realtimeExecutor.shutdown();
            realtimeExecutor.awaitTermination(30, TimeUnit.SECONDS);
            failFast(failures, "mongo");
            final Map<String, Double> after = MongoServerProbe.counters(db);
            final String profile = "- journal write concern: " + journalWriteConcern.asDocument().toJson()
                    + " (realtime + snapshot writes stay JOURNALED)\n" + schedule.markdown()
                    + "\nServer-side counters over the run:\n\n"
                    + MongoServerProbe.deltaMarkdown(before, after, recorder.total());
            return finish("mongo", recorder, snapshots.get(), durationSeconds, samples, profile);
        }
    }

    private static void mongoWorker(final int worker, final String pidPrefix, final int pidsPerWorker,
            final Schedule schedule, final MongoCollection<Document> journal, final MongoCollection<Document> realtime,
            final MongoCollection<Document> snaps, final ExecutorService realtimeExecutor, final Recorder recorder,
            final AtomicLong snapshots) {
        final long[] sns = new long[pidsPerWorker];
        final double nanosPerOp = schedule.nanosPerOp;
        final long endNanos = schedule.endNanos;
        final long start = schedule.start(worker);
        long opIdx = 0;
        int pidCursor = 0;
        while (System.nanoTime() < endNanos) {
            final long scheduled = start + (long) (opIdx * nanosPerOp);
            long now;
            while ((now = System.nanoTime()) < scheduled) {
                LockSupport.parkNanos(Math.min(scheduled - now, 1_000_000L));
            }
            final int k = pidCursor;
            pidCursor = (pidCursor + 1) % pidsPerWorker;
            final String pid = churnPid(pidPrefix, worker, k);
            final long sn = ++sns[k];
            final long t0 = System.nanoTime();
            final Document atom = PersistenceShapes.journalAtom(pid, sn,
                    CorpusGenerator.MANIFEST_ATTRIBUTE, Document.parse(churnEventJson(pid, sn)));
            journal.bulkWrite(List.of(new InsertOneModel<>(atom)));
            // plugin batchAppend: realtime insert is fire-and-forget, same doc instance
            realtimeExecutor.submit(() -> realtime.insertOne(atom));
            if (sn % CorpusGenerator.SNAPSHOT_EVERY == 0) {
                final long ts = System.currentTimeMillis();
                snaps.replaceOne(
                        new Document("pid", pid).append("sn", sn).append("ts", ts),
                        PersistenceShapes.snapshotDoc(pid, sn, ts,
                                Document.parse(churnSnapshotJson(pid, sn))),
                        new ReplaceOptions().upsert(true));
                snapshots.incrementAndGet();
            }
            recorder.record((System.nanoTime() - t0) / 1_000_000.0);
            opIdx++;
        }
    }

    // --- sampling machinery ------------------------------------------------------------------------

    interface WindowStats {
        long[] stats(int windowIndex) throws Exception;   // {walDelta, journalBytes, snapsBytes, deadTuples}
    }

    private static void runAndSample(final List<Thread> threads, final int durationSeconds,
            final Recorder recorder, final List<Sample> out, final WindowStats windowStats)
            throws Exception {
        threads.forEach(Thread::start);
        final int interval = BenchConfig.sampleIntervalSeconds();
        int windowIndex = 0;
        final long endMillis = System.currentTimeMillis() + durationSeconds * 1_000L;
        while (System.currentTimeMillis() < endMillis) {
            Thread.sleep(Math.min(interval * 1_000L, Math.max(1, endMillis - System.currentTimeMillis())));
            final List<Double> window = recorder.swap();
            final long[] s = windowStats.stats(windowIndex);
            final BenchProtocol.Timing t = window.isEmpty()
                    ? new BenchProtocol.Timing(0, 0, 0, 0, 0, List.of())
                    : BenchProtocol.of(window);
            out.add(new Sample(windowIndex, window.size(), t.p50Ms(), t.p95Ms(), t.maxMs(),
                    s[0], s[1], s[2], s[3]));
            System.out.printf("[bench] window %d: ops=%d p50=%.2f p95=%.2f max=%.2f%n",
                    windowIndex, window.size(), t.p50Ms(), t.p95Ms(), t.maxMs());
            windowIndex++;
        }
        for (final Thread t : threads) {
            t.join(60_000);
        }
    }

    static final class Recorder {
        // record()/swap() are both synchronized on `this`, so a swap can never interleave between
        // a record()'s list lookup and its list.add(): either the add happens-before the swap (the
        // sample lands in the window swap() is about to return) or it happens-after (the sample lands
        // in the fresh list swap() just installed, to be picked up by the next swap()). There is no
        // point at which a sample can be added to a list that has already been detached and handed
        // back by swap() but is no longer reachable from `current` — that orphaned-list window is what
        // let samples silently disappear from every window while still being counted in total(). With
        // both methods exclusive on the same monitor, sum(window.ops) == total() always holds. Worker
        // count is small (single digits), so lock contention here is not a concern for a bench harness.
        private List<Double> current = new ArrayList<>();
        private final AtomicLong total = new AtomicLong();

        synchronized void record(final double ms) {
            total.incrementAndGet();
            current.add(ms);
        }

        synchronized List<Double> swap() {
            final List<Double> old = current;
            current = new ArrayList<>();
            return old;
        }

        long total() {
            return total.get();
        }
    }

    private static ChurnResult finish(final String backend, final Recorder recorder,
            final long snapshotCount, final int durationSeconds, final List<Sample> samples, final String profile) {
        return new ChurnResult(backend, recorder.total(), snapshotCount,
                recorder.total() / (double) durationSeconds, samples, profile);
    }

    private static String lastWal;

    private static long[] pgWindowStats(final Connection c, final int windowIndex) throws Exception {
        try (Statement st = c.createStatement()) {
            long walDelta = 0;
            try (ResultSet rs = st.executeQuery("SELECT pg_current_wal_lsn()::text")) {
                rs.next();
                final String wal = rs.getString(1);
                if (windowIndex > 0 && lastWal != null) {
                    try (Statement st2 = c.createStatement();
                            ResultSet rs2 = st2.executeQuery(
                                    "SELECT pg_wal_lsn_diff('" + wal + "'::pg_lsn, '" + lastWal + "'::pg_lsn)")) {
                        rs2.next();
                        walDelta = rs2.getLong(1);
                    }
                }
                lastWal = wal;
            }
            long journalBytes = 0;
            long snapsBytes = 0;
            long dead = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT relname, pg_total_relation_size('public.'||relname), n_dead_tup "
                            + "FROM pg_stat_user_tables WHERE relname IN ('things_journal','things_snaps')")) {
                while (rs.next()) {
                    if ("things_journal".equals(rs.getString(1))) {
                        journalBytes = rs.getLong(2);
                    } else {
                        snapsBytes = rs.getLong(2);
                    }
                    dead += rs.getLong(3);
                }
            }
            return new long[]{walDelta, journalBytes, snapsBytes, dead};
        }
    }

    private static long[] mongoWindowStats(final MongoDatabase db, final int windowIndex) {
        final Document j = db.runCommand(new Document("collStats", PersistenceShapes.JOURNAL));
        final Document s = db.runCommand(new Document("collStats", PersistenceShapes.SNAPS));
        return new long[]{-1L,
                ((Number) j.getOrDefault("storageSize", 0)).longValue()
                        + ((Number) j.getOrDefault("totalIndexSize", 0)).longValue(),
                ((Number) s.getOrDefault("storageSize", 0)).longValue()
                        + ((Number) s.getOrDefault("totalIndexSize", 0)).longValue(),
                -1L};
    }

    // --- churn payloads (same shape/size as the corpus generator's band-B events) --------------------

    static String churnEventJson(final String pid, final long sn) {
        return org.eclipse.ditto.json.JsonObject.newBuilder()
                .set("type", "things.events:attributeModified")
                .set("thingId", pid.substring("thing:".length()))
                .set("revision", sn)
                .set("path", "/attributes/bench")
                .set("value", "x".repeat(400))
                .set("_timestamp", Instant.now().toString())
                .build()
                .toString();
    }

    static String churnSnapshotJson(final String pid, final long sn) {
        return org.eclipse.ditto.json.JsonObject.newBuilder()
                .set("thingId", pid.substring("thing:".length()))
                .set("policyId", pid.substring("thing:".length()))
                .set("_revision", sn)
                .set("_modified", Instant.now().toString())
                .set("attributes", org.eclipse.ditto.json.JsonObject.newBuilder()
                        .set("bench", "x".repeat(4_000))
                        .build())
                .build()
                .toString();
    }

    static String samplesMarkdown(final ChurnResult result) {
        final List<List<String>> rows = new ArrayList<>();
        for (final Sample s : result.samples()) {
            rows.add(List.of(String.valueOf(s.windowIndex()), String.valueOf(s.ops()),
                    String.format("%.2f", s.p50Ms()), String.format("%.2f", s.p95Ms()),
                    String.format("%.2f", s.maxMs()),
                    s.walBytesDelta() < 0 ? "—" : String.format("%.1f MB", s.walBytesDelta() / 1048576.0),
                    String.format("%.1f MB", s.journalTotalBytes() / 1048576.0),
                    String.format("%.1f MB", s.snapsTotalBytes() / 1048576.0),
                    s.deadTuples() < 0 ? "—" : String.valueOf(s.deadTuples())));
        }
        return BenchProtocol.table(List.of("window", "ops", "p50 ms", "p95 ms", "max ms",
                "WAL delta", "journal size", "snaps size", "dead tuples"), rows);
    }
}
