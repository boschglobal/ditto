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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.ditto.json.JsonObject;

/**
 * Deterministic things-shaped corpus. Every value derives from (seed, pidIndex[, sn]) through an
 * explicit splitmix64 finalizer, so two generators with equal parameters produce identical corpora
 * on any JVM. Depth distribution: piecewise log-uniform pinned at median 20 (u=0.5), p99 2000
 * (u=0.99), cap 50000; analytic mean ~139 events/pid. Snapshots every 500 events (things.conf
 * snapshot.threshold default).
 */
public final class CorpusGenerator {

    public static final int SNAPSHOT_EVERY = 500;
    public static final long DEPTH_CAP = 50_000L;
    public static final int NAMESPACE_POOL = 50;
    /** 2026-01-01T00:00:00Z — all corpus timestamps are BASE + sn seconds (in the past at run time). */
    public static final long BASE_TIMESTAMP_MILLIS = 1_767_225_600_000L;

    static final String MANIFEST_CREATED = "org.eclipse.ditto.things.model.signals.events.ThingCreated";
    static final String MANIFEST_ATTRIBUTE = "org.eclipse.ditto.things.model.signals.events.AttributeModified";
    static final String MANIFEST_FEATURE = "org.eclipse.ditto.things.model.signals.events.FeaturePropertyModified";

    public enum PidClass {
        MEDIAN(18L, 22L),
        P99(2_200L, 2_400L),
        OUTLIER(40_000L, DEPTH_CAP);

        final long minDepth;
        final long maxDepth;

        PidClass(final long minDepth, final long maxDepth) {
            this.minDepth = minDepth;
            this.maxDepth = maxDepth;
        }
    }

    public record EventRow(String pid, long sn, String manifest, String eventJson, long timestampMillis) {}

    public record SnapshotRow(String pid, long sn, String snapshotJson, long timestampMillis) {}

    private final long seed;
    private final long pidCount;
    private final double[] namespaceCumulative;
    private long cachedTotalEvents = -1L;
    private long cachedTotalSnapshots = -1L;

    public CorpusGenerator(final long seed, final long pidCount) {
        this.seed = seed;
        this.pidCount = pidCount;
        namespaceCumulative = new double[NAMESPACE_POOL];
        double h = 0.0;
        for (int k = 1; k <= NAMESPACE_POOL; k++) {
            h += 1.0 / k;
            namespaceCumulative[k - 1] = h;
        }
        for (int k = 0; k < NAMESPACE_POOL; k++) {
            namespaceCumulative[k] /= h;
        }
    }

    public long pidCount() {
        return pidCount;
    }

    // --- deterministic randomness -------------------------------------------------------------

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private double uniform(final long key, final long stream) {
        final long h = mix64(seed ^ mix64(key * 0x9E3779B97F4A7C15L + stream));
        return (h >>> 11) * 0x1.0p-53;
    }

    // --- depth --------------------------------------------------------------------------------

    public long depth(final long pidIndex) {
        return depthForU(uniform(pidIndex, 1L));
    }

    static long depthForU(final double u) {
        if (u < 0.50) {
            return logUniform(u / 0.50, 1L, 20L);
        }
        if (u < 0.95) {
            return logUniform((u - 0.50) / 0.45, 20L, 200L);
        }
        if (u < 0.99) {
            return logUniform((u - 0.95) / 0.04, 200L, 2_000L);
        }
        if (u < 0.999) {
            return logUniform((u - 0.99) / 0.009, 2_000L, 10_000L);
        }
        return logUniform((u - 0.999) / 0.001, 10_000L, DEPTH_CAP);
    }

    private static long logUniform(final double t, final long lo, final long hi) {
        final double v = Math.exp(Math.log((double) lo) + t * (Math.log((double) hi) - Math.log((double) lo)));
        return Math.min(hi, Math.max(lo, Math.round(v)));
    }

    // --- pid ----------------------------------------------------------------------------------

    public String pid(final long pidIndex) {
        return String.format("thing:bench.ns%02d:pid-%08d", namespaceIndex(pidIndex), pidIndex);
    }

    private int namespaceIndex(final long pidIndex) {
        final double u = uniform(pidIndex, 2L);
        for (int k = 0; k < NAMESPACE_POOL; k++) {
            if (u <= namespaceCumulative[k]) {
                return k + 1;
            }
        }
        return NAMESPACE_POOL;
    }

    // --- snapshots ----------------------------------------------------------------------------

    public long latestSnapshotSn(final long pidIndex) {
        return (depth(pidIndex) / SNAPSHOT_EVERY) * SNAPSHOT_EVERY;
    }

    public long snapshotCount(final long pidIndex) {
        return depth(pidIndex) / SNAPSHOT_EVERY;
    }

    public static long timestampMillisFor(final long sn) {
        return BASE_TIMESTAMP_MILLIS + sn * 1000L;
    }

    // --- event / snapshot streams ---------------------------------------------------------------

