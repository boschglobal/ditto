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

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.internal.utils.persistence.postgres.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.PostgresClientExtension;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;

import org.apache.pekko.persistence.AtomicWrite;
import org.apache.pekko.persistence.PersistentRepr;
import org.apache.pekko.persistence.journal.japi.AsyncWriteJournal;

import com.typesafe.config.Config;

import scala.concurrent.Future;
import scala.jdk.javaapi.FutureConverters;

/**
 * PostgreSQL {@link AsyncWriteJournal} — a thin japi adapter over {@link PostgresJournalOps} (which holds
 * all the testable write/recovery logic, since the japi base class forbids constructing the plugin with {@code new}).
 * <p>
 * Correctness contract (implemented in {@link PostgresJournalOps}):
 * </p>
 * <ul>
 *     <li><strong>Per-{@code AtomicWrite} transaction.</strong> Ditto produces zero multi-event {@code AtomicWrite}s
 *     (no {@code persistAll} call-site exists anywhere), so the design targets the single-event case but does not
 *     crash on a multi-event write — every {@code PersistentRepr} of an {@code AtomicWrite} is inserted in one
 *     transaction with one {@code Optional<Exception>} result slot per {@code AtomicWrite}.</li>
 *     <li><strong>High-water-mark.</strong> The same transaction upserts
 *     {@code <e>_journal_seq.highest_sn = GREATEST(highest_sn, max(sn))}; {@code doAsyncReadHighestSequenceNr} reads
 *     {@code GREATEST(COALESCE(MAX(sn),0), COALESCE(highest_sn,0))} (never bare {@code MAX(sn)}), so the highest
 *     sequence number is preserved even after all events are deleted; {@code
 *     doAsyncDeleteMessagesTo} does a PK-range delete and moves {@code deleted_to} only — never {@code highest_sn}.</li>
 *     <li><strong>Error mapping.</strong> A duplicate {@code (pid, sn)} triggers a SELECT
 *     of the existing row: if payload + manifest match the write completes successfully (idempotent commit-then-blip
 *     retry); a genuine mismatch is a fatal failure. A pool-acquire-timeout yields a failed Future — a lost write that
 *     stops the persistent actor rather than applying backpressure. A bad-grammar error (schema drift) surfaces
 *     unchanged.</li>
 * </ul>
 */
@NotThreadSafe
public final class PostgresJournal extends AsyncWriteJournal {

    private static final String ENTITY_KEY = "entity";

    private final PostgresJournalOps ops;

    /**
     * Constructor invoked reflectively by Pekko's persistence-plugin loader. Pekko tries, in order, a
     * {@code (Config, String)}, then this {@code (Config)}, then a no-arg constructor when materialising a
     * journal plugin; the single-{@link Config} form receives this plugin's own config block, which carries the
     * {@code entity} table-prefix key.
     * <p>
     * The plugin resolves its entity prefix from that block and binds the shared {@link PostgresPersistenceOperations}
     * to the entity's tables, over the single per-actor-system {@link DittoPostgresClient} resolved from
     * {@link PostgresClientExtension}. The journal, snapshot-store and read-journal of a service therefore share ONE
     * connection pool instead of opening three (the {@code 3 × max-size} over-allocation the conf documents
     * {@code max-size} as the per-service total). The shared client's lifecycle is owned by the extension, which
     * disposes it once via {@code CoordinatedShutdown}; this plugin never builds or disposes a client.
     * </p>
     *
     * @param config this journal plugin's own config block (must contain the {@code entity} key).
     */
    public PostgresJournal(final Config config) {
        final String entity = checkNotNull(config, "config").getString(ENTITY_KEY);
        final DittoPostgresClient client = PostgresClientExtension.get(context().system()).getClient();
        this.ops = PostgresJournalOps.of(PostgresPersistenceOperations.of(client, entity));
    }

    /**
     * @param operations the shared persistence operations bound to this journal's entity tables.
     */
    public PostgresJournal(final PostgresPersistenceOperations operations) {
        this.ops = PostgresJournalOps.of(checkNotNull(operations, "operations"));
    }

    @Override
    public Future<Iterable<Optional<Exception>>> doAsyncWriteMessages(final Iterable<AtomicWrite> messages) {
        return FutureConverters.asScala(ops.writeMessages(messages));
    }

    @Override
    public Future<Void> doAsyncDeleteMessagesTo(final String persistenceId, final long toSequenceNr) {
        return FutureConverters.asScala(ops.deleteMessagesTo(persistenceId, toSequenceNr));
    }

    @Override
    public Future<Long> doAsyncReadHighestSequenceNr(final String persistenceId, final long fromSequenceNr) {
        return FutureConverters.asScala(ops.readHighestSequenceNr(persistenceId, fromSequenceNr));
    }

    @Override
    public Future<Void> doAsyncReplayMessages(final String persistenceId, final long fromSequenceNr,
            final long toSequenceNr, final long max, final Consumer<PersistentRepr> replayCallback) {
        return FutureConverters.asScala(ops.replayMessages(persistenceId, fromSequenceNr, toSequenceNr, max,
                replayCallback));
    }

    // No postStop() pool disposal: the connection pool is the single shared client owned by PostgresClientExtension,
    // disposed once via CoordinatedShutdown — not per plugin actor (whose postStop Pekko does not guarantee runs before
    // dispatcher teardown).

}
