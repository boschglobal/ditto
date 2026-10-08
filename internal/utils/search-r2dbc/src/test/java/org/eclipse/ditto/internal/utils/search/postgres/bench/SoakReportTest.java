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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Unit test of the write-soak report rows and verdict lines. */
public final class SoakReportTest {

    @Test
    public void aWindowRowShowsTheMeasuredRateAndThePerUpdateFlatChurn() {
        final SoakAnalysis.RelationSample flat = new SoakAnalysis.RelationSample(SoakAnalysis.SEARCH_FLAT,
                780_000_000L, 3_200_000L, 780_000_000L, 0, 0, 0, 0, 2, "2026-10-08 10:00:00+00", 100L << 30,
                150L << 30, 250L << 30);
        final SoakAnalysis.Window window = new SoakAnalysis.Window(7, 510.0, 30.0, true, 5_960, 0, 4_200, 18_900,
                251_000, 52_428_800, 3_100, Map.of(SoakAnalysis.SEARCH_FLAT, flat),
                Map.of(SoakAnalysis.SEARCH_FLAT, new SoakAnalysis.RelationDelta(74_500, 0, 0, 74_500, 0)), Map.of(),
                Map.of(), List.of("search_flat pid 4711: vacuuming indexes, index passes 1"));

        final String row = SoakReport.windowRow(window);

        assertThat(row).startsWith("| 7 | 510 | gated | 5960 | 198.7 | 0 | 4.2 | 18.9 | 251.0 | 50.0 | 3100 "
                + "| 12.5 | 12.5 | 3,200,000 | 250.00 | - |");
        assertThat(row).contains("| 2/-/- |").endsWith("| search_flat pid 4711: vacuuming indexes, index passes 1 |\n");
    }

    @Test
    public void theSummaryStatesBothVerdictsWithTheirReasons() {
        final WriteSoakRunner.Settings settings = new WriteSoakRunner.Settings(10_000, 42, 24, 220.0,
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(30),
                Duration.ofSeconds(2));
        final WriteSoakRunner.Result result = new WriteSoakRunner.Result(List.of(), List.of(),
                new SoakAnalysis.Snapshot(Map.of(), Map.of(), 0, 0), 12_000, 0, 2, 1, List.of(), List.of(),
                new long[WriteSoakRunner.HISTOGRAM_BUCKETS], new int[10_000]);
        final SoakAnalysis.GateVerdict gate = new SoakAnalysis.GateVerdict(false, 60.0, 198.7, 0, List.of(2), 1,
                30.0, "1 of 2 gated windows stayed below 200.0 updates/s");
        final SoakAnalysis.PlateauVerdict plateau = new SoakAnalysis.PlateauVerdict(SoakAnalysis.SEARCH_FLAT, false,
                true, 9_000_000, 7_800_050, 0, 1L << 40, 1L << 40, "no autovacuum completed in the window");

        final String summary = SoakReport.summary(settings, 200.0, result, gate, List.of(plateau));

        assertThat(summary).contains("- after the last window: 2 failed update(s) that were still in flight, "
                        + "1 writer(s) still running")
                .contains("## Throughput gate: NOT MET")
                .contains("- 1 of 2 gated windows stayed below 200.0 updates/s")
                .contains("- failing windows: 2")
                .contains("| search_flat | no | 9,000,000 | 7,800,050 | 0 | yes |")
                .contains("NO PLATEAU: no autovacuum completed in the window");
    }

}
