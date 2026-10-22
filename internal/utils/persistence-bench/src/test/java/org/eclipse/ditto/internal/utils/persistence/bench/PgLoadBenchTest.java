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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.ditto.internal.utils.persistence.postgres.schema.PostgresSchema;
import org.junit.Test;

public final class PgLoadBenchTest {

    @Test
    public void afterTheCopyComeThePrimaryKeysThenTheSchemasThingsStatementsThenAnalyze() {
        final List<String> fromSchema = PostgresSchema.ddlStatements().stream()
                .filter(s -> s.startsWith("CREATE INDEX IF NOT EXISTS things_") || s.startsWith("ALTER TABLE things_"))
                .toList();
        final List<String> expected = new ArrayList<>(List.of(
                "ALTER TABLE things_journal ADD PRIMARY KEY (pid, sn)",
                "ALTER TABLE things_journal_seq ADD PRIMARY KEY (pid)",
                "ALTER TABLE things_snaps ADD PRIMARY KEY (pid, sn, written_at)"));
        expected.addAll(fromSchema);
        expected.addAll(List.of("ANALYZE things_journal", "ANALYZE things_journal_seq", "ANALYZE things_snaps"));

        assertThat(PgLoadBench.postLoadStatements("")).containsExactlyElementsOf(expected)
                .noneMatch(s -> s.contains("lifecycle"))
                .contains("CREATE INDEX IF NOT EXISTS things_journal_tags_idx ON things_journal USING GIN (tags)");
    }

    @Test
    public void anOverrideReplacesTheJournalAndSnapsStorageParametersOnly() {
        final String override = "autovacuum_vacuum_scale_factor = 0.01";
        assertThat(PgLoadBench.postLoadStatements(override))
                .contains("ALTER TABLE things_journal SET (autovacuum_vacuum_scale_factor = 0.01)",
                        "ALTER TABLE things_snaps SET (autovacuum_vacuum_scale_factor = 0.01)",
                        "ALTER TABLE things_journal_seq SET (autovacuum_vacuum_scale_factor = 0.01)")
                .noneMatch(s -> s.contains("cost_limit"));
    }

    @Test
    public void aJournalRowCarriesTheGivenTagsLiteralInItsFourthColumn() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 10L);
        final CorpusGenerator.EventRow e = gen.events(3).next();
        assertThat(PgLoadBench.appendJournalRow(new StringBuilder(), e, "{}").toString().split("\t")[3])
                .isEqualTo("{}");
        assertThat(PgLoadBench.appendJournalRow(new StringBuilder(), e, "{always-alive,priority-4}").toString()
                .split("\t")).hasSize(6).satisfies(cells -> {
                    assertThat(cells[0]).isEqualTo(e.pid());
                    assertThat(cells[3]).isEqualTo("{always-alive,priority-4}");
                    assertThat(cells[5]).isEqualTo(Instant.ofEpochMilli(e.timestampMillis()) + "\n");
                });
    }

    @Test
    public void aTaggedLoadWritesItsOwnLoadEvidence() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 1_000L);
        assertThat(PgLoadBench.evidenceBase(null)).isEqualTo("load-pg");
        assertThat(BenchProtocol.corpusEvidenceName(PgLoadBench.evidenceBase(PingTagMix.of(gen)), gen, "250k"))
                .isEqualTo("load-pg-partly-tagged-250k.md");
    }
}
