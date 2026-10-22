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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /** things.conf history-retention-duration default. */
    private static final Duration RETENTION = Duration.ofDays(3);

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

    /**
     * A fresh-share corpus: both loaders write the same ages (PG written_at, Mongo snapshot _id), and the
     * production-shaped cleanup pages return the generator's expected rows with the min age off and on.
     */
    @Test
    public void aFreshShareCorpusHasTheSameAgeMixAndCleanupPagesOnBothBackends() throws Exception {
        final CorpusGenerator gen =
                new CorpusGenerator(SEED, COUNT, 0.3, BenchConfig.defaultFreshBaseMillis(Instant.now()));
        final NewestSnapshotOracle oracle = NewestSnapshotOracle.of(gen);
        final long fresh = firstSnapshotted(gen, true);
        final long old = firstSnapshotted(gen, false);
        final String midPid = oracle.pgPage("", false, (int) oracle.snapshottedPids() / 2)
                .get((int) oracle.snapshottedPids() / 2 - 1).pid();
        final List<String> lowerBounds = List.of("", midPid);
        final int limit = PersistenceShapes.READS_PER_QUERY;
        assertThat(oracle.pgPage("", true, Integer.MAX_VALUE)).as("corpus large enough for a full page")
                .hasSizeGreaterThan(limit);

        final Properties props = new Properties();
        props.setProperty("user", "bench");
        props.setProperty("password", "bench");
        props.setProperty("prepareThreshold", "0");
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), props)) {
            PgLoadBench.run(connection, gen, false);

            assertThat(CleanupShapeBench.pgAgeMix(connection, RETENTION))
                    .containsExactly(oracle.snapshottedPids(), oracle.freshOnlySnapshottedPids());
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT (extract(epoch FROM written_at) * 1000)::bigint AS ts FROM things_snaps "
                            + "WHERE pid = ? AND sn = ?")) {
                for (final long idx : List.of(fresh, old)) {
                    ps.setString(1, gen.pid(idx));
                    ps.setLong(2, gen.latestSnapshotSn(idx));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong("ts")).isEqualTo(gen.timestampMillis(idx, gen.latestSnapshotSn(idx)));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)) {
                for (final String lb : lowerBounds) {
                    assertThat(CleanupShapeBench.pgPage(ps, lb, Duration.ZERO, limit))
                            .isEqualTo(oracle.pgPage(lb, false, limit));
                    assertThat(CleanupShapeBench.pgPage(ps, lb, RETENTION, limit))
                            .isEqualTo(oracle.pgPage(lb, true, limit))
                            .noneMatch(e -> e.pid().equals(gen.pid(fresh)));
                }
            }
            // NOT PRODUCTION what-if: the same pages without the payload; the decoded read; the payload size helper
            try (PreparedStatement ps = connection.prepareStatement(
                    CleanupShapeBench.PG_PAGE_WITHOUT_PAYLOAD_NOT_PRODUCTION)) {
                for (final String lb : lowerBounds) {
                    assertThat(CleanupShapeBench.pgPage(ps, lb, Duration.ZERO, limit))
                            .isEqualTo(oracle.pgPage(lb, false, limit));
                    assertThat(CleanupShapeBench.pgPage(ps, lb, RETENTION, limit))
                            .isEqualTo(oracle.pgPage(lb, true, limit));
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)) {
                assertThat(CleanupShapeBench.pgPageDecoded(ps, "", RETENTION, limit))
                        .isEqualTo(oracle.pgPage("", true, limit));
            }
            final long[] rowsAndBytes = CleanupShapeBench.pgPagePayloadBytes(connection, "", RETENTION, limit);
            assertThat(rowsAndBytes[0]).isEqualTo(limit);
            // corpus snapshots carry 2-10 KB of JSON each (CorpusGenerator targetBytes)
            assertThat(rowsAndBytes[1]).isGreaterThan(limit * 1_024L);
            // NOT PRODUCTION what-if, cache states: I/O snapshot, server start and warm pass on a real server
            try (Statement st = connection.createStatement()) {
                st.execute("SET track_io_timing = on");
            }
            assertThat(CleanupShapeBench.pgServerStart(connection)).contains("Z, ").endsWith(" s before this sample");
            try (PreparedStatement ps = connection.prepareStatement(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE)) {
                final CleanupShapeBench.WarmPass pass = CleanupShapeBench.pgWarmPass(connection, ps, limit);
                assertThat(pass.pids()).isEqualTo(oracle.snapshottedPids());
                assertThat(pass.pages()).isEqualTo((int) ((oracle.snapshottedPids() + limit - 1) / limit));
                assertThat(pass.blocks().hits() + pass.blocks().reads()).isPositive();
                assertThat(pass.blocks().readTimeMs()).isGreaterThanOrEqualTo(0.0);
                // after the pass every page is in shared buffers: two pages hit, (almost) nothing is read
                final CleanupShapeBench.PgIo afterPass = CleanupShapeBench.pgIo(connection);
                for (final String lb : lowerBounds) {
                    CleanupShapeBench.pgPage(ps, lb, Duration.ZERO, limit);
                }
                final CleanupShapeBench.PgIo warmReads = CleanupShapeBench.pgIo(connection).minus(afterPass);
                assertThat(warmReads.hits()).isPositive();
                assertThat(CleanupShapeBench.cacheStateCheck(CleanupShapeBench.CacheState.WARM, warmReads)).isEmpty();
            }
        }

        final String mongoUri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017);
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoLoadBench.run(client, "bench", gen, false);
            final MongoCollection<Document> snaps =
                    client.getDatabase("bench").getCollection(PersistenceShapes.SNAPS);

            assertThat(CleanupShapeBench.mongoAgeMix(snaps, RETENTION, Instant.now()))
                    .containsExactly(oracle.snapshottedPids(), oracle.freshOnlySnapshottedPids());
            for (final long idx : List.of(fresh, old)) {
                final Document newest = snaps.find(new Document("pid", gen.pid(idx))
                        .append("sn", gen.latestSnapshotSn(idx))).first();
                assertThat(newest).isNotNull();
                final long ts = gen.timestampMillis(idx, gen.latestSnapshotSn(idx));
                assertThat(newest.getLong("ts")).isEqualTo(ts);
                assertThat(newest.getObjectId("_id").getDate().getTime()).isEqualTo(ts / 1000L * 1000L);
            }
            for (final String lb : lowerBounds) {
                assertThat(CleanupShapeBench.mongoPage(snaps, lb, Duration.ZERO, limit))
                        .isEqualTo(oracle.mongoPage(lb, false, limit));
                assertThat(CleanupShapeBench.mongoPage(snaps, lb, RETENTION, limit))
                        .isEqualTo(oracle.mongoPage(lb, true, limit))
                        .noneMatch(e -> e.pid().equals(gen.pid(fresh)));
            }
        }
    }

    @Test
    public void aBoundedCreditPacedSweepStopsAtItsDeadlineAndRecordsOnlyCompletelySweptPids() throws Exception {
        final CorpusGenerator gen = new CorpusGenerator(SEED, COUNT);
        final Properties props = new Properties();
        props.setProperty("user", "bench");
        props.setProperty("password", "bench");
        props.setProperty("prepareThreshold", "0");
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), props)) {
            PgLoadBench.run(connection, gen, false);

            // credits pace: 3 batches per 3 s window. The bound is checked before each batch's credit wait, so a 7 s
            // bound allows the windows at 0, 3 and 6 s plus at most one batch that waited past the bound.
            final SweepEngine.SweepResult bounded =
                    SweepEngine.sweepPg(connection, true, new AtomicBoolean(false), Duration.ofSeconds(7));
            assertThat(bounded.deadlineReached()).isTrue();
            assertThat(bounded.batches()).isBetween(1L, 10L);
            assertThat(bounded.sweptPids()).hasSize((int) bounded.pids());
            assertSweptDownToTheirSnapshot(connection, bounded.sweptPids());

            final SweepEngine.SweepResult rest =
                    SweepEngine.sweepPg(connection, false, new AtomicBoolean(false), Duration.ZERO);
            assertThat(rest.deadlineReached()).isFalse();
            assertThat(rest.sweptPids()).isNotEmpty().hasSize((int) rest.pids());
            assertSweptDownToTheirSnapshot(connection, rest.sweptPids());
        }
    }

    private static void assertSweptDownToTheirSnapshot(final Connection connection,
            final List<SweepEngine.SweptPid> swept) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(PersistenceShapes.PG_MIN_EVENT_SN)) {
            for (final SweepEngine.SweptPid p : swept) {
                ps.setString(1, p.pid());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("sn")).as(p.pid() + " swept down to its snapshot")
                            .isEqualTo(p.snapshotSn());
                }
            }
        }
    }

    private static long firstSnapshotted(final CorpusGenerator gen, final boolean freshOnly) {
        for (long i = 0; i < gen.pidCount(); i++) {
            if (gen.snapshotCount(i) > 0 && gen.isFreshOnly(i) == freshOnly) {
                return i;
            }
        }
        throw new IllegalStateException("no snapshotted pid with freshOnly=" + freshOnly);
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
