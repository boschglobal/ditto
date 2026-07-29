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

import java.util.List;

import org.eclipse.ditto.internal.utils.search.postgres.bench.BenchQuerySupport.TextArray;

/**
 * The per-field revoke-wins/grant-inherits auth recheck SQL fragment specified by the Task 0.2 brief, evaluated
 * on candidate {@code search_things} rows (no index — a CPU-bound filter over already-narrowed candidates).
 * <p>
 * For a checked path {@code /attributes/<a>/<b>} the brief specifies six JSONB-path lookups against
 * {@code st.policy_auth}:
 * <pre>
 * $g1='{&lt;a&gt;,&lt;b&gt;,·r}', $g2='{&lt;a&gt;,&lt;b&gt;,·g}', $g3='{&lt;a&gt;,·r}', $g4='{&lt;a&gt;,·g}',
 * $g5='{·r}', $g6='{·g}'
 * </pre>
 * This bench's corpus generator ({@code CorpusGenerator.buildAuthTree}) only ever nests one level below root
 * (a root {@code ·g}/optional {@code ·r}, and an optional {@code attributes} subtree carrying only its own
 * {@code ·r}) — so concretizing {@code <a>="attributes"}, {@code <b>="location"} (matching shape 1/2/3/5's
 * checked path {@code /attributes/location/city}) makes {@code g3}/{@code g4} meaningfully exercise the
 * generator's one real nesting level (subtree revoke), while {@code g1}/{@code g2} are structurally always-false
 * in this corpus (no key nests two levels deep). That is a corpus-shallowness artifact, not a bug in this SQL —
 * recorded as a finding in the Task 0.2 report rather than silently masked.
 */
final class AuthRecheck {

    static final String[] G1 = {"attributes", "location", "·r"};
    static final String[] G2 = {"attributes", "location", "·g"};
    static final String[] G3 = {"attributes", "·r"};
    static final String[] G4 = {"attributes", "·g"};
    static final String[] G5 = {"·r"};
    static final String[] G6 = {"·g"};

    /**
     * The auth recheck SQL fragment, with 12 {@code ?} placeholders (g1,s, g2,s, g3,s, g4,s, g5,s, g6,s in order).
     * The {@code ?|} operator's {@code ?} is doubled ({@code ??|}) per pgjdbc's convention for escaping literal
     * {@code ?} characters inside JSONB operators — otherwise the driver's placeholder parser miscounts bind
     * parameters (it cannot tell {@code ?|}'s {@code ?} apart from a real {@code ?} placeholder).
     */
    static final String SQL =
            "NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)\n"
                    + "AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)\n"
                    + "      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)\n"
                    + "           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)\n"
                    + "                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)\n"
                    + "                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) )";

    private AuthRecheck() {
        throw new AssertionError("no instances");
    }

    /** @return the 12 bind values for {@link #SQL}, pairing each {@code g}-path with the same {@code subjects}. */
    static List<Object> params(final String[] subjects) {
        final TextArray s = new TextArray(subjects);
        return BenchQuerySupport.listOf(
                new TextArray(G1), s,
                new TextArray(G2), s,
                new TextArray(G3), s,
                new TextArray(G4), s,
                new TextArray(G5), s,
                new TextArray(G6), s);
    }

}
