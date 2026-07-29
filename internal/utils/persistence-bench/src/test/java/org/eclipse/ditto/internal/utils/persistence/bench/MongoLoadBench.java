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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bson.Document;
import org.junit.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;

/**
 * Bulk-loads the corpus into MongoDB with the exact document layout and index set that
 * pekko-persistence-mongodb 1.5.0 + Ditto's MongoReadJournal create for the things service:
 * journal {pid, from, to, events:[{v,pid,sn,_t,p,manifest}], v}; snaps {pid, sn, ts, s2};
 * indexes things_journal_index{pid,from,to}unique, max_sequence_sort{pid,to:-1},
 * journal_tag_index{_tg}sparse, ditto_tag_pid{_tg,pid}sparse, things_snaps_index{pid,sn:-1,ts:-1}
 * unique, snaps_pid_sn_id_index{pid,sn:-1,_id}; metadata collection created empty (only written on
 * the Pekko deleteFrom path, which no scenario exercises); things_realtime capped 100MB, empty at
 * rest (a live-tail buffer would have evicted corpus-age docs).
 * Invoke via: mvn -pl internal/utils/persistence-bench test -Dtest=MongoLoadBench -DfailIfNoTests=false
 */
public final class MongoLoadBench {

    private static final int BATCH = 1_000;

    @Test
    public void load() throws Exception {
        if (!BenchConfig.runMongo()) {
            System.out.println("[bench] backend=" + BenchConfig.backend() + " — skipping Mongo load");
            return;
        }
        try (MongoClient client = BenchConfig.openMongo()) {
            run(client, BenchConfig.mongoDbName(), BenchConfig.corpus(), true);
        }
    }

