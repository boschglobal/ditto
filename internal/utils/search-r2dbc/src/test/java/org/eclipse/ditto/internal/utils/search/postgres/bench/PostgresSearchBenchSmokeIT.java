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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers-backed smoke test for the Phase-0 bench harness: applies {@code bench/search-schema.sql},
 * COPY-loads a small (5k-thing) corpus plus one hand-built edge-case thing, builds the indexes, and asserts —
 * against the real, COPY-round-tripped database state — that the flattening rules the target design depends
 * on actually hold: a feature-subtree leaf produces two rows (one plain, one {@code /features/*}-wildcarded),
 * a direct array-of-array produces {@code typeRank = 5} stub rows with no descent into the inner arrays,
 * {@code ord} enumerates repeat occurrences at the same path, and an empty object still gets its own
 * {@code typeRank = 4} exists-row with no children.
 * <p>
 * Self-skips (via {@link Assume}) if Docker is unreachable, mirroring the
 * {@code persistence-r2dbc.PostgresDbResource} convention, so it is safe to run in any CI environment.
 */
public class PostgresSearchBenchSmokeIT {

    static {
        // docker-java (shaded in Testcontainers) clamps an unconfigured API version to the legacy 1.32, which
        // daemons with a >=1.40 minimum (OrbStack / recent Docker Desktop) reject. Mirrors the workaround in
        // persistence-r2dbc's PostgresDbResource.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.43");
        }
    }

    private static final String EDGE_THING_ID = "smoke.ns:edge-case-1";
    private static final int CORPUS_SIZE = 5_000;

    @Nullable
    private PostgreSQLContainer<?> container;

    @Before
    public void setUp() {
        try {
            container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("bench_smoke")
                    .withUsername("bench")
                    .withPassword("bench");
            container.start();
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker/Testcontainers unavailable — skipping PostgresSearchBenchSmokeIT", t);
        }
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    public void ddlAppliesFlattenerRulesHoldAndCopyRoundTrips() throws SQLException, java.io.IOException {
        try (Connection connection =
                DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword())) {

            SearchSchemaDdl.dropAll(connection);
            SearchSchemaDdl.applyTables(connection);

            final List<ThingRecord> corpus = new ArrayList<>(CORPUS_SIZE + 1);
            for (long i = 0; i < CORPUS_SIZE; i++) {
                corpus.add(CorpusGenerator.generate(i, 42L));
            }
            corpus.add(buildEdgeCaseThing());

            final long thingsCopied = CopyLoader.loadThings(connection, corpus.iterator());
            CopyLoader.loadFlat(connection, corpus.iterator());
            SearchSchemaDdl.applyIndexes(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE search_things");
                statement.execute("ANALYZE search_flat");
            }

            assertThat(thingsCopied).isEqualTo(CORPUS_SIZE + 1);
            assertThat(count(connection, "SELECT count(*) FROM search_things")).isEqualTo(CORPUS_SIZE + 1);
            assertThat(count(connection, "SELECT count(*) FROM search_flat")).isGreaterThan(0);

            assertFeatureLeafEmitsTwoRows(connection);
            assertArrayOfArrayStub(connection);
            assertOrdEnumeration(connection);
            assertEmptyObjectRow(connection);
        }
    }

    private ThingRecord buildEdgeCaseThing() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        // array-of-array: 3 outer elements, each itself an array -> 3 stub rows, no descent.
        attributes.put("matrix", List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6)));
        // ord enumeration: 3 scalar elements sharing one path.
        attributes.put("tags", List.of("red", "green", "blue"));
        // empty object: exactly one typeRank=4 row, no children.
        attributes.put("emptyObj", new LinkedHashMap<>());
        // empty array: exactly one typeRank=5 row, no value.
        attributes.put("emptyArr", new ArrayList<>());

        final Map<String, Object> envProperties = new LinkedHashMap<>();
        envProperties.put("temperature", new BigDecimal("21.5"));
        final Map<String, Object> envFeature = new LinkedHashMap<>();
        envFeature.put("properties", envProperties);
        final Map<String, Object> features = new LinkedHashMap<>();
        features.put("env", envFeature);

        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);
        thing.put("features", features);

        return new ThingRecord(EDGE_THING_ID, "smoke.ns", 1L, "smoke.ns:policy-0", 1L, null,
                List.of("user:sub-000"), thing, null, null, Instant.now(), null);
    }

    private void assertFeatureLeafEmitsTwoRows(final Connection connection) throws SQLException {
        final String path = "/features/env/properties/temperature";
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT wpath, f_id, type_rank, val_num FROM search_flat "
                                + "WHERE thing_id = '" + EDGE_THING_ID + "' AND path = '" + path + "' ORDER BY wpath")) {
            final List<String> wpaths = new ArrayList<>();
            while (rs.next()) {
                wpaths.add(rs.getString("wpath"));
                assertThat(rs.getString("f_id")).isEqualTo("env");
                assertThat(rs.getShort("type_rank")).isEqualTo(Flattener.TYPE_NUMBER);
                assertThat(rs.getBigDecimal("val_num")).isEqualByComparingTo("21.5");
            }
            assertThat(wpaths).containsExactly("/features/*/properties/temperature", path);
        }
    }

    private void assertArrayOfArrayStub(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT ord, type_rank, val_bool, val_num, val_text FROM search_flat "
                                + "WHERE thing_id = '" + EDGE_THING_ID + "' AND path = '/attributes/matrix' "
                                + "ORDER BY ord")) {
            final List<Integer> ords = new ArrayList<>();
            while (rs.next()) {
                ords.add(rs.getInt("ord"));
                assertThat(rs.getShort("type_rank")).isEqualTo(Flattener.TYPE_ARRAY);
                assertThat(rs.getObject("val_bool")).isNull();
                assertThat(rs.getObject("val_num")).isNull();
                assertThat(rs.getObject("val_text")).isNull();
            }
            assertThat(ords).containsExactly(0, 1, 2);
        }
    }

    private void assertOrdEnumeration(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT ord, val_text FROM search_flat "
                                + "WHERE thing_id = '" + EDGE_THING_ID + "' AND path = '/attributes/tags' "
                                + "ORDER BY ord")) {
            final List<String> values = new ArrayList<>();
            int expectedOrd = 0;
            while (rs.next()) {
                assertThat(rs.getInt("ord")).isEqualTo(expectedOrd++);
                values.add(rs.getString("val_text"));
            }
            assertThat(values).containsExactly("red", "green", "blue");
        }
    }

    private void assertEmptyObjectRow(final Connection connection) throws SQLException {
        assertThat(count(connection,
                "SELECT count(*) FROM search_flat WHERE thing_id = '" + EDGE_THING_ID + "' "
                        + "AND path = '/attributes/emptyObj'")).isEqualTo(1);
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT type_rank FROM search_flat WHERE thing_id = '" + EDGE_THING_ID + "' "
                                + "AND path = '/attributes/emptyObj'")) {
            rs.next();
            assertThat(rs.getShort("type_rank")).isEqualTo(Flattener.TYPE_OBJECT);
        }
        assertThat(count(connection,
                "SELECT count(*) FROM search_flat WHERE thing_id = '" + EDGE_THING_ID + "' "
                        + "AND path LIKE '/attributes/emptyObj/%'")).isEqualTo(0);
    }

    private static long count(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

}
