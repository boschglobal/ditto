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

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;

/**
 * M1 during the mass sweep: which churn windows ran while the sweep pass was still deleting the loaded backlog, and
 * the churn rate and latencies inside and after it.
 */
final class MassSweepWindows {

    private MassSweepWindows() {
    }

    record Split(List<ChurnEngine.Sample> during, List<ChurnEngine.Sample> after) {}

    /**
     * Window i covers [i x interval, (i+1) x interval) on the churn's clock; it is "during" when it ended no later than
     * the sweep did. A window that straddles the sweep's end counts as "after".
     *
     * @param sweepEndSeconds when the sweep pass finished, in seconds on the churn's clock
     */
    static Split split(final List<ChurnEngine.Sample> samples, final int intervalSeconds,
            final double sweepEndSeconds) {
        final List<ChurnEngine.Sample> during = new ArrayList<>();
        final List<ChurnEngine.Sample> after = new ArrayList<>();
        for (final ChurnEngine.Sample s : samples) {
            final double windowEnd = (s.windowIndex() + 1) * (double) intervalSeconds;
            if (windowEnd <= sweepEndSeconds) {
                during.add(s);
            } else {
                after.add(s);
            }
        }
        return new Split(during, after);
    }

    /** ops / (windows x interval): exact when the churn duration is a multiple of the interval (600 s / 30 s). */
    static double rate(final List<ChurnEngine.Sample> windows, final int intervalSeconds) {
        if (windows.isEmpty()) {
            return 0.0;
        }
        final long ops = windows.stream().mapToLong(ChurnEngine.Sample::ops).sum();
        return ops / (windows.size() * (double) intervalSeconds);
    }

    static String describe(final List<ChurnEngine.Sample> windows, final int intervalSeconds) {
        if (windows.isEmpty()) {
            return "no complete window";
        }
        final DoubleSummaryStatistics p50 = windows.stream().mapToDouble(ChurnEngine.Sample::p50Ms).summaryStatistics();
        final DoubleSummaryStatistics p95 = windows.stream().mapToDouble(ChurnEngine.Sample::p95Ms).summaryStatistics();
        return String.format(Locale.ROOT,
                "windows %d–%d (%d × %d s): achieved %.1f/s; window p50 %.2f–%.2f ms, p95 %.2f–%.2f ms",
                windows.get(0).windowIndex(), windows.get(windows.size() - 1).windowIndex(), windows.size(),
                intervalSeconds, rate(windows, intervalSeconds), p50.getMin(), p50.getMax(), p95.getMin(),
                p95.getMax());
    }

    /** The mass sweep needs the loaded backlog: at least 90 % of the generated snapshot rows still present. */
    static boolean backlogPresent(final long snapshotRows, final long generatedSnapshotRows) {
        return snapshotRows * 10 >= generatedSnapshotRows * 9;
    }
}
