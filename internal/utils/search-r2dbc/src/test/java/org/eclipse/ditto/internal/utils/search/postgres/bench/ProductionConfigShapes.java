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

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The RQL equivalents of the Phase-0 read shapes 1-5 ({@link ReadPathBench}), with the SAME bind cycles (cities,
 * temperature buckets, gt thresholds, ilike substrings, subject pairs) so the production-config numbers stay
 * comparable to the original hand-written-SQL numbers. Every query built from these strings goes through the shipped
 * search path ({@code QueryParser} → {@code SearchQueryAssembler}/{@code CreateSqlVisitor}/{@code AuthFilterSqlBuilder}).
 * <p>
 * Mapping to the hand-written originals:
 * </p>
 * <ul>
 *   <li>shape 1 (and 5): {@code city = ? AND temperature BETWEEN lo AND lo+0.99} → RQL has no BETWEEN, so the range is
 *   {@code ge(...) + le(...)} on the same path (each its own {@code EXISTS} leg, as the translator renders any two
 *   conditions on one path — Mongo parity: they may match different array elements);</li>
 *   <li>shape 2: {@code gt(temperature, t)};</li>
 *   <li>shape 3: {@code ilike(path, "*s*")} on the low-cardinality {@code attributes/vendor} and on the
 *   high-cardinality wildcard {@code features/*}{@code /properties/prop0} (the original bench's wpath
 *   {@code /features/*}{@code /properties/prop0} IS the wildcard row family, so the RQL is the wildcard feature path);</li>
 *   <li>shape 4: no filter, {@code sort(+features/env/properties/temperature)} (the service appends {@code +thingId}),
 *   page size 25 — page 1, page 100 via {@code limit(2475,25)}, page 100 via the service cursor;</li>
 *   <li>shape 5: {@code count} with shape 1's filter.</li>
 * </ul>
 * <p>
 * The counter-examples of the selective-leg choice ({@link #ceAFilter}, {@link #ceBFilter}, {@link #ceCFilter}) check
 * that the leg the statement materialises is the smaller one in both directions.
 * </p>
 */
final class ProductionConfigShapes {

    static final String CITY_FIELD = "attributes/location/city";
    static final String TEMP_FIELD = "features/env/properties/temperature";
    static final String VENDOR_FIELD = "attributes/vendor";
    static final String ZIP_FIELD = "attributes/location/zip";
    static final String WILDCARD_FEATURE_TEXT_FIELD = "features/*/properties/prop0";

    /** Page size used by every find shape (the service default page size is also 25). */
    static final int PAGE_SIZE = 25;
    /** Page 100 starts at row 2476 (OFFSET 2475), exactly as the original shape-4 bench. */
    static final int PAGE_100_OFFSET = 2475;
    /**
     * A second shape-4 boundary past the sort path's NULL block (the original bench's populated-branch check used the
     * same OFFSET 5000), so the cursor anchor carries a real temperature value instead of null.
     */
    static final int POPULATED_BOUNDARY_OFFSET = 5000;

    private static final String[] CITIES = {
            "Stuttgart", "Berlin", "Munich", "Hamburg", "Cologne", "Frankfurt", "Leipzig", "Dresden", "Bonn", "Essen",
    };
    private static final double[] TEMP_BUCKET_LO = {-15, -10, -5, 0, 5, 10, 15, 20, 25, 30};
    private static final double[] GT_THRESHOLDS = {38, 39, 40, 41, 42, 43, 44};
    private static final String[] ILIKE_SUBSTRINGS = {"234", "246", "456", "567", "789", "321", "135", "890"};
    private static final String[] CE_ZIPS = {
            "12345", "23456", "34567", "45678", "56789", "67890", "78901", "89012", "90123", "11111",
    };

    /** Length of shape 1/5's filter rotation (city and temperature bucket advance together). */
    static final int SHAPE1_CYCLE = lcm(CITIES.length, TEMP_BUCKET_LO.length);
    /** Length of shape 2's threshold rotation. */
    static final int SHAPE2_CYCLE = GT_THRESHOLDS.length;
    /** Length of shape 3's substring rotation (both variants). */
    static final int SHAPE3_CYCLE = ILIKE_SUBSTRINGS.length;
    /** The first timed iteration: timed iterations keep the original bench's binds 3..22. */
    static final int FIRST_TIMED_ITERATION = 3;

    /** Length of the counter-examples' rotation (zips, cities and temperature buckets: 10 each). */
    static final int CE_CYCLE = 10;

    private ProductionConfigShapes() {
        throw new AssertionError("no instances");
    }

    /**
     * @param cycle the length of a shape's filter rotation (1 for a fixed request).
     * @return how many warmup iterations (0..n-1) a round runs so that every filter value of the rotation — every city
     * / threshold / pattern the timed iterations use — is read once before the timed iterations; at least 3, as in the
     * original bench (review round 2, N3). The warmup runs iterations {@code 0..n-1} with their own subject pairs, so the
     * timed iterations {@code 3..n-1} repeat warmup requests exactly (filter AND pair); in round 1 the timed iterations
     * {@code >= n} meet their subject pair for the first time (its filter value is warm, its {@code global_read} pages
     * may not be).
     */
    static int warmupIterations(final int cycle) {
        return Math.max(FIRST_TIMED_ITERATION, cycle);
    }

    private static int lcm(final int a, final int b) {
        int x = a;
        int y = b;
        while (y != 0) {
            final int t = y;
            y = x % y;
            x = t;
        }
        return a / x * b;
    }

    /** Shape 1 / shape 5 filter: city equality AND a ~1% temperature range. */
    static String shape1Filter(final int iteration) {
        final String city = CITIES[iteration % CITIES.length];
        final BigDecimal lo = BigDecimal.valueOf(TEMP_BUCKET_LO[iteration % TEMP_BUCKET_LO.length]);
        final BigDecimal hi = lo.add(new BigDecimal("0.99"));
        return "and(eq(" + CITY_FIELD + ",\"" + city + "\"),ge(" + TEMP_FIELD + "," + lo.toPlainString() + "),le("
                + TEMP_FIELD + "," + hi.toPlainString() + "))";
    }

    /** Shape 2 filter: numeric gt on one named feature property. */
    static String shape2Filter(final int iteration) {
        return "gt(" + TEMP_FIELD + "," + BigDecimal.valueOf(GT_THRESHOLDS[iteration % GT_THRESHOLDS.length])
                .toPlainString() + ")";
    }

    /** Shape 3-low filter: infix ilike on the low-cardinality attribute. */
    static String shape3LowFilter(final int iteration) {
        return ilike(VENDOR_FIELD, iteration);
    }

    /** Shape 3-high filter: infix ilike on the high-cardinality wildcard feature property. */
    static String shape3HighFilter(final int iteration) {
        return ilike(WILDCARD_FEATURE_TEXT_FIELD, iteration);
    }

    /** The find options of shapes 1-3 (the service's default sort, +thingId, is applied by the query builder). */
    static List<String> defaultFindOptions() {
        return List.of("size(" + PAGE_SIZE + ")");
    }

    /** Shape 4 page 1 (and the base command of the cursor walk). */
    static List<String> shape4FirstPageOptions() {
        return List.of("sort(+" + TEMP_FIELD + ")", "size(" + PAGE_SIZE + ")");
    }

    /** Shape 4 page via {@code limit(offset,25)} (OFFSET paging). */
    static List<String> shape4OffsetOptions(final int offset) {
        return List.of("sort(+" + TEMP_FIELD + ")", "limit(" + offset + "," + PAGE_SIZE + ")");
    }

    /** A rare zip equality (2–8 things) with a wide temperature range (~62 % of things): the point leg must win. */
    static String ceAFilter(final int iteration) {
        return "and(eq(" + ZIP_FIELD + ",\"" + CE_ZIPS[iteration % CE_ZIPS.length] + "\"),ge(" + TEMP_FIELD
                + ",0),le(" + TEMP_FIELD + ",100))";
    }

    /** Shape 1's city equality (~9 %) with a 0.09-wide temperature range (~0.14 %): the range leg must win. */
    static String ceBFilter(final int iteration) {
        final BigDecimal lo = BigDecimal.valueOf(TEMP_BUCKET_LO[iteration % TEMP_BUCKET_LO.length]);
        return "and(eq(" + CITY_FIELD + ",\"" + CITIES[iteration % CITIES.length] + "\"),ge(" + TEMP_FIELD + ","
                + lo.toPlainString() + "),le(" + TEMP_FIELD + "," + lo.add(new BigDecimal("0.09")).toPlainString()
                + "))";
    }

    /** A lone two-sided temperature range (~1.4 %): previously no CTE at all. */
    static String ceCFilter(final int iteration) {
        final BigDecimal lo = BigDecimal.valueOf(TEMP_BUCKET_LO[iteration % TEMP_BUCKET_LO.length]);
        return "and(ge(" + TEMP_FIELD + "," + lo.toPlainString() + "),le(" + TEMP_FIELD + ","
                + lo.add(new BigDecimal("0.99")).toPlainString() + "))";
    }

    /** Shape 4 next page via the service cursor (what a client sends: the cursor and the page size). */
    static List<String> shape4CursorOptions(final String cursor) {
        return List.of("size(" + PAGE_SIZE + ")", "cursor(" + cursor + ")");
    }

    /**
     * Two subjects per iteration, cycling over the corpus generator's 200-subject pool — identical to
     * {@link ReadPathBench}'s {@code subjectsFor}.
     */
    static List<String> subjectsFor(final int iteration) {
        final int a = (iteration * 7) % 200;
        final int b = (iteration * 7 + 41) % 200;
        return List.of(String.format(Locale.ROOT, "user:sub-%03d", a), String.format(Locale.ROOT, "user:sub-%03d", b));
    }

    private static String ilike(final String field, final int iteration) {
        return "ilike(" + field + ",\"*" + ILIKE_SUBSTRINGS[iteration % ILIKE_SUBSTRINGS.length] + "*\")";
    }

}
