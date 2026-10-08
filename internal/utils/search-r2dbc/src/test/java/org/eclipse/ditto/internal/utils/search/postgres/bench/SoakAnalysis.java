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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * The write-soak samples and the verdicts computed from them (pure: no database). A window's rate is its successful
 * updates divided by its MEASURED length. The throughput gate is met when the gated windows cover at least
 * {@link #MINIMUM_GATED}, each reaches the gate rate, and no update failed. The bloat plateau of a relation, over the
 * trailing plateau window, needs both: dead tuples bounded (an autovacuum completed in the window, and the peak
 * {@code n_dead_tup} stayed at or below {@link #DEAD_TUPLE_THRESHOLD_FACTOR} times the autovacuum trigger) and a flat
 * size ({@code pg_total_relation_size} grew at most {@link #SIZE_GROWTH_LIMIT} over the window's second half).
 */
final class SoakAnalysis {

    static final String SEARCH_FLAT = "search_flat";
    static final String SEARCH_THINGS = "search_things";
    static final String SEARCH_THINGS_TOAST = "search_things TOAST";
    static final List<String> RELATIONS = List.of(SEARCH_FLAT, SEARCH_THINGS, SEARCH_THINGS_TOAST);
    static final Duration MINIMUM_GATED = Duration.ofHours(1);
    static final double SIZE_GROWTH_LIMIT = 0.005;
    static final double DEAD_TUPLE_THRESHOLD_FACTOR = 2.0;

    private static final double GATED_TOLERANCE_SECONDS = 1.0;

    private SoakAnalysis() {
        throw new AssertionError("no instances");
    }

    /** Cumulative counters ({@code pg_stat_all_tables}) and sizes of one relation at a sample instant. */
    record RelationSample(String relation, long liveTuples, long deadTuples, long reltuples, long tuplesInserted,
            long tuplesUpdated, long tuplesHotUpdated, long tuplesDeleted, long autovacuumCount,
            @Nullable String lastAutovacuum, long heapBytes, long indexBytes, long totalBytes) {}

    /** The counter changes of one relation over one window. */
    record RelationDelta(long inserted, long updated, long hotUpdated, long deleted, long autovacuums) {

        static final RelationDelta ZERO = new RelationDelta(0, 0, 0, 0, 0);

        static RelationDelta between(final RelationSample before, final RelationSample after) {
            return new RelationDelta(after.tuplesInserted() - before.tuplesInserted(),
                    after.tuplesUpdated() - before.tuplesUpdated(),
                    after.tuplesHotUpdated() - before.tuplesHotUpdated(),
                    after.tuplesDeleted() - before.tuplesDeleted(),
                    after.autovacuumCount() - before.autovacuumCount());
        }
    }

    /** All relations, the search tables' index sizes and the cumulative WAL position / full-page images. */
    record Snapshot(Map<String, RelationSample> relations, Map<String, Long> indexBytes, long walBytes,
            long walFullPageImages) {}

    /** One sample window; latency, WAL and deltas are per window, relation samples are cumulative at its end. */
    record Window(int number, double endSeconds, double seconds, boolean gated, long updates, long errors,
            long p50Micros, long p95Micros, long maxMicros, long walBytes, long walFullPageImages,
            Map<String, RelationSample> relations, Map<String, RelationDelta> deltas, Map<String, Long> indexBytes,
            Map<String, Map<String, Long>> vacuumWaitEvents, List<String> vacuumsRunning) {

        double rate() {
            return seconds <= 0 ? 0.0 : updates / seconds;
        }
    }

    /** One poll of a running vacuum: {@code pg_stat_progress_vacuum} joined with {@code pg_stat_activity}. */
    record VacuumObservation(int pid, String relation, String phase, long heapBlocksTotal, long heapBlocksScanned,
            long indexVacuumCount, boolean autovacuum, String waitEvent) {}

    /** A vacuum as the poller saw it, from its first to its last observation (resolution: the poll interval). */
    record VacuumCycle(String relation, int pid, boolean autovacuum, double firstSeenSeconds,
            double lastSeenSeconds, boolean finished, long maxIndexVacuumCount, long heapBlocksTotal,
            Map<String, Long> waitEvents) {

        double seconds() {
            return lastSeenSeconds - firstSeenSeconds;
        }
    }

    /** {@code autovacuum_vacuum_threshold} + {@code autovacuum_vacuum_scale_factor} as autovacuum applies them. */
    record VacuumTrigger(double baseThreshold, double scaleFactor) {

        /**
         * As autovacuum resolves them ({@code extract_autovac_opts}): a relation with any reloption of its own takes
         * every value it does not set from the server settings. Only a TOAST table with no reloption at all uses its
         * parent table's reloptions instead, again with the server settings for what those do not set.
         */
        static VacuumTrigger resolve(final List<String> own, final List<String> parentTable, final double serverBase,
                final double serverScale) {
            final List<String> options = own.isEmpty() ? parentTable : own;
            return new VacuumTrigger(reloption(options, "autovacuum_vacuum_threshold", serverBase),
                    reloption(options, "autovacuum_vacuum_scale_factor", serverScale));
        }

        long threshold(final long reltuples) {
            return Math.round(baseThreshold + scaleFactor * Math.max(0, reltuples));
        }
    }

    record GateVerdict(boolean met, double gatedSeconds, double minRate, long errors, List<Integer> failingWindows,
            int longestPassingRun, double longestPassingSeconds, String reason) {}

    record PlateauVerdict(String relation, boolean deadTuplesBounded, boolean sizeFlat, long maxDeadTuples,
            long vacuumThreshold, long autovacuumsInWindow, long sizeAtHalf, long sizeAtEnd, String reason) {

        boolean plateau() {
            return deadTuplesBounded && sizeFlat;
        }
    }

    static Window window(final int number, final double endSeconds, final double seconds, final boolean gated,
            final List<Long> latenciesMicros, final long errors, final Snapshot previous, final Snapshot current,
            final Map<String, Map<String, Long>> vacuumWaitEvents, final List<String> vacuumsRunning) {
        final List<Long> sorted = new ArrayList<>(latenciesMicros);
        Collections.sort(sorted);
        final Map<String, RelationDelta> deltas = new LinkedHashMap<>();
        current.relations().forEach((relation, now) -> {
            final RelationSample before = previous.relations().get(relation);
            deltas.put(relation, before == null ? RelationDelta.ZERO : RelationDelta.between(before, now));
        });
        return new Window(number, endSeconds, seconds, gated, sorted.size(), errors, percentile(sorted, 50),
                percentile(sorted, 95), sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1),
                current.walBytes() - previous.walBytes(),
                current.walFullPageImages() - previous.walFullPageImages(), current.relations(), deltas,
                current.indexBytes(), vacuumWaitEvents, vacuumsRunning);
    }

    /** Nearest-rank percentile of an ascending list; 0 when empty. */
    static long percentile(final List<Long> sorted, final int pct) {
        if (sorted.isEmpty()) {
            return 0;
        }
        final int rank = (int) Math.ceil(pct / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    static GateVerdict throughputGate(final List<Window> windows, final double gateRate,
            final Duration minimumGated) {
        final List<Window> gated = windows.stream().filter(Window::gated).toList();
        final double gatedSeconds = gated.stream().mapToDouble(Window::seconds).sum();
        final long errors = windows.stream().mapToLong(Window::errors).sum();
        final List<Integer> failing = new ArrayList<>();
        int run = 0;
        int longest = 0;
        double runSeconds = 0;
        double longestSeconds = 0;
        for (final Window window : gated) {
            if (window.rate() < gateRate) {
                failing.add(window.number());
                run = 0;
                runSeconds = 0;
            } else {
                run++;
                runSeconds += window.seconds();
                if (run > longest) {
                    longest = run;
                    longestSeconds = runSeconds;
                }
            }
        }
        final double minRate = gated.stream().mapToDouble(Window::rate).min().orElse(0.0);
        final boolean longEnough = gatedSeconds + GATED_TOLERANCE_SECONDS >= minimumGated.toSeconds();
        final String reason;
        if (gated.isEmpty()) {
            reason = "no gated window";
        } else if (!longEnough) {
            reason = String.format(Locale.ROOT, "the gated period lasted %.0f s, shorter than the required %d s",
                    gatedSeconds, minimumGated.toSeconds());
        } else if (!failing.isEmpty()) {
            reason = String.format(Locale.ROOT, "%d of %d gated windows stayed below %.1f updates/s",
                    failing.size(), gated.size(), gateRate);
        } else if (errors > 0) {
            reason = errors + " update(s) failed";
        } else {
            reason = String.format(Locale.ROOT, "all %d gated windows reached %.1f updates/s over %.0f s "
                    + "with no failed update", gated.size(), gateRate, gatedSeconds);
        }
        final boolean met = !gated.isEmpty() && longEnough && failing.isEmpty() && errors == 0;
        return new GateVerdict(met, gatedSeconds, minRate, errors, List.copyOf(failing), longest, longestSeconds,
                reason);
    }

    /**
     * The plateau window is the last {@code round(plateauWindow / window)} gated windows, picked by count so that the
     * jitter of the measured window ends cannot move its boundaries. Its start sample is the window before them, its
     * half sample the window after the first half of them.
     */
    static PlateauVerdict plateau(final String relation, final List<Window> windows, final Duration plateauWindow,
            final Duration window, final VacuumTrigger trigger) {
        final List<Window> sampled = windows.stream().filter(w -> w.relations().containsKey(relation)).toList();
        final long gatedCount = sampled.stream().filter(Window::gated).count();
        if (gatedCount < 2) {
            return new PlateauVerdict(relation, false, false, 0, 0, 0, 0, 0, "fewer than two gated windows");
        }
        final int count = (int) Math.min(gatedCount,
                Math.max(1, Math.round((double) plateauWindow.toMillis() / window.toMillis())));
        final int first = sampled.size() - count;
        final RelationSample atStart = sampled.get(Math.max(0, first - 1)).relations().get(relation);
        final RelationSample atHalf = sampled.get(Math.max(0, first - 1 + count / 2)).relations().get(relation);
        final RelationSample atEnd = sampled.get(sampled.size() - 1).relations().get(relation);
        final long maxDead = sampled.subList(first, sampled.size()).stream()
                .mapToLong(w -> w.relations().get(relation).deadTuples())
                .max()
                .orElse(atEnd.deadTuples());
        final long threshold = trigger.threshold(atEnd.reltuples());
        final long autovacuums = atEnd.autovacuumCount() - atStart.autovacuumCount();
        final boolean deadBelow = maxDead <= DEAD_TUPLE_THRESHOLD_FACTOR * threshold;
        final boolean bounded = autovacuums >= 1 && deadBelow;
        final long growth = atEnd.totalBytes() - atHalf.totalBytes();
        final boolean flat = growth <= SIZE_GROWTH_LIMIT * atHalf.totalBytes();
        final List<String> reasons = new ArrayList<>();
        if (autovacuums < 1) {
            reasons.add("no autovacuum completed in the window");
        }
        if (!deadBelow) {
            reasons.add(String.format(Locale.ROOT, "dead tuples peaked at %,d, above %.1f x the trigger %,d",
                    maxDead, DEAD_TUPLE_THRESHOLD_FACTOR, threshold));
        }
        if (!flat) {
            reasons.add(String.format(Locale.ROOT, "total size grew %,d bytes (%.2f %%) over the second half",
                    growth, 100.0 * growth / Math.max(1, atHalf.totalBytes())));
        }
        return new PlateauVerdict(relation, bounded, flat, maxDead, threshold, autovacuums, atHalf.totalBytes(),
                atEnd.totalBytes(), reasons.isEmpty() ? "plateau" : String.join("; ", reasons));
    }

    /** @return the value of {@code name} in reloptions like {@code [autovacuum_vacuum_scale_factor=0.01]}. */
    static double reloption(final List<String> reloptions, final String name, final double fallback) {
        for (final String option : reloptions) {
            if (option.startsWith(name + "=")) {
                return Double.parseDouble(option.substring(name.length() + 1));
            }
        }
        return fallback;
    }

    /**
     * Turns polls of {@code pg_stat_progress_vacuum} into vacuum cycles: a cycle is one (pid, relation) pair seen in
     * consecutive polls, closed by the first poll without it. Called by the poller thread and the sampler thread.
     */
    static final class VacuumCycleTracker {

        private final Map<String, Open> open = new LinkedHashMap<>();
        private final List<VacuumCycle> closed = new ArrayList<>();
        private final Map<String, Map<String, Long>> windowWaitEvents = new TreeMap<>();

        synchronized void observe(final double atSeconds, final List<VacuumObservation> observations) {
            final Set<String> seen = new HashSet<>();
            for (final VacuumObservation observation : observations) {
                final String key = observation.pid() + "/" + observation.relation();
                seen.add(key);
                open.computeIfAbsent(key, k -> new Open(observation, atSeconds)).update(observation, atSeconds);
                windowWaitEvents.computeIfAbsent(observation.relation(), r -> new TreeMap<>())
                        .merge(observation.waitEvent(), 1L, Long::sum);
            }
            final Iterator<Map.Entry<String, Open>> it = open.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, Open> entry = it.next();
                if (!seen.contains(entry.getKey())) {
                    closed.add(entry.getValue().toCycle(true));
                    it.remove();
                }
            }
        }

        /** @return per relation, how often each wait event was polled since the last drain (then forgets them). */
        synchronized Map<String, Map<String, Long>> drainWindowWaitEvents() {
            final Map<String, Map<String, Long>> drained = new TreeMap<>();
            windowWaitEvents.forEach((relation, events) -> drained.put(relation, Map.copyOf(events)));
            windowWaitEvents.clear();
            return drained;
        }

        synchronized List<String> running() {
            return open.values().stream().map(Open::describe).toList();
        }

        /** @return every cycle seen, in closing order; cycles still running count as unfinished. */
        synchronized List<VacuumCycle> finish() {
            final List<VacuumCycle> all = new ArrayList<>(closed);
            open.values().forEach(cycle -> all.add(cycle.toCycle(false)));
            open.clear();
            return all;
        }

        private static final class Open {

            private final String relation;
            private final int pid;
            private final boolean autovacuum;
            private final double firstSeen;
            private final Map<String, Long> waitEvents = new TreeMap<>();
            private double lastSeen;
            private long maxIndexVacuumCount;
            private long heapBlocksTotal;
            private String phase = "";

            private Open(final VacuumObservation first, final double atSeconds) {
                relation = first.relation();
                pid = first.pid();
                autovacuum = first.autovacuum();
                firstSeen = atSeconds;
            }

            private void update(final VacuumObservation observation, final double atSeconds) {
                lastSeen = atSeconds;
                maxIndexVacuumCount = Math.max(maxIndexVacuumCount, observation.indexVacuumCount());
                heapBlocksTotal = Math.max(heapBlocksTotal, observation.heapBlocksTotal());
                phase = observation.phase();
                waitEvents.merge(observation.waitEvent(), 1L, Long::sum);
            }

            private String describe() {
                return relation + " pid " + pid + ": " + phase + ", index passes " + maxIndexVacuumCount;
            }

            private VacuumCycle toCycle(final boolean finished) {
                return new VacuumCycle(relation, pid, autovacuum, firstSeen, lastSeen, finished,
                        maxIndexVacuumCount, heapBlocksTotal, Map.copyOf(waitEvents));
            }
        }
    }

}
