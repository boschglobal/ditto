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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;

/**
 * Records every statement executed through a connection factory: start, duration until its last row was read
 * (r2dbc-proxy's execute duration), rows, success, SQLSTATE of a failure, and the SQL. One record per execution:
 * r2dbc-proxy reports the end of one execution once per terminal signal of its subscribers (a statement cancelled by
 * statement_timeout arrives three times, with the same execution object); the record keeps the first report's timing
 * and is failed if any report failed. A report with an error is a failure even when it says success: when the rows fail
 * before the query publisher has completed, r2dbc-proxy reports the execution from the query subscriber's cancel, as
 * successful, with the rows' error still set.
 */
final class StatementTimer implements ProxyExecutionListener {

    record Statement(Instant start, long nanos, int rows, boolean success, String failure, String sql) {}

    private final List<Statement> statements = new ArrayList<>();
    private final Map<QueryExecutionInfo, Integer> recordOf = new IdentityHashMap<>();

    ConnectionFactory decorate(final ConnectionFactory factory) {
        return ProxyConnectionFactory.builder(factory).listener(this).build();
    }

    @Override
    public synchronized void afterQuery(final QueryExecutionInfo info) {
        final String sql = info.getQueries().stream().map(QueryInfo::getQuery).collect(Collectors.joining("; "));
        final Duration took = info.getExecuteDuration();
        final Statement reported = new Statement(Instant.now().minus(took), took.toNanos(),
                info.getCurrentResultCount(), info.isSuccess() && info.getThrowable() == null,
                info.getThrowable() == null ? "" : describe(info.getThrowable()), sql);
        final Integer index = recordOf.get(info);
        if (index == null) {
            recordOf.put(info, statements.size());
            statements.add(reported);
        } else if (statements.get(index).success() && !reported.success()) {
            final Statement first = statements.get(index);
            statements.set(index, new Statement(first.start(), first.nanos(), first.rows(), false,
                    reported.failure(), first.sql()));
        }
    }

    synchronized void clear() {
        statements.clear();
        recordOf.clear();
    }

    synchronized List<Statement> statements() {
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
