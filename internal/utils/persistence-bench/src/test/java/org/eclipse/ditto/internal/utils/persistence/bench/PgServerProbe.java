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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonObject;

/**
 * Server-side evidence for a W3 PostgreSQL run: cumulative-statistics deltas over the run (WAL, checkpoints,
 * buffer I/O of the journal tables), where the run's rows landed in the journal heap, and a wait-event sampler
 * of the churn backends.
 */
final class PgServerProbe {

    private PgServerProbe() {
    }

    /** Numeric cumulative counters of the statistics views W3 cares about, keyed "view.column". */
    static Map<String, Double> counters(final Connection c) throws SQLException {
        final Map<String, Double> out = new TreeMap<>();
        try (Statement st = c.createStatement()) {
            st.execute("SELECT pg_stat_clear_snapshot()");
        }
        addRow(c, out, "wal", "SELECT to_jsonb(s) FROM pg_stat_wal s", List.of());
        addRow(c, out, "bgwriter", "SELECT to_jsonb(s) FROM pg_stat_bgwriter s", List.of());
        addRow(c, out, "checkpointer", "SELECT to_jsonb(s) FROM pg_stat_checkpointer s", List.of());
        addRow(c, out, "db", "SELECT to_jsonb(s) FROM pg_stat_database s WHERE datname = current_database()",
                List.of("xact_commit", "blks_read", "blks_hit", "blk_read_time", "blk_write_time"));
        for (final String table : List.of(PersistenceShapes.JOURNAL, "things_journal_seq")) {
            addRow(c, out, table, "SELECT to_jsonb(s) FROM pg_statio_user_tables s WHERE relname = '" + table + "'",
                    List.of("heap_blks_read", "heap_blks_hit", "idx_blks_read", "idx_blks_hit"));
        }
        return out;
    }

