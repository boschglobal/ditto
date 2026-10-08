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

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.policies.model.PoliciesModelFactory;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.thingsearch.persistence.api.model.ThingWriteModel;
import org.eclipse.ditto.thingsearch.service.persistence.write.mapping.EnforcedThingMapper;

/**
 * The write-soak twin: a deterministic, text-heavy thing per corpus index and revision, with its enforcing policy,
 * mapped to the backend-neutral write model by the production mapper ({@link EnforcedThingMapper#toWriteModel}, the
 * call chain of the search updater stream).
 * <p>
 * Revision 1 is the loaded state. Every later revision rewrites exactly one of two hot groups of five leaves
 * (revision {@code r} rewrites group {@code r % 2}) plus {@code _revision} and {@code _modified}; every other leaf is
 * a function of the index alone. So the shipped anti-join deletes and inserts the same 12 or 13 flat rows on every
 * update, about 60 % of them text rows ({@code sf_text}, {@code sf_trgm}). Group 1 holds a two-element array, so
 * {@code ord = 1} rows (the partial index {@code sf_multi}) are rewritten too. One thing in ten carries three
 * 500-600-character texts, which pushes its document row into TOAST.
 * </p>
 */
final class SoakTwin {

    static final long POLICY_REVISION = 1L;
    static final int LONG_TEXT_PERCENT = 10;
    static final int LONG_TEXT_MIN = 500;
    static final int LONG_TEXT_MAX = 600;

    static final Set<String> HOT_GROUP_0 = Set.of(
            "/features/env/properties/status",
            "/features/env/properties/message",
            "/features/connectivity/properties/ip",
            "/features/env/properties/temperature",
            "/features/env/properties/alarm");
    static final Set<String> HOT_GROUP_1 = Set.of(
            "/features/connectivity/properties/lastSeen",
            "/features/env/properties/recentEvents",
            "/attributes/description",
            "/features/connectivity/properties/rssi",
            "/features/connectivity/properties/online");
    static final Set<String> SYSTEM_PATHS_CHANGED_PER_UPDATE = Set.of("/_revision", "/_modified");

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final int NAMESPACES = 20;
    private static final int POLICIES_PER_NAMESPACE = 50;
    private static final int MAX_ARRAY_SIZE = -1;   // the shipped default: index every array
    private static final String[] CITIES = {"Stuttgart", "Berlin", "Munich", "Hamburg", "Cologne", "Frankfurt",
            "Dresden", "Leipzig", "Bremen", "Hanover"};
    private static final String[] MANUFACTURERS = {"Acme Sensors", "Contoso Devices", "Initech", "Globex",
            "Umbrella Instruments", "Stark Industrial", "Wayne Automation", "Hooli Things", "Vandelay Controls",
            "Soylent Metering", "Tyrell Systems", "Cyberdyne Field"};
    private static final String[] STATUSES = {"ok", "degraded", "warning", "error", "maintenance", "offline"};
    private static final String[] CHANNELS = {"stable", "beta", "nightly", "lts"};
    private static final String[] UNITS = {"C", "%", "hPa", "lux", "ppm", "kWh", "V", "A"};
    private static final String[] EVENTS = {"door-open", "door-closed", "power-loss", "power-restored",
            "threshold-exceeded", "threshold-cleared", "reboot", "config-changed", "firmware-updated", "tamper"};
    private static final String[] TAGS = {"rooftop", "basement", "outdoor", "indoor", "critical", "spare",
            "pilot", "legacy", "battery", "mains", "wired", "wireless", "north", "south", "east", "west", "lab",
            "plant", "office", "warehouse", "cold-chain", "hvac", "lighting", "metering", "security", "fleet",
            "retrofit", "prototype", "certified", "monitored"};
    private static final String[] SYLLABLES = {"ka", "lo", "mi", "ne", "ru", "sa", "ti", "vo", "ber", "con", "dor",
            "fel", "gan", "hir", "jas", "kel", "lum", "mor", "nix", "pal", "quo", "rin", "sel", "tor", "ump", "val",
            "wen", "xer", "yal", "zor", "an", "el"};
    private static final String[] WORDS = vocabulary();

    private final long seed;
    private final ConcurrentHashMap<String, Policy> policies = new ConcurrentHashMap<>();

    SoakTwin(final long seed) {
        this.seed = seed;
    }

    long seed() {
        return seed;
    }

    static String namespace(final long index) {
        return "org.eclipse.ditto.soak.ns" + (index % NAMESPACES);
    }

