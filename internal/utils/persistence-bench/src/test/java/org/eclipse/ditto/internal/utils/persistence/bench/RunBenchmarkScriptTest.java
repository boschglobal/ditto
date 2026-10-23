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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * What run-benchmark.sh resolves its container settings to, through its side-effect-free {@code config} stage: the same
 * CPU and memory limits for both backends, and PostgreSQL's effective_cache_size derived from the memory limit.
 */
public final class RunBenchmarkScriptTest {

    private static final Path SCRIPT = Paths.get("benchmark", "run-benchmark.sh");

    @BeforeClass
    public static void requireBashAndScript() {
        assumeTrue("bash is not available - skipping", Files.isExecutable(Paths.get("/bin/bash")));
        assumeTrue("run from the module directory", Files.isRegularFile(SCRIPT));
    }

    @Test
    public void defaultsGiveBothContainersTheSameLimitsAndDeriveEffectiveCacheSize() throws Exception {
        final Result r = run("config");
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out())
                .contains("--name ditto-persistence-bench-pg --cpus 4 --memory 8g --memory-swap 8g "
                        + "-e POSTGRES_USER=bench")
                .contains("postgres:16 postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB")
                .contains("--name ditto-persistence-bench-mongo --cpus 4 --memory 8g --memory-swap 8g -p 57017:27017 "
                        + "mongo:7.0 mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4");
    }

    @Test
    public void customLimitsApplyToBothBackendsAndMoveEffectiveCacheSize() throws Exception {
        final Result r = run("config", "--cpus", "2", "--memory", "12");
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out().split("--cpus 2 --memory 12g --memory-swap 12g", -1)).hasSize(3);   // pg and mongo
        assertThat(r.out()).contains("effective_cache_size=9216MB");
    }

    @Test
    public void zeroMeansUnlimitedAndKeepsTheCacheSizeOfTheRunsBeforeTheLimits() throws Exception {
        final Result r = run("config", "--cpus", "0", "--memory", "0");
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).doesNotContain("--cpus").doesNotContain("--memory")
                .contains("effective_cache_size=12GB");
    }

    @Test
    public void memoryBelowTheCachePinPlusHeadroomIsRejected() throws Exception {
        final Result r = run("config", "--memory", "4");
        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("--memory 4: must be 0 or >= 6 GiB");
    }

    @Test
    public void nonNumericLimitsAreRejected() throws Exception {
        final Result cpus = run("config", "--cpus", "four");
        assertThat(cpus.exit()).isNotZero();
        assertThat(cpus.out()).contains("--cpus must be a number");
        final Result memory = run("config", "--memory", "8g");
        assertThat(memory.exit()).isNotZero();
        assertThat(memory.out()).contains("--memory must be whole GiB");
    }

    @Test
    public void helpDocumentsTheNewStagesAndOptions() throws Exception {
        final Result r = run("--help");
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("relimit").contains("recreate").contains("mixed-sweep").contains("settle")
                .contains("--cpus N").contains("--memory G").contains("BENCH_RESULTS_DIR")
                .contains("BENCH_PROBE_SUFFIX").contains("ping-recovery").contains("ping-actor")
                .contains("BENCH_PING_TAG_MIX").contains("BENCH_C1_CACHE_STATE")
                .contains("payload-pair").doesNotContain("payload-whatif").doesNotContain("NOT PRODUCTION");
    }

    @Test
    public void thePayloadPairRefusesAnotherBackendOrAnUnknownCacheStateBeforeTouchingDocker() throws Exception {
        final Result mongo = run("payload-pair", "--backend", "mongo");
        assertThat(mongo.exit()).isNotZero();
        assertThat(mongo.out()).contains("PostgreSQL only")
                .doesNotContain("creating").doesNotContain("already running").doesNotContain("starting");
        final Result hot = run(Map.of("BENCH_C1_CACHE_STATE", "hot"), "payload-pair", "--backend", "pg");
        assertThat(hot.exit()).isNotZero();
        assertThat(hot.out()).contains("BENCH_C1_CACHE_STATE must be asis, cold or warm")
                .doesNotContain("creating").doesNotContain("already running").doesNotContain("starting");
    }

    @Test
    public void thePingStagesRefuseAnotherBackendOrAnUntaggedCorpusBeforeTouchingDocker() throws Exception {
        final Result mongo = run(Map.of("BENCH_PING_TAG_MIX", "true"), "ping", "--backend", "mongo");
        assertThat(mongo.exit()).isNotZero();
        assertThat(mongo.out()).contains("PostgreSQL-only").doesNotContain("creating", "already running", "starting");
        final Result untagged = run(Map.of(), "ping-actor", "--backend", "pg");
        assertThat(untagged.exit()).isNotZero();
        assertThat(untagged.out()).contains("BENCH_PING_TAG_MIX=true");
    }

    @Test
    public void aTaggedLoadIsPostgresqlOnly() throws Exception {
        final Result r = run(Map.of("BENCH_PING_TAG_MIX", "true"), "load", "--backend", "both");
        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("BENCH_PING_TAG_MIX is PostgreSQL-only");
    }

    private record Result(int exit, String out) {}

    private static Result run(final String... args) throws Exception {
        return run(Map.of(), args);
    }

    private static Result run(final Map<String, String> env, final String... args) throws Exception {
        final List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("BENCH_"));   // the script's own defaults
        builder.environment().putAll(env);
        final Process process = builder.start();
        final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("script finished").isTrue();
        return new Result(process.exitValue(), out);
    }
}
