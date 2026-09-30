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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.DefaultPostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.RenderedSql;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.read.PostgresThingsSearchPersistence;
import org.eclipse.ditto.rql.query.Query;
import org.eclipse.ditto.thingsearch.persistence.api.model.ResultList;
import org.eclipse.ditto.thingsearch.persistence.api.model.TimestampedThingId;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import io.r2dbc.spi.Connection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The SHIPPED PostgreSQL search read stack against the bench database: the {@code ditto-postgres-search} profile
 * (which includes the shared {@code ditto-postgres-client} defaults: {@code force-custom-plan = true},
 * {@code statement-timeout = 60s}, {@code fetch-size = 0}, {@code prepared-statement-cache-queries = 0}, the pool
 * limits) loaded from the classpath exactly as the search service loads it, with only the connection coordinates
 * overridden (URI, user, password, and {@code ssl.mode = disable} because the bench container serves no TLS). The pool
 * is built by the production {@code ConnectionPoolFactory} (via {@link DittoPostgresClient#newInstance}), so
 * {@code plan_cache_mode = force_custom_plan} rides on every connection as a startup GUC exactly as in production, and
 * timed reads go through {@link PostgresThingsSearchPersistence#findAll}/{@link PostgresThingsSearchPersistence#count}.
 */
final class ProductionSearchDb implements AutoCloseable {

    private static final String PROFILE = "ditto-postgres-search.conf";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final DittoHeaders HEADERS = DittoHeaders.empty();

    private final ActorSystem system;
    private final PostgresConfig config;
    private final DittoPostgresClient client;
    private final PostgresThingsSearchPersistence persistence;

    ProductionSearchDb(final ActorSystem system, final String r2dbcUri, final String user, final String password) {
        this.system = system;
        config = shippedConfig(r2dbcUri, user, password);
        client = DittoPostgresClient.newInstance(config);
        persistence = PostgresThingsSearchPersistence.of(client);
    }

    /**
     * @return the shipped search profile with only the connection coordinates overridden.
     */
    static PostgresConfig shippedConfig(final String r2dbcUri, final String user, final String password) {
        final Config overrides = ConfigFactory.empty()
                .withValue("ditto.postgresql.uri", ConfigValueFactory.fromAnyRef(r2dbcUri))
                .withValue("ditto.postgresql.username", ConfigValueFactory.fromAnyRef(user))
                .withValue("ditto.postgresql.password", ConfigValueFactory.fromAnyRef(password))
                .withValue("ditto.postgresql.ssl.mode", ConfigValueFactory.fromAnyRef("disable"));
        final Config profile = ConfigFactory.parseResources(PROFILE);
        if (profile.isEmpty()) {
            throw new IllegalStateException("Shipped profile " + PROFILE + " not found on the classpath");
        }
        final Config config = overrides.withFallback(profile).resolve();
        return DefaultPostgresConfig.of(config.getConfig("ditto"));
    }

    PostgresConfig config() {
        return config;
    }

    /** Times nothing itself: runs the shipped {@code findAll} to completion and returns its page. */
    ResultList<TimestampedThingId> findAll(final Query query, final List<String> subjects) {
        return run(persistence.findAll(query, subjects, null, HEADERS));
    }

    /** Runs the shipped {@code count} to completion. */
    long count(final Query query, final List<String> subjects) {
        return run(persistence.count(query, subjects, HEADERS));
    }

    /**
     * {@code EXPLAIN (ANALYZE, BUFFERS, SETTINGS)} of a rendered statement on a connection from the SAME shipped pool
     * (so it plans under the same session GUCs as the timed reads), binding exactly like the persistence does.
     * <p>
     * <b>Execution mode.</b> The shipped {@code findAll} sets {@code Statement.fetchSize(100)}
     * ({@code PostgresThingsSearchPersistence.DEFAULT_FETCH_SIZE}); r2dbc-postgresql then sends
     * {@code Execute(portal, 100)}, and PostgreSQL never runs a parallel plan in parallel for a portal executed with
     * a row limit — the planner still picks the parallel plan, the leader runs it alone. A plain EXPLAIN would
     * launch workers the timed path never gets, so with {@code leaderOnly} the EXPLAIN runs in a transaction with
     * {@code SET LOCAL max_parallel_workers = 0}: same plan choice ({@code max_parallel_workers} is not a planner
     * input), zero workers launched. {@code count} executes without a fetch size (workers allowed), so counts are
     * explained with {@code leaderOnly = false}. The {@code Settings:} line of the output records which mode ran.
     * </p>
     */
    String explain(final RenderedSql rendered, final boolean leaderOnly) {
        final String sql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS) " + rendered.sql();
        final List<String> lines = Flux.usingWhen(Mono.from(client.getConnectionPool().create()),
                        connection -> leaderOnly
                                ? Mono.from(connection.beginTransaction())
                                .thenMany(Flux.from(connection.createStatement("SET LOCAL max_parallel_workers = 0")
                                        .execute()).flatMap(result -> result.getRowsUpdated()))
                                .thenMany(explainRows(connection, rendered, sql))
                                .collectList()
                                .flatMapMany(rows -> Mono.from(connection.commitTransaction())
                                        .thenMany(Flux.fromIterable(rows)))
                                : explainRows(connection, rendered, sql),
                        Connection::close)
                .collectList()
                .block(TIMEOUT);
        return String.join("\n", lines) + "\n";
    }

    private static Flux<String> explainRows(final Connection connection, final RenderedSql rendered,
            final String sql) {
        return Flux.from(rendered.applyTo(connection.createStatement(sql)).execute())
                .flatMap(result -> result.map((row, meta) -> row.get(0, String.class)));
    }

    /** Executes a rendered find statement directly and returns its {@code thing_id} column, in order. */
    List<String> executeThingIds(final RenderedSql rendered) {
        return Flux.usingWhen(Mono.from(client.getConnectionPool().create()),
                        connection -> Flux.from(rendered.applyTo(connection.createStatement(rendered.sql())).execute())
                                .flatMap(result -> result.map((row, meta) -> row.get("thing_id", String.class))),
                        Connection::close)
                .collectList()
                .block(TIMEOUT);
    }

    /** Executes a rendered count statement directly. */
    long executeCount(final RenderedSql rendered) {
        final Long count = Flux.usingWhen(Mono.from(client.getConnectionPool().create()),
                        connection -> Flux.from(rendered.applyTo(connection.createStatement(rendered.sql())).execute())
                                .flatMap(result -> result.map((row, meta) -> row.get(0, Long.class))),
                        Connection::close)
                .next()
                .block(TIMEOUT);
        return count == null ? 0L : count;
    }

    /** {@code SHOW <name>} for each GUC, on a pooled connection (i.e. as the timed reads see them). */
    Map<String, String> show(final List<String> gucs) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String guc : gucs) {
            final String value = Flux.usingWhen(Mono.from(client.getConnectionPool().create()),
                            connection -> Flux.from(connection.createStatement("SHOW " + guc).execute())
                                    .flatMap(result -> result.map((row, meta) -> row.get(0, String.class))),
                            Connection::close)
                    .next()
                    .block(TIMEOUT);
            values.put(guc, value);
        }
        return values;
    }

    /** Runs an arbitrary single-column query on a pooled connection (evidence queries only). */
    List<String> queryColumn(final String sql) {
        final List<String> values = Flux.usingWhen(Mono.from(client.getConnectionPool().create()),
                        connection -> Flux.from(connection.createStatement(sql).execute())
                                .flatMap(result -> result.map((row, meta) -> String.valueOf(row.get(0)))),
                        Connection::close)
                .collectList()
                .block(TIMEOUT);
        return new ArrayList<>(values);
    }

    private <T> T run(final Source<T, ?> source) {
        try {
            return source.runWith(Sink.head(), system).toCompletableFuture().get(TIMEOUT.toSeconds(),
                    TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("shipped search read failed", e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

}
