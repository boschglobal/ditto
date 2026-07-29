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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code bench/search-schema.sql} (the exact bench-target DDL — see the task brief) from the classpath
 * and splits it into a table-creation phase and an index-creation phase, so the loader can run
 * {@code CREATE TABLE} before the bulk {@code COPY} and defer every {@code CREATE INDEX} until after it. The
 * DDL resource itself stays a single verbatim copy of the target schema; the split is done here, at
 * statement-parse time, rather than by maintaining two separately-edited resource files that could drift.
 */
final class SearchSchemaDdl {

    private static final String RESOURCE = "/bench/search-schema.sql";
    private static final String CREATE_INDEX_PREFIX = "CREATE INDEX";

    private SearchSchemaDdl() {
        throw new AssertionError("no instances");
    }

    /** Drops both bench tables (and anything depending on them), if present. Idempotent. */
    static void dropAll(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS search_flat, search_things CASCADE");
        }
    }

    /** Runs every non-index statement (the {@code CREATE EXTENSION} and both {@code CREATE TABLE}s). */
    static void applyTables(final Connection connection) throws SQLException, IOException {
        execute(connection, tableStatements());
    }

    /** Runs every {@code CREATE INDEX} statement. Intended to run only after the bulk {@code COPY}. */
    static void applyIndexes(final Connection connection) throws SQLException, IOException {
        execute(connection, indexStatements());
    }

    static List<String> tableStatements() throws IOException {
        return filter(loadStatements(), false);
    }

    static List<String> indexStatements() throws IOException {
        return filter(loadStatements(), true);
    }

    private static List<String> filter(final List<String> all, final boolean indexOnly) {
        final List<String> result = new ArrayList<>();
        for (final String statement : all) {
            final boolean isIndexStatement =
                    statement.regionMatches(true, 0, CREATE_INDEX_PREFIX, 0, CREATE_INDEX_PREFIX.length());
            if (isIndexStatement == indexOnly) {
                result.add(statement);
            }
        }
        return result;
    }

    private static List<String> loadStatements() throws IOException {
        final String sql;
        try (InputStream in = SearchSchemaDdl.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final List<String> statements = new ArrayList<>();
        for (final String rawStatement : sql.split(";")) {
            final String trimmed = rawStatement.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static void execute(final Connection connection, final List<String> statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (final String sql : statements) {
                statement.execute(sql);
            }
        }
    }

}
