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
package org.eclipse.ditto.internal.utils.persistence.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.pattern.AskTimeoutException;
import org.apache.pekko.pattern.CircuitBreakerOpenException;
import org.junit.Test;

import scala.concurrent.duration.FiniteDuration;

/**
 * Tests {@link PersistenceOverload#isOverload(Throwable)}.
 */
public final class PersistenceOverloadTest {

    @Test
    public void openCircuitBreakerIsOverload() {
        assertThat(PersistenceOverload.isOverload(openCircuitBreaker())).isTrue();
    }

    @Test
    public void backendExceptionMarkedAsOverloadIsOverload() {
        assertThat(PersistenceOverload.isOverload(new BackendOverload())).isTrue();
    }

    @Test
    public void overloadWrappedInACompletionExceptionIsOverload() {
        assertThat(PersistenceOverload.isOverload(new CompletionException(openCircuitBreaker()))).isTrue();
        assertThat(PersistenceOverload.isOverload(
                new IllegalStateException("outer", new CompletionException(new BackendOverload())))).isTrue();
    }

    @Test
    public void otherFailuresAreNotOverload() {
        assertThat(PersistenceOverload.isOverload(new IllegalStateException("boom"))).isFalse();
        assertThat(PersistenceOverload.isOverload(new AskTimeoutException("timed out"))).isFalse();
        assertThat(PersistenceOverload.isOverload(null)).isFalse();
    }

    @Test
    public void cyclicCauseChainEnds() {
        final RuntimeException first = new RuntimeException("first");
        final RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(PersistenceOverload.isOverload(first)).isFalse();
    }

    private static CircuitBreakerOpenException openCircuitBreaker() {
        return new CircuitBreakerOpenException(new FiniteDuration(3L, TimeUnit.SECONDS),
                "Circuit Breaker is open; calls are failing fast");
    }

    private static final class BackendOverload extends RuntimeException implements PersistenceOverload {

        private static final long serialVersionUID = 1L;

        private BackendOverload() {
            super("store out of capacity");
        }
    }

}
