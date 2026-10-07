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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.SystemIndexProbe;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex.SystemSearchIndex;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.PostgresSortClause;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.SearchQueryAssembler;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.SelectiveLegs;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.RenderedSql;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.rql.query.Query;
import org.eclipse.ditto.thingsearch.persistence.api.model.ResultList;
import org.eclipse.ditto.thingsearch.persistence.api.model.TimestampedThingId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Production-config mode, step 2: the read shapes 1-5 measured on what SHIPS — the production schema only (loaded by
 * {@link ProductionConfigLoadBench}), every query built by the search service's own parser/cursor code and rendered
 * by {@code SearchQueryAssembler}/{@code CreateSqlVisitor}/{@code AuthFilterSqlBuilder} ({@link ProductionSearchPath}),
 * executed by {@code PostgresThingsSearchPersistence} over a pool built by the production {@code ConnectionPoolFactory}
 * from the shipped {@code ditto-postgres-search} profile ({@link ProductionSearchDb}: {@code plan_cache_mode =
 * force_custom_plan} as a startup GUC, 60 s statement timeout, unnamed statements). No hand-written SQL.
 * <p>
 * <b>Warm</b> ({@link #warm()}): shape by shape, {@code bench.pc.rounds} contiguous rounds (default 3), each a warmup
 * over the shape's whole filter rotation (every city / threshold / pattern the timed iterations use, at least 3
 * iterations) followed by 20 timed executions (the original bench's binds 3..22) of the persistence call (the service's
 * "database access" segment; parsing is not timed); nearest-rank p50/p95 per round and over all rounds, and whether
 * the per-round p95s settled (within {@link #SETTLED_SPREAD} or {@link #SETTLED_ABS_MS} of each other).
 * Right after a
 * shape's rounds: {@code EXPLAIN (ANALYZE, BUFFERS, SETTINGS)} of its iteration 0 and of its slowest iteration, on the
 * same pool and in the timed path's execution mode ({@link ProductionSearchDb#explain}: finds leader-only), then a
 * cross-check against the persistence's own result.
 * </p>
 * <p>
 * <b>Cold</b> ({@link #cold()}): per shape {@code bench.pc.coldSamples} (default 3) cycles of
 * {@link BenchDocker#coldRestart} (PG stop, Docker-VM page cache dropped, PG start) → fresh shipped pool → first
 * execution timed (+2 follow-ups for the warm-up slope), then one more cold cycle for a cold {@code EXPLAIN} (same
 * execution mode as above).
 * </p>
 * <p>
 * <b>Selective legs</b> ({@code bench.pc.rangeLegs}): {@code auto} renders and executes every statement as the service
 * does, i.e. with range legs while this instance sees the system index {@code sf_multi} valid; {@code off} forces point
 * legs only (the statement as before range legs). {@code bench.pc.variants} restricts the warm run to a list of
 * variant ids. <b>Invariance</b> ({@link #legInvariance()}): both modes must return the same results.
 * </p>
 * <p>
 * Evidence: {@code <bench.pc.resultsDir>/} ({@code environment.md}, one file per shape, {@code cold.md},
 * {@code summary.md}, and {@code warm.json}/{@code cold.json} from which {@code summary.md} is regenerated;
 * {@code invariance.md} from {@link #legInvariance()}).
 * Deliberately excluded from every automatic build phase; run via {@code benchmark/run-benchmark.sh pc-read pc-cold
 * pc-bias} (in that order: {@link #corpusBias()} reads the pages the first cold samples need) or
 * {@code mvn -pl internal/utils/search-r2dbc test -Dtest=ProductionConfigReadBench#warm -DfailIfNoTests=false}.
 * </p>
 */
public class ProductionConfigReadBench {

    static final double GATE_P95_MS = 200.0;

    private static final int TIMED = 20;
    private static final int FIRST_TIMED = ProductionConfigShapes.FIRST_TIMED_ITERATION;
    /**
     * A shape counts as settled when its per-round p95s lie within this relative spread of each other — or within
     * {@link #SETTLED_ABS_MS}, so that a few milliseconds of timer/JIT jitter on a ~50 ms query do not make it "did not
     * settle" (see {@link #settledLabel}).
     */
    static final double SETTLED_SPREAD = 0.10;
    static final double SETTLED_ABS_MS = 10.0;
    private static final int COLD_FOLLOW_UPS = 2;
    private static final List<String> FIXED_SUBJECTS = ProductionConfigShapes.subjectsFor(0);
    private static final String SUDO = "sudo (no auth)";
    private static final String REGION_FIELD = "attributes/region";

    private static final List<String> GUCS = List.of("server_version", "plan_cache_mode", "statement_timeout",
            "shared_buffers", "effective_cache_size", "work_mem", "maintenance_work_mem", "random_page_cost",
            "seq_page_cost", "effective_io_concurrency", "jit", "max_parallel_workers_per_gather",
            "max_parallel_workers", "max_worker_processes", "default_statistics_target", "enable_partitionwise_join",
            "server_encoding", "track_io_timing", "application_name");

    private ActorSystem system;
    private ProductionSearchPath path;
    private Path resultsDir;
    /** The per-path indexes the catalog check found during the warm run (written to warm.json and summary.md). */
    private List<String> perPathIndexes = List.of();
    /** The selective legs of this run, resolved once by the first {@link #newDb()} (see {@link #resolveLegs()}). */
    @Nullable private SelectiveLegs legs;
    /** How {@link #legs} was resolved, for {@code environment.md}. */
    private String legsLine = "not resolved";

    @Before
    public void setUp() {
        system = ActorSystem.create("ProductionConfigReadBench", ProductionSearchPath.actorSystemConfig());
        path = new ProductionSearchPath(system);
        resultsDir = BenchConfig.productionConfigResultsDir();
    }

    @After
    public void tearDown() {
        TestKit.shutdownActorSystem(system);
    }

    // =================================================================================================================
    // shapes
    // =================================================================================================================

    /**
     * One benchmarked request.
     *
     * @param id short id used in tables ("1", "3-high", "4-keyset", ...).
     * @param title human title.
     * @param file evidence file the variant is written to.
     * @param count whether it is a {@code count} (else {@code findAll}).
     * @param filter RQL filter per iteration ({@code null} = none).
     * @param options RQL options per iteration (ignored for counts).
     * @param subjects auth subjects per iteration.
     * @param request how the request is described in the evidence.
     */
    record Variant(String id, String title, String file, boolean count, IntFunction<String> filter,
            IntFunction<List<String>> options, IntFunction<List<String>> subjects, String request) {

        /** Length of this shape's filter rotation (1 = the same request every iteration). */
        int cycle() {
            return switch (id) {
                case "1", "5" -> ProductionConfigShapes.SHAPE1_CYCLE;
                case "2" -> ProductionConfigShapes.SHAPE2_CYCLE;
                case "3-low", "3-high" -> ProductionConfigShapes.SHAPE3_CYCLE;
                case "ce-a", "ce-a-sudo", "ce-b", "ce-c" -> ProductionConfigShapes.CE_CYCLE;
                default -> 1;
            };
        }

        /** Warmup iterations per round: the whole filter rotation, at least 3 (review round 2, N3). */
        int warmupIterations() {
            return ProductionConfigShapes.warmupIterations(cycle());
        }

        Query query(final ProductionSearchPath path, final int iteration) {
            return count ? path.count(filter.apply(iteration))
                    : path.find(filter.apply(iteration), options.apply(iteration));
        }

        RenderedSql render(final ProductionSearchPath path, final int iteration, final SelectiveLegs legs) {
            final Query query = query(path, iteration);
            return count ? ProductionSearchPath.renderCount(query, subjects.apply(iteration), legs)
                    : ProductionSearchPath.renderFind(query, subjects.apply(iteration), legs);
        }
    }

    /** The service cursors of the two shape-4 keyset boundaries (computed against the live corpus). */
    record Cursors(String page100, String page201) {}

    static List<Variant> variants(final Cursors cursors) {
        final List<Variant> variants = new ArrayList<>();
        final IntFunction<List<String>> defaultFind = i -> ProductionConfigShapes.defaultFindOptions();
        final IntFunction<List<String>> cycling = ProductionConfigShapes::subjectsFor;
        final IntFunction<List<String>> fixed = i -> FIXED_SUBJECTS;
        variants.add(new Variant("1", "eq + range AND (city = ?, temperature in [lo, lo+0.99])",
                "shape-1-eq-and-range.md", false, ProductionConfigShapes::shape1Filter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.shape1Filter(0) + "` (city and bucket cycle per iteration), "
                        + "options `" + ProductionConfigShapes.defaultFindOptions() + "`"));
        variants.add(new Variant("2", "gt range on one named feature property", "shape-2-gt-range.md", false,
                ProductionConfigShapes::shape2Filter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.shape2Filter(0) + "` (threshold cycles 38..44), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`"));
        variants.add(new Variant("3-low", "infix ilike, low-cardinality attribute", "shape-3-ilike.md", false,
                ProductionConfigShapes::shape3LowFilter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.shape3LowFilter(0) + "` (substring cycles), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`"));
        variants.add(new Variant("3-high", "infix ilike, high-cardinality wildcard feature property",
                "shape-3-ilike.md", false, ProductionConfigShapes::shape3HighFilter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.shape3HighFilter(0) + "` (substring cycles), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`"));
        variants.add(new Variant("4-page1", "sort by temperature, page 1", "shape-4-sort-paging.md", false,
                i -> null, i -> ProductionConfigShapes.shape4FirstPageOptions(), fixed,
                "no filter, options `" + ProductionConfigShapes.shape4FirstPageOptions() + "`"));
        variants.add(new Variant("4-offset", "sort by temperature, page 100 via OFFSET", "shape-4-sort-paging.md",
                false, i -> null, i -> ProductionConfigShapes.shape4OffsetOptions(
                ProductionConfigShapes.PAGE_100_OFFSET), fixed,
                "no filter, options `" + ProductionConfigShapes.shape4OffsetOptions(
                        ProductionConfigShapes.PAGE_100_OFFSET) + "`"));
        variants.add(new Variant("4-keyset", "sort by temperature, page 100 via the service cursor",
                "shape-4-sort-paging.md", false, i -> null,
                i -> ProductionConfigShapes.shape4CursorOptions(cursors.page100()), fixed,
                "no filter, options `[size(25), cursor(<cursor after page 99>)]`"));
        variants.add(new Variant("4-offset-5000", "sort by temperature, page 201 via OFFSET (populated anchor)",
                "shape-4-sort-paging.md", false, i -> null, i -> ProductionConfigShapes.shape4OffsetOptions(
                ProductionConfigShapes.POPULATED_BOUNDARY_OFFSET), fixed,
                "no filter, options `" + ProductionConfigShapes.shape4OffsetOptions(
                        ProductionConfigShapes.POPULATED_BOUNDARY_OFFSET) + "`"));
        variants.add(new Variant("4-keyset-5000", "sort by temperature, page 201 via the service cursor "
                + "(populated anchor)", "shape-4-sort-paging.md", false, i -> null,
                i -> ProductionConfigShapes.shape4CursorOptions(cursors.page201()), fixed,
                "no filter, options `[size(25), cursor(<cursor after row 5000>)]`"));
        variants.add(new Variant("5", "count with shape 1's filter", "shape-5-count.md", true,
                ProductionConfigShapes::shape1Filter, i -> List.of(), cycling,
                "count, filter `" + ProductionConfigShapes.shape1Filter(0) + "` (cycles like shape 1)"));
        variants.add(new Variant("ce-a", "rare zip equality + wide temperature range (the point leg must win)",
                "ce-leg-choice.md", false, ProductionConfigShapes::ceAFilter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.ceAFilter(0) + "` (zip cycles), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`"));
        variants.add(new Variant("ce-a-sudo",
                "rare zip equality + wide temperature range, sudo (the point leg must win)", "ce-leg-choice.md",
                false, ProductionConfigShapes::ceAFilter, defaultFind, i -> null,
                "filter `" + ProductionConfigShapes.ceAFilter(0) + "` (zip cycles), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`, no auth"));
        variants.add(new Variant("ce-b", "city equality + 0.09-wide temperature range (the range leg must win)",
                "ce-leg-choice.md", false, ProductionConfigShapes::ceBFilter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.ceBFilter(0) + "` (city and bucket cycle), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`"));
        variants.add(new Variant("ce-c", "lone two-sided temperature range (previously no CTE)", "ce-leg-choice.md",
                false, ProductionConfigShapes::ceCFilter, defaultFind, cycling,
                "filter `" + ProductionConfigShapes.ceCFilter(0) + "` (bucket cycles), options `"
                        + ProductionConfigShapes.defaultFindOptions() + "`"));
        return variants;
    }

    private Cursors computeCursors(final ProductionSearchDb db) {
        return new Cursors(cursorAfterRow(db, ProductionConfigShapes.PAGE_100_OFFSET),
                cursorAfterRow(db, ProductionConfigShapes.POPULATED_BOUNDARY_OFFSET));
    }

    /** The cursor the service hands out after the page that ends at row {@code row} (1-based). */
    private String cursorAfterRow(final ProductionSearchDb db, final int row) {
        final Query previousPage =
                path.find(null, ProductionConfigShapes.shape4OffsetOptions(row - ProductionConfigShapes.PAGE_SIZE));
        final ResultList<TimestampedThingId> page = db.findAll(previousPage, FIXED_SUBJECTS);
        return ProductionSearchPath.nextPageCursor(null, ProductionConfigShapes.shape4FirstPageOptions(), page)
                .orElseThrow(() -> new IllegalStateException("no next-page cursor after row " + row));
    }

    // =================================================================================================================
    // warm
    // =================================================================================================================

    @Test
    public void warm() throws Exception {
        final int rounds = BenchConfig.productionConfigRounds();
        final Instant started = Instant.now();
        try (ProductionSearchDb db = newDb()) {
            final Map<String, String> settings = db.show(GUCS);
            requireForceCustomPlan(settings);

            final Cursors cursors = computeCursors(db);
            final List<Variant> allVariants = variants(cursors);
            final String keysetCheck = keysetVsOffset(db, allVariants);
            final List<Variant> variants = selected(allVariants, BenchConfig.productionConfigVariants());

            // Shape by shape, its rounds back to back (review round 1, I2): each round is 3 warmup + 20 timed
            // executions immediately before one another, so a shape's numbers do not depend on which OTHER shape ran
            // before it. The plans are captured right after the shape's own rounds, before anything else touches the
            // pages (the cross-check / total-match executions of the same statements run after the plans, M4).
            final Map<String, String> sections = new LinkedHashMap<>();
            final List<JsonObject> json = new ArrayList<>();
            for (final Variant variant : variants) {
                final List<List<Sample>> perRound = new ArrayList<>();
                for (int round = 1; round <= rounds; round++) {
                    System.out.printf("warm shape %s round %d/%d%n", variant.id(), round, rounds);
                    for (int i = 0; i < variant.warmupIterations(); i++) {
                        executeTimed(db, variant, i);
                    }
                    final List<Sample> samples = new ArrayList<>();
                    for (int i = 0; i < TIMED; i++) {
                        samples.add(executeTimed(db, variant, FIRST_TIMED + i));
                    }
                    perRound.add(samples);
                    System.out.printf("  -> %s%n", Stats.of(ms(samples)));
                }
                final RenderedSql sql = variant.render(path, 0, legs());
                final String explain = db.explain(sql, !variant.count());
                final int slowest = slowestIteration(perRound);
                final String slowestExplain = db.explain(variant.render(path, slowest, legs()), !variant.count());
                final String crossCheck = crossCheck(db, variant, sql);
                final String totalMatches = totalMatches(db, variant);
                sections.merge(variant.file(), variantSection(variant, perRound, sql, explain, crossCheck,
                        totalMatches, slowest, slowestExplain), String::concat);
                json.add(warmJson(variant, perRound));
            }

            final String environment = environment(db, settings, started, rounds, keysetCheck);
            write("environment.md", environment);
            for (final Map.Entry<String, String> file : sections.entrySet()) {
                write(file.getKey(), fileHeader(file.getKey()) + file.getValue());
            }
            write("warm.json", JsonFactory.newObjectBuilder()
                    .set("generatedAt", started.toString())
                    .set("rounds", rounds)
                    .set("things", Long.parseLong(db.queryColumn("SELECT count(*) FROM search_things").get(0)))
                    .set("perPathIndexes", jsonArray(perPathIndexes.stream().map(JsonValue::of).toList()))
                    .set("variants", jsonArray(json))
                    .build().toString());
            writeSummary();
        }
    }

    /** @return the variants whose id is in {@code ids}, in their order; all of them when {@code ids} is empty. */
    static List<Variant> selected(final List<Variant> variants, final Set<String> ids) {
        if (ids.isEmpty()) {
            return variants;
        }
        final List<String> unknown = ids.stream()
                .filter(id -> variants.stream().noneMatch(v -> v.id().equals(id)))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown bench.pc.variants " + unknown);
        }
        return variants.stream().filter(v -> ids.contains(v.id())).toList();
    }

    private static void requireForceCustomPlan(final Map<String, String> settings) {
        if (!"force_custom_plan".equals(settings.get("plan_cache_mode"))) {
            throw new IllegalStateException("the shipped pool must bind plan_cache_mode=force_custom_plan, got "
                    + settings.get("plan_cache_mode"));
        }
    }

    /**
     * Records how the service-cursor ("keyset") pages relate to the OFFSET pages at the same position. They are NOT
     * expected to be equal: the cursor's resume filter ({@code ThingsSearchCursor.getNextPageFilter}, e.g.
     * {@code or(gt(sortPath, v), and(eq(sortPath, v), gt(thingId, id)))}) is an ordinary criteria, so the translator
     * applies the per-field read auth of the sort path to it, while the OFFSET page (no filter) is restricted by
     * {@code global_read} only (sort keys carry no auth). In this corpus {@code global_read} and {@code policy_auth}
     * are drawn independently, so most {@code global_read}-visible things are NOT readable at the policy root — the
     * only level at which the corpus grants on {@code /features/...}. Behind a populated anchor the cursor page then
     * holds only readable things; behind a {@code null} anchor the resume filter's {@code nor(exists(sortPath))}
     * branch (true whenever the sort path is missing OR not readable) admits the non-readable ones as well. The counts
     * below make that visible; an empty cursor page fails the run.
     */
    private String keysetVsOffset(final ProductionSearchDb db, final List<Variant> variants) throws SQLException {
        final StringBuilder sb = new StringBuilder();
        sb.append("The service cursor (\"keyset\") page and the `limit(offset,25)` page at the same position are ")
                .append("not the same request: the cursor's resume filter is an ordinary criteria and ")
                .append("gets the per-field read auth of the sort path, the OFFSET page only the `global_read` ")
                .append("restriction (sort keys carry no auth, plan §3.3 exception 3; Mongo shares the cursor code), ")
                .append("so they can differ. Behind a `null` anchor (inside the block of things without the sort ")
                .append("path) the filter's `nor(exists(sortPath))` branch is true for missing AND for non-readable ")
                .append("values, so non-readable things stay in; behind a populated anchor only readable things ")
                .append("follow. ")
                .append("\"root-readable\" = the subject pair is in the root `·g` and not in the root `·r` of ")
                .append("`policy_auth` — for `/features/env/properties/temperature` the only level this corpus ")
                .append("grants on.\n\n");
        for (final String[] pair : new String[][] {{"4-offset", "4-keyset"}, {"4-offset-5000", "4-keyset-5000"}}) {
            final List<String> offsetIds = ids(db.findAll(find(variants, pair[0]).query(path, 0), FIXED_SUBJECTS));
            final List<String> keysetIds = ids(db.findAll(find(variants, pair[1]).query(path, 0), FIXED_SUBJECTS));
            final long overlap = keysetIds.stream().filter(offsetIds::contains).count();
            final long keysetReadable = rootReadable(keysetIds);
            if (keysetIds.isEmpty()) {
                throw new AssertionError(pair[1] + " (service cursor page) returned no rows");
            }
            sb.append("- `").append(pair[1]).append("`: ").append(keysetIds.size()).append(" rows, ")
                    .append(keysetReadable).append(" root-readable; `").append(pair[0]).append("`: ")
                    .append(offsetIds.size()).append(" rows, ").append(rootReadable(offsetIds))
                    .append(" root-readable; rows in both pages: ").append(overlap).append(".\n");
        }
        return sb.toString();
    }

    private static long rootReadable(final List<String> thingIds) throws SQLException {
        try (Connection jdbc = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(),
                BenchConfig.password());
                java.sql.PreparedStatement ps = jdbc.prepareStatement("SELECT count(*) FROM search_things "
                        + "WHERE thing_id = ANY(?) AND COALESCE((policy_auth #> '{·g}') ??| ?, false) "
                        + "AND NOT COALESCE((policy_auth #> '{·r}') ??| ?, false)")) {
            ps.setArray(1, jdbc.createArrayOf("text", thingIds.toArray()));
            ps.setArray(2, jdbc.createArrayOf("text", FIXED_SUBJECTS.toArray()));
            ps.setArray(3, jdbc.createArrayOf("text", FIXED_SUBJECTS.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The EXPLAINed statement must return what the persistence returned for the same request. */
    private String crossCheck(final ProductionSearchDb db, final Variant variant, final RenderedSql sql) {
        final Query query = variant.query(path, 0);
        final List<String> subjects = variant.subjects().apply(0);
        if (variant.count()) {
            final long viaPersistence = db.count(query, subjects);
            final long direct = db.executeCount(sql);
            return "persistence count = " + viaPersistence + ", EXPLAINed statement count = " + direct
                    + " → identical: " + (viaPersistence == direct);
        }
        final List<String> viaPersistence = ids(db.findAll(query, subjects));
        final List<String> direct = db.executeThingIds(sql);
        final List<String> directPage = direct.subList(0, Math.min(direct.size(), query.getLimit()));
        return "persistence returned " + viaPersistence.size() + " ids; the EXPLAINed statement returned "
                + direct.size() + " rows (page + has-next probe) → same page, same order: "
                + viaPersistence.equals(directPage);
    }

    /** For filtered finds: the total match count for iteration 0, via the shipped count path (evidence only). */
    private String totalMatches(final ProductionSearchDb db, final Variant variant) {
        if (variant.count() || variant.filter().apply(0) == null) {
            return null;
        }
        final long total = db.count(path.count(variant.filter().apply(0)), variant.subjects().apply(0));
        return "Total things matching the iteration-0 request for its subject pair (shipped count path, untimed): "
                + total + ".";
    }

    // =================================================================================================================
    // corpus bias (read-only evidence; the pc-bias stage runs it AFTER warm AND cold: it reads exactly the pages the
    // first cold samples need, and the cold protocol cannot drop the macOS host cache — review round 2, N1)
    // =================================================================================================================

    /**
     * Counts that quantify how the corpus's independently drawn auth attributes bias the shapes (review round 1, I3):
     * production derives {@code global_read} from the grants and writes one {@code features_auth} entry per feature,
     * the generator does neither, so the per-field auth recheck discards most candidates before the expensive probes.
     * Writes {@code corpus-bias.md}. Read-only; not timed.
     */
    @Test
    public void corpusBias() throws Exception {
        final String pair = "'{" + String.join(",", FIXED_SUBJECTS) + "}'::text[]";
        final String visible = "SELECT st.thing_id, st.policy_auth FROM search_things st WHERE st.global_read && "
                + pair;
        final String[][] counts = {
                {"things whose `global_read` overlaps the pair (visible)", "SELECT count(*) FROM (" + visible + ") v"},
                {"visible things with the pair in the root `·g` and not in the root `·r` (root-readable)",
                        "SELECT count(*) FROM (" + visible + ") v WHERE COALESCE((v.policy_auth #> '{·g}') ?| " + pair
                                + ", false) AND NOT COALESCE((v.policy_auth #> '{·r}') ?| " + pair + ", false)"},
                {"things with city Stuttgart (shape 1/5 iteration 0: the CTE leg)",
                        "SELECT count(DISTINCT thing_id) FROM search_flat WHERE wpath = '/attributes/location/city' "
                                + "AND val_text = 'Stuttgart'"},
                {"visible things with city Stuttgart (candidates a consistent-auth corpus would keep)",
                        "SELECT count(*) FROM (" + visible + ") v WHERE EXISTS (SELECT 1 FROM search_flat f "
                                + "WHERE f.thing_id = v.thing_id AND f.wpath = '/attributes/location/city' "
                                + "AND f.val_text = 'Stuttgart')"},
                {"visible things with a temperature", "SELECT count(*) FROM (" + visible + ") v WHERE EXISTS "
                        + "(SELECT 1 FROM search_flat f WHERE f.thing_id = v.thing_id "
                        + "AND f.wpath = '/features/env/properties/temperature')"},
                {"visible things with temperature > 38", temperatureAbove(visible, 38)},
                {"visible things with temperature > 42", temperatureAbove(visible, 42)},
                {"visible things with temperature > 44", temperatureAbove(visible, 44)},
                {"features in the corpus", "SELECT sum((SELECT count(*) FROM jsonb_object_keys("
                        + "coalesce(thing -> 'features', '{}'::jsonb)))) FROM search_things"},
                {"features with a `features_auth` entry (production writes one per feature)",
                        "SELECT sum((SELECT count(*) FROM jsonb_object_keys(features_auth))) FROM search_things "
                                + "WHERE features_auth IS NOT NULL"},
        };
        final StringBuilder md = new StringBuilder();
        md.append("# Corpus auth bias (production-config baseline)\n\n");
        md.append("Generated by `ProductionConfigReadBench#corpusBias` on ").append(Instant.now())
                .append(" (read-only, after the timed runs). Subject pair: ").append(FIXED_SUBJECTS)
                .append(" (iteration 0 of every shape; the fixed pair of shape 4).\n\n");
        md.append("| count | value |\n|---|---:|\n");
        try (Connection jdbc = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(),
                BenchConfig.password()); Statement statement = jdbc.createStatement()) {
            for (final String[] count : counts) {
                try (ResultSet rs = statement.executeQuery(count[1].replace("?|", "??|"))) {
                    rs.next();
                    md.append("| ").append(count[0]).append(" | ").append(rs.getLong(1)).append(" |\n");
                }
            }
        }
        md.append("\nHow to read it: production derives `global_read` from the policy grants, so a visible thing is ")
                .append("readable and the per-field auth recheck keeps it; in this corpus it discards all but the ")
                .append("root-readable ones BEFORE the expensive probes (the shape plans show the survivors, e.g. ")
                .append("63 of Stuttgart's visible things in shape 1's iteration-0 plan). With consistent auth a ")
                .append("LIMIT-26 find probes candidates in `thing_id` order until 26 match: at `temperature > 44` ")
                .append("that is about 26 / (matches / visible) visible things.\n");
        write("corpus-bias.md", md.toString());
    }

    private static String temperatureAbove(final String visible, final int threshold) {
        return "SELECT count(*) FROM (" + visible + ") v WHERE EXISTS (SELECT 1 FROM search_flat f "
                + "WHERE f.thing_id = v.thing_id AND f.wpath = '/features/env/properties/temperature' "
                + "AND f.val_num > " + threshold + ")";
    }

    // =================================================================================================================
    // cold
    // =================================================================================================================

    @Test
    public void cold() throws Exception {
        final int samplesPerShape = BenchConfig.productionConfigColdSamples();
        final String container = BenchConfig.container();
        final String image = BenchConfig.image();
        final Instant started = Instant.now();
        final Cursors cursors;
        try (ProductionSearchDb db = newDb()) {
            requireForceCustomPlan(db.show(List.of("plan_cache_mode")));
            cursors = computeCursors(db);
        }
        final List<Variant> variants = variants(cursors);

        final StringBuilder md = new StringBuilder();
        md.append("# Cold first execution (production config)\n\n");
        md.append("Generated by `ProductionConfigReadBench#cold` on ").append(started).append(". Container `")
                .append(container).append("`, ").append(BenchDocker.describe(container)).append(".\n\n");
        md.append("**What \"cold\" means:** before every sample the bench PostgreSQL container is stopped (shared ")
                .append("buffers emptied), the Docker VM's Linux page cache is dropped (`sync; echo 3 > ")
                .append("/proc/sys/vm/drop_caches` from a `--privileged` container on the same VM — the VM holds ")
                .append("the data volume), the container is started again and a FRESH shipped pool is built; one ")
                .append("connection is opened (`SHOW plan_cache_mode`, untimed), then the shape's iteration-0 ")
                .append("request is timed through `PostgresThingsSearchPersistence` (first execution), followed ")
                .append("by ").append(COLD_FOLLOW_UPS).append(" repeats of the same request (warm-up slope). The ")
                .append("macOS host's own file cache of the Docker VM disk image is NOT dropped (needs root `sudo ")
                .append("purge`), so a read that misses the VM page cache is served from host RAM or from the SSD, ")
                .append("and which one is not controlled: these are \"PostgreSQL- and VM-cold, host-cache state ")
                .append("unknown\" numbers, not SSD-cold ones. The page-cache lines before/after each drop are ")
                .append("recorded below.\n\n");

        final List<JsonObject> json = new ArrayList<>();
        md.append("| shape | first execution ms (each sample) | median | repeats after the first (ms) |\n");
        md.append("|---|---|---:|---|\n");
        final StringBuilder plans = new StringBuilder();
        final StringBuilder drops = new StringBuilder();
        for (final Variant variant : variants) {
            final List<Double> firsts = new ArrayList<>();
            final List<List<Double>> repeats = new ArrayList<>();
            for (int s = 0; s < samplesPerShape; s++) {
                final BenchDocker.ColdRestart restart = BenchDocker.coldRestart(container, image);
                drops.append("- shape ").append(variant.id()).append(" sample ").append(s + 1).append(": before `")
                        .append(restart.before()).append("` after `").append(restart.after()).append("` (")
                        .append(restart.millis()).append(" ms stop/drop/start)\n");
                try (ProductionSearchDb db = newDb()) {
                    requireForceCustomPlan(db.show(List.of("plan_cache_mode")));
                    firsts.add(executeTimed(db, variant, 0).ms());
                    final List<Double> slope = new ArrayList<>();
                    for (int r = 0; r < COLD_FOLLOW_UPS; r++) {
                        slope.add(executeTimed(db, variant, 0).ms());
                    }
                    repeats.add(slope);
                }
                System.out.printf("cold shape %s sample %d: %.1f ms then %s%n", variant.id(), s + 1,
                        firsts.get(s), repeats.get(s));
            }
            BenchDocker.coldRestart(container, image);
            final String coldPlan;
            try (ProductionSearchDb db = newDb()) {
                requireForceCustomPlan(db.show(List.of("plan_cache_mode")));
                coldPlan = db.explain(variant.render(path, 0, legs()), !variant.count());
            }
            md.append("| ").append(variant.id()).append(" | ").append(fmtList(firsts)).append(" | ")
                    .append(fmt(median(firsts))).append(" | ").append(repeats.stream().map(this::fmtList)
                            .reduce((a, b) -> a + "; " + b).orElse("")).append(" |\n");
            plans.append("## Shape ").append(variant.id()).append(" — ").append(variant.title())
                    .append(": cold EXPLAIN (ANALYZE, BUFFERS, SETTINGS), iteration-0 binds, first execution after ")
                    .append("a cold restart, ").append(executionMode(variant)).append("\n\n```\n").append(coldPlan)
                    .append("```\n\n");
            json.add(JsonFactory.newObjectBuilder()
                    .set("id", variant.id())
                    .set("first", toJson(firsts))
                    .set("median", median(firsts))
                    .set("repeats", jsonArray(repeats.stream().map(this::toJson).toList()))
                    .build());
        }
        md.append("\n### Page-cache drops (Docker VM `/proc/meminfo`, kB)\n\n").append(drops).append('\n');
        md.append(plans);
        write("cold.md", md.toString());
        write("cold.json", JsonFactory.newObjectBuilder()
                .set("generatedAt", started.toString())
                .set("samplesPerShape", samplesPerShape)
                .set("variants", jsonArray(json))
                .build().toString());
        writeSummary();
    }

    // =================================================================================================================
    // selective-leg invariance
    // =================================================================================================================

    /**
     * The selective-leg choice changes plans, never results: every request of the variants with a two-sided range
     * ({@code 1}, {@code 5}, {@code ce-a}, {@code ce-a-sudo}, {@code ce-b}, {@code ce-c}) over its whole rotation, and
     * two filters on the array-heavy {@code attributes/region} (one straddling range, one empty interval), rendered
     * with point legs only and with range legs. Compared: (a) the request itself (the find page incl. the has-next row,
     * in order, or the count) and (b) the full sudo match list ordered by thing id (count + md5). For the region
     * filters both leader-only plans are appended. Writes {@code invariance.md}; fails at the end on any difference.
     */
    @Test
    public void legInvariance() throws Exception {
        final Instant started = Instant.now();
        final List<String> differences = new ArrayList<>();
        final StringBuilder md = new StringBuilder();
        md.append("# Selective-leg invariance (production config)\n\n");
        md.append("Generated by `ProductionConfigReadBench#legInvariance` on ").append(started).append(". Every ")
                .append("request rendered by `SearchQueryAssembler` with point legs only (`POINT`, the statement as ")
                .append("before range legs) and with range legs (`POINT_AND_RANGE`), executed directly on the shipped ")
                .append("pool. (a) = the request itself (find page incl. the has-next row in order, or the count); ")
                .append("(b) = the full sudo match list ordered by thing id (`findAllUnlimited`), as count and md5 ")
                .append("over `id + \"\\n\"`.\n\n");
        final StringBuilder plans = new StringBuilder();
        try (ProductionSearchDb db = newDb()) {
            requireForceCustomPlan(db.show(List.of("plan_cache_mode")));
            md.append("- Selective range legs as `pc-read` would run them: ").append(legsLine).append("\n\n");
            md.append("| request | iteration | subjects | (a) point legs | (a) range legs | (b) point legs | ")
                    .append("(b) range legs | same |\n|---|---:|---|---|---|---|---|:---:|\n");
            final List<Variant> variants = variants(new Cursors("", ""));
            for (final String id : List.of("1", "5", "ce-a", "ce-a-sudo", "ce-b", "ce-c")) {
                final Variant variant = find(variants, id);
                for (int i = 0; i < variant.cycle(); i++) {
                    System.out.printf("invariance %s iteration %d%n", id, i);
                    compareLegs(db, id, i, variant.query(path, i), variant.subjects().apply(i), variant.count(), md,
                            differences);
                }
            }
            for (final String filter : List.of(
                    "and(ge(" + REGION_FIELD + ",91700),le(" + REGION_FIELD + ",93000))",
                    "and(ge(" + REGION_FIELD + ",93000),le(" + REGION_FIELD + ",91700))")) {
                System.out.printf("invariance %s%n", filter);
                final Query query = path.find(filter, ProductionConfigShapes.defaultFindOptions());
                compareLegs(db, "`" + filter + "`", 0, query, null, false, md, differences);
                for (final SelectiveLegs mode : SelectiveLegs.values()) {
                    plans.append("### `").append(filter).append("`, ").append(mode).append(" (sudo find, ")
                            .append(executionMode(find(variants, "1"))).append(")\n\n```\n")
                            .append(db.explain(ProductionSearchPath.renderFind(query, null, mode), true))
                            .append("```\n\n");
                }
            }
        }
        md.append("\n**Differences: ").append(differences.size()).append("**").append(differences.isEmpty() ? ""
                : ": " + differences).append("\n\n## Plans of the `").append(REGION_FIELD)
                .append("` filters (the multi-valued branch on an array-heavy path)\n\n").append(plans);
        write("invariance.md", md.toString());
        if (!differences.isEmpty()) {
            throw new AssertionError("range legs changed results: " + differences);
        }
    }

    private void compareLegs(final ProductionSearchDb db, final String request, final int iteration, final Query query,
            @Nullable final List<String> subjects, final boolean count, final StringBuilder md,
            final List<String> differences) throws NoSuchAlgorithmException {
        final List<String> pointRequest = requestResult(db, query, subjects, count, SelectiveLegs.POINT);
        final List<String> rangeRequest = requestResult(db, query, subjects, count, SelectiveLegs.POINT_AND_RANGE);
        final List<String> pointList = db.ids(fullSudoList(query, SelectiveLegs.POINT));
        final List<String> rangeList = db.ids(fullSudoList(query, SelectiveLegs.POINT_AND_RANGE));
        final boolean same = pointRequest.equals(rangeRequest) && pointList.equals(rangeList);
        if (!same) {
            differences.add(request + " iteration " + iteration);
        }
        md.append("| ").append(request).append(" | ").append(iteration).append(" | ").append(subjectsOrSudo(subjects))
                .append(" | ").append(describe(pointRequest, count)).append(" | ")
                .append(describe(rangeRequest, count)).append(" | ").append(describe(pointList, false)).append(" | ")
                .append(describe(rangeList, false)).append(" | ").append(same ? "yes" : "**NO**").append(" |\n");
    }

    /** The find page incl. the has-next row, in order; or the count as a one-element list. */
    private static List<String> requestResult(final ProductionSearchDb db, final Query query,
            @Nullable final List<String> subjects, final boolean count, final SelectiveLegs mode) {
        return count
                ? List.of(String.valueOf(db.executeCount(ProductionSearchPath.renderCount(query, subjects, mode))))
                : db.executeThingIds(ProductionSearchPath.renderFind(query, subjects, mode));
    }

    private RenderedSql fullSudoList(final Query query, final SelectiveLegs mode) {
        return SearchQueryAssembler.findAllUnlimited(query.getCriteria(), null, PostgresSortClause.of(
                path.find(null, List.of("sort(+thingId)")).getSortOptions()), null, 0L, mode);
    }

    private static String describe(final List<String> result, final boolean count)
            throws NoSuchAlgorithmException {
        if (count) {
            return "count " + result.get(0);
        }
        final MessageDigest md5 = MessageDigest.getInstance("MD5");
        result.forEach(id -> md5.update((id + "\n").getBytes(StandardCharsets.UTF_8)));
        return result.size() + " ids, md5 `" + HexFormat.of().formatHex(md5.digest()) + "`";
    }

    // =================================================================================================================
    // measurement
    // =================================================================================================================

    /** One timed execution: rows = page rows returned (find) or the count value (count). */
    private record Sample(double ms, long rows) {}

    private Sample executeTimed(final ProductionSearchDb db, final Variant variant, final int iteration) {
        final Query query = variant.query(path, iteration); // the service's parse step: not timed
        final List<String> subjects = variant.subjects().apply(iteration);
        final long t0 = System.nanoTime();
        final long rows = variant.count() ? db.count(query, subjects) : db.findAll(query, subjects).size();
        return new Sample((System.nanoTime() - t0) / 1_000_000.0, rows);
    }

    /** Nearest-rank statistics in milliseconds. */
    record Stats(int n, double min, double p50, double p95, double max) {

        static Stats of(final List<Double> values) {
            final List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            return new Stats(sorted.size(), sorted.get(0), nearestRank(sorted, 50), nearestRank(sorted, 95),
                    sorted.get(sorted.size() - 1));
        }

        static double nearestRank(final List<Double> sorted, final int percentile) {
            final int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
            return sorted.get(Math.max(0, Math.min(rank - 1, sorted.size() - 1)));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "n=%d min=%.1f p50=%.1f p95=%.1f max=%.1f", n, min, p50, p95, max);
        }
    }

    private static List<Double> ms(final List<Sample> samples) {
        return samples.stream().map(Sample::ms).toList();
    }

    /** (max − min) / min of the per-round p95s. */
    static double roundP95Spread(final List<List<Sample>> rounds) {
        final double min = rounds.stream().mapToDouble(r -> Stats.of(ms(r)).p95()).min().orElse(0);
        return min <= 0 ? 0 : roundP95SpreadMs(rounds) / min;
    }

    /** max − min of the per-round p95s, in ms. */
    static double roundP95SpreadMs(final List<List<Sample>> rounds) {
        final double min = rounds.stream().mapToDouble(r -> Stats.of(ms(r)).p95()).min().orElse(0);
        final double max = rounds.stream().mapToDouble(r -> Stats.of(ms(r)).p95()).max().orElse(0);
        return max - min;
    }

    static boolean settled(final List<List<Sample>> rounds) {
        return roundP95Spread(rounds) <= SETTLED_SPREAD || roundP95SpreadMs(rounds) <= SETTLED_ABS_MS;
    }

    /**
     * "settled" when every round's p95 lies within {@link #SETTLED_SPREAD} (or {@link #SETTLED_ABS_MS}) of the lowest
     * one; "did not settle" otherwise — a statement about the numbers only (the per-round p95s differ by more than 10%
     * and more than 10 ms), not about the cause, which differs per shape (a working set that is still warming, a new
     * subject pair, per-bind plan variance). No single round is then presented as the warm figure (review round 2/3).
     */
    static String settledLabel(final List<List<Sample>> rounds) {
        return settled(rounds) ? "settled" : "did not settle";
    }

    private static List<Double> pooled(final List<List<Sample>> rounds) {
        final List<Double> all = new ArrayList<>();
        rounds.forEach(r -> all.addAll(ms(r)));
        return all;
    }

    // =================================================================================================================
    // evidence
    // =================================================================================================================

    /**
     * @return the iteration index ({@code FIRST_TIMED + i}) of the slowest timed execution of the LAST round, whose plan
     * is captured next to the iteration-0 plan.
     */
    private static int slowestIteration(final List<List<Sample>> rounds) {
        final List<Sample> last = rounds.get(rounds.size() - 1);
        int slowest = 0;
        for (int i = 1; i < last.size(); i++) {
            if (last.get(i).ms() > last.get(slowest).ms()) {
                slowest = i;
            }
        }
        return FIRST_TIMED + slowest;
    }

    private String variantSection(final Variant variant, final List<List<Sample>> rounds, final RenderedSql sql,
            final String explain, final String crossCheck, @Nullable final String totalMatches,
            final int slowestIteration, final String slowestExplain) {
        final StringBuilder sb = new StringBuilder();
        sb.append("## Shape ").append(variant.id()).append(" — ").append(variant.title()).append("\n\n");
        sb.append("Request (as a client sends it, parsed by the service's `QueryParser`): ").append(variant.request())
                .append(". Auth subjects: ").append(subjectsLabel(variant)).append(".\n\n");
        sb.append("### Warm latency (per round: ").append(variant.warmupIterations()).append(" warmup iterations 0..")
                .append(variant.warmupIterations() - 1).append(" covering the whole filter rotation of ")
                .append(variant.cycle()).append(", then ").append(TIMED).append(" timed iterations ").append(FIRST_TIMED)
                .append("..").append(FIRST_TIMED + TIMED - 1)
                .append("; `PostgresThingsSearchPersistence` call, nearest-rank)\n\n");
        sb.append("| round | n | min ms | p50 ms | p95 ms | max ms | rows per execution (min–max) |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---|\n");
        for (int r = 0; r < rounds.size(); r++) {
            final Stats stats = Stats.of(ms(rounds.get(r)));
            sb.append("| ").append(r + 1).append(" | ").append(stats.n()).append(" | ").append(fmt(stats.min()))
                    .append(" | ").append(fmt(stats.p50())).append(" | ").append(fmt(stats.p95())).append(" | ")
                    .append(fmt(stats.max())).append(" | ").append(rowRange(rounds.get(r))).append(" |\n");
        }
        final Stats all = Stats.of(pooled(rounds));
        sb.append("| **all rounds** | ").append(all.n()).append(" | ").append(fmt(all.min())).append(" | **")
                .append(fmt(all.p50())).append("** | **").append(fmt(all.p95())).append("** | ")
                .append(fmt(all.max())).append(" | |\n\n");
        sb.append("Round to round: per-round p95 spread ").append(fmt(100 * roundP95Spread(rounds))).append("% (")
                .append(fmt(roundP95SpreadMs(rounds))).append(" ms) → **").append(settledLabel(rounds))
                .append("** (settled = all per-round p95 within ").append(fmt(100 * SETTLED_SPREAD))
                .append("% or ").append(fmt(SETTLED_ABS_MS)).append(" ms of the lowest).\n\n");
        sb.append("Warm p95 (all rounds) vs the 200 ms read gate: **")
                .append(all.p95() <= GATE_P95_MS ? "PASS" : "MISS").append("** (").append(settledLabel(rounds))
                .append("); worst single-round p95: ")
                .append(fmt(rounds.stream().mapToDouble(r -> Stats.of(ms(r)).p95()).max().orElse(0))).append(" ms.\n\n");
        if (totalMatches != null) {
            sb.append(totalMatches).append("\n\n");
        }
        sb.append("### SQL (rendered by `SearchQueryAssembler` for iteration 0, exactly as ")
                .append("`PostgresThingsSearchPersistence` renders it)\n\n```sql\n").append(sql.sql())
                .append("\n```\n\nBinds (`$1`…): `").append(sql.bindValues().stream()
                        .map(ProductionConfigReadBench::bindToString).toList()).append("`\n\n");
        sb.append("Cross-check: ").append(crossCheck).append("\n\n");
        selectiveLegSummary(sql, explain).ifPresent(line -> sb.append(line).append("\n\n"));
        sb.append("### EXPLAIN (ANALYZE, BUFFERS, SETTINGS) — warm, iteration-0 binds, right after this shape's own ")
                .append("rounds (before the cross-check executions), same shipped pool, ").append(executionMode(variant))
                .append("\n\n```\n").append(explain).append("```\n\n");
        sb.append("### Per-iteration latency (ms per round) and request\n\n| iteration | ");
        for (int r = 0; r < rounds.size(); r++) {
            sb.append("round ").append(r + 1).append(" | ");
        }
        sb.append("rows (last round) | filter / options | subjects |\n|---:|");
        sb.append("---:|".repeat(rounds.size())).append("---:|---|---|\n");
        for (int i = 0; i < TIMED; i++) {
            final int iteration = FIRST_TIMED + i;
            sb.append("| ").append(iteration).append(" | ");
            for (final List<Sample> round : rounds) {
                sb.append(fmt(round.get(i).ms())).append(" | ");
            }
            sb.append(rounds.get(rounds.size() - 1).get(i).rows()).append(" | `")
                    .append(variant.count() || variant.filter().apply(iteration) != null
                            ? variant.filter().apply(iteration) : String.valueOf(variant.options().apply(iteration)))
                    .append("` | ").append(subjectsOrSudo(variant.subjects().apply(iteration))).append(" |\n");
        }
        sb.append("\n### EXPLAIN (ANALYZE, BUFFERS, SETTINGS) — warm, the slowest iteration of the last round (")
                .append(slowestIteration).append("), ").append(executionMode(variant)).append("\n\n");
        selectiveLegSummary(variant.render(path, slowestIteration, legs()), slowestExplain)
                .ifPresent(line -> sb.append(line.replace("(warm EXPLAIN below)", "(EXPLAIN below)")).append("\n\n"));
        sb.append("```\n").append(slowestExplain).append("```\n\n");
        return sb.toString();
    }

    private static String subjectsLabel(final Variant variant) {
        final List<String> first = variant.subjects().apply(0);
        if (first == null) {
            return SUDO;
        }
        return first.equals(FIXED_SUBJECTS) && FIXED_SUBJECTS.equals(variant.subjects().apply(1))
                ? "fixed pair " + FIXED_SUBJECTS : "2 per iteration, cycling (iteration 0: " + first + ")";
    }

    private static String subjectsOrSudo(@Nullable final List<String> subjects) {
        return subjects == null ? SUDO : subjects.toString();
    }

    /** How the plan was executed, stated next to every EXPLAIN (review round 1, I1). */
    private static String executionMode(final Variant variant) {
        return variant.count()
                ? "executed as the shipped `count` executes (no fetch size: parallel workers allowed)"
                : "executed leader-only as the shipped `findAll` executes (fetch size 100 ⇒ `Execute` with a row "
                + "limit ⇒ no parallel workers; emulated with `SET LOCAL max_parallel_workers = 0`, which leaves the "
                + "plan choice unchanged)";
    }

    private static final java.util.regex.Pattern ACTUAL =
            java.util.regex.Pattern.compile("actual time=([0-9.]+)\\.\\.([0-9.]+) rows=(\\d+) loops=(\\d+)");
    private static final java.util.regex.Pattern EXECUTION_TIME =
            java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms");

    /**
     * For statements with the selective-leg CTE: which leg it materialised (the CTE body's {@code wpath}/value binds),
     * how many thing ids, and how long the CTE body node took relative to the whole execution — read off the plan.
     * With a point and a range leg ({@code sel_pick} first) also which of them the statement picked: the {@code sel_leg}
     * branches gated off by their one-time filter are {@code (never executed)} — and how long the pick took.
     */
    static java.util.Optional<String> selectiveLegSummary(final RenderedSql sql, final String explain) {
        final List<String> branches = cteBranches(sql);
        if (branches.isEmpty()) {
            return java.util.Optional.empty();
        }
        final String[] lines = explain.split("\n");
        final int legLine = cteLine(lines, "CTE sel_leg");
        final String cteNode = legLine >= 0 && legLine + 1 < lines.length ? lines[legLine + 1] : null;
        final java.util.regex.Matcher node = cteNode == null ? null : ACTUAL.matcher(cteNode);
        final java.util.regex.Matcher total = EXECUTION_TIME.matcher(explain);
        final StringBuilder sb = new StringBuilder("Selective-leg CTE (`sel_leg`): ");
        if (branches.size() == 1) {
            sb.append("materialised the leg `").append(cteCondition(sql)).append('`');
        } else if (branches.size() == 2) {
            sb.append("materialised the range leg (in-range rows + multi-valued rows) `").append(cteCondition(sql))
                    .append('`');
        } else {
            final List<Boolean> executed = legLine < 0 ? List.of() : executedBranches(lines, legLine);
            sb.append("picked ").append(chosenLeg(executed))
                    .append(" (branches executed [point, range, multi-valued]: ").append(executed).append(") of `")
                    .append(cteCondition(sql)).append('`');
        }
        if (node != null && node.find()) {
            sb.append("; its body node (`").append(cteNode.trim().replaceAll("\\s+\\(cost=.*", "")).append("`) produced ")
                    .append(node.group(3)).append(" thing ids in ").append(node.group(2)).append(" ms (loops=")
                    .append(node.group(4)).append(')');
        }
        final int pickLine = cteLine(lines, "CTE sel_pick");
        if (pickLine >= 0 && pickLine + 1 < lines.length) {
            final java.util.regex.Matcher pick = ACTUAL.matcher(lines[pickLine + 1]);
            if (pick.find()) {
                sb.append("; the pick (`sel_pick`) took ").append(pick.group(2)).append(" ms");
            }
        }
        if (total.find()) {
            sb.append(" of ").append(total.group(1)).append(" ms total execution (warm EXPLAIN below)");
        }
        return java.util.Optional.of(sb.append('.').toString());
    }

    private static int cteLine(final String[] lines, final String cte) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(cte)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return for each {@code Result} with a {@code One-Time Filter} in the {@code sel_leg} subtree (the pick's gated
     * {@code UNION ALL} branches, in order: point, range, multi-valued), whether a node below it other than the
     * {@code sel_pick} scan ran.
     */
    private static List<Boolean> executedBranches(final String[] lines, final int cteLine) {
        final int cteIndent = indent(lines[cteLine]);
        int end = cteLine + 1;
        while (end < lines.length && indent(lines[end]) > cteIndent) {
            end++;
        }
        final List<Boolean> executed = new ArrayList<>();
        for (int i = cteLine + 1; i < end; i++) {
            if (lines[i].trim().startsWith("->  Result") && hasOneTimeFilter(lines, i, end)) {
                final int resultIndent = indent(lines[i]);
                boolean ran = false;
                for (int j = i + 1; j < end && indent(lines[j]) > resultIndent; j++) {
                    final String line = lines[j].trim();
                    if (line.startsWith("->  ") && !line.contains("sel_pick") && !line.contains("(never executed)")) {
                        ran = true;
                    }
                }
                executed.add(ran);
            }
        }
        return executed;
    }

    /** Whether the node at {@code nodeLine} lists a {@code One-Time Filter} among its own (non-child) lines. */
    private static boolean hasOneTimeFilter(final String[] lines, final int nodeLine, final int end) {
        final int nodeIndent = indent(lines[nodeLine]);
        for (int j = nodeLine + 1; j < end && indent(lines[j]) > nodeIndent; j++) {
            final String line = lines[j].trim();
            if (line.startsWith("->  ") || line.startsWith("InitPlan") || line.startsWith("SubPlan")) {
                return false;
            }
            if (line.startsWith("One-Time Filter:")) {
                return true;
            }
        }
        return false;
    }

    private static int indent(final String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String chosenLeg(final List<Boolean> executed) {
        if (executed.size() != 3) {
            return "an undetermined leg";
        }
        if (executed.get(0) && !executed.get(1) && !executed.get(2)) {
            return "the point leg";
        }
        if (!executed.get(0) && (executed.get(1) || executed.get(2))) {
            return "the range leg (in-range rows + multi-valued rows)";
        }
        return "an undetermined leg";
    }

    private static final String LEG_CTE_START = "sel_leg AS MATERIALIZED (";
    private static final String UNION_ALL = " UNION ALL ";
    private static final String WHERE = " WHERE ";
    private static final String PICK_GATE = " AND (SELECT sp.point_wins FROM sel_pick sp))";
    private static final String PICK_GATE_NEGATED = " AND NOT (SELECT sp.point_wins FROM sel_pick sp))";
    private static final java.util.regex.Pattern BIND = java.util.regex.Pattern.compile("\\$(\\d+)");

    /**
     * @return the candidate conditions of the selective-leg CTE with their {@code $n} binds substituted, read from the
     * rendered SQL (review round 1, M8: independent of which operator and bind positions the leg uses): the point leg
     * alone; {@code range: <in range> UNION ALL <multi-valued>}; or, with the pick,
     * {@code point: …; range: … UNION ALL …}.
     */
    static String cteCondition(final RenderedSql sql) {
        final List<String> branches = cteBranches(sql);
        return switch (branches.size()) {
            case 0 -> "?";
            case 1 -> branches.get(0);
            case 2 -> "range: " + branches.get(0) + UNION_ALL.stripTrailing() + " " + branches.get(1);
            default -> "point: " + branches.get(0) + "; range: " + branches.get(1) + UNION_ALL.stripTrailing() + " "
                    + branches.get(2);
        };
    }

    /**
     * @return the {@code WHERE} condition of each {@code UNION ALL} branch of {@code sel_leg} (without the pick's
     * gate), binds substituted; empty if the statement has no selective-leg CTE.
     */
    private static List<String> cteBranches(final RenderedSql sql) {
        final String text = sql.sql();
        final int start = text.indexOf(LEG_CTE_START);
        if (start < 0 || !(text.startsWith("WITH sel_leg AS MATERIALIZED")
                || text.startsWith("WITH sel_pick AS MATERIALIZED"))) {
            return List.of();
        }
        final List<String> bodies = new ArrayList<>();
        int depth = 1;
        int branchStart = start + LEG_CTE_START.length();
        int i = branchStart;
        while (i < text.length() && depth > 0) {
            final char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 1 && text.startsWith(UNION_ALL, i)) {
                bodies.add(text.substring(branchStart, i));
                branchStart = i + UNION_ALL.length();
                i = branchStart - 1;
            }
            i++;
        }
        bodies.add(text.substring(branchStart, i - 1));
        return bodies.stream()
                .map(body -> withoutPickGate(body.substring(body.indexOf(WHERE) + WHERE.length())))
                .map(condition -> withBinds(condition, sql))
                .toList();
    }

    private static String withoutPickGate(final String condition) {
        for (final String gate : List.of(PICK_GATE, PICK_GATE_NEGATED)) {
            if (condition.endsWith(gate)) {
                return condition.substring(1, condition.length() - gate.length());
            }
        }
        return condition;
    }

    private static String withBinds(final String condition, final RenderedSql sql) {
        final java.util.regex.Matcher bind = BIND.matcher(condition);
        final StringBuilder out = new StringBuilder();
        while (bind.find()) {
            final Object value = sql.bindValues().get(Integer.parseInt(bind.group(1)) - 1);
            final String rendered = value instanceof String text ? "'" + text + "'" : bindToString(value);
            bind.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rendered));
        }
        bind.appendTail(out);
        return out.toString();
    }

    private static String bindToString(final Object bind) {
        if (bind instanceof String[] array) {
            return "{" + String.join(",", array) + "}";
        } else if (bind instanceof Object[] array) {
            return java.util.Arrays.toString(array);
        }
        return String.valueOf(bind);
    }

    private static String rowRange(final List<Sample> samples) {
        final long min = samples.stream().mapToLong(Sample::rows).min().orElse(0);
        final long max = samples.stream().mapToLong(Sample::rows).max().orElse(0);
        return min == max ? String.valueOf(min) : min + "–" + max;
    }

    private String fileHeader(final String file) {
        return "# " + file.replace(".md", "") + " — production config (shipped schema + shipped translator)\n\n"
                + "Generated by `ProductionConfigReadBench#warm`. Environment, server settings and the catalog check: "
                + "`environment.md`; cold numbers and cold plans: `cold.md`; headline table: `summary.md`.\n\n";
    }

    private JsonObject warmJson(final Variant variant, final List<List<Sample>> rounds) {
        final Stats all = Stats.of(pooled(rounds));
        return JsonFactory.newObjectBuilder()
                .set("id", variant.id())
                .set("title", variant.title())
                .set("file", variant.file())
                .set("n", all.n())
                .set("min", round1(all.min()))
                .set("p50", round1(all.p50()))
                .set("p95", round1(all.p95()))
                .set("max", round1(all.max()))
                .set("roundP50", toJson(rounds.stream().map(r -> Stats.of(ms(r)).p50()).toList()))
                .set("roundP95", toJson(rounds.stream().map(r -> Stats.of(ms(r)).p95()).toList()))
                .set("roundP95Spread", round1(100 * roundP95Spread(rounds)))
                .set("roundP95SpreadMs", round1(roundP95SpreadMs(rounds)))
                .set("settled", settled(rounds))
                .build();
    }

    private String environment(final ProductionSearchDb db, final Map<String, String> settings, final Instant started,
            final int rounds, final String keysetCheck) throws SQLException {
        final PostgresConfig config = db.config();
        final StringBuilder sb = new StringBuilder();
        sb.append("# Environment of the production-config search benchmark\n\n");
        sb.append("Generated by `ProductionConfigReadBench#warm` on ").append(started).append(" (").append(rounds)
                .append(" warm rounds).\n\n");
        sb.append("## Docker\n\n- ").append(BenchDocker.describe(BenchConfig.container())).append("\n- VM: ")
                .append(BenchDocker.dockerVm()).append("\n\n");
        sb.append("## Shipped client configuration (from `ditto-postgres-search.conf` → `ditto-postgres-client.conf`, ")
                .append("only uri/user/password/ssl.mode overridden)\n\n");
        sb.append("- uri: `").append(config.getUri()).append("`\n");
        sb.append("- force-custom-plan: ").append(config.isForceCustomPlan()).append('\n');
        sb.append("- statement-timeout: ").append(config.getStatementTimeout()).append('\n');
        sb.append("- pool: max-size ").append(config.getPoolConfig().getMaxSize()).append(", fetch-size ")
                .append(config.getPoolConfig().getFetchSize()).append(", prepared-statement-cache-queries ")
                .append(config.getPoolConfig().getPreparedStatementCacheQueries()).append(", max-acquire-time ")
                .append(config.getPoolConfig().getMaxAcquireTime()).append('\n');
        sb.append("- read fetch size: `PostgresThingsSearchPersistence.DEFAULT_FETCH_SIZE` (100) per statement\n");
        sb.append("- Selective range legs: ").append(legsLine).append("\n\n");
        sb.append("## Session settings as a pooled connection sees them (`SHOW`)\n\n| setting | value |\n|---|---|\n");
        settings.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        try (Connection jdbc = DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(),
                BenchConfig.password()); Statement statement = jdbc.createStatement()) {
            sb.append("\n## Server settings not at their built-in default (`pg_settings`)\n\n")
                    .append("| name | setting | unit | source |\n|---|---|---|---|\n");
            try (ResultSet rs = statement.executeQuery("SELECT name, setting, coalesce(unit, ''), source "
                    + "FROM pg_settings WHERE source NOT IN ('default', 'override', 'client', 'session') "
                    + "ORDER BY name")) {
                while (rs.next()) {
                    sb.append("| ").append(rs.getString(1)).append(" | ").append(rs.getString(2)).append(" | ")
                            .append(rs.getString(3)).append(" | ").append(rs.getString(4)).append(" |\n");
                }
            }
            try (ResultSet rs = statement.executeQuery("SELECT datcollate, datctype, "
                    + "pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database()")) {
                rs.next();
                sb.append("\nDatabase collation / ctype / encoding: `").append(rs.getString(1)).append("` / `")
                        .append(rs.getString(2)).append("` / `").append(rs.getString(3)).append("`\n");
            }
            final ProductionConfigSchema.CatalogCheck check = ProductionConfigSchema.verify(jdbc);
            sb.append("\n## Catalog check against the production DDL\n\n")
                    .append("Every index definition, column, constraint, reloption, per-column statistics target and ")
                    .append("extended statistics object of the search tables, compared with a reference built from ")
                    .append("`PostgresSearchSchema.ddlStatements()` and the system index `sf_multi` in a rolled-back ")
                    .append("scratch schema.\n\n")
                    .append("- matches production: **").append(check.productionVerdict()).append("** (")
                    .append(check.comparedItems()).append(" items compared)\n- indexes: ").append(check.actualIndexes())
                    .append("\n- differences: ").append(check.differences())
                    .append("\n- per-path indexes (`ditto.postgresql.search.path-indexes`, built outside the ")
                    .append("production DDL, not compared): ").append(check.perPathIndexes()).append("\n\n");
            if (!check.matchesProduction()) {
                throw new AssertionError("catalog diverges from the production DDL: " + check);
            }
            perPathIndexes = check.perPathIndexNames();
            sb.append("## Corpus\n\n");
            try (ResultSet rs = statement.executeQuery("SELECT (SELECT count(*) FROM search_things), "
                    + "(SELECT count(*) FROM search_flat), pg_size_pretty(pg_database_size(current_database())), "
                    + "(SELECT count(*) FROM search_things WHERE features_auth IS NOT NULL), "
                    + "(SELECT count(*) FROM search_things WHERE global_read && '{user:sub-000,user:sub-041}'), "
                    + "(SELECT count(*) FROM search_things WHERE global_read && '{user:sub-000,user:sub-041}' "
                    + "AND COALESCE((policy_auth #> '{·g}') ?| '{user:sub-000,user:sub-041}', false))")) {
                rs.next();
                sb.append("- search_things ").append(rs.getLong(1)).append(" rows, search_flat ").append(rs.getLong(2))
                        .append(" rows, database ").append(rs.getString(3)).append('\n');
                sb.append("- auth realism (corpus property, identical to the original bench): `global_read` and ")
                        .append("`policy_auth` are drawn independently, so of the ").append(rs.getLong(5))
                        .append(" things whose `global_read` overlaps the fixed pair {user:sub-000,user:sub-041}, only ")
                        .append(rs.getLong(6)).append(" also carry one of them in the root `·g` grant; ")
                        .append(rs.getLong(4)).append(" things have any `features_auth` entry (the generator gives ")
                        .append("~20% of features an auth tree, production writes one per feature).\n\n");
            }
        }
        sb.append("## Service cursor (keyset) vs OFFSET pages\n\n").append(keysetCheck).append('\n');
        return sb.toString();
    }

    /**
     * The summary's statement on per-path indexes ({@code ditto.postgresql.search.path-indexes}): they change the
     * plans of {@code like}/{@code ilike} on their paths, so a run with one must not read as a plain production-DDL run.
     */
    static String perPathIndexLine(final JsonObject warm) {
        final Optional<JsonArray> names = warm.getValue("perPathIndexes").filter(JsonValue::isArray)
                .map(JsonValue::asArray);
        if (names.isEmpty()) {
            return "Per-path indexes during the warm run: not recorded.";
        }
        if (names.get().isEmpty()) {
            return "Per-path indexes during the warm run: none (production DDL only).";
        }
        return "**Per-path indexes during the warm run** (`ditto.postgresql.search.path-indexes`, outside the "
                + "production DDL): " + names.get().stream().map(JsonValue::asString).map(name -> "`" + name + "`")
                .collect(Collectors.joining(", ")) + ".";
    }

    private void writeSummary() throws IOException {
        final Path warmFile = resultsDir.resolve("warm.json");
        final Path coldFile = resultsDir.resolve("cold.json");
        final JsonObject warm = Files.exists(warmFile)
                ? JsonFactory.newObject(Files.readString(warmFile, StandardCharsets.UTF_8)) : JsonObject.empty();
        final JsonObject cold = Files.exists(coldFile)
                ? JsonFactory.newObject(Files.readString(coldFile, StandardCharsets.UTF_8)) : JsonObject.empty();
        final Map<String, JsonObject> coldById = new LinkedHashMap<>();
        cold.getValue("variants").filter(JsonValue::isArray).map(JsonValue::asArray).orElse(JsonArray.empty())
                .forEach(v -> coldById.put(v.asObject().getValue("id").map(JsonValue::asString).orElse("?"),
                        v.asObject()));

        final StringBuilder sb = new StringBuilder();
        sb.append("# Production-config search benchmark — summary\n\n");
        sb.append("Shipped schema (`PostgresSearchSchema.ddlStatements()` + the system index `sf_multi`), shipped ")
                .append("translator (service ")
                .append("`QueryParser` + `ThingsSearchCursor` → `SearchQueryAssembler`/`CreateSqlVisitor`/")
                .append("`AuthFilterSqlBuilder`), shipped read persistence and pool config (`plan_cache_mode = ")
                .append("force_custom_plan`), ")
                .append(warm.getValue("things").map(JsonValue::formatAsString).orElse("?"))
                .append(" things (corpus and seed: `load.md`). Warm: ")
                .append(warm.getValue("generatedAt").map(JsonValue::formatAsString).orElse("not run"))
                .append(", ").append(warm.getValue("rounds").map(JsonValue::formatAsString).orElse("?"))
                .append(" contiguous rounds per shape × (warmup over the whole filter rotation + 20 timed); a shape is ")
                .append("\"settled\" when its per-round p95s lie within 10% (or 10 ms) of each other. Cold: ")
                .append(cold.getValue("generatedAt").map(JsonValue::formatAsString).orElse("not run"))
                .append(", ").append(cold.getValue("samplesPerShape").map(JsonValue::formatAsString).orElse("?"))
                .append(" samples per shape (PostgreSQL- and Docker-VM-cold, host-cache state unknown: the macOS host ")
                .append("file cache is not dropped, see `cold.md`). Plans: finds explained leader-only, as the shipped fetch-size ")
                .append("path executes them. Server settings: `environment.md`.\n\n");
        sb.append(perPathIndexLine(warm)).append("\n\n");
        sb.append("| shape | warm p50 ms | warm p95 ms (all rounds) | p50 per round | p95 per round | per-round p95 ")
                .append("spread | cold 1st exec ms (median; host-cache state unknown) | cold samples | ")
                .append("warm p95 (all rounds) vs 200 ms gate | evidence |\n");
        sb.append("|---|---:|---:|---|---|---|---:|---|:---:|---|\n");
        warm.getValue("variants").filter(JsonValue::isArray).map(JsonValue::asArray).orElse(JsonArray.empty())
                .forEach(value -> {
                    final JsonObject v = value.asObject();
                    final String id = v.getValue("id").map(JsonValue::asString).orElse("?");
                    final double p95 = v.getValue("p95").map(JsonValue::asDouble).orElse(Double.NaN);
                    final JsonObject c = coldById.get(id);
                    sb.append("| ").append(id).append(" ").append(v.getValue("title").map(JsonValue::asString)
                                    .orElse("")).append(" | ")
                            .append(v.getValue("p50").map(JsonValue::formatAsString).orElse("?")).append(" | ")
                            .append(fmt(p95)).append(" | ")
                            .append(v.getValue("roundP50").map(JsonValue::formatAsString).orElse("?")).append(" | ")
                            .append(v.getValue("roundP95").map(JsonValue::formatAsString).orElse("?")).append(" | ")
                            .append(v.getValue("roundP95Spread").map(JsonValue::formatAsString).orElse("?"))
                            .append("% / ").append(v.getValue("roundP95SpreadMs").map(JsonValue::formatAsString)
                                    .orElse("?"))
                            .append(" ms (").append(v.getValue("settled").map(JsonValue::asBoolean).orElse(false)
                                    ? "settled" : "did not settle").append(") | ")
                            .append(c == null ? "–" : c.getValue("median").map(x -> fmt(x.asDouble())).orElse("?"))
                            .append(" | ")
                            .append(c == null ? "–" : c.getValue("first").map(JsonValue::formatAsString).orElse("?"))
                            .append(" | ").append(p95 <= GATE_P95_MS ? "PASS" : "**MISS**")
                            .append(v.getValue("settled").map(JsonValue::asBoolean).orElse(false)
                                    ? " (settled)" : " (did not settle)").append(" | `")
                            .append(v.getValue("file").map(JsonValue::asString).orElse("?")).append("` |\n");
                });
        write("summary.md", sb.toString());
    }

    // =================================================================================================================
    // helpers
    // =================================================================================================================

    private ProductionSearchDb newDb() {
        return new ProductionSearchDb(system, BenchConfig.r2dbcUrl(), BenchConfig.user(), BenchConfig.password(),
                legs());
    }

    /** @return the selective legs of this run, resolved on first use and then kept for warm, cold and bias alike. */
    private SelectiveLegs legs() {
        if (legs == null) {
            legs = resolveLegs();
        }
        return legs;
    }

    /**
     * {@code bench.pc.rangeLegs=off}: point legs only. {@code auto}: what the service's probe of the system index
     * {@code sf_multi} sees on this database.
     */
    private SelectiveLegs resolveLegs() {
        if ("off".equals(BenchConfig.productionConfigRangeLegs())) {
            legsLine = "off (forced: bench.pc.rangeLegs=off)";
            return SelectiveLegs.POINT;
        }
        try (ProductionSearchDb probeDb = new ProductionSearchDb(system, BenchConfig.r2dbcUrl(), BenchConfig.user(),
                BenchConfig.password())) {
            final boolean valid = Boolean.TRUE.equals(SystemIndexProbe.of(SystemSearchIndex.MULTI_VALUED)
                    .refresh(probeDb.pool())
                    .block(Duration.ofSeconds(60L)));
            legsLine = valid ? "on (sf_multi valid)" : "off (sf_multi missing or invalid)";
            return SelectiveLegs.of(valid);
        }
    }

    private static Variant find(final List<Variant> variants, final String id) {
        return variants.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> ids(final ResultList<TimestampedThingId> page) {
        return page.stream().map(t -> t.thingId().toString()).toList();
    }

    private void write(final String file, final String content) throws IOException {
        BenchQuerySupport.writeResultFile(resultsDir.resolve(file), content);
    }

    private JsonArray toJson(final List<Double> values) {
        final var builder = JsonFactory.newArrayBuilder();
        values.forEach(v -> builder.add(round1(v)));
        return builder.build();
    }

    private static JsonArray jsonArray(final List<? extends JsonValue> values) {
        final var builder = JsonFactory.newArrayBuilder();
        values.forEach(builder::add);
        return builder.build();
    }

    private static double round1(final double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double median(final List<Double> values) {
        return Stats.nearestRank(values.stream().sorted().toList(), 50);
    }

    private static String fmt(final double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String fmtList(final List<Double> values) {
        return String.join(", ", values.stream().map(ProductionConfigReadBench::fmt).toList());
    }

}
