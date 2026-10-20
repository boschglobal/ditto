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

public final class CleanupShapeBenchTest {

    @Test
    public void thePayloadFreeWhatIfIsTheProductionPageWithoutItsTwoPayloadColumns() {
        assertThat(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE
                .replace("s.snapshot, ", "")
                .replace("snapshot::text AS snapshot, ", ""))
                .isEqualTo(CleanupShapeBench.PG_PAGE_WITHOUT_PAYLOAD_NOT_PRODUCTION);
        assertThat(CleanupShapeBench.PG_PAGE_WITHOUT_PAYLOAD_NOT_PRODUCTION)
                .doesNotContain("snapshot")
                .contains("SELECT p.pid, s.sn, s.lifecycle, s.written_at FROM (")
                .contains("SELECT sn, lifecycle, written_at FROM things_snaps ");
    }

    @Test
    public void payloadShareIsTheRelativeSavingAtP50AndAtP95() {
        // nearest rank over three samples: p50 3.0 vs 1.2 -> 60 %; p95 4.0 vs 2.0 -> 50 %
        final BenchProtocol.Timing withPayload = BenchProtocol.of(List.of(3.0, 3.0, 4.0));
        final BenchProtocol.Timing withoutPayload = BenchProtocol.of(List.of(1.2, 1.2, 2.0));
        assertThat(CleanupShapeBench.payloadShare(withPayload, withoutPayload)).isEqualTo("60 % / 50 %");
    }

    @Test
    public void eachLabelledWhatIfRunWritesItsOwnEvidenceFile() {
        assertThat(CleanupShapeBench.whatIfEvidenceName("3d", "run1"))
                .isEqualTo("c1-payload-free-what-if-retention3d-pg-run1.md");
        assertThat(CleanupShapeBench.whatIfEvidenceName("3d", "run3"))
                .isEqualTo("c1-payload-free-what-if-retention3d-pg-run3.md");
        assertThat(CleanupShapeBench.whatIfEvidenceName("3d", ""))
                .isEqualTo("c1-payload-free-what-if-retention3d-pg.md");
    }

    @Test
    public void theServerIsQuietOnlyOnceBothCorpusTablesWereVacuumedAndNoVacuumRuns() {
        assertThat(CleanupShapeBench.isQuiet("none", "none")).isTrue();
        assertThat(CleanupShapeBench.isQuiet("none", "things_journal (vacuuming heap)")).isFalse();
        assertThat(CleanupShapeBench.isQuiet("things_journal", "none")).isFalse();
        assertThat(CleanupShapeBench.isQuiet("things_journal, things_snaps", "none")).isFalse();
    }
}
