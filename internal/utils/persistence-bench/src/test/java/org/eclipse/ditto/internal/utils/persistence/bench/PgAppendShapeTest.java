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

import java.util.List;

import org.junit.Test;

public final class PgAppendShapeTest {

    @Test
    public void shapesParseByLabel() {
        assertThat(PgAppendShape.parse("txn")).isEqualTo(PgAppendShape.TXN);
        assertThat(PgAppendShape.parse(" R2DBC-phased ")).isEqualTo(PgAppendShape.R2DBC_PHASED);
        for (final PgAppendShape shape : PgAppendShape.values()) {
            assertThat(PgAppendShape.parse(shape.label())).isEqualTo(shape);
        }
        assertThatThrownBy(() -> PgAppendShape.parse("jdbc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("txn");
    }

    @Test
    public void theCanonicalShapeTimesThePluginTransactionSteps() {
        assertThat(PgAppendShape.TXN.phases()).containsExactly("insert", "upsert", "commit");
        assertThat(PgAppendShape.TXN.usesR2dbc()).isFalse();
        assertThat(PgAppendShape.R2DBC_PHASED.phases())
                .containsExactly("acquire", "begin", "insert", "upsert", "commit", "release");
        assertThat(PgAppendShape.R2DBC.usesR2dbc()).isTrue();
    }

    @Test
    public void theOneStatementWhatIfWritesTheEventAndTheHighWaterMark() {
        assertThat(PgAppenders.PG_INSERT_AND_UPSERT_ONE_STATEMENT)
                .contains(PersistenceShapes.PG_INSERT_EVENT + " RETURNING pid, sn")
                .contains("INSERT INTO things_journal_seq (pid, highest_sn) SELECT pid, sn FROM ins")
                .contains("GREATEST(things_journal_seq.highest_sn, excluded.highest_sn)");
        assertThat(PgAppenders.PG_INSERT_AND_UPSERT_ONE_STATEMENT.chars().filter(c -> c == '?').count())
                .isEqualTo(5);
    }

    @Test
    public void stepTimingsAreAggregatedPerStep() {
        final ChurnEngine.PhaseRecorder recorder = new ChurnEngine.PhaseRecorder();
        recorder.record(new long[]{1_000_000L, 2_000_000L, 3_000_000L});
        recorder.record(new long[]{1_000_000L, 4_000_000L, 5_000_000L});

        assertThat(recorder.swap()).hasSize(2);
        assertThat(recorder.swap()).isEmpty();
        final String md = ChurnEngine.phaseMarkdown(PgAppendShape.TXN, recorder.all());

        assertThat(md)
                .contains("| insert | 2 | 1.000 | 1.000 | 1.000 |")
                .contains("| upsert | 2 | 2.000 | 4.000 | 3.000 |")
                .contains("| commit | 2 | 3.000 | 5.000 | 4.000 |");
        assertThat(ChurnEngine.phaseMarkdown(PgAppendShape.R2DBC, List.of())).contains("total only");
    }

    @Test
    public void staggeredSchedulesAreSpreadEvenlyOverOnePeriod() {
        // 8 workers at 500/s: one op per worker every 16 ms
        final ChurnEngine.Schedule schedule = new ChurnEngine.Schedule(8, 500, 1, true);
        for (int w = 0; w < 8; w++) {
            schedule.start(w);
        }

        assertThat(schedule.nanosPerOp).isEqualTo(16_000_000.0);
        assertThat(schedule.markdown())
                .contains("staggered evenly")
                .contains("16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms");
    }
}
