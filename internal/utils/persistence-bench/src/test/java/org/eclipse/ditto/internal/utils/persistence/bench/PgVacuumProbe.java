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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only probes of PostgreSQL's vacuum state for the sweep scenarios: autovacuum progress, what the autovacuum
 * workers wait for, storage parameters, the vacuum-relevant server settings and per-table vacuum statistics. Every
 * query runs as its own statement on an autocommit connection, so the statistics views are read fresh. Only columns
 * that PostgreSQL 16, 17 and 18 share are read.
 */
final class PgVacuumProbe {

    static final List<String> TABLES = List.of("things_journal", "things_journal_seq", "things_snaps");

    private static final String IN_TABLES = "('things_journal','things_journal_seq','things_snaps')";

    record Progress(String table, String phase, long heapBlksScanned, long heapBlksTotal, long indexPasses) {}

    private PgVacuumProbe() {
    }

    static List<Progress> progress(final Connection c) throws SQLException {
        final List<Progress> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT cl.relname, p.phase, p.heap_blks_scanned, p.heap_blks_total, "
                        + "p.index_vacuum_count FROM pg_stat_progress_vacuum p JOIN pg_class cl ON cl.oid = p.relid "
                        + "WHERE p.datname = current_database() ORDER BY cl.relname")) {
            while (rs.next()) {
                out.add(new Progress(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }
        }
        return out;
    }

    static String formatProgress(final List<Progress> rows) {
        if (rows.isEmpty()) {
            return "none";
        }
        return rows.stream()
                .map(p -> String.format("%s %s (heap %d/%d, index passes %d)", p.table(), p.phase(),
                        p.heapBlksScanned(), p.heapBlksTotal(), p.indexPasses()))
                .collect(Collectors.joining("; "));
    }

    /** One sample per autovacuum worker on this database: its wait_event, or "CPU" when it waits for nothing. */
    static List<String> autovacuumWaits(final Connection c) throws SQLException {
        final List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT coalesce(wait_event, 'CPU') FROM pg_stat_activity "
                        + "WHERE backend_type = 'autovacuum worker' AND datname = current_database()")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** "VacuumDelay 11, CPU 3, DataFileRead 3" (most frequent first, then by name); "no autovacuum worker" if empty. */
    static String formatWaits(final Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "no autovacuum worker";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.<String, Integer>comparingByKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
    }

    static String reloptions(final Connection c) throws SQLException {
        final List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT cl.relname, coalesce(array_to_string(cl.reloptions, ','), "
                        + "'none') FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace "
                        + "WHERE n.nspname = 'public' AND cl.relname IN " + IN_TABLES + " ORDER BY cl.relname")) {
            while (rs.next()) {
                out.add(rs.getString(1) + " [" + rs.getString(2) + "]");
            }
        }
        return String.join("; ", out);
    }

    /** The vacuum-relevant server settings, each as current_setting() prints it (with its unit, e.g. 64MB). */
    static String settings(final Connection c) throws SQLException {
        final List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT name, current_setting(name) FROM pg_settings WHERE name IN "
                        + "('server_version', 'shared_buffers', 'maintenance_work_mem', 'autovacuum_work_mem', "
                        + "'autovacuum_max_workers', 'autovacuum_naptime', 'autovacuum_vacuum_cost_limit', "
                        + "'autovacuum_vacuum_cost_delay', 'vacuum_cost_limit', 'vacuum_cost_page_miss', "
                        + "'vacuum_cost_page_dirty') ORDER BY name")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "=" + rs.getString(2));
            }
        }
        return String.join(", ", out);
    }

    static String tableStatsMarkdown(final Connection c) throws SQLException {
        final List<List<String>> rows = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT s.relname, cl.reltuples::bigint, s.n_live_tup, s.n_dead_tup, "
                        + "s.autovacuum_count, s.vacuum_count, s.last_autovacuum, s.last_vacuum "
                        + "FROM pg_stat_user_tables s JOIN pg_class cl ON cl.oid = s.relid "
                        + "WHERE s.relname IN " + IN_TABLES + " ORDER BY s.relname")) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), String.valueOf(rs.getLong(2)), String.valueOf(rs.getLong(3)),
                        String.valueOf(rs.getLong(4)), String.valueOf(rs.getLong(5)), String.valueOf(rs.getLong(6)),
                        String.valueOf(rs.getString(7)), String.valueOf(rs.getString(8))));
            }
        }
        return BenchProtocol.table(List.of("table", "reltuples", "live", "dead", "autovacuum_count", "vacuum_count",
                "last_autovacuum", "last_vacuum"), rows);
    }
}
