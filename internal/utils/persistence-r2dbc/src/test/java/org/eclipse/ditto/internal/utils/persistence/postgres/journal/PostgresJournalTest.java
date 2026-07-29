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
package org.eclipse.ditto.internal.utils.persistence.postgres.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.ditto.internal.utils.persistence.postgres.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.RecordingConnectionFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.StubClientSupport;
import org.junit.Test;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcTimeoutException;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.persistence.AtomicWrite;
import org.apache.pekko.persistence.PersistentRepr;
import org.apache.pekko.persistence.journal.Tagged;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Unit tests for the {@link PostgresJournal} write path, exercised through {@link PostgresJournalOps} (the plain
 * delegate the actor forwards to, so no {@code ActorSystem} is needed): the high-water-mark contract, the idempotency
 * handling of a duplicate {@code (pid, sn)} insert, and the lost-write semantics on a failed write — all offline
 * against a {@link RecordingConnectionFactory}.
 */
public final class PostgresJournalTest {

    private static final String PID = "thing:ns:id";

    private PostgresJournalOps journalFor(final RecordingConnectionFactory factory) {
        final DittoPostgresClient client = StubClientSupport.clientFor(factory);
        return PostgresJournalOps.of(PostgresPersistenceOperations.of(client, "things"));
    }

    private static AtomicWrite singleWrite(final long sn, final String eventJson, final String... tags) {
        final Object payload = tags.length == 0
                ? (Object) org.eclipse.ditto.json.JsonFactory.newObject(eventJson)
                : new Tagged(org.eclipse.ditto.json.JsonFactory.newObject(eventJson),
                        CollectionConverters.asScala(java.util.Arrays.asList(tags)).toSet());
        final PersistentRepr repr = PersistentRepr.apply(payload, sn, PID,
                "org.eclipse.ditto.things.model.signals.events.ThingCreated", false, ActorRef.noSender(), "writer-1");
        return AtomicWrite.apply(repr);
    }

    private static <T> T await(final java.util.concurrent.CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    public void writeUpsertsHighWaterMarkInSameTransaction() throws Exception {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        final PostgresJournalOps journal = journalFor(factory);

        final Iterable<Optional<Exception>> results = await(journal.writeMessages(
                List.of(singleWrite(5L, "{\"x\":1}"))));

        assertThat(results).containsExactly(Optional.empty());
        assertThat(factory.executedContaining("INSERT INTO things_journal ")).isTrue();
        // The high-water-mark row is upserted in the same transaction, raised via GREATEST.
        assertThat(factory.executedSql().stream()
                .anyMatch(sql -> sql.contains("INSERT INTO things_journal_seq")
                        && sql.contains("GREATEST"))).isTrue();
    }

    @Test
    public void readHighestSequenceNrUsesGreatestNotBareMax() throws Exception {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .onSql("GREATEST(", List.of(Map.of("hwm", 42L)));
        final PostgresJournalOps journal = journalFor(factory);

        final long hwm = await(journal.readHighestSequenceNr(PID, 0L));

        assertThat(hwm).isEqualTo(42L);
        final String sql = factory.executedSql().stream()
                .filter(s -> s.contains("GREATEST(")).findFirst().orElseThrow();
        // never a bare MAX(sn); always GREATEST(COALESCE(MAX(sn),0), COALESCE(highest_sn,0)).
        assertThat(sql).contains("highest_sn");
        assertThat(sql).contains("things_journal_seq");
    }

    @Test
    public void readHighestSequenceNrFloorsAtFromSequenceNr() throws Exception {
        // L-6: readHighestSequenceNr must never return a value below the caller's fromSequenceNr floor. When the
        // persisted high-water-mark (5) is below the requested floor (10), the floor wins.
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .onSql("GREATEST(", List.of(Map.of("hwm", 5L)));
        final PostgresJournalOps journal = journalFor(factory);

        final long hwm = await(journal.readHighestSequenceNr(PID, 10L));

        assertThat(hwm).isGreaterThanOrEqualTo(10L);
        assertThat(hwm).isEqualTo(10L);
    }

    @Test
    public void readHighestSequenceNrReturnsHwmWhenAboveFromSequenceNr() throws Exception {
        // Existing behaviour preserved: when the persisted high-water-mark (42) exceeds the floor (10), HWM wins.
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .onSql("GREATEST(", List.of(Map.of("hwm", 42L)));
        final PostgresJournalOps journal = journalFor(factory);

        final long hwm = await(journal.readHighestSequenceNr(PID, 10L));

        assertThat(hwm).isEqualTo(42L);
    }

    @Test
    public void deleteMessagesToMovesDeletedToNeverHighestSn() throws Exception {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        final PostgresJournalOps journal = journalFor(factory);

        await(journal.deleteMessagesTo(PID, 7L));

        assertThat(factory.executedContaining("DELETE FROM things_journal WHERE pid = $1 AND sn <= $2")).isTrue();
        final String upsert = factory.executedSql().stream()
                .filter(s -> s.contains("things_journal_seq")).findFirst().orElseThrow();
        // A delete updates deleted_to ONLY; it must NOT lower or set highest_sn.
        assertThat(upsert).contains("deleted_to");
        assertThat(upsert).doesNotContain("SET highest_sn");
    }

    @Test
    public void deleteMessagesToInsertBranchNeverFabricatesHighestSn() throws Exception {
        // R-1 regression guard: on the INSERT path (no prior journal_seq row) a delete must NOT seed highest_sn from
        // toSequenceNr. The VALUES clause must write a literal 0 into highest_sn so only deleted_to ever moves; otherwise
        // readHighestSequenceNr (= GREATEST(MAX(sn), highest_sn)) would return a sequence number that was never persisted.
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        final PostgresJournalOps journal = journalFor(factory);

        await(journal.deleteMessagesTo(PID, 7L));

        final String upsert = factory.executedSql().stream()
                .filter(s -> s.contains("things_journal_seq")).findFirst().orElseThrow();
        // Column order is (pid, highest_sn, deleted_to): the second VALUES position (highest_sn) must be the literal 0,
        // never the same $n placeholder bound from toSequenceNr that feeds deleted_to.
        assertThat(upsert).contains("(pid, highest_sn, deleted_to)");
        assertThat(upsert).contains("VALUES ($1, 0, $2)");
        // The defect was VALUES ($1, $2, $2), reusing the toSequenceNr bind for highest_sn.
        assertThat(upsert).doesNotContain("VALUES ($1, $2, $2)");
    }

    @Test
    public void duplicateWithMatchingPayloadIsIdempotentSuccess() throws Exception {
        final String eventJson = "{\"x\":1}";
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .failOnSql("INSERT INTO things_journal ",
                        new R2dbcDataIntegrityViolationException("duplicate key", "23505", 0))
                // the reconciliation SELECT returns the SAME manifest + payload -> idempotent.
                .onSql("SELECT pid, sn, seq, manifest, tags, event::text", List.of(Map.of(
                        "pid", PID, "sn", 5L, "seq", 1L,
                        "manifest", "org.eclipse.ditto.things.model.signals.events.ThingCreated",
                        "tags", new String[0], "event", eventJson)));
        final PostgresJournalOps journal = journalFor(factory);

        final Iterable<Optional<Exception>> results = await(journal.writeMessages(
                List.of(singleWrite(5L, eventJson))));

        // Commit-then-connection-blip retry: the stored row matches the retried write -> complete OK.
        assertThat(results).containsExactly(Optional.empty());
    }

    @Test
    public void duplicateWithDifferentPayloadIsFatalFailedFuture() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .failOnSql("INSERT INTO things_journal ",
                        new R2dbcDataIntegrityViolationException("duplicate key", "23505", 0))
                .onSql("SELECT pid, sn, seq, manifest, tags, event::text", List.of(Map.of(
                        "pid", PID, "sn", 5L, "seq", 1L,
                        "manifest", "org.eclipse.ditto.things.model.signals.events.ThingCreated",
                        "tags", new String[0], "event", "{\"DIFFERENT\":true}")));
        final PostgresJournalOps journal = journalFor(factory);

        // A genuine (pid, sn) collision with a DIFFERENT stored payload is split-brain corruption: it MUST be
        // fatal (failed Future -> WriteMessageFailure -> actor stops), NOT a recoverable per-message rejection
        // (a present Optional) that silently drops the write and lets the actor diverge from the journal.
        assertThatThrownBy(() -> await(journal.writeMessages(List.of(singleWrite(5L, "{\"x\":1}")))))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIFFERENT payload/manifest");
    }

