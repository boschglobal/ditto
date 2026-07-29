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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;

/**
 * W2 snapshot write: ~4 KB state JSON. Two sub-scenarios: fresh insert (new sn each iteration)
 * and overwrite (same pid/sn/ts key every iteration — the upsert conflict path).
 * Invoke: mvn -pl internal/utils/persistence-bench test -Dtest=SnapshotBench -DfailIfNoTests=false
 */
public final class SnapshotBench {

    @Test
    public void w2SnapshotWrite() throws Exception {
        BenchProtocol.Timing pgFresh = null;
        BenchProtocol.Timing pgOverwrite = null;
        BenchProtocol.Timing mongoFresh = null;
        BenchProtocol.Timing mongoOverwrite = null;

        if (BenchConfig.runPg()) {
            try (Connection c = BenchConfig.openPg()) {
                c.setAutoCommit(true);
                try (PreparedStatement ps = c.prepareStatement(PersistenceShapes.PG_SAVE_SNAPSHOT)) {
                    final String pid = "thing:bench.w2:pg";
                    pgFresh = BenchProtocol.measure(iteration -> {
                        ps.setString(1, pid);
                        ps.setLong(2, iteration + 1L);
                        ps.setString(3, ChurnEngine.churnSnapshotJson(pid, iteration + 1L));
                        ps.setString(4, null);
                        ps.setObject(5, Instant.ofEpochMilli(CorpusGenerator.BASE_TIMESTAMP_MILLIS
                                + iteration * 1000L).atOffset(ZoneOffset.UTC));
                        ps.executeUpdate();
                    });
                    pgOverwrite = BenchProtocol.measure(iteration -> {
                        ps.setString(1, pid);
                        ps.setLong(2, 1L);
                        ps.setString(3, ChurnEngine.churnSnapshotJson(pid, 1L));
                        ps.setString(4, null);
                        ps.setObject(5, Instant.ofEpochMilli(CorpusGenerator.BASE_TIMESTAMP_MILLIS)
                                .atOffset(ZoneOffset.UTC));
                        ps.executeUpdate();
                    });
                }
            }
        }
        if (BenchConfig.runMongo()) {
            try (MongoClient client = BenchConfig.openMongo()) {
                final MongoCollection<Document> snaps = client.getDatabase(BenchConfig.mongoDbName())
                        .getCollection(PersistenceShapes.SNAPS).withWriteConcern(WriteConcern.JOURNALED);
                final String pid = "thing:bench.w2:mongo";
                mongoFresh = BenchProtocol.measure(iteration -> {
                    final long sn = iteration + 1L;
                    final long ts = CorpusGenerator.BASE_TIMESTAMP_MILLIS + iteration * 1000L;
                    snaps.replaceOne(new Document("pid", pid).append("sn", sn).append("ts", ts),
                            PersistenceShapes.snapshotDoc(pid, sn, ts,
                                    Document.parse(ChurnEngine.churnSnapshotJson(pid, sn))),
                            new ReplaceOptions().upsert(true));
                });
                mongoOverwrite = BenchProtocol.measure(iteration -> {
                    final long ts = CorpusGenerator.BASE_TIMESTAMP_MILLIS;
                    snaps.replaceOne(new Document("pid", pid).append("sn", 1L).append("ts", ts),
                            PersistenceShapes.snapshotDoc(pid, 1L, ts,
                                    Document.parse(ChurnEngine.churnSnapshotJson(pid, 1L))),
                            new ReplaceOptions().upsert(true));
                });
            }
        }
        BenchProtocol.writeEvidence("w2-snapshot-write.md",
                BenchProtocol.runHeader("W2 — snapshot write (~4 KB)")
                        + RecoveryBench.resultTable(List.of(
                                RecoveryBench.sideBySide("fresh insert (new sn per iteration)", pgFresh, mongoFresh),
                                RecoveryBench.sideBySide("overwrite (same pid/sn/ts key)", pgOverwrite, mongoOverwrite)))
                        + "\nPG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. "
                        + "Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED "
                        + "(ScalaDriverPersistenceSnapshotter.saveSnapshot).\n");
    }
}