    public static LoadStats run(final MongoClient client, final String dbName,
            final CorpusGenerator gen, final boolean writeEvidence) {
        final MongoDatabase db = client.getDatabase(dbName);
        final long t0 = System.nanoTime();

        for (final String c : List.of(PersistenceShapes.JOURNAL, PersistenceShapes.SNAPS,
                PersistenceShapes.REALTIME, PersistenceShapes.METADATA)) {
            db.getCollection(c).drop();
        }
        db.createCollection(PersistenceShapes.REALTIME,
                new CreateCollectionOptions().capped(true).sizeInBytes(104_857_600L));

        final MongoCollection<Document> journal = db.getCollection(PersistenceShapes.JOURNAL);
        final MongoCollection<Document> snaps = db.getCollection(PersistenceShapes.SNAPS);
        final InsertManyOptions unordered = new InsertManyOptions().ordered(false);

        final long tInsert0 = System.nanoTime();
        long journalRows = 0;
        long snapshotRows = 0;
        List<Document> batch = new ArrayList<>(BATCH);
        for (long i = 0; i < gen.pidCount(); i++) {
            final Iterator<CorpusGenerator.EventRow> events = gen.events(i);
            while (events.hasNext()) {
                final CorpusGenerator.EventRow e = events.next();
                batch.add(PersistenceShapes.journalAtom(e.pid(), e.sn(), e.manifest(),
                        Document.parse(e.eventJson())));
                journalRows++;
                if (batch.size() == BATCH) {
                    journal.insertMany(batch, unordered);
                    batch = new ArrayList<>(BATCH);
                }
                if (journalRows % 2_000_000 == 0) {
                    System.out.println("[bench] journal docs inserted: " + journalRows);
                }
            }
        }
        if (!batch.isEmpty()) {
            journal.insertMany(batch, unordered);
            batch = new ArrayList<>(BATCH);
        }
        for (long i = 0; i < gen.pidCount(); i++) {
            final Iterator<CorpusGenerator.SnapshotRow> it = gen.snapshots(i);
            while (it.hasNext()) {
                final CorpusGenerator.SnapshotRow s = it.next();
                batch.add(PersistenceShapes.snapshotDoc(s.pid(), s.sn(), s.timestampMillis(),
                        Document.parse(s.snapshotJson())));
                snapshotRows++;
                if (batch.size() == BATCH) {
                    snaps.insertMany(batch, unordered);
                    batch = new ArrayList<>(BATCH);
                }
            }
        }
        if (!batch.isEmpty()) {
            snaps.insertMany(batch, unordered);
        }
        final double insertSeconds = (System.nanoTime() - tInsert0) / 1e9;

        final long tIndex0 = System.nanoTime();
        journal.createIndex(Indexes.ascending("pid", "from", "to"),
                new IndexOptions().name("things_journal_index").unique(true));
        journal.createIndex(Indexes.compoundIndex(Indexes.ascending("pid"), Indexes.descending("to")),
                new IndexOptions().name("max_sequence_sort"));
        journal.createIndex(Indexes.ascending("_tg"),
                new IndexOptions().name("journal_tag_index").sparse(true));
        journal.createIndex(Indexes.ascending("_tg", "pid"),
                new IndexOptions().name("ditto_tag_pid").sparse(true));
        snaps.createIndex(Indexes.compoundIndex(Indexes.ascending("pid"),
                        Indexes.descending("sn"), Indexes.descending("ts")),
                new IndexOptions().name("things_snaps_index").unique(true));
        snaps.createIndex(Indexes.compoundIndex(Indexes.ascending("pid"),
                        Indexes.descending("sn"), Indexes.ascending("_id")),
                new IndexOptions().name("snaps_pid_sn_id_index"));
        db.getCollection(PersistenceShapes.METADATA).createIndex(Indexes.ascending("pid"),
                new IndexOptions().name("things_metadata_index").unique(true).sparse(true));
        final double indexSeconds = (System.nanoTime() - tIndex0) / 1e9;

        final LoadStats stats = new LoadStats(
                journal.countDocuments(),
                db.getCollection(PersistenceShapes.METADATA).countDocuments(),
                snaps.countDocuments());
        if (stats.journalRows() != gen.totalEvents() || stats.snapshotRows() != gen.totalSnapshots()) {
            throw new IllegalStateException("loaded counts do not match generator: " + stats
                    + " vs events=" + gen.totalEvents() + " snaps=" + gen.totalSnapshots());
        }

        if (writeEvidence) {
            final StringBuilder md = new StringBuilder(BenchProtocol.runHeader("Load evidence — MongoDB"));
            md.append("- journal docs: ").append(stats.journalRows()).append('\n');
            md.append("- snapshot docs: ").append(stats.snapshotRows()).append('\n');
            md.append("- metadata docs: 0 (only written on the Pekko deleteFrom path — see plan deviation 6)\n");
            md.append(String.format("- insertMany: %.1f s; index build: %.1f s; total: %.1f s%n%n",
                    insertSeconds, indexSeconds, (System.nanoTime() - t0) / 1e9));
            md.append("## Sizes (collStats)\n\n").append(sizesMarkdown(db));
            BenchProtocol.writeEvidence("load-mongo.md", md.toString());
        }
        return stats;
    }

    static String sizesMarkdown(final MongoDatabase db) {
        final List<List<String>> rows = new ArrayList<>();
        for (final String c : List.of(PersistenceShapes.JOURNAL, PersistenceShapes.SNAPS,
                PersistenceShapes.REALTIME, PersistenceShapes.METADATA)) {
            final Document stats = db.runCommand(new Document("collStats", c));
            rows.add(List.of(c,
                    String.valueOf(stats.get("count")),
                    mb(((Number) stats.getOrDefault("size", 0)).longValue()),
                    mb(((Number) stats.getOrDefault("storageSize", 0)).longValue()),
                    mb(((Number) stats.getOrDefault("totalIndexSize", 0)).longValue())));
        }
        return BenchProtocol.table(
                List.of("collection", "docs", "dataSize", "storageSize", "totalIndexSize"), rows);
    }

    private static String mb(final long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
