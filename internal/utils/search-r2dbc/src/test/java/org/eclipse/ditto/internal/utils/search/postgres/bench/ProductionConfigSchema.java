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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.PostgresSearchSchema;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.PathIndex;

/**
 * The production-config bench mode's schema: exactly {@link PostgresSearchSchema#ddlStatements()} — the SHIPPED search
 * DDL — and nothing else. The only liberty taken is ordering: the statements are split into an order-preserving
 * pre-load phase (the {@code pg_trgm} extension, the three tables and the tables' autovacuum reloptions) and post-load
 * phase (every {@code CREATE INDEX}), so the 1M-thing bulk {@code COPY} does not maintain 12 indexes row by row. The
 * resulting catalog is the same as a production bootstrap's; {@link #verify(Connection)} proves that after the load
 * (index set, default statistics targets, exactly the production reloptions).
 */
final class ProductionConfigSchema {

    private static final String CREATE_INDEX = "CREATE INDEX";
    private static final Pattern INDEX_NAME = Pattern.compile("^CREATE INDEX IF NOT EXISTS (\\S+) ON ");
    private static final Pattern TABLE_NAME = Pattern.compile("^CREATE TABLE IF NOT EXISTS (\\S+) \\(");

    private ProductionConfigSchema() {
        throw new AssertionError("no instances");
    }

    /** @return every non-index production DDL statement, in production order. */
    static List<String> preLoadStatements() {
        return filter(false);
    }

    /** @return every production {@code CREATE INDEX} statement, in production order. */
    static List<String> postLoadStatements() {
        return filter(true);
    }

    /** @return the production tables (parsed from the DDL). */
    static List<String> tableNames() {
        final List<String> tables = new ArrayList<>();
        for (final String statement : PostgresSearchSchema.ddlStatements()) {
            final Matcher m = TABLE_NAME.matcher(statement);
            if (m.find()) {
                tables.add(m.group(1));
            }
        }
        return tables;
    }

    /** @return the indexes a production bootstrap leaves on the search tables: one PK per table + every DDL index. */
    static Set<String> expectedIndexNames() {
        final Set<String> names = new LinkedHashSet<>();
        for (final String table : tableNames()) {
            names.add(table + "_pkey");
        }
        for (final String statement : postLoadStatements()) {
            final Matcher m = INDEX_NAME.matcher(statement);
            if (!m.find()) {
                throw new IllegalStateException("Unparseable production index DDL: " + statement);
            }
            names.add(m.group(1));
        }
        return names;
    }

