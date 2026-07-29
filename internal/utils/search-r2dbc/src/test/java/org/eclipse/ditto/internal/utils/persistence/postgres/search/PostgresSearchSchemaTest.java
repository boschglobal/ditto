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
package org.eclipse.ditto.internal.utils.persistence.postgres.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.PostgresSchemaManager;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.TableContract;
import org.junit.Test;

/**
 * Unit tests for the {@link PostgresSearchSchema} descriptor: the DDL statement set, table contracts and checksum
 * machinery, verified WITHOUT a live PostgreSQL (the bootstrap-against-a-real-DB behavior is covered by
 * {@code PostgresSearchSchemaManagerIT}).
 */
public final class PostgresSearchSchemaTest {

    @Test
    public void descriptorIdentity() {
        assertThat(PostgresSearchSchema.descriptor().component()).isEqualTo("ditto-postgres-search");
        assertThat(PostgresSearchSchema.descriptor().version()).isEqualTo(1);
        assertThat(PostgresSearchSchema.COMPONENT).isEqualTo("ditto-postgres-search");
    }

    @Test
    public void firstStatementCreatesTheTrgmExtension() {
        assertThat(PostgresSearchSchema.ddlStatements().get(0))
                .isEqualTo("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Test
    public void everyCreateStatementIsIdempotent() {
        for (final String ddl : PostgresSearchSchema.ddlStatements()) {
            if (ddl.regionMatches(true, 0, "CREATE TABLE", 0, "CREATE TABLE".length())) {
                assertThat(ddl).containsIgnoringCase("CREATE TABLE IF NOT EXISTS");
            } else if (ddl.regionMatches(true, 0, "CREATE INDEX", 0, "CREATE INDEX".length())) {
                assertThat(ddl).containsIgnoringCase("CREATE INDEX IF NOT EXISTS");
            }
        }
    }

    @Test
    public void ddlDeclaresTheThreeTablesAndTheirIndexes() {
        final List<String> ddl = PostgresSearchSchema.ddlStatements();
        assertThat(ddl).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS search_things"));
        assertThat(ddl).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS search_flat"));
        assertThat(ddl).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS search_sync"));
        assertThat(ddl).anyMatch(s -> s.contains("st_namespace"));
        assertThat(ddl).anyMatch(s -> s.contains("st_global_read") && s.contains("USING gin"));
        assertThat(ddl).anyMatch(s -> s.contains("st_referenced_pols") && s.contains("jsonb_path_ops"));
        assertThat(ddl).anyMatch(s -> s.contains("sf_num") && s.contains("WHERE val_num IS NOT NULL"));
        assertThat(ddl).anyMatch(s -> s.contains("sf_exists"));
    }

    @Test
    public void trgmIndexUsesTheCUtf8CollationOverrideAndTrgmOps() {
        assertThat(PostgresSearchSchema.ddlStatements())
                .anyMatch(s -> s.contains("sf_trgm")
                        && s.contains("(val_text COLLATE \"C.utf8\")")
                        && s.contains("gin_trgm_ops")
                        && s.contains("WHERE val_text IS NOT NULL"));
    }

    @Test
    public void scopedPerPathTrigramIndexIsNotPartOfV1Ddl() {
        // The E4 per-path scoped-trigram index is an open plan question (§3.7) and is deliberately NOT in v1.
        assertThat(PostgresSearchSchema.ddlStatements())
                .noneMatch(s -> s.contains("val_text gin_trgm_ops) WHERE wpath ="))
                .noneMatch(s -> s.toLowerCase().contains("sf_trgm_scoped"));
    }

    @Test
    public void ddlDoesNotOwnTheSharedSchemaVersionTable() {
        // schema_version is owned/prepended by PostgresSchemaManager, never by the descriptor.
        assertThat(PostgresSearchSchema.ddlStatements())
                .noneMatch(s -> s.contains("schema_version"));
    }

    @Test
    public void effectiveDdlPrependsTheSharedSchemaVersionTable() {
        final List<String> effective = PostgresSchemaManager.effectiveDdl(PostgresSearchSchema.descriptor());
        assertThat(effective.get(0)).isEqualTo(PostgresSchemaManager.SCHEMA_VERSION_DDL);
        assertThat(effective).hasSize(PostgresSearchSchema.ddlStatements().size() + 1);
    }

    @Test
    public void tableContractsCoverTheThreeTablesWithTheirPrimaryKeys() {
        final List<TableContract> contracts = PostgresSearchSchema.tableContracts();
        assertThat(contracts).extracting(TableContract::tableName)
                .containsExactly("search_things", "search_flat", "search_sync");

        final TableContract flat = contracts.get(1);
        assertThat(flat.tableName()).isEqualTo("search_flat");
        assertThat(flat.primaryKeyDef()).isEqualTo("PRIMARY KEY (thing_id, path, wpath, ord)");
        assertThat(flat.collatedColumns()).containsExactlyInAnyOrder("thing_id", "path", "wpath", "val_text");

        final TableContract things = contracts.get(0);
        assertThat(things.primaryKeyDef()).isEqualTo("PRIMARY KEY (thing_id)");
        assertThat(things.collatedColumns()).containsExactlyInAnyOrder("thing_id", "namespace");
    }

    @Test
    public void checksumIsDeterministic() {
        assertThat(PostgresSchemaManager.checksum(PostgresSearchSchema.descriptor()))
                .isEqualTo(PostgresSchemaManager.checksum(PostgresSearchSchema.descriptor()))
                .hasSize(64);
    }
}
