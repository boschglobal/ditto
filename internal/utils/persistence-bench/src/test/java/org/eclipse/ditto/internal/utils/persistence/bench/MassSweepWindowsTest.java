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

import java.util.List;

import org.junit.Test;

public final class MassSweepWindowsTest {

    private static ChurnEngine.Sample window(final int index, final long ops, final double p50, final double p95) {
        return new ChurnEngine.Sample(index, ops, p50, p95, p95, 0L, 0L, 0L, 0L);
    }

    private static final List<ChurnEngine.Sample> FOUR = List.of(window(0, 15_000, 1.2, 2.1),
            window(1, 14_970, 1.8, 3.4), window(2, 15_010, 1.1, 2.0), window(3, 15_000, 1.0, 1.9));

    @Test
    public void windowsThatEndBeforeTheSweepEndsAreDuringAndAStraddlingWindowIsAfter() {
        final MassSweepWindows.Split split = MassSweepWindows.split(FOUR, 30, 75.0);
        assertThat(split.during()).extracting(ChurnEngine.Sample::windowIndex).containsExactly(0, 1);
        assertThat(split.after()).extracting(ChurnEngine.Sample::windowIndex).containsExactly(2, 3);
    }

    @Test
    public void aWindowEndingExactlyWithTheSweepIsDuring() {
        assertThat(MassSweepWindows.split(FOUR, 30, 60.0).during()).hasSize(2);
    }

    @Test
    public void aSweepThatOutlastsTheChurnCoversEveryWindow() {
        final MassSweepWindows.Split split = MassSweepWindows.split(FOUR, 30, 1_000.0);
        assertThat(split.during()).hasSize(4);
        assertThat(split.after()).isEmpty();
    }

    @Test
    public void rateIsOpsPerCoveredSecondAndDescribeSummarisesTheWindows() {
        assertThat(MassSweepWindows.rate(FOUR.subList(0, 2), 30)).isEqualTo(29_970 / 60.0);
        assertThat(MassSweepWindows.rate(List.of(), 30)).isZero();
        assertThat(MassSweepWindows.describe(FOUR.subList(0, 2), 30))
                .isEqualTo("windows 0–1 (2 × 30 s): achieved 499.5/s; window p50 1.20–1.80 ms, p95 2.10–3.40 ms");
        assertThat(MassSweepWindows.describe(List.of(), 30)).isEqualTo("no complete window");
    }

    @Test
    public void theMassSweepNeedsNinetyPercentOfTheGeneratedSnapshots() {
        assertThat(MassSweepWindows.backlogPresent(44_271L, 44_271L)).isTrue();
        assertThat(MassSweepWindows.backlogPresent(39_844L, 44_271L)).isTrue();
        assertThat(MassSweepWindows.backlogPresent(39_843L, 44_271L)).isFalse();
        assertThat(MassSweepWindows.backlogPresent(8_581L, 44_271L)).isFalse();   // after a full sweep
    }
}
