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
import java.util.List;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertOneModel;

/**
 * W1 single append txn (journal INSERT + journal_seq upsert in one txn vs 1-doc ordered bulkWrite
 * JOURNALED + async realtime insert) and W3 sustained churn (ChurnEngine).
 * Invoke: mvn -pl internal/utils/persistence-bench test -Dtest='AppendBench#w1SingleAppend' ...
 */
public final class AppendBench {

    @Test
    public void w1SingleAppend() throws Exception {
        BenchProtocol.Timing pgT = null;
        BenchProtocol.Timing mongoT = null;
        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg()) {
                c.setAutoCommit(false);
                try (PreparedStatement insert = c.prepareStatement(PersistenceShapes.PG_INSERT_EVENT);
                        PreparedStatement upsert = c.prepareStatement(PersistenceShapes.PG_UPSERT_SEQ)) {
                    final String pid = "thing:bench.w1:pg";
                    pgT = BenchProtocol.measure(iteration -> {
                        final long sn = iteration + 1L;
                        insert.setString(1, pid);
                        insert.setLong(2, sn);
                        insert.setString(3, CorpusGenerator.MANIFEST_ATTRIBUTE);
                        insert.setObject(4, c.createArrayOf("text", new String[0]));
                        insert.setString(5, ChurnEngine.churnEventJson(pid, sn));
                        insert.executeUpdate();
                        upsert.setString(1, pid);
                        upsert.setLong(2, sn);
                        upsert.executeUpdate();
                        c.commit();
                    });
                }
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoDatabase db = client.getDatabase(BenchConfig.mongoDbName());
                final MongoCollection<Document> journal =
                        db.getCollection(PersistenceShapes.JOURNAL).withWriteConcern(WriteConcern.JOURNALED);
                final MongoCollection<Document> realtime =
                        db.getCollection(PersistenceShapes.REALTIME).withWriteConcern(WriteConcern.JOURNALED);
                // daemon thread: an exception in measure(...) below must not leak a non-daemon thread
                // that pins the surefire JVM open.
                final java.util.concurrent.ExecutorService realtimeExecutor =
                        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            final Thread t = new Thread(r, "w1-realtime-append");
                            t.setDaemon(true);
                            return t;
                        });
                try {
                    final String pid = "thing:bench.w1:mongo";
                    mongoT = BenchProtocol.measure(iteration -> {
                        final long sn = iteration + 1L;
                        final Document atom = PersistenceShapes.journalAtom(pid, sn,
                                CorpusGenerator.MANIFEST_ATTRIBUTE,
                                Document.parse(ChurnEngine.churnEventJson(pid, sn)));
                        journal.bulkWrite(List.of(new InsertOneModel<>(atom)));
                        realtimeExecutor.submit(() -> realtime.insertOne(atom));
                    });
                } finally {
                    realtimeExecutor.shutdown();
                    realtimeExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
        BenchProtocol.writeEvidence("w1-single-append.md",
                BenchProtocol.runHeader("W1 — single append txn")
                        + RecoveryBench.resultTable(List.of(
                                RecoveryBench.sideBySide("append (journal + high-water-mark)", pgT, mongoT)))
                        + "\nPG: event INSERT + journal_seq upsert, one transaction (synchronous_commit=on). "
                        + "Mongo: ordered 1-doc bulkWrite, WriteConcern JOURNALED; the plugin's async "
                        + "realtime-collection insert runs fire-and-forget outside the timed path exactly "
                        + "as in ScalaDriverPersistenceJournaller.batchAppend (plan deviation 4). "
                        + "Mongo deleteMany/insert plans are not explainable — timings only.\n");
    }

    @Test
    public void w3SustainedChurn() throws Exception {
        final int duration = BenchConfig.writeDurationSeconds();
        final int rate = BenchConfig.writeTargetRate();
        final int workers = BenchConfig.writeWorkers();
        final int pidsPerWorker = BenchConfig.writePidsPerWorker();
        final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("W3 — sustained churn"))
                .append(String.format("- target rate %d/s, %d workers x %d pids, %d s per backend%n%n",
                        rate, workers, pidsPerWorker, duration));
        if (BenchConfig.runPg()) {
            final ChurnEngine.ChurnResult r =
                    ChurnEngine.runPg("thing:bench.churn", duration, rate, workers, pidsPerWorker);
            md.append("## PostgreSQL\n\n")
                    .append(String.format("- total ops %d (+%d snapshots), achieved %.1f/s%n%n",
                            r.totalOps(), r.totalSnapshots(), r.achievedRate()))
                    .append(ChurnEngine.samplesMarkdown(r)).append('\n');
        }
        if (BenchConfig.runMongo()) {
            final ChurnEngine.ChurnResult r =
                    ChurnEngine.runMongo("thing:bench.churn", duration, rate, workers, pidsPerWorker);
            md.append("## MongoDB\n\n")
                    .append(String.format("- total ops %d (+%d snapshots), achieved %.1f/s%n%n",
                            r.totalOps(), r.totalSnapshots(), r.achievedRate()))
                    .append(ChurnEngine.samplesMarkdown(r)).append('\n');
        }
        md.append("\nNote: standalone mongod has no oplog — WAL-delta column applies to PG only; "
                + "Mongo growth is tracked via collStats storage+index size (plan deviation list).\n");
        BenchProtocol.writeEvidence("w3-sustained-churn.md", md.toString());
    }
}
