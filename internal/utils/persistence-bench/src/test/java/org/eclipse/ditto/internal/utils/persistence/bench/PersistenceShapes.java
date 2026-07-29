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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.bson.BsonNull;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Verbatim transcriptions of the operations Ditto's persistence plugins issue.
 * PG SQL: PostgresPersistenceOperations / PostgresSchema (persistence-r2dbc). The R2DBC originals
 * use $n placeholders (a repeated $n binds once); JDBC needs one ? per occurrence — bind counts
 * are documented per constant.
 * Mongo: MongoReadJournal (Ditto) + pekko-persistence-mongodb 1.5.0 driver sources.
 */
public final class PersistenceShapes {

    private PersistenceShapes() {
    }

    // --- cleanup defaults (DefaultCleanupConfig / CleanupConfig.ConfigValue) --------------------
    public static final int READS_PER_QUERY = 100;      // pid-stream page size
    public static final int WRITES_PER_CREDIT = 100;    // delete batch size
    public static final int CREDITS_PER_BATCH = 3;
    public static final Duration CREDIT_INTERVAL = Duration.ofSeconds(3);

    /** PostgresSnapshotStoreOps.toInstantUpper clamp: 9999-12-31T23:59:59Z in epoch millis. */
    public static final long PG_MAX_WRITTEN_AT_MILLIS = 253_402_300_799_000L;

    // --- collection names (things entity; Ditto things.conf overrides) --------------------------
    public static final String JOURNAL = "things_journal";
    public static final String SNAPS = "things_snaps";
    public static final String REALTIME = "things_realtime";
    public static final String METADATA = "things_metadata";

    // --- PG SQL ---------------------------------------------------------------------------------

    /** Append: event insert (binds pid, sn, manifest, tags[], eventJson). One txn with SEQ upsert. */
    public static final String PG_INSERT_EVENT =
            "INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, clock_timestamp())";

    /** Append: high-water-mark upsert (binds pid, maxSn). */
    public static final String PG_UPSERT_SEQ =
            "INSERT INTO things_journal_seq (pid, highest_sn) VALUES (?, ?) "
                    + "ON CONFLICT (pid) DO UPDATE SET highest_sn = "
                    + "GREATEST(things_journal_seq.highest_sn, excluded.highest_sn)";

    /** Recovery replay, no LIMIT (binds pid, fromSn, toSn). */
    public static final String PG_REPLAY =
            "SELECT pid, sn, seq, manifest, tags, event::text AS event FROM things_journal "
                    + "WHERE pid = ? AND sn >= ? AND sn <= ? ORDER BY sn ASC";

    /** Highest sequence number (binds pid TWICE — the R2DBC original reuses $1). */
    public static final String PG_HIGHEST_SN =
            "SELECT GREATEST(COALESCE((SELECT MAX(sn) FROM things_journal WHERE pid = ?), 0), "
                    + "COALESCE((SELECT highest_sn FROM things_journal_seq WHERE pid = ?), 0)) AS hwm";

    /** Latest-snapshot load (binds pid, maxSn, minSn, maxWrittenAt, minWrittenAt as OffsetDateTime). */
    public static final String PG_LOAD_SNAPSHOT =
            "SELECT pid, sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps "
                    + "WHERE pid = ? AND sn <= ? AND sn >= ? AND written_at <= ? AND written_at >= ? "
                    + "ORDER BY sn DESC, written_at DESC LIMIT 1";

    /** Snapshot save upsert (binds pid, sn, snapshotJson, lifecycle|null, writtenAt). */
    public static final String PG_SAVE_SNAPSHOT =
            "INSERT INTO things_snaps (pid, sn, snapshot, lifecycle, written_at) "
                    + "VALUES (?, ?, ?::jsonb, ?, ?) "
                    + "ON CONFLICT (pid, sn, written_at) DO UPDATE SET "
                    + "snapshot = excluded.snapshot, lifecycle = excluded.lifecycle";

    /**
     * Cleanup pid-stream page — production shape of {@code getNewestSnapshotsAbove}: two-phase loose
     * index scan. JDBC {@code ?} placeholders cannot be reused, so the R2DBC original's
     * $1..$5-with-reuse becomes EIGHT positional binds: lowerBound, regexEmptyCheck, regex, ageFlag,
     * interval, limit, ageFlag again, interval again.
     */
    public static final String PG_NEWEST_SNAPSHOTS_ABOVE =
            "SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM ("
                    + "SELECT pid FROM things_snaps WHERE pid > ? AND (? = '' OR pid ~ ?) "
                    + "AND (? OR written_at < now() - ?::interval) "
                    + "GROUP BY pid ORDER BY pid LIMIT ?"
                    + ") p CROSS JOIN LATERAL ("
                    + "SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps "
                    + "WHERE pid = p.pid AND (? OR written_at < now() - ?::interval) "
                    + "ORDER BY sn DESC, written_at DESC LIMIT 1"
                    + ") s ORDER BY p.pid";

