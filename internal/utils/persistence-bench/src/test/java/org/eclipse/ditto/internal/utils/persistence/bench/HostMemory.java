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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One host-memory sample (preamble rule): swap in use, Swapins/Swapouts (16 KiB pages), memory free %. macOS only. */
final class HostMemory {

    static final long MAX_SWAPOUTS = 1_000L;
    static final int MIN_FREE_PERCENT = 50;

    record Sample(Instant at, double swapUsedMb, long swapins, long swapouts, int freePercent, boolean available) {

        static Sample unavailable(final Instant at) {
            return new Sample(at, 0.0, 0L, 0L, 0, false);
        }

        Sample withSwapouts(final long newSwapouts, final int newFreePercent) {
            return new Sample(at, swapUsedMb, swapins, newSwapouts, newFreePercent, available);
        }

        String line() {
            return available
                    ? String.format("%s swap_used_MB=%.2f swapins=%d swapouts=%d free=%d", at, swapUsedMb, swapins,
                    swapouts, freePercent)
                    : at + " not sampled (no macOS memory tools)";
        }
    }

    private HostMemory() {}

    static Sample sample() {
        final Instant at = Instant.now();
        try {
            final String vmStat = run("/usr/bin/vm_stat");
            return new Sample(at, parseSwapUsedMb(run("/usr/sbin/sysctl", "-n", "vm.swapusage")),
                    parseCounter(vmStat, "Swapins"), parseCounter(vmStat, "Swapouts"),
                    parseFreePercent(run("/usr/bin/memory_pressure", "-Q")), true);
        } catch (final IOException | RuntimeException e) {
            return Sample.unavailable(at);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sample.unavailable(at);
        }
    }

    static String verdict(final Sample before, final Sample after) {
        if (!before.available() || !after.available()) {
            return "NOT SAMPLED";
        }
        return after.swapouts() - before.swapouts() <= MAX_SWAPOUTS && before.freePercent() >= MIN_FREE_PERCENT
                && after.freePercent() >= MIN_FREE_PERCENT ? "RELIABLE" : "UNRELIABLE";
    }

    static double parseSwapUsedMb(final String sysctl) {
        return Double.parseDouble(group(Pattern.compile("used = ([0-9.]+)M"), sysctl));
    }

    static long parseCounter(final String vmStat, final String key) {
        return Long.parseLong(group(Pattern.compile("(?m)^" + key + ":\\s+(\\d+)\\.?$"), vmStat));
    }

    static int parseFreePercent(final String memoryPressure) {
        return Integer.parseInt(group(Pattern.compile("free percentage: (\\d+)%"), memoryPressure));
    }

    private static String group(final Pattern pattern, final String text) {
        final Matcher m = pattern.matcher(text);
        if (!m.find()) {
            throw new IllegalArgumentException("no " + pattern + " in: " + text);
        }
        return m.group(1);
    }

    private static String run(final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + out);
        }
        return out;
    }
}
