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

import javax.annotation.Nullable;

import org.apache.pekko.pattern.CircuitBreakerOpenException;

/**
 * Marks an exception of a persistence backend which means that the store is reachable but has no capacity for the
 * call right now, e.g. a connection pool that rejects a borrower because its queue of waiting borrowers is full.
 * <p>
 * {@link #isOverload(Throwable)} classifies such an exception, and Pekko's {@link CircuitBreakerOpenException} (raised
 * by the journal and snapshot-store circuit breakers of every backend), as persistence overload. Callers answer
 * persistence overload with the entity's "unavailable" exception (HTTP 503) instead of an internal error (HTTP 500).
 * </p>
 */
public interface PersistenceOverload {

    /**
     * Tells whether the given throwable or one of its causes is persistence overload.
     *
     * @param throwable the failure to classify, may be {@code null}.
     * @return {@code true} if a {@link PersistenceOverload} or a {@link CircuitBreakerOpenException} is in the cause
     * chain.
     */
    static boolean isOverload(@Nullable final Throwable throwable) {
        final int maxCauseDepth = 32; // a cause chain may be cyclic
        Throwable current = throwable;
        for (int depth = 0; null != current && depth < maxCauseDepth; depth++) {
            if (current instanceof PersistenceOverload || current instanceof CircuitBreakerOpenException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
