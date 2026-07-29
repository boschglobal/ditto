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
package org.eclipse.ditto.internal.utils.persistence.postgres.client.schema;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Pure, I/O-free refuse-to-boot decision logic for the schema bootstrap. Kept separate from {@code PostgresSchemaManager}
 * so the two guards can be unit-tested without a live PostgreSQL:
 * <ol>
 *     <li><strong>checksum guard</strong> — the stored {@code schema_version.checksum} (if any) must equal the code's
 *     current checksum; a mismatch is a code-vs-stored downgrade or drift.</li>
 *     <li><strong>live-catalog guard</strong> — each table's actual primary-key definition, column types, and the
 *     {@code COLLATE "C"} collation of its persistence-id columns (read from the catalog) must match the canonical
 *     contract, because {@code CREATE TABLE IF NOT EXISTS} silently keeps a divergent pre-existing table that the
 *     checksum alone cannot catch on first boot. Collation is checked separately from {@code data_type} because
 *     {@code information_schema.columns.data_type} reports {@code text} regardless of collation.</li>
 * </ol>
 * Every method throws {@link SchemaBootException} with a precise diff on divergence.
 */
public final class SchemaVerifier {

    /**
     * The byte-ordered collation every persistence-id column must declare so that {@code col > $1 ORDER BY col} cursor
     * pagination and primary-key ordering are stable across locales. PostgreSQL reports it as
     * {@code information_schema.columns.collation_name = 'C'}. Shared by every Postgres-backed component.
     */
    static final String PID_COLLATION = "C";

    private SchemaVerifier() {
        throw new AssertionError();
    }

    /**
     * Version-aware checksum verification.
     * <ul>
     *   <li>No stored row: first boot — pass.</li>
     *   <li>Stored version equals the current code version: checksums must match exactly (drift guard).</li>
     *   <li>Stored version is OLDER: pass — an additive schema upgrade is in flight (the additive-only DDL
     *       policy means the CREATE-IF-NOT-EXISTS bootstrap has already applied the delta); the caller then
     *       advances the row to the current version+checksum. During a rolling upgrade, not-yet-upgraded
     *       nodes keep booting because their older version compares as OLDER only on the upgraded row —
     *       see the runbook note below for the mixed-version window.</li>
     *   <li>Stored version is NEWER: refuse — a downgrade against an already-advanced schema.</li>
     * </ul>
     * NOTE on the mixed-version window: once one upgraded node advances the row, an OLD-version node that
     * restarts sees storedVersion &gt; its currentVersion and refuses to boot — this is intentional
     * (old code must not run against a newer schema); the runbook documents completing the roll promptly.
     *
     * @param component the logical component identifier (for the diagnostic message).
     * @param expectedChecksum the current code checksum (computed over the effective DDL for the component).
     * @param currentVersion the current code schema version for the component.
     * @param storedChecksum the checksum read from {@code schema_version}, or {@code null} on first boot (no row yet).
     * @param storedVersion the version read from {@code schema_version}, or {@code null} on first boot (no row yet).
     * @throws SchemaBootException on a same-version checksum mismatch (drift) or a stored version newer than the
     * current code version (downgrade).
     */
    public static void verifyChecksum(final String component, final String expectedChecksum, final int currentVersion,
            @Nullable final String storedChecksum, @Nullable final Integer storedVersion) {
        if (storedChecksum == null || storedVersion == null) {
            return;
        }
        if (storedVersion > currentVersion) {
            throw new SchemaBootException("Refusing to boot: stored schema version " + storedVersion
                    + " is NEWER than this code's version " + currentVersion + " for component '"
                    + component + "' — schema downgrade detected. Upgrade this service "
                    + "or restore the matching database.");
        }
        if (storedVersion == currentVersion && !storedChecksum.equals(expectedChecksum)) {
            throw new SchemaBootException(
                    "Refusing to boot: schema_version checksum mismatch for component '" + component
                            + "' at version " + currentVersion + ". Stored=" + storedChecksum
                            + " expected=" + expectedChecksum
                            + ". This indicates schema drift (manual DDL edits or a code/DB mismatch at the "
                            + "same schema version). See deployment/postgres/README.md#schema-upgrades.");
        }
        // storedVersion < currentVersion: additive upgrade in flight — allowed.
    }

    /**
     * Verifies the live catalog of a single table against its canonical contract: primary-key definition and column
     * data types. Collation is <strong>not</strong> checked by this overload — use
     * {@link #verifyTable(TableContract, String, Map, Map)} to additionally catch collation drift on the
     * {@code COLLATE "C"} persistence-id columns.
     *
     * @param contract the expected PK definition + column types.
     * @param actualPrimaryKeyDef the {@code pg_get_constraintdef} of the table's PK, or {@code null} if absent.
     * @param actualColumns the actual {@code column name → data_type} map read from {@code information_schema.columns}.
     * @throws SchemaBootException on any PK or column divergence.
     */
    public static void verifyTable(final TableContract contract,
            @Nullable final String actualPrimaryKeyDef,
            final Map<String, String> actualColumns) {
        verifyPrimaryKeyAndColumns(contract, actualPrimaryKeyDef, actualColumns);
    }

