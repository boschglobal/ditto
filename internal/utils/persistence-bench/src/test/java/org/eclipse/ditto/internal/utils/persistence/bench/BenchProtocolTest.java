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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class BenchProtocolTest {

    @Test
    public void percentileIsNearestRank() {
        final List<Double> sorted = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0,
                11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0);
        assertThat(BenchProtocol.percentile(sorted, 50.0)).isEqualTo(10.0);
        assertThat(BenchProtocol.percentile(sorted, 95.0)).isEqualTo(19.0);
        assertThat(BenchProtocol.percentile(sorted, 100.0)).isEqualTo(20.0);
        assertThat(BenchProtocol.percentile(List.of(7.5), 50.0)).isEqualTo(7.5);
    }

    @Test
    public void ofComputesStats() {
        final BenchProtocol.Timing t = BenchProtocol.of(List.of(3.0, 1.0, 2.0));
        assertThat(t.n()).isEqualTo(3);
        assertThat(t.p50Ms()).isEqualTo(2.0);
        assertThat(t.maxMs()).isEqualTo(3.0);
        assertThat(t.meanMs()).isEqualTo(2.0);
    }

    @Test
    public void measureRunsWarmupsThenTimedIterations() {
        final AtomicInteger calls = new AtomicInteger();
        final BenchProtocol.Timing t = BenchProtocol.measure(2, 5, iteration -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(7);
        assertThat(t.n()).isEqualTo(5);
        assertThat(t.samplesMs()).hasSize(5);
    }

    @Test
    public void measurePassesDistinctIterationIndexes() {
        final StringBuilder seen = new StringBuilder();
        BenchProtocol.measure(1, 3, iteration -> seen.append(iteration).append(','));
        // warmup gets 0; timed iterations continue counting
        assertThat(seen.toString()).isEqualTo("0,1,2,3,");
    }

    @Test
    public void tableRendersGithubMarkdown() {
        final String md = BenchProtocol.table(List.of("scenario", "pg", "mongo"),
                List.of(List.of("R1", "1.2", "3.4")));
        assertThat(md).isEqualTo("""
                | scenario | pg | mongo |
                |---|---|---|
                | R1 | 1.2 | 3.4 |
                """);
    }

    @Test
    public void timingCellsFormat() {
        final BenchProtocol.Timing t = BenchProtocol.of(List.of(1.234, 2.345, 3.456));
        assertThat(t.cells()).isEqualTo("2.35 | 3.46 | 3.46");
    }

    @Test
    public void tableEscapesPipesInsideCells() {
        final String md = BenchProtocol.table(
                List.of("scenario", "PG p50 | p95 | max (ms)"),
                List.of(List.of("R1", "0.58 | 0.74 | 0.74")));
        assertThat(md).isEqualTo("""
                | scenario | PG p50 \\| p95 \\| max (ms) |
                |---|---|
                | R1 | 0.58 \\| 0.74 \\| 0.74 |
                """);
    }

    @Test
    public void tableWithoutPipesIsUnchanged() {
        final String md = BenchProtocol.table(List.of("scenario", "pg", "mongo"),
                List.of(List.of("R1", "1.2", "3.4")));
        assertThat(md).isEqualTo("""
                | scenario | pg | mongo |
                |---|---|---|
                | R1 | 1.2 | 3.4 |
                """);
    }

    @Test
    public void inlineLiteralsSubstitutesPositionallyInOrder() {
        final String sql = BenchProtocol.inlineLiterals(
                "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?", "x", 42L, 7);
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE a = 'x' AND b = 42 AND c = 7");
    }

    @Test
    public void inlineLiteralsQuotesStringsAndDoublesEmbeddedSingleQuotes() {
        final String sql = BenchProtocol.inlineLiterals("SELECT * FROM t WHERE name = ?", "O'Brien");
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE name = 'O''Brien'");
    }

    @Test
    public void inlineLiteralsRendersNumbersUnquoted() {
        final String sql = BenchProtocol.inlineLiterals("SELECT ?, ?", 1L, Long.MAX_VALUE);
        assertThat(sql).isEqualTo("SELECT 1, 9223372036854775807");
    }

    @Test
    public void inlineLiteralsQuotesNonNumericNonStringArgsLikeTimestamps() {
        final String sql = BenchProtocol.inlineLiterals("SELECT * FROM t WHERE ts <= ?",
                Instant.ofEpochMilli(PersistenceShapes.PG_MAX_WRITTEN_AT_MILLIS).atOffset(ZoneOffset.UTC));
        assertThat(sql).isEqualTo("SELECT * FROM t WHERE ts <= '9999-12-31T23:59:59Z'");
    }

    @Test
    public void inlineLiteralsThrowsOnPlaceholderArgCountMismatch() {
        assertThatThrownBy(() -> BenchProtocol.inlineLiterals("SELECT ? , ?", "only-one"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
