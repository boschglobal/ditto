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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.PostgresPersistenceOperations;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.RecordingConnectionFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.ops.StubClientSupport;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.R2dbcTimeoutException;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.SinkQueueWithCancel;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;

/**
 * Offline tests of how the PostgreSQL read journal reads the tag pid streams: the cursor of each window, windows
 * without a qualifying pid, the retry of a failed window from the same cursor, the failure once retries are
 * exhausted, the stop of retries when the stream is cancelled, and the single statement of the priority order. A
 * bind-aware stub answers each statement from an in-memory "journal", so the tests see exactly which cursor every
 * statement was bound with.
 */
public final class PostgresReadJournalKeysetPagingTest {

    /** Both window variants: the window over all pids after the cursor. */
    private static final String WINDOW = "SELECT DISTINCT pid FROM things_journal WHERE pid > $1 ORDER BY pid LIMIT $3";
    private static final String PRIORITY = "priority-";
    private static final String TAG = "always-alive";
    private static final int BATCH_SIZE = 10;
    /** {@code maxIdleTime} with one retry (log2 of 2 seconds). */
    private static final Duration ONE_RETRY = Duration.ofSeconds(2);
    /** {@code maxIdleTime} of the shipped pings (10 retries). */
    private static final Duration PING_INTERVAL = Duration.ofMinutes(10);

    private static ActorSystem system;
    private static Materializer mat;

    @BeforeClass
    public static void beforeClass() {
        system = ActorSystem.create("PostgresReadJournalKeysetPagingTest");
        mat = SystemMaterializer.get(system).materializer();
    }

