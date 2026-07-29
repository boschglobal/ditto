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
 * (version-independently). The pinned literal has since moved ONCE, deliberately: schema v2 replaced the redundant
 * {@code (pid, sn DESC)} snaps index creation with an idempotent {@code DROP INDEX IF EXISTS}, changing the DDL text.
 * That is exactly the "deliberate, versioned schema change" the policy above allows — it is carried by the
 * {@link PostgresSchema#VERSION} bump 1 -&gt; 2, and {@code SchemaVerifier.verifyChecksum} treats a stored version
 * OLDER than the code version as an upgrade in flight (pass, then advance the row), so deployed v1 databases upgrade
 * rather than refuse to boot.
 * </p>
 */
public final class SchemaChecksumPreservationTest {

    /**
     * The {@code ditto-postgres-persistence} component checksum for the CURRENT schema version, computed over the
     * effective DDL list whose element 0 is the {@code schema_version} table. Frozen here as the compatibility
     * contract — only a deliberate {@link PostgresSchema#VERSION} bump may change it.
     * <p>
     * History: {@code 117464679ca1d6aa9517a42d54c77de4782ba9b8c7870a88425bcd9b4d0991b4} was the v1 value, preserved
     * unchanged across the postgres-client extraction; schema v2 (snaps {@code (pid, sn DESC)} index dropped) moved it
     * to the value below.
     */
    private static final String CURRENT_PERSISTENCE_CHECKSUM =
            "7f72550a37b22382e580fc288c54d26ff81b3184a1eaea11cdd3775dc323a509";

    @Test
    public void persistenceChecksumMatchesThePinnedValueForTheCurrentSchemaVersion() {
        assertThat(PostgresSchemaManager.checksum(PostgresSchema.descriptor()))
                .as("effective DDL must not drift without a deliberate PostgresSchema.VERSION bump")
                .isEqualTo(CURRENT_PERSISTENCE_CHECKSUM);
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
