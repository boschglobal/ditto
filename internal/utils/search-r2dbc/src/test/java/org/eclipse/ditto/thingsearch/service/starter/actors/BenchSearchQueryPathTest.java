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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.PoisonPill;
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.auth.AuthorizationContext;
import org.eclipse.ditto.base.model.auth.AuthorizationSubject;
import org.eclipse.ditto.base.model.auth.DittoAuthorizationContextType;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.PostgresSortClause;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.SearchQueryAssembler;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.RenderedSql;
import org.eclipse.ditto.internal.utils.tracing.DittoTracing;
import org.eclipse.ditto.internal.utils.tracing.config.DefaultTracingConfig;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.rql.query.Query;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.thingsearch.model.signals.commands.query.CountThings;
import org.eclipse.ditto.thingsearch.model.signals.commands.query.QueryThings;
import org.eclipse.ditto.thingsearch.persistence.api.ThingsSearchPersistence;
import org.eclipse.ditto.thingsearch.persistence.api.model.ResultListImpl;
import org.eclipse.ditto.thingsearch.persistence.api.model.TimestampedThingId;
import org.eclipse.ditto.thingsearch.service.common.config.DittoSearchConfig;
import org.eclipse.ditto.thingsearch.service.persistence.query.QueryParser;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Pins {@link BenchSearchQueryPath}'s hand-copied {@code SearchActor} flow against the REAL {@code SearchActor}: a
 * command is sent to a {@code SearchActor} whose {@link ThingsSearchPersistence} only captures what it is asked, and
 * the captured query (rendered to SQL + binds by {@code SearchQueryAssembler}, as the PostgreSQL persistence renders
 * it) and subjects must equal what the benchmark produces for the same command. Covers a filtered find with the
 * default sort, a sorted OFFSET ({@code limit}) find, a sorted cursor find and a count (review round 1, M7).
 */
public final class BenchSearchQueryPathTest {

