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

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.PostgresSortClause;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.SearchQueryAssembler;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.RenderedSql;
import org.eclipse.ditto.rql.query.Query;
import org.eclipse.ditto.thingsearch.model.signals.commands.query.CountThings;
import org.eclipse.ditto.thingsearch.model.signals.commands.query.QueryThings;
import org.eclipse.ditto.thingsearch.persistence.api.model.ResultList;
import org.eclipse.ditto.thingsearch.service.common.config.DittoSearchConfig;
import org.eclipse.ditto.thingsearch.service.persistence.query.QueryParser;
import org.eclipse.ditto.thingsearch.service.starter.actors.BenchSearchQueryPath;

import com.typesafe.config.Config;

/**
 * The shipped RQL-to-SQL path, DB-free: search commands are parsed by the query parser the search service itself builds
 * ({@code SearchRootActor.getQueryParser}: production simple-field mappings, page-size limits, the PostgreSQL
 * provider's {@code QueryBuilderFactory}, the configured criteria validator), cursors are handled by the service's
 * {@code ThingsSearchCursor} (via {@link BenchSearchQueryPath}, pinned against the real {@code SearchActor}), and SQL
 * is rendered by {@link SearchQueryAssembler} with exactly the arguments {@code PostgresThingsSearchPersistence} passes
 * it ({@code findAll}: {@code limit + 1} and {@code skip}; {@code count}: {@code skip} and {@code limit}).
 * <p>
 * The actor system must be created with {@link #actorSystemConfig()}. The {@code render*} methods exist for
 * {@code EXPLAIN} capture only; timed executions go through {@code PostgresThingsSearchPersistence} itself (see
 * {@link ProductionSearchDb}).
 * </p>
 */
final class ProductionSearchPath {

    private static final DittoHeaders HEADERS = DittoHeaders.empty();

    private final ActorSystem system;
    private final QueryParser queryParser;

    /**
     * @param system an actor system created with {@link #actorSystemConfig()}.
     */
    ProductionSearchPath(final ActorSystem system) {
        this.system = system;
        final DittoSearchConfig searchConfig =
                DittoSearchConfig.of(DefaultScopedConfig.dittoScoped(system.settings().config()));
        queryParser = BenchSearchQueryPath.productionQueryParser(searchConfig, system);
    }

    /** @return the configuration the benchmark's actor system needs ({@link BenchSearchQueryPath#serviceConfig()}). */
    static Config actorSystemConfig() {
        return BenchSearchQueryPath.serviceConfig();
    }

    /** @return the search command for a find request. */
    static QueryThings queryThings(@Nullable final String filter, final List<String> options) {
        return QueryThings.of(filter, options, null, null, HEADERS);
    }

    /**
     * @param filter the RQL filter, or {@code null} for none.
     * @param options the RQL options ({@code size}, {@code sort}, {@code limit}, {@code cursor}).
     * @return the query the service hands to {@code ThingsSearchPersistence.findAll}.
     */
    Query find(@Nullable final String filter, final List<String> options) {
        return BenchSearchQueryPath.parseQueryThings(queryParser, queryThings(filter, options), system);
    }

    /**
     * @param filter the RQL filter.
     * @return the query the service hands to {@code ThingsSearchPersistence.count}.
     */
    Query count(final String filter) {
        return BenchSearchQueryPath.parseCountThings(queryParser, CountThings.of(filter, null, HEADERS));
    }

    /**
     * @param filter the filter of the cursor-less command that produced {@code page}.
     * @param options its options.
     * @param page the page it returned.
     * @return the cursor the service hands the client for the next page.
     */
    static Optional<String> nextPageCursor(@Nullable final String filter, final List<String> options,
            final ResultList<?> page) {
        return BenchSearchQueryPath.nextPageCursor(queryThings(filter, options), page);
    }

    /** @return the SQL {@code PostgresThingsSearchPersistence.findAll} executes for {@code query}. */
    static RenderedSql renderFind(final Query query, final List<String> subjects) {
        return SearchQueryAssembler.findAll(query.getCriteria(), subjects, PostgresSortClause.of(query.getSortOptions()),
                (long) query.getLimit() + 1L, query.getSkip());
    }

    /** @return the SQL {@code PostgresThingsSearchPersistence.count} executes for {@code query}. */
    static RenderedSql renderCount(final Query query, final List<String> subjects) {
        return SearchQueryAssembler.count(query.getCriteria(), subjects, query.getSkip(), query.getLimit());
    }

}
