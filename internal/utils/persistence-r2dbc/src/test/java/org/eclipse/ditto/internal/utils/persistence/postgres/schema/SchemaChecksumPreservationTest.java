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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.PostgresSchemaManager;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.schema.SchemaChecksum;
import org.junit.Test;

/**
 * Refactor-safety net for the {@code internal/utils/postgres-client} extraction (Task B1).
 * <p>
 * Before the extraction the {@code schema_version} table DDL was folded into {@code PostgresSchema.ddlStatements()} as
 * element 0, and the persisted {@code schema_version.checksum} for the {@code ditto-postgres-persistence} component was
 * a SHA-256 over that full list. The extraction hoisted the {@code schema_version} DDL out of {@code PostgresSchema}
 * and into the shared, manager-owned {@link PostgresSchemaManager#SCHEMA_VERSION_DDL}; the manager prepends it to the
 * descriptor's own statements ({@link PostgresSchemaManager#effectiveDdl}) so the effective statement set — and hence
 * the checksum — is byte-for-byte identical to the pre-extraction one.
 * </p>
 * <p>
 * This test PINS the exact effective-DDL checksum literal: an already-bootstrapped production database persisted this
 * value, and an unexpected mismatch would refuse-to-boot every running deployment. If this test fails, the effective
 * DDL has drifted — that MUST be a deliberate, versioned schema change, never an accidental side effect of code motion.
 * </p>
 * <p>
 * The extraction itself was checksum-neutral, which is what
 * {@link #effectiveDdlPrependsTheSharedSchemaVersionTableToTheComponentStatements()} still proves structurally
 * (version-independently). The pinned literal has since moved twice, deliberately: v2 (snaps {@code (pid, sn DESC)}
 * index dropped), v3 (lifecycle partial index dropped, vacuum cost budget raised); each replaced an index creation
 * with an idempotent {@code DROP INDEX IF EXISTS}, changing the DDL text. That is exactly the "deliberate, versioned
 * schema change" the policy above allows — each is carried by a {@link PostgresSchema#VERSION} bump (1 -&gt; 2,
 * 2 -&gt; 3), and {@code SchemaVerifier.verifyStoredVersion} treats a stored version OLDER than the code version as an
 * upgrade in flight (pass, then advance the row), so deployed databases upgrade rather than refuse to boot. The later
 * {@code min_compatible_version} column is added by a manager-owned statement outside the effective DDL, so it left
 * this literal unchanged.
 * </p>
 */
public final class SchemaChecksumPreservationTest {

    /**
     * The {@code ditto-postgres-persistence} component checksum for the CURRENT schema version, computed over the
     * effective DDL list whose element 0 is the {@code schema_version} table. Frozen here as the compatibility
     * contract — only a deliberate {@link PostgresSchema#VERSION} bump may change it.
     * <p>
     * History: {@code 117464679ca1d6aa9517a42d54c77de4782ba9b8c7870a88425bcd9b4d0991b4} was the v1 value, preserved
     * unchanged across the postgres-client extraction; v2 (snaps {@code (pid, sn DESC)} index dropped) stored
     * {@link PostgresSchemaV2#CHECKSUM}; v3 (lifecycle partial index dropped, journal and snaps vacuum cost budget
     * raised) moved it to the value below.
     */
    private static final String CURRENT_PERSISTENCE_CHECKSUM =
            "1c74caf668bfd35ca03813be60fb40f7a12f53aebe89d850247ca1cf260fffea";

    private static final Pattern LIFECYCLE_INDEX =
            Pattern.compile("^CREATE INDEX IF NOT EXISTS (\\w+_snaps_lifecycle_idx) ON .*$");
    private static final Pattern PRUNED_TABLE_ALTER =
            Pattern.compile("^ALTER TABLE (\\w+_(?:journal|snaps)) SET \\(autovacuum_vacuum_scale_factor = 0\\.01\\)$");

    @Test
    public void persistenceChecksumMatchesThePinnedValueForTheCurrentSchemaVersion() {
        assertThat(PostgresSchema.VERSION).as("bump the pin together with the version").isEqualTo(3);
        assertThat(PostgresSchemaManager.checksum(PostgresSchema.descriptor()))
                .as("effective DDL must not drift without a deliberate PostgresSchema.VERSION bump")
                .isEqualTo(CURRENT_PERSISTENCE_CHECKSUM);
    }

    @Test
    public void frozenV2DescriptorReproducesTheChecksumV2DatabasesStore() {
        assertThat(PostgresSchemaManager.checksum(PostgresSchemaV2.descriptor())).isEqualTo(PostgresSchemaV2.CHECKSUM);
    }

    @Test
    public void v3IsV2WithTheLifecycleIndexDroppedAndTheJournalAndSnapsVacuumBudgetRaised() {
        final List<String> v2 = PostgresSchemaV2.DDL_STATEMENTS;
        final List<String> v3 = PostgresSchema.ddlStatements();
        assertThat(v3).hasSameSizeAs(v2);
        for (int i = 0; i < v2.size(); i++) {
            assertThat(v3.get(i)).as("statement " + i).isEqualTo(expectedV3(v2.get(i)));
        }
        assertThat(PostgresSchema.tableContracts()).isEqualTo(PostgresSchemaV2.TABLE_CONTRACTS);
    }

    private static String expectedV3(final String v2Statement) {
        final Matcher lifecycle = LIFECYCLE_INDEX.matcher(v2Statement);
        if (lifecycle.matches()) {
            return "DROP INDEX IF EXISTS " + lifecycle.group(1);
        }
        final Matcher pruned = PRUNED_TABLE_ALTER.matcher(v2Statement);
        if (pruned.matches()) {
            return "ALTER TABLE " + pruned.group(1)
                    + " SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_limit = 2000)";
        }
        return v2Statement;
    }

    @Test
    public void effectiveDdlPrependsTheSharedSchemaVersionTableToTheComponentStatements() {
        final List<String> effective = PostgresSchemaManager.effectiveDdl(PostgresSchema.descriptor());

        // First statement is the shared, manager-owned schema_version table.
        assertThat(effective.get(0)).isEqualTo(PostgresSchemaManager.SCHEMA_VERSION_DDL);

        // The remainder is exactly the component's own statements — i.e. the effective list reconstitutes the historical
        // "[schema_version, ...componentDdl]" ordering the checksum was originally computed over.
        final List<String> reconstructed = new ArrayList<>();
        reconstructed.add(PostgresSchemaManager.SCHEMA_VERSION_DDL);
        reconstructed.addAll(PostgresSchema.ddlStatements());
        assertThat(effective).isEqualTo(reconstructed);

        // And the pinned checksum equals a direct SHA-256 over that reconstructed list.
        assertThat(SchemaChecksum.compute(reconstructed)).isEqualTo(CURRENT_PERSISTENCE_CHECKSUM);
    }
}
