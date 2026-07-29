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
import java.util.Locale;
import java.util.Properties;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * Connection coordinates and knobs for the bench, resolved system-property-first, then
 * environment variable, then default. The run-benchmark.sh script exports the BENCH_* variables.
 */
public final class BenchConfig {

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
        return new CorpusGenerator(seed(), count());
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
