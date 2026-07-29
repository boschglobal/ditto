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

/**
 * The backend-agnostic description of one component's PostgreSQL schema, consumed by the generic
 * {@link PostgresSchemaManager}. Each Postgres-backed component (the event-sourcing persistence backend, the search
 * backend, …) supplies its OWN descriptor; the manager owns everything that is shared across all of them — the
 * {@code schema_version} bookkeeping table ({@link PostgresSchemaManager#SCHEMA_VERSION_DDL}) and the cluster-wide
 * first-boot advisory lock — and applies them for every component.
 * <p>
 * A descriptor therefore declares ONLY its component-specific parts:
 * </p>
 * <ul>
 *     <li>{@link #component()} — the logical identifier stored in {@code schema_version.component} (the checksum guard
 *     is scoped per component).</li>
 *     <li>{@link #version()} — bumped on any breaking DDL change, per the schema-evolution policy.</li>
 *     <li>{@link #ddlStatements()} — the ordered, idempotent ({@code IF NOT EXISTS}) DDL for this component's own
 *     tables/indexes, <strong>excluding</strong> the shared {@code schema_version} table (the manager prepends that
 *     once). Order is stable so the checksum is deterministic.</li>
 *     <li>{@link #tableContracts()} — the live-catalog verification contracts for this component's tables.</li>
 * </ul>
 * <p>
 * The manager computes the {@code schema_version} checksum over the shared {@code schema_version} DDL followed by this
 * descriptor's {@link #ddlStatements()}, i.e. over the exact statement set the bootstrap executes — so the checksum can
 * never drift away from what is actually run.
 * </p>
 */
public interface PostgresSchemaDescriptor {

    /**
     * @return the logical component identifier stored in {@code schema_version.component}.
     */
    String component();

    /**
     * @return the current code schema version for this component.
     */
    int version();

    /**
     * @return the ordered list of this component's own DDL statements (CREATE TABLE / CREATE INDEX / ALTER TABLE …),
     * each {@code IF NOT EXISTS}, <strong>excluding</strong> the shared {@code schema_version} table (owned by the
     * manager). Order is stable so the checksum is deterministic.
     */
    List<String> ddlStatements();

    /**
     * @return the live-catalog verification contracts for this component's tables (never the shared
     * {@code schema_version} table).
     */
    List<TableContract> tableContracts();

}
