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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.Test;

public final class CleanupShapeBenchTest {

    @Test
    public void theProductionPageIsTheStatementBeforeTheChangeWithoutItsTwoPayloadColumns() {
        assertThat(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD
                .replace("s.snapshot, ", "")
                .replace("snapshot::text AS snapshot, ", ""))
                .isEqualTo(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE);
        assertThat(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)
                .doesNotContain("snapshot")
                .contains("SELECT p.pid, s.sn, s.lifecycle, s.written_at FROM (")
                .contains("SELECT sn, lifecycle, written_at FROM things_snaps ");
        assertThat(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE_WITH_PAYLOAD)
                .contains("SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM (")
                .contains("SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps ");
    }

    @Test
    public void payloadShareIsTheRelativeSavingAtP50AndAtP95() {
        // nearest rank over three samples: p50 3.0 vs 1.2 -> 60 %; p95 4.0 vs 2.0 -> 50 %
        final BenchProtocol.Timing withPayload = BenchProtocol.of(List.of(3.0, 3.0, 4.0));
        final BenchProtocol.Timing withoutPayload = BenchProtocol.of(List.of(1.2, 1.2, 2.0));
        assertThat(CleanupShapeBench.payloadShare(withPayload, withoutPayload)).isEqualTo("60 % / 50 %");
    }

    @Test
    public void eachLabelledPayloadPairRunWritesItsOwnEvidenceFileAndTheCacheStateComesBeforeTheLabel() {
        assertThat(CleanupShapeBench.payloadPairEvidenceName("3d", CleanupShapeBench.CacheState.ASIS, "run1"))
                .isEqualTo("c1-payload-pair-retention3d-pg-run1.md");
        assertThat(CleanupShapeBench.payloadPairEvidenceName("3d", CleanupShapeBench.CacheState.ASIS, ""))
                .isEqualTo("c1-payload-pair-retention3d-pg.md");
        assertThat(CleanupShapeBench.payloadPairEvidenceName("3d", CleanupShapeBench.CacheState.COLD,
                "limits-p1-run1")).isEqualTo("c1-payload-pair-retention3d-pg-cold-limits-p1-run1.md");
        assertThat(CleanupShapeBench.payloadPairEvidenceName("3d", CleanupShapeBench.CacheState.WARM, ""))
                .isEqualTo("c1-payload-pair-retention3d-pg-warm.md");
    }

    @Test
    public void theCacheStateKnobTakesThreeWordsOnly() {
        assertThat(CleanupShapeBench.CacheState.parse("asis")).isEqualTo(CleanupShapeBench.CacheState.ASIS);
        assertThat(CleanupShapeBench.CacheState.parse("cold")).isEqualTo(CleanupShapeBench.CacheState.COLD);
        assertThat(CleanupShapeBench.CacheState.parse("warm")).isEqualTo(CleanupShapeBench.CacheState.WARM);
        assertThatThrownBy(() -> CleanupShapeBench.CacheState.parse("hot"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("asis, cold or warm");
    }

    @Test
    public void aColdRunMustReadBlocksAndAWarmRunAlmostNone() {
        final CleanupShapeBench.PgIo noReads = new CleanupShapeBench.PgIo(0, 40_000, 0, 5_000, 0, 20_000, 0.0);
        final CleanupShapeBench.PgIo fewReads = new CleanupShapeBench.PgIo(3, 40_000, 1, 5_000, 2, 20_000, 0.4);
        final CleanupShapeBench.PgIo manyReads =
                new CleanupShapeBench.PgIo(900, 40_000, 50, 5_000, 2_000, 20_000, 310.5);
        assertThat(CleanupShapeBench.cacheStateCheck(CleanupShapeBench.CacheState.COLD, noReads))
                .hasValueSatisfying(f -> assertThat(f).contains("cold state not established"));
        assertThat(CleanupShapeBench.cacheStateCheck(CleanupShapeBench.CacheState.COLD, manyReads)).isEmpty();
        assertThat(CleanupShapeBench.cacheStateCheck(CleanupShapeBench.CacheState.WARM, fewReads)).isEmpty();
        assertThat(CleanupShapeBench.cacheStateCheck(CleanupShapeBench.CacheState.WARM, manyReads))
                .hasValueSatisfying(f -> assertThat(f).contains("warm state not established").contains("2950"));
        assertThat(CleanupShapeBench.cacheStateCheck(CleanupShapeBench.CacheState.ASIS, manyReads)).isEmpty();
    }

    @Test
    public void theColdStateTimesTheMinAgeZeroPairFirstAndTheOtherStatesKeepTheOrderOfTheFirstRuns() {
        final Duration retention = Duration.ofDays(3);
        assertThat(CleanupShapeBench.pairOrder(CleanupShapeBench.CacheState.COLD, retention))
                .containsExactly(Duration.ZERO, retention);
        assertThat(CleanupShapeBench.pairOrder(CleanupShapeBench.CacheState.ASIS, retention))
                .containsExactly(retention, Duration.ZERO);
        assertThat(CleanupShapeBench.pairOrder(CleanupShapeBench.CacheState.WARM, retention))
                .containsExactly(retention, Duration.ZERO);
    }

    @Test
    public void ioDeltasSubtractEveryCounterAndSumTheReadsAndHits() {
        final CleanupShapeBench.PgIo before = new CleanupShapeBench.PgIo(10, 100, 1, 50, 5, 200, 1.5);
        final CleanupShapeBench.PgIo after = new CleanupShapeBench.PgIo(19, 1_100, 5, 350, 453, 2_200, 25.5);
        final CleanupShapeBench.PgIo during = after.minus(before);
        assertThat(during).isEqualTo(new CleanupShapeBench.PgIo(9, 1_000, 4, 300, 448, 2_000, 24.0));
        assertThat(during.reads()).isEqualTo(461);
        assertThat(during.hits()).isEqualTo(3_300);
        assertThat(during.line()).isEqualTo(
                "heap 9 read / 1000 hit, index 4 / 300, toast 448 / 2000; 461 reads in all, read time 24.0 ms");
    }

    @Test
    public void theServerIsQuietOnlyOnceBothCorpusTablesWereVacuumedAndNoVacuumRuns() {
        assertThat(CleanupShapeBench.isQuiet("none", "none")).isTrue();
        assertThat(CleanupShapeBench.isQuiet("none", "things_journal (vacuuming heap)")).isFalse();
        assertThat(CleanupShapeBench.isQuiet("things_journal", "none")).isFalse();
        assertThat(CleanupShapeBench.isQuiet("things_journal, things_snaps", "none")).isFalse();
    }
}
