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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.Test;

/**
 * Unit test of the write-soak verdicts: the throughput gate, the bloat plateau, vacuum-cycle tracking and the
 * autovacuum trigger a relation gets from its reloptions.
 */
public final class SoakAnalysisTest {

    private static final SoakAnalysis.VacuumTrigger TRIGGER = new SoakAnalysis.VacuumTrigger(50, 0.01);

    @Test
    public void theGateIsMetOnlyWhenEveryGatedWindowReachesTheRateForAnHour() {
        final List<SoakAnalysis.Window> windows = new ArrayList<>();
        windows.add(rateWindow(1, false, 3_000, 30.0));                       // warm-up at 100/s: not gated
        IntStream.rangeClosed(2, 121).forEach(n -> windows.add(rateWindow(n, true, 6_300, 30.0)));   // 210/s

        final SoakAnalysis.GateVerdict met = SoakAnalysis.throughputGate(windows, 200.0, Duration.ofHours(1));
        assertThat(met.met()).as(met.reason()).isTrue();
        assertThat(met.gatedSeconds()).isEqualTo(3_600.0);
        assertThat(met.longestPassingRun()).isEqualTo(120);

        windows.set(60, rateWindow(61, true, 6_000, 30.0));   // exactly 200.0/s reaches the gate
        assertThat(SoakAnalysis.throughputGate(windows, 200.0, Duration.ofHours(1)).met()).isTrue();

        windows.set(60, rateWindow(61, true, 6_000, 30.2));   // 198.7/s: the measured length counts, not 30 s
        final SoakAnalysis.GateVerdict missed = SoakAnalysis.throughputGate(windows, 200.0, Duration.ofHours(1));
        assertThat(missed.met()).isFalse();
        assertThat(missed.failingWindows()).containsExactly(61);
        assertThat(missed.minRate()).isCloseTo(198.68, within(0.01));
        assertThat(missed.reason()).contains("1 of 120 gated windows");
        assertThat(missed.longestPassingRun()).isEqualTo(60);
    }

    @Test
    public void aGatedPeriodShorterThanAnHourOrAFailedUpdateMissesTheGate() {
        final List<SoakAnalysis.Window> tenMinutes = IntStream.rangeClosed(1, 20)
                .mapToObj(n -> rateWindow(n, true, 6_600, 30.0)).toList();
        final SoakAnalysis.GateVerdict shortRun = SoakAnalysis.throughputGate(tenMinutes, 200.0, Duration.ofHours(1));
        assertThat(shortRun.met()).isFalse();
        assertThat(shortRun.reason()).contains("shorter than the required 3600 s");

        final List<SoakAnalysis.Window> hour = new ArrayList<>(IntStream.rangeClosed(1, 120)
                .mapToObj(n -> rateWindow(n, true, 6_600, 30.0)).toList());
        hour.set(5, new SoakAnalysis.Window(6, 180.0, 30.0, true, 6_600, 1, 0, 0, 0, 0, 0, Map.of(), Map.of(),
                Map.of(), Map.of(), List.of()));
        final SoakAnalysis.GateVerdict failed = SoakAnalysis.throughputGate(hour, 200.0, Duration.ofHours(1));
        assertThat(failed.met()).isFalse();
        assertThat(failed.reason()).isEqualTo("1 update(s) failed");
    }

    @Test
    public void deadTuplesAreBoundedWhenAutovacuumRanAndTheyStayNearTheTrigger() {
        // 20 windows of 60 s; the plateau window is the last 600 s (start 600, half 900, end 1200)
        final List<SoakAnalysis.Window> sawtooth = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> flatWindow(i, 60.0 * i, 5_000 + (i % 5) * 2_000L, 1_000_000_000L + i * 100_000L,
                        i / 5))
                .toList();
        final SoakAnalysis.PlateauVerdict plateau = SoakAnalysis.plateau(SoakAnalysis.SEARCH_FLAT, sawtooth,
                Duration.ofSeconds(600), Duration.ofSeconds(60), TRIGGER);
        assertThat(plateau.plateau()).as(plateau.reason()).isTrue();
        assertThat(plateau.vacuumThreshold()).isEqualTo(10_050);
        assertThat(plateau.maxDeadTuples()).isEqualTo(13_000);
        assertThat(plateau.autovacuumsInWindow()).isEqualTo(2);

