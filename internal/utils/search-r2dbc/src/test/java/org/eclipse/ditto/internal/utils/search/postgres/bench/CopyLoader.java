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

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

/**
 * Bulk-loads {@link ThingRecord}s into {@code search_things} and {@code search_flat} via JDBC
 * {@link CopyManager} ({@code COPY ... FROM STDIN}), streaming rows lazily so a 1M-thing (or larger) corpus is
 * never materialized in memory: each {@link Iterator}&lt;{@link ThingRecord}&gt; is pulled one element at a
 * time and, for {@code search_flat}, only the current thing's own flattened rows are buffered before moving
 * on to the next thing.
 */
final class CopyLoader {

    private static final String THINGS_COLUMNS =
            "(thing_id, namespace, revision, policy_id, policy_rev, referenced_policies, global_read, thing, "
                    + "policy_auth, features_auth, t_modified, delete_at)";
    private static final String FLAT_COLUMNS =
            "(thing_id, path, wpath, f_id, ord, type_rank, val_bool, val_num, val_text)";

    private CopyLoader() {
        throw new AssertionError("no instances");
    }

    /** @return the number of rows copied into {@code search_things}. */
    static long loadThings(final Connection connection, final Iterator<ThingRecord> things)
            throws SQLException, IOException {
        final Iterator<String> lines = mapping(things, CopyTextFormat::thingsLine);
        return copyIn(connection, "COPY search_things " + THINGS_COLUMNS + " FROM STDIN", lines);
    }

    /** @return the number of rows copied into {@code search_flat}. */
    static long loadFlat(final Connection connection, final Iterator<ThingRecord> things)
            throws SQLException, IOException {
        final Iterator<String> lines = new Iterator<>() {
            private Iterator<FlatRow> currentThingRows = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!currentThingRows.hasNext() && things.hasNext()) {
                    final ThingRecord thing = things.next();
                    currentThingRows = Flattener.flatten(thing.thingId(), thing.thing()).iterator();
                }
                return currentThingRows.hasNext();
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return CopyTextFormat.flatLine(currentThingRows.next());
            }
        };
        return copyIn(connection, "COPY search_flat " + FLAT_COLUMNS + " FROM STDIN", lines);
    }

    private static Iterator<String> mapping(final Iterator<ThingRecord> things,
            final java.util.function.Function<ThingRecord, String> render) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return things.hasNext();
            }

            @Override
            public String next() {
                return render.apply(things.next());
            }
        };
    }

    private static long copyIn(final Connection connection, final String sql, final Iterator<String> lines)
            throws SQLException, IOException {
        final CopyManager copyManager = new CopyManager(connection.unwrap(BaseConnection.class));
        return copyManager.copyIn(sql, new LineIteratorReader(lines));
    }

    /** Adapts an {@code Iterator<String>} of pre-formatted COPY lines (each including its trailing {@code \n}) to a
     * {@link Reader}, pulling one line at a time so the caller never has to buffer the whole stream. */
    private static final class LineIteratorReader extends Reader {

        private final Iterator<String> lines;
        private String current = "";
        private int pos;

        private LineIteratorReader(final Iterator<String> lines) {
            this.lines = lines;
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) {
            while (pos >= current.length()) {
                if (!lines.hasNext()) {
                    return -1;
                }
                current = lines.next();
                pos = 0;
            }
            final int n = Math.min(len, current.length() - pos);
            current.getChars(pos, pos + n, cbuf, off);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            // nothing to release
        }

    }

}
