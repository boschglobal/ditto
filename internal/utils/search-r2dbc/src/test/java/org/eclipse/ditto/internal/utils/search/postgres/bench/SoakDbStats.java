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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The write-soak's database probes. {@code search_things}' TOAST table is read through {@code pg_stat_all_tables}
 * ({@code pg_stat_user_tables} excludes the {@code pg_toast} schema). Every probe runs in its own autocommit statement,
 * so the cumulative statistics are fresh each time ({@code stats_fetch_consistency} caches them per transaction).
 * The columns used exist on PostgreSQL 16 to 18.
 */
final class SoakDbStats {

    static final List<String> SETTINGS = List.of("server_version", "shared_buffers", "effective_cache_size",
            "work_mem", "maintenance_work_mem", "autovacuum_work_mem", "max_wal_size", "min_wal_size",
            "checkpoint_timeout", "wal_compression", "synchronous_commit", "full_page_writes", "random_page_cost",
            "effective_io_concurrency", "max_connections", "autovacuum", "autovacuum_max_workers",
            "autovacuum_naptime", "autovacuum_vacuum_threshold", "autovacuum_vacuum_scale_factor",
            "autovacuum_vacuum_insert_scale_factor", "autovacuum_analyze_scale_factor",
            "autovacuum_vacuum_cost_limit", "autovacuum_vacuum_cost_delay", "vacuum_cost_limit",
            "vacuum_cost_page_miss", "vacuum_cost_page_dirty", "gin_pending_list_limit", "jit");

    private static final String RELATIONS_CTE = "WITH rels(label, rel_oid) AS ("
            + "SELECT '" + SoakAnalysis.SEARCH_FLAT + "', 'search_flat'::regclass::oid "
            + "UNION ALL SELECT '" + SoakAnalysis.SEARCH_THINGS + "', 'search_things'::regclass::oid "
            + "UNION ALL SELECT '" + SoakAnalysis.SEARCH_THINGS_TOAST + "', reltoastrelid FROM pg_class "
            + "WHERE oid = 'search_things'::regclass AND reltoastrelid <> 0) ";
    private static final String RELATION_STATS = RELATIONS_CTE
            + "SELECT r.label, s.n_live_tup, s.n_dead_tup, greatest(c.reltuples, 0)::bigint, s.n_tup_ins, "
            + "s.n_tup_upd, s.n_tup_hot_upd, s.n_tup_del, s.autovacuum_count, s.last_autovacuum::text, "
            + "pg_relation_size(r.rel_oid), pg_indexes_size(r.rel_oid), pg_total_relation_size(r.rel_oid) "
            + "FROM rels r JOIN pg_class c ON c.oid = r.rel_oid JOIN pg_stat_all_tables s ON s.relid = r.rel_oid "
            + "ORDER BY r.label";
    private static final String INDEX_SIZES = "SELECT c.relname, pg_relation_size(i.indexrelid) FROM pg_index i "
            + "JOIN pg_class c ON c.oid = i.indexrelid "
            + "WHERE i.indrelid IN ('search_things'::regclass, 'search_flat'::regclass) ORDER BY c.relname";
    private static final String WAL = "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint, "
            + "(SELECT wal_fpi FROM pg_stat_wal)";
    private static final String VACUUM_PROGRESS = RELATIONS_CTE
            + "SELECT p.pid, coalesce(r.label, p.relid::regclass::text), p.phase, p.heap_blks_total, "
            + "p.heap_blks_scanned, p.index_vacuum_count, coalesce(a.backend_type = 'autovacuum worker', false), "
            + "coalesce(a.wait_event_type || ':' || a.wait_event, 'CPU') "
            + "FROM pg_stat_progress_vacuum p LEFT JOIN rels r ON r.rel_oid = p.relid "
            + "LEFT JOIN pg_stat_activity a ON a.pid = p.pid WHERE p.datname = current_database()";
    private static final String RELOPTIONS = RELATIONS_CTE
            + "SELECT r.label, coalesce(array_to_string(c.reloptions, ','), '') FROM rels r "
            + "JOIN pg_class c ON c.oid = r.rel_oid ORDER BY r.label";
    private static final String SETTINGS_SQL =
            "SELECT name, current_setting(name) FROM pg_settings WHERE name = ANY (?) ORDER BY name";

    private SoakDbStats() {
        throw new AssertionError("no instances");
    }

    static SoakAnalysis.Snapshot snapshot(final Connection connection) throws SQLException {
        final Map<String, SoakAnalysis.RelationSample> relations = new LinkedHashMap<>();
        final Map<String, Long> indexBytes = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(RELATION_STATS)) {
                while (rs.next()) {
                    relations.put(rs.getString(1), new SoakAnalysis.RelationSample(rs.getString(1), rs.getLong(2),
                            rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                            rs.getLong(9), rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getLong(13)));
                }
            }
            try (ResultSet rs = statement.executeQuery(INDEX_SIZES)) {
                while (rs.next()) {
                    indexBytes.put(rs.getString(1), rs.getLong(2));
                }
            }
            try (ResultSet rs = statement.executeQuery(WAL)) {
                rs.next();
                return new SoakAnalysis.Snapshot(relations, indexBytes, rs.getLong(1), rs.getLong(2));
            }
        }
    }

    static List<SoakAnalysis.VacuumObservation> vacuumProgress(final Connection connection) throws SQLException {
        final List<SoakAnalysis.VacuumObservation> observations = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(VACUUM_PROGRESS)) {
            while (rs.next()) {
                observations.add(new SoakAnalysis.VacuumObservation(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getBoolean(7), rs.getString(8)));
            }
        }
        return observations;
    }

    static Map<String, List<String>> reloptions(final Connection connection) throws SQLException {
        final Map<String, List<String>> reloptions = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(RELOPTIONS)) {
            while (rs.next()) {
                final String options = rs.getString(2);
                reloptions.put(rs.getString(1), options.isEmpty() ? List.of() : List.of(options.split(",")));
            }
        }
        return reloptions;
    }

    static Map<String, String> settings(final Connection connection) throws SQLException {
        return settings(connection, SETTINGS);
    }

    static Map<String, String> settings(final Connection connection, final List<String> names) throws SQLException {
        final Map<String, String> settings = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(SETTINGS_SQL)) {
            ps.setArray(1, connection.createArrayOf("text", names.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    settings.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return settings;
    }

    static Map<String, SoakAnalysis.VacuumTrigger> vacuumTriggers(final Connection connection) throws SQLException {
        final Map<String, List<String>> reloptions = reloptions(connection);
        final Map<String, String> server = settings(connection,
                List.of("autovacuum_vacuum_threshold", "autovacuum_vacuum_scale_factor"));
        final double base = Double.parseDouble(server.get("autovacuum_vacuum_threshold"));
        final double scale = Double.parseDouble(server.get("autovacuum_vacuum_scale_factor"));
        final Map<String, SoakAnalysis.VacuumTrigger> triggers = new LinkedHashMap<>();
        reloptions.forEach((relation, own) -> triggers.put(relation, SoakAnalysis.VacuumTrigger.resolve(own,
                SoakAnalysis.SEARCH_THINGS_TOAST.equals(relation)
                        ? reloptions.getOrDefault(SoakAnalysis.SEARCH_THINGS, List.of()) : List.of(),
                base, scale)));
        return triggers;
    }

}