        final List<SoakAnalysis.Window> climbing = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> flatWindow(i, 60.0 * i, 5_000L * i, 1_000_000_000L + i * 100_000L, 0))
                .toList();
        final SoakAnalysis.PlateauVerdict noVacuum = SoakAnalysis.plateau(SoakAnalysis.SEARCH_FLAT, climbing,
                Duration.ofSeconds(600), Duration.ofSeconds(60), TRIGGER);
        assertThat(noVacuum.deadTuplesBounded()).isFalse();
        assertThat(noVacuum.reason()).contains("no autovacuum completed").contains("dead tuples peaked at 100,000");
    }

    @Test
    public void theSizeIsFlatOnlyWhenTheSecondHalfGrowsAtMostHalfAPercent() {
        final List<SoakAnalysis.Window> growing = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> flatWindow(i, 60.0 * i, 5_000, 1_000_000_000L + i * 10_000_000L, i / 5))
                .toList();
        final SoakAnalysis.PlateauVerdict verdict = SoakAnalysis.plateau(SoakAnalysis.SEARCH_FLAT, growing,
                Duration.ofSeconds(600), Duration.ofSeconds(60), TRIGGER);
        assertThat(verdict.deadTuplesBounded()).isTrue();
        assertThat(verdict.sizeFlat()).isFalse();
        assertThat(verdict.sizeAtHalf()).isEqualTo(1_150_000_000L);
        assertThat(verdict.sizeAtEnd()).isEqualTo(1_200_000_000L);
        assertThat(verdict.reason()).contains("grew 50,000,000 bytes");
    }

    @Test
    public void bothPlateauLimitsAreInclusiveAndDeadTuplesBeforeThePlateauWindowDoNotCount() {
        // windows 1-10 lie before the plateau window: their dead tuples are far above 2 x the trigger 10,050
        final List<SoakAnalysis.Window> windows = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> flatWindow(i, 60.0 * i, i <= 10 ? 100_000 : 20_100,
                        i <= 15 ? 1_000_000_000L : 1_005_000_000L, i >= 12 ? 1 : 0))
                .toList();
        final SoakAnalysis.PlateauVerdict verdict = SoakAnalysis.plateau(SoakAnalysis.SEARCH_FLAT, windows,
                Duration.ofSeconds(600), Duration.ofSeconds(60), TRIGGER);
        assertThat(verdict.maxDeadTuples()).isEqualTo(20_100);
        assertThat(verdict.deadTuplesBounded()).as(verdict.reason()).isTrue();
        assertThat(verdict.sizeFlat()).as(verdict.reason()).isTrue();   // grew exactly 0.5 %
    }

    @Test
    public void thePlateauWindowIsTheLastWindowsByCountWhateverTheTimerJitter() {
        // window 10 (the one before the plateau window) ends 2 ms late, window 20 only 1 ms: an autovacuum and a
        // dead-tuple peak in window 10 must not count for the plateau window 11-20
        final List<SoakAnalysis.Window> windows = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> flatWindow(i, 60.0 * i + (i == 10 ? 0.002 : i == 20 ? 0.001 : 0.0015),
                        i == 10 ? 30_000 : 5_000, 1_000_000_000L, i >= 10 ? 1 : 0))
                .toList();
        final SoakAnalysis.PlateauVerdict verdict = SoakAnalysis.plateau(SoakAnalysis.SEARCH_FLAT, windows,
                Duration.ofSeconds(600), Duration.ofSeconds(60), TRIGGER);
        assertThat(verdict.autovacuumsInWindow()).isZero();
        assertThat(verdict.maxDeadTuples()).isEqualTo(5_000);
        assertThat(verdict.deadTuplesBounded()).isFalse();
        assertThat(verdict.reason()).contains("no autovacuum completed");
    }

    @Test
    public void theTrackerTurnsPollsIntoCyclesWithIndexPassesAndWaitEvents() {
        final SoakAnalysis.VacuumCycleTracker tracker = new SoakAnalysis.VacuumCycleTracker();
        tracker.observe(10.0, List.of(observation(101, SoakAnalysis.SEARCH_FLAT, 0, "Timeout:VacuumDelay")));
        tracker.observe(12.0, List.of(observation(101, SoakAnalysis.SEARCH_FLAT, 1, "IO:DataFileRead"),
                observation(102, SoakAnalysis.SEARCH_THINGS, 0, "CPU")));
        assertThat(tracker.running()).hasSize(2);
        tracker.observe(14.0, List.of(observation(101, SoakAnalysis.SEARCH_FLAT, 2, "IO:DataFileRead")));
        tracker.observe(16.0, List.of());
        tracker.observe(18.0, List.of(observation(103, SoakAnalysis.SEARCH_FLAT, 0, "CPU")));

        assertThat(tracker.drainWindowWaitEvents()).isEqualTo(Map.of(
                SoakAnalysis.SEARCH_FLAT, Map.of("Timeout:VacuumDelay", 1L, "IO:DataFileRead", 2L, "CPU", 1L),
                SoakAnalysis.SEARCH_THINGS, Map.of("CPU", 1L)));
        assertThat(tracker.drainWindowWaitEvents()).isEmpty();

        final List<SoakAnalysis.VacuumCycle> cycles = tracker.finish();
        assertThat(cycles).hasSize(3);
        final SoakAnalysis.VacuumCycle flat = cycles.stream().filter(c -> c.pid() == 101).findFirst().orElseThrow();
        assertThat(flat.finished()).isTrue();
        assertThat(flat.seconds()).isEqualTo(4.0);
        assertThat(flat.maxIndexVacuumCount()).isEqualTo(2);
        assertThat(flat.waitEvents()).containsEntry("IO:DataFileRead", 2L).containsEntry("Timeout:VacuumDelay", 1L);
        assertThat(cycles.stream().filter(c -> c.pid() == 103).findFirst().orElseThrow().finished()).isFalse();
    }

    @Test
    public void aToastTableWithoutOwnReloptionsInheritsTheTablesThenTheServers() {
        final List<String> table = List.of("fillfactor=90", "autovacuum_vacuum_scale_factor=0.01");
        final SoakAnalysis.VacuumTrigger toast = SoakAnalysis.VacuumTrigger.resolve(List.of(), table, 50, 0.2);
        assertThat(toast).isEqualTo(new SoakAnalysis.VacuumTrigger(50, 0.01));
        assertThat(SoakAnalysis.VacuumTrigger.resolve(List.of(), List.of(), 50, 0.2))
                .isEqualTo(new SoakAnalysis.VacuumTrigger(50, 0.2));
        assertThat(toast.threshold(-1)).isEqualTo(50);   // never analysed: reltuples -1
    }

    @Test
    public void aToastTableWithAnyOwnReloptionTakesTheServersValueForEveryUnsetOne() {
        // autovacuum uses the TOAST table's whole option set once it has any reloption, never the parent's
        final List<String> table = List.of("autovacuum_vacuum_threshold=1000", "autovacuum_vacuum_scale_factor=0.01");
        assertThat(SoakAnalysis.VacuumTrigger.resolve(List.of("vacuum_truncate=false"), table, 50, 0.2))
                .isEqualTo(new SoakAnalysis.VacuumTrigger(50, 0.2));
        assertThat(SoakAnalysis.VacuumTrigger.resolve(List.of("autovacuum_vacuum_threshold=500"), table, 50, 0.2))
                .isEqualTo(new SoakAnalysis.VacuumTrigger(500, 0.2));
    }

    private static SoakAnalysis.Window rateWindow(final int number, final boolean gated, final long updates,
            final double seconds) {
        return new SoakAnalysis.Window(number, number * seconds, seconds, gated, updates, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    private static SoakAnalysis.Window flatWindow(final int number, final double endSeconds, final long dead,
            final long totalBytes, final long autovacuums) {
        final SoakAnalysis.RelationSample flat = new SoakAnalysis.RelationSample(SoakAnalysis.SEARCH_FLAT,
                1_000_000, dead, 1_000_000, 0, 0, 0, 0, autovacuums, null, totalBytes / 2, totalBytes / 2,
                totalBytes);
        return new SoakAnalysis.Window(number, endSeconds, 60.0, true, 12_000, 0, 1_000, 2_000, 3_000, 0, 0,
                Map.of(SoakAnalysis.SEARCH_FLAT, flat), Map.of(), Map.of(), Map.of(), List.of());
    }

    private static SoakAnalysis.VacuumObservation observation(final int pid, final String relation,
            final long indexPasses, final String waitEvent) {
        return new SoakAnalysis.VacuumObservation(pid, relation, "vacuuming indexes", 1_000, 500, indexPasses, true,
                waitEvent);
    }

}
