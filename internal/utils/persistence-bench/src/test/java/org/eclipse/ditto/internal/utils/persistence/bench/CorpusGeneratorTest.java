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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.ditto.json.JsonObject;
import org.junit.Test;

public final class CorpusGeneratorTest {

    @Test
    public void depthQuantileFunctionHitsThePinnedQuantiles() {
        assertThat(CorpusGenerator.depthForU(0.0)).isEqualTo(1L);
        assertThat(CorpusGenerator.depthForU(0.5)).isEqualTo(20L);
        assertThat(CorpusGenerator.depthForU(0.99)).isEqualTo(2_000L);
        assertThat(CorpusGenerator.depthForU(0.999)).isEqualTo(10_000L);
        assertThat(CorpusGenerator.depthForU(0.9999999)).isBetween(49_000L, 50_000L);
    }

    @Test
    public void empiricalDistributionMatchesSpec() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 100_000L);
        final List<Long> depths = new ArrayList<>(100_000);
        for (long i = 0; i < 100_000; i++) {
            depths.add(gen.depth(i));
        }
        depths.sort(Long::compareTo);
        final long median = depths.get(50_000);
        final long p99 = depths.get(99_000);
        assertThat(median).isBetween(15L, 25L);
        assertThat(p99).isBetween(1_600L, 2_500L);
        assertThat(depths.get(depths.size() - 1)).isLessThanOrEqualTo(CorpusGenerator.DEPTH_CAP);
    }

    @Test
    public void generatorIsDeterministic() {
        final CorpusGenerator a = new CorpusGenerator(42L, 10_000L);
        final CorpusGenerator b = new CorpusGenerator(42L, 10_000L);
        for (long i = 0; i < 10_000; i += 137) {
            assertThat(a.pid(i)).isEqualTo(b.pid(i));
            assertThat(a.depth(i)).isEqualTo(b.depth(i));
        }
        final Iterator<CorpusGenerator.EventRow> ea = a.events(7L);
        final Iterator<CorpusGenerator.EventRow> eb = b.events(7L);
        while (ea.hasNext()) {
            assertThat(ea.next()).isEqualTo(eb.next());
        }
        assertThat(eb.hasNext()).isFalse();
    }

    @Test
    public void differentSeedsProduceDifferentDepths() {
        final CorpusGenerator a = new CorpusGenerator(42L, 1_000L);
        final CorpusGenerator b = new CorpusGenerator(43L, 1_000L);
        int diff = 0;
        for (long i = 0; i < 1_000; i++) {
            if (a.depth(i) != b.depth(i)) {
                diff++;
            }
        }
        assertThat(diff).isGreaterThan(500);
    }

    @Test
    public void pidFormatAndNamespacePool() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 10_000L);
        for (long i = 0; i < 200; i++) {
            assertThat(gen.pid(i)).matches("thing:bench\\.ns\\d{2}:pid-\\d{8}");
        }
    }

    @Test
    public void eventsAreThingsShapedWithBoundedPayload() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 10_000L);
        final Iterator<CorpusGenerator.EventRow> it = gen.events(3L);
        long expectedSn = 1;
        while (it.hasNext()) {
            final CorpusGenerator.EventRow row = it.next();
            assertThat(row.sn()).isEqualTo(expectedSn++);
            final JsonObject json = JsonObject.of(row.eventJson());
            assertThat(json.getValue("revision").orElseThrow().asLong()).isEqualTo(row.sn());
            final int valueLen = json.getValue("value").orElseThrow().asString().length();
            assertThat(valueLen).isBetween(300, 1_000);
            if (row.sn() == 1) {
                assertThat(row.manifest()).isEqualTo(
                        "org.eclipse.ditto.things.model.signals.events.ThingCreated");
            }
            assertThat(row.timestampMillis())
                    .isEqualTo(CorpusGenerator.BASE_TIMESTAMP_MILLIS + row.sn() * 1000L);
        }
        assertThat(expectedSn - 1).isEqualTo(gen.depth(3L));
    }

    @Test
    public void snapshotsFollowThe500Cadence() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 1_000_000L);
        final long p99Index = gen.findPidIndexes(CorpusGenerator.PidClass.P99, 1, 0).get(0);
        final long depth = gen.depth(p99Index);
        assertThat(depth).isBetween(2_200L, 2_400L);
        assertThat(gen.latestSnapshotSn(p99Index)).isEqualTo(2_000L);
        assertThat(gen.snapshotCount(p99Index)).isEqualTo(4L);
        final List<Long> sns = new ArrayList<>();
        gen.snapshots(p99Index).forEachRemaining(s -> sns.add(s.sn()));
        assertThat(sns).containsExactly(500L, 1_000L, 1_500L, 2_000L);
        final CorpusGenerator.SnapshotRow snap = gen.snapshots(p99Index).next();
        final JsonObject json = JsonObject.of(snap.snapshotJson());
        assertThat(json.getValue("_revision").orElseThrow().asLong()).isEqualTo(500L);
        assertThat(snap.snapshotJson().length()).isBetween(2_000, 11_000);
    }

    @Test
    public void medianPidsHaveNoSnapshots() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 100_000L);
        final long idx = gen.findPidIndexes(CorpusGenerator.PidClass.MEDIAN, 1, 0).get(0);
        assertThat(gen.latestSnapshotSn(idx)).isZero();
        assertThat(gen.snapshots(idx).hasNext()).isFalse();
    }

    @Test
    public void findPidIndexesFindsAllThreeClassesAtFullScale() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 1_000_000L);
        for (final CorpusGenerator.PidClass cls : CorpusGenerator.PidClass.values()) {
            final List<Long> found = gen.findPidIndexes(cls, 23, 0);
            assertThat(found).as("class %s", cls).hasSize(23);
        }
        // disjoint second sample for cleanup scenarios
        final List<Long> fromMiddle = gen.findPidIndexes(CorpusGenerator.PidClass.P99, 23, 500_000L);
        assertThat(fromMiddle).hasSize(23);
    }

    @Test
    public void totalsAreSumsOfDepths() {
        final CorpusGenerator gen = new CorpusGenerator(42L, 5_000L);
        long events = 0;
        long snaps = 0;
        for (long i = 0; i < 5_000; i++) {
            events += gen.depth(i);
            snaps += gen.depth(i) / CorpusGenerator.SNAPSHOT_EVERY;
        }
        assertThat(gen.totalEvents()).isEqualTo(events);
        assertThat(gen.totalSnapshots()).isEqualTo(snaps);
    }
}
