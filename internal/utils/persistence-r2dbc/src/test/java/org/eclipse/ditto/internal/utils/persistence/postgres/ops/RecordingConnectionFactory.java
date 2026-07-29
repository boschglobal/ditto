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
package org.eclipse.ditto.internal.utils.persistence.postgres.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.reactivestreams.Publisher;

import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Readable;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * An offline {@link ConnectionFactory} stub that drives a real {@code ConnectionPool} for unit/contract tests of the
 * shared persistence operations and the three plugins without a live PostgreSQL. It records every executed SQL string
 * and the binds, and lets a test script typed-column rows or a failure per execution. Transaction calls are accepted as
 * no-ops.
 */
public final class RecordingConnectionFactory implements ConnectionFactory {

    /** SQL substring -> rows to emit (each row = column name -> value). */
    private final Map<String, List<Map<String, Object>>> rowsBySqlSubstring = new LinkedHashMap<>();
    /** SQL substring -> failure to raise on execute. */
    private final Map<String, RuntimeException> failuresBySqlSubstring = new LinkedHashMap<>();
    private final List<String> executedSql = new CopyOnWriteArrayList<>();
    /** Records the {@code Statement.fetchSize(n)} bound per executed SQL (0 = never set). */
    private final Map<String, Integer> fetchSizeBySql = new java.util.concurrent.ConcurrentHashMap<>();
    private long defaultRowsUpdated = 1L;

    public RecordingConnectionFactory onSql(final String sqlSubstring, final List<Map<String, Object>> rows) {
        rowsBySqlSubstring.put(sqlSubstring, rows);
        return this;
    }

    public RecordingConnectionFactory failOnSql(final String sqlSubstring, final RuntimeException failure) {
        failuresBySqlSubstring.put(sqlSubstring, failure);
        return this;
    }

    public List<String> executedSql() {
        return List.copyOf(executedSql);
    }

    public boolean executedContaining(final String sqlSubstring) {
        return executedSql.stream().anyMatch(sql -> sql.contains(sqlSubstring));
    }

    /** How many times any SQL containing {@code sqlSubstring} was prepared (createStatement). */
    public long executedCountContaining(final String sqlSubstring) {
        return executedSql.stream().filter(sql -> sql.contains(sqlSubstring)).count();
    }

    /** The {@code Statement.fetchSize(n)} bound on the first executed SQL containing {@code sqlSubstring} (0 if unset). */
    public int fetchSizeForSqlContaining(final String sqlSubstring) {
        return fetchSizeBySql.entrySet().stream()
                .filter(entry -> entry.getKey().contains(sqlSubstring))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(0);
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.fromCallable(StubConnection::new);
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return () -> "recording-stub";
    }

