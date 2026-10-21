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

import java.util.Comparator;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.Test;

public final class ScenarioPlansTest {

    private static final CorpusGenerator GEN = new CorpusGenerator(42L, 5_000L);

    @Test
    public void lastSnapshottedPidIsTheGreatestPidThatHasASnapshot() {
        final long idx = ScenarioPlans.lastSnapshottedPidIndex(GEN);
        assertThat(GEN.snapshotCount(idx)).isPositive();
        assertThat(GEN.pid(idx)).isEqualTo(snapshottedPids().max(Comparator.naturalOrder()).orElseThrow());
    }

    @Test
    public void firstSnapshottedPidIsTheSmallestPidThatHasASnapshot() {
        final long idx = ScenarioPlans.firstSnapshottedPidIndex(GEN);
        assertThat(GEN.snapshotCount(idx)).isPositive();
        assertThat(GEN.pid(idx)).isEqualTo(snapshottedPids().min(Comparator.naturalOrder()).orElseThrow());
    }

    @Test
    public void firstRangeIsTheFirstCleanupBatchOrAnEmptyRangeAtTheSnapshot() {
        assertThat(ScenarioPlans.firstRangeOrEmpty(CleanupPlanner.eventDeleteRanges(1L, 1_000L, 100), 1_000L))
                .isEqualTo(new CleanupPlanner.Range(0L, 99L));
        assertThat(ScenarioPlans.firstRangeOrEmpty(List.of(), 1_000L))
                .isEqualTo(new CleanupPlanner.Range(1_000L, 999L));
    }

    @Test
    public void mongoAppendSaysWhyThereIsNoPlan() {
        assertThat(ScenarioPlans.mongoAppend()).contains("not explainable").contains("insertOne");
    }

    private static Stream<String> snapshottedPids() {
        return LongStream.range(0, GEN.pidCount()).filter(i -> GEN.snapshotCount(i) > 0).mapToObj(GEN::pid);
    }
}
