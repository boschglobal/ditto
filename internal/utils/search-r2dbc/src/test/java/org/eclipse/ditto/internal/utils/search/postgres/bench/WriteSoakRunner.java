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
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.javadsl.Flow;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.write.PostgresSearchUpdaterFlow;
import org.eclipse.ditto.thingsearch.persistence.api.model.ThingWriteModel;
import org.eclipse.ditto.thingsearch.persistence.api.write.SearchWriteResultStatus;
import org.eclipse.ditto.thingsearch.persistence.api.write.UpdaterData;
import org.eclipse.ditto.thingsearch.persistence.api.write.UpdaterResult;

/**
 * The write-soak engine: writers that drive every update through the shipped search write flow, a sampler that closes a
 * window on every absolute window boundary, and a poller that follows the running (auto)vacuums (see {@link #run}).
 */
final class WriteSoakRunner {

    /** 100-microsecond buckets up to 60 s; the last bucket collects everything slower. */
    static final int HISTOGRAM_BUCKETS = 600_001;
    static final long BUCKET_MICROS = 100L;

    private WriteSoakRunner() {
        throw new AssertionError("no instances");
    }

    /** Opens a JDBC connection to the soak database (the sampler and the vacuum poller hold one each). */
    @FunctionalInterface
    interface JdbcConnector {

        Connection open() throws SQLException;
    }

