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

import java.time.Duration;
import java.util.Map;

import org.eclipse.ditto.internal.utils.persistence.postgres.config.DefaultSslConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.config.PoolerMode;
import org.eclipse.ditto.internal.utils.persistence.postgres.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.monitoring.R2dbcMetricsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider;
import io.r2dbc.postgresql.client.SSLMode;
import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * Builds the R2DBC {@link ConnectionPool} for the PostgreSQL backend from a {@link PostgresConfig}.
 * <p>
 * Applies the SSL boot guard and the connection-pooling-safe defaults: {@code fetch-size=0} (no server-side
 * portals/cursors) and {@code prepared-statement-cache-queries=0} (unnamed statements), which keep the backend safe
 * behind PgBouncer in any pool mode. The configured {@code pooler-mode} drives an informational startup log line only
 * and never blocks boot.
 * </p>
 * <p>
 * <strong>Backpressure caveat:</strong> the pool-wide {@code fetch-size=0} disables backpressure on the read path —
 * r2dbc-postgresql treats {@code 0} as "materialise the entire result set" before the Pekko {@code Source} can exert
 * demand. The long, genuinely-unbounded streaming read-journal queries therefore bind a positive per-statement
 * {@code Statement.fetchSize(...)} (see {@code PostgresPersistenceOperations.STREAMING_FETCH_SIZE}) to open a
 * server-side portal and restore demand-driven fetching; the write path keeps {@code fetch-size=0} to stay safe under
 * PgBouncer transaction pooling.
 * </p>
 */
