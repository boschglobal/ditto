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
package org.eclipse.ditto.internal.utils.persistence.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.bson.BsonValue;
import org.eclipse.ditto.internal.utils.persistence.api.serializer.SnapshotSerializer;
import org.eclipse.ditto.internal.utils.persistence.mongo.AbstractMongoSnapshotAdapter;
import org.eclipse.ditto.internal.utils.persistence.mongo.DittoBsonJson;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.eclipse.ditto.things.model.ThingsModelFactory;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import org.apache.pekko.persistence.SnapshotMetadata;
import org.apache.pekko.persistence.SnapshotOffer;

/**
 * Proves that the MongoDB snapshot adapter (BSON envelope) and the {@link PostgresSnapshotAdapter} (JSONB envelope)
 * share the same pure {@link SnapshotSerializer} and therefore round-trip a {@link Thing} to an identical
 * {@link JsonObject} with field-level equality, and that the JSONB text/bytes decode recovers the original domain
 * type.
 */
public final class SnapshotAdapterParityTest {

    /** Concrete Thing snapshot serializer mirroring ThingMongoSnapshotAdapter's pure logic (no pubsub side-effect). */
    private static final class ThingSnapshotSerializer extends AbstractMongoSnapshotAdapter<Thing> {

        private ThingSnapshotSerializer() {
            super(LoggerFactory.getLogger(ThingSnapshotSerializer.class));
        }

        @Override
        protected Thing createJsonifiableFrom(final JsonObject jsonObject) {
            return ThingsModelFactory.newThing(jsonObject);
        }

        @Override
        protected boolean isDeleted(final Thing snapshotEntity) {
            return snapshotEntity.hasLifecycle(ThingLifecycle.DELETED);
        }

        @Override
        protected JsonField getDeletedLifecycleJsonField() {
            final var field = Thing.JsonFields.LIFECYCLE;
            return JsonField.newInstance(field.getPointer().getRoot().orElseThrow(),
                    JsonValue.of(ThingLifecycle.DELETED.name()), field);
        }

        @Override
        protected Optional<JsonField> getRevisionJsonField(final Thing entity) {
            final var field = Thing.JsonFields.REVISION;
            return entity.getRevision().map(revision ->
                    JsonField.newInstance(field.getPointer().getRoot().orElseThrow(),
                            JsonValue.of(revision.toLong())));
        }
    }

    private static Thing sampleThing() {
        return ThingsModelFactory.newThingBuilder()
                .setId(ThingId.of("org.eclipse.ditto", "thing-parity"))
                .setAttribute(JsonFactory.newPointer("manufacturer"), JsonValue.of("Bosch"))
                .setAttribute(JsonFactory.newPointer("count"), JsonValue.of(42))
                .setFeature("sensor", ThingsModelFactory.newFeaturePropertiesBuilder()
                        .set("temperature", 21.5)
                        .set("active", true)
                        .build())
                .setRevision(7L)
                .setLifecycle(ThingLifecycle.ACTIVE)
                .build();
    }

    @Test
    public void mongoAndPostgresProduceIdenticalSnapshotJson() {
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        final PostgresSnapshotAdapter<Thing> postgres = new PostgresSnapshotAdapter<>(serializer);

        final Thing thing = sampleThing();

        // Mongo envelope: domain -> BSON -> JsonObject
        final BsonValue mongoStored = (BsonValue) serializer.toSnapshotStore(thing);
        final JsonObject mongoJson = DittoBsonJson.getInstance().serialize(mongoStored).asObject();

        // Postgres envelope: domain -> JSONB text -> JsonObject
        final String postgresStored = (String) postgres.toSnapshotStore(thing);
        final JsonObject postgresJson = JsonFactory.newObject(postgresStored);

        // Field-level parity through Ditto's JsonObject serializer (canonical JSON rendering). Raw value-type
        // identity is NOT asserted because BSON and JSONB normalize numbers differently ([LOW] caveat); the
        // contract is field-level equality through the serializer, i.e. identical canonical JSON.
        assertThat(postgresJson.toString()).isEqualTo(mongoJson.toString());
        assertThat(postgresJson.toString()).isEqualTo(serializer.toJson(thing).toString());
        // And both envelopes recover to the identical domain Thing.
        assertThat(postgres.fromSnapshotStore(snapshotOffer(postgresStored)))
                .isEqualTo(serializer.fromSnapshotStore(snapshotOffer(mongoStored)));
    }

