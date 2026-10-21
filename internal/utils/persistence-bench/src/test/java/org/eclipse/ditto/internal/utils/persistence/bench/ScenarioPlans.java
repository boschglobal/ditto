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
import java.time.Instant;
import java.util.List;

import org.bson.Document;

import com.mongodb.client.MongoDatabase;

/**
 * One representative plan per statement shape for the scenarios whose timed loops do not show one: EXPLAIN (ANALYZE,
 * BUFFERS) on PostgreSQL, writes inside a rolled-back transaction ({@link BenchProtocol#pgExplainRolledBack}), and
 * explain (executionStats) on MongoDB where its explain command has a form for the operation. Every method leaves the
 * corpus as it found it. Call them outside timed loops.
 */
final class ScenarioPlans {

    private ScenarioPlans() {
    }

    // --- PostgreSQL ---------------------------------------------------------------------------

    /** W1/W3/M1 append: event INSERT + journal_seq upsert in one transaction, rolled back. */
    static String pgAppend(final Connection c, final String pid, final long sn) {
        return BenchProtocol.pgExplainRolledBack(c,
                BenchProtocol.inlineLiterals(PersistenceShapes.PG_INSERT_EVENT, pid, sn,
                        CorpusGenerator.MANIFEST_ATTRIBUTE, "{}", ChurnEngine.churnEventJson(pid, sn)),
                BenchProtocol.inlineLiterals(PersistenceShapes.PG_UPSERT_SEQ, pid, sn));
    }

    /** W2 snapshot upsert with lifecycle NULL (as SnapshotBench writes it), rolled back. */
    static String pgSnapshotWrite(final Connection c, final String pid, final long sn, final Instant writtenAt) {
        return BenchProtocol.pgExplainRolledBack(c,
                BenchProtocol.inlineLiterals(PersistenceShapes.PG_SAVE_SNAPSHOT, pid, sn,
                        ChurnEngine.churnSnapshotJson(pid, sn), null, writtenAt.toString()));
    }

    /**
     * C2/C3/M1 per-pid cleanup as SweepEngine issues it: the two min-sn reads and the FIRST delete batch of each table
     * (the batches differ only in their sn range); the deletes are rolled back.
     */
    static String pgCleanupPid(final Connection c, final String pid, final long snapshotSn) throws SQLException {
        final Long evMin = pgMin(c, PersistenceShapes.PG_MIN_EVENT_SN, pid);
        final Long snMin = pgMin(c, PersistenceShapes.PG_MIN_SNAP_SN, pid);
        final CleanupPlanner.Range ev = firstRangeOrEmpty(CleanupPlanner.eventDeleteRanges(
                evMin == null ? snapshotSn : evMin, snapshotSn, PersistenceShapes.WRITES_PER_CREDIT), snapshotSn);
        final CleanupPlanner.Range sn = firstRangeOrEmpty(CleanupPlanner.snapshotDeleteRanges(
                snMin == null ? snapshotSn : snMin, snapshotSn, false, false, PersistenceShapes.WRITES_PER_CREDIT),
                snapshotSn);
        return batchLine(pid, evMin, snMin, snapshotSn, ev, sn)
                + BenchProtocol.pgExplain(c, BenchProtocol.inlineLiterals(PersistenceShapes.PG_MIN_EVENT_SN, pid))
                + BenchProtocol.pgExplainRolledBack(c, BenchProtocol.inlineLiterals(PersistenceShapes.PG_DELETE_EVENTS,
                        pid, ev.fromSn(), ev.toSn()))
                + BenchProtocol.pgExplain(c, BenchProtocol.inlineLiterals(PersistenceShapes.PG_MIN_SNAP_SN, pid))
                + BenchProtocol.pgExplainRolledBack(c, BenchProtocol.inlineLiterals(PersistenceShapes.PG_DELETE_SNAPS,
                        pid, sn.fromSn(), sn.toSn()));
    }

