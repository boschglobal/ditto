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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The expected result of one cleanup pid-stream page (getNewestSnapshotsAbove), derived from the
 * generator alone, so a bench run can check that what it timed returned the right rows on either
 * backend.
 * <p>
 * "Age filter" means a min age that every backdated row exceeds and no fresh-only row reaches (the
 * corpus mode's contract; the C1 retention bench checks it on the loaded data). A pid is then either
 * entirely qualifying or entirely not, so its newest qualifying snapshot is its latest snapshot.
 * Pid order is byte order: PG's {@code COLLATE "C"} and Mongo's binary string comparison agree on
 * these ASCII pids, and so does {@link String#compareTo}.
 */
public final class NewestSnapshotOracle {

    /** One page entry: a pid and the sequence number of the snapshot the page returns for it. */
    public record PidSn(String pid, long sn) {}

    private record Entry(String pid, long latestSn, long snapshotCount, boolean freshOnly) {}

    /** Every pid with at least one snapshot, in pid order. */
    private final List<Entry> entries;

    private NewestSnapshotOracle(final List<Entry> entries) {
        this.entries = entries;
    }

    public static NewestSnapshotOracle of(final CorpusGenerator gen) {
        final List<Entry> entries = new ArrayList<>();
        for (long i = 0; i < gen.pidCount(); i++) {
            final long snapshots = gen.snapshotCount(i);
            if (snapshots > 0) {
                entries.add(new Entry(gen.pid(i), gen.latestSnapshotSn(i), snapshots, gen.isFreshOnly(i)));
            }
        }
        entries.sort(Comparator.comparing(Entry::pid));
        return new NewestSnapshotOracle(List.copyOf(entries));
    }

    public long snapshottedPids() {
        return entries.size();
    }

    public long freshOnlySnapshottedPids() {
        return entries.stream().filter(Entry::freshOnly).count();
    }

    /**
     * PostgresPersistenceOperations.getNewestSnapshotsAbove: the first {@code limit} distinct pids
     * above the lower bound that have a qualifying row, each with its newest qualifying snapshot.
     */
    public List<PidSn> pgPage(final String lowerBound, final boolean ageFilter, final int limit) {
        final List<PidSn> page = new ArrayList<>(Math.min(limit, entries.size()));
        for (int i = firstAbove(lowerBound); i < entries.size() && page.size() < limit; i++) {
            final Entry e = entries.get(i);
            if (!(ageFilter && e.freshOnly())) {
                page.add(new PidSn(e.pid(), e.latestSn()));
            }
        }
        return page;
    }

    /**
     * MongoReadJournal.listNewestActiveSnapshotsByBatch: {@code $limit} applies to the qualifying
     * snapshot ROWS in (pid asc, sn desc) order before {@code $group}, so a page holds the pids of the
     * first {@code limit} rows (the last one possibly read only partly), each with its newest row.
     */
    public List<PidSn> mongoPage(final String lowerBound, final boolean ageFilter, final int limit) {
        final List<PidSn> page = new ArrayList<>();
        long rows = 0;
        for (int i = firstAbove(lowerBound); i < entries.size() && rows < limit; i++) {
            final Entry e = entries.get(i);
            if (!(ageFilter && e.freshOnly())) {
                page.add(new PidSn(e.pid(), e.latestSn()));
                rows += e.snapshotCount();
            }
        }
        return page;
    }

    private int firstAbove(final String lowerBound) {
        int lo = 0;
        int hi = entries.size();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (entries.get(mid).pid().compareTo(lowerBound) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