final class ConnectionPoolFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionPoolFactory.class);

    /**
     * The R2DBC pool name. Distinct + role-bearing ({@value}) so the single shared per-service pool is identifiable in
     * pool-internal logging; its gauges carry the matching {@code pool=shared} discriminating tag (round-2 finding H-9).
     */
    static final String POOL_NAME = "ditto-postgres-pool-shared";

    private ConnectionPoolFactory() {
        throw new AssertionError();
    }

    static ConnectionPool createConnectionPool(final PostgresConfig config) {
        final DefaultSslConfig sslConfig = config.getSslConfig() instanceof DefaultSslConfig defaultSslConfig
                ? defaultSslConfig
                : null;
        if (sslConfig != null) {
            // Refuse to boot on an unsafe verify-(ca|full)-without-root-cert combo.
            sslConfig.validateBootGuard();
        }

        logPoolerModeCrossCheck(config);

        final ConnectionFactory driverFactory = ConnectionFactories.get(buildOptions(config));
        // Wrap the driver factory with r2dbc-proxy so command timers + acquire-latency feed the neutral
        // ditto_persistence_* metric family. The proxy sits BELOW the pool, so afterCreateOnConnectionFactory observes
        // the actual physical connection creation latency.
        final ConnectionFactory connectionFactory = withMetricsProxy(driverFactory);
        final ConnectionPoolConfiguration poolConfiguration = buildPoolConfiguration(connectionFactory, config);
        return new ConnectionPool(poolConfiguration);
    }

    private static ConnectionFactory withMetricsProxy(final ConnectionFactory delegate) {
        return ProxyConnectionFactory.builder(delegate)
                .listener(R2dbcMetricsListener.kamon())
                .build();
    }

    static ConnectionFactoryOptions buildOptions(final PostgresConfig config) {
        // The runtime (DML) role: layer the runtime username/password.
        final ConnectionFactoryOptions.Builder builder = baseOptions(config);
        config.getUsername().ifPresent(user -> builder.option(ConnectionFactoryOptions.USER, user));
        config.getPassword().ifPresent(pwd -> builder.option(ConnectionFactoryOptions.PASSWORD, pwd));
        return builder.build();
    }

    /**
     * Builds the {@link ConnectionFactoryOptions} for the one-shot schema-bootstrap (DDL) connection, layering the
     * {@code ddl-credentials} role on top of the same URL + SSL + pooling-safe options as the runtime pool.
     * <p>
     * Per {@code ditto-postgres-persistence.conf} ({@code pool.ddl-credentials}), an empty DDL role means "reuse the runtime
     * role": when {@link PostgresConfig.DdlCredentialsConfig#isConfigured()} is {@code false} the runtime
     * username/password are layered instead, so the bootstrap still authenticates. When it IS configured, the DDL
     * username/password win — that is what makes {@code POSTGRES_DDL_USER}/{@code POSTGRES_DDL_PASSWORD} actually do
     * something (the least-privilege schema-owner split). This is the sole production consumer of
     * {@link PostgresConfig.PoolConfig#getDdlCredentials()}.
     * </p>
     *
     * @param config the backend configuration.
     * @return the DDL connection-factory options.
     */
    static ConnectionFactoryOptions buildDdlOptions(final PostgresConfig config) {
        final ConnectionFactoryOptions.Builder builder = baseOptions(config);
        final PostgresConfig.DdlCredentialsConfig ddlCredentials = config.getPoolConfig().getDdlCredentials();
        if (ddlCredentials.isConfigured()) {
            ddlCredentials.getUsername().ifPresent(user -> builder.option(ConnectionFactoryOptions.USER, user));
            ddlCredentials.getPassword().ifPresent(pwd -> builder.option(ConnectionFactoryOptions.PASSWORD, pwd));
        } else {
            // Empty DDL role -> reuse the runtime role for the bootstrap (per the conf comment).
            config.getUsername().ifPresent(user -> builder.option(ConnectionFactoryOptions.USER, user));
            config.getPassword().ifPresent(pwd -> builder.option(ConnectionFactoryOptions.PASSWORD, pwd));
        }
        return builder.build();
    }

    /**
     * Builds a one-shot (single-connection) {@link ConnectionFactory} authenticated as the DDL role, used by the schema
     * bootstrap. It is NOT pooled: the bootstrap opens exactly one connection, runs all DDL in a single transaction and
     * closes it, so a pool would only add an idle DDL-privileged connection for the lifetime of the service.
     *
     * @param config the backend configuration.
     * @return a DDL-role connection factory.
     */
    static ConnectionFactory createDdlConnectionFactory(final PostgresConfig config) {
        final DefaultSslConfig sslConfig = config.getSslConfig() instanceof DefaultSslConfig defaultSslConfig
                ? defaultSslConfig
                : null;
        if (sslConfig != null) {
            sslConfig.validateBootGuard();
        }
        return ConnectionFactories.get(buildDdlOptions(config));
    }

    private static ConnectionFactoryOptions.Builder baseOptions(final PostgresConfig config) {
        final PostgresConfig.PoolConfig poolConfig = config.getPoolConfig();
        final PostgresConfig.SslConfig sslConfig = config.getSslConfig();

        // Parse the r2dbc URL first, then layer explicit overrides so config keys win over URL query params.
        // NOTE: credentials are intentionally NOT layered here. baseOptions() is the shared (URL + SSL + pooling-safe)
        // foundation; the runtime (DML) role is layered by buildOptions() and the schema-bootstrap (DDL) role by
        // buildDdlOptions(), so each caller picks its own credentials (H-1 DDL credential-layering).
        final ConnectionFactoryOptions.Builder builder = ConnectionFactoryOptions.parse(config.getUri()).mutate();

        // fetch-size=0 -> no server-side portal/cursor; keeps transaction pooling safe but DISABLES backpressure
        // (whole result set materialised on read). Unbounded streaming read-journal statements override this per
        // statement with a positive Statement.fetchSize(...) — see PostgresPersistenceOperations.STREAMING_FETCH_SIZE.
        builder.option(PostgresqlConnectionFactoryProvider.FETCH_SIZE, poolConfig.getFetchSize());
        // prepared-statement-cache-queries=0 -> unnamed statements; safe on any PgBouncer version / pool mode.
        builder.option(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES,
                poolConfig.getPreparedStatementCacheQueries());

        // Connect timeout: bound how long a borrow may block on a dead/unreachable host before failing fast. Bound
        // directly via the r2dbc SPI option (Duration-typed). Interacts with pool.max-acquire-time: a borrow that has
        // to open a fresh physical connection can wait up to connect-timeout for the TCP/TLS handshake WITHIN the
        // max-acquire-time budget — keep connect-timeout < max-acquire-time so the acquire surfaces the connect failure
        // rather than a generic acquire timeout.
        builder.option(ConnectionFactoryOptions.CONNECT_TIMEOUT, config.getConnectTimeout());

        // Bind statement_timeout as an r2dbc "options" STARTUP parameter (a GUC name->value Map) rather than a plain
        // per-session "SET", which PgBouncer's server_reset_query would wipe between transaction-pooled borrows. NOTE:
        // r2dbc-postgresql's OPTIONS is a Map<String,String> of GUC name->value (e.g. {"statement_timeout":"60000"}),
        // NOT a libpq "-c statement_timeout=..." string. Value is milliseconds (the unit-less GUC default unit).
        // statement-timeout=0 disables the timeout (Postgres semantics), so we skip the option entirely in that case.
        //
        // PgBouncer caveat (see ditto-postgres-persistence.conf): the GUC rides inside libpq's startup "options" field, which
        // PgBouncer does NOT forward across TRANSACTION-pooled borrows (even with track_extra_parameters). This option
        // is therefore enforced for DIRECT/SESSION pooling and direct Postgres; under TRANSACTION-mode PgBouncer set the
        // timeout server-side (e.g. ALTER ROLE ... SET statement_timeout).
        final Duration statementTimeout = config.getStatementTimeout();
        if (statementTimeout != null && !statementTimeout.isZero() && !statementTimeout.isNegative()) {
            // MERGE with any startup GUCs the operator supplied via the URI's options= query parameter
            // (e.g. search_path) — a bare Map.of(...) would REPLACE the parsed OPTIONS value wholesale.
            final Map<String, String> options = new java.util.LinkedHashMap<>();
            final Object parsedOptions = ConnectionFactoryOptions.parse(config.getUri())
                    .getValue(PostgresqlConnectionFactoryProvider.OPTIONS);
            if (parsedOptions instanceof Map<?, ?> parsedMap) {
                parsedMap.forEach((k, v) -> options.put(String.valueOf(k), String.valueOf(v)));
            } else if (parsedOptions instanceof String parsedString) {
                // URL form arrives as "a=b;c=d" before the provider converts it.
                for (final String pair : parsedString.split(";")) {
                    final int eq = pair.indexOf('=');
                    if (eq > 0) {
                        options.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
            }
            options.put("statement_timeout", Long.toString(statementTimeout.toMillis()));
            builder.option(PostgresqlConnectionFactoryProvider.OPTIONS, options);
        }

        builder.option(PostgresqlConnectionFactoryProvider.SSL_MODE, SSLMode.fromValue(sslConfig.getMode()));
        // Only set cert/key paths when non-empty: r2dbc's requireExistingFilePath() throws on empty/missing.
        sslConfig.getRootCert()
                .ifPresent(v -> builder.option(PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT, v));
        sslConfig.getCert()
                .ifPresent(v -> builder.option(PostgresqlConnectionFactoryProvider.SSL_CERT, v));
        sslConfig.getKey()
                .ifPresent(v -> builder.option(PostgresqlConnectionFactoryProvider.SSL_KEY, v));
        sslConfig.getKeyPassword()
                .ifPresent(v -> builder.option(PostgresqlConnectionFactoryProvider.SSL_PASSWORD, v));

        return builder;
    }

    private static ConnectionPoolConfiguration buildPoolConfiguration(final ConnectionFactory connectionFactory,
            final PostgresConfig config) {
        final PostgresConfig.PoolConfig poolConfig = config.getPoolConfig();
        final ConnectionPoolConfiguration.Builder builder = ConnectionPoolConfiguration.builder(connectionFactory)
                // Distinct, role-bearing pool name: with the shared-pool topology a service builds exactly one pool
                // (shared across journal+snapshot+read-journal), tagged pool=shared on its gauges (round-2 finding H-9).
                .name(POOL_NAME)
                .initialSize(poolConfig.getInitialSize())
                .maxSize(poolConfig.getMaxSize())
                // A pool-acquire-timeout fails the write Future, which STOPS the persistent actor (it is NOT
                // backpressure) -> max-size / max-acquire-time must be sized >= the Mongo baseline.
                .maxAcquireTime(poolConfig.getMaxAcquireTime());

        applyIfPositive(poolConfig.getMaxIdleTime(), builder::maxIdleTime);
        applyIfPositive(poolConfig.getMaxLifeTime(), builder::maxLifeTime);

        return builder.build();
    }

    private static void applyIfPositive(final Duration duration,
            final java.util.function.Consumer<Duration> setter) {
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            setter.accept(duration);
        }
    }

    static void logPoolerModeCrossCheck(final PostgresConfig config) {
        final PoolerMode poolerMode = config.getPoolerMode();
        final int cacheQueries = config.getPoolConfig().getPreparedStatementCacheQueries();

        // Any populated named-statement cache under a non-SESSION pooler is risky:
        //  - TRANSACTION: actively unsafe NOW — server connections are multiplexed between transactions, so named
        //    prepared statements break unless PgBouncer >= 1.21 with max_prepared_statements > 0.
        //  - DIRECT (or unset): safe TODAY, but it is a latent foot-gun — a future flip to TRANSACTION pooling would
        //    silently break this prepared-statement cache.
        // SESSION pins one server connection per client, so a named-statement cache is and stays safe.
        if (cacheQueries > 0 && poolerMode != PoolerMode.SESSION) {
            if (poolerMode == PoolerMode.TRANSACTION) {
                LOGGER.warn("pooler-mode=transaction with prepared-statement-cache-queries={} (!=0): named prepared " +
                        "statements are unsafe under PgBouncer transaction pooling unless PgBouncer >= 1.21 with " +
                        "max_prepared_statements > 0.", cacheQueries);
            } else {
                LOGGER.warn("pooler-mode={} with prepared-statement-cache-queries={} (!=0): this named " +
                        "prepared-statement cache is safe under the current pooling mode, but a future flip to " +
                        "TRANSACTION pooling would break this cache (named statements are unsafe under PgBouncer " +
                        "transaction pooling unless PgBouncer >= 1.21 with max_prepared_statements > 0). Either keep " +
                        "prepared-statement-cache-queries=0 or do not switch to transaction pooling.",
                        poolerMode.getConfigValue(), cacheQueries);
            }
        } else if (poolerMode == PoolerMode.TRANSACTION) {
            LOGGER.info("pooler-mode=transaction with prepared-statement-cache-queries=0: unnamed statements are " +
                    "safe under PgBouncer transaction pooling on any version.");
        }
    }

}