    record Settings(long thingCount, long seed, int workers, double targetRate, Duration warmup, Duration gated,
            Duration plateau, Duration window, Duration vacuumPoll) {

        Settings {
            require(workers >= 1, "workers must be at least 1");
            require(thingCount >= workers, "thingCount must be at least the number of workers");
            require(thingCount <= Integer.MAX_VALUE, "thingCount must fit an int");
            require(targetRate > 0, "targetRate must be positive");
            require(!warmup.isNegative(), "warmup must not be negative");
            require(window.compareTo(Duration.ofSeconds(1)) >= 0, "window must be at least 1 s");
            require(!vacuumPoll.isNegative() && !vacuumPoll.isZero() && vacuumPoll.compareTo(window) < 0,
                    "vacuumPoll must be positive and shorter than the window");
            require(gated.compareTo(window) >= 0, "gated must last at least one window");
            require(plateau.compareTo(window) >= 0 && plateau.compareTo(gated) <= 0,
                    "plateau must last at least one window and at most the gated period");
        }

        /**
         * @throws IllegalArgumentException if there are more writers than the pool has connections: the extra writers
         * would queue on the pool and fail after its max-acquire-time, which measures the pool, not the database.
         */
        void requireWorkersWithin(final int poolMaxSize) {
            require(workers <= poolMaxSize, "workers (" + workers + ") must not exceed the shipped pool's "
                    + "max-size (" + poolMaxSize + ")");
        }

        private static void require(final boolean condition, final String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    /**
     * @param errors the failed updates of all windows.
     * @param lateErrors the failed updates that were still in flight at the last window boundary (in no window).
     * @param writersAlive the writers still running when the soak returned (a write outlived its timeout).
     */
    record Result(List<SoakAnalysis.Window> windows, List<SoakAnalysis.VacuumCycle> vacuumCycles,
            SoakAnalysis.Snapshot start, long updates, long errors, long lateErrors, int writersAlive,
            List<String> errorSamples, List<String> pollerErrors, long[] gatedLatencyHistogram,
            int[] updatesApplied) {

        /** @return the thing's revision after the soak (1 = untouched since the load). */
        int revision(final long index) {
            return 1 + updatesApplied[Math.toIntExact(index)];
        }

        /** @return the nearest-rank percentile over the gated windows, as its bucket's upper bound. */
        long gatedLatencyPercentileMicros(final int pct) {
            long total = 0;
            for (final long count : gatedLatencyHistogram) {
                total += count;
            }
            if (total == 0) {
                return 0;
            }
            final long rank = (long) Math.ceil(pct / 100.0 * total);
            long cumulative = 0;
            for (int bucket = 0; bucket < gatedLatencyHistogram.length; bucket++) {
                cumulative += gatedLatencyHistogram[bucket];
                if (cumulative >= rank) {
                    return (bucket + 1) * BUCKET_MICROS;
                }
            }
            return gatedLatencyHistogram.length * BUCKET_MICROS;
        }
    }

    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(70);   // > statement-timeout 60s + acquire 4s
    private static final Duration RESULT_TIMEOUT = WRITE_TIMEOUT.plusSeconds(5);   // the stream's timeout fires first
    private static final int MAX_ERROR_SAMPLES = 20;

    /**
     * Runs the warm-up and the gated soak.
     * <ul>
     *     <li>{@code workers} writer threads, each with one update in flight, are paced by one shared
     *     {@link RateLimiter}. Each update is {@code Source.single(UpdaterData).via(flow)} on the shipped
     *     {@link PostgresSearchUpdaterFlow}, the per-thing framing of the service's {@code ThingUpdater}. Writer
     *     {@code w} owns the things whose index is congruent to {@code w}, so one thing's writes never overlap.</li>
     *     <li>A window closes on every absolute boundary {@code t0 + k * window}. The sampler snapshots the database,
     *     and {@code onWindow} receives the window at once, so a crash keeps the evidence written so far.</li>
     *     <li>A poller thread follows the running (auto)vacuums.</li>
     * </ul>
     */
    static Result run(final Settings settings, final SoakTwin twin, final DittoPostgresClient client,
            final ActorSystem system, final JdbcConnector jdbc, final Consumer<SoakAnalysis.Window> onWindow)
            throws SQLException, InterruptedException {
        final Flow<UpdaterData, UpdaterResult, NotUsed> writeFlow = PostgresSearchUpdaterFlow.of(client).create();
        final RateLimiter limiter = new RateLimiter(settings.targetRate());
        final int[] updatesApplied = new int[Math.toIntExact(settings.thingCount())];
        final Recorder recorder = new Recorder();
        final SoakAnalysis.VacuumCycleTracker tracker = new SoakAnalysis.VacuumCycleTracker();
        final List<String> pollerErrors = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean stop = new AtomicBoolean();
        final List<SoakAnalysis.Window> windows = new ArrayList<>();
        final long[] histogram = new long[HISTOGRAM_BUCKETS];

        try (Connection statsConnection = jdbc.open(); Connection vacuumConnection = jdbc.open()) {
            final SoakAnalysis.Snapshot start = SoakDbStats.snapshot(statsConnection);
            final long t0 = System.nanoTime();
            final Thread poller = new Thread(() -> pollVacuums(vacuumConnection, tracker, settings.vacuumPoll(), t0,
                    stop, pollerErrors), "write-soak-vacuum-poller");
            poller.setDaemon(true);
            final List<Thread> writers = new ArrayList<>();
            for (int w = 0; w < settings.workers(); w++) {
                final int worker = w;
                final Thread writer = new Thread(() -> write(worker, settings, twin, updatesApplied, writeFlow, system,
                        limiter, stop, recorder), "write-soak-writer-" + w);
                writer.setDaemon(true);
                writers.add(writer);
            }
            poller.start();
            writers.forEach(Thread::start);
            try {
                SoakAnalysis.Snapshot previous = start;
                final long windowNanos = settings.window().toNanos();
                final long warmupEnd = settings.warmup().toNanos();
                final long end = warmupEnd + settings.gated().toNanos();
                long nominal = 0;
                long actualPrevious = 0;
                int number = 0;
                while (nominal < end) {
                    final long boundary = Math.min(nominal + windowNanos, nominal < warmupEnd ? warmupEnd : end);
                    sleepUntil(t0 + boundary);
                    final long actual = System.nanoTime() - t0;
                    final Recorder.Counts counts = recorder.drain();
                    final SoakAnalysis.Snapshot snapshot = SoakDbStats.snapshot(statsConnection);
                    final boolean gated = nominal >= warmupEnd;
                    final SoakAnalysis.Window window = SoakAnalysis.window(++number, actual / 1e9,
                            (actual - actualPrevious) / 1e9, gated, counts.latenciesMicros(), counts.errors(),
                            previous, snapshot, tracker.drainWindowWaitEvents(), tracker.running());
                    if (gated) {
                        for (final long micros : counts.latenciesMicros()) {
                            histogram[(int) Math.min(micros / BUCKET_MICROS, HISTOGRAM_BUCKETS - 1)]++;
                        }
                    }
                    windows.add(window);
                    onWindow.accept(window);
                    previous = snapshot;
                    actualPrevious = actual;
                    nominal = boundary;
                }
            } finally {
                stop.set(true);
                for (final Thread writer : writers) {
                    writer.join(RESULT_TIMEOUT.toMillis() + 5_000L);
                }
                poller.join(settings.vacuumPoll().toMillis() + 10_000L);
            }
            final long lateErrors = recorder.drain().errors();   // writes in flight at the last boundary
            final int writersAlive = (int) writers.stream().filter(Thread::isAlive).count();
            final long updates = windows.stream().mapToLong(SoakAnalysis.Window::updates).sum();
            final long errors = windows.stream().mapToLong(SoakAnalysis.Window::errors).sum();
            return new Result(List.copyOf(windows), tracker.finish(), start, updates, errors, lateErrors,
                    writersAlive, recorder.errorSamples(), List.copyOf(pollerErrors), histogram, updatesApplied);
        }
    }

    private static void write(final int worker, final Settings settings, final SoakTwin twin,
            final int[] updatesApplied, final Flow<UpdaterData, UpdaterResult, NotUsed> writeFlow,
            final ActorSystem system, final RateLimiter limiter, final AtomicBoolean stop, final Recorder recorder) {
        final SplittableRandom random = new SplittableRandom(settings.seed() * 31L + worker);
        final long owned = (settings.thingCount() - worker + settings.workers() - 1) / settings.workers();
        while (!stop.get()) {
            long index = -1;
            try {
                limiter.acquire();
                if (stop.get()) {
                    return;
                }
                index = random.nextLong(owned) * settings.workers() + worker;
                final int slot = (int) index;
                final ThingWriteModel model = twin.writeModel(index, 2 + updatesApplied[slot]);   // loaded at 1
                final long started = System.nanoTime();
                // on timeout the stream cancels the write, and the flow's cancel arm releases the pooled connection
                final UpdaterResult result = Source.single(new UpdaterData(model, model))
                        .via(writeFlow)
                        .completionTimeout(WRITE_TIMEOUT)
                        .runWith(Sink.head(), system)
                        .toCompletableFuture()
                        .get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                final long micros = (System.nanoTime() - started) / 1_000L;
                if (result.result().classify() == SearchWriteResultStatus.OK) {
                    updatesApplied[slot]++;
                    recorder.success(micros);
                } else {
                    recorder.error(index, String.valueOf(result.result()));
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Throwable t) {   // an Error too, so that no writer stops writing its things unnoticed
                recorder.error(index, t.toString());
            }
        }
    }

    private static void pollVacuums(final Connection connection, final SoakAnalysis.VacuumCycleTracker tracker,
            final Duration poll, final long t0, final AtomicBoolean stop, final List<String> errors) {
        while (!stop.get()) {
            try {
                tracker.observe((System.nanoTime() - t0) / 1e9, SoakDbStats.vacuumProgress(connection));
            } catch (final SQLException e) {
                if (errors.size() < MAX_ERROR_SAMPLES) {
                    errors.add(e.toString());
                }
            }
            try {
                Thread.sleep(poll.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void sleepUntil(final long deadlineNanos) throws InterruptedException {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)));
        }
    }

    /** Latencies and errors since the last drain, filled by the writers and drained by the sampler. */
    private static final class Recorder {

        record Counts(List<Long> latenciesMicros, long errors) {}

        private final ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
        private final LongAdder errors = new LongAdder();
        private final List<String> errorSamples = new ArrayList<>();

        void success(final long micros) {
            latenciesMicros.add(micros);
        }

        void error(final long index, final String what) {
            errors.increment();
            synchronized (errorSamples) {
                if (errorSamples.size() < MAX_ERROR_SAMPLES) {
                    errorSamples.add((index < 0 ? "" : SoakTwin.thingId(index) + ": ") + what);
                }
            }
        }

        Counts drain() {
            final List<Long> drained = new ArrayList<>();
            for (Long micros = latenciesMicros.poll(); micros != null; micros = latenciesMicros.poll()) {
                drained.add(micros);
            }
            return new Counts(drained, errors.sumThenReset());
        }

        List<String> errorSamples() {
            synchronized (errorSamples) {
                return List.copyOf(errorSamples);
            }
        }
    }

}
