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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.internal.utils.persistence.postgres.readjournal.PostgresReadJournal;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * An ID-order walk broken while it runs: each trigger fires on its own thread once the walk has delivered a number of
 * pids; afterwards the read journal's WARNs show which window each break failed, and the delivered pids show where the
 * walk resumed after it.
 */
final class PingRecovery {

    static final String TERMINATE = "pg_terminate_backend";
    static final String RESTART = "container restart";

    private static final Pattern PAGE_WARNING = Pattern.compile(" after <(.*?)> failed on attempt <(\\d+)>");
    private static final String FIRST_WINDOW_CURSOR = "the start";   // PostgresReadJournal's name for the "" cursor

    record Trigger(String kind, int afterDelivered, Callable<String> breaker) {}

    record Break(String kind, int deliveredBefore, Instant at, long nanos, String detail, String failure) {}

    /** A read-journal WARN of a failed window attempt; {@code cursor} "": the first window. */
    record Warning(Instant at, String cursor, int attempt, String message) {}

    /**
     * Where the walk resumed after a break, split at the cursor of the window that failed: the first read-journal WARN
     * at or after the trigger and before the next trigger. The read journal fetches a page ahead, so at the trigger
     * the walk may still deliver a page it holds; the window that fails is a later one. {@code warning} empty: no WARN
     * followed the break. {@code failedAttempts}: the WARNs with that cursor until the next trigger. The durations run
     * to the delivery of the first pid after the cursor (empty when none was delivered).
     */
    record ResumePoint(String kind, Optional<Warning> warning, int failedAttempts, String lastBefore,
            String firstAfter, int lastBeforeIndex, int firstAfterIndex, Optional<Duration> sinceTrigger,
            Optional<Duration> sinceWarning, boolean contiguous) {}

    record Result(List<String> delivered, List<Long> deliveredNanos, long walkNanos, Optional<Throwable> walkFailure,
            List<Break> breaks, List<ResumePoint> resumes) {}

    private PingRecovery() {}

    static Result run(final Source<String, NotUsed> walk, final Materializer mat, final List<String> expected,
            final List<Trigger> triggers, final ListAppender<ILoggingEvent> readJournalWarnings,
            final Duration timeout) throws Exception {
        final List<String> pids = new ArrayList<>();
        final List<Long> nanos = new ArrayList<>();
        final List<Break> breaks = new CopyOnWriteArrayList<>();
        final long start = System.nanoTime();
        final CompletableFuture<Done> done = walk.runForeach(pid -> {
            synchronized (pids) {
                pids.add(pid);
                nanos.add(System.nanoTime());
            }
        }, mat).toCompletableFuture();
        final ExecutorService breaker = Executors.newSingleThreadExecutor();
        final Future<?> breaking = breaker.submit(() -> {
            for (final Trigger trigger : triggers) {
                while (delivered(pids) < trigger.afterDelivered() && !done.isDone()) {
                    TimeUnit.MILLISECONDS.sleep(2);
                }
                final int before = delivered(pids);
                final Instant at = Instant.now();
                final long breakNanos = System.nanoTime();
                if (done.isDone()) {
                    breaks.add(new Break(trigger.kind(), before, at, breakNanos, "", "the walk ended first"));
                    continue;
                }
                try {
                    breaks.add(new Break(trigger.kind(), before, at, breakNanos, trigger.breaker().call(), ""));
                } catch (final Exception e) {
                    breaks.add(new Break(trigger.kind(), before, at, breakNanos, "", e.toString()));
                }
            }
            return null;
        });
        Optional<Throwable> failure = Optional.empty();
        try {
            done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            failure = Optional.of(e.getCause());
        }
        final long walkNanos = System.nanoTime() - start;
        breaking.get(5, TimeUnit.MINUTES);
        breaker.shutdownNow();
        final List<String> deliveredPids;
        final List<Long> deliveredNanos;
        synchronized (pids) {
            deliveredPids = List.copyOf(pids);
            deliveredNanos = List.copyOf(nanos);
        }
        final List<Warning> warnings;
        synchronized (readJournalWarnings) {   // AppenderBase appends under the appender's lock
            warnings = readJournalWarnings.list.stream()
                    .flatMap(e -> pageWarning(Instant.ofEpochMilli(e.getTimeStamp()), e.getFormattedMessage()).stream())
                    .toList();
        }
        final List<Break> breakList = List.copyOf(breaks);
        return new Result(deliveredPids, deliveredNanos, walkNanos, failure, breakList,
                resumePoints(breakList, warnings, expected, deliveredPids, deliveredNanos));
    }

