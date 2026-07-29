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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Group R: recovery shapes, per pid class (MEDIAN / P99 / OUTLIER).
 * R1 latest-snapshot fetch; R2a tail replay from latest snapshot (real recovery — never more than
 * 499 events at the things snapshot cadence); R2b full replay from sn 1 (the design's
 * "outlier 50k events" bar); R3 highest sequence number.
 * Invoke: mvn -pl internal/utils/persistence-bench test -Dtest=RecoveryBench -DfailIfNoTests=false
 */
public final class RecoveryBench {

    @Test
    public void recovery() throws Exception {
        final CorpusGenerator gen = BenchConfig.corpus();
        final int n = BenchConfig.warmups() + BenchConfig.iterations();
        final List<Long> median = pickPids(gen, CorpusGenerator.PidClass.MEDIAN, n, 0);
        final List<Long> p99 = pickPids(gen, CorpusGenerator.PidClass.P99, n, 0);
        final List<Long> outlier = pickPids(gen, CorpusGenerator.PidClass.OUTLIER, n, 0);

        Connection pg = null;
        MongoClient mongoClient = null;
        MongoDatabase mongo = null;
        try {
            if (BenchConfig.runPg()) {
                pg = BenchConfig.openPg();
            }
            if (BenchConfig.runMongo()) {
                mongoClient = BenchConfig.openMongo();
                mongo = mongoClient.getDatabase(BenchConfig.mongoDbName());
            }
            r1(gen, pg, mongo, median, p99, outlier);
            r2(gen, pg, mongo, median, p99, outlier);
            r3(gen, pg, mongo, median, p99, outlier);
        } finally {
            if (pg != null) {
                pg.close();
            }
            if (mongoClient != null) {
                mongoClient.close();
            }
        }
    }

    // --- R1 -------------------------------------------------------------------------------------

