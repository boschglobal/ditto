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
package org.eclipse.ditto.internal.utils.persistence.postgres.readjournal;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.eclipse.ditto.internal.utils.persistence.api.DeleteOutcome;
import org.eclipse.ditto.internal.utils.persistence.api.DittoReadJournal;
import org.eclipse.ditto.internal.utils.persistence.api.JournalEntry;
import org.eclipse.ditto.internal.utils.persistence.api.SnapshotEntry;
import org.eclipse.ditto.internal.utils.persistence.api.SnapshotFilter;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.JournalRow;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.SnapshotRow;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;

import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.persistence.query.EventEnvelope;
import org.apache.pekko.persistence.query.Offset;
import org.apache.pekko.persistence.query.Sequence;
import org.apache.pekko.stream.Attributes;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;

/**
 * PostgreSQL {@link DittoReadJournal} built on the shared {@link PostgresPersistenceOperations}.
 * <p>
 * Every one of the interface's abstract methods has an explicit disposition — real SQL, an intentional no-op, or a
 * documented stub — and a contract test asserts none throws {@code UnsupportedOperationException} at runtime. The
 * 4 {@code ensure*Index} methods are documented no-ops because the DDL (including the GIN tag index and the snapshot
 * indexes) is created upfront by {@code PostgresSchemaManager}; they complete successfully so callers that
 * await index creation are unblocked.
 * </p>
 * <p>
 * Offset decision: {@code eventsByTag}/{@code currentEventsByTag} build {@code Offset.sequence(seq)}
 * from the {@code seq BIGINT GENERATED ALWAYS AS IDENTITY} column — a monotonic global order. (Commit-visibility gaps
 * are possible under concurrency, as for any IDENTITY-based offset; there is no in-repo consumer of the tag offset
 * today, so this is the documented forward-compatible choice rather than {@code Offset.noOffset()}.)
 * </p>
 */
public final class PostgresReadJournal implements DittoReadJournal {

    private final PostgresPersistenceOperations operations;

    private PostgresReadJournal(final PostgresPersistenceOperations operations) {
        this.operations = checkNotNull(operations, "operations");
    }

    /**
     * @param operations the shared persistence operations bound to this read journal's entity tables.
     * @return the read journal.
     */
    public static PostgresReadJournal of(final PostgresPersistenceOperations operations) {
        return new PostgresReadJournal(operations);
    }

    // -------------------------------------------------------------------------------------------------
    // Index management — documented NO-OPS: DDL is created upfront by PostgresSchemaManager.
    // -------------------------------------------------------------------------------------------------

    @Override
    public CompletionStage<Done> ensureTagPidIndex() {
        return alreadyEnsured();
    }

    @Override
    public CompletionStage<Done> ensureSnapshotCollectionPidIdIndex() {
        return alreadyEnsured();
    }

    @Override
    public CompletionStage<Done> ensureSnapshotCollectionPidSnIndex() {
        return alreadyEnsured();
    }

    @Override
    public CompletionStage<Done> ensureSnapshotCollectionPidSnIdIndex() {
        return alreadyEnsured();
    }

    private static CompletionStage<Done> alreadyEnsured() {
        // No-op: indexes are part of the upfront DDL bootstrap. Complete so index-awaiting callers proceed.
        return CompletableFuture.completedFuture(Done.getInstance());
    }

    // -------------------------------------------------------------------------------------------------
    // PID retrieval
    // -------------------------------------------------------------------------------------------------

    /**
     * Streams the FULL result set by repeating a bounded page query until an empty page arrives —
     * the exact contract of {@code MongoReadJournal#unfoldBatchedSource}: {@code batchSize} is the page size,
     * never a cap on the total. The seed of the next page is derived from the LAST element of the raw page
     * (before any caller-side filtering), so filtered-out trailing rows cannot stall the cursor.
     */
    private static <T> Source<List<T>, NotUsed> unfoldBatchedSource(final String lowerBoundPid,
            final Materializer mat,
            final Function<T, String> seedCreator,
            final Function<String, Source<T, NotUsed>> pageCreator) {
        return Source.unfoldAsync("",
                        startPid -> {
                            final String actualStart =
                                    lowerBoundPid.compareTo(startPid) >= 0 ? lowerBoundPid : startPid;
                            return pageCreator.apply(actualStart)
                                    .runWith(Sink.<T>seq(), mat)
                                    .thenApply(page -> page.isEmpty()
                                            ? Optional.<Pair<String, List<T>>>empty()
                                            : Optional.of(Pair.create(
                                                    seedCreator.apply(page.get(page.size() - 1)),
                                                    (List<T>) page)));
                        })
                .withAttributes(Attributes.inputBuffer(1, 1));
    }