    private static int delivered(final List<String> pids) {
        synchronized (pids) {
            return pids.size();
        }
    }

    /** The failed window of a read-journal WARN ("Page query of read-journal stream … after <cursor> failed …"). */
    static Optional<Warning> pageWarning(final Instant at, final String message) {
        final Matcher m = PAGE_WARNING.matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        final String cursor = FIRST_WINDOW_CURSOR.equals(m.group(1)) ? "" : m.group(1);
        return Optional.of(new Warning(at, cursor, Integer.parseInt(m.group(2)), message));
    }

    /** The index of the break a WARN at {@code at} follows, -1 before the first (logback keeps milliseconds). */
    static int breakOf(final List<Break> breaks, final Instant at) {
        int index = -1;
        for (int i = 0; i < breaks.size(); i++) {
            if (!breaks.get(i).at().truncatedTo(ChronoUnit.MILLIS).isAfter(at)) {
                index = i;
            }
        }
        return index;
    }

    static List<ResumePoint> resumePoints(final List<Break> breaks, final List<Warning> warnings,
            final List<String> expectedSorted, final List<String> delivered, final List<Long> deliveredNanos) {
        final List<ResumePoint> resumes = new ArrayList<>();
        for (int i = 0; i < breaks.size(); i++) {
            final int breakIndex = i;
            final List<Warning> ofBreak = warnings.stream().filter(w -> breakOf(breaks, w.at()) == breakIndex)
                    .toList();
            resumes.add(resumePoint(breaks.get(i), ofBreak, expectedSorted, delivered, deliveredNanos));
        }
        return resumes;
    }

    private static ResumePoint resumePoint(final Break b, final List<Warning> ofBreak,
            final List<String> expectedSorted, final List<String> delivered, final List<Long> deliveredNanos) {
        if (ofBreak.isEmpty()) {
            return new ResumePoint(b.kind(), Optional.empty(), 0, "", "", -1, -1, Optional.empty(), Optional.empty(),
                    false);
        }
        final Warning first = ofBreak.get(0);
        final int failedAttempts = (int) ofBreak.stream().filter(w -> w.cursor().equals(first.cursor())).count();
        int split = 0;
        while (split < delivered.size() && delivered.get(split).compareTo(first.cursor()) <= 0) {
            split++;
        }
        final String before = split == 0 ? "" : delivered.get(split - 1);
        final String after = split < delivered.size() ? delivered.get(split) : "";
        final int beforeIndex = before.isEmpty() ? -1 : Collections.binarySearch(expectedSorted, before);
        final int afterIndex = after.isEmpty() ? -1 : Collections.binarySearch(expectedSorted, after);
        // nothing delivered after the cursor: the failed window was the final, empty one
        final boolean contiguous = (before.isEmpty() || beforeIndex >= 0)
                && (after.isEmpty() ? beforeIndex == expectedSorted.size() - 1 : afterIndex == beforeIndex + 1);
        final Optional<Duration> sinceTrigger = after.isEmpty() ? Optional.empty()
                : Optional.of(Duration.ofNanos(deliveredNanos.get(split) - b.nanos()));
        final Optional<Duration> sinceWarning =
                sinceTrigger.map(d -> d.minus(Duration.between(b.at(), first.at())));
        return new ResumePoint(b.kind(), Optional.of(first), failedAttempts, before, after, beforeIndex, afterIndex,
                sinceTrigger, sinceWarning, contiguous);
    }

