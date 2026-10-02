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

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.Test;

public final class PersistenceShapesTest {

    private static final Instant NOW = Instant.parse("2026-10-02T09:15:30Z");

    @Test
    public void snapshotObjectIdCarriesTheSnapshotWriteTimeAndIsUniquePerPidAndSn() {
        final long ts = Instant.parse("2026-01-01T00:08:20Z").toEpochMilli();
        final ObjectId id = PersistenceShapes.snapshotObjectId(123_456L, 500L, ts);

        assertThat(id.getDate()).isEqualTo(Date.from(Instant.ofEpochMilli(ts)));
        assertThat(PersistenceShapes.snapshotObjectId(123_456L, 500L, ts)).isEqualTo(id);
        final Set<ObjectId> ids = new HashSet<>();
        for (long pidIndex = 0; pidIndex < 200; pidIndex++) {
            for (long sn = 500; sn <= 5_000; sn += 500) {
                ids.add(PersistenceShapes.snapshotObjectId(pidIndex, sn, ts));
            }
        }
        assertThat(ids).hasSize(200 * 10);
    }

    @Test
    public void snapshotObjectIdOrdersAgainstTheMinAgeCutoffBySecond() {
        // MongoReadJournal: _id < ObjectId.getSmallestWithDate(now - minAge) <=> written before the cutoff second
        final Instant cutoff = NOW.minus(Duration.ofDays(3));
        final ObjectId smallest = ObjectId.getSmallestWithDate(Date.from(cutoff));
        assertThat(PersistenceShapes.snapshotObjectId(9L, 500L, cutoff.minusSeconds(1).toEpochMilli()))
                .isLessThan(smallest);
        assertThat(PersistenceShapes.snapshotObjectId(9L, 500L, cutoff.toEpochMilli()))
                .isGreaterThanOrEqualTo(smallest);
    }

    @Test
    public void zeroMinAgeIsTheExistingCleanupPipeline() {
        for (final String lowerBound : List.of("", "thing:bench.ns01:pid-00000042")) {
            assertThat(render(PersistenceShapes.newestSnapshotsPipeline(lowerBound, 100, Duration.ZERO, NOW)))
                    .isEqualTo(render(PersistenceShapes.newestSnapshotsPipeline(lowerBound, 100)));
        }
    }

    @Test
    public void activeMinAgeAddsTheProductionIdBoundAndLeavesTheOtherStagesAlone() {
        final String lowerBound = "thing:bench.ns01:pid-00000042";
        final ObjectId cutoff = ObjectId.getSmallestWithDate(Date.from(NOW.minus(Duration.ofDays(3))));

        final List<BsonDocument> withAge =
                render(PersistenceShapes.newestSnapshotsPipeline(lowerBound, 100, Duration.ofDays(3), NOW));
        final List<BsonDocument> legacy = render(PersistenceShapes.newestSnapshotsPipeline(lowerBound, 100));

        assertThat(withAge.get(0)).isEqualTo(BsonDocument.parse("{\"$match\": {\"$and\": ["
                + "{\"pid\": {\"$gt\": \"" + lowerBound + "\"}}, "
                + "{\"_id\": {\"$lt\": {\"$oid\": \"" + cutoff.toHexString() + "\"}}}]}}"));
        assertThat(withAge.subList(1, withAge.size())).isEqualTo(legacy.subList(1, legacy.size()));

        final List<BsonDocument> firstPage =
                render(PersistenceShapes.newestSnapshotsPipeline("", 100, Duration.ofDays(3), NOW));
        assertThat(firstPage.get(0)).isEqualTo(BsonDocument.parse("{\"$match\": {\"$and\": [{}, "
                + "{\"_id\": {\"$lt\": {\"$oid\": \"" + cutoff.toHexString() + "\"}}}]}}"));
    }

    private static List<BsonDocument> render(final List<Bson> pipeline) {
        return pipeline.stream().map(Bson::toBsonDocument).toList();
    }
}