    /** The C1-shaped page as C3 and M1 issue it (minAge 0, no pid filter). */
    static String pgPage(final Connection c, final String lowerBound) {
        return BenchProtocol.pgExplain(c, BenchProtocol.inlineLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE,
                lowerBound, "", "", true, "0 seconds", PersistenceShapes.READS_PER_QUERY, true, "0 seconds"));
    }

    /** C4: the R2 replay from {@code fromSn}. */
    static String pgReplay(final Connection c, final String pid, final long fromSn) {
        return BenchProtocol.pgExplain(c,
                BenchProtocol.inlineLiterals(PersistenceShapes.PG_REPLAY, pid, fromSn, Long.MAX_VALUE));
    }

    /** The sn the next append of {@code pid} would write. */
    static long pgNextSn(final Connection c, final String pid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(sn), 0) + 1 FROM things_journal WHERE pid = ?")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Long pgMin(final Connection c, final String sql, final String pid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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

    // --- MongoDB ------------------------------------------------------------------------------

    static String mongoAppend() {
        return BenchProtocol.mongoNotExplainable("journal bulkWrite(InsertOne) and the realtime insertOne");
    }

    /** W2: replaceOne({pid, sn, ts}, snapshot, upsert) as SnapshotBench issues it. */
    static String mongoSnapshotWrite(final MongoDatabase db, final String pid, final long sn, final long tsMillis) {
        return BenchProtocol.mongoExplainReplaceUpsert(db, PersistenceShapes.SNAPS,
                new Document("pid", pid).append("sn", sn).append("ts", tsMillis),
                PersistenceShapes.snapshotDoc(pid, sn, tsMillis,
                        Document.parse(ChurnEngine.churnSnapshotJson(pid, sn))));
    }

    /** C2/C3/M1 per-pid cleanup: the two min finds and the first deleteMany per collection (explain only). */
    static String mongoCleanupPid(final MongoDatabase db, final String pid, final long snapshotSn) {
        final Document minEvDoc = db.getCollection(PersistenceShapes.JOURNAL).find(new Document("pid", pid))
                .sort(new Document("to", 1)).limit(1).projection(new Document("to", 1)).first();
        final Document minSnapDoc = db.getCollection(PersistenceShapes.SNAPS).find(new Document("pid", pid))
                .sort(new Document("sn", 1)).limit(1).projection(new Document("sn", 1)).first();
        final Long evMin = minEvDoc == null ? null : minEvDoc.getLong("to");
        final Long snMin = minSnapDoc == null ? null : minSnapDoc.getLong("sn");
        final CleanupPlanner.Range ev = firstRangeOrEmpty(CleanupPlanner.eventDeleteRanges(
                evMin == null ? snapshotSn : evMin, snapshotSn, PersistenceShapes.WRITES_PER_CREDIT), snapshotSn);
        final CleanupPlanner.Range sn = firstRangeOrEmpty(CleanupPlanner.snapshotDeleteRanges(
                snMin == null ? snapshotSn : snMin, snapshotSn, false, false, PersistenceShapes.WRITES_PER_CREDIT),
                snapshotSn);
        return batchLine(pid, evMin, snMin, snapshotSn, ev, sn)
                + BenchProtocol.mongoExplainFind(db, PersistenceShapes.JOURNAL, new Document("pid", pid),
                        new Document("to", 1), new Document("to", 1), 1)
                + BenchProtocol.mongoExplainDelete(db, PersistenceShapes.JOURNAL,
                        PersistenceShapes.deleteEventsFilter(pid, ev.fromSn(), ev.toSn()))
                + BenchProtocol.mongoExplainFind(db, PersistenceShapes.SNAPS, new Document("pid", pid),
                        new Document("sn", 1), new Document("sn", 1), 1)
                + BenchProtocol.mongoExplainDelete(db, PersistenceShapes.SNAPS,
                        PersistenceShapes.deleteSnapshotsFilter(pid, sn.fromSn(), sn.toSn()));
    }

    static String mongoPage(final MongoDatabase db, final String lowerBound) {
        return BenchProtocol.mongoExplainAggregate(db, PersistenceShapes.SNAPS,
                PersistenceShapes.newestSnapshotsPipeline(lowerBound, PersistenceShapes.READS_PER_QUERY));
    }

    static String mongoReplay(final MongoDatabase db, final String pid, final long fromSn) {
        return BenchProtocol.mongoExplainFind(db, PersistenceShapes.JOURNAL,
                PersistenceShapes.replayFilter(pid, fromSn, Long.MAX_VALUE), new Document("to", 1),
                new Document("events", 1), null);
    }

    // --- pure helpers -------------------------------------------------------------------------

    /** The first batch, or the empty range [snapshotSn, snapshotSn - 1] when the pid has nothing left to delete. */
    static CleanupPlanner.Range firstRangeOrEmpty(final List<CleanupPlanner.Range> ranges, final long snapshotSn) {
        return ranges.isEmpty() ? new CleanupPlanner.Range(snapshotSn, snapshotSn - 1) : ranges.get(0);
    }

    /**
     * The snapshotted corpus pid that sorts last: a sweep pass reaches it at its very end. Corpus pids have one
     * fixed-width shape, so Java's order, MongoDB's binary order and PostgreSQL's "C" collation agree on it.
     */
    static long lastSnapshottedPidIndex(final CorpusGenerator gen) {
        return extremeSnapshottedPidIndex(gen, true);
    }

    /** The snapshotted corpus pid that sorts first: the first pid of a sweep's first page. */
    static long firstSnapshottedPidIndex(final CorpusGenerator gen) {
        return extremeSnapshottedPidIndex(gen, false);
    }

    private static long extremeSnapshottedPidIndex(final CorpusGenerator gen, final boolean last) {
        long best = -1;
        String bestPid = null;
        for (long i = 0; i < gen.pidCount(); i++) {
            if (gen.snapshotCount(i) > 0) {
                final String pid = gen.pid(i);
                if (bestPid == null || (last ? pid.compareTo(bestPid) > 0 : pid.compareTo(bestPid) < 0)) {
                    best = i;
                    bestPid = pid;
                }
            }
        }
        if (best < 0) {
            throw new IllegalStateException("corpus of " + gen.pidCount() + " pids has no snapshotted pid");
        }
        return best;
    }

    private static String batchLine(final String pid, final Long evMin, final Long snMin, final long snapshotSn,
            final CleanupPlanner.Range ev, final CleanupPlanner.Range sn) {
        return "pid " + pid + ": min event sn " + evMin + ", min snapshot sn " + snMin + ", snapshot sn " + snapshotSn
                + "; first event batch [" + ev.fromSn() + ", " + ev.toSn() + "], first snapshot batch ["
                + sn.fromSn() + ", " + sn.toSn() + "]" + (ev.fromSn() > ev.toSn() ? " (no events left to delete)" : "")
                + "\n\n";
    }
}
