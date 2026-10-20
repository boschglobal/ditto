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
    private static final Pattern RELOPTIONS =
            Pattern.compile("autovacuum_[a-z_]+ = [0-9.]+(, autovacuum_[a-z_]+ = [0-9.]+)*");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9-]*");

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

    /**
     * W3 churn pid prefix. A distinct prefix per run lets W3 be repeated on one corpus without a
     * reload (the default prefix collides with the previous run's rows).
     */
    public static String w3PidPrefix() {
        return property("bench.w3.pidPrefix", "BENCH_W3_PID_PREFIX", "thing:bench.churn");
    }

    /** W3 PostgreSQL append shape (see {@link PgAppendShape}); default the plugin's transaction shape. */
    public static PgAppendShape w3PgShape() {
        return PgAppendShape.parse(property("bench.w3.pgShape", "BENCH_W3_PG_SHAPE", PgAppendShape.TXN.label()));
    }

    /** W3 Mongo journal write concern: journaled (the plugin default) | w1 (no journal wait, what-if). */
    public static String w3MongoWriteConcern() {
        final String value = property("bench.w3.mongoWriteConcern", "BENCH_W3_MONGO_WRITE_CONCERN", "journaled")
                .toLowerCase(Locale.ROOT);
        if (!"journaled".equals(value) && !"w1".equals(value)) {
            throw new IllegalArgumentException("bench.w3.mongoWriteConcern must be journaled or w1: '" + value + "'");
        }
        return value;
    }

    /**
     * Interval of the W3 PostgreSQL wait-event sampler (pg_stat_activity of the churn backends);
     * 0 (default) disables it so the canonical W3 run carries no extra server load.
     */
    public static int w3WaitSampleMillis() {
        return Integer.parseInt(property("bench.w3.waitSampleMillis", "BENCH_W3_WAIT_SAMPLE_MILLIS", "0"));
    }

    /**
     * W3 staggered start: spread the workers' op schedules evenly over one pacing period instead of letting
     * each worker start its schedule when its connection is ready (which can align them into bursts).
     */
    public static boolean w3Stagger() {
        return Boolean.parseBoolean(property("bench.w3.stagger", "BENCH_W3_STAGGER", "false"));
    }

    /** W3 evidence file, relative to the results folder (default the canonical w3-sustained-churn.md). */
    public static String w3EvidenceFile() {
        return property("bench.w3.evidenceFile", "BENCH_W3_EVIDENCE_FILE", "w3-sustained-churn.md");
    }

    /** unthrottled | credits — see design deviation 3. */
    public static String sweepPace() {
        return property("bench.sweep.pace", "BENCH_SWEEP_PACE", "unthrottled").toLowerCase(Locale.ROOT);
    }

    public static int settleTimeoutSeconds() {
        return Integer.parseInt(property("bench.c4.settleTimeoutSeconds", "BENCH_C4_SETTLE_TIMEOUT", "900"));
    }

    /**
     * Suffix for the evidence files of the load, W1/W2 and C3/C4/C5 scenarios, e.g. "cl2000-pg" writes
     * c4-before-after-cl2000-pg.md; "" (default) keeps the committed names.
     */
    public static String evidenceLabel() {
        final String label = property("bench.evidenceLabel", "BENCH_EVIDENCE_LABEL", "");
        if (!LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException(
                    "evidence label: lower-case letters, digits and '-' only: '" + label + "'");
        }
        return label;
    }

    /**
     * Replaces the persistence schema's autovacuum storage parameters of things_journal and things_snaps at load
     * time, written as in the schema DDL, e.g. "autovacuum_vacuum_scale_factor = 0.01"; "" (default) loads the
     * production values. things_journal_seq always keeps the production value.
     */
    public static String pgReloptionsOverride() {
        return checkReloptions(property("bench.pg.reloptions", "BENCH_PG_RELOPTIONS", ""));
    }

    static String checkReloptions(final String reloptions) {
        if (!reloptions.isEmpty() && !RELOPTIONS.matcher(reloptions).matches()) {
            throw new IllegalArgumentException(
                    "reloptions must look like 'autovacuum_a = 1, autovacuum_b = 0.5': '" + reloptions + "'");
        }
        return reloptions;
    }

    /** true: VACUUM (ANALYZE) the three corpus tables before C3 / C5, so every run sweeps a settled corpus. */
    public static boolean c3VacuumFirst() {
        return Boolean.parseBoolean(property("bench.c3.vacuumFirst", "BENCH_C3_VACUUM_FIRST", "false"));
    }

    /** false: C4 skips its OFFLINE VACUUM FULL / compact step. */
    public static boolean c4OfflineReclaim() {
        return Boolean.parseBoolean(property("bench.c4.offlineReclaim", "BENCH_C4_OFFLINE_RECLAIM", "true"));
    }

    /** Wall-clock bound of the C5 window sweep; 0 s (default) = unbounded. */
    public static Duration sweepMaxDuration() {
        return Duration.ofSeconds(Long.parseLong(property("bench.sweep.maxSeconds", "BENCH_SWEEP_MAX_SECONDS", "0")));
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
        return openPg(null);
    }

    /** Like {@link #openPg()}, tagged with an application_name (null: the driver default). */
    public static Connection openPg(final String applicationName) throws SQLException {
        final Properties props = new Properties();
        props.setProperty("user", pgUser());
        props.setProperty("password", pgPassword());
        props.setProperty("prepareThreshold", "0");
        if (applicationName != null) {
            props.setProperty("ApplicationName", applicationName);
        }
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
