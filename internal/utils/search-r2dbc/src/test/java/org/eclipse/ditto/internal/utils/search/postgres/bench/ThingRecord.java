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
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * One synthetic {@code search_things} row, mirroring the bench schema in {@code bench/search-schema.sql}.
 * {@link #thing()} is the flattening input for {@link Flattener}; every other field maps 1:1 to a column.
 *
 * @param thingId the primary key.
 * @param namespace the namespace part of the thing ID (duplicated as its own column for the {@code st_namespace}
 * index).
 * @param revision the thing revision.
 * @param policyId the associated policy ID, may be {@code null}.
 * @param policyRev the associated policy revision, may be {@code null}.
 * @param referencedPolicies policy IDs imported/referenced by the thing's policy, may be {@code null} (stored as a
 * JSON array).
 * @param globalRead subjects with unconditional read access (maps to the {@code global_read text[]} column).
 * @param thing the thing payload tree (object/array/String/Number/Boolean/null nodes) — the only field
 * {@link Flattener} ever looks at.
 * @param policyAuth the policy grant/revoke tree, may be {@code null}.
 * @param featuresAuth the per-feature grant/revoke tree, may be {@code null}.
 * @param tModified the last-modified timestamp, may be {@code null}.
 * @param deleteAt a scheduled deletion timestamp, may be {@code null}.
 */
record ThingRecord(
        String thingId,
        String namespace,
        long revision,
        @Nullable String policyId,
        @Nullable Long policyRev,
        @Nullable List<String> referencedPolicies,
        List<String> globalRead,
        Map<String, Object> thing,
        @Nullable Map<String, Object> policyAuth,
        @Nullable Map<String, Object> featuresAuth,
        @Nullable Instant tModified,
        @Nullable Instant deleteAt) {
}
