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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 */
public final class ChurnEngine {

    private ChurnEngine() {
    }

    public record Sample(int windowIndex, long ops, double p50Ms, double p95Ms, double maxMs,
            long walBytesDelta, long journalTotalBytes, long snapsTotalBytes, long deadTuples) {}

    public record ChurnResult(String backend, long totalOps, long totalSnapshots, double achievedRate,
            List<Sample> samples) {}

    public static ChurnResult runPg(final String pidPrefix, final int durationSeconds,
            final int targetRate, final int workers, final int pidsPerWorker) throws Exception {
        final Recorder recorder = new Recorder();
        final AtomicLong snapshots = new AtomicLong();
        final List<Thread> threads = new ArrayList<>();
        final long endNanos = System.nanoTime() + durationSeconds * 1_000_000_000L;
        for (int w = 0; w < workers; w++) {
            final int worker = w;
            final Thread t = new Thread(() -> {
                try (Connection c = BenchConfig.openPg()) {
                    c.setAutoCommit(false);
                    try (PreparedStatement insert = c.prepareStatement(PersistenceShapes.PG_INSERT_EVENT);
                            PreparedStatement upsert = c.prepareStatement(PersistenceShapes.PG_UPSERT_SEQ);
                            PreparedStatement snap = c.prepareStatement(PersistenceShapes.PG_SAVE_SNAPSHOT)) {
                        final long[] sns = new long[pidsPerWorker];
                        final double nanosPerOp = 1_000_000_000.0 * workers / targetRate;
                        final long start = System.nanoTime();
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
                            final String pid = pidPrefix + ":w" + worker + "-p" + k;
                            final long sn = ++sns[k];
                            final long t0 = System.nanoTime();
                            insert.setString(1, pid);
                            insert.setLong(2, sn);
                            insert.setString(3, CorpusGenerator.MANIFEST_ATTRIBUTE);
                            insert.setObject(4, c.createArrayOf("text", new String[0]));
                            insert.setString(5, churnEventJson(pid, sn));
                            insert.executeUpdate();
                            upsert.setString(1, pid);
                            upsert.setLong(2, sn);
                            upsert.executeUpdate();
                            c.commit();
                            if (sn % CorpusGenerator.SNAPSHOT_EVERY == 0) {
                                snap.setString(1, pid);
                                snap.setLong(2, sn);
                                snap.setString(3, churnSnapshotJson(pid, sn));
                                snap.setString(4, null);
                                snap.setObject(5, Instant.now().atOffset(ZoneOffset.UTC));
                                snap.executeUpdate();
                                c.commit();
                                snapshots.incrementAndGet();
                            }
                            recorder.record((System.nanoTime() - t0) / 1_000_000.0);
                            opIdx++;
                        }
                    }
                } catch (final Exception e) {
                    throw new IllegalStateException("pg churn worker failed", e);
                }
            }, "pg-churn-" + w);
            threads.add(t);
        }
        final List<Sample> samples = new ArrayList<>();
        try (Connection sampler = BenchConfig.openPg()) {
            runAndSample(threads, durationSeconds, recorder, samples,
                    windowIndex -> pgWindowStats(sampler, windowIndex));
        }
        return finish("pg", recorder, snapshots.get(), durationSeconds, samples);
    }

    public static ChurnResult runMongo(final String pidPrefix, final int durationSeconds,
            final int targetRate, final int workers, final int pidsPerWorker) throws Exception {
        final Recorder recorder = new Recorder();
        final AtomicLong snapshots = new AtomicLong();
        final List<Thread> threads = new ArrayList<>();
        final long endNanos = System.nanoTime() + durationSeconds * 1_000_000_000L;
        try (MongoClient client = BenchConfig.openMongo()) {
            final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
            final MongoCollection<Document> journal =
                    db.getCollection(PersistenceShapes.JOURNAL).withWriteConcern(WriteConcern.JOURNALED);
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
                    final long[] sns = new long[pidsPerWorker];
                    final double nanosPerOp = 1_000_000_000.0 * workers / targetRate;
                    final long start = System.nanoTime();
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
                        final String pid = pidPrefix + ":w" + worker + "-p" + k;
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
                }, "mongo-churn-" + w);
                threads.add(t);
            }
            final List<Sample> samples = new ArrayList<>();
            runAndSample(threads, durationSeconds, recorder, samples,
                    windowIndex -> mongoWindowStats(db, windowIndex));
            realtimeExecutor.shutdown();
            realtimeExecutor.awaitTermination(30, TimeUnit.SECONDS);
            return finish("mongo", recorder, snapshots.get(), durationSeconds, samples);
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
            final long snapshotCount, final int durationSeconds, final List<Sample> samples) {
        return new ChurnResult(backend, recorder.total(), snapshotCount,
                recorder.total() / (double) durationSeconds, samples);
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
