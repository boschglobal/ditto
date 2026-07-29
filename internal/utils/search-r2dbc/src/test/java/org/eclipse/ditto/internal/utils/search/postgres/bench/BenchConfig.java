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
