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

import java.util.concurrent.TimeUnit;

/**
 * A minimal shared, thread-safe fixed-rate limiter (no third-party dependency): {@link #acquire()} reserves the
 * caller's own scheduled time slot under a short synchronized section (so concurrent workers never contend on the
 * sleep itself), then parks the calling thread until that slot arrives. Used by {@link WriteFanoutBench} to pace
 * an aggregate target rate (e.g. 200 updates/s) across an arbitrary number of worker threads — each worker calls
 * {@link #acquire()} once per transaction, and the aggregate throughput converges on the configured rate
 * regardless of how many workers are sharing this instance.
 */
final class RateLimiter {

    private final long intervalNanos;
    private long nextSlotNanos;

    RateLimiter(final double permitsPerSecond) {
        this.intervalNanos = Math.round(1_000_000_000.0 / permitsPerSecond);
        this.nextSlotNanos = System.nanoTime();
    }

    /**
     * Blocks the calling thread until its reserved slot arrives. If the limiter is already behind schedule (e.g.
     * because transactions are taking longer than the target interval — a saturation signal worth reporting
     * honestly rather than hiding), this returns immediately instead of trying to "catch up" with a burst.
     */
    void acquire() throws InterruptedException {
        final long mySlot = reserveSlot();
        final long waitNanos = mySlot - System.nanoTime();
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    private synchronized long reserveSlot() {
        final long now = System.nanoTime();
        nextSlotNanos = Math.max(nextSlotNanos + intervalNanos, now);
        return nextSlotNanos;
    }

}
