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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The docker CLI steps of the production-config cold runs (inherits {@code DOCKER_HOST} from the environment).
 * <p>
 * <b>What "cold" means here:</b> {@link #coldRestart} stops the bench PostgreSQL container (empties its shared
 * buffers; the shutdown checkpoint flushes dirty pages), then — from a throw-away {@code --privileged} container on the
 * same Docker VM — runs {@code sync; echo 3 > /proc/sys/vm/drop_caches}, which drops the Linux page cache of the
 * Docker VM that holds the data volume, then starts the container again and waits for {@code pg_isready}. It does NOT
 * drop the macOS host's own file cache of the Docker VM disk image (that needs root: {@code sudo purge}).
 * </p>
 */
final class BenchDocker {

    private static final String MEMINFO =
            "grep -E '^(MemTotal|MemFree|MemAvailable|Buffers|Cached):' /proc/meminfo | tr -s ' ' | tr '\\n' ';'";

    private BenchDocker() {
        throw new AssertionError("no instances");
    }

    /**
     * @param before the Docker VM's {@code /proc/meminfo} page-cache lines just before the drop.
     * @param after the same lines just after it.
     * @param millis wall time of the whole stop/drop/start/ready cycle.
     */
    record ColdRestart(String before, String after, long millis) {}

    static ColdRestart coldRestart(final String container, final String image) {
        final long t0 = System.nanoTime();
        run("docker", "stop", container);
        final String out = run("docker", "run", "--rm", "--privileged", "--entrypoint", "sh", image, "-c",
                MEMINFO + "; echo '|'; sync; echo 3 > /proc/sys/vm/drop_caches; " + MEMINFO);
        run("docker", "start", container);
        waitReady(container);
        final String[] parts = out.split("\\|", 2);
        return new ColdRestart(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "?",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
    }

    /** @return {@code docker inspect} facts about the bench container, for the evidence files. */
    static String describe(final String container) {
        return run("docker", "inspect", container, "--format",
                "container={{.Name}} image={{.Config.Image}} imageId={{.Image}} cmd={{json .Config.Cmd}} "
                        + "mounts={{range .Mounts}}{{.Type}}:{{.Name}}->{{.Destination}} {{end}}"
                        + "shmSize={{.HostConfig.ShmSize}} memoryLimit={{.HostConfig.Memory}} "
                        + "ports={{json .HostConfig.PortBindings}}").trim();
    }

    /** @return the Docker VM's CPU count and total memory as {@code docker info} reports them. */
    static String dockerVm() {
        return run("docker", "info", "--format",
                "os={{.OperatingSystem}} kernel={{.KernelVersion}} cpus={{.NCPU}} memTotalBytes={{.MemTotal}} "
                        + "server={{.ServerVersion}}").trim();
    }

    private static void waitReady(final String container) {
        for (int i = 0; i < 120; i++) {
            if (exitCode("docker", "exec", container, "pg_isready", "-U", "bench", "-d", "bench") == 0) {
                sleep(1000); // pg_isready can answer during a crash-recovery restart window: confirm once more
                if (exitCode("docker", "exec", container, "pg_isready", "-U", "bench", "-d", "bench") == 0) {
                    return;
                }
            }
            sleep(500);
        }
        throw new IllegalStateException("bench database " + container + " not ready after restart");
    }

    static String run(final String... command) {
        final Result result = exec(command);
        if (result.exitCode != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed (" + result.exitCode + "): "
                    + result.output);
        }
        return result.output;
    }

    private static int exitCode(final String... command) {
        return exec(command).exitCode;
    }

    private record Result(int exitCode, String output) {}

    private static Result exec(final String... command) {
        final List<String> cmd = new ArrayList<>(List.of(command));
        java.nio.file.Path output = null;
        try {
            // Output goes to a file, not a pipe: reading a pipe to EOF would block before waitFor(...) could time out
            // for as long as the child keeps its stdout open (review round 1, M10).
            output = java.nio.file.Files.createTempFile("bench-docker-", ".out");
            final Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out after 5 minutes: " + cmd);
            }
            return new Result(process.exitValue(), java.nio.file.Files.readString(output, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("could not run " + cmd, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + cmd, e);
        } finally {
            if (output != null) {
                try {
                    java.nio.file.Files.deleteIfExists(output);
                } catch (final IOException e) {
                    // best effort: a leftover temp file is harmless
                }
            }
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
