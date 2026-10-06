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
package org.eclipse.ditto.internal.utils.persistentactors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.pattern.AskTimeoutException;
import org.apache.pekko.pattern.CircuitBreakerOpenException;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.exceptions.DittoInternalErrorException;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.api.PersistenceOverload;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.exceptions.ThingNotAccessibleException;
import org.eclipse.ditto.things.model.signals.commands.exceptions.ThingUnavailableException;
import org.junit.Test;

import scala.concurrent.duration.FiniteDuration;

/**
 * Tests {@link AbstractPersistenceSupervisor#getTransformationExceptionAsRuntimeException}: a failed signal
 * transformation caused by persistence overload (its existence check timed out, found no persistence actor, or failed
 * with an overload exception) is answered with the entity's "unavailable" exception (503); a DittoRuntimeException is
 * passed on; any other failure stays an internal error (500). Backend-neutral: the open circuit breaker is Pekko's, for
 * MongoDB and PostgreSQL alike.
 */
public final class TransformationExceptionMappingTest {

    private static final ThingId THING_ID = ThingId.of("org.eclipse.ditto", "overloaded");
    private static final DittoHeaders HEADERS = DittoHeaders.newBuilder().correlationId("transformation").build();

    @Test
    public void existenceCheckTimeoutIsAnsweredUnavailable() {
        final AskTimeoutException timeout = new AskTimeoutException("Ask timed out on [pa] after [5000 ms]");

        assertUnavailable(map(new CompletionException(timeout), false), timeout);
    }

    @Test
    public void absentPersistenceActorIsAnsweredUnavailable() {
        final IllegalArgumentException noRecipient =
                new IllegalArgumentException("Unsupported recipient type, question not sent to [null]");

        assertUnavailable(map(new CompletionException(noRecipient), true), noRecipient);
    }

    @Test
    public void openCircuitBreakerIsAnsweredUnavailable() {
        final CircuitBreakerOpenException open = new CircuitBreakerOpenException(
                new FiniteDuration(3L, TimeUnit.SECONDS), "Circuit Breaker is open; calls are failing fast");

        assertUnavailable(map(new CompletionException(open), false), open);
    }

    @Test
    public void backendOverloadIsAnsweredUnavailable() {
        final BackendOverload overload = new BackendOverload();

        assertUnavailable(map(new CompletionException(overload), false), overload);
    }

    @Test
    public void dittoRuntimeExceptionIsPassedOn() {
        final ThingNotAccessibleException notAccessible =
                ThingNotAccessibleException.newBuilder(THING_ID).dittoHeaders(HEADERS).build();

        assertThat(map(new CompletionException(notAccessible), false)).isSameAs(notAccessible);
        assertThat(map(new CompletionException(notAccessible), true)).isSameAs(notAccessible);
    }

    @Test
    public void otherFailureStaysAnInternalError() {
        final IllegalStateException unexpected = new IllegalStateException("expect SudoRetrieveThingResponse, got: 42");

        final DittoRuntimeException dre = map(new CompletionException(unexpected), false);

        assertThat(dre).isInstanceOf(DittoInternalErrorException.class);
        assertThat(dre.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(dre.getDittoHeaders().getCorrelationId()).contains("transformation");
    }

    private static DittoRuntimeException map(final Throwable error, final boolean persistenceActorAbsent) {
        return AbstractPersistenceSupervisor.getTransformationExceptionAsRuntimeException(error, HEADERS,
                persistenceActorAbsent, () -> ThingUnavailableException.newBuilder(THING_ID));
    }

    private static void assertUnavailable(final DittoRuntimeException dre, final Throwable cause) {
        assertThat(dre).isInstanceOf(ThingUnavailableException.class);
        assertThat(dre.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(dre.getDittoHeaders().getCorrelationId()).contains("transformation");
        assertThat(dre.getCause()).isSameAs(cause);
    }

    private static final class BackendOverload extends RuntimeException implements PersistenceOverload {

        private static final long serialVersionUID = 1L;

        private BackendOverload() {
            super("store out of capacity");
        }
    }

}
