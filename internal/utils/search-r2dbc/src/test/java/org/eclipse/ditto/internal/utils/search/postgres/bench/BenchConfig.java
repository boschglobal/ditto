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

/**
 * Connection coordinates and corpus-size parameters for the Phase-0 bench harness, read from system properties
 * with an environment-variable fallback. Defaults match the {@code docker run} one-liner documented in
 * {@code bench/README.md}.
 */
final class BenchConfig {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:55432/bench";
    private static final String DEFAULT_USER = "bench";
    private static final String DEFAULT_PASSWORD = "bench";
    private static final long DEFAULT_COUNT = 1_000_000L;
    private static final long DEFAULT_SEED = 42L;

    private BenchConfig() {
        throw new AssertionError("no instances");
    }

    /**
     * @return the JDBC URL of the long-lived bench database, from {@code BENCH_PG_URL} (sysprop or env),
     * defaulting to {@value #DEFAULT_URL}.
     */
    static String jdbcUrl() {
        return property("BENCH_PG_URL", DEFAULT_URL);
    }

    /**
     * @return the login role for the bench database, from {@code BENCH_PG_USER} (sysprop or env).
     */
    static String user() {
        return property("BENCH_PG_USER", DEFAULT_USER);
    }

    /**
     * @return the login password for the bench database, from {@code BENCH_PG_PASSWORD} (sysprop or env).
     */
    static String password() {
        return property("BENCH_PG_PASSWORD", DEFAULT_PASSWORD);
    }

    /**
     * @return the number of things to generate, from the {@code bench.count} system property, defaulting to
     * {@value #DEFAULT_COUNT} (parameterizable so a 10M run can happen later).
     */
    static long thingCount() {
        return Long.getLong("bench.count", DEFAULT_COUNT);
    }

    /**
     * @return the deterministic corpus-generation seed, from the {@code bench.seed} system property.
     */
    static long seed() {
        return Long.getLong("bench.seed", DEFAULT_SEED);
    }

    // --- Task 0.3 write-fanout bench knobs (all overridable for a short dry run before committing to the full
    // ~10 min / 2x5 min durations the brief specifies) ------------------------------------------------------------

    /** @return Part 1's sustained-run duration; default 600s (10 min, the brief's reduced-scale figure). */
    static java.time.Duration writePart1Duration() {
        return java.time.Duration.ofSeconds(longProperty("bench.write.part1Seconds", 600L));
    }

    /** @return each of Part 2's two comparison-phase durations; default 300s (5 min, brief's "2x5 min" option). */
    static java.time.Duration writePart2PhaseDuration() {
        return java.time.Duration.ofSeconds(longProperty("bench.write.part2PhaseSeconds", 300L));
    }

    /** @return the sampling cadence during a write-bench run; default 30s per the brief. */
    static java.time.Duration writeSampleInterval() {
        return java.time.Duration.ofSeconds(longProperty("bench.write.sampleIntervalSeconds", 30L));
    }

    /** @return the number of concurrent writer worker threads; default 8 (within the brief's 4-16 range). */
    static int writeWorkers() {
        return (int) longProperty("bench.write.workers", 8L);
    }

    /** @return the target AGGREGATE sustained update rate across all workers, updates/second; default 200. */
    static double writeTargetRate() {
        final String v = property("bench.write.targetRate", null);
        return v != null ? Double.parseDouble(v) : 200.0;
    }

    /** @return the number of distinct existing thing_ids churned per pool (Part 1: one pool; Part 2: two). */
    static int writePoolSizeEach() {
        return (int) longProperty("bench.write.poolSize", 2000L);
    }

    // --- production-config mode knobs (ProductionConfigLoadBench / ProductionConfigReadBench) ---------------------------

    /**
     * @return the r2dbc URL of the bench database for the shipped read stack, from {@code BENCH_PG_R2DBC_URL}
     * (sysprop or env), defaulting to {@link #jdbcUrl()} with the {@code jdbc:} scheme swapped for {@code r2dbc:}.
     */
    static String r2dbcUrl() {
        return property("BENCH_PG_R2DBC_URL", jdbcUrl().replaceFirst("^jdbc:postgresql:", "r2dbc:postgresql:"));
    }

    /** @return the bench container the cold runs restart, from {@code BENCH_PG_CONTAINER} (sysprop or env). */
    static String container() {
        return property("BENCH_PG_CONTAINER", "ditto-search-bench-pc");
    }

    /**
     * @return the image the cold runs use for the privileged page-cache drop, from {@code BENCH_PG_IMAGE} (sysprop or
     * env); defaults to the bench container's own image so no extra image has to be pulled.
     */
    static String image() {
        return property("BENCH_PG_IMAGE", "postgres:16");
    }

