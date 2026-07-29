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

/** Verifies the 1:1 transcription of Cleanup.getSnUpperBoundsPerBatch (worked example from CleanupTest). */
public final class CleanupPlannerTest {

    @Test
    public void eventRangesTileDownAndKeepTheSnapshotRevisionEvent() {
        final List<CleanupPlanner.Range> ranges = CleanupPlanner.eventDeleteRanges(30L, 50L, 4);
        assertThat(ranges).containsExactly(
                new CleanupPlanner.Range(30L, 33L),
                new CleanupPlanner.Range(34L, 37L),
                new CleanupPlanner.Range(38L, 41L),
                new CleanupPlanner.Range(42L, 45L),
                new CleanupPlanner.Range(46L, 49L));
    }

    @Test
    public void noEventRangesWhenMinAtOrAboveSnapshotRevision() {
        assertThat(CleanupPlanner.eventDeleteRanges(50L, 50L, 4)).isEmpty();
        assertThat(CleanupPlanner.eventDeleteRanges(51L, 50L, 4)).isEmpty();
    }

    @Test
    public void snapshotRangesKeepLatestByDefault() {
        final List<CleanupPlanner.Range> ranges =
                CleanupPlanner.snapshotDeleteRanges(40L, 50L, true, false, 4);
        assertThat(ranges).containsExactly(
                new CleanupPlanner.Range(38L, 41L),
                new CleanupPlanner.Range(42L, 45L),
                new CleanupPlanner.Range(46L, 49L));
    }

    @Test
    public void snapshotRangesDeleteFinalDeletedSnapshotWhenConfigured() {
        final List<CleanupPlanner.Range> ranges =
                CleanupPlanner.snapshotDeleteRanges(40L, 50L, true, true, 4);
        assertThat(ranges).containsExactly(
                new CleanupPlanner.Range(39L, 42L),
                new CleanupPlanner.Range(43L, 46L),
                new CleanupPlanner.Range(47L, 50L));
    }

    @Test
    public void snapshotGuardMatchesCleanupSemantics() {
        // minSn == sr.sn and not deleting the final snapshot -> nothing
        assertThat(CleanupPlanner.snapshotDeleteRanges(50L, 50L, false, false, 4)).isEmpty();
        // but with deleteFinalDeletedSnapshot on a deleted entity the final one goes
        assertThat(CleanupPlanner.snapshotDeleteRanges(50L, 50L, true, true, 4))
                .containsExactly(new CleanupPlanner.Range(47L, 50L));
    }

    @Test
    public void lowestRangeMayExtendBelowMinSn() {
        // difference 20, batch 100 -> one batch, firstBatchSn = 21-1-0 = 20, range [20-100+1, 20].
        // The real Cleanup math lets the lowest range extend below minSn (harmless — no rows there);
        // the transcription must preserve that.
        assertThat(CleanupPlanner.eventDeleteRanges(1L, 21L, 100))
                .containsExactly(new CleanupPlanner.Range(-79L, 20L));
    }
}
