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

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.spi.R2dbcTimeoutException;

public final class StatementTimerTest {

    @Test
    public void anExecutionReportedSeveralTimesIsOneRecordAndFailedIfAnyReportFailed() {
        final StatementTimer timer = new StatementTimer();
        final Map<String, Object> state = new HashMap<>(Map.of("duration", Duration.ofMillis(60_000), "success", false,
                "throwable", new R2dbcTimeoutException("canceling statement due to statement timeout", "57014")));
        final QueryExecutionInfo cancelled = execution(state);
        timer.afterQuery(cancelled);                            // onError of the result
        state.put("success", true);
        state.remove("throwable");
        state.put("duration", Duration.ofMillis(60_002));
        timer.afterQuery(cancelled);                            // cancel of the result subscriber
        timer.afterQuery(cancelled);                            // cancel of the query subscriber
        timer.afterQuery(execution(new HashMap<>(Map.of("duration", Duration.ofMillis(3), "success", true))));

        assertThat(timer.statements()).hasSize(2);
        assertThat(timer.statements().get(0).success()).isFalse();
        assertThat(timer.statements().get(0).failure()).contains("57014");
        assertThat(timer.statements().get(0).nanos()).isEqualTo(Duration.ofMillis(60_000).toNanos());
        assertThat(timer.statements().get(1).success()).isTrue();
    }

    @Test
    public void aLaterFailedReportOfASuccessfulRecordMarksItFailed() {
        final StatementTimer timer = new StatementTimer();
        final Map<String, Object> state = new HashMap<>(Map.of("duration", Duration.ofMillis(5), "success", true));
        final QueryExecutionInfo execution = execution(state);
        timer.afterQuery(execution);
        state.put("success", false);
        state.put("throwable", new R2dbcTimeoutException("canceling statement due to statement timeout", "57014"));
        timer.afterQuery(execution);

        assertThat(timer.statements()).singleElement().satisfies(s -> {
            assertThat(s.success()).isFalse();
            assertThat(s.failure()).contains("57014");
        });
        timer.clear();
        assertThat(timer.statements()).isEmpty();
    }

    @Test
    public void aReportThatSaysSuccessButCarriesTheErrorOfItsRowsIsAFailure() {
        // r2dbc-proxy: the rows fail (57014) before the query publisher completes; the query subscriber's cancel then
        // reports the execution once, with isSuccess() true and the rows' error as its throwable
        final StatementTimer timer = new StatementTimer();
        timer.afterQuery(execution(new HashMap<>(Map.of("duration", Duration.ofMillis(200), "success", true,
                "throwable", new R2dbcTimeoutException("canceling statement due to statement timeout", "57014")))));

        assertThat(timer.statements()).singleElement().satisfies(s -> {
            assertThat(s.success()).isFalse();
            assertThat(s.failure()).contains("57014");
        });
    }

    /** A QueryExecutionInfo whose answers follow {@code state}: one object, as r2dbc-proxy passes one per execute. */
    private static QueryExecutionInfo execution(final Map<String, Object> state) {
        return (QueryExecutionInfo) Proxy.newProxyInstance(QueryExecutionInfo.class.getClassLoader(),
                new Class<?>[]{QueryExecutionInfo.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getQueries" -> List.of(new QueryInfo("SELECT w.pid AS pid FROM things_journal"));
                    case "getExecuteDuration" -> state.get("duration");
                    case "getCurrentResultCount" -> 0;
                    case "isSuccess" -> state.get("success");
                    case "getThrowable" -> state.get("throwable");
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
