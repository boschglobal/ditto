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

import java.math.BigDecimal;

import javax.annotation.Nullable;

/**
 * One synthetic {@code search_flat} row produced by {@link Flattener}, mirroring the bench schema in
 * {@code bench/search-schema.sql}. Exactly one of {@link #valBool()}/{@link #valNum()}/{@link #valText()} is
 * non-null for a scalar row ({@code typeRank} 2/3/6); all three are {@code null} for object/array/null rows
 * ({@code typeRank} 1/4/5).
 *
 * @param thingId the owning thing's ID.
 * @param path the JSON-pointer path over the thing payload (never rewritten for feature wildcarding).
 * @param wpath the queryable path — equal to {@code path} outside a feature subtree, or the
 * {@code /features/*}-wildcarded variant of {@code path} inside one.
 * @param fId the feature ID if this row is inside a feature subtree, else {@code null}.
 * @param ord the row-enumeration counter for this {@code (path, wpath)} pair within the thing (not an array
 * index — see {@link Flattener}).
 * @param typeRank 1 null, 2 number, 3 string, 4 object, 5 array, 6 boolean.
 * @param valBool the boolean value if {@code typeRank == 6}, else {@code null}.
 * @param valNum the numeric value if {@code typeRank == 2}, else {@code null}.
 * @param valText the string value if {@code typeRank == 3}, else {@code null}.
 */
record FlatRow(
        String thingId,
        String path,
        String wpath,
        @Nullable String fId,
        int ord,
        short typeRank,
        @Nullable Boolean valBool,
        @Nullable BigDecimal valNum,
        @Nullable String valText) {
}
