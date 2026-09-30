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

/**
 * What the schema bootstrap (or self-heal) may do after comparing the stored {@code schema_version} row with this
 * code's {@link PostgresSchemaDescriptor}. Decided by {@link SchemaVerifier} <em>before</em> any component DDL runs;
 * the refuse cases are not values but a {@link SchemaBootException}.
 */
public enum SchemaVersionDecision {

    /**
     * No stored row yet, an older stored version, or the same version: run this code's DDL. At boot the catalog is then
     * verified and the row is inserted or advanced to this code's version, checksum and {@code min_compatible_version}.
     */
    APPLY,

    /**
     * The stored version is newer than this code's, and its {@code min_compatible_version} is at most this code's
     * version (a rolling upgrade is in flight and the newer schema is declared backward compatible): run
     * <strong>no</strong> DDL and write <strong>no</strong> row. At boot only the catalog facts this code needs are
     * verified.
     */
    COMPATIBLE_NEWER

}
