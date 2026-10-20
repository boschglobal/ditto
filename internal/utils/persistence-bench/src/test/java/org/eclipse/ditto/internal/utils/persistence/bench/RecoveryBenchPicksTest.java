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

import java.util.stream.LongStream;

import org.junit.Test;

public final class RecoveryBenchPicksTest {

    @Test
    public void distinctPicksNeverRepeatAPid() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 5_000L);
        assertThat(RecoveryBench.pickDistinctPids(gen, CorpusGenerator.PidClass.MEDIAN, 23, 0))
                .hasSize(23)
                .doesNotHaveDuplicates();
    }

    @Test
    public void timedIterationsAreCappedByTheDistinctPidsLeftAfterTheWarmups() {
        // bench defaults: 3 warm-ups, 20 timed iterations
        assertThat(RecoveryBench.timedIterations(LongStream.rangeClosed(1, 10).boxed().toList())).isEqualTo(7);
        assertThat(RecoveryBench.timedIterations(LongStream.rangeClosed(1, 26).boxed().toList())).isEqualTo(20);
    }
}
