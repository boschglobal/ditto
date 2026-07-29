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
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free compact-JSON serializer for the {@code Map<String, Object>}/{@code List<Object>}/
 * scalar tree produced by {@link CorpusGenerator} — used to write the {@code thing}/{@code policy_auth}/
 * {@code features_auth}/{@code referenced_policies} jsonb column values for {@link CopyTextFormat}. Kept
 * self-contained on purpose: this is bench-only tooling and has no reason to take on the Ditto JSON model or a
 * third-party JSON library as a dependency.
 */
final class JsonWriter {

    private JsonWriter() {
        throw new AssertionError("no instances");
    }

    static String write(final Object value) {
        final StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(final Object value, final StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, sb);
        } else if (value instanceof List<?> list) {
            writeArray(list, sb);
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            sb.append(BigDecimal.valueOf(((Number) value).doubleValue()).toPlainString());
        } else if (value instanceof Number n) {
            sb.append(n);
        } else {
            throw new IllegalArgumentException("Unsupported JSON node type: " + value.getClass());
        }
    }

    private static void writeObject(final Map<?, ?> map, final StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString((String) entry.getKey(), sb);
            sb.append(':');
            writeValue(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeArray(final List<?> list, final StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (final Object element : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(element, sb);
        }
        sb.append(']');
    }

    private static void writeString(final String s, final StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

}