    public static final String PG_MIN_EVENT_SN = "SELECT MIN(sn) AS sn FROM things_journal WHERE pid = ?";
    public static final String PG_MIN_SNAP_SN = "SELECT MIN(sn) AS sn FROM things_snaps WHERE pid = ?";

    /** Cleanup range deletes (binds pid, minSn, maxSn). NOTE: does NOT touch journal_seq.deleted_to. */
    public static final String PG_DELETE_EVENTS =
            "DELETE FROM things_journal WHERE pid = ? AND sn >= ? AND sn <= ?";
    public static final String PG_DELETE_SNAPS =
            "DELETE FROM things_snaps WHERE pid = ? AND sn >= ? AND sn <= ?";

    // --- Mongo shapes -----------------------------------------------------------------------------

    /** Journal atom, one event per atom (from == to == sn), exactly as ScalaDriverSerializers writes it. */
    public static Document journalAtom(final String pid, final long sn, final String manifest,
            final Document payload) {
        return new Document()
                .append("pid", pid)
                .append("from", sn)
                .append("to", sn)
                .append("events", List.of(new Document()
                        .append("v", 1)
                        .append("pid", pid)
                        .append("sn", sn)
                        .append("_t", "bson")
                        .append("p", payload)
                        .append("manifest", manifest)))
                .append("v", 1);
    }

    /** Snapshot doc: {pid, sn, ts, s2} (ScalaDriverPersistenceSnapshotter.serializeSnapshot). */
    public static Document snapshotDoc(final String pid, final long sn, final long tsMillis,
            final Document s2) {
        return new Document()
                .append("pid", pid)
                .append("sn", sn)
                .append("ts", tsMillis)
                .append("s2", s2);
    }

    /** Replay filter (ScalaDriverPersistenceJournaller.journalRangeQuery); sort {to:1}, projection {events:1}. */
    public static Document replayFilter(final String pid, final long fromSn, final long toSn) {
        return new Document("pid", pid)
                .append("from", new Document("$gte", fromSn))
                .append("to", new Document("$lte", toSn));
    }

    /** Snapshot-load filter (findYoungestSnapshotByMaxSequence); sort {sn:-1, ts:-1}, limit 1. */
    public static Document snapshotLoadFilter(final String pid, final long maxSn, final long maxTsMillis) {
        return new Document("pid", pid)
                .append("sn", new Document("$lte", maxSn))
                .append("ts", new Document("$lte", maxTsMillis));
    }

    /** maxSequenceNr sort: {pid:1, to:-1} (DocumentDB workaround kept by the plugin). */
    public static Document highestSnSort() {
        return new Document("pid", 1).append("to", -1);
    }

    /** MongoReadJournal.deleteEvents filter: pid + to-range. */
    public static Document deleteEventsFilter(final String pid, final long minSn, final long maxSn) {
        return new Document("pid", pid)
                .append("to", new Document("$gte", minSn).append("$lte", maxSn));
    }

    /** MongoReadJournal.deleteSnapshots filter: pid + sn-range. */
    public static Document deleteSnapshotsFilter(final String pid, final long minSn, final long maxSn) {
        return new Document("pid", pid)
                .append("sn", new Document("$gte", minSn).append("$lte", maxSn));
    }

    /**
     * MongoReadJournal.listNewestActiveSnapshotsByBatch pipeline for the cleanup call
     * (includeDeleted=true, minAge=0, no pid filter): match, sort {pid,sn:-1}, limit, group-by-pid
     * (first sn + first s2.__lifecycle), sort by _id, then the max-pid/items group. No $addFields
     * DELETED filter because cleanup includes deleted snapshots.
     */
    public static List<Bson> newestSnapshotsPipeline(final String lowerBoundPid, final int batchSize) {
        final List<Bson> pipeline = new ArrayList<>(6);
        final Document match = lowerBoundPid.isEmpty()
                ? new Document()
                : new Document("pid", new Document("$gt", lowerBoundPid));
        pipeline.add(new Document("$match", match));
        pipeline.add(new Document("$sort", new Document("pid", 1).append("sn", -1)));
        pipeline.add(new Document("$limit", batchSize));
        pipeline.add(new Document("$group", new Document("_id", "$pid")
                .append("sn", new Document("$first", "$sn"))
                .append("__lifecycle", new Document("$first", "$s2.__lifecycle"))));
        pipeline.add(new Document("$sort", new Document("_id", 1)));
        pipeline.add(new Document("$group", new Document("_id", new BsonNull())
                .append("m", new Document("$max", "$_id"))
                .append("i", new Document("$push", "$$ROOT"))));
        return pipeline;
    }
}