    private static final List<String> SUBJECTS = List.of("user:sub-000", "user:sub-041");
    private static final DittoHeaders HEADERS = DittoHeaders.newBuilder()
            .correlationId("bench-query-path-test")
            .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                    AuthorizationSubject.newInstance(SUBJECTS.get(0)),
                    AuthorizationSubject.newInstance(SUBJECTS.get(1))))
            .build();
    private static final String FILTER = "and(eq(attributes/location/city,\"Stuttgart\"),"
            + "ge(features/env/properties/temperature,-15.0),le(features/env/properties/temperature,-14.01))";
    private static final List<String> SORTED = List.of("sort(+features/env/properties/temperature)", "size(25)");

    private static ActorSystem system;
    private static QueryParser queryParser;
    private static DittoSearchConfig searchConfig;

    @BeforeClass
    public static void setUp() {
        try {
            DittoTracing.init(DefaultTracingConfig.of(ConfigFactory.parseString("tracing.enabled = false")));
        } catch (final IllegalStateException alreadyInitialized) {
            // another test in this JVM initialised tracing first — fine
        }
        system = ActorSystem.create("BenchSearchQueryPathTest", BenchSearchQueryPath.serviceConfig());
        searchConfig = DittoSearchConfig.of(DefaultScopedConfig.dittoScoped(system.settings().config()));
        queryParser = BenchSearchQueryPath.productionQueryParser(searchConfig, system);
    }

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(system);
    }

    @Test
    public void filteredFindWithDefaultSortMatchesTheSearchActor() {
        assertSameAsSearchActor(QueryThings.of(FILTER, List.of("size(25)"), null, null, HEADERS));
    }

    @Test
    public void sortedOffsetFindMatchesTheSearchActor() {
        assertSameAsSearchActor(QueryThings.of(null,
                List.of("sort(+features/env/properties/temperature)", "limit(2475,25)"), null, null, HEADERS));
    }

    @Test
    public void sortedCursorFindMatchesTheSearchActor() {
        final ResultListImpl<TimestampedThingId> previousPage = new ResultListImpl<>(
                List.of(new TimestampedThingId(ThingId.of("ns:thing-7"), Optional.empty())), 25L,
                JsonArray.of(JsonValue.of(12.5), JsonValue.of("ns:thing-7")));
        final String cursor = BenchSearchQueryPath.nextPageCursor(
                QueryThings.of(null, SORTED, null, null, HEADERS), previousPage).orElseThrow();

        final Query query = assertSameAsSearchActor(
                QueryThings.of(null, List.of("size(25)", "cursor(" + cursor + ")"), null, null, HEADERS));
        assertThat(render(query).bindValues()).contains("ns:thing-7");
    }

    @Test
    public void countMatchesTheSearchActor() {
        final CountThings command = CountThings.of(FILTER, null, HEADERS);
        final Captured captured = runThroughSearchActor(command);

        final Query expected = BenchSearchQueryPath.parseCountThings(queryParser, command);
        assertThat(captured.subjects()).isEqualTo(SUBJECTS);
        assertThat(sameSql(renderCount(captured.query()), renderCount(expected))).isTrue();
    }

    private static Query assertSameAsSearchActor(final QueryThings command) {
        final Captured captured = runThroughSearchActor(command);

        final Query expected = BenchSearchQueryPath.parseQueryThings(queryParser, command, system);
        assertThat(captured.subjects()).isEqualTo(SUBJECTS);
        assertThat(captured.query().getLimit()).isEqualTo(expected.getLimit());
        assertThat(captured.query().getSkip()).isEqualTo(expected.getSkip());
        assertThat(sameSql(render(captured.query()), render(expected)))
                .as("SQL the real SearchActor makes the persistence run == the benchmark's SQL:\n%s\n%s",
                        render(captured.query()), render(expected))
                .isTrue();
        return expected;
    }

    private record Captured(Query query, List<String> subjects) {}

    /** Sends {@code command} to a real SearchActor whose persistence captures the query it is asked to run. */
    private static Captured runThroughSearchActor(final Object command) {
        final CompletableFuture<Captured> captured = new CompletableFuture<>();
        final ThingsSearchPersistence persistence = (ThingsSearchPersistence) Proxy.newProxyInstance(
                BenchSearchQueryPathTest.class.getClassLoader(), new Class<?>[] {ThingsSearchPersistence.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findAll" -> {
                            @SuppressWarnings("unchecked") final List<String> subjects = (List<String>) args[1];
                            captured.complete(new Captured((Query) args[0], subjects));
                            return Source.single(new ResultListImpl<>(List.of(), -1));
                        }
                        case "count" -> {
                            @SuppressWarnings("unchecked") final List<String> subjects = (List<String>) args[1];
                            captured.complete(new Captured((Query) args[0], subjects));
                            return Source.single(0L);
                        }
                        case "toString" -> {
                            return "capturing ThingsSearchPersistence";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });

        final TestKit pubSub = new TestKit(system);
        final ActorRef searchActor = system.actorOf(SearchActor.props(queryParser, persistence, pubSub.getRef(),
                searchConfig.getSlowQueryLogConfig()));
        try {
            final var subscribe = pubSub.expectMsgClass(DistributedPubSubMediator.Subscribe.class);
            pubSub.reply(new DistributedPubSubMediator.SubscribeAck(subscribe));
            final TestKit client = new TestKit(system);
            searchActor.tell(command, client.getRef());
            final Captured result = captured.get(20, TimeUnit.SECONDS);
            client.expectMsgClass(Duration.ofSeconds(20), Object.class); // the response: the flow completed
            return result;
        } catch (final Exception e) {
            throw new AssertionError("SearchActor did not hand the command to the persistence", e);
        } finally {
            searchActor.tell(PoisonPill.getInstance(), ActorRef.noSender());
        }
    }

    private static RenderedSql render(final Query query) {
        return SearchQueryAssembler.findAll(query.getCriteria(), SUBJECTS, PostgresSortClause.of(query.getSortOptions()),
                (long) query.getLimit() + 1L, query.getSkip());
    }

    private static RenderedSql renderCount(final Query query) {
        return SearchQueryAssembler.count(query.getCriteria(), SUBJECTS, query.getSkip(), query.getLimit());
    }

    private static boolean sameSql(final RenderedSql a, final RenderedSql b) {
        if (!a.sql().equals(b.sql()) || a.bindValues().size() != b.bindValues().size()) {
            return false;
        }
        for (int i = 0; i < a.bindValues().size(); i++) {
            if (!java.util.Objects.deepEquals(a.bindValues().get(i), b.bindValues().get(i))) {
                return false;
            }
        }
        return true;
    }

}
