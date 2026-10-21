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
import java.time.Instant;
import java.time.OffsetDateTime;
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
import com.mongodb.client.MongoDatabase;

/**
 * The scenario plan captures run their statements (PostgreSQL: EXPLAIN ANALYZE inside a rolled-back transaction;
 * MongoDB: the explain command) and leave the corpus exactly as it was.
 */
public final class PlanCaptureIT {

    private static final CorpusGenerator GEN = new CorpusGenerator(42L, 2_000L);

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> mongo;
    private static long idx;

    @BeforeClass
    public static void startContainersAndLoad() throws Exception {
        assumeTrue("Docker is not available - skipping", DockerClientFactory.instance().isDockerAvailable());
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("bench").withUsername("bench").withPassword("bench");
        postgres.start();
        mongo = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);
        mongo.start();
        idx = ScenarioPlans.lastSnapshottedPidIndex(GEN);
        try (Connection c = pg()) {
            PgLoadBench.run(c, GEN, false);
        }
        try (MongoClient client = mongoClient()) {
            MongoLoadBench.run(client, "bench", GEN, false);
        }
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
    public void pgAppendPlanRunsTheTransactionAndRollsItBack() throws Exception {
        final String pid = GEN.pid(idx);
        try (Connection c = pg()) {
            final long[] before = pgCounts(c, pid);
            final String plan = ScenarioPlans.pgAppend(c, pid, ScenarioPlans.pgNextSn(c, pid));
            assertThat(plan).containsPattern("Insert on things_journal\\s+\\(")
                    .containsPattern("Insert on things_journal_seq\\s+\\(")
                    .contains("Conflict Resolution: UPDATE").contains("Conflicting Tuples: 1")
                    .contains("actual time=").doesNotContain("EXPLAIN failed");
            assertThat(pgCounts(c, pid)).containsExactly(before);
            assertThat(c.getAutoCommit()).isTrue();
        }
    }

    @Test
    public void pgSnapshotWritePlansCoverFreshAndOverwriteAndChangeNothing() throws Exception {
        final String pid = GEN.pid(idx);
        final long sn = GEN.latestSnapshotSn(idx);
        try (Connection c = pg()) {
            final long[] before = pgCounts(c, pid);
            final Instant writtenAt = pgWrittenAt(c, pid, sn);
            final String xminBefore = pgXmin(c, pid, sn);
            final String fresh = ScenarioPlans.pgSnapshotWrite(c, pid, sn + 1_000L, writtenAt.plusSeconds(1));
            final String overwrite = ScenarioPlans.pgSnapshotWrite(c, pid, sn, writtenAt);
            assertThat(fresh).containsPattern("Insert on things_snaps\\s+\\(").contains("Conflicting Tuples: 0")
                    .doesNotContain("EXPLAIN failed");
            assertThat(overwrite).contains("Conflict Resolution: UPDATE").contains("Conflicting Tuples: 1")
                    .doesNotContain("EXPLAIN failed");
            assertThat(pgCounts(c, pid)).containsExactly(before);
            assertThat(pgXmin(c, pid, sn)).isEqualTo(xminBefore);
        }
    }

    @Test
    public void pgCleanupPlanDeletesTheFirstBatchOnlyInsideTheRollback() throws Exception {
        final String pid = GEN.pid(idx);
        try (Connection c = pg()) {
            final long[] before = pgCounts(c, pid);
            final String plan = ScenarioPlans.pgCleanupPid(c, pid, GEN.latestSnapshotSn(idx));
            assertThat(plan).containsPattern("Delete on things_journal\\s+\\(")
                    .containsPattern("Delete on things_snaps\\s+\\(")
                    .contains("actual time=").doesNotContain("EXPLAIN failed");
            assertThat(pgCounts(c, pid)).containsExactly(before);
        }
    }

    @Test
    public void pgReadPlansCoverThePageAndTheReplay() throws Exception {
        try (Connection c = pg()) {
            assertThat(ScenarioPlans.pgPage(c, "")).contains("things_snaps").contains("actual time=")
                    .doesNotContain("EXPLAIN failed");
            assertThat(ScenarioPlans.pgReplay(c, GEN.pid(idx), 1L)).contains("things_journal")
                    .contains("actual time=").doesNotContain("EXPLAIN failed");
        }
    }

