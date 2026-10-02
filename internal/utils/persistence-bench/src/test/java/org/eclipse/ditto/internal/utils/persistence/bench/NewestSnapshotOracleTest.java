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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.BeforeClass;
import org.junit.Test;

public final class NewestSnapshotOracleTest {

    private static final long COUNT = 60_000L;
    private static final int LIMIT = 100;

    private static CorpusGenerator gen;
    private static NewestSnapshotOracle oracle;
    /** Every snapshotted pid in byte order (the order of PG's "C" collation and of Mongo's binary strings). */
    private static TreeMap<String, Long> snapshottedByPid;
    private static Map<String, Long> indexByPid;

    @BeforeClass
    public static void buildCorpus() {
        gen = new CorpusGenerator(42L, COUNT, 0.3, 1_790_000_000_000L);
        oracle = NewestSnapshotOracle.of(gen);
        snapshottedByPid = new TreeMap<>();
        indexByPid = new HashMap<>();
        for (long i = 0; i < COUNT; i++) {
            if (gen.snapshotCount(i) > 0) {
                snapshottedByPid.put(gen.pid(i), i);
                indexByPid.put(gen.pid(i), i);
            }
        }
    }

    @Test
    public void countsTheSnapshottedAndTheFreshOnlyPids() {
        assertThat(oracle.snapshottedPids()).isEqualTo(snapshottedByPid.size());
        assertThat(oracle.freshOnlySnapshottedPids()).isEqualTo(
                snapshottedByPid.values().stream().filter(gen::isFreshOnly).count());
        assertThat(oracle.freshOnlySnapshottedPids()).isPositive().isLessThan(oracle.snapshottedPids());
    }

    @Test
    public void pgPageWithoutAgeFilterIsTheNextDistinctSnapshottedPidsWithTheirNewestSnapshot() {
        final String lowerBound = snapshottedByPid.keySet().stream().skip(37).findFirst().orElseThrow();
        final List<NewestSnapshotOracle.PidSn> page = oracle.pgPage(lowerBound, false, LIMIT);

        assertThat(page).extracting(NewestSnapshotOracle.PidSn::pid)
                .containsExactlyElementsOf(snapshottedByPid.tailMap(lowerBound, false).keySet().stream()
                        .limit(LIMIT).toList());
        assertThat(page).allSatisfy(e -> assertThat(e.sn()).isEqualTo(gen.latestSnapshotSn(indexByPid.get(e.pid()))));
        assertThat(page).anySatisfy(e -> assertThat(gen.isFreshOnly(indexByPid.get(e.pid()))).isTrue());
    }

    @Test
    public void pgPageWithAgeFilterSkipsFreshOnlyPidsAndScansFurtherToFillThePage() {
        final String lowerBound = snapshottedByPid.keySet().stream().skip(37).findFirst().orElseThrow();
        final List<NewestSnapshotOracle.PidSn> unfiltered = oracle.pgPage(lowerBound, false, LIMIT);
        final List<NewestSnapshotOracle.PidSn> page = oracle.pgPage(lowerBound, true, LIMIT);

        assertThat(page).hasSize(LIMIT);
        assertThat(page).extracting(NewestSnapshotOracle.PidSn::pid)
                .containsExactlyElementsOf(snapshottedByPid.tailMap(lowerBound, false).entrySet().stream()
                        .filter(e -> !gen.isFreshOnly(e.getValue()))
                        .map(Map.Entry::getKey)
                        .limit(LIMIT).toList());
        assertThat(page).allSatisfy(e -> assertThat(e.sn()).isEqualTo(gen.latestSnapshotSn(indexByPid.get(e.pid()))));
        assertThat(page.get(LIMIT - 1).pid()).isGreaterThan(unfiltered.get(LIMIT - 1).pid());
    }

    @Test
    public void mongoPageLimitsSnapshotRowsBeforeGroupingByPid() {
        for (final boolean ageFilter : new boolean[]{false, true}) {
            final String lowerBound = snapshottedByPid.keySet().stream().skip(500).findFirst().orElseThrow();
            final List<NewestSnapshotOracle.PidSn> page = oracle.mongoPage(lowerBound, ageFilter, LIMIT);
            final List<NewestSnapshotOracle.PidSn> pgPage = oracle.pgPage(lowerBound, ageFilter, LIMIT);

            // the first LIMIT rows in (pid asc, sn desc) order: every pid but the last contributes all its rows
            long rowsBeforeLast = 0;
            for (int i = 0; i < page.size() - 1; i++) {
                rowsBeforeLast += gen.snapshotCount(indexByPid.get(page.get(i).pid()));
            }
            final long lastRows = gen.snapshotCount(indexByPid.get(page.get(page.size() - 1).pid()));
            assertThat(rowsBeforeLast).as("ageFilter=%s", ageFilter).isLessThan(LIMIT);
            assertThat(rowsBeforeLast + lastRows).as("ageFilter=%s", ageFilter).isGreaterThanOrEqualTo(LIMIT);
            // $first after the (pid asc, sn desc) sort: the newest row of each pid, even the partially read last one
            assertThat(page).allSatisfy(e ->
                    assertThat(e.sn()).isEqualTo(gen.latestSnapshotSn(indexByPid.get(e.pid()))));
            assertThat(pgPage.subList(0, page.size())).isEqualTo(page);
            assertThat(page.size()).isLessThan(LIMIT);
        }
    }

    @Test
    public void pagesAboveTheLastSnapshottedPidAreEmpty() {
        final String last = snapshottedByPid.lastKey();
        assertThat(oracle.pgPage(last, false, LIMIT)).isEmpty();
        assertThat(oracle.mongoPage(last, true, LIMIT)).isEmpty();
        assertThat(oracle.pgPage("", true, LIMIT)).hasSize(LIMIT);
    }

    @Test
    public void anUnboundedPageListsEveryQualifyingPid() {
        assertThat(oracle.pgPage("", false, Integer.MAX_VALUE)).hasSize((int) oracle.snapshottedPids());
        assertThat(oracle.pgPage("", true, Integer.MAX_VALUE))
                .hasSize((int) (oracle.snapshottedPids() - oracle.freshOnlySnapshottedPids()));
    }
}
