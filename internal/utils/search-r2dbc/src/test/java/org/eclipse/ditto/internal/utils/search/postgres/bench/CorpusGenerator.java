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
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import javax.annotation.Nullable;

/**
 * Deterministic synthetic-twin corpus generator for the Phase-0 bench. Each thing is generated independently
 * from {@code (seed, index)} via {@link #generate(long, long)} — the same {@code (seed, index)} pair always
 * produces the byte-identical {@link ThingRecord}, which lets the loader stream the corpus twice (once per
 * table) without materializing it in memory.
 * <p>
 * Shape, per the bench plan:
 * <ul>
 *     <li>5-50 attributes, 1-20 features per thing (counts skewed toward the low end so the corpus stays
 *     within the flat-row budget at 1M scale, while still covering the full stated range).</li>
 *     <li>Mixed value types: strings, ints, doubles, booleans, nulls, 2-3 deep nested objects, arrays
 *     (including arrays of objects and, occasionally, arrays of arrays).</li>
 *     <li>Namespaces are Zipf-ish skewed over a pool of 50.</li>
 *     <li>Two "hot" paths are present in most things so read benchmarks have both selective and unselective
 *     predicates to hit: {@code /attributes/location/city} (string) and
 *     {@code /features/env/properties/temperature} (number).</li>
 *     <li>{@code global_read} draws 1-5 subjects from a pool of ~200; {@code policy_auth}/{@code features_auth}
 *     are small grant/revoke trees shaped like production policy imprints (root grant, occasional subtree
 *     revoke) — exact policy semantics are not benchmarked here.</li>
 * </ul>
 */
final class CorpusGenerator {

    private static final int NAMESPACE_POOL_SIZE = 50;
    private static final int SUBJECT_POOL_SIZE = 200;
    private static final int POLICY_POOL_SIZE = 5_000;

    private static final String[] NAMESPACES = buildNamespaces();
    private static final double[] NAMESPACE_ZIPF_CDF = buildZipfCdf(NAMESPACE_POOL_SIZE);

    private static final String[] CITIES = {
            "Stuttgart", "Berlin", "Munich", "Hamburg", "Cologne", "Frankfurt", "Leipzig", "Dresden", "Bonn",
            "Essen",
    };

    private static final String[] ATTRIBUTE_KEYS = {
            "model", "vendor", "serialNumber", "firmwareVersion", "installDate", "maintenanceWindow", "tags",
            "calibration", "networkConfig", "assetTag", "owner", "warrantyUntil", "color", "weightKg",
            "heightCm", "certified", "region", "buildingId", "floor", "room", "capacityLiters", "powerWatts",
            "protocol", "vendorContact", "lastServicedBy",
    };

    private CorpusGenerator() {
        throw new AssertionError("no instances");
    }

    static ThingRecord generate(final long index, final long seed) {
        final SplittableRandom rng = new SplittableRandom(mix(seed, index));

        final String namespace = pickNamespace(rng);
        final String thingId = namespace + ":thing-" + index;
        final long revision = 1 + rng.nextInt(500);
        final String policyId = namespace + ":policy-" + rng.nextInt(POLICY_POOL_SIZE);
        final long policyRev = 1 + rng.nextInt(50);
        final List<String> referencedPolicies = rng.nextInt(100) < 8
                ? sample(rng, 1 + rng.nextInt(3), POLICY_POOL_SIZE, CorpusGenerator::policyRef)
                : List.of();
        final List<String> globalRead = sample(rng, 1 + rng.nextInt(5), SUBJECT_POOL_SIZE, CorpusGenerator::subject);

        final Map<String, Object> thing = generateThing(rng);
        final Map<String, Object> policyAuth = buildAuthTree(rng);
        final Map<String, Object> featuresAuth = buildFeaturesAuth(rng, thing);

        final Instant tModified = Instant.now().minusSeconds(rng.nextInt(3600 * 24 * 400));
        final Instant deleteAt = rng.nextInt(100) < 2
                ? Instant.now().plusSeconds(rng.nextInt(3600 * 24 * 30))
                : null;

        return new ThingRecord(thingId, namespace, revision, policyId, policyRev,
                referencedPolicies.isEmpty() ? null : referencedPolicies, globalRead, thing, policyAuth,
                featuresAuth, tModified, deleteAt);
    }

    // --- thing payload -----------------------------------------------------------------------------------

    private static Map<String, Object> generateThing(final SplittableRandom rng) {
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", generateAttributes(rng));
        thing.put("features", generateFeatures(rng));
        return thing;
    }

