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

import java.time.Duration;

import org.junit.Test;

public final class SweepDeadlineTest {

    @Test
    public void aZeroBoundNeverExpires() throws Exception {
        final SweepEngine.Deadline deadline = new SweepEngine.Deadline(Duration.ZERO);
        Thread.sleep(5L);
        assertThat(deadline.reached()).isFalse();
        assertThat(deadline.wasReached()).isFalse();
    }

    @Test
    public void aBoundExpiresOnceItsTimeHasPassedAndStaysExpired() throws Exception {
        final SweepEngine.Deadline deadline = new SweepEngine.Deadline(Duration.ofMillis(50));
        assertThat(deadline.reached()).isFalse();
        Thread.sleep(80L);
        assertThat(deadline.wasReached()).as("no clock read: not yet observed").isFalse();
        assertThat(deadline.reached()).isTrue();
        assertThat(deadline.wasReached()).isTrue();
        assertThat(deadline.reached()).isTrue();
    }
}
