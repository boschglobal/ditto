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
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.testkit.PostgresDbResource;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.ThingFlattener;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.write.PostgresSearchUpdaterFlow;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.thingsearch.persistence.api.model.ThingWriteModel;
import org.eclipse.ditto.thingsearch.persistence.api.write.SearchWriteResultStatus;
import org.eclipse.ditto.thingsearch.persistence.api.write.UpdaterData;
import org.eclipse.ditto.thingsearch.persistence.api.write.UpdaterResult;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The write-soak harness against a real PostgreSQL (Testcontainers): the COPY-loaded corpus is exactly what the
 * shipped writer stores, and a short soak writes through the shipped flow and samples every relation.
 * Skipped when Docker is unreachable.
 */
public final class WriteSoakIT {

    private static final PostgresDbResource POSTGRES = new PostgresDbResource();
    private static final long SEED = 7L;
    private static ActorSystem system;
    private static DittoPostgresClient client;

    @BeforeClass
    public static void startContainer() {
        try {
            POSTGRES.start();
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker/Testcontainers unavailable — skipping WriteSoakIT", t);
        }
        system = ActorSystem.create("WriteSoakIT");
        client = DittoPostgresClient.newInstance(ProductionSearchDb.shippedConfig(POSTGRES.getR2dbcUrl(),
                PostgresDbResource.DDL_USER, PostgresDbResource.DDL_PASSWORD));
    }

    @AfterClass
    public static void stopContainer() {
        if (client != null) {
            client.close();
        }
        if (system != null) {
            system.terminate();
        }
        POSTGRES.stop();
    }

    @Test
    public void aResumedLoadHoldsExactlyWhatTheShippedWriterStoresForTheSameThings() throws Exception {
        final SoakTwin twin = new SoakTwin(SEED);
        try (Connection connection = jdbc()) {
            ProductionConfigSchema.dropAll(connection);
            ProductionConfigSchema.applyPreLoad(connection);
            SoakCorpus.copy(connection, twin, 0, 60, 25, line -> { });
            assertThatIllegalStateException().isThrownBy(() -> SoakCorpus.requireResumable(connection, 59))
                    .withMessageContaining("holds 60");

            final SoakCorpus.LoadReport report = SoakCorpus.load(connection, twin, 120, 60, 25, "64MB", line -> { });

            assertThat(report.catalog().matchesProduction()).as(report.catalog().toString()).isTrue();
            assertThat(report.things()).isEqualTo(60);
            assertThat(scalar(connection, "SELECT count(*) FROM search_things")).isEqualTo(120L);
            final long longTextThings = java.util.stream.LongStream.range(0, 120)
                    .filter(index -> twin.thing(index, 1).getValue(JsonPointer.of("/attributes/notes")).isPresent())
                    .count();
            assertThat(longTextThings).isPositive();
            assertThat(toastedValues(connection)).as("every long-text thing's document row is stored out of line")
                    .isGreaterThanOrEqualTo(longTextThings);

            final Map<String, String> ctids = flatCtids(connection);
            final List<String> documents = documentRows(connection);
            for (long index = 0; index < 120; index++) {
                assertThat(write(twin.writeModel(index, 1)).result().classify()).isEqualTo(SearchWriteResultStatus.OK);
            }
            assertThat(flatCtids(connection)).as("the shipped anti-join found nothing to change").isEqualTo(ctids);
            assertThat(documentRows(connection)).isEqualTo(documents);

            final SoakCorpus.LoadReport postLoadOnly = SoakCorpus.load(connection, twin, 120, 120, 25, null,
                    line -> { });
            assertThat(postLoadOnly.things()).as("a load that failed after its last COPY resumes at the count")
                    .isZero();
            assertThat(postLoadOnly.catalog().matchesProduction()).as(postLoadOnly.catalog().toString()).isTrue();

            CopyLoader.copyIn(connection, "COPY search_things " + CopyLoader.THINGS_COLUMNS + " FROM STDIN",
                    LongStream.range(120, 145)
                            .mapToObj(index -> CopyTextFormat.documentLine(twin.writeModel(index, 1).getDocument()))
                            .iterator());
            assertThatIllegalStateException().as("interrupted between a chunk's two COPYs")
                    .isThrownBy(() -> SoakCorpus.requireResumable(connection, 145))
                    .withMessageContaining(SoakTwin.thingId(144) + " has no flat rows");
        }
    }

