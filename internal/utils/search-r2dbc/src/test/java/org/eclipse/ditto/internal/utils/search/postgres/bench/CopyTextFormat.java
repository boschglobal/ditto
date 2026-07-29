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

import javax.annotation.Nullable;

/**
 * Renders {@link ThingRecord}/{@link FlatRow} instances as {@code COPY ... FROM STDIN} TEXT-format lines
 * (tab-separated fields, {@code \N} for SQL NULL, trailing {@code \n}). Two escaping layers are stacked
 * correctly: type-specific literal syntax is built first (JSON text via {@link JsonWriter}, the
 * {@code {"a","b"}} array literal for {@code text[]}), then the *whole* field text is run through the
 * COPY TEXT-format backslash-escaper — necessary because, e.g., the double quotes and backslashes that JSON
 * string-escaping introduces are themselves literal characters that COPY's own escaping must double.
 */
final class CopyTextFormat {

    private static final String NULL = "\\N";

    private CopyTextFormat() {
        throw new AssertionError("no instances");
    }

    static String thingsLine(final ThingRecord t) {
        return String.join("\t",
                text(t.thingId()),
                text(t.namespace()),
                Long.toString(t.revision()),
                textOrNull(t.policyId()),
                t.policyRev() == null ? NULL : Long.toString(t.policyRev()),
                jsonOrNull(t.referencedPolicies()),
                textArray(t.globalRead()),
                json(t.thing()),
                jsonOrNull(t.policyAuth()),
                jsonOrNull(t.featuresAuth()),
                instantOrNull(t.tModified()),
                instantOrNull(t.deleteAt())
        ) + "\n";
    }

    static String flatLine(final FlatRow r) {
        return String.join("\t",
                text(r.thingId()),
                text(r.path()),
                text(r.wpath()),
                textOrNull(r.fId()),
                Integer.toString(r.ord()),
                Short.toString(r.typeRank()),
                r.valBool() == null ? NULL : (r.valBool() ? "t" : "f"),
                r.valNum() == null ? NULL : r.valNum().toPlainString(),
                textOrNull(r.valText())
        ) + "\n";
    }

    private static String text(final String s) {
        return escape(s);
    }

    private static String textOrNull(@Nullable final String s) {
        return s == null ? NULL : escape(s);
    }

    private static String json(final Object tree) {
        return escape(JsonWriter.write(tree));
    }

    private static String jsonOrNull(@Nullable final Object tree) {
        return tree == null ? NULL : escape(JsonWriter.write(tree));
    }

    private static String instantOrNull(@Nullable final Instant instant) {
        return instant == null ? NULL : escape(instant.toString());
    }

    private static String textArray(final List<String> values) {
        if (values.isEmpty()) {
            return escape("{}");
        }
        final StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append('}');
        return escape(sb.toString());
    }

    /**
     * Escapes one field's literal text for the COPY TEXT format: backslash, tab, newline and carriage-return
     * are backslash-escaped. (Every producer above builds NULL fields as the literal {@link #NULL} constant
     * directly, bypassing this method, so a real {@code \N} two-character sequence never has to be
     * distinguished from an escaped one.)
     */
    private static String escape(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

}
