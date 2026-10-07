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

import java.util.List;
import java.util.Optional;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.SelectiveLegs;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.RenderedSql;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.rql.query.Query;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.thingsearch.persistence.api.model.ResultListImpl;
import org.eclipse.ditto.thingsearch.persistence.api.model.TimestampedThingId;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * DB-free checks that every production-config bench shape is an RQL request the SHIPPED search path accepts, and that
 * the SQL rendered for it carries the production pieces the brief requires (global-read term, per-field auth
 * rechecks on the auth trees, the service's default sort/page size, OFFSET for {@code limit(...)}, the service
 * cursor's resume predicate for keyset paging, {@code count(*)} for counts).
 */
public final class ProductionConfigShapesTest {

    private static ActorSystem system;
    private static ProductionSearchPath path;

    @BeforeClass
    public static void setUp() {
        system = ActorSystem.create("ProductionConfigShapesTest", ProductionSearchPath.actorSystemConfig());
        path = new ProductionSearchPath(system);
    }

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(system);
    }

    @Test
    public void shape1RendersGlobalReadPerFieldAuthAndDefaultPaging() {
        final Query query = path.find(ProductionConfigShapes.shape1Filter(0), ProductionConfigShapes.defaultFindOptions());
        final RenderedSql sql = ProductionSearchPath.renderFind(query, ProductionConfigShapes.subjectsFor(0));

        assertThat(sql.sql()).contains("st.global_read &&").contains("policy_auth").contains("ORDER BY");
        assertThat(sql.bindValues()).contains("/attributes/location/city", "/features/env/properties/temperature",
                "Stuttgart");
        assertThat(query.getLimit()).isEqualTo(ProductionConfigShapes.PAGE_SIZE);
        assertThat(query.getSkip()).isZero();
        // findAll fetches pageLimit + 1 rows (has-next-page probe), OFFSET 0.
        assertThat(sql.bindValues()).contains(26L, 0L);
    }

    @Test
    public void warmupCoversEveryFilterValueTheTimedIterationsUse() {
        // Review round 2, N3: a round's warmup must pre-read every city / threshold / pattern its timed iterations use.
        assertWarmupCovers(ProductionConfigShapes::shape1Filter, ProductionConfigShapes.SHAPE1_CYCLE);
        assertWarmupCovers(ProductionConfigShapes::shape2Filter, ProductionConfigShapes.SHAPE2_CYCLE);
        assertWarmupCovers(ProductionConfigShapes::shape3LowFilter, ProductionConfigShapes.SHAPE3_CYCLE);
        assertWarmupCovers(ProductionConfigShapes::shape3HighFilter, ProductionConfigShapes.SHAPE3_CYCLE);
        // Timed iterations keep the original bench's binds 3..22.
        assertThat(ProductionConfigShapes.FIRST_TIMED_ITERATION).isEqualTo(3);
        assertThat(ProductionConfigShapes.warmupIterations(1)).isEqualTo(3);
        assertThat(ProductionConfigShapes.warmupIterations(ProductionConfigShapes.SHAPE1_CYCLE)).isEqualTo(10);
    }

    @Test
    public void everyReadBenchVariantWarmsUpOverItsWholeRotation() {
        // Review round 3, nit 4: pins the variant id -> rotation mapping (Variant.cycle()), so a renamed or added
        // variant cannot silently fall back to 3 warmups while its requests rotate.
        final List<ProductionConfigReadBench.Variant> variants =
                ProductionConfigReadBench.variants(new ProductionConfigReadBench.Cursors("c100", "c201"));
        final java.util.Map<String, Integer> warmups = new java.util.LinkedHashMap<>();
        variants.forEach(v -> warmups.put(v.id(), v.warmupIterations()));

        assertThat(warmups).containsExactly(
                java.util.Map.entry("1", 10), java.util.Map.entry("2", 7), java.util.Map.entry("3-low", 8),
                java.util.Map.entry("3-high", 8), java.util.Map.entry("4-page1", 3),
                java.util.Map.entry("4-offset", 3), java.util.Map.entry("4-keyset", 3),
                java.util.Map.entry("4-offset-5000", 3), java.util.Map.entry("4-keyset-5000", 3),
                java.util.Map.entry("5", 10), java.util.Map.entry("ce-a", 10), java.util.Map.entry("ce-a-sudo", 10),
                java.util.Map.entry("ce-b", 10), java.util.Map.entry("ce-c", 10));
        for (final ProductionConfigReadBench.Variant variant : variants) {
            final java.util.Set<String> distinctRequests = new java.util.HashSet<>();
            for (int i = 0; i < 20; i++) {
                final int iteration = ProductionConfigShapes.FIRST_TIMED_ITERATION + i;
                distinctRequests.add(variant.filter().apply(iteration) + " " + variant.options().apply(iteration));
            }
            assertThat(distinctRequests).as("rotation of variant %s", variant.id()).hasSize(variant.cycle());
        }
    }

    private static void assertWarmupCovers(final java.util.function.IntFunction<String> filter, final int cycle) {
        final java.util.Set<String> warmed = new java.util.HashSet<>();
        for (int i = 0; i < ProductionConfigShapes.warmupIterations(cycle); i++) {
            warmed.add(filter.apply(i));
        }
        final java.util.Set<String> timed = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            timed.add(filter.apply(ProductionConfigShapes.FIRST_TIMED_ITERATION + i));
        }
        assertThat(warmed).containsAll(timed);
        assertThat(timed).hasSize(cycle);
    }

    @Test
    public void everyFilterShapeParsesThroughTheServiceParser() {
        for (int i = 0; i < 23; i++) {
            assertThat(ProductionSearchPath.renderFind(
                    path.find(ProductionConfigShapes.shape1Filter(i), ProductionConfigShapes.defaultFindOptions()),
                    ProductionConfigShapes.subjectsFor(i)).sql()).isNotBlank();
            assertThat(ProductionSearchPath.renderFind(
                    path.find(ProductionConfigShapes.shape2Filter(i), ProductionConfigShapes.defaultFindOptions()),
                    ProductionConfigShapes.subjectsFor(i)).sql()).isNotBlank();
            assertThat(ProductionSearchPath.renderFind(
                    path.find(ProductionConfigShapes.shape3LowFilter(i), ProductionConfigShapes.defaultFindOptions()),
                    ProductionConfigShapes.subjectsFor(i)).sql()).contains("ILIKE");
            assertThat(ProductionSearchPath.renderCount(path.count(ProductionConfigShapes.shape1Filter(i)),
                    ProductionConfigShapes.subjectsFor(i)).sql()).contains("count(*)");
        }
    }

    @Test
    public void selectiveLegSummaryReadsTheCteLegFromTheBindsAndThePlan() {
        final RenderedSql sql = ProductionSearchPath.renderFind(
                path.find(ProductionConfigShapes.shape1Filter(0), ProductionConfigShapes.defaultFindOptions()),
                ProductionConfigShapes.subjectsFor(0));
        final String plan = "Limit  (cost=1.0..2.0 rows=1 width=88) (actual time=119.0..119.1 rows=0 loops=1)\n"
                + "  CTE sel_leg\n"
                + "    ->  Bitmap Heap Scan on search_flat sfc  (cost=10.68..824.41 rows=207 width=40) "
                + "(actual time=2.455..50.903 rows=17818 loops=1)\n"
                + "Execution Time: 119.114 ms\n";

        assertThat(ProductionConfigReadBench.selectiveLegSummary(sql, plan)).hasValueSatisfying(line -> assertThat(line)
                .contains("`(sfc.wpath = '/attributes/location/city' AND sfc.val_text = 'Stuttgart')`")
                .contains("`->  Bitmap Heap Scan on search_flat sfc`")
                .contains("17818 thing ids in 50.903 ms")
                .contains("of 119.114 ms total execution"));
        assertThat(ProductionConfigReadBench.selectiveLegSummary(ProductionSearchPath.renderFind(
                path.find(ProductionConfigShapes.shape2Filter(0), ProductionConfigShapes.defaultFindOptions()),
                ProductionConfigShapes.subjectsFor(0)), plan)).isEmpty();
        // the 2-argument render is the point-legs-only statement; shape 2 (one-sided range) has no leg in either mode
        final Query shape1 =
                path.find(ProductionConfigShapes.shape1Filter(0), ProductionConfigShapes.defaultFindOptions());
        assertThat(sql.sql()).isEqualTo(ProductionSearchPath.renderFind(shape1, ProductionConfigShapes.subjectsFor(0),
                SelectiveLegs.POINT).sql());
        assertThat(ProductionSearchPath.renderFind(
                path.find(ProductionConfigShapes.shape2Filter(0), ProductionConfigShapes.defaultFindOptions()),
                ProductionConfigShapes.subjectsFor(0), SelectiveLegs.POINT_AND_RANGE).sql()).doesNotContain("sel_leg");
    }

    @Test
    public void selectiveLegSummaryReadsThePickedBranchFromThePlan() {
        final RenderedSql sql = ProductionSearchPath.renderFind(
                path.find(ProductionConfigShapes.shape1Filter(0), ProductionConfigShapes.defaultFindOptions()),
                ProductionConfigShapes.subjectsFor(0), SelectiveLegs.POINT_AND_RANGE);
        final String plan = "Limit  (cost=1.0..2.0 rows=1 width=88) (actual time=150.0..150.1 rows=0 loops=1)\n"
                + "  CTE sel_pick\n"
                + "    ->  Subquery Scan on pc  (cost=1.00..2.00 rows=1 width=1) "
                + "(actual time=40.100..40.200 rows=1 loops=1)\n"
                + "  CTE sel_leg\n"
                + "    ->  Append  (cost=1.00..900.00 rows=300 width=40) "
                + "(actual time=40.300..45.500 rows=14012 loops=1)\n"
                + "          ->  Result  (cost=0.01..300.00 rows=100 width=40) "
                + "(actual time=0.002..0.002 rows=0 loops=1)\n"
                + "                One-Time Filter: $3\n"
                + "                InitPlan 3 (returns $3)\n"
                + "                  ->  CTE Scan on sel_pick sp  (cost=0.00..0.02 rows=1 width=1) "
                + "(actual time=40.150..40.250 rows=1 loops=1)\n"
                + "                ->  Bitmap Heap Scan on search_flat sfc  (cost=10.68..824.41 rows=207 width=40) "
                + "(never executed)\n"
                + "                      ->  Bitmap Index Scan on sf_text  (cost=0.00..10.63 rows=207 width=0) "
                + "(never executed)\n"
                + "          ->  Result  (cost=0.01..300.00 rows=100 width=40) "
                + "(actual time=0.100..4.000 rows=14000 loops=1)\n"
                + "                One-Time Filter: (NOT $5)\n"
                + "                InitPlan 5 (returns $5)\n"
                + "                  ->  CTE Scan on sel_pick sp_1  (cost=0.00..0.02 rows=1 width=1) "
                + "(actual time=0.001..0.001 rows=1 loops=1)\n"
                + "                ->  Index Scan using sf_num on search_flat sfc_1  (cost=0.56..290.00 rows=99 "
                + "width=40) (actual time=0.050..3.900 rows=14000 loops=1)\n"
                + "          ->  Result  (cost=0.01..300.00 rows=100 width=40) "
                + "(actual time=0.010..0.500 rows=12 loops=1)\n"
                + "                One-Time Filter: (NOT $7)\n"
                + "                InitPlan 7 (returns $7)\n"
                + "                  ->  CTE Scan on sel_pick sp_2  (cost=0.00..0.02 rows=1 width=1) "
                + "(actual time=0.001..0.001 rows=1 loops=1)\n"
                + "                ->  Index Only Scan using sf_multi on search_flat sfc_2  (cost=0.42..8.44 rows=1 "
                + "width=40) (actual time=0.010..0.400 rows=12 loops=1)\n"
                + "Execution Time: 150.114 ms\n";

        assertThat(sql.sql()).startsWith("WITH sel_pick AS MATERIALIZED");
        assertThat(ProductionConfigReadBench.selectiveLegSummary(sql, plan)).hasValueSatisfying(line -> assertThat(line)
                .contains("picked the range leg (in-range rows + multi-valued rows)")
                .contains("branches executed [point, range, multi-valued]: [false, true, true]")
                .contains("point: (sfc.wpath = '/attributes/location/city' AND sfc.val_text = 'Stuttgart')")
                .contains("range: (sfc.wpath = '/features/env/properties/temperature' AND sfc.val_num >= ")
                .contains("UNION ALL (sfc.wpath = '/features/env/properties/temperature' AND sfc.ord = 1)")
                .contains("`->  Append`) produced 14012 thing ids in 45.500 ms")
                .contains("the pick (`sel_pick`) took 40.200 ms")
                .contains("of 150.114 ms total execution"));
    }

    @Test
    public void counterExampleFiltersParseAndRotate() {
        for (int i = 0; i < ProductionConfigShapes.CE_CYCLE; i++) {
            for (final String filter : List.of(ProductionConfigShapes.ceAFilter(i), ProductionConfigShapes.ceBFilter(i),
                    ProductionConfigShapes.ceCFilter(i))) {
                assertThat(path.find(filter, ProductionConfigShapes.defaultFindOptions()).getCriteria())
                        .as(filter).isNotNull();
            }
        }
        assertThat(ProductionConfigShapes.ceAFilter(0)).isEqualTo("and(eq(attributes/location/zip,\"12345\"),"
                + "ge(features/env/properties/temperature,0),le(features/env/properties/temperature,100))");
        assertThat(ProductionConfigShapes.ceBFilter(0)).isEqualTo("and(eq(attributes/location/city,\"Stuttgart\"),"
                + "ge(features/env/properties/temperature,-15.0),le(features/env/properties/temperature,-14.91))");
        assertThat(ProductionConfigShapes.ceCFilter(1)).isEqualTo("and(ge(features/env/properties/temperature,-10.0),"
                + "le(features/env/properties/temperature,-9.01))");
    }

    @Test
    public void shape3HighUsesTheWildcardFeatureRowFamilyAndPerFeatureAuth() {
        final RenderedSql sql = ProductionSearchPath.renderFind(
                path.find(ProductionConfigShapes.shape3HighFilter(0), ProductionConfigShapes.defaultFindOptions()),
                ProductionConfigShapes.subjectsFor(0));

        assertThat(sql.sql()).contains("ILIKE").contains("features_auth");
        assertThat(sql.bindValues()).contains("/features/*/properties/prop0", "%234%");
    }

    @Test
    public void shape4OffsetPagingRendersOffset() {
        final Query query = path.find(null,
                ProductionConfigShapes.shape4OffsetOptions(ProductionConfigShapes.PAGE_100_OFFSET));
        final RenderedSql sql = ProductionSearchPath.renderFind(query, ProductionConfigShapes.subjectsFor(0));

        assertThat(query.getSkip()).isEqualTo(ProductionConfigShapes.PAGE_100_OFFSET);
        assertThat(sql.sql()).contains("LATERAL").contains("OFFSET");
        assertThat(sql.bindValues()).contains(26L, 2475L, "/features/env/properties/temperature");
    }

    @Test
    public void shape4CursorPagingAppliesTheServiceResumePredicate() {
        final List<String> firstPageOptions = ProductionConfigShapes.shape4FirstPageOptions();
        final Query firstPage = path.find(null, firstPageOptions);
        final RenderedSql firstPageSql = ProductionSearchPath.renderFind(firstPage, ProductionConfigShapes.subjectsFor(0));

        // A full page whose last row sorts at temperature 12.5 / thing "ns:thing-7".
        final ResultListImpl<TimestampedThingId> page = new ResultListImpl<>(
                List.of(new TimestampedThingId(ThingId.of("ns:thing-7"), Optional.empty())), 25L,
                JsonArray.of(JsonValue.of(12.5), JsonValue.of("ns:thing-7")));
        final Optional<String> cursor = ProductionSearchPath.nextPageCursor(null, firstPageOptions, page);
        assertThat(cursor).isPresent();

        final Query nextPage = path.find(null, ProductionConfigShapes.shape4CursorOptions(cursor.orElseThrow()));
        final RenderedSql nextPageSql = ProductionSearchPath.renderFind(nextPage, ProductionConfigShapes.subjectsFor(0));

        assertThat(nextPage.getSkip()).isZero();
        assertThat(nextPage.getSortOptions()).isEqualTo(firstPage.getSortOptions());
        assertThat(nextPage.getCriteria()).isNotEqualTo(firstPage.getCriteria());
        assertThat(nextPageSql.bindValues()).contains("ns:thing-7").hasSizeGreaterThan(firstPageSql.bindValues().size());
    }

}
