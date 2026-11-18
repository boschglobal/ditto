/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.things.service.persistence.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.bson.BsonDocument;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.mongo.adapters.ThingMongoEventAdapter;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Attributes;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.eclipse.ditto.things.model.signals.commands.modify.DeleteThing;
import org.eclipse.ditto.things.model.signals.events.ThingCreated;
import org.eclipse.ditto.things.model.signals.events.ThingDeleted;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.persistence.journal.EventSeq;
import org.apache.pekko.persistence.journal.Tagged;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Tests for {@link ThingMongoEventAdapter}.
 */
public final class ThingMongoEventAdapterTest {

    private static final String GOLDEN_MANIFEST = "things.events:thingCreated";
    private static final Set<String> GOLDEN_TAGS = Set.of("always-alive", "priority-3");
    private static final Instant GOLDEN_TIMESTAMP = Instant.parse("2026-11-19T00:00:00Z");

    private final ThingMongoEventAdapter underTest;

    public ThingMongoEventAdapterTest() {
        underTest = new ThingMongoEventAdapter(
                (ExtendedActorSystem) ActorSystem.create("test", ConfigFactory.load("test")));
    }

    @Test
    public void extractsTypeOfEventAsManifest() {
        final ThingDeleted event = ThingDeleted.of(ThingId.generateRandom(), 1L, null, DittoHeaders.empty(), null);
        assertThat(underTest.manifest(event)).isEqualTo(event.getType());
    }

