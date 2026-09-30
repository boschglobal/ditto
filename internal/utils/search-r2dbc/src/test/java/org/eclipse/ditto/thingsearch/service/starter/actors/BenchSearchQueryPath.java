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
package org.eclipse.ditto.thingsearch.service.starter.actors;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.rql.query.Query;
import org.eclipse.ditto.thingsearch.model.SearchModelFactory;
import org.eclipse.ditto.thingsearch.model.SearchResult;
import org.eclipse.ditto.thingsearch.model.signals.commands.query.CountThings;
import org.eclipse.ditto.thingsearch.model.signals.commands.query.QueryThings;
import org.eclipse.ditto.thingsearch.persistence.api.model.ResultList;
import org.eclipse.ditto.thingsearch.service.persistence.query.QueryParser;

/**
 * The search service's command-to-{@link Query} chain, exposed for the production-config search benchmark
 * ({@code org.eclipse.ditto.internal.utils.search.postgres.bench.ProductionSearchPath}). It lives in the cursor's own
 * package (split-package test source, same precedent as {@link ParityCursorWalker}) because
 * {@link ThingsSearchCursor} is package-private, and it mirrors {@code SearchActor} step for step so the benchmark's
 * queries — including cursor ("keyset") paging — are built exactly as the service builds them:
 * <ul>
 *   <li>{@code QueryThings}: {@code ThingsSearchCursor.extractCursor} → {@code ThingsSearchCursor.adjust(cursor,
 *   command)} → {@code QueryParser.parse} → {@code ThingsSearchCursor.adjust(cursor, query, criteriaFactory)}
 *   ({@code SearchActor} query flow);</li>
 *   <li>{@code CountThings}: {@code QueryParser.parse} ({@code SearchActor} count flow);</li>
 *   <li>the cursor handed to the client after a page: {@code ThingsSearchCursor.processSearchResult}
 *   ({@code SearchActor#toQueryThingsResponse}).</li>
 * </ul>
 * <p>
 * <b>Why a copy, and how it is pinned.</b> {@code SearchActor} runs this chain inside its message handling
 * ({@code performQuery} / {@code executeCount}), interleaved with tracing, pre-enforcement and the persistence call,
 * so there is no callable production method to reuse; the benchmark needs the resulting {@code Query} (to render and
 * EXPLAIN the same statement) and must time only the persistence call. {@code BenchSearchQueryPathTest} drives the
 * REAL {@code SearchActor} with a capturing persistence and fails if the query it hands to
 * {@code ThingsSearchPersistence} ever differs from what this class produces for the same command — plain, OFFSET
 * and cursor finds and counts (review round 1, M7). The query parser itself is the service's own
 * ({@link #productionQueryParser}).
 * </p>
 */
public final class BenchSearchQueryPath {

    private static final long TIMEOUT_SECONDS = 30;

    /**
     * The search-service settings the benchmark's actor system needs, as the service ships them: the extension FQCNs
     * of {@code search.conf} ({@code query-criteria-validator}) and of the shipped {@code ditto-postgres-search}
     * profile ({@code search-persistence-provider} = the PostgreSQL provider), the code defaults for everything else
     * under {@code ditto.search}/{@code ditto.limits}, a placeholder Mongo URI (required by {@code DittoSearchConfig},
     * never used) and the {@code search-dispatcher} {@code SearchActor} runs on.
     */
    private static final String SERVICE_CONFIG = "ditto {\n"
            + "  mongodb { uri = \"mongodb://localhost:27017/unused\" }\n"
            + "  search {}\n"
            + "  extensions {\n"
            + "    query-criteria-validator = "
            + "\"org.eclipse.ditto.thingsearch.service.persistence.query.validation.DefaultQueryCriteriaValidator\"\n"
            + "  }\n"
            + "}\n"
            + "search-dispatcher { type = Dispatcher, executor = \"fork-join-executor\" }\n";

    private BenchSearchQueryPath() {
        throw new AssertionError("no instances");
    }

    /**
     * @return the configuration for an actor system that builds the search service's query parser exactly as the
     * service does (see {@link #productionQueryParser}).
     */
    public static com.typesafe.config.Config serviceConfig() {
        return com.typesafe.config.ConfigFactory.parseString(SERVICE_CONFIG)
                .withFallback(com.typesafe.config.ConfigFactory.parseResources("ditto-postgres-search.conf"))
                .withFallback(com.typesafe.config.ConfigFactory.load())
                .resolve();
    }

    /**
     * @param searchConfig the search config of {@code system}.
     * @param system an actor system configured with {@link #serviceConfig()}.
     * @return the query parser the search service itself builds ({@code SearchRootActor.getQueryParser}: simple-field
     * mappings, the persistence provider's {@code QueryBuilderFactory} with the configured page-size limits, the
     * configured {@code QueryCriteriaValidator}).
     */
    public static QueryParser productionQueryParser(
            final org.eclipse.ditto.thingsearch.service.common.config.SearchConfig searchConfig,
            final ActorSystem system) {
        return SearchRootActor.getQueryParser(searchConfig, system);
    }

    /**
     * @param queryParser the service's query parser (built with the backend's {@code QueryBuilderFactory}).
     * @param queryThings the search command, optionally carrying a {@code cursor(...)} option.
     * @param system the actor system the service uses to decode cursors.
     * @return the query {@code SearchActor} hands to {@code ThingsSearchPersistence.findAll}.
     */
    public static Query parseQueryThings(final QueryParser queryParser, final QueryThings queryThings,
            final ActorSystem system) {
        final Optional<ThingsSearchCursor> cursor = runSingle(ThingsSearchCursor.extractCursor(queryThings, system),
                system);
        final QueryThings command = ThingsSearchCursor.adjust(cursor, queryThings);
        final Query parsed = join(queryParser.parse(command).toCompletableFuture());
        return ThingsSearchCursor.adjust(cursor, parsed, queryParser.getCriteriaFactory());
    }

    /**
     * @param queryParser the service's query parser.
     * @param countThings the count command.
     * @return the query {@code SearchActor} hands to {@code ThingsSearchPersistence.count}.
     */
    public static Query parseCountThings(final QueryParser queryParser, final CountThings countThings) {
        return join(queryParser.parse(countThings).toCompletableFuture());
    }

    /**
     * @param queryThings a cursor-less search command (no {@code limit} option) whose page produced {@code page}.
     * @param page the page the persistence returned for it.
     * @return the encoded cursor the service returns to the client for the next page, or empty on the last page.
     */
    public static Optional<String> nextPageCursor(final QueryThings queryThings, final ResultList<?> page) {
        final SearchResult searchResult =
                SearchModelFactory.newSearchResult(JsonArray.empty(), page.nextPageOffset());
        return ThingsSearchCursor.processSearchResult(queryThings, null, searchResult, page).getCursor();
    }

    private static <T> T runSingle(final Source<T, ?> source, final ActorSystem system) {
        return join(source.runWith(Sink.head(), system).toCompletableFuture());
    }

    private static <T> T join(final java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("search command could not be turned into a query", e);
        }
    }

}