    /** Drops the production search tables (and anything hanging off them, e.g. leftover bench-only indexes). */
    static void dropAll(final Connection connection) throws SQLException {
        final List<String> tables = new ArrayList<>(tableNames());
        java.util.Collections.reverse(tables); // search_flat references search_things
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + String.join(", ", tables) + " CASCADE");
        }
    }

    static void applyPreLoad(final Connection connection) throws SQLException {
        execute(connection, preLoadStatements());
    }

    static void applyPostLoad(final Connection connection) throws SQLException {
        execute(connection, postLoadStatements());
    }

    /**
     * The catalog as it stands, checked item by item against a reference catalog built from the production DDL.
     *
     * @param actualIndexes every index on the search tables.
     * @param comparedItems how many catalog items (indexes, columns, constraints, reloptions, statistics targets,
     * extended statistics) were compared.
     * @param differences every item whose live definition differs from, or is missing in, the reference (must be
     * empty).
     * @param indexDefinitions {@code pg_get_indexdef} of every live index, for the evidence file.
     * @param perPathIndexes {@code pg_get_indexdef} of the operator-configured per-path indexes
     * ({@code ditto.postgresql.search.path-indexes}): built outside the production DDL, so not compared, only listed.
     */
    record CatalogCheck(Set<String> actualIndexes, int comparedItems, List<String> differences,
            List<String> indexDefinitions, List<String> perPathIndexes) {

        boolean matchesProduction() {
            return differences.isEmpty();
        }

        /**
         * @return {@code "true"}, {@code "false"}, or — when the catalog matches but per-path indexes are present —
         * {@code "production DDL + N per-path index(es)"}, so a run with a leftover per-path index cannot pass for a
         * plain production-DDL run.
         */
        String productionVerdict() {
            if (!matchesProduction()) {
                return "false";
            }
            return perPathIndexes.isEmpty() ? "true" : "production DDL + " + perPathIndexes.size() + " per-path index"
                    + (perPathIndexes.size() == 1 ? "" : "es");
        }

        /**
         * @return the names of the per-path indexes, parsed from their definitions.
         */
        List<String> perPathIndexNames() {
            return perPathIndexes.stream()
                    .map(definition -> definition.substring("CREATE INDEX ".length(), definition.indexOf(" ON ")))
                    .toList();
        }
    }

    /**
     * The definition of a per-path trigram index as {@code PathIndexMaintenance} builds it (schema name removed): a
     * managed {@code sf_path_} name, the table-wide trigram expression, restricted to one wpath.
     */
    private static final Pattern PER_PATH_TRIGRAM = Pattern.compile("CREATE INDEX "
            + Pattern.quote(PathIndex.NAME_PREFIX) + "trgm_[0-9a-f]{16} ON search_flat USING gin \\(val_text COLLATE \""
            + Pattern.quote(PostgresSearchSchema.TRIGRAM_INDEX_COLLATION)
            + "\" gin_trgm_ops\\) WHERE \\(\\(wpath = '.*'::text\\) AND \\(val_text IS NOT NULL\\)\\)");

    /** Scratch schema the reference catalog is built in; the transaction that creates it is always rolled back. */
    private static final String REFERENCE_SCHEMA = "production_config_ddl_reference";

    /**
     * Compares the live search tables with a REFERENCE built from {@link PostgresSearchSchema#ddlStatements()} in a
     * scratch schema inside a transaction that is rolled back (nothing persists). Compared, normalised for the schema
     * name: every index definition ({@code pg_get_indexdef}), every column (type, collation, NOT NULL, default), every
     * constraint ({@code pg_get_constraintdef}), every relation's reloptions, every per-column statistics target and
     * every extended statistics object. Anything the production DDL does not create — a covering index, a
     * {@code SET STATISTICS}, a {@code CREATE STATISTICS}, a reloption — or a same-named but redefined index shows up
     * as a difference (review round 1, M5). Comparing against the reference instead of hard-coded expectations keeps
     * the check correct when the production DDL itself gains, e.g., autovacuum reloptions. An operator-configured
     * per-path trigram index ({@code sf_path_trgm_*} with exactly the shape {@code PathIndexMaintenance} builds) is not
     * part of the production DDL by design; it is listed in {@link CatalogCheck#perPathIndexes()} instead of being
     * reported as a difference. Any other index under that name prefix is still a difference.
     */
    static CatalogCheck verify(final Connection connection) throws SQLException {
        final String liveSchema;
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT current_schema()")) {
            rs.next();
            liveSchema = rs.getString(1);
        }
        final java.util.Map<String, String> live = catalog(connection, liveSchema);
        final List<String> liveIndexDefinitions = indexDefinitions(connection, liveSchema);
        final Set<String> liveIndexes = new TreeSet<>();
        live.keySet().stream().filter(k -> k.startsWith("index ")).forEach(k -> liveIndexes.add(k.substring(6)));
        final List<String> perPathIndexes = new ArrayList<>();
        live.entrySet().removeIf(item -> {
            final boolean perPath = item.getKey().startsWith("index ")
                    && PathIndex.isManagedName(item.getKey().substring(6))
                    && PER_PATH_TRIGRAM.matcher(item.getValue()).matches();
            if (perPath) {
                perPathIndexes.add(item.getValue());
            }
            return perPath;
        });

        final java.util.Map<String, String> reference;
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + REFERENCE_SCHEMA);
            // pg_trgm (and its gin_trgm_ops) stays where it is installed; the tables and indexes go to the reference.
            statement.execute("SET LOCAL search_path = " + REFERENCE_SCHEMA + ", " + liveSchema);
            for (final String ddl : PostgresSearchSchema.ddlStatements()) {
                statement.execute(ddl);
            }
            reference = catalog(connection, REFERENCE_SCHEMA);
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }

        final List<String> differences = new ArrayList<>();
        final Set<String> keys = new TreeSet<>(live.keySet());
        keys.addAll(reference.keySet());
        for (final String key : keys) {
            final String liveValue = live.get(key);
            final String referenceValue = reference.get(key);
            if (!java.util.Objects.equals(liveValue, referenceValue)) {
                differences.add(key + ": live=" + liveValue + " production=" + referenceValue);
            }
        }
        return new CatalogCheck(liveIndexes, keys.size(), differences, liveIndexDefinitions,
                List.copyOf(perPathIndexes));
    }

    /** Item → normalised definition for the search tables in {@code schema}. */
    private static java.util.Map<String, String> catalog(final Connection connection, final String schema)
            throws SQLException {
        final String tables = "'" + String.join("','", tableNames()) + "'";
        final String inSchema = "n.nspname = '" + schema + "' AND c.relname IN (" + tables + ")";
        final java.util.Map<String, String> items = new java.util.TreeMap<>();
        final String[] queries = {
                // indexes
                "SELECT 'index ' || i.relname, pg_get_indexdef(i.oid) FROM pg_index x "
                        + "JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class c ON c.oid = x.indrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE " + inSchema,
                // columns
                "SELECT 'column ' || c.relname || '.' || a.attname, format_type(a.atttypid, a.atttypmod) "
                        + "|| ' collate=' || coalesce(co.collname, '-') || ' notnull=' || a.attnotnull "
                        + "|| ' default=' || coalesce(pg_get_expr(d.adbin, d.adrelid), '-') "
                        + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "LEFT JOIN pg_collation co ON co.oid = a.attcollation AND a.attcollation <> 0 "
                        + "LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                        + "WHERE " + inSchema + " AND a.attnum > 0 AND NOT a.attisdropped",
                // constraints
                "SELECT 'constraint ' || c.relname || '.' || k.conname, pg_get_constraintdef(k.oid) "
                        + "FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE " + inSchema,
                // reloptions of the tables and of their indexes
                "SELECT 'reloptions ' || r.relname, array_to_string(r.reloptions, ',') FROM pg_class r "
                        + "LEFT JOIN pg_index x ON x.indexrelid = r.oid "
                        + "JOIN pg_class c ON c.oid = coalesce(x.indrelid, r.oid) "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE " + inSchema + " AND r.reloptions IS NOT NULL",
                // per-column statistics targets (-1 on PG <= 16 / NULL on PG >= 17 = default: not listed)
                "SELECT 'statistics-target ' || c.relname || '.' || a.attname, a.attstattarget::text "
                        + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE " + inSchema + " AND a.attnum > 0 "
                        + "AND NOT a.attisdropped AND a.attstattarget IS NOT NULL AND a.attstattarget <> -1",
                // extended statistics (CREATE STATISTICS)
                "SELECT 'extended-statistics ' || s.stxname, pg_get_statisticsobjdef(s.oid) FROM pg_statistic_ext s "
                        + "JOIN pg_class c ON c.oid = s.stxrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE " + inSchema,
        };
        try (Statement statement = connection.createStatement()) {
            for (final String query : queries) {
                try (ResultSet rs = statement.executeQuery(query)) {
                    while (rs.next()) {
                        items.put(rs.getString(1), normalise(rs.getString(2), schema));
                    }
                }
            }
        }
        return items;
    }

    private static List<String> indexDefinitions(final Connection connection, final String schema)
            throws SQLException {
        final List<String> definitions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname = '"
                        + schema + "' AND tablename IN ('" + String.join("','", tableNames())
                        + "') ORDER BY indexname")) {
            while (rs.next()) {
                definitions.add(rs.getString(1));
            }
        }
        return definitions;
    }

    private static String normalise(final String definition, final String schema) {
        return definition == null ? null : definition.replace(schema + ".", "");
    }

    private static List<String> filter(final boolean indexStatements) {
        final List<String> result = new ArrayList<>();
        for (final String statement : PostgresSearchSchema.ddlStatements()) {
            if (statement.startsWith(CREATE_INDEX) == indexStatements) {
                result.add(statement);
            }
        }
        return List.copyOf(result);
    }

    private static void execute(final Connection connection, final List<String> statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (final String sql : statements) {
                statement.execute(sql);
            }
        }
    }

}