    private static void r1(final CorpusGenerator gen, final Connection pg, final MongoDatabase mongo,
            final List<Long> median, final List<Long> p99, final List<Long> outlier) throws Exception {
        final List<List<String>> rows = new ArrayList<>();
        final StringBuilder plans = new StringBuilder();
        for (final var entry : List.of(new Object[]{"MEDIAN (no snapshot)", median},
                new Object[]{"P99", p99}, new Object[]{"OUTLIER", outlier})) {
            final String label = (String) entry[0];
            @SuppressWarnings("unchecked") final List<Long> idxs = (List<Long>) entry[1];
            BenchProtocol.Timing pgT = null;
            BenchProtocol.Timing mongoT = null;
            if (pg != null) {
                try (PreparedStatement ps = pg.prepareStatement(PersistenceShapes.PG_LOAD_SNAPSHOT)) {
                    pgT = BenchProtocol.measure(iteration -> {
                        ps.setString(1, gen.pid(idxs.get(iteration % idxs.size())));
                        ps.setLong(2, Long.MAX_VALUE);
                        ps.setLong(3, 0L);
                        ps.setObject(4, Instant.ofEpochMilli(PersistenceShapes.PG_MAX_WRITTEN_AT_MILLIS)
                                .atOffset(ZoneOffset.UTC));
                        ps.setObject(5, Instant.EPOCH.atOffset(ZoneOffset.UTC));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rs.getString("snapshot");
                            }
                        }
                    });
                }
            }
            if (mongo != null) {
                final MongoCollection<Document> snaps = mongo.getCollection(PersistenceShapes.SNAPS);
                mongoT = BenchProtocol.measure(iteration -> snaps
                        .find(PersistenceShapes.snapshotLoadFilter(
                                gen.pid(idxs.get(iteration % idxs.size())), Long.MAX_VALUE, Long.MAX_VALUE))
                        .sort(new Document("sn", -1).append("ts", -1))
                        .first());
            }
            rows.add(sideBySide(label, pgT, mongoT));
        }
        if (pg != null) {
            final String pid = gen.pid(p99.get(0));
            plans.append("### PG plan (P99 pid)\n\n").append(BenchProtocol.pgExplain(pg,
                    BenchProtocol.inlineLiterals(PersistenceShapes.PG_LOAD_SNAPSHOT,
                            pid, Long.MAX_VALUE, 0L,
                            Instant.ofEpochMilli(PersistenceShapes.PG_MAX_WRITTEN_AT_MILLIS)
                                    .atOffset(ZoneOffset.UTC),
                            Instant.EPOCH.atOffset(ZoneOffset.UTC))));
        }
        if (mongo != null) {
            final String pid = gen.pid(p99.get(0));
            plans.append("\n### Mongo plan (P99 pid)\n\n").append(BenchProtocol.mongoExplainFind(mongo,
                    PersistenceShapes.SNAPS,
                    PersistenceShapes.snapshotLoadFilter(pid, Long.MAX_VALUE, Long.MAX_VALUE),
                    new Document("sn", -1).append("ts", -1), null, 1));
        }
        BenchProtocol.writeEvidence("r1-latest-snapshot.md",
                BenchProtocol.runHeader("R1 — latest-snapshot fetch")
                        + resultTable(rows) + "\n" + plans);
    }

    // --- R2 -------------------------------------------------------------------------------------

    private static void r2(final CorpusGenerator gen, final Connection pg, final MongoDatabase mongo,
            final List<Long> median, final List<Long> p99, final List<Long> outlier) throws Exception {
        final List<List<String>> rows = new ArrayList<>();
        rows.add(sideBySide("R2a tail MEDIAN (~20 ev, no snapshot)",
                pg == null ? null : pgReplayTail(pg, gen, median),
                mongo == null ? null : mongoReplayTail(mongo, gen, median)));
        rows.add(sideBySide("R2a tail P99 (200-400 ev past sn-2000 snapshot)",
                pg == null ? null : pgReplayTail(pg, gen, p99),
                mongo == null ? null : mongoReplayTail(mongo, gen, p99)));
        rows.add(sideBySide("R2a tail OUTLIER (0-499 ev past latest snapshot)",
                pg == null ? null : pgReplayTail(pg, gen, outlier),
                mongo == null ? null : mongoReplayTail(mongo, gen, outlier)));
        rows.add(sideBySide("R2b full P99 (~2200-2400 ev from sn 1)",
                pg == null ? null : pgReplayFull(pg, gen, p99),
                mongo == null ? null : mongoReplayFull(mongo, gen, p99)));
        rows.add(sideBySide("R2b full OUTLIER (40k-50k ev from sn 1)",
                pg == null ? null : pgReplayFull(pg, gen, outlier),
                mongo == null ? null : mongoReplayFull(mongo, gen, outlier)));

        final StringBuilder plans = new StringBuilder();
        if (pg != null) {
            final String pid = gen.pid(outlier.get(0));
            plans.append("### PG plan (OUTLIER full replay)\n\n").append(BenchProtocol.pgExplain(pg,
                    BenchProtocol.inlineLiterals(PersistenceShapes.PG_REPLAY, pid, 1L, Long.MAX_VALUE)));
        }
        if (mongo != null) {
            final String pid = gen.pid(outlier.get(0));
            plans.append("\n### Mongo plan (OUTLIER full replay)\n\n")
                    .append(BenchProtocol.mongoExplainFind(mongo, PersistenceShapes.JOURNAL,
                            PersistenceShapes.replayFilter(pid, 1L, Long.MAX_VALUE),
                            new Document("to", 1), new Document("events", 1), null));
        }
        BenchProtocol.writeEvidence("r2-replay.md",
                BenchProtocol.runHeader("R2 — event replay (a: from latest snapshot, b: from sn 1)")
                        + resultTable(rows) + "\n" + plans
                        + "\nBar mapping (design section 7): the 'outlier 50k events < 2 s' bar applies to "
                        + "R2b full OUTLIER; real recovery is R2a (snapshots every 500 cap the tail at 499).\n");
    }

    public static BenchProtocol.Timing pgReplayTail(final Connection pg, final CorpusGenerator gen,
            final List<Long> pidIndexes) throws Exception {
        return pgReplay(pg, gen, pidIndexes, true);
    }

    public static BenchProtocol.Timing pgReplayFull(final Connection pg, final CorpusGenerator gen,
            final List<Long> pidIndexes) throws Exception {
        return pgReplay(pg, gen, pidIndexes, false);
    }

    private static BenchProtocol.Timing pgReplay(final Connection pg, final CorpusGenerator gen,
            final List<Long> pidIndexes, final boolean fromSnapshot) throws Exception {
        try (PreparedStatement ps = pg.prepareStatement(PersistenceShapes.PG_REPLAY)) {
            return BenchProtocol.measure(iteration -> {
                final long idx = pidIndexes.get(iteration % pidIndexes.size());
                ps.setString(1, gen.pid(idx));
                ps.setLong(2, fromSnapshot ? gen.latestSnapshotSn(idx) + 1 : 1L);
                ps.setLong(3, Long.MAX_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getString("event");
                    }
                }
            });
        }
    }

    public static BenchProtocol.Timing mongoReplayTail(final MongoDatabase db, final CorpusGenerator gen,
            final List<Long> pidIndexes) {
        return mongoReplay(db, gen, pidIndexes, true);
    }

    public static BenchProtocol.Timing mongoReplayFull(final MongoDatabase db, final CorpusGenerator gen,
            final List<Long> pidIndexes) {
        return mongoReplay(db, gen, pidIndexes, false);
    }

    private static BenchProtocol.Timing mongoReplay(final MongoDatabase db, final CorpusGenerator gen,
            final List<Long> pidIndexes, final boolean fromSnapshot) {
        final MongoCollection<Document> journal = db.getCollection(PersistenceShapes.JOURNAL);
        return BenchProtocol.measure(iteration -> {
            final long idx = pidIndexes.get(iteration % pidIndexes.size());
            final long from = fromSnapshot ? gen.latestSnapshotSn(idx) + 1 : 1L;
            journal.find(PersistenceShapes.replayFilter(gen.pid(idx), from, Long.MAX_VALUE))
                    .sort(new Document("to", 1))
                    .projection(new Document("events", 1))
                    .forEach(doc -> doc.getList("events", Document.class).size());
        });
    }

    // --- R3 -------------------------------------------------------------------------------------

    private static void r3(final CorpusGenerator gen, final Connection pg, final MongoDatabase mongo,
            final List<Long> median, final List<Long> p99, final List<Long> outlier) throws Exception {
        // shape is O(1) on both backends; measured over a class mix
        final List<Long> mixed = new ArrayList<>();
        for (int i = 0; i < Math.max(median.size(), Math.max(p99.size(), outlier.size())); i++) {
            mixed.add(median.get(i % median.size()));
            mixed.add(p99.get(i % p99.size()));
            mixed.add(outlier.get(i % outlier.size()));
        }
        BenchProtocol.Timing pgT = null;
        BenchProtocol.Timing mongoT = null;
        final StringBuilder plans = new StringBuilder();
        if (pg != null) {
            try (PreparedStatement ps = pg.prepareStatement(PersistenceShapes.PG_HIGHEST_SN)) {
                pgT = BenchProtocol.measure(iteration -> {
                    final String pid = gen.pid(mixed.get(iteration % mixed.size()));
                    ps.setString(1, pid);
                    ps.setString(2, pid);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        rs.getLong("hwm");
                    }
                });
            }
            final String pid = gen.pid(p99.get(0));
            plans.append("### PG plan\n\n").append(BenchProtocol.pgExplain(pg,
                    BenchProtocol.inlineLiterals(PersistenceShapes.PG_HIGHEST_SN, pid, pid)));
        }
        if (mongo != null) {
            final MongoCollection<Document> journal = mongo.getCollection(PersistenceShapes.JOURNAL);
            mongoT = BenchProtocol.measure(iteration -> {
                // plugin maxSequenceNr: journal max 'to' (metadata fallback never fires — journal non-empty)
                journal.find(new Document("pid", gen.pid(mixed.get(iteration % mixed.size()))))
                        .projection(new Document("to", 1))
                        .sort(PersistenceShapes.highestSnSort())
                        .limit(1)
                        .first();
            });
            final String pid = gen.pid(p99.get(0));
            plans.append("\n### Mongo plan\n\n").append(BenchProtocol.mongoExplainFind(mongo,
                    PersistenceShapes.JOURNAL, new Document("pid", pid),
                    PersistenceShapes.highestSnSort(), new Document("to", 1), 1));
        }
        BenchProtocol.writeEvidence("r3-highest-sn.md",
                BenchProtocol.runHeader("R3 — highest sequence number")
                        + resultTable(List.of(sideBySide("mixed classes", pgT, mongoT))) + "\n" + plans);
    }

    // --- shared helpers ---------------------------------------------------------------------------

    /** Finds up to n class pids; cycles the found list if the corpus is too small (documented in evidence). */
    public static List<Long> pickPids(final CorpusGenerator gen, final CorpusGenerator.PidClass cls,
            final int n, final long fromIndex) {
        final List<Long> found = gen.findPidIndexes(cls, n, fromIndex);
        if (found.isEmpty()) {
            throw new IllegalStateException("no pids of class " + cls + " in corpus of " + gen.pidCount());
        }
        final List<Long> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(found.get(i % found.size()));
        }
        return result;
    }

    static List<String> sideBySide(final String label, final BenchProtocol.Timing pg,
            final BenchProtocol.Timing mongo) {
        return List.of(label,
                pg == null ? "—" : pg.cells(),
                mongo == null ? "—" : mongo.cells());
    }

    static String resultTable(final List<List<String>> rows) {
        return BenchProtocol.table(
                List.of("scenario", "PG p50 | p95 | max (ms)", "Mongo p50 | p95 | max (ms)"), rows);
    }
}
