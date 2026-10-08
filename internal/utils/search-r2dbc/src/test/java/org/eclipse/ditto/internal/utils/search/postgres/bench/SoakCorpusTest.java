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

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.Test;

/** Unit test of the corpus load's argument checks, which run before the database is touched. */
public final class SoakCorpusTest {

    private static final SoakTwin TWIN = new SoakTwin(42);

    @Test
    public void aChunkBelowOneIsRejectedBeforeAnyTableIsDropped() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SoakCorpus.load(null, TWIN, 100, 0, 0, null, line -> { }))
                .withMessageContaining("chunk");
    }

    @Test
    public void aLoadCanResumeAtTheCountButNotBeyondIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SoakCorpus.load(null, TWIN, 100, 101, 10, null, line -> { }))
                .withMessageContaining("[0, 100]");
    }

}
