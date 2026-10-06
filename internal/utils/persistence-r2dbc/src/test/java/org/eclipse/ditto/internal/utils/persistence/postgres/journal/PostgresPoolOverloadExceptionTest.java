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
package org.eclipse.ditto.internal.utils.persistence.postgres.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;

import org.eclipse.ditto.internal.utils.persistence.api.PersistenceOverload;
import org.junit.Test;

import reactor.pool.PoolAcquirePendingLimitException;

/**
 * Tests that a shed journal write is classified as persistence overload, backend-neutrally.
 */
public final class PostgresPoolOverloadExceptionTest {

    @Test
    public void poolOverloadIsPersistenceOverload() {
        final PostgresPoolOverloadException overload =
                PostgresPoolOverloadException.of(new PoolAcquirePendingLimitException(8));

        assertThat(overload).isInstanceOf(PersistenceOverload.class);
        assertThat(PersistenceOverload.isOverload(new CompletionException(overload))).isTrue();
    }

}
