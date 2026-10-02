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
package org.eclipse.ditto.internal.utils.persistence.bench;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * Connection coordinates and knobs for the bench, resolved system-property-first, then
 * environment variable, then default. The run-benchmark.sh script exports the BENCH_* variables.
 */
public final class BenchConfig {

    private static final Pattern RETENTION = Pattern.compile("(\\d+)([dhms])");

    private BenchConfig() {
    }

    public static String pgUrl() {
        return property("bench.pg.url", "BENCH_PG_URL", "jdbc:postgresql://localhost:55433/bench");
    }

    public static String pgUser() {
        return property("bench.pg.user", "BENCH_PG_USER", "bench");
    }

    public static String pgPassword() {
        return property("bench.pg.password", "BENCH_PG_PASSWORD", "bench");
    }

    public static String mongoUri() {
        return property("bench.mongo.uri", "BENCH_MONGO_URI", "mongodb://localhost:57017");
    }

    public static String mongoDbName() {
        return property("bench.mongo.db", "BENCH_MONGO_DB", "bench");
    }

    public static long count() {
        return Long.parseLong(property("bench.count", "BENCH_COUNT", "1000000"));
    }

    public static long seed() {
        return Long.parseLong(property("bench.seed", "BENCH_SEED", "42"));
    }

    /** pg | mongo | both */
    public static String backend() {
        return property("bench.backend", "BENCH_BACKEND", "both").toLowerCase(Locale.ROOT);
    }

    public static boolean runPg() {
        return !"mongo".equals(backend());
    }

    public static boolean runMongo() {
        return !"pg".equals(backend());
    }

    public static int warmups() {
        return Integer.parseInt(property("bench.warmups", "BENCH_WARMUPS", "3"));
    }

    public static int iterations() {
        return Integer.parseInt(property("bench.iterations", "BENCH_ITERATIONS", "20"));
    }

    public static int sampleIntervalSeconds() {
        return Integer.parseInt(property("bench.sample.intervalSeconds", "BENCH_SAMPLE_INTERVAL", "30"));
    }

    public static int writeDurationSeconds() {
        return Integer.parseInt(property("bench.write.durationSeconds", "BENCH_WRITE_DURATION", "600"));
    }

    public static int writeTargetRate() {
        return Integer.parseInt(property("bench.write.targetRate", "BENCH_WRITE_RATE", "500"));
    }

    public static int writeWorkers() {
        return Integer.parseInt(property("bench.write.workers", "BENCH_WRITE_WORKERS", "8"));
    }

    public static int writePidsPerWorker() {
        return Integer.parseInt(property("bench.write.pidsPerWorker", "BENCH_WRITE_PIDS_PER_WORKER", "100"));
    }

    /** unthrottled | credits — see design deviation 3. */
    public static String sweepPace() {
        return property("bench.sweep.pace", "BENCH_SWEEP_PACE", "unthrottled").toLowerCase(Locale.ROOT);
    }

    public static int settleTimeoutSeconds() {
        return Integer.parseInt(property("bench.c4.settleTimeoutSeconds", "BENCH_C4_SETTLE_TIMEOUT", "900"));
    }

    /**
     * Share of pids whose events and snapshots are all written relative to {@link #freshBaseMillis()}
     * (see {@link CorpusGenerator}); 0 (default) loads the backdated corpus.
     */
    public static double freshShare() {
        return Double.parseDouble(property("bench.freshShare", "BENCH_FRESH_SHARE", "0"));
    }

    /**
     * Timestamp base of the fresh-only pids, an ISO-8601 instant; default: the previous UTC midnight,
     * so loads on the same UTC day generate the same corpus. Pass it explicitly when the backends
     * are loaded on different days.
     */
    public static long freshBaseMillis() {
        final String configured = property("bench.freshBase", "BENCH_FRESH_BASE", "");
        return configured.isEmpty()
                ? defaultFreshBaseMillis(Instant.now())
                : Instant.parse(configured).toEpochMilli();
    }

    static long defaultFreshBaseMillis(final Instant now) {
        return now.truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(1)).toEpochMilli();
    }

    /** The C1 retention variant's history-retention-duration (things.conf default 3d), e.g. "3d", "12h". */
    public static String c1RetentionSpec() {
        return property("bench.c1.retention", "BENCH_C1_RETENTION", "3d");
    }

    public static Duration c1Retention() {
        return parseRetention(c1RetentionSpec());
    }

    /** Parses a non-negative whole number with a d/h/m/s unit, as the service config durations are written. */
    static Duration parseRetention(final String spec) {
        final Matcher m = RETENTION.matcher(spec);
        if (!m.matches()) {
            throw new IllegalArgumentException("retention must look like 3d, 12h, 90m or 45s: '" + spec + "'");
        }
        final long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "d" -> Duration.ofDays(amount);
            case "h" -> Duration.ofHours(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofSeconds(amount);
        };
    }

    /** Rejects a fresh base whose deepest fresh pid would write rows at or after {@code now}. */
    static void checkFreshWindow(final CorpusGenerator gen, final Instant now) {
        if (gen.freshShare() > 0.0 && gen.latestFreshTimestampMillis() >= now.toEpochMilli()) {
            throw new IllegalStateException("fresh base " + Instant.ofEpochMilli(gen.freshBaseMillis())
                    + " puts the deepest fresh-only pid's rows up to "
                    + Instant.ofEpochMilli(gen.latestFreshTimestampMillis())
                    + ", in the future; pick a base at least " + CorpusGenerator.DEPTH_CAP + " s in the past");
        }
    }

    /**
     * pgjdbc connection with prepareThreshold=0: every execution is custom-planned — the search
     * bench's generic-plan-flip finding, adopted as protocol.
     */
    public static Connection openPg() throws SQLException {
        final Properties props = new Properties();
        props.setProperty("user", pgUser());
        props.setProperty("password", pgPassword());
        props.setProperty("prepareThreshold", "0");
        return DriverManager.getConnection(pgUrl(), props);
    }

    public static MongoClient openMongo() {
        return MongoClients.create(mongoUri());
    }

    public static CorpusGenerator corpus() {
        final double share = freshShare();
        final CorpusGenerator gen = share > 0.0
                ? new CorpusGenerator(seed(), count(), share, freshBaseMillis())
                : new CorpusGenerator(seed(), count());
        checkFreshWindow(gen, Instant.now());
        return gen;
    }

    static String property(final String sysProp, final String envVar, final String fallback) {
        final String fromSys = System.getProperty(sysProp);
        if (fromSys != null && !fromSys.isEmpty()) {
            return fromSys;
        }
        final String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        return fallback;
    }
}
