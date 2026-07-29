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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.ditto.internal.utils.persistence.postgres.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.RecordingConnectionFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.StubClientSupport;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.persistence.query.Offset;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;

/**
 * H-2 / M-2 regression coverage for the PostgreSQL read journal.
 * <p>
 * H-2: with the connection-pool-wide {@code fetch-size=0} default, the r2dbc-postgresql driver materialises the whole
 * result set into heap before the Pekko {@code Source} can exert demand. The genuinely unbounded streaming read-journal
 * queries ({@code currentPersistenceIds()}, {@code eventsByTag()}, {@code getPidsWithTag(considerOnlyLatest=false)}) must
 * therefore bind a positive per-statement {@code Statement.fetchSize(n)} so a server-side portal restores backpressure.
 * </p>
 * <p>
 * M-2: {@code getLatestJournalEntries} must NOT be an N+1 (one inner replay query per pid); a single
 * loose-index-scan query (distinct-pid phase + per-pid top-1 {@code CROSS JOIN LATERAL}) returns the newest
 * event per pid.
 * </p>
 */
public final class PostgresReadJournalBackpressureTest {

    private static ActorSystem system;
    private static Materializer mat;

    @BeforeClass
    public static void beforeClass() {
        system = ActorSystem.create("PostgresReadJournalBackpressureTest");
        mat = SystemMaterializer.get(system).materializer();
    }

    @AfterClass
    public static void afterClass() {
        if (system != null) {
            system.terminate();
        }
    }

    @Test
    public void currentPersistenceIdsBindsPositiveFetchSize() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        final PostgresReadJournal journal = readJournal(factory);

        drain(journal.currentPersistenceIds());

        assertThat(factory.fetchSizeForSqlContaining("SELECT DISTINCT pid FROM"))
                .as("currentPersistenceIds() must open a server-side portal via a positive fetchSize")
                .isPositive();
    }

    @Test
    public void eventsByTagBindsPositiveFetchSize() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        final PostgresReadJournal journal = readJournal(factory);

        drain(journal.currentEventsByTag("always-alive", Offset.sequence(0L)));

        assertThat(factory.fetchSizeForSqlContaining("seq > $2 ORDER BY seq ASC"))
                .as("eventsByTag() is unbounded and must bind a positive fetchSize")
                .isPositive();
    }

    @Test
    public void getPidsWithTagAllBindsPositiveFetchSize() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        final PostgresReadJournal journal = readJournal(factory);

        // considerOnlyLatest=false -> SELECT DISTINCT pid ... WHERE tags @> ... (no LIMIT)
        drain(journal.getJournalPidsWithTag("always-alive", 10, Duration.ofSeconds(1), mat, false));

        assertThat(factory.fetchSizeForSqlContaining("SELECT DISTINCT pid FROM"))
                .as("getPidsWithTag(considerOnlyLatest=false) is unbounded and must bind a positive fetchSize")
                .isPositive();
    }

    @Test
    public void getLatestJournalEntriesUsesSingleLooseScanQueryNotPerPidNPlus1() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory();
        // Three pids exist; an N+1 implementation would run one inner replay per pid.
        factory.onSql("CROSS JOIN LATERAL", List.of(
                journalRow("thing:ns:a", 7L, "manifest-a"),
                journalRow("thing:ns:b", 3L, "manifest-b"),
                journalRow("thing:ns:c", 9L, "manifest-c")));
        final PostgresReadJournal journal = readJournal(factory);

        drain(journal.getLatestJournalEntries(10, Duration.ofSeconds(1), mat));

        assertThat(factory.executedContaining("CROSS JOIN LATERAL"))
                .as("getLatestJournalEntries must use a single loose-index-scan (CROSS JOIN LATERAL) query")
                .isTrue();
        // N+1 would issue a per-pid bounded-replay (... WHERE pid = $1 AND sn >= ... ORDER BY sn ASC) per pid.
        assertThat(factory.executedCountContaining("sn >= $2 AND sn <= $3 ORDER BY sn ASC"))
                .as("getLatestJournalEntries must NOT issue a per-pid inner replay query (N+1)")
                .isZero();
    }

    private static PostgresReadJournal readJournal(final RecordingConnectionFactory factory) {
        final DittoPostgresClient client = StubClientSupport.clientFor(factory);
        return PostgresReadJournal.of(PostgresPersistenceOperations.of(client, "things"));
    }

    private static Map<String, Object> journalRow(final String pid, final long sn, final String manifest) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", pid);
        row.put("sn", sn);
        row.put("seq", sn);
        row.put("manifest", manifest);
        row.put("tags", new String[0]);
        row.put("event", "{\"foo\":\"bar\"}");
        return row;
    }

    private <T> void drain(final Source<T, ?> source) {
        try {
            source.runWith(Sink.ignore(), system).toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new AssertionError("stream failed", e);
        }
    }

}
