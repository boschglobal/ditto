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

import java.util.List;
import java.util.stream.LongStream;

/**
 * 1:1 transcription of the delete-plan math in
 * internal/utils/persistent-actors/.../cleanup/Cleanup.java (lines 102-141):
 * batches tile downward so the highest upperBound is (snapshotRevisionSn - 1), preserving the
 * event/snapshot at the snapshot revision; the lowest range may extend below minSn (no rows there).
 */
public final class CleanupPlanner {

    private CleanupPlanner() {
    }

    public record Range(long fromSn, long toSn) {}

    public static List<Range> eventDeleteRanges(final long minSn, final long snapshotSn,
            final int deleteBatchSize) {
        if (minSn >= snapshotSn) {
            return List.of();
        }
        return toRanges(upperBounds(minSn, snapshotSn, deleteBatchSize), deleteBatchSize);
    }

    public static List<Range> snapshotDeleteRanges(final long minSn, final long snapshotSn,
            final boolean isDeleted, final boolean deleteFinalDeletedSnapshot, final int deleteBatchSize) {
        if (minSn >= snapshotSn && !deleteFinalDeletedSnapshot) {
            return List.of();
        }
        final long maxSnToDelete = deleteFinalDeletedSnapshot && isDeleted ? snapshotSn + 1 : snapshotSn;
        if (minSn >= maxSnToDelete) {
            return List.of();
        }
        return toRanges(upperBounds(minSn, maxSnToDelete, deleteBatchSize), deleteBatchSize);
    }

    // Cleanup.getSnUpperBoundsPerBatch, verbatim math
    private static List<Long> upperBounds(final long minSn, final long snapshotRevisionSn,
            final int deleteBatchSize) {
        final long difference = snapshotRevisionSn - minSn;
        final long batches = (difference / deleteBatchSize) + (difference % deleteBatchSize == 0L ? 0L : 1L);
        final long firstBatchSn = snapshotRevisionSn - 1 - ((batches - 1) * deleteBatchSize);
        return LongStream.range(0, batches)
                .mapToObj(multiplier -> firstBatchSn + multiplier * deleteBatchSize)
                .toList();
    }

    private static List<Range> toRanges(final List<Long> upperBounds, final int deleteBatchSize) {
        return upperBounds.stream()
                .map(ub -> new Range(ub - deleteBatchSize + 1, ub))
                .toList();
    }
}
