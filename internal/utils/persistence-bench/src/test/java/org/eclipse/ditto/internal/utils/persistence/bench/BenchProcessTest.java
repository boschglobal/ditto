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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;

import org.junit.Test;

public final class BenchProcessTest {

    @Test
    public void returnsTheOutputOfACommandThatEndsInTime() throws Exception {
        assertThat(BenchProcess.run(Duration.ofSeconds(10), "echo", "hello")).isEqualTo("hello\n");
    }

    @Test
    public void aNonZeroExitIsAnIOExceptionWithTheOutput() {
        assertThatThrownBy(() -> BenchProcess.run(Duration.ofSeconds(10), "sh", "-c", "echo oops; exit 3"))
                .isInstanceOf(IOException.class).hasMessageContaining("exit 3").hasMessageContaining("oops");
    }

    @Test
    public void aCommandThatOverrunsItsTimeoutIsKilledAndReported() {
        final long start = System.nanoTime();
        assertThatThrownBy(() -> BenchProcess.run(Duration.ofMillis(300), "sleep", "30"))
                .isInstanceOf(IOException.class).hasMessageContaining("did not end within PT0.3S");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }
}
