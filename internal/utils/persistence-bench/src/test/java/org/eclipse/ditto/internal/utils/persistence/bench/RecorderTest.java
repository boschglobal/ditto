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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Focused unit tests for {@link ChurnEngine.Recorder}: record()/swap() must be mutually exclusive so
 * no sample can land in an orphaned window list — i.e. sum(window.ops) must always equal total().
 */
public final class RecorderTest {

    @Test
    public void recordsBeforeSwapAppearInSwappedWindow() {
        final ChurnEngine.Recorder recorder = new ChurnEngine.Recorder();
        recorder.record(1.0);
        recorder.record(2.0);
        final List<Double> window = recorder.swap();
        assertThat(window).containsExactly(1.0, 2.0);
        assertThat(recorder.total()).isEqualTo(2L);
    }

    @Test
    public void recordsAfterSwapGoToTheNextWindow() {
        final ChurnEngine.Recorder recorder = new ChurnEngine.Recorder();
        recorder.record(1.0);
        final List<Double> firstWindow = recorder.swap();
        recorder.record(2.0);
        recorder.record(3.0);
        final List<Double> secondWindow = recorder.swap();
        assertThat(firstWindow).containsExactly(1.0);
        assertThat(secondWindow).containsExactly(2.0, 3.0);
        assertThat(recorder.total()).isEqualTo(3L);
    }

    @Test
    public void swapOnAnEmptyCurrentWindowReturnsEmptyAndStartsFresh() {
        final ChurnEngine.Recorder recorder = new ChurnEngine.Recorder();
        assertThat(recorder.swap()).isEmpty();
        recorder.record(5.0);
        assertThat(recorder.swap()).containsExactly(5.0);
    }

    /**
     * Stress test reproducing the exact race the fix closes: many producer threads calling record()
     * concurrently with a thread hammering swap(). Before the fix (record() reading the current list
     * reference, then synchronizing on it, non-atomically with swap()'s getAndSet+copy), a record()
     * could add its sample to a list that swap() had already detached and would never surface again —
     * the sample would still be counted in total() but would vanish from every window, breaking
     * sum(window.ops) == total(). With record()/swap() both synchronized on the Recorder instance, that
     * interleaving is impossible, so the invariant must hold no matter how the threads interleave.
     */
    @Test
    public void totalAlwaysEqualsSumOfWindowsUnderConcurrentRecordAndSwap() throws Exception {
        final ChurnEngine.Recorder recorder = new ChurnEngine.Recorder();
        final int producerThreads = 8;
        final int recordsPerThread = 20_000;
        final List<List<Double>> windows = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean stopSwapping = new AtomicBoolean(false);

        final ExecutorService swapperPool = Executors.newSingleThreadExecutor();
        final Future<?> swapper = swapperPool.submit(() -> {
            while (!stopSwapping.get()) {
                windows.add(recorder.swap());
            }
        });

        final ExecutorService producerPool = Executors.newFixedThreadPool(producerThreads);
        final List<Future<?>> producers = new ArrayList<>();
        for (int i = 0; i < producerThreads; i++) {
            producers.add(producerPool.submit(() -> {
                for (int j = 0; j < recordsPerThread; j++) {
                    recorder.record(1.0);
                }
            }));
        }
        for (final Future<?> f : producers) {
            f.get(30, TimeUnit.SECONDS);
        }
        stopSwapping.set(true);
        swapper.get(30, TimeUnit.SECONDS);
        windows.add(recorder.swap()); // final flush: anything recorded after the last background swap

        producerPool.shutdown();
        swapperPool.shutdown();
        assertThat(producerPool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(swapperPool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        final long sumOfWindows = windows.stream().mapToLong(List::size).sum();
        assertThat(recorder.total()).isEqualTo((long) producerThreads * recordsPerThread);
        assertThat(sumOfWindows).isEqualTo(recorder.total());
    }
}
