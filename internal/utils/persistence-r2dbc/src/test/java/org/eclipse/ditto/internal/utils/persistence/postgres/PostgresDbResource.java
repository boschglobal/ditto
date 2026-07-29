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
package org.eclipse.ditto.internal.utils.persistence.postgres;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.junit.rules.ExternalResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * A JUnit {@link ExternalResource} wrapping a Testcontainers PostgreSQL container (PG 16 by default), mirroring the
 * shape of {@code MongoDbResource} so the PostgreSQL parity-matrix ITs share one container-lifecycle wrapper.
 * <p>
 * Beyond plain Testcontainers it solves one environment-portability problem: the docker-java client bundled inside
 * Testcontainers 1.20.6 negotiates the legacy Docker Remote API version {@code 1.32} when none is configured, which
 * modern daemons (OrbStack / recent Docker Desktop, minimum API 1.40) reject with
 * {@code "client version 1.32 is too old"}. docker-java reads the API version from the {@code api.version} <em>system
 * property</em> (not the {@code DOCKER_API_VERSION} env var, which its shaded copy ignores), so the static initializer
 * sets a sane default before any Testcontainers class loads. This makes {@code mvn verify} run the ITs against a real
 * Postgres with no per-developer or CI configuration.
 * </p>
 * <p>
 * The resource exposes both the DDL role (schema-owning, used by {@code PostgresSchemaManager}) and the connection
 * coordinates needed to build an r2dbc {@link ConnectionFactory}. A {@code @Rule}/{@code @ClassRule} starts the
 * container in {@link #before()} and stops it in {@link #after()}; tests that need a manual lifecycle can call
 * {@link #start()}/{@link #stop()} directly.
 * </p>
 */
@NotThreadSafe
public final class PostgresDbResource extends ExternalResource {

    /** The default PostgreSQL image — PG 16 is the minimum supported version. */
    public static final String DEFAULT_IMAGE = "postgres:16";

    /** The schema-owning DDL role (mirrors the two-role split between schema-owner and runtime user; the tests run DDL as this role). */
    public static final String DDL_USER = "ditto_ddl";
    public static final String DDL_PASSWORD = "ddl_secret";
    public static final String DATABASE = "ditto";

    static {
        // docker-java (shaded in Testcontainers 1.20.6) clamps an unconfigured API version to the legacy 1.32, which
        // daemons with a >=1.40 minimum reject. It honours the `api.version` SYSTEM PROPERTY (its shaded copy does not
        // read the DOCKER_API_VERSION env var), so set a modern default unless one is already configured. Must run
        // before any Testcontainers/docker-java class is touched.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.43");
        }
    }

    private final DockerImageName image;

    @Nullable
    private PostgreSQLContainer<?> container;

    /**
     * Creates a resource for the default image ({@link #DEFAULT_IMAGE}).
     */
    public PostgresDbResource() {
        this(DEFAULT_IMAGE);
    }

    /**
     * Creates a resource for an explicit image tag (e.g. {@code postgres:15} / {@code postgres:17} for the version
     * matrix).
     *
     * @param dockerImage the PostgreSQL image, e.g. {@code postgres:16}.
     */
    public PostgresDbResource(final String dockerImage) {
        this.image = DockerImageName.parse(dockerImage);
    }

    /**
     * Starts the container (idempotent — a no-op if already running). Throws if Docker is unreachable; ITs guard this
     * with {@code Assume.assumeNoException} so an unreachable daemon becomes a skip, not a failure.
     */
    public void start() {
        if (container == null) {
            container = new PostgreSQLContainer<>(image)
                    .withDatabaseName(DATABASE)
                    .withUsername(DDL_USER)
                    .withPassword(DDL_PASSWORD);
            container.start();
        }
    }

    /**
     * Stops and removes the container (idempotent).
     */
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @Override
    protected void before() {
        start();
    }

    @Override
    protected void after() {
        stop();
    }

    private PostgreSQLContainer<?> requireRunning() {
        if (container == null) {
            throw new IllegalStateException("PostgresDbResource container is not started.");
        }
        return container;
    }

    /**
     * @return the host the mapped Postgres port is reachable on.
     */
    public String getHost() {
        return requireRunning().getHost();
    }

    /**
     * @return the host port mapped to the container's Postgres port.
     */
    public int getPort() {
        return requireRunning().getFirstMappedPort();
    }

    /**
     * @return the database name.
     */
    public String getDatabaseName() {
        return DATABASE;
    }

    /**
     * @return a fresh r2dbc {@link ConnectionFactory} authenticated as the DDL (schema-owning) role.
     */
    public ConnectionFactory newConnectionFactory() {
        return newConnectionFactory(DDL_USER, DDL_PASSWORD);
    }

    /**
     * @param user the login role.
     * @param password the login password.
     * @return a fresh r2dbc {@link ConnectionFactory} for the given credentials against this container.
     */
    public ConnectionFactory newConnectionFactory(final String user, final String password) {
        return ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, getHost())
                .option(ConnectionFactoryOptions.PORT, getPort())
                .option(ConnectionFactoryOptions.DATABASE, DATABASE)
                .option(ConnectionFactoryOptions.USER, user)
                .option(ConnectionFactoryOptions.PASSWORD, password)
                .build());
    }

    /**
     * @return the r2dbc URL for this container as the DDL role.
     */
    public String getR2dbcUrl() {
        return "r2dbc:postgresql://" + DDL_USER + ":" + DDL_PASSWORD + "@" + getHost() + ":" + getPort()
                + "/" + DATABASE;
    }

}
