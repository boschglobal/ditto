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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Flattens a thing payload tree (as produced by {@link CorpusGenerator}) into {@code search_flat} rows,
 * implementing the target design's exact semantics:
 * <ul>
 *     <li>{@code path} is the JSON-pointer path over the payload, e.g. {@code /attributes/temp}.</li>
 *     <li>Rows anywhere under {@code /features/<id>/...} carry {@code fId = <id>} and are emitted <b>twice</b>:
 *     once with {@code wpath = path}, once with {@code wpath} rewritten to {@code /features/*<rest>}. Rows
 *     outside a feature subtree carry {@code fId = null} and a single row with {@code wpath = path}.</li>
 *     <li>{@code ord} is a row-enumeration counter per {@code path} (equivalently per {@code (path, wpath)},
 *     since {@code wpath} is a deterministic function of {@code path}) — <b>not</b> an array index. It only
 *     advances past 0 when array explosion produces more than one row at the same path.</li>
 *     <li>Arrays are exploded recursively through object/array nesting without appending an index segment to
 *     the path (so an array of objects reuses the array's own path for every element, and repeat visits bump
 *     {@code ord}). The one exception (kept for Mongo parity): when an array element is itself an array, that
 *     inner array is <b>not</b> exploded — it contributes a single {@code typeRank = 5} (array) stub row with
 *     no value, instead of being walked into.</li>
 *     <li>Objects always contribute an exists row ({@code typeRank = 4}, no value) at their own path, whether
 *     or not they have children — so an empty object still gets exactly one row. Non-nested, non-empty arrays
 *     do <b>not</b> get a container row (their exploded elements carry the semantic content); an empty array
 *     gets a single {@code typeRank = 5} stub row so it isn't silently dropped.</li>
 * </ul>
 */
final class Flattener {

    static final short TYPE_NULL = 1;
    static final short TYPE_NUMBER = 2;
    static final short TYPE_STRING = 3;
    static final short TYPE_OBJECT = 4;
    static final short TYPE_ARRAY = 5;
    static final short TYPE_BOOLEAN = 6;

    private static final String FEATURES_ROOT = "/features";

    private Flattener() {
        throw new AssertionError("no instances");
    }

    /**
     * Flattens {@code thing}'s children (the root document itself does not get a row — only its top-level keys
     * and everything below them do).
     *
     * @param thingId the owning thing's ID, copied verbatim into every emitted row.
     * @param thing the thing payload, an object whose values are recursively String/Number/Boolean/null/
     * {@code Map<String, Object>}/{@code List<Object>}.
     * @return the flattened rows, in a stable but unspecified order.
     */
    static List<FlatRow> flatten(final String thingId, final Map<String, Object> thing) {
        final List<FlatRow> rows = new ArrayList<>();
        final Map<String, Integer> ordCounters = new HashMap<>();
        for (final Map.Entry<String, Object> entry : thing.entrySet()) {
            walk(rows, ordCounters, thingId, entry.getValue(), "/" + entry.getKey(), null);
        }
        return rows;
    }

    private static void walk(final List<FlatRow> rows, final Map<String, Integer> ordCounters, final String thingId,
            @Nullable final Object node, final String path, @Nullable final String featureId) {

        if (node == null) {
            emit(rows, ordCounters, thingId, path, featureId, TYPE_NULL, null, null, null);
        } else if (node instanceof Map<?, ?> mapNode) {
            emit(rows, ordCounters, thingId, path, featureId, TYPE_OBJECT, null, null, null);
            for (final Map.Entry<?, ?> entry : mapNode.entrySet()) {
                final String key = (String) entry.getKey();
                final String childPath = path + "/" + key;
                // Entering "/features"'s immediate children activates feature-subtree wildcarding for
                // everything below — the feature ID is the key itself.
                final String childFeatureId =
                        featureId == null && FEATURES_ROOT.equals(path) ? key : featureId;
                walk(rows, ordCounters, thingId, entry.getValue(), childPath, childFeatureId);
            }
        } else if (node instanceof List<?> listNode) {
            if (listNode.isEmpty()) {
                emit(rows, ordCounters, thingId, path, featureId, TYPE_ARRAY, null, null, null);
            } else {
                for (final Object element : listNode) {
                    if (element instanceof List<?>) {
                        // Direct array-of-array: the inner array is NOT exploded (Mongo parity) — one stub
                        // row per outer element, no descent into the inner array's own content.
                        emit(rows, ordCounters, thingId, path, featureId, TYPE_ARRAY, null, null, null);
                    } else {
                        // Same path for every element — no index segment — so repeat scalars/objects at this
                        // path bump `ord` rather than collide.
                        walk(rows, ordCounters, thingId, element, path, featureId);
                    }
                }
            }
        } else if (node instanceof String s) {
            emit(rows, ordCounters, thingId, path, featureId, TYPE_STRING, null, null, s);
        } else if (node instanceof Boolean b) {
            emit(rows, ordCounters, thingId, path, featureId, TYPE_BOOLEAN, b, null, null);
        } else if (node instanceof Number n) {
            emit(rows, ordCounters, thingId, path, featureId, TYPE_NUMBER, null, toBigDecimal(n), null);
        } else {
            throw new IllegalArgumentException("Unsupported payload node type: " + node.getClass());
        }
    }

    private static void emit(final List<FlatRow> rows, final Map<String, Integer> ordCounters, final String thingId,
            final String path, @Nullable final String featureId, final short typeRank,
            @Nullable final Boolean valBool, @Nullable final BigDecimal valNum, @Nullable final String valText) {

        final int ord = ordCounters.merge(path, 1, Integer::sum) - 1;
        if (featureId == null) {
            rows.add(new FlatRow(thingId, path, path, null, ord, typeRank, valBool, valNum, valText));
        } else {
            rows.add(new FlatRow(thingId, path, path, featureId, ord, typeRank, valBool, valNum, valText));
            rows.add(new FlatRow(thingId, path, starWpath(path, featureId), featureId, ord, typeRank, valBool,
                    valNum, valText));
        }
    }

    private static String starWpath(final String path, final String featureId) {
        final String prefix = FEATURES_ROOT + "/" + featureId;
        return FEATURES_ROOT + "/*" + path.substring(prefix.length());
    }

    private static BigDecimal toBigDecimal(final Number n) {
        if (n instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        } else if (n instanceof Double || n instanceof Float) {
            return BigDecimal.valueOf(n.doubleValue());
        } else {
            return BigDecimal.valueOf(n.longValue());
        }
    }

}