    /** Adds a one-row view's numeric columns (all of them when {@code only} is empty); absent views are skipped. */
    private static void addRow(final Connection c, final Map<String, Double> out, final String prefix,
            final String sql, final List<String> only) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return;
            }
            final JsonObject row = JsonFactory.newObject(rs.getString(1));
            for (final JsonField field : row) {
                final String key = field.getKeyName();
                if (field.getValue().isNumber() && (only.isEmpty() || only.contains(key))) {
                    out.put(prefix + "." + key, field.getValue().asDouble());
                }
            }
        } catch (final SQLException e) {
            // e.g. pg_stat_checkpointer before PostgreSQL 17: nothing to record
        }
    }

    /** Column headers of {@link #windowCells}. */
    static final List<String> WINDOW_HEADERS = List.of("WAL flushes", "commits per flush", "avg WAL fsync ms",
            "FPI per op", "bufs written: checkpointer / bgwriter / backends");

    /**
     * Per-window WAL flush and buffer write-back cells: how many WAL fsyncs the window's commits needed, how long one
     * took (needs track_wal_io_timing), and who wrote dirty buffers back.
     */
    static List<String> windowCells(final Map<String, Double> before, final Map<String, Double> after,
            final long ops) {
        final double syncs = delta(before, after, "wal.wal_sync");
        final double syncMs = delta(before, after, "wal.wal_sync_time");
        return List.of(String.format("%.0f", syncs),
                syncs > 0 ? String.format("%.2f", ops / syncs) : "—",
                syncs > 0 && syncMs > 0 ? String.format("%.2f", syncMs / syncs) : "—",
                ops > 0 ? String.format("%.2f", delta(before, after, "wal.wal_fpi") / ops) : "—",
                String.format("%.0f / %.0f / %.0f", delta(before, after, "bgwriter.buffers_checkpoint"),
                        delta(before, after, "bgwriter.buffers_clean"),
                        delta(before, after, "bgwriter.buffers_backend")));
    }

    private static double delta(final Map<String, Double> before, final Map<String, Double> after,
            final String key) {
        return after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0);
    }

    static String deltaMarkdown(final Map<String, Double> before, final Map<String, Double> after, final long ops) {
        final List<List<String>> rows = new ArrayList<>();
        for (final Map.Entry<String, Double> e : after.entrySet()) {
            final double delta = e.getValue() - before.getOrDefault(e.getKey(), 0.0);
            if (delta != 0.0) {
                rows.add(List.of(e.getKey(), String.format("%.0f", delta),
                        ops > 0 ? String.format("%.3f", delta / ops) : "—"));
            }
        }
        return BenchProtocol.table(List.of("counter (pg_stat view.column)", "delta over the run", "per op"), rows);
    }

    static long relationBlocks(final Connection c, final String table) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT pg_relation_size('" + table + "') / "
                        + "current_setting('block_size')::int")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Where the run's journal rows landed: heap blocks below {@code blocksAtStart} existed before the run, so a row
     * there was placed into free space the free space map handed out instead of being appended at the end.
     */
    static String placementMarkdown(final Connection c, final List<String> pids, final long blocksAtStart)
            throws SQLException {
        final String sql = "SELECT count(*), count(DISTINCT blk), count(*) FILTER (WHERE blk < ?), "
                + "count(DISTINCT blk) FILTER (WHERE blk < ?) FROM ("
                + "SELECT (ctid::text::point)[0]::bigint AS blk FROM " + PersistenceShapes.JOURNAL
                + " WHERE pid = ANY(?)) r";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            final Array array = c.createArrayOf("text", pids.toArray(new String[0]));
            ps.setLong(1, blocksAtStart);
            ps.setLong(2, blocksAtStart);
            ps.setArray(3, array);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                final long rows = rs.getLong(1);
                final long pages = rs.getLong(2);
                final long oldRows = rs.getLong(3);
                final long oldPages = rs.getLong(4);
                return String.format("- journal heap placement of the run's %d rows: %d distinct pages; %d rows "
                                + "(%.1f %%) on %d pages that existed before the run (blocks < %d, i.e. free "
                                + "space reused via the free space map), %d rows appended on %d new pages%n",
                        rows, pages, oldRows, rows > 0 ? 100.0 * oldRows / rows : 0.0, oldPages, blocksAtStart,
                        rows - oldRows, pages - oldPages);
            }
        }
    }

    /**
     * Samples pg_stat_activity of the churn backends ({@link PgAppenders#CHURN_APPLICATION_NAME}) every
     * {@code intervalMillis} and counts (state, wait_event_type:wait_event) over all samples. An idle backend is
     * waiting for its next paced op; "idle in transaction" waits for the client's next statement (network round
     * trip plus client time); "active" with no wait event is on CPU.
     */
    static final class WaitSampler implements AutoCloseable {

        private final Map<String, Long> counts = new TreeMap<>();
        private final Thread thread;
        private volatile boolean running = true;
        private long samples;
        private volatile Exception failure;

        WaitSampler(final int intervalMillis) {
            thread = new Thread(() -> run(intervalMillis), "w3-wait-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private void run(final int intervalMillis) {
            final String sql = "SELECT state || ' / ' || coalesce(wait_event_type || ':' || wait_event, 'CPU'), "
                    + "count(*) FROM pg_stat_activity WHERE application_name = '"
                    + PgAppenders.CHURN_APPLICATION_NAME + "' GROUP BY 1";
            try (Connection c = BenchConfig.openPg("w3-wait-sampler"); Statement st = c.createStatement()) {
                while (running) {
                    try (ResultSet rs = st.executeQuery(sql)) {
                        synchronized (counts) {
                            samples++;
                            while (rs.next()) {
                                counts.merge(rs.getString(1), rs.getLong(2), Long::sum);
                            }
                        }
                    }
                    LockSupport.parkNanos(intervalMillis * 1_000_000L);
                }
            } catch (final Exception e) {
                failure = e;
            }
        }

        @Override
        public void close() throws InterruptedException {
            running = false;
            thread.join(10_000);
        }

        String markdown() {
            synchronized (counts) {
                final long total = counts.values().stream().mapToLong(Long::longValue).sum();
                final List<List<String>> rows = new ArrayList<>();
                counts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .forEach(e -> rows.add(List.of(e.getKey(), String.valueOf(e.getValue()),
                                String.format("%.1f %%", 100.0 * e.getValue() / Math.max(1, total)))));
                return String.format("- wait-event samples: %d sampler passes, %d backend observations%s%n%n",
                        samples, total, failure == null ? "" : " (sampler failed: " + failure.getMessage() + ")")
                        + BenchProtocol.table(List.of("state / wait event", "observations", "share"), rows);
            }
        }
    }
}
