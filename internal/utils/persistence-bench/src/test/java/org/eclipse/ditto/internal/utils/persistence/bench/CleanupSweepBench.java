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
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

/**
 * C3: full cleanup sweep over the corpus (DESTRUCTIVE — that is its job). C4: before/after proof —
 * autovacuum settle, post-sweep replay timings, natural sizes, then explicitly-labelled OFFLINE
 * reclamation (VACUUM FULL / compact) with re-measured sizes.
 */
public final class CleanupSweepBench {

    @Test
    public void c3FullSweep() throws Exception {
        final boolean credits = "credits".equals(BenchConfig.sweepPace());
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("C3 — full cleanup sweep"))
                .append("- pace: ").append(credits ? "credits (3 batches / 3 s — Ditto's default gate)"
                        : "unthrottled (sequential, one batch in flight — measures backend capability)")
                .append("\n\n");
        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg()) {
                final SweepEngine.SweepResult r = SweepEngine.sweepPg(c, credits, new AtomicBoolean(false));
                md.append(sweepSection("PostgreSQL", r));
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final SweepEngine.SweepResult r = SweepEngine.sweepMongo(
                        client.getDatabase(BenchConfig.mongoDbName()), credits, new AtomicBoolean(false));
                md.append(sweepSection("MongoDB", r));
            }
        }
        md.append("\nAt Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the "
                + "same sweep would need [total batches / 1 per second] — quoted per backend above. "
                + "Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs "
                + "(plan deviation 3).\n");
        BenchProtocol.writeEvidence("c3-full-sweep.md", md.toString());
    }

    private static String sweepSection(final String backend, final SweepEngine.SweepResult r) {
        return "## " + backend + "\n\n"
                + String.format("- pids processed: %d%n- event rows deleted: %d%n- snapshot rows deleted: %d%n"
                        + "- delete batches: %d%n- duration: %.1f s (%.0f rows/s)%n"
                        + "- at default credit pace this would take ~%.1f hours%n%n",
                        r.pids(), r.eventRowsDeleted(), r.snapRowsDeleted(), r.batches(),
                        r.durationSeconds(),
                        (r.eventRowsDeleted() + r.snapRowsDeleted()) / Math.max(1.0, r.durationSeconds()),
                        r.batches() / 3600.0)
                + SweepEngine.samplesMarkdown(r) + "\n";
    }

    @Test
    public void c4BeforeAfterProof() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final int n = BenchConfig.warmups() + BenchConfig.iterations();
        final List<Long> median = RecoveryBench.pickPids(gen, CorpusGenerator.PidClass.MEDIAN, n, 0);
        final List<Long> p99 = RecoveryBench.pickPids(gen, CorpusGenerator.PidClass.P99, n, 0);
        final List<Long> outlier = RecoveryBench.pickPids(gen, CorpusGenerator.PidClass.OUTLIER, n, 0);
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader(
                "C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)"));

        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg()) {
                md.append("## PostgreSQL\n\n### Autovacuum settle\n\n").append(settlePg(c));
                md.append("\n### Post-sweep replay (same pid picks as r2-replay.md)\n\n");
                md.append(RecoveryBench.resultTable(List.of(
                        RecoveryBench.sideBySide("R2a tail MEDIAN",
                                RecoveryBench.pgReplayTail(c, gen, median), null),
                        RecoveryBench.sideBySide("R2a tail P99",
                                RecoveryBench.pgReplayTail(c, gen, p99), null),
                        RecoveryBench.sideBySide("R2a tail OUTLIER",
                                RecoveryBench.pgReplayTail(c, gen, outlier), null),
                        RecoveryBench.sideBySide("R2b full-scan OUTLIER (post-sweep: only the tail remains)",
                                RecoveryBench.pgReplayFull(c, gen, outlier), null))));
                md.append("\n### Natural sizes (files do NOT shrink after deletes — see note)\n\n")
                        .append(PgLoadBench.sizesMarkdown(c));
                md.append("\n### OFFLINE reclamation (VACUUM FULL — not part of normal operation)\n\n");
                try (Statement st = c.createStatement()) {
                    st.execute("VACUUM FULL things_journal");
                    st.execute("VACUUM FULL things_snaps");
                    st.execute("ANALYZE things_journal");
                    st.execute("ANALYZE things_snaps");
                }
                md.append(PgLoadBench.sizesMarkdown(c));
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                md.append("\n## MongoDB\n\n### Post-sweep replay (same pid picks)\n\n");
                md.append(RecoveryBench.resultTable(List.of(
                        RecoveryBench.sideBySide("R2a tail MEDIAN", null,
                                RecoveryBench.mongoReplayTail(db, gen, median)),
                        RecoveryBench.sideBySide("R2a tail P99", null,
                                RecoveryBench.mongoReplayTail(db, gen, p99)),
                        RecoveryBench.sideBySide("R2a tail OUTLIER", null,
                                RecoveryBench.mongoReplayTail(db, gen, outlier)),
                        RecoveryBench.sideBySide("R2b full-scan OUTLIER (post-sweep)", null,
                                RecoveryBench.mongoReplayFull(db, gen, outlier)))));
                md.append("\n### Natural sizes\n\n").append(MongoLoadBench.sizesMarkdown(db));
                md.append("\n### OFFLINE reclamation (compact — blocks, standalone-only convenience)\n\n");
                for (final String coll : List.of(PersistenceShapes.JOURNAL, PersistenceShapes.SNAPS)) {
                    try {
                        db.runCommand(new Document("compact", coll));
                    } catch (final RuntimeException e) {
                        md.append("- compact ").append(coll).append(" failed: ").append(e.getMessage()).append('\n');
                    }
                }
                md.append(MongoLoadBench.sizesMarkdown(db));
            }
        }
        md.append("\nDeletes free space inside data files (reusable) but do not shrink them — the "
                + "honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the "
                + "live/dead tuple counts above.\n");
        BenchProtocol.writeEvidence("c4-before-after.md", md.toString());
    }

    private static String settlePg(final Connection c) throws Exception {
        final long timeoutMillis = BenchConfig.settleTimeoutSeconds() * 1_000L;
        final long start = System.currentTimeMillis();
        final StringBuilder timeline = new StringBuilder();
        while (true) {
            long live = 0;
            long dead = 0;
            long autovac = 0;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT sum(n_live_tup), sum(n_dead_tup), sum(autovacuum_count) "
                                    + "FROM pg_stat_user_tables "
                                    + "WHERE relname IN ('things_journal','things_snaps')")) {
                if (rs.next()) {
                    live = rs.getLong(1);
                    dead = rs.getLong(2);
                    autovac = rs.getLong(3);
                }
            }
            final long elapsed = (System.currentTimeMillis() - start) / 1000;
            timeline.append(String.format("- t+%ds: live=%d dead=%d autovacuum_count=%d%n",
                    elapsed, live, dead, autovac));
            if (dead < Math.max(100_000L, live / 100L)) {
                timeline.append("- settled (dead < max(100k, 1% of live))\n");
                break;
            }
            if (System.currentTimeMillis() - start > timeoutMillis) {
                timeline.append("- TIMEOUT waiting for autovacuum — dead tuples still high, note in README\n");
                break;
            }
            Thread.sleep(15_000L);
        }
        return timeline.toString();
    }
}
