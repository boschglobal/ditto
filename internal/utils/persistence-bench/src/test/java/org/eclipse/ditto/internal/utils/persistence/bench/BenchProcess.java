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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short external command (vm_stat, docker restart, …) with a timeout that applies: the output goes to a
 * temporary file, so the wait does not block on an unread pipe, and a command that overruns is killed.
 */
final class BenchProcess {

    private BenchProcess() {}

    static String run(final Duration timeout, final String... command) throws IOException, InterruptedException {
        final Path out = Files.createTempFile("bench-process", ".out");
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(out.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(String.join(" ", command) + " did not end within " + timeout + ": "
                        + Files.readString(out, StandardCharsets.UTF_8));
            }
            final String output = Files.readString(out, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException(String.join(" ", command) + " failed (exit " + process.exitValue() + "): "
                        + output);
            }
            return output;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
