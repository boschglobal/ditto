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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.ditto.internal.utils.persistence.bench.PingTagMix.Group;
import org.junit.Test;

public final class PingTagMixTest {

    private static final CorpusGenerator GEN_250K = new CorpusGenerator(42L, 250_000L);
    private static final PingTagMix MIX_250K = PingTagMix.of(GEN_250K);

    @Test
    public void theMixIsAPureFunctionOfSeedAndPidIndex() {
        final PingTagMix again = PingTagMix.of(new CorpusGenerator(42L, 250_000L));
        for (long i = 0; i < GEN_250K.pidCount(); i += 997) {
            assertThat(again.group(i)).isEqualTo(MIX_250K.group(i));
            assertThat(again.priority(i)).isEqualTo(MIX_250K.priority(i));
        }
        assertThat(PingTagMix.of(new CorpusGenerator(43L, 250_000L)).expectedIdOrder(false))
                .as("another seed, another mix").isNotEqualTo(MIX_250K.expectedIdOrder(false));
    }

    @Test
    public void groupsAreDisjointAndCoverEveryPid() {
        final Set<String> seen = new HashSet<>();
        long total = 0;
        for (final Group group : Group.values()) {
            final List<String> pids = MIX_250K.pids(group);
            total += pids.size();
            assertThat(pids).allSatisfy(pid -> assertThat(seen.add(pid)).as(pid + " in two groups").isTrue());
        }
        assertThat(total).isEqualTo(250_000L);
    }

    @Test
    public void sharesAt250kAreWithinTheStatedTolerance() {
        final Map<Group, Double> share = new EnumMap<>(Group.class);
        for (final Group group : Group.values()) {
            share.put(group, MIX_250K.pids(group).size() / 250_000.0);
        }
        assertThat(MIX_250K.pids(Group.CLUSTER)).hasSize(2_500);
        assertThat(share.get(Group.SPREAD)).isBetween(0.096, 0.102);
        assertThat(share.get(Group.OLDER_ONLY)).isBetween(0.0085, 0.0100); // depth-1 pids (≈ 6.8 %) stay untagged
        assertThat(share.get(Group.NEWEST_ONLY)).isBetween(0.0090, 0.0110);
        assertThat(share.get(Group.UNTAGGED)).isBetween(0.865, 0.878);
        // pinned from the planner's independent model of the generator (see the plan, §3.1)
        assertThat(MIX_250K.pids(Group.SPREAD)).hasSize(24_727);
        assertThat(MIX_250K.pids(Group.OLDER_ONLY)).hasSize(2_317);
        assertThat(MIX_250K.pids(Group.NEWEST_ONLY)).hasSize(2_494);
        assertThat(MIX_250K.taggedRows()).isEqualTo(4_080_789L);
    }

    @Test
    public void theClusterIsTheContiguousEndOfTheKeySpace() {
        final List<String> sorted = MIX_250K.allPidsSorted();
        final List<String> tail = sorted.subList(250_000 - 2_500, 250_000);
        assertThat(MIX_250K.pids(Group.CLUSTER)).containsExactlyElementsOf(tail);
        assertThat(MIX_250K.clusterStart()).isEqualTo(tail.get(0)).isEqualTo("thing:bench.ns48:pid-00185848");
    }

    @Test
    public void spreadPidsCoverEveryTenthOfTheKeySpace() {
        final List<String> sorted = MIX_250K.allPidsSorted().subList(0, 250_000 - 2_500);
        final Set<String> spread = new HashSet<>(MIX_250K.pids(Group.SPREAD));
        for (int tenth = 0; tenth < 10; tenth++) {
            final List<String> slice = sorted.subList(tenth * sorted.size() / 10, (tenth + 1) * sorted.size() / 10);
            final long inSlice = slice.stream().filter(spread::contains).count();
            assertThat(inSlice / (double) slice.size()).as("tenth " + tenth).isBetween(0.08, 0.12);
        }
    }

    @Test
    public void olderOnlyTagsEveryEventButTheNewestAndNewestOnlyTagsOnlyTheNewest() {
        for (final Group group : Group.values()) {
            for (final String pid : MIX_250K.pids(group).subList(0, Math.min(50, MIX_250K.pids(group).size()))) {
                final long index = PingTagMix.indexOf(pid);
                final PingTagMix.Placement placement = MIX_250K.placement(index);
                final long depth = GEN_250K.depth(index);
                final List<Boolean> tagged = new ArrayList<>();
                for (long sn = 1; sn <= depth; sn++) {
                    tagged.add(placement.tagged(sn));
                }
                switch (group) {
                    case CLUSTER, SPREAD -> assertThat(tagged).containsOnly(true);
                    case OLDER_ONLY -> {
                        assertThat(depth).isGreaterThanOrEqualTo(2L);
                        assertThat(tagged.subList(0, (int) depth - 1)).containsOnly(true);
                        assertThat(tagged.get((int) depth - 1)).isFalse();
                    }
                    case NEWEST_ONLY -> {
                        assertThat(tagged.get((int) depth - 1)).isTrue();
                        assertThat(tagged.subList(0, (int) depth - 1)).doesNotContain(true);
                    }
                    case UNTAGGED -> assertThat(tagged).doesNotContain(true);
                }
                assertThat(placement.tagsLiteral(depth)).isIn("{}",
                        "{always-alive,priority-" + MIX_250K.priority(index) + "}");
            }
        }
    }

    @Test
    public void expectedSetsFollowTheReadJournalSemanticsAndCarryStableChecksums() {
        final List<String> any = MIX_250K.expectedIdOrder(false);
        final List<String> newest = MIX_250K.expectedIdOrder(true);
        assertThat(any).hasSize(32_038).isSorted();
        assertThat(newest).hasSize(29_721).isSorted();
        assertThat(any).containsAll(newest).containsAll(MIX_250K.pids(Group.OLDER_ONLY));
        assertThat(newest).doesNotContainAnyElementsOf(MIX_250K.pids(Group.OLDER_ONLY));
        assertThat(PingTagMix.sha256(any))
                .isEqualTo("5761a81d53cd3e99ab4fdfec2843cc9fc434e9cad37ea74fb3324f6f03617988");
        assertThat(PingTagMix.sha256(newest))
                .isEqualTo("39ef64837a2833e3d9346405e6ec9a3b986b28efd6e45f6d71600a856790f757");
    }

    @Test
    public void everyExpectedPidHasAPriorityFromZeroToNine() {
        assertThat(MIX_250K.priorityByPid()).hasSize(32_038)
                .allSatisfy((pid, priority) -> assertThat(priority).isBetween(0, 9));
    }

    @Test
    public void aCorpusBelowOneHundredPidsHasNoCluster() {
        final PingTagMix small = PingTagMix.of(new CorpusGenerator(42L, 99L));
        assertThat(small.pids(Group.CLUSTER)).isEmpty();
        assertThat(small.clusterStart()).isEmpty();
    }
}
