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
package org.eclipse.ditto.internal.utils.persistence.bench;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The PostgreSQL append shapes the W3 churn can drive. {@link #TXN} is the canonical W3 protocol (the
 * plugin's write shape transcribed to pgjdbc); the others are profiling variants:
 * <ul>
 *     <li>{@link #NO_HWM}: {@code TXN} without the {@code journal_seq} upsert. What-if only: without
 *     the upsert the high-water-mark contract does not hold.</li>
 *     <li>{@link #ONE_STATEMENT}: the event INSERT and the {@code journal_seq} upsert in one
 *     data-modifying CTE, autocommit, i.e. one round trip. What-if only.</li>
 *     <li>{@link #R2DBC}: the production {@code PostgresPersistenceOperations#insertEvents} on a pool
 *     built by the production {@code ConnectionPoolFactory} (r2dbc-postgresql, unnamed statements).</li>
 *     <li>{@link #R2DBC_PHASED}: the steps of that production transaction on the same kind of pool,
 *     issued one by one so that each step can be timed.</li>
 * </ul>
 */
public enum PgAppendShape {

    TXN("txn", List.of("insert", "upsert", "commit")),
    NO_HWM("no-hwm", List.of("insert", "commit")),
    ONE_STATEMENT("one-statement", List.of("statement")),
    R2DBC("r2dbc", List.of()),
    R2DBC_PHASED("r2dbc-phased", List.of("acquire", "begin", "insert", "upsert", "commit", "release"));

    private final String label;
    private final List<String> phases;

    PgAppendShape(final String label, final List<String> phases) {
        this.label = label;
        this.phases = phases;
    }

    public String label() {
        return label;
    }

    /** The separately timed steps of one append, in execution order; empty when only the total is timed. */
    public List<String> phases() {
        return phases;
    }

    public boolean usesR2dbc() {
        return this == R2DBC || this == R2DBC_PHASED;
    }

    public static PgAppendShape parse(final String label) {
        final String normalized = label.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(shape -> shape.label.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown W3 PostgreSQL append shape '" + label
                        + "', expected one of " + Arrays.stream(values()).map(PgAppendShape::label)
                        .collect(Collectors.joining(", "))));
    }
}