    private static Map<String, Object> generateAttributes(final SplittableRandom rng) {
        final int count = skewedCount(rng, 5, 50, 4);
        final Map<String, Object> attributes = new LinkedHashMap<>();

        // Hot path: present in most things so read benchmarks have an unselective predicate to hit.
        if (rng.nextInt(100) < 90) {
            final Map<String, Object> location = new LinkedHashMap<>();
            location.put("city", CITIES[rng.nextInt(CITIES.length)]);
            if (rng.nextInt(100) < 50) {
                location.put("zip", String.valueOf(10_000 + rng.nextInt(89_999)));
            }
            attributes.put("location", location);
        }

        final Set<String> usedKeys = new LinkedHashSet<>(attributes.keySet());
        int longTailIndex = 0;
        while (attributes.size() < count) {
            final String key = nextAttributeKey(rng, usedKeys, longTailIndex++);
            usedKeys.add(key);
            attributes.put(key, randomValue(rng, 0));
        }
        return attributes;
    }

    private static Map<String, Object> generateFeatures(final SplittableRandom rng) {
        int count = skewedCount(rng, 1, 20, 5);
        final Map<String, Object> features = new LinkedHashMap<>();

        // Hot path: present in most things so read benchmarks have a selective numeric predicate to hit.
        final boolean forceEnv = rng.nextInt(100) < 90;
        if (forceEnv) {
            features.put("env", buildFeature(rng, true));
            count = Math.max(0, count - 1);
        }
        for (int i = 0; i < count; i++) {
            features.put("feature-" + i, buildFeature(rng, false));
        }
        return features;
    }

    private static Map<String, Object> buildFeature(final SplittableRandom rng, final boolean forceTemperature) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        if (forceTemperature) {
            properties.put("temperature", roundedDouble(rng, -20.0, 45.0));
        }
        final int propCount = skewedCount(rng, forceTemperature ? 1 : 2, 4, 2);
        for (int i = 0; i < propCount; i++) {
            properties.put("prop" + i, randomValue(rng, 1));
        }

