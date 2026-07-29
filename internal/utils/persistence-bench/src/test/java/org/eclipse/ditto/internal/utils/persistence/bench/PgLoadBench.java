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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

/**
 * Bulk-loads the corpus into PostgreSQL via COPY, then builds constraints/indexes/storage params
 * so the end state is identical to PostgresSchema.ddlStatements() for the "things" entity, then
 * ANALYZEs and records counts + sizes into load-pg.md. Never runs in CI (no *Test/*IT name);
 * invoke via: mvn -pl internal/utils/persistence-bench test -Dtest=PgLoadBench -DfailIfNoTests=false
 */
public final class PgLoadBench {

    // Tables created WITHOUT PK for the bulk load (deviation 8); end state matches PostgresSchema.
    private static final List<String> CREATE_TABLES = List.of(
            "DROP TABLE IF EXISTS things_journal, things_journal_seq, things_snaps CASCADE",
            "CREATE TABLE things_journal (pid TEXT NOT NULL COLLATE \"C\", sn BIGINT NOT NULL, "
                    + "seq BIGINT GENERATED ALWAYS AS IDENTITY, manifest TEXT NOT NULL, "
                    + "tags TEXT[] NOT NULL DEFAULT '{}', event JSONB NOT NULL, "
                    + "written_at TIMESTAMPTZ NOT NULL DEFAULT now())",
            "CREATE TABLE things_journal_seq (pid TEXT COLLATE \"C\" NOT NULL, "
                    + "highest_sn BIGINT NOT NULL, deleted_to BIGINT NOT NULL DEFAULT 0)",
            "CREATE TABLE things_snaps (pid TEXT NOT NULL COLLATE \"C\", sn BIGINT NOT NULL, "
                    + "snapshot JSONB NOT NULL, lifecycle TEXT, written_at TIMESTAMPTZ NOT NULL)");

    private static final List<String> POST_LOAD = List.of(
            "ALTER TABLE things_journal ADD PRIMARY KEY (pid, sn)",
            "CREATE INDEX things_journal_tags_idx ON things_journal USING GIN (tags)",
            "ALTER TABLE things_journal_seq ADD PRIMARY KEY (pid)",
            "ALTER TABLE things_snaps ADD PRIMARY KEY (pid, sn, written_at)",
            "CREATE INDEX things_snaps_lifecycle_idx ON things_snaps (lifecycle) WHERE lifecycle = 'DELETED'",
            "ALTER TABLE things_journal SET (autovacuum_vacuum_scale_factor = 0.01)",
            "ALTER TABLE things_journal_seq SET (autovacuum_vacuum_scale_factor = 0.01)",
            "ALTER TABLE things_snaps SET (autovacuum_vacuum_scale_factor = 0.01)",
            "ANALYZE things_journal",
            "ANALYZE things_journal_seq",
            "ANALYZE things_snaps");

    @Test
    public void load() throws Exception {
        if (!BenchConfig.runPg()) {
            System.out.println("[bench] backend=" + BenchConfig.backend() + " — skipping PG load");
            return;
        }
        try (Connection connection = BenchConfig.openPg()) {
            run(connection, BenchConfig.corpus(), true);
        }
    }