    @Test
    public void mongoCleanupExplainsShowIndexPlansAndDeleteNothing() {
        final String pid = GEN.pid(idx);
        try (MongoClient client = mongoClient()) {
            final MongoDatabase db = client.getDatabase("bench");
            final long journalBefore = count(db, PersistenceShapes.JOURNAL, pid);
            final long snapsBefore = count(db, PersistenceShapes.SNAPS, pid);
            final String plan = ScenarioPlans.mongoCleanupPid(db, pid, GEN.latestSnapshotSn(idx));
            assertThat(plan).contains("// winningPlan").contains("// executionStats (summary)").contains("IXSCAN")
                    .contains("nWouldDelete").doesNotContain("explain failed");
            assertThat(count(db, PersistenceShapes.JOURNAL, pid)).isEqualTo(journalBefore);
            assertThat(count(db, PersistenceShapes.SNAPS, pid)).isEqualTo(snapsBefore);
            System.out.println("[it] mongo cleanup explain:\n" + plan);
        }
    }

    @Test
    public void mongoSnapshotUpsertExplainWritesNothing() {
        final String pid = GEN.pid(idx);
        final long sn = GEN.latestSnapshotSn(idx);
        try (MongoClient client = mongoClient()) {
            final MongoDatabase db = client.getDatabase("bench");
            final MongoCollection<Document> snaps = db.getCollection(PersistenceShapes.SNAPS);
            final Document existing = snaps.find(new Document("pid", pid).append("sn", sn)).first();
            assertThat(existing).isNotNull();
            final long ts = existing.getLong("ts");
            final String fresh = ScenarioPlans.mongoSnapshotWrite(db, pid, sn + 1_000L, ts + 1_000L);
            final String overwrite = ScenarioPlans.mongoSnapshotWrite(db, pid, sn, ts);
            assertThat(fresh).contains("// winningPlan").doesNotContain("explain failed");
            assertThat(overwrite).contains("// winningPlan").doesNotContain("explain failed");
            assertThat(snaps.countDocuments(new Document("pid", pid).append("sn", sn + 1_000L))).isZero();
            assertThat(snaps.find(new Document("pid", pid).append("sn", sn)).first()).isEqualTo(existing);
            System.out.println("[it] mongo snapshot upsert explain:\n" + overwrite);
        }
    }

    private static Connection pg() throws Exception {
        final Properties props = new Properties();
        props.setProperty("user", "bench");
        props.setProperty("password", "bench");
        props.setProperty("prepareThreshold", "0");
        return DriverManager.getConnection(postgres.getJdbcUrl(), props);
    }

    private static MongoClient mongoClient() {
        return MongoClients.create("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017));
    }

    /** {journal rows, snapshot rows, journal_seq highest_sn (-1 if none)} of one pid. */
    private static long[] pgCounts(final Connection c, final String pid) throws Exception {
        return new long[]{
                Long.parseLong(pgText(c, "SELECT count(*) FROM things_journal WHERE pid = ?", pid)),
                Long.parseLong(pgText(c, "SELECT count(*) FROM things_snaps WHERE pid = ?", pid)),
                Long.parseLong(pgText(c, "SELECT coalesce(max(highest_sn), -1) FROM things_journal_seq WHERE pid = ?",
                        pid))};
    }

    private static String pgText(final Connection c, final String sql, final String pid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Instant pgWrittenAt(final Connection c, final String pid, final long sn) throws Exception {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT written_at FROM things_snaps WHERE pid = ? AND sn = ?")) {
            ps.setString(1, pid);
            ps.setLong(2, sn);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("snapshot %s/%s exists", pid, sn).isTrue();
                return rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static String pgXmin(final Connection c, final String pid, final long sn) throws Exception {
        return pgText(c, "SELECT xmin::text FROM things_snaps WHERE pid = ? AND sn = " + sn, pid);
    }

    private static long count(final MongoDatabase db, final String collection, final String pid) {
        return db.getCollection(collection).countDocuments(new Document("pid", pid));
    }
}
