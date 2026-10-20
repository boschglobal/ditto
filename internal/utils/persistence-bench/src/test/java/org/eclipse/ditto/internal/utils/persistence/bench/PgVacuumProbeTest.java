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
import java.util.Map;

import org.junit.Test;

public final class PgVacuumProbeTest {

    @Test
    public void progressIsNoneWithoutAVacuumAndOneClausePerTableOtherwise() {
        assertThat(PgVacuumProbe.formatProgress(List.of())).isEqualTo("none");
        assertThat(PgVacuumProbe.formatProgress(List.of(
                new PgVacuumProbe.Progress("things_journal", "vacuuming indexes", 1200L, 4_700_000L, 1L),
                new PgVacuumProbe.Progress("things_snaps", "scanning heap", 10L, 40L, 0L))))
                .isEqualTo("things_journal vacuuming indexes (heap 1200/4700000, index passes 1); "
                        + "things_snaps scanning heap (heap 10/40, index passes 0)");
    }

    @Test
    public void waitsAreCountedMostFrequentFirst() {
        assertThat(PgVacuumProbe.formatWaits(Map.of())).isEqualTo("no autovacuum worker");
        assertThat(PgVacuumProbe.formatWaits(Map.of("DataFileRead", 3, "VacuumDelay", 11, "CPU", 3)))
                .isEqualTo("VacuumDelay 11, CPU 3, DataFileRead 3");
    }
}
