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
package org.eclipse.ditto.internal.utils.persistence.postgres.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests pinning the canonical DDL shapes to their structural invariants: every statement is idempotent
 * ({@code IF NOT EXISTS}), the journal sequence column uses an IDENTITY generator, every persistence id column (on the
 * journal, the high-water-mark table, and the snapshot table) uses {@code COLLATE "C"} for byte-ordered comparisons,
 * the tags column is backed by a GIN index, the snapshot table
 * carries its full primary key, the per-entity high-water-mark table exists, and autovacuum is tuned per table.
 */
public final class PostgresSchemaTest {

    private static final List<String> DDL = PostgresSchema.ddlStatements();

    @Test
    public void everyStatementIsIdempotent() {
        // Every CREATE carries IF NOT EXISTS; ALTERs are inherently idempotent (SET).
        assertThat(DDL).allSatisfy(stmt -> {
            if (stmt.startsWith("CREATE")) {
                assertThat(stmt).contains("IF NOT EXISTS");
            }
        });
    }

    @Test
    public void coversAllFourEntities() {
        assertThat(PostgresSchema.ENTITIES).containsExactly("things", "policies", "connections", "wot");
        for (final String e : PostgresSchema.ENTITIES) {
            assertThat(DDL).anyMatch(s -> s.contains(e + "_journal ("));
            assertThat(DDL).anyMatch(s -> s.contains(e + "_journal_seq ("));
            assertThat(DDL).anyMatch(s -> s.contains(e + "_snaps ("));
        }
    }

    @Test
    public void journalHasIdentityOffsetCollateCAndGin() {
        final String journal = stmtContaining("things_journal (");
        assertThat(journal).contains("seq BIGINT GENERATED ALWAYS AS IDENTITY");   // database-generated sequence
        assertThat(journal).contains("pid TEXT NOT NULL COLLATE \"C\"");           // byte-ordered comparisons
        assertThat(journal).contains("tags TEXT[] NOT NULL DEFAULT '{}'");
        assertThat(journal).contains("event JSONB NOT NULL");
        assertThat(journal).contains("PRIMARY KEY (pid, sn)");
        assertThat(stmtContaining("things_journal_tags_idx")).contains("USING GIN (tags)");  // tag membership lookups
    }

    @Test
    public void highWaterMarkTableHasHighestSnAndDeletedTo() {
        final String seq = stmtContaining("things_journal_seq (");
        assertThat(seq).contains("pid TEXT COLLATE \"C\" PRIMARY KEY");   // byte-ordered primary key
        assertThat(seq).contains("highest_sn BIGINT NOT NULL");
        assertThat(seq).contains("deleted_to BIGINT NOT NULL DEFAULT 0");
    }

    @Test
    public void everyPidColumnUsesCollateC() {
        // pid is COLLATE "C" on the journal, the high-water-mark table, AND the snaps table so that pid > $1
        // cursor pagination and primary-key ordering are byte-ordered and locale-independent across all tables.
        for (final String e : PostgresSchema.ENTITIES) {
            assertThat(stmtContaining(e + "_journal (")).contains("pid TEXT NOT NULL COLLATE \"C\"");
            assertThat(stmtContaining(e + "_journal_seq (")).contains("pid TEXT COLLATE \"C\" PRIMARY KEY");
            assertThat(stmtContaining(e + "_snaps (")).contains("pid TEXT NOT NULL COLLATE \"C\"");
        }
        // No DDL statement may declare a pid TEXT column without immediately attaching COLLATE "C".
        assertThat(DDL)
                .filteredOn(s -> s.contains("pid TEXT"))
                .allSatisfy(s -> assertThat(s).containsPattern("pid TEXT( NOT NULL)? COLLATE \"C\""));
    }

    @Test
    public void snapshotTableHasThreeColumnPkAndPartialDeletedIndex() {
        final String snaps = stmtContaining("things_snaps (");
        assertThat(snaps).contains("snapshot JSONB NOT NULL");
        assertThat(snaps).contains("PRIMARY KEY (pid, sn, written_at)");
        // v2: the (pid, sn DESC) index is GONE — it never satisfied the newest-per-pid sort (missing
        // written_at tiebreak) and the loose-index-scan queries left nothing needing it; the PK
        // (pid, sn, written_at) serves every snaps access path. v1 databases converge via DROP.
        assertThat(DDL).noneMatch(s -> s.startsWith("CREATE INDEX") && s.contains("_snaps_pid_sn_desc_idx"));
        assertThat(DDL).anyMatch(s -> s.equals("DROP INDEX IF EXISTS things_snaps_pid_sn_desc_idx"));
        assertThat(stmtContaining("things_snaps_lifecycle_idx")).contains("WHERE lifecycle = 'DELETED'");
    }

    @Test
    public void autovacuumTunedPerTable() {
        for (final String e : PostgresSchema.ENTITIES) {
            assertThat(DDL).anyMatch(s -> s.equals(
                    "ALTER TABLE " + e + "_journal SET (autovacuum_vacuum_scale_factor = 0.01)"));
            assertThat(DDL).anyMatch(s -> s.equals(
                    "ALTER TABLE " + e + "_snaps SET (autovacuum_vacuum_scale_factor = 0.01)"));
        }
    }

    @Test
    public void schemaVersionTablePresent() {
        assertThat(stmtContaining("schema_version (")).contains("checksum TEXT NOT NULL");
    }

    @Test
    public void doesNotUseSessionAdvisoryLockOrIndisvalidRebuild() {
        assertThat(DDL).noneMatch(s -> s.contains("CONCURRENTLY"));
        assertThat(DDL).noneMatch(s -> s.contains("pg_advisory_lock("));   // the bootstrap uses pg_advisory_XACT_lock
    }

    private static String stmtContaining(final String needle) {
        return DDL.stream().filter(s -> s.contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("No DDL statement contains: " + needle));
    }

}