    @Override
    public Source<String, NotUsed> getJournalPids(final int batchSize, final Duration maxIdleTime,
            final Materializer mat) {
        return getJournalPidsAbove("", batchSize, mat);
    }

    @Override
    public Source<JournalEntry, NotUsed> getLatestJournalEntries(final int batchSize, final Duration maxIdleTime,
            final Materializer mat) {
        // The newest event per pid as a backend-neutral JournalEntry (pid + manifest + payload JSON), via a single
        // loose-index-scan streaming query (distinct-pid phase + per-pid top-1 LATERAL) — NOT an N+1 per-pid replay (M-2).
        return operations.getLatestJournalEntries()
                .map(row -> JournalEntry.of(row.pid(), row.manifest(), parse(row.eventJson())));
    }

    @Override
    public Source<String, NotUsed> getJournalPidsWithTag(final String tag, final int batchSize,
            final Duration maxIdleTime, final Materializer mat, final boolean considerOnlyLatest) {
        return operations.getPidsWithTag(tag, considerOnlyLatest);
    }

    @Override
    public Source<String, NotUsed> getJournalPidsWithTagOrderedByPriorityTag(final String tag,
            final Duration maxIdleTime) {
        return operations.getPidsWithTagOrderedByPriority(tag);
    }

    @Override
    public Source<String, NotUsed> getJournalPidsAbove(final String lowerBoundPid, final int batchSize,
            final Materializer mat) {
        // Cursor-paginated to exhaustion: batchSize is the page size, never a cap (Mongo parity).
        return unfoldBatchedSource(lowerBoundPid, mat, Function.identity(),
                start -> operations.getPidsAbove(start, batchSize))
                .mapConcat(page -> page);
    }

    @Override
    public Source<String, NotUsed> getMostRecentJournalTagsForPid(final String pid) {
        return operations.getMostRecentTagsForPid(pid);
    }

    @Override
    public Source<String, NotUsed> getJournalPidsAboveWithTag(final String lowerBoundPid, final String tag,
            final int batchSize, final Materializer mat) {
        return unfoldBatchedSource(lowerBoundPid, mat, Function.identity(),
                start -> operations.getPidsAboveWithTag(start, tag, batchSize))
                .mapConcat(page -> page);
    }

    // -------------------------------------------------------------------------------------------------
    // Snapshot retrieval
    // -------------------------------------------------------------------------------------------------

    @Override
    public Source<Long, NotUsed> getLastSnapshotSequenceNumberBeforeTimestamp(final String pid,
            final Instant timestamp) {
        return operations.getLastSnapshotSequenceNumberBeforeTimestamp(pid, timestamp);
    }

    @Override
    public Source<SnapshotEntry, NotUsed> getNewestSnapshotsAbove(final String lowerBoundPid, final int batchSize,
            final Materializer mat, final String... snapshotFields) {
        return newestSnapshots(lowerBoundPid, "", batchSize, false, Duration.ZERO, mat);
    }

    @Override
    public Source<SnapshotEntry, NotUsed> getNewestSnapshotsAbove(final String lowerBoundPid, final int batchSize,
            final boolean includeDeleted, final Duration minAgeFromNow, final Materializer mat,
            final String... snapshotFields) {
        return newestSnapshots(lowerBoundPid, "", batchSize, includeDeleted, minAgeFromNow, mat);
    }

    @Override
    public Source<SnapshotEntry, NotUsed> getNewestSnapshotsAbove(final SnapshotFilter snapshotFilter,
            final int batchSize, final Materializer mat, final String... snapshotFields) {
        return newestSnapshots(snapshotFilter.lowerBoundPid(), snapshotFilter.pidFilter(), batchSize, false,
                snapshotFilter.minAgeFromNow(), mat);
    }

