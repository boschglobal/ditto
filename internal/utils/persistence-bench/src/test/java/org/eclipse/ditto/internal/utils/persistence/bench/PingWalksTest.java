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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public final class PingWalksTest {

    private static ActorSystem system;
    private static Materializer mat;

    @BeforeClass
    public static void start() {
        system = ActorSystem.create("PingWalksTest");
        mat = SystemMaterializer.get(system).materializer();
    }

    @AfterClass
    public static void stop() {
        TestKit.shutdownActorSystem(system);
    }

    @Test
    public void aFailedWalkKeepsWhatItDeliveredAndItsCause() throws Exception {
        final StatementTimer timer = new StatementTimer();
        final PingWalks.Walk failed = PingWalks.walk("failing", Source.from(List.of("a", "b", "x")).map(pid -> {
            if (pid.equals("x")) {
                throw new IllegalStateException("boom");
            }
            return pid;
        }), timer, mat, Duration.ofSeconds(10));
        assertThat(failed.delivered()).containsExactly("a", "b");
        assertThat(failed.failure()).contains("boom");
        assertThat(PingWalks.walk("ok", Source.from(List.of("a")), timer, mat, Duration.ofSeconds(10)).failure())
                .isEmpty();
    }

    @Test
    public void checkListsMissingExtraAndDuplicatePidsAndTheOrder() {
        final PingWalks.Check check = PingWalks.check(List.of("a", "c", "c", "x"), List.of("a", "b", "c"), true);
        assertThat(check.missing()).containsExactly("b");
        assertThat(check.extra()).containsExactly("x");
        assertThat(check.duplicates()).containsExactly("c");
        assertThat(check.ascending()).isFalse();
        assertThat(check.problems()).hasSize(4);
        assertThat(PingWalks.check(List.of("a", "b", "c"), List.of("a", "b", "c"), true).problems()).isEmpty();
        assertThat(PingWalks.check(List.of("b", "a", "c"), List.of("a", "b", "c"), true).problems())
                .containsExactly("not in strictly ascending pid order");
        assertThat(PingWalks.check(List.of("c", "a", "b"), List.of("a", "b", "c"), false).problems())
                .as("the priority order is not in pid order").isEmpty();
    }

    @Test
    public void percentilesAreNearestRankAndTenthsSplitTheWalk() {
        final List<Long> values = LongStream.rangeClosed(1, 20).boxed().toList();
        assertThat(PingWalks.percentile(values, 50)).isEqualTo(10L);
        assertThat(PingWalks.percentile(values, 95)).isEqualTo(19L);
        assertThat(PingWalks.percentile(values, 99)).isEqualTo(20L);
        assertThat(PingWalks.tenthMedians(values)).containsExactly(1L, 3L, 5L, 7L, 9L, 11L, 13L, 15L, 17L, 19L);
    }

    @Test
    public void windowKStartsAfterTheLastPidOfWindowKMinusOne() {
        final List<String> sorted = List.of("a", "b", "c", "d", "e", "f", "g");
        assertThat(PingWalks.windowCursor(sorted, 0, 3)).isEmpty();
        assertThat(PingWalks.windowCursor(sorted, 1, 3)).isEqualTo("c");
        assertThat(PingWalks.windowCursor(sorted, 2, 3)).isEqualTo("f");
    }

    @Test
    public void aRiseInPriorityAlongThePriorityWalkIsAViolation() {
        final Map<String, Integer> priority = Map.of("a", 9, "b", 9, "c", 3, "d", 5);
        assertThat(PingWalks.priorityViolations(List.of("a", "b", "c"), priority)).isEmpty();
        assertThat(PingWalks.priorityViolations(List.of("a", "c", "d"), priority)).containsExactly("d");
    }

    @Test
    public void aWalkOffenderIsAFailureACheckProblemAFailedStatementOrTheTimeout() {
        final StatementTimer.Statement ok = new StatementTimer.Statement(Instant.EPOCH, 5L, 500, true, "", "w");
        final StatementTimer.Statement cancelled =
                new StatementTimer.Statement(Instant.EPOCH, 60L, 0, false, "SQLSTATE <57014> canceled", "w");
        final PingWalks.Walk failed = new PingWalks.Walk("control", List.of("a", "b"), 1L, List.of(ok, cancelled),
                "boom");
        assertThat(PingWalks.offenders(failed, PingWalks.check(failed.delivered(), List.of("a", "b", "c"), true), 60L,
                60L)).containsExactly("control: the walk failed: boom", "control: 1 missing, first [c]",
                "control: failed statement SQLSTATE <57014> canceled",
                "control: a statement reached statement_timeout");
        final PingWalks.Walk clean = new PingWalks.Walk("control", List.of("a"), 1L, List.of(ok), "");
        assertThat(PingWalks.offenders(clean, PingWalks.check(clean.delivered(), List.of("a"), true), 5L, 60L))
                .isEmpty();
    }

    @Test
    public void aReadJournalWarnNamesTheCursorOfTheWindowThatFailed() {
        final Instant at = Instant.parse("2026-10-22T03:52:09.929Z");
        assertThat(PingRecovery.pageWarning(at, "Page query of read-journal stream <pids with tag <always-alive>> "
                + "after <thing:bench.ns19:pid-00002138> failed on attempt <2> (at most <10> retries with back-off): "
                + "io.r2dbc.spi.R2dbcNonTransientResourceException: Connection validation failed"))
                .hasValueSatisfying(w -> {
                    assertThat(w.at()).isEqualTo(at);
                    assertThat(w.cursor()).isEqualTo("thing:bench.ns19:pid-00002138");
                    assertThat(w.attempt()).isEqualTo(2);
                });
        assertThat(PingRecovery.pageWarning(at, "Page query of read-journal stream <pids with tag <always-alive>> "
                + "after <the start> failed on attempt <1> (at most <10> retries with back-off): boom"))
                .hasValueSatisfying(w -> assertThat(w.cursor()).as("the first window").isEmpty());
        assertThat(PingRecovery.pageWarning(at, "another warning of the read journal")).isEmpty();
    }

    @Test
    public void theResumePointIsTakenAtTheWindowThatFailedNotWhereTheConsumerWasAtTheTrigger() {
        // the key space a..t is read in windows; the expected (tagged) pids are a subset, the cursor i is untagged
        final List<String> expected = List.of("b", "d", "f", "h", "k", "m", "p", "r");
        final Instant trigger = Instant.parse("2026-10-22T03:52:08.803291Z");
        final PingRecovery.Break restart =
                new PingRecovery.Break(PingRecovery.RESTART, 2, trigger, 10_000_000_000L, "restarted", "");
        // b and d are delivered when the trigger fires; the consumer drains the page it holds (f, h) while the window
        // after i fails twice; its third attempt delivers k 3 s after the trigger
        final List<Long> nanos = List.of(9_500_000_000L, 9_900_000_000L, 10_005_000_000L, 10_010_000_000L,
                13_000_000_000L, 13_000_000_100L, 13_000_000_200L, 13_000_000_300L);
        final List<PingRecovery.ResumePoint> resumes = PingRecovery.resumePoints(List.of(restart),
                List.of(warning(trigger.plusMillis(37), "i", 1), warning(trigger.plusMillis(1_126), "i", 2)),
                expected, expected, nanos);
        assertThat(resumes).hasSize(1);
        final PingRecovery.ResumePoint r = resumes.get(0);
        assertThat(r.warning()).hasValueSatisfying(w -> assertThat(w.attempt()).isEqualTo(1));
        assertThat(r.failedAttempts()).isEqualTo(2);
        assertThat(r.lastBefore()).as("the last pid up to the failed window's cursor, not d").isEqualTo("h");
        assertThat(r.firstAfter()).isEqualTo("k");
        assertThat(r.lastBeforeIndex()).isEqualTo(3);
        assertThat(r.firstAfterIndex()).isEqualTo(4);
        assertThat(r.contiguous()).isTrue();
        assertThat(r.sinceTrigger()).contains(Duration.ofSeconds(3));
        assertThat(r.sinceWarning()).contains(Duration.ofMillis(2_963));
    }

    @Test
    public void aBreakWithNoWarnBeforeTheNextBreakHadNoEffect() {
        final List<String> expected = List.of("b", "d", "f", "h");
        final List<Long> nanos = List.of(1L, 2L, 3_000_000_100L, 3_000_000_200L);
        final Instant t0 = Instant.parse("2026-10-22T03:52:05.223216Z");
        final List<PingRecovery.Break> breaks = List.of(
                new PingRecovery.Break(PingRecovery.TERMINATE, 1, t0, 10L, "terminated: true", ""),
                new PingRecovery.Break(PingRecovery.RESTART, 2, t0.plusSeconds(3), 3_000_000_010L, "restarted", ""));
        final List<PingRecovery.ResumePoint> resumes = PingRecovery.resumePoints(breaks,
                List.of(warning(t0.plusSeconds(3).plusMillis(40), "e", 1)), expected, expected, nanos);
        assertThat(resumes.get(0).warning()).as("the only WARN follows the restart").isEmpty();
        assertThat(resumes.get(0).contiguous()).isFalse();
        assertThat(resumes.get(1).lastBefore()).isEqualTo("d");
        assertThat(resumes.get(1).firstAfter()).isEqualTo("f");
        assertThat(resumes.get(1).contiguous()).isTrue();

        assertThat(PingRecovery.resumePoints(breaks.subList(0, 1),
                List.of(warning(Instant.parse("2026-10-22T03:52:05.223Z"), "", 1)), expected, expected,
                List.of(20L, 30L, 40L, 50L)).get(0))
                .as("a WARN logged in the trigger's millisecond (logback keeps milliseconds); the first window failed")
                .satisfies(r -> {
                    assertThat(r.lastBefore()).isEmpty();
                    assertThat(r.firstAfter()).isEqualTo("b");
                    assertThat(r.contiguous()).isTrue();
                });
    }

    @Test
    public void aGapAtTheFailedWindowIsNotContiguous() {
        final List<String> expected = List.of("b", "d", "f", "h");
        final Instant t0 = Instant.parse("2026-10-22T03:52:05Z");
        final PingRecovery.Break terminate = new PingRecovery.Break(PingRecovery.TERMINATE, 1, t0, 10L, "", "");
        final PingRecovery.ResumePoint r = PingRecovery.resumePoints(List.of(terminate),
                List.of(warning(t0.plusMillis(5), "e", 1)), expected, List.of("b", "d", "h"), List.of(1L, 2L, 50L))
                .get(0);
        assertThat(r.lastBefore()).isEqualTo("d");
        assertThat(r.firstAfter()).isEqualTo("h");
        assertThat(r.contiguous()).isFalse();
        assertThat(PingRecovery.resumePoints(List.of(terminate), List.of(warning(t0.plusMillis(5), "i", 1)), expected,
                expected, List.of(1L, 2L, 3L, 4L)).get(0).contiguous())
                .as("the failed window was the final, empty one: nothing after it, every pid delivered").isTrue();
    }

    @Test
    public void aRecoveryOffenderIsABreakWithoutEffectAGapOrARestartThatKeptThePostmaster() {
        final List<String> expected = List.of("b", "d", "f", "h");
        final List<Long> nanos = List.of(100L, 200_000_000L, 1_000_000_000L, 3_500_000_000L);
        final Instant t0 = Instant.parse("2026-10-22T03:52:05Z");
        final List<PingRecovery.Break> breaks = List.of(
                new PingRecovery.Break(PingRecovery.TERMINATE, 1, t0, 150L, "terminated: true", ""),
                new PingRecovery.Break(PingRecovery.RESTART, 3, t0.plusSeconds(3), 3_000_000_150L, "restarted", ""));
        final PingWalks.Check check = PingWalks.check(expected, expected, true);
        final PingRecovery.Result noWarn = new PingRecovery.Result(expected, nanos, 1L, Optional.empty(), breaks,
                PingRecovery.resumePoints(breaks, List.of(warning(t0.plusSeconds(3).plusMillis(40), "g", 1)),
                        expected, expected, nanos));
        assertThat(PingRecovery.offenders(noWarn, check, false)).satisfiesExactly(
                o -> assertThat(o).startsWith("pg_terminate_backend: no read-journal WARN"),
                o -> assertThat(o).startsWith("container restart: the postmaster start time did not change"));
        final PingRecovery.Result clean = new PingRecovery.Result(expected, nanos, 1L, Optional.empty(), breaks,
                PingRecovery.resumePoints(breaks, List.of(warning(t0.plusMillis(3), "c", 1),
                        warning(t0.plusSeconds(3).plusMillis(40), "g", 1)), expected, expected, nanos));
        assertThat(PingRecovery.offenders(clean, check, true)).isEmpty();
        assertThat(PingRecovery.offenders(clean, check, false)).hasSize(1);
    }

    private static PingRecovery.Warning warning(final Instant at, final String cursor, final int attempt) {
        return new PingRecovery.Warning(at, cursor, attempt, "after <" + cursor + "> failed on attempt <" + attempt
                + ">");
    }
}