    @Test
    public void postgresRecoversThingFromJsonbText() {
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        final PostgresSnapshotAdapter<Thing> postgres = new PostgresSnapshotAdapter<>(serializer);
        final Thing thing = sampleThing();

        final String jsonbText = (String) postgres.toSnapshotStore(thing);
        final Thing recovered = postgres.fromSnapshotStore(snapshotOffer(jsonbText));

        assertThat(recovered).isEqualTo(thing);
    }

    @Test
    public void postgresRecoversThingFromJsonbBytes() {
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        final PostgresSnapshotAdapter<Thing> postgres = new PostgresSnapshotAdapter<>(serializer);
        final Thing thing = sampleThing();

        final byte[] jsonbBytes = ((String) postgres.toSnapshotStore(thing)).getBytes(StandardCharsets.UTF_8);
        final Thing recovered = postgres.fromSnapshotStore(snapshotOffer(jsonbBytes));

        assertThat(recovered).isEqualTo(thing);
    }

    @Test
    public void postgresRecoversThingFromAlreadyParsedJsonObject() {
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        final PostgresSnapshotAdapter<Thing> postgres = new PostgresSnapshotAdapter<>(serializer);
        final Thing thing = sampleThing();

        final JsonObject json = serializer.toJson(thing);
        final Thing recovered = postgres.fromSnapshotStore(snapshotOffer(json));

        assertThat(recovered).isEqualTo(thing);
    }

    @Test
    public void deletedThingRoundTripsToNullOnBothEnvelopes() {
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        final PostgresSnapshotAdapter<Thing> postgres = new PostgresSnapshotAdapter<>(serializer);
        final Thing deleted = sampleThing().toBuilder().setLifecycle(ThingLifecycle.DELETED).build();

        final BsonValue mongoStored = (BsonValue) serializer.toSnapshotStore(deleted);
        final JsonObject mongoJson = DittoBsonJson.getInstance().serialize(mongoStored).asObject();
        final String postgresStored = (String) postgres.toSnapshotStore(deleted);

        assertThat(JsonFactory.newObject(postgresStored).toString()).isEqualTo(mongoJson.toString());
        // Both decode a deleted snapshot to null (entity is deleted).
        assertThat(postgres.fromSnapshotStore(snapshotOffer(postgresStored))).isNull();
        assertThat(serializer.fromSnapshotStore(snapshotOffer(mongoStored))).isNull();
    }

    @Test(expected = IllegalArgumentException.class)
    public void mongoAdapterRejectsJsonbStringPayload() {
        // AbstractMongoSnapshotAdapter requires a BsonValue and rejects JSONB text payloads, which is
        // exactly why a dedicated PostgresSnapshotAdapter is needed.
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        serializer.fromSnapshotStore(snapshotOffer(serializer.toJson(sampleThing()).toString()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void postgresAdapterRejectsBsonPayload() {
        final ThingSnapshotSerializer serializer = new ThingSnapshotSerializer();
        final PostgresSnapshotAdapter<Thing> postgres = new PostgresSnapshotAdapter<>(serializer);
        final BsonValue bson = (BsonValue) serializer.toSnapshotStore(sampleThing());
        postgres.fromSnapshotStore(snapshotOffer(bson));
    }

    private static SnapshotOffer snapshotOffer(final Object snapshot) {
        return new SnapshotOffer(new SnapshotMetadata("pid", 7L, 0L), snapshot);
    }

}
