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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.testkit.PostgresDbResource;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.PathIndex;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.PathIndexKind;
import org.eclipse.ditto.rql.query.expression.ThingsFieldExpressionFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Proves the production-config catalog check ({@link ProductionConfigSchema#verify}) accepts exactly the production
 * DDL and reports every bench-only extra the Phase-0 mitigation ladder used — a covering index, a same-named but
 * redefined index, a per-column statistics target, an extended statistics object, a reloption — and that the
 * reference schema it builds leaves no trace. An operator-configured per-path trigram index (built outside the
 * production DDL) is listed separately, not reported.
 */
public final class ProductionConfigSchemaIT {

    private static final PostgresDbResource POSTGRES = new PostgresDbResource();

    private Connection connection;

    @BeforeClass
    public static void startContainer() {
        try {
            POSTGRES.start();
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker/Testcontainers unavailable — skipping ProductionConfigSchemaIT", t);
        }
    }

    @AfterClass
    public static void stopContainer() {
        POSTGRES.stop();
    }

    @Before
    public void createProductionSchema() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getPort() + "/"
                        + PostgresDbResource.DATABASE, PostgresDbResource.DDL_USER, PostgresDbResource.DDL_PASSWORD);
        connection.setAutoCommit(true);
        ProductionConfigSchema.dropAll(connection);
        ProductionConfigSchema.applyPreLoad(connection);
        ProductionConfigSchema.applyPostLoad(connection);
    }

    @After
    public void close() throws SQLException {
        connection.close();
    }

    @Test
    public void productionDdlMatchesAndTheReferenceLeavesNoTrace() throws SQLException {
        final ProductionConfigSchema.CatalogCheck check = ProductionConfigSchema.verify(connection);

        assertThat(check.differences()).isEmpty();
        assertThat(check.matchesProduction()).isTrue();
        assertThat(check.productionVerdict()).isEqualTo("true");
        assertThat(check.actualIndexes()).containsExactlyInAnyOrderElementsOf(
                ProductionConfigSchema.expectedIndexNames());
        // 14 indexes + 25 columns + 4 constraints at least.
        assertThat(check.comparedItems()).isGreaterThanOrEqualTo(43);
        assertThat(connection.getAutoCommit()).isTrue();
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT count(*) FROM pg_namespace "
                        + "WHERE nspname = 'production_config_ddl_reference'")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    public void extraCoveringIndexIsReported() throws SQLException {
        execute("CREATE INDEX sf_num_covering ON search_flat (wpath, val_num) INCLUDE (thing_id) "
                + "WHERE val_num IS NOT NULL");

        assertThat(ProductionConfigSchema.verify(connection).differences())
                .anyMatch(d -> d.startsWith("index sf_num_covering: live=CREATE INDEX sf_num_covering"));
    }

    @Test
    public void sameNamedRedefinedIndexIsReported() throws SQLException {
        execute("DROP INDEX sf_num");
        execute("CREATE INDEX sf_num ON search_flat (wpath, val_num) INCLUDE (thing_id) WHERE val_num IS NOT NULL");

        assertThat(ProductionConfigSchema.verify(connection).differences())
                .anyMatch(d -> d.startsWith("index sf_num: ") && d.contains("INCLUDE"));
    }

    @Test
    public void statisticsTargetIsReported() throws SQLException {
        execute("ALTER TABLE search_flat ALTER COLUMN wpath SET STATISTICS 10000");

        assertThat(ProductionConfigSchema.verify(connection).differences())
                .anyMatch(d -> d.startsWith("statistics-target search_flat.wpath: live=10000"));
    }

    @Test
    public void extendedStatisticsAreReported() throws SQLException {
        execute("CREATE STATISTICS sf_wpath_num (dependencies) ON wpath, val_num FROM search_flat");

        assertThat(ProductionConfigSchema.verify(connection).differences())
                .anyMatch(d -> d.startsWith("extended-statistics sf_wpath_num"));
    }

    @Test
    public void reloptionIsReported() throws SQLException {
        execute("ALTER TABLE search_flat SET (autovacuum_vacuum_scale_factor = 0.01)");

        assertThat(ProductionConfigSchema.verify(connection).differences())
                .anyMatch(d -> d.startsWith("reloptions search_flat: live=autovacuum_vacuum_scale_factor=0.01"));
    }

    @Test
    public void perPathTrigramIndexIsListedNotReported() throws SQLException {
        final PathIndex index = PathIndex.of("features/*/properties/prop0", PathIndexKind.TRIGRAM,
                ThingsFieldExpressionFactory.of(Map.of()));
        execute(index.createStatement());

        final ProductionConfigSchema.CatalogCheck check = ProductionConfigSchema.verify(connection);

        assertThat(check.differences()).isEmpty();
        assertThat(check.perPathIndexes()).singleElement().asString()
                .startsWith("CREATE INDEX " + index.name() + " ON search_flat USING gin");
        assertThat(check.perPathIndexNames()).containsExactly(index.name());
        assertThat(check.actualIndexes()).contains(index.name());
        // a run with a leftover per-path index must not read as a plain production-DDL run
        assertThat(check.productionVerdict()).isEqualTo("production DDL + 1 per-path index");
    }

    @Test
    public void otherIndexUnderThePerPathPrefixIsReported() throws SQLException {
        execute("CREATE INDEX sf_path_trgm_0000000000000000 ON search_flat (wpath)");

        final ProductionConfigSchema.CatalogCheck check = ProductionConfigSchema.verify(connection);

        assertThat(check.differences()).anyMatch(d -> d.startsWith("index sf_path_trgm_0000000000000000: live="));
        assertThat(check.perPathIndexes()).isEmpty();
    }

    private void execute(final String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

}