    static String thingId(final long index) {
        return namespace(index) + ":soak-" + index;
    }

    static String policyId(final long index) {
        return namespace(index) + ":policy-" + ((index / NAMESPACES) % POLICIES_PER_NAMESPACE);
    }

    static Instant modifiedAt(final long index, final int revision) {
        return BASE_INSTANT.plusMillis(index).plusSeconds(revision);
    }

    /** @return {@code true} for the flat-row paths an update may rewrite (both hot groups, the two system fields). */
    static boolean isRewrittenOnUpdate(final String path) {
        return HOT_GROUP_0.contains(path) || HOT_GROUP_1.contains(path)
                || SYSTEM_PATHS_CHANGED_PER_UPDATE.contains(path);
    }

    /** @return the write model the search updater would produce for this thing at this revision. */
    ThingWriteModel writeModel(final long index, final int revision) {
        return EnforcedThingMapper.toWriteModel(thing(index, revision), policy(index), Set.of(), POLICY_REVISION, null,
                MAX_ARRAY_SIZE);
    }

    Policy policy(final long index) {
        return policies.computeIfAbsent(policyId(index), SoakTwin::newPolicy);
    }

    /** @return the thing JSON as the things service hands it to the search updater (regular and special fields). */
    JsonObject thing(final long index, final int revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1 (the loaded state), got " + revision);
        }
        // group 0 was last rewritten at the largest even revision <= this one, group 1 at the largest odd one;
        // the loaded revision 1 shows both groups at touch 0.
        final long touch0 = revision / 2;
        final long touch1 = (revision - 1) / 2;
        final SplittableRandom rng = new SplittableRandom(mix(seed, index));   // static leaves, fixed draw order

        final JsonObjectBuilder attributes = JsonObject.newBuilder()
                .set("manufacturer", pick(rng, MANUFACTURERS))
                .set("model", "model-" + (1 + rng.nextInt(400)))
                .set("serial", String.format(Locale.ROOT, "SN-%016x", rng.nextLong()))
                .set("installedYear", 2010 + rng.nextInt(16))
                .set("ratedPowerKw", (50 + rng.nextInt(24_950)) / 100.0)
                .set("active", rng.nextInt(10) != 0)
                .set("tags", JsonArray.newBuilder()
                        .add(TAGS[rng.nextInt(10)], TAGS[10 + rng.nextInt(10)], TAGS[20 + rng.nextInt(10)])
                        .build())
                .set("location", JsonObject.newBuilder()
                        .set("city", pick(rng, CITIES))
                        .set("street", capitalize(phrase(rng, 1, 2)) + " Street " + (1 + rng.nextInt(300)))
                        .set("zip", String.format(Locale.ROOT, "%05d", rng.nextInt(100_000)))
                        .build());
        if (rng.nextInt(100) < LONG_TEXT_PERCENT) {
            attributes.set("notes", longText(rng))
                    .set("maintenanceLog", longText(rng))
                    .set("installationNotes", longText(rng));
        }
        final double humidity = (2_000 + rng.nextInt(6_000)) / 100.0;
        final String ssid = "wlan-" + pick(rng, SYLLABLES) + "-" + rng.nextInt(1_000);
        final String version = (1 + rng.nextInt(4)) + "." + rng.nextInt(20) + "." + rng.nextInt(50);
        final String channel = pick(rng, CHANNELS);
        final String lastCheck = BASE_INSTANT.minusSeconds(rng.nextInt(2_592_000)).toString();
        final boolean updateAvailable = rng.nextInt(4) == 0;
        final double sensorValue = rng.nextInt(100_000) / 100.0;
        final String unit = pick(rng, UNITS);
        final String label = capitalize(phrase(rng, 2, 4));
        final int statusBase = rng.nextInt(STATUSES.length);
        final int eventBase = rng.nextInt(EVENTS.length);
        final double temperatureBase = (-500 + rng.nextInt(3_500)) / 100.0;

        // hot values: a function of (index, group, touch); consecutive touches always differ
        final SplittableRandom hot0 = new SplittableRandom(mix(mix(seed, index), touch0 << 1));
        final SplittableRandom hot1 = new SplittableRandom(mix(mix(seed, index), (touch1 << 1) | 1L));
        attributes.set("description", capitalize(phrase(hot1, 6, 16)) + " (" + touch1 + ")");