        final Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("properties", properties);
        if (rng.nextInt(100) < 10) {
            final Map<String, Object> desired = new LinkedHashMap<>();
            final int desiredCount = skewedCount(rng, 1, 3, 2);
            for (int i = 0; i < desiredCount; i++) {
                desired.put("desired" + i, randomValue(rng, 1));
            }
            feature.put("desiredProperties", desired);
        }
        return feature;
    }

    /**
     * @param depth current nesting depth (0 = top-level attribute/property value); nesting is capped at depth
     * 2 so generated objects are at most 2-3 levels deep as required.
     */
    private static Object randomValue(final SplittableRandom rng, final int depth) {
        if (depth < 2) {
            final int roll = rng.nextInt(100);
            if (roll < 8) {
                return randomObject(rng, depth);
            } else if (roll < 15) {
                return randomArray(rng, depth);
            }
        }
        return randomScalar(rng);
    }

    private static Map<String, Object> randomObject(final SplittableRandom rng, final int depth) {
        final Map<String, Object> object = new LinkedHashMap<>();
        // Occasionally empty, to exercise the empty-object exists-row rule at scale.
        if (rng.nextInt(100) < 90) {
            final int children = 1 + rng.nextInt(3);
            for (int i = 0; i < children; i++) {
                object.put("f" + i, randomValue(rng, depth + 1));
            }
        }
        return object;
    }

    private static List<Object> randomArray(final SplittableRandom rng, final int depth) {
        // Occasionally empty, to exercise the empty-array stub-row rule at scale.
        if (rng.nextInt(100) < 10) {
            return List.of();
        }
        final int elements = 1 + rng.nextInt(5);
        final List<Object> array = new ArrayList<>(elements);
        final int shapeRoll = rng.nextInt(100);
        if (shapeRoll < 15 && depth < 2) {
            // Array of objects.
            for (int i = 0; i < elements; i++) {
                array.add(randomObject(rng, depth + 1));
            }
        } else if (shapeRoll < 25) {
            // Array of arrays — each inner array is a stub from the flattener's point of view regardless of
            // its own content, so keep it small and scalar-only.
            for (int i = 0; i < elements; i++) {
                final int innerSize = 1 + rng.nextInt(3);
                final List<Object> inner = new ArrayList<>(innerSize);
                for (int j = 0; j < innerSize; j++) {
                    inner.add(randomScalar(rng));
                }
                array.add(inner);
            }
        } else {
            // Array of scalars.
            for (int i = 0; i < elements; i++) {
                array.add(randomScalar(rng));
            }
        }
        return array;
    }

    @Nullable
    private static Object randomScalar(final SplittableRandom rng) {
        final int roll = rng.nextInt(100);
        if (roll < 35) {
            return "value-" + rng.nextInt(100_000);
        } else if (roll < 60) {
            return (long) rng.nextInt(100_000);
        } else if (roll < 75) {
            return roundedDouble(rng, -1_000.0, 1_000.0);
        } else if (roll < 90) {
            return rng.nextBoolean();
        } else {
            return null;
        }
    }

    private static BigDecimal roundedDouble(final SplittableRandom rng, final double min, final double max) {
        final double value = min + rng.nextDouble() * (max - min);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String nextAttributeKey(final SplittableRandom rng, final Set<String> usedKeys,
            final int longTailIndex) {
        // Mostly draw from the curated pool (realistic key names); fall back to a synthetic long-tail key once
        // the pool is exhausted or occasionally for variety.
        if (rng.nextInt(100) < 70) {
            final String candidate = ATTRIBUTE_KEYS[rng.nextInt(ATTRIBUTE_KEYS.length)];
            if (!usedKeys.contains(candidate)) {
                return candidate;
            }
        }
        return "attr-" + longTailIndex + "-" + rng.nextInt(1_000_000);
    }

    // --- auth trees ----------------------------------------------------------------------------------------

    private static Map<String, Object> buildAuthTree(final SplittableRandom rng) {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("·g", sample(rng, 1 + rng.nextInt(3), SUBJECT_POOL_SIZE, CorpusGenerator::subject));
        if (rng.nextInt(100) < 15) {
            root.put("·r", sample(rng, 1 + rng.nextInt(2), SUBJECT_POOL_SIZE, CorpusGenerator::subject));
        }
        if (rng.nextInt(100) < 30) {
            final Map<String, Object> subtree = new LinkedHashMap<>();
            subtree.put("·r", sample(rng, 1, SUBJECT_POOL_SIZE, CorpusGenerator::subject));
            root.put("attributes", subtree);
        }
        return root;
    }

    @Nullable
    private static Map<String, Object> buildFeaturesAuth(final SplittableRandom rng,
            final Map<String, Object> thing) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> features = (Map<String, Object>) thing.get("features");
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final String featureId : features.keySet()) {
            if (rng.nextInt(100) < 20) {
                result.put(featureId, buildAuthTree(rng));
            }
        }
        return result.isEmpty() ? null : result;
    }

    // --- pools + sampling helpers ----------------------------------------------------------------------------

    private static String[] buildNamespaces() {
        final String[] namespaces = new String[NAMESPACE_POOL_SIZE];
        for (int i = 0; i < NAMESPACE_POOL_SIZE; i++) {
            namespaces[i] = "org.eclipse.ditto.bench.ns" + i;
        }
        return namespaces;
    }

    /** Rank-based Zipf-ish weights (weight ∝ 1/rank), as a cumulative distribution for O(log n) sampling. */
    private static double[] buildZipfCdf(final int poolSize) {
        final double[] cdf = new double[poolSize];
        double cumulative = 0;
        for (int rank = 1; rank <= poolSize; rank++) {
            cumulative += 1.0 / rank;
            cdf[rank - 1] = cumulative;
        }
        for (int i = 0; i < poolSize; i++) {
            cdf[i] /= cumulative;
        }
        return cdf;
    }

    private static String pickNamespace(final SplittableRandom rng) {
        final double roll = rng.nextDouble();
        int lo = 0;
        int hi = NAMESPACE_ZIPF_CDF.length - 1;
        while (lo < hi) {
            final int mid = (lo + hi) / 2;
            if (NAMESPACE_ZIPF_CDF[mid] < roll) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return NAMESPACES[lo];
    }

    private static String subject(final int index) {
        return "user:sub-" + String.format("%03d", index);
    }

    private static String policyRef(final int index) {
        return "org.eclipse.ditto.bench.ns" + (index % NAMESPACE_POOL_SIZE) + ":policy-" + index;
    }

    private static List<String> sample(final SplittableRandom rng, final int count, final int poolSize,
            final java.util.function.IntFunction<String> render) {
        final Set<Integer> indices = new LinkedHashSet<>();
        final int bound = Math.min(count, poolSize);
        while (indices.size() < bound) {
            indices.add(rng.nextInt(poolSize));
        }
        final List<String> result = new ArrayList<>(indices.size());
        for (final int index : indices) {
            result.add(render.apply(index));
        }
        return result;
    }

    /** Deterministically mixes the corpus seed with a thing index into a single 64-bit RNG seed. */
    private static long mix(final long seed, final long index) {
        long h = seed ^ (index * 0x9E3779B97F4A7C15L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** Skews a uniform draw toward {@code min} via {@code min + (max-min+1) * U^pow}, capped at {@code max}. */
    private static int skewedCount(final SplittableRandom rng, final int min, final int max, final int pow) {
        final double u = Math.pow(rng.nextDouble(), pow);
        final int value = min + (int) ((max - min + 1) * u);
        return Math.min(value, max);
    }

}
