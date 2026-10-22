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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Opt-in journal tags for the persistence-ping walks ({@code bench.pingTagMix} / {@code BENCH_PING_TAG_MIX}): a pure
 * function of the generator's seed and the pid index, so the pids every walk must deliver are known without reading
 * the database. Disjoint groups:
 * <ul>
 * <li>{@link Group#CLUSTER}: the last {@code count / 100} pids in pid order ({@code COLLATE "C"}), a contiguous block
 * at the end of the key space, {@value #TAG} on every event;</li>
 * <li>{@link Group#SPREAD}: about 10 % of the other pids, spread over the key space, {@value #TAG} on every event;</li>
 * <li>{@link Group#OLDER_ONLY}: about 1 %, tagged on every event but the newest (a pid with a single event drawn for
 * this group stays untagged);</li>
 * <li>{@link Group#NEWEST_ONLY}: about 1 %, tagged on the newest event only;</li>
 * <li>{@link Group#UNTAGGED}: everything else, {@code {}} as without the mix.</li>
 * </ul>
 * Every tagged event also carries {@code priority-<n>}, n from 0 to 9 per pid, as the connectivity priority ping
 * expects. Pids, depths, payloads and timestamps are those of the corpus without the mix.
 */
public final class PingTagMix {

    public static final String TAG = "always-alive";
    /** Suffix of the load evidence of a corpus with the mix, so it never overwrites the untagged corpus's file. */
    public static final String EVIDENCE_SUFFIX = "partly-tagged";

    static final double SPREAD_LIMIT = 0.10;
    static final double OLDER_ONLY_LIMIT = 0.11;
    static final double NEWEST_ONLY_LIMIT = 0.12;
    static final int PRIORITY_LEVELS = 10;

    public enum Group { CLUSTER, SPREAD, OLDER_ONLY, NEWEST_ONLY, UNTAGGED }

    /** Where the tag sits on one pid's events. */
    public record Placement(Group group, long depth, int priority) {

        public boolean tagged(final long sn) {
            return switch (group) {
                case CLUSTER, SPREAD -> true;
                case OLDER_ONLY -> sn < depth;
                case NEWEST_ONLY -> sn == depth;
                case UNTAGGED -> false;
            };
        }

        /** The {@code tags} column in COPY text format. */
        public String tagsLiteral(final long sn) {
            return tagged(sn) ? "{" + TAG + ",priority-" + priority + "}" : "{}";
        }

        public long taggedRows() {
            return switch (group) {
                case CLUSTER, SPREAD -> depth;
                case OLDER_ONLY -> depth - 1;
                case NEWEST_ONLY -> 1;
                case UNTAGGED -> 0;
            };
        }
    }

    private final CorpusGenerator gen;
    private final String[] pids;
    private final int[] inPidOrder;
    private final Group[] groups;
    private final String clusterStart;

    private PingTagMix(final CorpusGenerator gen, final String[] pids, final int[] inPidOrder, final Group[] groups,
            final String clusterStart) {
        this.gen = gen;
        this.pids = pids;
        this.inPidOrder = inPidOrder;
        this.groups = groups;
        this.clusterStart = clusterStart;
    }

    public static PingTagMix of(final CorpusGenerator gen) {
        final int count = Math.toIntExact(gen.pidCount());
        final String[] pids = new String[count];
        for (int i = 0; i < count; i++) {
            pids[i] = gen.pid(i);
        }
        // the pids are ASCII: String order is byte order, the order of the pid column (COLLATE "C")
        final int[] inPidOrder = IntStream.range(0, count).boxed()
                .sorted(Comparator.comparing(i -> pids[i]))
                .mapToInt(Integer::intValue)
                .toArray();
        final int clusterSize = count / 100;
        final String clusterStart = clusterSize == 0 ? "" : pids[inPidOrder[count - clusterSize]];
        final Group[] groups = new Group[count];
        for (int i = 0; i < count; i++) {
            groups[i] = classify(gen, i, !clusterStart.isEmpty() && pids[i].compareTo(clusterStart) >= 0);
        }
        return new PingTagMix(gen, pids, inPidOrder, groups, clusterStart);
    }

    static Group classify(final CorpusGenerator gen, final long pidIndex, final boolean inCluster) {
        if (inCluster) {
            return Group.CLUSTER;
        }
        final double u = gen.pingTagDraw(pidIndex);
        if (u < SPREAD_LIMIT) {
            return Group.SPREAD;
        }
        if (u < OLDER_ONLY_LIMIT) {
            // a pid with a single event has no older event to carry the tag
            return gen.depth(pidIndex) >= 2 ? Group.OLDER_ONLY : Group.UNTAGGED;
        }
        if (u < NEWEST_ONLY_LIMIT) {
            return Group.NEWEST_ONLY;
        }
        return Group.UNTAGGED;
    }

    /** The pid index of a corpus pid ({@code …:pid-NNNNNNNN}). */
    static long indexOf(final String pid) {
        return Long.parseLong(pid.substring(pid.lastIndexOf('-') + 1));
    }

    public Group group(final long pidIndex) {
        return groups[Math.toIntExact(pidIndex)];
    }

    public int priority(final long pidIndex) {
        return (int) (gen.pingPriorityDraw(pidIndex) * PRIORITY_LEVELS);
    }

    public Placement placement(final long pidIndex) {
        return new Placement(group(pidIndex), gen.depth(pidIndex), priority(pidIndex));
    }

    /** The first pid of the clustered block; empty for a corpus below 100 pids. */
    public String clusterStart() {
        return clusterStart;
    }

    /** Every corpus pid in pid order. */
    public List<String> allPidsSorted() {
        return Arrays.stream(inPidOrder).mapToObj(i -> pids[i]).toList();
    }

    /** The pids of a group in pid order. */
    public List<String> pids(final Group group) {
        return Arrays.stream(inPidOrder).filter(i -> groups[i] == group).mapToObj(i -> pids[i]).toList();
    }

    /**
     * What an ID-order walk must deliver, in pid order: the pids with any tagged event, or with
     * {@code considerOnlyLatest} those whose newest event is tagged. The priority order delivers the first set too.
     */
    public List<String> expectedIdOrder(final boolean considerOnlyLatest) {
        return Arrays.stream(inPidOrder)
                .filter(i -> groups[i] != Group.UNTAGGED && !(considerOnlyLatest && groups[i] == Group.OLDER_ONLY))
                .mapToObj(i -> pids[i])
                .toList();
    }

    /** The {@code priority-<n>} of every pid with a tagged event (constant per pid, so also its newest tagged one). */
    public Map<String, Integer> priorityByPid() {
        final Map<String, Integer> result = new LinkedHashMap<>();
        for (final int i : inPidOrder) {
            if (groups[i] != Group.UNTAGGED) {
                result.put(pids[i], priority(i));
            }
        }
        return result;
    }

    public long taggedRows() {
        long rows = 0;
        for (int i = 0; i < pids.length; i++) {
            rows += placement(i).taggedRows();
        }
        return rows;
    }

    /** Journal rows of window k (batch pids in pid order): {@code [k][0]} all, {@code [k][1]} of untagged pids. */
    public long[][] journalRowsPerWindow(final int batch) {
        final int windows = (pids.length + batch - 1) / batch;
        final long[][] rows = new long[windows][2];
        for (int position = 0; position < inPidOrder.length; position++) {
            final int i = inPidOrder[position];
            final long depth = gen.depth(i);
            rows[position / batch][0] += depth;
            if (groups[i] == Group.UNTAGGED) {
                rows[position / batch][1] += depth;
            }
        }
        return rows;
    }

    /** SHA-256 (hex) over the given pids, each followed by a newline. */
    static String sha256(final List<String> sortedPids) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final String pid : sortedPids) {
                digest.update((pid + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The groups, their shares and checksums, and the expected sets, as an evidence section. */
    public String evidenceMarkdown() {
        final StringBuilder md = new StringBuilder("## Ping tag mix\n\n");
        md.append("Tags `").append(TAG).append("` + `priority-<n>` (n = 0–9 per pid) on the tagged events; group draw ")
                .append("= generator stream 6, priority = stream 7; cluster = the last count/100 pids in pid order, ")
                .append(clusterStart.isEmpty() ? "none (below 100 pids)" : "from `" + clusterStart + "`")
                .append(". Checksum = SHA-256 over the sorted pids, each followed by `\\n`.\n\n")
                .append("| group | pids | share | tagged events | journal rows of these pids | sha256 |\n")
                .append("|---|---|---|---|---|---|\n");
        for (final Group group : Group.values()) {
            final List<String> members = pids(group);
            final long rows = members.stream().mapToLong(pid -> gen.depth(indexOf(pid))).sum();
            md.append(String.format("| %s | %,d | %.4f %% | %s | %,d | `%s` |%n", group, members.size(),
                    100.0 * members.size() / pids.length, describe(group), rows, sha256(members)));
        }
        final List<String> any = expectedIdOrder(false);
        final List<String> newest = expectedIdOrder(true);
        md.append("\n| expected set | pids | sha256 |\n|---|---|---|\n")
                .append(String.format(
                        "| ID order, any tagged event; priority order; the policies ping | %,d | `%s` |%n",
                        any.size(), sha256(any)))
                .append(String.format("| ID order, newest event tagged | %,d | `%s` |%n",
                        newest.size(), sha256(newest)))
                .append(String.format("%n- tagged journal rows (expected): %,d%n%n", taggedRows()));
        return md.toString();
    }

    private static String describe(final Group group) {
        return switch (group) {
            case CLUSTER, SPREAD -> "every event";
            case OLDER_ONLY -> "every event but the newest";
            case NEWEST_ONLY -> "the newest event only";
            case UNTAGGED -> "none";
        };
    }
}