    @Test
    public void aSoakWritesThroughTheShippedFlowAndSamplesEveryRelation() throws Exception {
        final SoakTwin twin = new SoakTwin(SEED);
        final long count = 200;
        try (Connection connection = jdbc()) {
            SoakCorpus.load(connection, twin, count, 0, 100, null, line -> { });
            SoakCorpus.requireCorpusOf(connection, twin, count);
            assertThatIllegalStateException()
                    .isThrownBy(() -> SoakCorpus.requireCorpusOf(connection, twin, count + 1));
            assertThatIllegalStateException().as("a corpus loaded with another seed")
                    .isThrownBy(() -> SoakCorpus.requireCorpusOf(connection, new SoakTwin(SEED + 1), count))
                    .withMessageContaining("seed " + (SEED + 1));
            final Map<String, String> ctidsBefore = flatCtids(connection);

            final WriteSoakRunner.Settings settings = new WriteSoakRunner.Settings(count, SEED, 4, 40.0,
                    Duration.ofSeconds(3), Duration.ofSeconds(9), Duration.ofSeconds(9), Duration.ofSeconds(3),
                    Duration.ofSeconds(1));
            settings.requireWorkersWithin(25);
            final List<SoakAnalysis.Window> streamed = new ArrayList<>();
            final WriteSoakRunner.Result result = WriteSoakRunner.run(settings, twin, client, system,
                    WriteSoakIT::jdbc, streamed::add);

            assertThat(result.errors()).as(result.errorSamples().toString()).isZero();
            assertThat(result.pollerErrors()).isEmpty();
            assertThat(result.updates()).isGreaterThan(100);
            assertThat(result.windows()).hasSize(4).isEqualTo(streamed);
            assertThat(result.windows().get(0).gated()).isFalse();
            assertThat(result.windows().subList(1, 4)).allMatch(SoakAnalysis.Window::gated);
            final SoakAnalysis.Window last = result.windows().get(3);
            assertThat(last.relations()).containsKeys(SoakAnalysis.SEARCH_FLAT, SoakAnalysis.SEARCH_THINGS,
                    SoakAnalysis.SEARCH_THINGS_TOAST);
            assertThat(last.relations().get(SoakAnalysis.SEARCH_FLAT).totalBytes()).isPositive();
            assertThat(last.relations().get(SoakAnalysis.SEARCH_THINGS_TOAST).liveTuples()).isPositive();
            assertThat(last.indexBytes()).containsKeys("search_flat_pkey", "sf_text", "sf_trgm", "sf_multi",
                    "st_modified");
            assertThat(result.windows().stream().mapToLong(SoakAnalysis.Window::walBytes).sum()).isPositive();

            int soaked = 0;
            for (long index = 0; index < count; index++) {
                final int revision = result.revision(index);
                if (revision > 1) {
                    assertThat(flatKeys(connection, SoakTwin.thingId(index))).as("thing %d at revision %d", index,
                            revision).isEqualTo(expectedFlatKeys(twin.writeModel(index, revision)));
                    soaked++;
                }
            }
            assertThat(soaked).isGreaterThan(50);

            final Map<String, String> ctidsAfter = flatCtids(connection);
            ctidsBefore.forEach((key, ctid) -> {
                if (!SoakTwin.isRewrittenOnUpdate(key.split("\\|", -1)[1])) {
                    assertThat(ctidsAfter.get(key)).as("untouched row %s keeps its ctid", key).isEqualTo(ctid);
                }
            });

            assertThatIllegalStateException().as("a second soak on this corpus would replay the first one's writes")
                    .isThrownBy(() -> SoakCorpus.requireCorpusOf(connection, twin, count))
                    .withMessageContaining("is at revision");
        }
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getPort() + "/"
                + PostgresDbResource.DATABASE, PostgresDbResource.DDL_USER, PostgresDbResource.DDL_PASSWORD);
    }

    private static UpdaterResult write(final ThingWriteModel model) throws Exception {
        return Source.single(new UpdaterData(model, model))
                .via(PostgresSearchUpdaterFlow.of(client).create())
                .runWith(Sink.head(), system)
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
    }

    /** {@code thing_id|path|wpath|ord} -> ctid of every flat row. */
    private static Map<String, String> flatCtids(final Connection connection) throws SQLException {
        final Map<String, String> ctids = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT thing_id || '|' || path || '|' || wpath || '|' || ord, "
                        + "ctid::text FROM search_flat")) {
            while (rs.next()) {
                ctids.put(rs.getString(1), rs.getString(2));
            }
        }
        return ctids;
    }

    /** Every doc-row column the writer sets except {@code updated_at} (always {@code now()}), as row text. */
    private static List<String> documentRows(final Connection connection) throws SQLException {
        final List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT (thing_id, namespace, revision, policy_id, policy_rev, "
                        + "referenced_policies, global_read, thing, policy_auth, features_auth, t_modified, "
                        + "delete_at)::text FROM search_things ORDER BY thing_id")) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static Set<String> flatKeys(final Connection connection, final String thingId) throws SQLException {
        final Set<String> keys = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT path, wpath, f_id, ord, type_rank, val_bool, "
                + "val_num, val_text FROM search_flat WHERE thing_id = ?")) {
            ps.setString(1, thingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Object bool = rs.getObject(6);
                    keys.add(key(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getShort(5),
                            bool == null ? null : (Boolean) bool, rs.getBigDecimal(7), rs.getString(8)));
                }
            }
        }
        return keys;
    }

    private static Set<String> expectedFlatKeys(final ThingWriteModel model) {
        return ThingFlattener.flatten(model.getDocument()).stream()
                .map(r -> key(r.path(), r.wpath(), r.fId(), r.ord(), r.typeRank(), r.valBool(), r.valNum(),
                        r.valText()))
                .collect(Collectors.toSet());
    }

    private static String key(final String path, final String wpath, final String fId, final int ord,
            final short typeRank, final Boolean valBool, final BigDecimal valNum, final String valText) {
        return path + "|" + wpath + "|" + fId + "|" + ord + "|" + typeRank + "|" + valBool + "|"
                + (valNum == null ? "null" : valNum.stripTrailingZeros().toPlainString()) + "|" + valText;
    }

    private static long toastedValues(final Connection connection) throws SQLException {
        final String toast = text(connection,
                "SELECT reltoastrelid::regclass::text FROM pg_class WHERE oid = 'search_things'::regclass");
        return scalar(connection, "SELECT count(DISTINCT chunk_id) FROM " + toast);
    }

    private static long scalar(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

}