    /**
     * Why the recovery stage fails: the walk failed or missed its expected pids, a break did not happen or had no
     * effect (no read-journal WARN followed it), the walk did not resume right after a failed window, or the server
     * was not restarted by the restart break ({@code serverRestarted}: the postmaster start time changed).
     */
    static List<String> offenders(final Result result, final PingWalks.Check check, final boolean serverRestarted) {
        final List<String> offenders = new ArrayList<>();
        result.walkFailure().ifPresent(f -> offenders.add("the walk failed: " + StatementTimer.describe(f)));
        check.problems().forEach(p -> offenders.add("walk: " + p));
        result.breaks().stream().filter(b -> !b.failure().isEmpty())
                .forEach(b -> offenders.add(b.kind() + " did not happen: " + b.failure()));
        for (final ResumePoint r : result.resumes()) {
            if (r.warning().isEmpty()) {
                offenders.add(r.kind() + ": no read-journal WARN between this break and the next (no window failed)");
            } else if (!r.contiguous()) {
                offenders.add(r.kind() + ": not resumed right after the failed window: " + r);
            }
        }
        if (!serverRestarted && result.breaks().stream().anyMatch(b -> RESTART.equals(b.kind()))) {
            offenders.add(RESTART + ": the postmaster start time did not change (the server did not restart)");
        }
        return offenders;
    }

    /** Terminates the walk's backend while it runs a window statement (found by application_name). */
    static Callable<String> terminateActiveWindow(final Callable<Connection> observers, final String applicationName,
            final Duration bound) {
        return () -> {
            try (Connection observer = observers.call();
                    PreparedStatement find = observer.prepareStatement("SELECT pid, wait_event_type, left(query, 60) "
                            + "FROM pg_stat_activity WHERE application_name = ? AND state = 'active' "
                            + "AND query LIKE 'SELECT w.pid AS pid%' AND pid <> pg_backend_pid()");
                    PreparedStatement terminate = observer.prepareStatement("SELECT pg_terminate_backend(?)")) {
                find.setString(1, applicationName);
                final long deadline = System.nanoTime() + bound.toNanos();
                while (System.nanoTime() < deadline) {
                    try (ResultSet rs = find.executeQuery()) {
                        if (rs.next()) {
                            final int backend = rs.getInt(1);
                            final String what = "backend " + backend + " (wait " + rs.getString(2) + ", `"
                                    + rs.getString(3) + "…`)";
                            terminate.setInt(1, backend);
                            try (ResultSet t = terminate.executeQuery()) {
                                t.next();
                                if (!t.getBoolean(1)) {
                                    throw new IllegalStateException(what + ": pg_terminate_backend returned false");
                                }
                                return what + " terminated: true";
                            }
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(5);
                }
                throw new IllegalStateException("no active window statement of " + applicationName + " in " + bound);
            }
        };
    }

    /** Restarts the bench PostgreSQL container (its data volume and port stay). */
    static Callable<String> restartContainer(final String container) {
        return () -> {
            final long start = System.nanoTime();
            final Process process = new ProcessBuilder("docker", "restart", "-t", "60", container)
                    .redirectErrorStream(true).start();
            final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(3, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IllegalStateException("docker restart " + container + " failed: " + out);
            }
            return String.format("`docker restart -t 60 %s` returned after %.1f s (%s)", container,
                    (System.nanoTime() - start) / 1e9, out);
        };
    }

    /** Captures the read journal's WARNs (PageRetry: one per failed window attempt). */
    static ListAppender<ILoggingEvent> captureReadJournalWarnings() {
        final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresReadJournal.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    static void release(final ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresReadJournal.class)).detachAppender(appender);
        appender.stop();
    }
}