    /**
     * Verifies the live catalog of a single table against its canonical contract: primary-key definition, column data
     * types, and the byte-ordered collation of the persistence-id columns.
     * <p>
     * The collation check exists because {@code information_schema.columns.data_type} reports {@code text} regardless
     * of collation, so a pre-existing table whose {@code pid} column silently lacks {@code COLLATE "C"} would pass the
     * data-type check yet break {@code pid > $1 ORDER BY pid} cursor pagination and primary-key ordering. A column with
     * no explicit collation reports {@code collation_name = NULL} (it inherits the database default), so for any column
     * the contract marks as collated, a {@code null}/absent collation is itself a drift failure.
     * </p>
     *
     * @param contract the expected PK definition, column types, and collated columns.
     * @param actualPrimaryKeyDef the {@code pg_get_constraintdef} of the table's PK, or {@code null} if absent.
     * @param actualColumns the actual {@code column name → data_type} map read from {@code information_schema.columns}.
     * @param actualCollations the actual {@code column name → collation_name} map read from
     * {@code information_schema.columns} (a {@code null} value / absent entry means the column has no explicit
     * collation and uses the database default).
     * @throws SchemaBootException on any PK, column, or collation divergence.
     */
    public static void verifyTable(final TableContract contract,
            @Nullable final String actualPrimaryKeyDef,
            final Map<String, String> actualColumns,
            final Map<String, String> actualCollations) {

        verifyPrimaryKeyAndColumns(contract, actualPrimaryKeyDef, actualColumns);

        for (final String collatedColumn : contract.collatedColumns()) {
            final String actualCollation = actualCollations.get(collatedColumn);
            if (actualCollation == null || !actualCollation.equals(PID_COLLATION)) {
                throw new SchemaBootException(
                        "Refusing to boot: table '" + contract.tableName() + "' column '" + collatedColumn
                                + "' has collation_name '" + actualCollation + "' but expected '"
                                + PID_COLLATION + "'. A pre-existing table whose persistence-id column "
                                + "lacks COLLATE \"" + PID_COLLATION + "\" breaks pid cursor pagination "
                                + "and primary-key ordering; a NULL collation means the column uses the database "
                                + "default rather than the required byte-ordered collation.");
            }
        }
    }

    /**
     * Verifies all canonical table contracts (PK + data types only; no collation check).
     *
     * @param contracts the canonical contracts ({ PostgresSchemaDescriptor#tableContracts()}).
     * @param actualPrimaryKeyDefs table name → actual PK definition (null entry / missing key = absent).
     * @param actualColumnsByTable table name → (column name → data_type).
     * @throws SchemaBootException on the first divergence.
     */
    public static void verifyAllTables(final List<TableContract> contracts,
            final Map<String, String> actualPrimaryKeyDefs,
            final Map<String, Map<String, String>> actualColumnsByTable) {
        for (final TableContract contract : contracts) {
            verifyTable(contract, actualPrimaryKeyDefs.get(contract.tableName()),
                    actualColumnsByTable.getOrDefault(contract.tableName(), Map.of()));
        }
    }

    /**
     * Verifies all canonical table contracts including the persistence-id collation.
     *
     * @param contracts the canonical contracts ({ PostgresSchemaDescriptor#tableContracts()}).
     * @param actualPrimaryKeyDefs table name → actual PK definition (null entry / missing key = absent).
     * @param actualColumnsByTable table name → (column name → data_type).
     * @param actualCollationsByTable table name → (column name → collation_name); a missing table or column means no
     * explicit collation.
     * @throws SchemaBootException on the first divergence.
     */
    public static void verifyAllTables(final List<TableContract> contracts,
            final Map<String, String> actualPrimaryKeyDefs,
            final Map<String, Map<String, String>> actualColumnsByTable,
            final Map<String, Map<String, String>> actualCollationsByTable) {
        for (final TableContract contract : contracts) {
            verifyTable(contract, actualPrimaryKeyDefs.get(contract.tableName()),
                    actualColumnsByTable.getOrDefault(contract.tableName(), Map.of()),
                    actualCollationsByTable.getOrDefault(contract.tableName(), Map.of()));
        }
    }

    private static void verifyPrimaryKeyAndColumns(final TableContract contract,
            @Nullable final String actualPrimaryKeyDef,
            final Map<String, String> actualColumns) {

        if (actualPrimaryKeyDef == null || !normalizePk(actualPrimaryKeyDef).equals(normalizePk(contract.primaryKeyDef()))) {
            throw new SchemaBootException(
                    "Refusing to boot: table '" + contract.tableName() + "' has a divergent primary key. Expected '"
                            + contract.primaryKeyDef() + "' but catalog reports '" + actualPrimaryKeyDef
                            + "'. CREATE TABLE IF NOT EXISTS silently kept a pre-existing divergent table.");
        }

        for (final Map.Entry<String, String> expected : contract.columns().entrySet()) {
            final String actualType = actualColumns.get(expected.getKey());
            if (actualType == null) {
                throw new SchemaBootException(
                        "Refusing to boot: table '" + contract.tableName() + "' is missing expected column '"
                                + expected.getKey() + "'.");
            }
            if (!actualType.equalsIgnoreCase(expected.getValue())) {
                throw new SchemaBootException(
                        "Refusing to boot: table '" + contract.tableName() + "' column '" + expected.getKey()
                                + "' has data_type '" + actualType + "' but expected '" + expected.getValue()
                                + "'.");
            }
        }
    }

    private static String normalizePk(final String pk) {
        // pg_get_constraintdef returns e.g. "PRIMARY KEY (pid, sn)"; normalise whitespace + case for comparison.
        return pk.replaceAll("\\s+", " ").trim().toUpperCase();
    }

}