    @Test
    public void notNullIntegrityViolationIsFatalFailedFuture() {
        // SQLSTATE 23502 = not_null_violation: NOT a duplicate-key collision, so reconcileDuplicate must not
        // misclassify it as a recoverable rejection. It is a constraint/schema fault -> fatal failed Future.
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .failOnSql("INSERT INTO things_journal ",
                        new R2dbcDataIntegrityViolationException(
                                "null value in column \"manifest\" violates not-null constraint", "23502", 0));
        final PostgresJournalOps journal = journalFor(factory);

        assertThatThrownBy(() -> await(journal.writeMessages(List.of(singleWrite(5L, "{\"x\":1}")))))
                .hasCauseInstanceOf(R2dbcDataIntegrityViolationException.class);
    }

    @Test
    public void checkConstraintViolationIsFatalFailedFuture() {
        // SQLSTATE 23514 = check_violation: likewise NOT a duplicate -> fatal failed Future, never a rejection.
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .failOnSql("INSERT INTO things_journal ",
                        new R2dbcDataIntegrityViolationException(
                                "new row violates check constraint \"things_journal_sn_check\"", "23514", 0));
        final PostgresJournalOps journal = journalFor(factory);

        assertThatThrownBy(() -> await(journal.writeMessages(List.of(singleWrite(5L, "{\"x\":1}")))))
                .hasCauseInstanceOf(R2dbcDataIntegrityViolationException.class);
    }

    @Test
    public void poolAcquireTimeoutIsALostWriteFailedFuture() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .failOnSql("INSERT INTO things_journal ",
                        new R2dbcTimeoutException("pool acquire timed out"));
        final PostgresJournalOps journal = journalFor(factory);

        // A failed write Future (NOT backpressure) -> the persistent actor stops.
        assertThatThrownBy(() -> await(journal.writeMessages(List.of(singleWrite(5L, "{\"x\":1}")))))
                .hasCauseInstanceOf(R2dbcTimeoutException.class);
    }

}