    public Iterator<EventRow> events(final long pidIndex) {
        final String pid = pid(pidIndex);
        final String thingId = pid.substring("thing:".length());
        final long depth = depth(pidIndex);
        return new Iterator<>() {
            private long sn = 1L;

            @Override
            public boolean hasNext() {
                return sn <= depth;
            }

            @Override
            public EventRow next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                final long currentSn = sn++;
                final long ts = timestampMillisFor(currentSn);
                final String manifest;
                final String type;
                final String path;
                if (currentSn == 1L) {
                    manifest = MANIFEST_CREATED;
                    type = "things.events:thingCreated";
                    path = "/";
                } else if (currentSn % 2 == 0) {
                    manifest = MANIFEST_ATTRIBUTE;
                    type = "things.events:attributeModified";
                    path = "/attributes/bench";
                } else {
                    manifest = MANIFEST_FEATURE;
                    type = "things.events:featurePropertyModified";
                    path = "/features/f0/properties/status";
                }
                final String json = JsonObject.newBuilder()
                        .set("type", type)
                        .set("thingId", thingId)
                        .set("revision", currentSn)
                        .set("path", path)
                        .set("value", padding(pidIndex, currentSn))
                        .set("_timestamp", Instant.ofEpochMilli(ts).toString())
                        .build()
                        .toString();
                return new EventRow(pid, currentSn, manifest, json, ts);
            }
        };
    }

    public Iterator<SnapshotRow> snapshots(final long pidIndex) {
        final String pid = pid(pidIndex);
        final String thingId = pid.substring("thing:".length());
        final long lastSn = latestSnapshotSn(pidIndex);
        final int targetBytes = 2_048 + (int) (uniform(pidIndex, 4L) * 8_192.0);
        return new Iterator<>() {
            private long sn = SNAPSHOT_EVERY;

            @Override
            public boolean hasNext() {
                return sn <= lastSn;
            }

            @Override
            public SnapshotRow next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                final long currentSn = sn;
                sn += SNAPSHOT_EVERY;
                final long ts = timestampMillisFor(currentSn);
                final int padLen = Math.max(256, targetBytes - 300);
                final String json = JsonObject.newBuilder()
                        .set("thingId", thingId)
                        .set("policyId", thingId)
                        .set("_revision", currentSn)
                        .set("_modified", Instant.ofEpochMilli(ts).toString())
                        .set("attributes", JsonObject.newBuilder()
                                .set("bench", randomAscii(mix64(seed ^ (pidIndex * 53L + currentSn)), padLen))
                                .build())
                        .set("features", JsonObject.newBuilder()
                                .set("f0", JsonObject.newBuilder()
                                        .set("properties", JsonObject.newBuilder()
                                                .set("status", "ok")
                                                .build())
                                        .build())
                                .build())
                        .build()
                        .toString();
                return new SnapshotRow(pid, currentSn, json, ts);
            }
        };
    }

    private String padding(final long pidIndex, final long sn) {
        final int size = 300 + (int) (uniform(pidIndex * 1_000_003L + sn, 3L) * 700.0);
        return randomAscii(mix64(seed ^ (pidIndex * 31L + sn)), size);
    }

    private static String randomAscii(final long initialState, final int size) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        final StringBuilder sb = new StringBuilder(size);
        long state = initialState;
        while (sb.length() < size) {
            state = mix64(state);
            long v = state;
            for (int i = 0; i < 10 && sb.length() < size; i++) {
                sb.append(alphabet.charAt((int) Long.remainderUnsigned(v, 36L)));
                v = Long.divideUnsigned(v, 36L);
            }
        }
        return sb.toString();
    }

    // --- corpus-wide scans ----------------------------------------------------------------------

    public long totalEvents() {
        if (cachedTotalEvents < 0) {
            long sum = 0;
            for (long i = 0; i < pidCount; i++) {
                sum += depth(i);
            }
            cachedTotalEvents = sum;
        }
        return cachedTotalEvents;
    }

    public long totalSnapshots() {
        if (cachedTotalSnapshots < 0) {
            long sum = 0;
            for (long i = 0; i < pidCount; i++) {
                sum += depth(i) / SNAPSHOT_EVERY;
            }
            cachedTotalSnapshots = sum;
        }
        return cachedTotalSnapshots;
    }

    /**
     * Scans pid indexes starting at {@code fromIndex} (wrapping around once) and returns up to
     * {@code n} indexes whose depth falls into the class's range. May return fewer at small corpus
     * sizes — callers must handle short lists.
     */
    public List<Long> findPidIndexes(final PidClass cls, final int n, final long fromIndex) {
        final List<Long> result = new ArrayList<>(n);
        for (long scanned = 0; scanned < pidCount && result.size() < n; scanned++) {
            final long i = (fromIndex + scanned) % pidCount;
            final long d = depth(i);
            if (d >= cls.minDepth && d <= cls.maxDepth) {
                result.add(i);
            }
        }
        return result;
    }
}
