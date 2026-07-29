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

import org.junit.Test;

public final class CreditPacerTest {

    @Test
    public void disabledPacerNeverBlocks() {
        final SweepEngine.CreditPacer pacer = new SweepEngine.CreditPacer(false, Duration.ofSeconds(3));
        final long t0 = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            pacer.acquire();
        }
        assertThat(System.nanoTime() - t0).isLessThan(100_000_000L);
    }

    @Test
    public void enabledPacerReleasesCreditsPerBatchPerInterval() {
        // 3 credits per 100 ms window: 7 acquires need at least 2 full window waits (~200 ms)
        final SweepEngine.CreditPacer pacer = new SweepEngine.CreditPacer(true, Duration.ofMillis(100));
        final long t0 = System.nanoTime();
        for (int i = 0; i < 7; i++) {
            pacer.acquire();
        }
        final long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(200L);
        assertThat(elapsedMs).isLessThan(2_000L);
    }
}