    private List<Map<String, Object>> rowsFor(final String sql) {
        return rowsBySqlSubstring.entrySet().stream()
                .filter(entry -> sql.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }

    private RuntimeException failureFor(final String sql) {
        return failuresBySqlSubstring.entrySet().stream()
                .filter(entry -> sql.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private final class StubConnection implements Connection {

        @Override
        public Publisher<Void> close() {
            return Mono.empty();
        }

        @Override
        public Statement createStatement(final String sql) {
            executedSql.add(sql);
            return new StubStatement(sql);
        }

        @Override
        public Publisher<Void> beginTransaction() {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> beginTransaction(final io.r2dbc.spi.TransactionDefinition definition) {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> commitTransaction() {
            return Mono.empty();
        }

        @Override
        public io.r2dbc.spi.Batch createBatch() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Publisher<Void> createSavepoint(final String name) {
            return Mono.empty();
        }

        @Override
        public boolean isAutoCommit() {
            return false;
        }

        @Override
        public io.r2dbc.spi.ConnectionMetadata getMetadata() {
            return new io.r2dbc.spi.ConnectionMetadata() {
                @Override
                public String getDatabaseProductName() {
                    return "recording-stub";
                }

                @Override
                public String getDatabaseVersion() {
                    return "0";
                }
            };
        }

        @Override
        public io.r2dbc.spi.IsolationLevel getTransactionIsolationLevel() {
            return io.r2dbc.spi.IsolationLevel.READ_COMMITTED;
        }

        @Override
        public Publisher<Void> releaseSavepoint(final String name) {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> rollbackTransaction() {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> rollbackTransactionToSavepoint(final String name) {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> setAutoCommit(final boolean autoCommit) {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> setLockWaitTimeout(final java.time.Duration timeout) {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> setStatementTimeout(final java.time.Duration timeout) {
            return Mono.empty();
        }

        @Override
        public Publisher<Void> setTransactionIsolationLevel(final io.r2dbc.spi.IsolationLevel isolationLevel) {
            return Mono.empty();
        }

        @Override
        public Publisher<Boolean> validate(final io.r2dbc.spi.ValidationDepth depth) {
            return Mono.just(true);
        }
    }

    private final class StubStatement implements Statement {

        private final String sql;

        private StubStatement(final String sql) {
            this.sql = sql;
        }

        @Override
        public Statement add() {
            return this;
        }

        @Override
        public Statement bind(final int index, final Object value) {
            return this;
        }

        @Override
        public Statement bind(final String name, final Object value) {
            return this;
        }

        @Override
        public Statement bindNull(final int index, final Class<?> type) {
            return this;
        }

        @Override
        public Statement bindNull(final String name, final Class<?> type) {
            return this;
        }

        @Override
        public Statement fetchSize(final int rows) {
            fetchSizeBySql.put(sql, rows);
            return this;
        }

        @Override
        public Publisher<? extends Result> execute() {
            final RuntimeException failure = failureFor(sql);
            if (failure != null) {
                return Mono.error(failure);
            }
            return Mono.just(new StubResult(rowsFor(sql), defaultRowsUpdated));
        }
    }

    private static final class StubResult implements Result {

        private final List<Map<String, Object>> rows;
        private final long rowsUpdated;

        private StubResult(final List<Map<String, Object>> rows, final long rowsUpdated) {
            this.rows = rows;
            this.rowsUpdated = rowsUpdated;
        }

        @Override
        public Publisher<Long> getRowsUpdated() {
            return Mono.just(rows.isEmpty() ? rowsUpdated : (long) rows.size());
        }

        @Override
        public <T> Publisher<T> map(final BiFunction<Row, RowMetadata, ? extends T> mappingFunction) {
            final List<T> mapped = new ArrayList<>();
            for (final Map<String, Object> row : rows) {
                mapped.add(mappingFunction.apply(new StubRow(row), new StubRowMetadata(row)));
            }
            return Flux.fromIterable(mapped);
        }

        @Override
        public <T> Publisher<T> map(final Function<? super Readable, ? extends T> mappingFunction) {
            final List<T> mapped = new ArrayList<>();
            for (final Map<String, Object> row : rows) {
                mapped.add(mappingFunction.apply(new StubRow(row)));
            }
            return Flux.fromIterable(mapped);
        }

        @Override
        public Result filter(final java.util.function.Predicate<Segment> filter) {
            return this;
        }

        @Override
        public <T> Publisher<T> flatMap(final Function<Segment, ? extends Publisher<? extends T>> mappingFunction) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubRow implements Row {

        private final Map<String, Object> values;

        private StubRow(final Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public RowMetadata getMetadata() {
            return new StubRowMetadata(values);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(final int index, final Class<T> type) {
            final Object value = new ArrayList<>(values.values()).get(index);
            return (T) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(final String name, final Class<T> type) {
            return (T) values.get(name);
        }
    }

    private static final class StubRowMetadata implements RowMetadata {

        private final Map<String, Object> values;

        private StubRowMetadata(final Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public ColumnMetadata getColumnMetadata(final int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ColumnMetadata getColumnMetadata(final String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<? extends ColumnMetadata> getColumnMetadatas() {
            return List.of();
        }
    }

}