    /** @return where the production-config evidence is written, from {@code bench.pc.resultsDir}. */
    static java.nio.file.Path productionConfigResultsDir() {
        return java.nio.file.Path.of(property("bench.pc.resultsDir",
                "src/test/resources/bench/results/production-config"));
    }

    /** @return how many warm rounds (each: every shape, 3 warmup + 20 timed) to run; default 3. */
    static int productionConfigRounds() {
        return (int) longProperty("bench.pc.rounds", 3L);
    }

    /** @return how many cold first-executions to take per shape; default 3. */
    static int productionConfigColdSamples() {
        return (int) longProperty("bench.pc.coldSamples", 3L);
    }

    /**
     * @return whether the read bench may use the selective range legs, from {@code bench.pc.rangeLegs}: {@code auto}
     * (the default: as the service does, i.e. while the system index {@code sf_multi} is valid) or {@code off} (point
     * legs only, the statement as before range legs).
     * @throws IllegalArgumentException for any other value.
     */
    static String productionConfigRangeLegs() {
        final String value = property("bench.pc.rangeLegs", "auto");
        if (!"auto".equals(value) && !"off".equals(value)) {
            throw new IllegalArgumentException("bench.pc.rangeLegs must be 'auto' or 'off', got <" + value + ">");
        }
        return value;
    }

    /**
     * @return the read-bench variant ids to run, from {@code bench.pc.variants} (a comma list, e.g. {@code 1,5,ce-a});
     * empty = all.
     */
    static java.util.Set<String> productionConfigVariants() {
        final java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (final String id : property("bench.pc.variants", "").split(",")) {
            if (!id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return java.util.Collections.unmodifiableSet(ids);
    }

    // --- production write-path soak knobs (WriteSoakBench) ---------------------------------------------------------

    /** @return where the write-soak evidence is written, from {@code bench.soak.resultsDir}. */
    static java.nio.file.Path soakResultsDir() {
        return java.nio.file.Path.of(property("bench.soak.resultsDir",
                "src/test/resources/bench/results/write-bench/10m-soak"));
    }

    /** @return the gated soak period (every window in it counts for the throughput gate); default 3600 s. */
    static java.time.Duration soakGated() {
        return java.time.Duration.ofSeconds(longProperty("bench.soak.seconds", 3600L));
    }

    /** @return the warm-up before the gated period (sampled and reported, not gated); default 300 s. */
    static java.time.Duration soakWarmup() {
        return java.time.Duration.ofSeconds(longProperty("bench.soak.warmupSeconds", 300L));
    }

    /** @return the trailing part of the gated period the bloat-plateau verdict looks at; default 3600 s. */
    static java.time.Duration soakPlateau() {
        return java.time.Duration.ofSeconds(longProperty("bench.soak.plateauSeconds", 3600L));
    }

    /** @return the sample window; default 30 s. */
    static java.time.Duration soakWindow() {
        return java.time.Duration.ofSeconds(longProperty("bench.soak.windowSeconds", 30L));
    }

    /** @return how often running (auto)vacuums are polled for progress and wait events; default 2 s. */
    static java.time.Duration soakVacuumPoll() {
        return java.time.Duration.ofSeconds(longProperty("bench.soak.vacuumPollSeconds", 2L));
    }

    /** @return the concurrent writers, one write in flight each; default 24 (the shipped pool has 25). */
    static int soakWorkers() {
        return (int) longProperty("bench.soak.workers", 24L);
    }

    /** @return the offered aggregate update rate; default 220/s, 10 % above the gate (see the bench README). */
    static double soakTargetRate() {
        final String v = property("bench.soak.targetRate", null);
        return v != null ? Double.parseDouble(v) : 220.0;
    }

    /** @return the rate every gated window must reach; default 200/s. */
    static double soakGateRate() {
        final String v = property("bench.soak.gateRate", null);
        return v != null ? Double.parseDouble(v) : 200.0;
    }

    /** @return things per COPY chunk (each chunk commits on its own, so a load can resume); default 10,000. */
    static int soakLoadChunk() {
        return (int) longProperty("bench.soak.loadChunk", 10_000L);
    }

    /** @return the corpus index a resumed load starts at; default 0 = drop the search tables and load from scratch. */
    static long soakLoadFrom() {
        return longProperty("bench.soak.loadFrom", 0L);
    }

    /**
     * @return a session {@code maintenance_work_mem} for the post-load index builds, or {@code null} = the server's.
     */
    @javax.annotation.Nullable
    static String soakMaintenanceWorkMem() {
        return property("bench.soak.maintenanceWorkMem", null);
    }

    private static long longProperty(final String key, final long defaultValue) {
        final String v = property(key, null);
        return v != null ? Long.parseLong(v) : defaultValue;
    }

    private static String property(final String key, final String defaultValue) {
        final String sysProp = System.getProperty(key);
        if (sysProp != null) {
            return sysProp;
        }
        final String env = System.getenv(key);
        return env != null ? env : defaultValue;
    }

}
