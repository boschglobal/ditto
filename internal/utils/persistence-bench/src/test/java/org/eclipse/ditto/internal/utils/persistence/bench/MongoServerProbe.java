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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bson.Document;

import com.mongodb.client.MongoDatabase;

/**
 * Server-side evidence for a W3 MongoDB run: serverStatus deltas of the WiredTiger journal (log) syncs the
 * JOURNALED write concern waits for, checkpoints and cache I/O.
 */
final class MongoServerProbe {

    private static final Map<String, List<String>> SECTIONS = Map.of(
            "log", List.of("log sync operations", "log sync time duration (usecs)", "log flush operations",
                    "log write operations", "log bytes written", "log sync_dir operations"),
            "transaction", List.of("transaction checkpoints", "transaction checkpoint total time (msecs)"),
            "cache", List.of("pages read into cache", "pages written from cache"));

    private MongoServerProbe() {
    }

    static Map<String, Double> counters(final MongoDatabase db) {
        final Document status = db.runCommand(new Document("serverStatus", 1));
        final Map<String, Double> out = new TreeMap<>();
        if (status.get("wiredTiger") instanceof Document wiredTiger) {
            SECTIONS.forEach((section, keys) -> {
                if (wiredTiger.get(section) instanceof Document d) {
                    keys.forEach(key -> {
                        if (d.get(key) instanceof Number n) {
                            out.put("wiredTiger." + section + "." + key, n.doubleValue());
                        }
                    });
                }
            });
        }
        if (status.get("opcounters") instanceof Document opcounters) {
            for (final String key : List.of("insert", "update")) {
                if (opcounters.get(key) instanceof Number n) {
                    out.put("opcounters." + key, n.doubleValue());
                }
            }
        }
        return out;
    }

    static String deltaMarkdown(final Map<String, Double> before, final Map<String, Double> after, final long ops) {
        final List<List<String>> rows = new ArrayList<>();
        for (final Map.Entry<String, Double> e : after.entrySet()) {
            final double delta = e.getValue() - before.getOrDefault(e.getKey(), 0.0);
            rows.add(List.of(e.getKey(), String.format("%.0f", delta),
                    ops > 0 ? String.format("%.3f", delta / ops) : "—"));
        }
        return BenchProtocol.table(List.of("counter (serverStatus)", "delta over the run", "per op"), rows);
    }
}