    @AfterClass
    public static void afterClass() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
        }
    }

    @Test
    public void idOrderStreamReadsWindowsAfterTheLastPidOfThePreviousWindow() {
        final List<String> journal = pids(25);
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .respondTo(WINDOW, binds -> windowRows(journal, binds, pid -> true));

        final List<String> streamed = run(readJournal(factory)
                .getJournalPidsWithTag(TAG, BATCH_SIZE, Duration.ZERO, mat, false));

        assertThat(streamed).containsExactlyElementsOf(journal);
        assertThat(cursors(factory))
                .as("first window from the start, then after the last pid of each window, until an empty window")
                .containsExactly("", journal.get(9), journal.get(19), journal.get(24));
        assertThat(factory.executions()).allSatisfy(execution -> {
            assertThat(execution.binds().get(1)).isEqualTo(TAG);
            assertThat(execution.binds().get(2)).as("batchSize is the window size").isEqualTo(BATCH_SIZE);
        });
    }

    @Test
    public void windowsWithoutAQualifyingPidStillMoveTheWalkOn() {
        // 40 pids; only the last 5 qualify: three windows deliver nothing.
        final List<String> journal = pids(40);
        for (final boolean considerOnlyLatest : List.of(false, true)) {
            final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                    .respondTo(WINDOW, binds -> windowRows(journal, binds, pid -> journal.indexOf(pid) >= 35));

            final List<String> streamed = run(readJournal(factory)
                    .getJournalPidsWithTag(TAG, BATCH_SIZE, Duration.ZERO, mat, considerOnlyLatest));

            assertThat(streamed).as("considerOnlyLatest=%s", considerOnlyLatest)
                    .containsExactlyElementsOf(journal.subList(35, 40));
            assertThat(cursors(factory)).as("considerOnlyLatest=%s", considerOnlyLatest)
                    .containsExactly("", journal.get(9), journal.get(19), journal.get(29), journal.get(39));
        }
    }

    @Test
    public void failedWindowIsRetriedFromItsCursorWithoutRepeatingOrSkippingPids() {
        final List<String> journal = pids(25);
        final AtomicBoolean failedOnce = new AtomicBoolean();
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .respondTo(WINDOW, binds -> {
                    if (journal.get(9).equals(binds.get(0)) && failedOnce.compareAndSet(false, true)) {
                        throw statementTimeout();
                    }
                    return windowRows(journal, binds, pid -> true);
                });

        final List<String> streamed = run(readJournal(factory)
                .getJournalPidsWithTag(TAG, BATCH_SIZE, ONE_RETRY, mat, false));

        assertThat(streamed).containsExactlyElementsOf(journal);
        assertThat(cursors(factory))
                .as("the failed second window is read again after the first window's last pid, not from the start")
                .containsExactly("", journal.get(9), journal.get(9), journal.get(19), journal.get(24));
    }

    @Test
    public void streamFailsWithTheWindowErrorOnceTheRetriesAreExhausted() {
        final List<String> journal = pids(25);
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .respondTo(WINDOW, binds -> {
                    if (journal.get(9).equals(binds.get(0))) {
                        throw statementTimeout();
                    }
                    return windowRows(journal, binds, pid -> true);
                });
        final List<String> delivered = new CopyOnWriteArrayList<>();

        final Throwable failure = catchThrowable(() -> readJournal(factory)
                .getJournalPidsWithTag(TAG, BATCH_SIZE, ONE_RETRY, mat, false)
                .runForeach(delivered::add, mat)
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS));

        assertThat(sqlStates(failure)).as("the window's own error: %s", failure).contains("57014");
        assertThat(delivered).as("the first window was delivered before the failure")
                .containsExactlyElementsOf(journal.subList(0, 10));
        assertThat(cursors(factory))
                .as("one attempt plus one retry of the failing window, then no further window")
                .containsExactly("", journal.get(9), journal.get(9));
    }

    @Test
    public void zeroMaxIdleTimeMeansNoRetry() {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .respondTo(WINDOW, binds -> {
                    throw statementTimeout();
                });

        final Throwable failure = catchThrowable(() -> run(readJournal(factory)
                .getJournalPidsWithTag(TAG, BATCH_SIZE, Duration.ZERO, mat, false)));

        assertThat(sqlStates(failure)).contains("57014");
        assertThat(factory.executions()).hasSize(1);
    }

    @Test
    public void cancellingTheStreamStopsTheRetriesOfAWindowInBackOff() throws Exception {
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .respondTo(WINDOW, binds -> {
                    throw statementTimeout();
                });
        final SinkQueueWithCancel<String> consumer = readJournal(factory)
                .getJournalPidsWithTag(TAG, BATCH_SIZE, PING_INTERVAL, mat, false)
                .runWith(Sink.queue(), mat);
        awaitExecutions(factory, 1);

        // the first attempt failed; the window waits 1 s before its first retry, then 2 s, ...
        consumer.cancel();
        TimeUnit.MILLISECONDS.sleep(3_500L);

        assertThat(factory.executions())
                .as("no retry after the stream was cancelled (without the stop: attempts at 0 s, ~1 s and ~3 s)")
                .hasSize(1);
    }

    @Test
    public void priorityOrderIsOneStatementReadCompletelyAndRetriedAsAWhole() {
        final List<String> byPriority = IntStream.rangeClosed(1, 25).mapToObj(i -> pid(26 - i)).toList();
        final AtomicBoolean failedOnce = new AtomicBoolean();
        final RecordingConnectionFactory factory = new RecordingConnectionFactory()
                .respondTo(PRIORITY, binds -> {
                    if (failedOnce.compareAndSet(false, true)) {
                        throw statementTimeout();
                    }
                    return byPriority.stream().map(pid -> Map.<String, Object>of("pid", pid)).toList();
                });

        final List<String> streamed = run(readJournal(factory)
                .getJournalPidsWithTagOrderedByPriorityTag(TAG, ONE_RETRY));

        assertThat(streamed).as("in statement order, each pid once, although the first attempt failed")
                .containsExactlyElementsOf(byPriority);
        assertThat(factory.executions()).hasSize(2).allSatisfy(execution -> {
            assertThat(execution.sql()).doesNotContain("LIMIT $");
            assertThat(execution.binds()).containsExactly(Map.entry(0, TAG));
        });
    }

    // ---------------------------------------------------------------------------------------------------------

    private static List<Map<String, Object>> windowRows(final List<String> journal, final Map<Integer, Object> binds,
            final Predicate<String> qualifies) {
        final String after = (String) binds.get(0);
        final int window = (Integer) binds.get(2);
        return journal.stream()
                .filter(pid -> pid.compareTo(after) > 0)
                .limit(window)
                .map(pid -> Map.<String, Object>of("pid", pid, "qualifies", qualifies.test(pid)))
                .toList();
    }

    private static PostgresReadJournal readJournal(final RecordingConnectionFactory factory) {
        final DittoPostgresClient client = StubClientSupport.clientFor(factory);
        return PostgresReadJournal.of(PostgresPersistenceOperations.of(client, "things"));
    }

    private static List<String> pids(final int count) {
        return IntStream.rangeClosed(1, count).mapToObj(PostgresReadJournalKeysetPagingTest::pid).toList();
    }

    private static String pid(final int i) {
        return String.format("thing:ns:%03d", i);
    }

    private static List<Object> cursors(final RecordingConnectionFactory factory) {
        final List<Object> cursors = new ArrayList<>();
        factory.executions().stream()
                .filter(execution -> execution.sql().contains(WINDOW))
                .forEach(execution -> cursors.add(execution.binds().get(0)));
        return cursors;
    }

    private static void awaitExecutions(final RecordingConnectionFactory factory, final int count)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (factory.executions().size() < count && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(factory.executions()).hasSizeGreaterThanOrEqualTo(count);
    }

    private static R2dbcTimeoutException statementTimeout() {
        return new R2dbcTimeoutException("canceling statement due to statement timeout", "57014");
    }

    private static List<String> sqlStates(final Throwable failure) {
        final List<String> sqlStates = new ArrayList<>();
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof R2dbcException r2dbc && r2dbc.getSqlState() != null) {
                sqlStates.add(r2dbc.getSqlState());
            }
        }
        return sqlStates;
    }

    private static <T> List<T> run(final Source<T, ?> source) {
        try {
            return source.runWith(Sink.seq(), mat).toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("stream failed", e);
        }
    }

}