    /**
     * Newest-per-pid snapshot streaming, paginated to exhaustion (batchSize is the page size, never a cap —
     * Mongo parity). DELETED filtering happens HERE, post-newest-per-pid-selection (Mongo post-$group parity): a pid whose
     * newest snapshot is DELETED is dropped entirely instead of resurrecting an older live snapshot. The
     * pagination seed is taken from the raw page before filtering, so a page whose trailing pids are all
     * DELETED still advances the cursor.
     * <p>
     * Known limitation: {@code snapshotFields} projection is still not pushed into SQL (efficiency
     * follow-up, out of scope); the pid regex uses POSIX ERE ({@code ~}), which covers the
     * {@code ^…(a|b)…} patterns {@code SnapshotStreamingActor.getSnapshotFilterFromCommand} generates.
     */
    private Source<SnapshotEntry, NotUsed> newestSnapshots(final String lowerBoundPid, final String pidFilterRegex,
            final int batchSize, final boolean includeDeleted, final Duration minAgeFromNow,
            final Materializer mat) {
        return unfoldBatchedSource(lowerBoundPid, mat, SnapshotRow::pid,
                start -> operations.getNewestSnapshotsAbove(start, batchSize, minAgeFromNow, pidFilterRegex))
                .mapConcat(page -> page.stream()
                        .filter(row -> includeDeleted || !"DELETED".equals(row.lifecycle()))
                        .map(PostgresReadJournal::toSnapshotEntry)
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public Source<Optional<Long>, NotUsed> getSmallestEventSeqNo(final String pid) {
        return operations.getSmallestEventSeqNo(pid);
    }

    @Override
    public Source<Optional<Long>, NotUsed> getLatestEventSeqNo(final String pid) {
        // Consults the high-water-mark metadata, NEVER bare MAX(sn).
        return operations.getLatestEventSeqNo(pid);
    }

    @Override
    public Source<Optional<Long>, NotUsed> getSmallestSnapshotSeqNo(final String pid) {
        return operations.getSmallestSnapshotSeqNo(pid);
    }

    // -------------------------------------------------------------------------------------------------
    // Deletion (cleanup) — bounded PK range delete (distinct from SnapshotStore.deleteAsync).
    // -------------------------------------------------------------------------------------------------

    @Override
    public Source<DeleteOutcome, NotUsed> deleteEvents(final String pid, final long minSeqNr, final long maxSeqNr) {
        return operations.deleteEvents(pid, minSeqNr, maxSeqNr).map(DeleteOutcome::acknowledged);
    }

    @Override
    public Source<DeleteOutcome, NotUsed> deleteSnapshots(final String pid, final long minSeqNr, final long maxSeqNr) {
        return operations.deleteSnapshots(pid, minSeqNr, maxSeqNr).map(DeleteOutcome::acknowledged);
    }

    // -------------------------------------------------------------------------------------------------
    // Pekko ReadJournal SPI
    // -------------------------------------------------------------------------------------------------

    @Override
    public Source<EventEnvelope, NotUsed> currentEventsByPersistenceId(final String persistenceId,
            final long fromSequenceNr, final long toSequenceNr) {
        return operations.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)
                .map(PostgresReadJournal::toEventEnvelope);
    }

    @Override
    public Source<EventEnvelope, NotUsed> currentEventsByTag(final String tag, final Offset offset) {
        return operations.eventsByTag(tag, toSeqOffset(offset)).map(PostgresReadJournal::toEventEnvelope);
    }

    @Override
    public Source<String, NotUsed> currentPersistenceIds() {
        return operations.currentPersistenceIds();
    }

    @Override
    public Source<EventEnvelope, NotUsed> eventsByPersistenceId(final String persistenceId, final long fromSequenceNr,
            final long toSequenceNr) {
        // No live tailing in this backend yet; the bounded current query satisfies the in-repo (recovery) consumers.
        return currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr);
    }

    @Override
    public Source<EventEnvelope, NotUsed> eventsByTag(final String tag, final Offset offset) {
        return currentEventsByTag(tag, offset);
    }

    @Override
    public Source<String, NotUsed> persistenceIds() {
        return currentPersistenceIds();
    }

    // -------------------------------------------------------------------------------------------------
    // Event payload mapping: decode the JSONB/text payload to a JsonObject; no BsonDocument cast.
    // -------------------------------------------------------------------------------------------------

    @Override
    public JsonObject toEventJson(final EventEnvelope eventEnvelope) {
        final Object event = eventEnvelope.event();
        if (event instanceof JsonObject jsonObject) {
            return jsonObject;
        }
        if (event instanceof CharSequence charSequence) {
            return parse(charSequence.toString());
        }
        if (event instanceof byte[] bytes) {
            return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("Cannot convert event payload of type <"
                + (event == null ? "null" : event.getClass()) + "> to JsonObject; expected JSONB-derived text.");
    }

    // -------------------------------------------------------------------------------------------------
    // mapping helpers
    // -------------------------------------------------------------------------------------------------

    private static EventEnvelope toEventEnvelope(final JournalRow row) {
        // Offset = the global monotonic seq IDENTITY column.
        return EventEnvelope.apply(Offset.sequence(row.seq()), row.pid(), row.sequenceNr(), row.eventJson());
    }

    private static SnapshotEntry toSnapshotEntry(final SnapshotRow row) {
        return SnapshotEntry.of(row.pid(), row.sequenceNr(), row.lifecycle(), parse(row.snapshotJson()));
    }

    private static long toSeqOffset(final Offset offset) {
        if (offset instanceof Sequence sequence) {
            return sequence.value();
        }
        // Offset.noOffset() (or any non-sequence offset) -> start from the beginning.
        return 0L;
    }

    private static JsonObject parse(final String json) {
        return JsonFactory.newObject(json);
    }

}
