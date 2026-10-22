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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;

/**
 * Records every statement executed through a connection factory: start, duration until its last row was read
 * (r2dbc-proxy's execute duration), rows, success, SQLSTATE of a failure, and the SQL.
 */
final class StatementTimer implements ProxyExecutionListener {

    record Statement(Instant start, long nanos, int rows, boolean success, String failure, String sql) {}

    private final List<Statement> statements = new CopyOnWriteArrayList<>();

    ConnectionFactory decorate(final ConnectionFactory factory) {
        return ProxyConnectionFactory.builder(factory).listener(this).build();
    }

    @Override
    public void afterQuery(final QueryExecutionInfo info) {
        final String sql = info.getQueries().stream().map(QueryInfo::getQuery).collect(Collectors.joining("; "));
        final Duration took = info.getExecuteDuration();
        statements.add(new Statement(Instant.now().minus(took), took.toNanos(), info.getCurrentResultCount(),
                info.isSuccess(), info.getThrowable() == null ? "" : describe(info.getThrowable()), sql));
    }

    void clear() {
        statements.clear();
    }

    List<Statement> statements() {
        return List.copyOf(statements);
    }

    static String describe(final Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof R2dbcException r2dbc && r2dbc.getSqlState() != null) {
                return "SQLSTATE <" + r2dbc.getSqlState() + "> " + r2dbc.getMessage();
            }
        }
        return error.toString();
    }
}