    public static LoadStats run(final Connection connection, final CorpusGenerator gen,
            final boolean writeEvidence) throws Exception {
        connection.setAutoCommit(true);
        final long t0 = System.nanoTime();
        try (Statement st = connection.createStatement()) {
            for (final String ddl : CREATE_TABLES) {
                st.execute(ddl);
            }
            // session-local speedup for the post-load index builds only
            st.execute("SET maintenance_work_mem = '1GB'");
        }

        final CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();

        final long tCopy0 = System.nanoTime();
        long journalRows = 0;
        long snapshotRows = 0;
        // journal
        CopyIn copy = copyManager.copyIn(
                "COPY things_journal (pid, sn, manifest, tags, event, written_at) FROM STDIN");
        StringBuilder buf = new StringBuilder(1 << 21);
        for (long i = 0; i < gen.pidCount(); i++) {
            final Iterator<CorpusGenerator.EventRow> events = gen.events(i);
            while (events.hasNext()) {
                final CorpusGenerator.EventRow e = events.next();
                buf.append(e.pid()).append('\t')
                        .append(e.sn()).append('\t')
                        .append(e.manifest()).append('\t')
                        .append("{}").append('\t')
                        .append(copyEscape(e.eventJson())).append('\t')
                        .append(Instant.ofEpochMilli(e.timestampMillis()).toString()).append('\n');
                journalRows++;
                if (buf.length() > (1 << 20)) {
                    flush(copy, buf);
                }
                if (journalRows % 2_000_000 == 0) {
                    System.out.println("[bench] journal rows copied: " + journalRows);
                }
            }
        }
        flush(copy, buf);
        copy.endCopy();

        // journal_seq: one row per pid (highest_sn = depth, deleted_to = 0)
        copy = copyManager.copyIn("COPY things_journal_seq (pid, highest_sn, deleted_to) FROM STDIN");
        for (long i = 0; i < gen.pidCount(); i++) {
            buf.append(gen.pid(i)).append('\t').append(gen.depth(i)).append('\t').append(0).append('\n');
            if (buf.length() > (1 << 20)) {
                flush(copy, buf);
            }
        }
        flush(copy, buf);
        copy.endCopy();

        // snapshots (lifecycle NULL — all corpus entities are live)
        copy = copyManager.copyIn(
                "COPY things_snaps (pid, sn, snapshot, lifecycle, written_at) FROM STDIN");
        for (long i = 0; i < gen.pidCount(); i++) {
            final Iterator<CorpusGenerator.SnapshotRow> snaps = gen.snapshots(i);
            while (snaps.hasNext()) {
                final CorpusGenerator.SnapshotRow s = snaps.next();
                buf.append(s.pid()).append('\t')
                        .append(s.sn()).append('\t')
                        .append(copyEscape(s.snapshotJson())).append('\t')
                        .append("\\N").append('\t')
                        .append(Instant.ofEpochMilli(s.timestampMillis()).toString()).append('\n');
                snapshotRows++;
                if (buf.length() > (1 << 20)) {
                    flush(copy, buf);
                }
            }
        }
        flush(copy, buf);
        copy.endCopy();
        final double copySeconds = (System.nanoTime() - tCopy0) / 1e9;

        final long tIndex0 = System.nanoTime();
        try (Statement st = connection.createStatement()) {
            for (final String ddl : POST_LOAD) {
                System.out.println("[bench] " + ddl);
                st.execute(ddl);
            }
        }
        final double indexSeconds = (System.nanoTime() - tIndex0) / 1e9;

        final LoadStats stats = new LoadStats(
                count(connection, "things_journal"),
                count(connection, "things_journal_seq"),
                count(connection, "things_snaps"));

        if (stats.journalRows() != gen.totalEvents() || stats.auxRows() != gen.pidCount()
                || stats.snapshotRows() != gen.totalSnapshots()) {
            throw new IllegalStateException("loaded counts do not match generator: " + stats
                    + " vs events=" + gen.totalEvents() + " pids=" + gen.pidCount()
                    + " snaps=" + gen.totalSnapshots());
        }

        if (writeEvidence) {
            final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("Load evidence — PostgreSQL"));
            md.append("- journal rows: ").append(stats.journalRows()).append('\n');
            md.append("- journal_seq rows: ").append(stats.auxRows()).append('\n');
            md.append("- snapshot rows: ").append(stats.snapshotRows()).append('\n');
            md.append(String.format("- COPY: %.1f s; constraints+indexes+ANALYZE: %.1f s; total: %.1f s%n%n",
                    copySeconds, indexSeconds, (System.nanoTime() - t0) / 1e9));
            md.append("## Sizes\n\n").append(sizesMarkdown(connection));
            md.append("\nEnd-state DDL is identical to PostgresSchema.ddlStatements(): tables were "
                    + "created without PKs for the COPY, then PK/indexes/autovacuum params added "
                    + "(design plan, deviation 8).\n");
            BenchProtocol.writeEvidence("load-pg.md", md.toString());
        }
        return stats;
    }

    static String sizesMarkdown(final Connection connection) throws SQLException {
        final List<List<String>> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.relname, pg_table_size(c.oid), pg_indexes_size(c.oid), "
                        + "pg_total_relation_size(c.oid) "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' "
                        + "AND c.relname IN ('things_journal','things_journal_seq','things_snaps') "
                        + "ORDER BY c.relname");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), mb(rs.getLong(2)), mb(rs.getLong(3)), mb(rs.getLong(4))));
            }
        }
        final StringBuilder sb = new StringBuilder(
                BenchProtocol.table(List.of("table", "heap", "indexes", "total"), rows));
        sb.append('\n');
        final List<List<String>> idx = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT indexrelname, pg_relation_size(indexrelid) FROM pg_stat_user_indexes "
                        + "WHERE relname IN ('things_journal','things_journal_seq','things_snaps') "
                        + "ORDER BY indexrelname");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                idx.add(List.of(rs.getString(1), mb(rs.getLong(2))));
            }
        }
        sb.append(BenchProtocol.table(List.of("index", "size"), idx));
        return sb.toString();
    }

    private static String mb(final long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static long count(final Connection connection, final String tableName) throws SQLException {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tableName)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void flush(final CopyIn copy, final StringBuilder buf) throws SQLException {
        if (buf.length() > 0) {
            final byte[] bytes = buf.toString().getBytes(StandardCharsets.UTF_8);
            copy.writeToCopy(bytes, 0, bytes.length);
            buf.setLength(0);
        }
    }

    /** COPY text-format escaping; the generated JSON contains no control chars, but be exact anyway. */
    static String copyEscape(final String s) {
        if (s.indexOf('\\') < 0 && s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }
}
