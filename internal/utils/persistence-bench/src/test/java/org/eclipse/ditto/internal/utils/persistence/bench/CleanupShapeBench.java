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
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Group C shapes. C1: one getNewestSnapshotsAbove page (PG DISTINCT ON vs the MongoReadJournal
 * aggregation), cleanup parameters (includeDeleted=true, minAge=0, no pid filter, batch 100).
 * C2: the full single-pid cleanup sequence Cleanup.java performs — min-event-sn, batched event
 * range deletes tiling to snapshotSn-1, min-snapshot-sn, batched snapshot range deletes keeping
 * the latest. NOTE (plan deviation 2): no journal_seq.deleted_to update — the real cleanup path
 * does not touch it.
 */
public final class CleanupShapeBench {

    @Test
    public void c1PidStreamPage() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final int n = BenchConfig.warmups() + BenchConfig.iterations();
        // deterministic lower bounds spread over the pid space; iteration 0 = "" (the first page)
        final List<String> lowerBounds = new ArrayList<>(n);
        lowerBounds.add("");
        for (int i = 1; i < n; i++) {
            lowerBounds.add(gen.pid((long) i * (gen.pidCount() / n)));
        }
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
        BenchProtocol.writeEvidence("c1-pid-stream-page.md",
                BenchProtocol.runHeader("C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)")
                        + RecoveryBench.resultTable(List.of(
                                RecoveryBench.sideBySide("one page, varying lower bound", pgT, mongoT)))
                        + "\n" + plans
                        + "\nCleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), "
                        + "minAge=0 (age predicates disabled), pidFilter='' — exactly what Cleanup.java passes.\n");
    }

    @Test
    public void c2SinglePidCleanup() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        // scan from mid-corpus: disjoint from RecoveryBench's pids (which scan from 0) at full scale
        final List<Long> p99 = gen.findPidIndexes(CorpusGenerator.PidClass.P99, 23, gen.pidCount() / 2);
        final List<Long> outlier = gen.findPidIndexes(CorpusGenerator.PidClass.OUTLIER, 23, gen.pidCount() / 2);

        final List<List<String>> rows = new ArrayList<>();
        final StringBuilder notes = new StringBuilder();
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
            if (BenchConfig.runPg()) {
                try (Connection c = BenchConfig.openPg()) {
                    pgR = pgCleanupClass(c, gen, found, warm);
                }
            }
            if (BenchConfig.runMongo()) {
                try (MongoClient client = BenchConfig.openMongo()) {
                    mongoR = mongoCleanupClass(client.getDatabase(BenchConfig.mongoDbName()), gen, found, warm);
                }
            }
            rows.add(RecoveryBench.sideBySide(label + " total per-pid",
                    pgR == null ? null : pgR.totals(), mongoR == null ? null : mongoR.totals()));
            rows.add(RecoveryBench.sideBySide(label + " per delete batch (100 rows)",
                    pgR == null ? null : pgR.batches(), mongoR == null ? null : mongoR.batches()));
        }
        BenchProtocol.writeEvidence("c2-single-pid-cleanup.md",
                BenchProtocol.runHeader("C2 — single-pid cleanup (batched deletes, batch size 100)")
                        + RecoveryBench.resultTable(rows) + "\n" + notes
                        + "\nSequence per pid (Cleanup.java 1:1): min event sn -> event ranges tiling to "
                        + "snapshotSn-1 -> min snapshot sn -> snapshot ranges keeping the latest. "
                        + "No journal_seq.deleted_to update — the real cleanup path does not touch it "
                        + "(plan deviation 2). Mongo deleteMany has no executionStats — timings only. "
                        + "C2 consumes these pids' cleanup backlog; rerun 'load' for a pristine corpus.\n");
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
