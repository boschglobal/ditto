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
package org.eclipse.ditto.internal.utils.persistence.postgres.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.ditto.internal.utils.persistence.postgres.PostgresDbResource;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Integration test for {@link PostgresSchemaManager} against a real PostgreSQL (PG 16) via Testcontainers.
 * <p>
 * Runs under the failsafe phase ({@code *IT}); skipped offline / when no Docker daemon is reachable (the
 * {@link #startContainer() Assume} guard turns an unreachable Docker into a skip, not a failure).
 * </p>
 */
public final class PostgresSchemaManagerIT {

    private static final PostgresDbResource POSTGRES = new PostgresDbResource();
    private static ConnectionFactory connectionFactory;

    @BeforeClass
    public static void startContainer() {
        try {
            POSTGRES.start();
        } catch (final Throwable t) {
            Assume.assumeNoException("Docker/Testcontainers unavailable — skipping PostgresSchemaManagerIT", t);
        }
        connectionFactory = POSTGRES.newConnectionFactory();
    }

    @AfterClass
    public static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    public void bootstrapCreatesAllTablesAndIsIdempotent() {
        PostgresSchemaManager.of(connectionFactory).bootstrap();

        // every entity's three tables exist
        for (final String entity : PostgresSchema.ENTITIES) {
            assertThat(tableExists(entity + "_journal")).isTrue();
            assertThat(tableExists(entity + "_journal_seq")).isTrue();
            assertThat(tableExists(entity + "_snaps")).isTrue();
        }
        assertThat(tableExists("schema_version")).isTrue();

        // re-run is idempotent (IF NOT EXISTS + checksum match)
        PostgresSchemaManager.of(connectionFactory).bootstrap();
        assertThat(scalar("SELECT checksum FROM schema_version WHERE component = '"
                + PostgresSchema.COMPONENT + "'")).isEqualTo(SchemaChecksum.current());
    }

    @Test
    public void divergentPreExistingPkRefusesToBoot() {
        // Pre-create a divergent things_journal with the OLD (pid, sn, written_at) PK, then bootstrap:
        // CREATE TABLE IF NOT EXISTS keeps the existing table, so the live-catalog check must detect the
        // mismatch and refuse to boot rather than silently running against a schema with the wrong primary key.
        runDdl("DROP TABLE IF EXISTS things_journal CASCADE");
        runDdl("CREATE TABLE things_journal (pid TEXT, sn BIGINT, seq BIGINT GENERATED ALWAYS AS IDENTITY, "
                + "manifest TEXT, tags TEXT[], event JSONB, written_at TIMESTAMPTZ, "
                + "PRIMARY KEY (pid, sn, written_at))");

        assertThatThrownBy(() -> PostgresSchemaManager.of(connectionFactory).bootstrap())
                .isInstanceOf(SchemaBootException.class)
                .hasMessageContaining("divergent primary key");

        // cleanup so the idempotent test is order-independent
        runDdl("DROP TABLE IF EXISTS things_journal CASCADE");
    }

    @Test
    public void bootstrapIgnoresSameNamedTablesInOtherSchemas() {
        // A decoy schema with a WRONG-shaped things_journal must not corrupt verification of the real one: an
        // unqualified pg_constraint/information_schema.columns lookup by bare table name would merge/collide the
        // decoy's catalog rows into the real table's, causing a false SchemaBootException (or a false pass).
        runDdl("CREATE SCHEMA IF NOT EXISTS decoy");
        runDdl("CREATE TABLE IF NOT EXISTS decoy.things_journal (wrong BIGINT PRIMARY KEY)");
        try {
            PostgresSchemaManager.of(connectionFactory).bootstrap();
        } finally {
            // cleanup so repeated runs against the same container stay deterministic.
            runDdl("DROP SCHEMA IF EXISTS decoy CASCADE");
        }
    }

    private static boolean tableExists(final String table) {
        return Boolean.parseBoolean(scalar(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = '" + table + "')"));
    }

    private static String scalar(final String sql) {
        return Mono.usingWhen(Mono.from(connectionFactory.create()),
                        conn -> Flux.from(conn.createStatement(sql).execute())
                                .flatMap(result -> result.map((row, meta) -> String.valueOf(row.get(0))))
                                .next(),
                        Connection::close)
                .block();
    }

    private static void runDdl(final String sql) {
        Mono.usingWhen(Mono.from(connectionFactory.create()),
                        conn -> Flux.from(conn.createStatement(sql).execute())
                                .flatMap(Result::getRowsUpdated)
                                .then(),
                        Connection::close)
                .block();
    }

}
