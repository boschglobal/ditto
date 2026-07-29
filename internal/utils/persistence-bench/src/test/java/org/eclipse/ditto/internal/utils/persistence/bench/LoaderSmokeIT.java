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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.bson.Document;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

/**
 * Smoke-validates both loaders + generator determinism on a 5k corpus against throwaway
 * containers. The only CI-visible entry point of this module's bench code (self-skips without
 * Docker); everything else runs only via benchmark/run-benchmark.sh.
 */
public final class LoaderSmokeIT {

    private static final long SEED = 42L;
    private static final long COUNT = 5_000L;

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> mongo;

    @BeforeClass
    public static void startContainers() {
        assumeTrue("Docker is not available - skipping", DockerClientFactory.instance().isDockerAvailable());
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("bench").withUsername("bench").withPassword("bench");
        postgres.start();
        mongo = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);
        mongo.start();
    }

    @AfterClass
    public static void stopContainers() {
        if (postgres != null) {
            postgres.stop();
        }
        if (mongo != null) {
            mongo.stop();
        }
    }

    @Test
    public void loadersAgreeWithGeneratorAndEachOther() throws Exception {
        final CorpusGenerator gen = new CorpusGenerator(SEED, COUNT);

        final long idx = deepestPidIndex(gen);
        final String pid = gen.pid(idx);

        final Properties props = new Properties();
        props.setProperty("user", "bench");
        props.setProperty("password", "bench");
        props.setProperty("prepareThreshold", "0");
        final LoadStats pgStats;
        final long pgLatestSnapSn;
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), props)) {
            pgStats = PgLoadBench.run(connection, gen, false);

            assertThat(pgStats.journalRows()).isEqualTo(gen.totalEvents());
            assertThat(pgStats.auxRows()).isEqualTo(COUNT);
            assertThat(pgStats.snapshotRows()).isEqualTo(gen.totalSnapshots());

            // spot-check the deepest pid end-to-end on PG (class-based picks are probabilistic at 5k;
            // the deepest pid always exists and is virtually certain to carry snapshots)
            try (PreparedStatement ps = connection.prepareStatement(PersistenceShapes.PG_REPLAY)) {
                ps.setString(1, pid);
                ps.setLong(2, 1L);
                ps.setLong(3, Long.MAX_VALUE);
                final List<Long> sns = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sns.add(rs.getLong("sn"));
                    }
                }
                assertThat(sns).hasSize((int) gen.depth(idx));
                assertThat(sns.get(0)).isEqualTo(1L);
                assertThat(sns.get(sns.size() - 1)).isEqualTo(gen.depth(idx));
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT sn FROM things_snaps WHERE pid = ? ORDER BY sn DESC, written_at DESC LIMIT 1")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    pgLatestSnapSn = rs.getLong("sn");
                }
            }
            assertThat(pgLatestSnapSn).isEqualTo(gen.latestSnapshotSn(idx));
        }

        final String mongoUri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017);
        try (MongoClient client = MongoClients.create(mongoUri)) {
            final LoadStats mongoStats = MongoLoadBench.run(client, "bench", gen, false);

            assertThat(mongoStats.journalRows()).isEqualTo(pgStats.journalRows());
            assertThat(mongoStats.snapshotRows()).isEqualTo(pgStats.snapshotRows());
            assertThat(mongoStats.auxRows()).isZero();

            // same spot-check pid on Mongo: replay shape returns the same sn sequence
            final MongoCollection<Document> journal =
                    client.getDatabase("bench").getCollection(PersistenceShapes.JOURNAL);
            final List<Long> sns = new ArrayList<>();
            journal.find(PersistenceShapes.replayFilter(pid, 1L, Long.MAX_VALUE))
                    .sort(new Document("to", 1))
                    .projection(new Document("events", 1))
                    .forEach(doc -> {
                        for (final Object e : doc.getList("events", Document.class)) {
                            sns.add(((Document) e).getLong("sn"));
                        }
                    });
            assertThat(sns).hasSize((int) gen.depth(idx));
            assertThat(sns.get(sns.size() - 1)).isEqualTo(gen.depth(idx));

            // latest-snapshot agreement: PG == Mongo == generator
            final Document newest = client.getDatabase("bench")
                    .getCollection(PersistenceShapes.SNAPS)
                    .find(PersistenceShapes.snapshotLoadFilter(pid, Long.MAX_VALUE, Long.MAX_VALUE))
                    .sort(new Document("sn", -1).append("ts", -1))
                    .first();
            assertThat(newest).isNotNull();
            assertThat(newest.getLong("sn")).isEqualTo(gen.latestSnapshotSn(idx));
            assertThat(newest.getLong("sn")).isEqualTo(pgLatestSnapSn);
        }
    }

    private static long deepestPidIndex(final CorpusGenerator gen) {
        long best = 0;
        for (long i = 1; i < gen.pidCount(); i++) {
            if (gen.depth(i) > gen.depth(best)) {
                best = i;
            }
        }
        if (gen.latestSnapshotSn(best) == 0) {
            throw new IllegalStateException("deepest pid has no snapshot — corpus too small");
        }
        return best;
    }

    @Test
    public void generatorIsDeterministicAcrossInstances() {
        final CorpusGenerator a = new CorpusGenerator(SEED, COUNT);
        final CorpusGenerator b = new CorpusGenerator(SEED, COUNT);
        assertThat(a.totalEvents()).isEqualTo(b.totalEvents());
        assertThat(a.totalSnapshots()).isEqualTo(b.totalSnapshots());
        for (long i = 0; i < COUNT; i += 61) {
            assertThat(a.pid(i)).isEqualTo(b.pid(i));
            assertThat(a.depth(i)).isEqualTo(b.depth(i));
        }
    }
}
