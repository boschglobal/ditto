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

import java.time.Instant;

import org.junit.Test;

public final class HostMemoryTest {

    @Test
    public void parsesTheThreeMacOsTools() {
        assertThat(HostMemory.parseSwapUsedMb("total = 3072.00M  used = 1943.19M  free = 1128.81M  (encrypted)"))
                .isEqualTo(1943.19);
        final String vmStat = "Pages free:                               12345.\n"
                + "Swapins:                                     4530503.\n"
                + "Swapouts:                                    6137538.\n";
        assertThat(HostMemory.parseCounter(vmStat, "Swapouts")).isEqualTo(6_137_538L);
        assertThat(HostMemory.parseCounter(vmStat, "Swapins")).isEqualTo(4_530_503L);
        assertThat(HostMemory.parseFreePercent("System-wide memory free percentage: 87%")).isEqualTo(87);
    }

    @Test
    public void aRunIsReliableIffAtMostOneThousandPagesWereSwappedOutAndBothSamplesShowHalfTheMemoryFree() {
        final HostMemory.Sample before = new HostMemory.Sample(Instant.EPOCH, 1943.19, 1L, 6_137_538L, 87, true);
        assertThat(HostMemory.verdict(before, before.withSwapouts(6_138_538L, 51))).isEqualTo("RELIABLE");
        assertThat(HostMemory.verdict(before, before.withSwapouts(6_138_539L, 87))).isEqualTo("UNRELIABLE");
        assertThat(HostMemory.verdict(before, before.withSwapouts(6_137_538L, 49))).isEqualTo("UNRELIABLE");
        assertThat(HostMemory.verdict(before, HostMemory.Sample.unavailable(Instant.EPOCH))).isEqualTo("NOT SAMPLED");
    }
}