    @Test
    public void manifestThrowsIllegalArgumentExceptionForNonEvents() {
        final DeleteThing nonEvent = DeleteThing.of(ThingId.generateRandom(), DittoHeaders.empty());
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> underTest.manifest(nonEvent));
    }

    @Test
    public void toJournalThrowsIllegalArgumentExceptionForNonEvents() {
        final DeleteThing nonEvent = DeleteThing.of(ThingId.generateRandom(), DittoHeaders.empty());
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> underTest.toJournal(nonEvent));
    }

    @Test
    public void toJournalReturnsBsonDocument() {
        final Thing thing = Thing.newBuilder()
                .setId(ThingId.of("pap.th.tMJyAjktUVP:YlmZXbTQ"))
                .setModified(Instant.parse("2021-02-24T14:17:37.581679843Z"))
                .setCreated(Instant.parse("2021-02-24T14:17:37.581679843Z"))
                .setLifecycle(ThingLifecycle.ACTIVE)
                .setRevision(1)
                .setPolicyId(PolicyId.of("pap.th.tMJyAjktUVP:YlmZXbTQ"))
                .setAttributes(Attributes.newBuilder().set("hello", "cloud").build())
                .build();
        final ThingCreated thingCreated =
                ThingCreated.of(thing, 1L, Instant.parse("2021-02-24T14:17:37.581679843Z"), DittoHeaders.empty(), null);

        final String journalEntry = "{\n" +
                "                \"type\" : \"things.events:thingCreated\",\n" +
                "                \"_timestamp\" : \"2021-02-24T14:17:37.581679843Z\",\n" +
                "                \"revision\" : 1,\n" +
                "                \"_metadata\" : null,\n" +
                "                \"thingId\" : \"pap.th.tMJyAjktUVP:YlmZXbTQ\",\n" +
                "                \"thing\" : {\n" +
                "                    \"__lifecycle\" : \"ACTIVE\",\n" +
                "                    \"_revision\" : 1,\n" +
                "                    \"_modified\" : \"2021-02-24T14:17:37.581679843Z\",\n" +
                "                    \"_created\" : \"2021-02-24T14:17:37.581679843Z\",\n" +
                "                    \"_namespace\" : \"pap.th.tMJyAjktUVP\",\n" +
                "                    \"thingId\" : \"pap.th.tMJyAjktUVP:YlmZXbTQ\",\n" +
                "                    \"policyId\" : \"pap.th.tMJyAjktUVP:YlmZXbTQ\",\n" +
                "                    \"attributes\" : {\n" +
                "                        \"hello\" : \"cloud\"\n" +
                "                    }\n" +
                "                },\n" +
                "                \"__hh\": {}\n" +
                "            }";
        final BsonDocument bsonEvent = BsonDocument.parse(journalEntry);
        final Tagged tagged = new Tagged(bsonEvent, Set.of());

        assertThat(underTest.toJournal(thingCreated)).isEqualTo(tagged);
    }

    @Test
    public void fromJournalThrowsIllegalArgumentExceptionForNonBsonValues() {
        final DeleteThing nonEvent = DeleteThing.of(ThingId.generateRandom(), DittoHeaders.empty());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> underTest.fromJournal(nonEvent, DeleteThing.TYPE));
    }

    @Test
    public void fromJournalReturnsEvent() {
        final String journalEntry = "{\n" +
                "                \"type\" : \"things.events:thingCreated\",\n" +
                "                \"_timestamp\" : \"2021-02-24T14:17:37.581679843Z\",\n" +
                "                \"_metadata\" : null,\n" +
                "                \"thingId\" : \"pap.th.tMJyAjktUVP:YlmZXbTQ\",\n" +
                "                \"thing\" : {\n" +
                "                    \"__lifecycle\" : \"ACTIVE\",\n" +
                "                    \"_revision\" : 1,\n" +
                "                    \"_modified\" : \"2021-02-24T14:17:37.581679843Z\",\n" +
                "                    \"_created\" : \"2021-02-24T14:17:37.581679843Z\",\n" +
                "                    \"_namespace\" : \"pap.th.tMJyAjktUVP\",\n" +
                "                    \"thingId\" : \"pap.th.tMJyAjktUVP:YlmZXbTQ\",\n" +
                "                    \"policyId\" : \"pap.th.tMJyAjktUVP:YlmZXbTQ\",\n" +
                "                    \"attributes\" : {\n" +
                "                        \"hello\" : \"cloud\"\n" +
                "                    }\n" +
                "                }\n" +
                "            }";
        final BsonDocument bsonEvent = BsonDocument.parse(journalEntry);

        final Thing thing = Thing.newBuilder()
                .setId(ThingId.of("pap.th.tMJyAjktUVP:YlmZXbTQ"))
                .setModified(Instant.parse("2021-02-24T14:17:37.581679843Z"))
                .setCreated(Instant.parse("2021-02-24T14:17:37.581679843Z"))
                .setLifecycle(ThingLifecycle.ACTIVE)
                .setRevision(1)
                .setPolicyId(PolicyId.of("pap.th.tMJyAjktUVP:YlmZXbTQ"))
                .setAttributes(Attributes.newBuilder().set("hello", "cloud").build())
                .build();
        final ThingCreated thingCreated =
                ThingCreated.of(thing, 0, Instant.parse("2021-02-24T14:17:37.581679843Z"), DittoHeaders.empty(), null);
        final EventSeq eventSeq = underTest.fromJournal(bsonEvent, "things.events:thingCreated");
        final List<Object> objects = CollectionConverters.asJava(eventSeq.events());
        assertThat(objects).containsExactly(thingCreated);
    }

    /**
     * Replays the journal document {@code ThingMongoEventAdapter} wrote before the serializer split
     * ({@code golden/journal/thing-mongo.json}) and pins the write path to the same document, manifest and tags.
     */
    @Test
    public void goldenJournalDocumentWrittenBeforeTheSplitReplaysAndIsWrittenUnchanged() throws IOException {
        final BsonDocument golden = BsonDocument.parse(readFixture("thing-mongo.json"));
        final Thing thing = Thing.newBuilder()
                .setId(ThingId.of("golden:thing"))
                .setModified(GOLDEN_TIMESTAMP)
                .setCreated(GOLDEN_TIMESTAMP)
                .setLifecycle(ThingLifecycle.ACTIVE)
                .setRevision(1)
                .setPolicyId(PolicyId.of("golden:thing"))
                .setAttributes(Attributes.newBuilder().set("hello", "golden").build())
                .build();
        final ThingCreated written = ThingCreated.of(thing, 1L, GOLDEN_TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-thing").journalTags(GOLDEN_TAGS).build(), null);
        // recovery resets the revision to the default and restores only the persisted historical headers
        final ThingCreated recovered = ThingCreated.of(thing, 0L, GOLDEN_TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-thing").build(), null);

        assertThat(CollectionConverters.asJava(underTest.fromJournal(golden, GOLDEN_MANIFEST).events()))
                .containsExactly(recovered);
        assertThat(underTest.manifest(written)).isEqualTo(GOLDEN_MANIFEST);
        final Tagged tagged = (Tagged) underTest.toJournal(written);
        assertThat(CollectionConverters.asJava(tagged.tags())).isEqualTo(GOLDEN_TAGS);
        assertThat(tagged.payload()).isEqualTo(golden);
    }

    static String readFixture(final String name) throws IOException {
        try (final InputStream in = Objects.requireNonNull(
                ThingMongoEventAdapterTest.class.getResourceAsStream("/golden/journal/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
