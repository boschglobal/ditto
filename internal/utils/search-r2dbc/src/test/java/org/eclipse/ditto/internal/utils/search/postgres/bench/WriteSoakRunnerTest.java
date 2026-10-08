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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;

import org.junit.Test;

/** Unit test of the write-soak settings checks and the gated-latency histogram. */
public final class WriteSoakRunnerTest {

    @Test
    public void theDefaultSettingsAreValid() {
        settings(24, Duration.ofHours(1), Duration.ofHours(1), Duration.ofSeconds(2)).requireWorkersWithin(25);
        settings(25, Duration.ofHours(1), Duration.ofHours(1), Duration.ofSeconds(2)).requireWorkersWithin(25);
    }

    @Test
    public void aPlateauLongerThanTheGatedPeriodIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> settings(24, Duration.ofHours(1), Duration.ofMinutes(61), Duration.ofSeconds(2)))
                .withMessageContaining("plateau");
    }

    @Test
    public void aVacuumPollNotShorterThanTheWindowIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> settings(24, Duration.ofHours(1), Duration.ofHours(1), Duration.ofSeconds(30)))
                .withMessageContaining("vacuumPoll");
    }

    @Test
    public void moreWritersThanTheShippedPoolHasConnectionsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> settings(26, Duration.ofHours(1), Duration.ofHours(1), Duration.ofSeconds(2))
                        .requireWorkersWithin(25))
                .withMessageContaining("max-size");
    }

    @Test
    public void gatedLatencyPercentilesComeFromTheHistogramUpperBucketBounds() {
        final long[] histogram = new long[WriteSoakRunner.HISTOGRAM_BUCKETS];
        histogram[10] = 90;    // 1.0-1.1 ms
        histogram[500] = 10;   // 50.0-50.1 ms
        final WriteSoakRunner.Result result = new WriteSoakRunner.Result(List.of(), List.of(),
                new SoakAnalysis.Snapshot(java.util.Map.of(), java.util.Map.of(), 0, 0), 100, 0, 0, 0, List.of(),
                List.of(), histogram, new int[] {0, 3});
        assertThat(result.gatedLatencyPercentileMicros(50)).isEqualTo(1_100);
        assertThat(result.gatedLatencyPercentileMicros(95)).isEqualTo(50_100);
        assertThat(result.revision(1)).isEqualTo(4);
    }

    private static WriteSoakRunner.Settings settings(final int workers, final Duration gated,
            final Duration plateau, final Duration vacuumPoll) {
        return new WriteSoakRunner.Settings(10_000, 42, workers, 220.0, Duration.ofMinutes(5), gated, plateau,
                Duration.ofSeconds(30), vacuumPoll);
    }

}
