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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class MixedChurnBenchTest {

    private static ChurnEngine.ChurnResult churn() {
        return new ChurnEngine.ChurnResult("fake", 10L, 0L, 1.0, List.of(), "");
    }

    private static SweepEngine.SweepResult pass(final long pids) {
        return new SweepEngine.SweepResult(pids, 0L, 0L, 0L, 0.0, List.of(), List.of(), false, Instant.now());
    }

    @Test
    public void aSinglePassThatOutlastsTheChurnIsAwaitedToItsEnd() throws Exception {
        final CountDownLatch churnDone = new CountDownLatch(1);
        final MixedChurnBench.SweptChurn run = MixedChurnBench.sweepWithChurn(stop -> {
            assertThat(churnDone.await(10, TimeUnit.SECONDS)).isTrue();   // still sweeping when the churn ends
            assertThat(stop.get()).isFalse();                              // and nobody stops the pass
            return pass(7);
        }, false, () -> {
            churnDone.countDown();
            return churn();
        });
        assertThat(run.passes()).extracting(SweepEngine.SweepResult::pids).containsExactly(7L);
    }

    @Test
    public void cyclingPassesStopWhenTheChurnEnds() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch twoPasses = new CountDownLatch(2);
        final MixedChurnBench.SweptChurn run = MixedChurnBench.sweepWithChurn(stop -> {
            calls.incrementAndGet();
            twoPasses.countDown();
            Thread.sleep(5);
            return pass(1);
        }, true, () -> {
            assertThat(twoPasses.await(10, TimeUnit.SECONDS)).isTrue();
            return churn();
        });
        assertThat(run.passes()).hasSizeGreaterThanOrEqualTo(2).hasSize(calls.get());
    }

    @Test
    public void aFailingSweeperFailsTheRun() {
        assertThatThrownBy(() -> MixedChurnBench.sweepWithChurn(stop -> {
            throw new SQLException("boom");
        }, false, MixedChurnBenchTest::churn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sweeper failed")
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    public void aFailingChurnStopsTheSweeper() {
        final AtomicReference<AtomicBoolean> seen = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);
        assertThatThrownBy(() -> MixedChurnBench.sweepWithChurn(stop -> {
            seen.set(stop);
            started.countDown();
            while (!stop.get()) {
                Thread.sleep(1);
            }
            return pass(0);
        }, false, () -> {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("churn worker failed");
        })).hasMessage("churn worker failed");
        assertThat(seen.get().get()).isTrue();
    }
}