        final JsonObject features = JsonObject.newBuilder()
                .set("env", properties(JsonObject.newBuilder()
                        .set("temperature", Math.round((temperatureBase + ((touch0 % 160) - 80) * 0.25) * 100) / 100.0)
                        .set("humidity", humidity)
                        .set("status", STATUSES[(int) ((statusBase + touch0) % STATUSES.length)])
                        .set("message", phrase(hot0, 3, 8) + " #" + touch0)
                        .set("alarm", touch0 % 2 == 1)
                        .set("recentEvents", JsonArray.newBuilder()
                                .add(EVENTS[(int) ((eventBase + touch1) % EVENTS.length)],
                                        EVENTS[(int) ((eventBase + touch1 + EVENTS.length - 1) % EVENTS.length)])
                                .build())))
                .set("connectivity", properties(JsonObject.newBuilder()
                        .set("ssid", ssid)
                        .set("ip", "10." + ((index >>> 16) & 255) + "." + ((index >>> 8) & 255) + "."
                                + (1 + touch0 % 250))
                        .set("rssi", -40 - (int) (touch1 % 50))
                        .set("online", touch1 % 2 == 0)
                        .set("lastSeen", BASE_INSTANT.plusSeconds(index % 86_400 + 60 * touch1).toString())))
                .set("firmware", properties(JsonObject.newBuilder()
                        .set("version", version)
                        .set("channel", channel)
                        .set("lastCheck", lastCheck)
                        .set("updateAvailable", updateAvailable)))
                .set("sensor", properties(JsonObject.newBuilder()
                        .set("value", sensorValue)
                        .set("unit", unit)
                        .set("label", label)))
                .build();

        return JsonObject.newBuilder()
                .set("thingId", thingId(index))
                .set("policyId", policyId(index))
                .set("_namespace", namespace(index))
                .set("_revision", revision)
                .set("_created", BASE_INSTANT.plusMillis(index).toString())
                .set("_modified", modifiedAt(index, revision).toString())
                .set("attributes", attributes.build())
                .set("features", features)
                .build();
    }

    private static JsonObject properties(final JsonObjectBuilder properties) {
        return JsonObject.newBuilder().set("properties", properties.build()).build();
    }

    private static Policy newPolicy(final String policyId) {
        final int group = Math.floorMod(policyId.hashCode(), 64);
        return PoliciesModelFactory.newPolicy("""
                {
                  "policyId": "%s",
                  "entries": {
                    "OWNER": {
                      "subjects": { "integration:soak-owner": { "type": "generated" } },
                      "resources": {
                        "policy:/": { "grant": ["READ", "WRITE"], "revoke": [] },
                        "thing:/": { "grant": ["READ", "WRITE"], "revoke": [] }
                      }
                    },
                    "AUDITOR": {
                      "subjects": { "soak:auditor-%d": { "type": "generated" } },
                      "resources": {
                        "thing:/": { "grant": ["READ"], "revoke": [] },
                        "thing:/attributes/serial": { "grant": [], "revoke": ["READ"] }
                      }
                    },
                    "OPERATOR": {
                      "subjects": { "soak:operator-%d": { "type": "generated" } },
                      "resources": {
                        "thing:/features": { "grant": ["READ"], "revoke": [] },
                        "thing:/attributes/location": { "grant": ["READ"], "revoke": [] }
                      }
                    }
                  }
                }
                """.formatted(policyId, group % 8, group));
    }

    private static String pick(final SplittableRandom rng, final String[] values) {
        return values[rng.nextInt(values.length)];
    }

    private static String phrase(final SplittableRandom rng, final int minWords, final int maxWords) {
        final int words = minWords + rng.nextInt(maxWords - minWords + 1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(WORDS[rng.nextInt(WORDS.length)]);
        }
        return sb.toString();
    }

    private static String longText(final SplittableRandom rng) {
        final int length = LONG_TEXT_MIN + rng.nextInt(LONG_TEXT_MAX - LONG_TEXT_MIN + 1);
        final StringBuilder sb = new StringBuilder(length + 16);
        while (sb.length() < length) {
            sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
        }
        sb.setLength(length);
        return sb.toString();
    }

    private static String capitalize(final String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String[] vocabulary() {
        final String[] words = new String[4_096];
        for (int i = 0; i < words.length; i++) {
            final int third = i >> 10;
            words[i] = SYLLABLES[i & 31] + SYLLABLES[(i >> 5) & 31] + (third == 0 ? "" : SYLLABLES[third]);
        }
        return words;
    }

    /** splitmix64 finaliser over two inputs. */
    private static long mix(final long a, final long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

}
