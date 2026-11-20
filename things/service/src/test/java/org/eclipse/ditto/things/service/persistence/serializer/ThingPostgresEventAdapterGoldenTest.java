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
package org.eclipse.ditto.things.service.persistence.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.persistence.journal.Tagged;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.adapters.ThingPostgresEventAdapter;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.things.model.Attributes;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingLifecycle;
import org.eclipse.ditto.things.model.signals.events.ThingCreated;
import org.junit.AfterClass;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Replays the PostgreSQL journal row {@code ThingPostgresEventAdapter} wrote before the serializer split
 * ({@code golden/journal/thing-postgres.json}, a byte-identical copy of the persistence-r2dbc fixture) through the real
 * {@link ThingPostgresEventAdapter}, which delegates to this module's {@code ThingEventSerializer}, and pins the write
 * path to the same manifest, tags and payload.
 */
public final class ThingPostgresEventAdapterGoldenTest {

    private static final String MANIFEST = "things.events:thingCreated";
    private static final Set<String> TAGS = Set.of("always-alive", "priority-3");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-19T00:00:00Z");

    private static final ExtendedActorSystem SYSTEM = (ExtendedActorSystem) ActorSystem.create(
            "ThingPostgresEventAdapterGoldenTest", ConfigFactory.load("test"));

    private final ThingPostgresEventAdapter underTest = new ThingPostgresEventAdapter(SYSTEM);

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(SYSTEM);
    }

    @Test
    public void goldenRowWrittenBeforeTheSplitReplaysFromText() throws IOException {
        final String text = ThingMongoEventAdapterTest.readFixture("thing-postgres.json");

        assertThat(CollectionConverters.asJava(underTest.fromJournal(text, MANIFEST).events()))
                .containsExactly(recovered());
    }

    @Test
    public void goldenRowWrittenBeforeTheSplitReplaysFromBytes() throws IOException {
        final byte[] bytes =
                ThingMongoEventAdapterTest.readFixture("thing-postgres.json").getBytes(StandardCharsets.UTF_8);

        assertThat(CollectionConverters.asJava(underTest.fromJournal(bytes, MANIFEST).events()))
                .containsExactly(recovered());
    }

    @Test
    public void writeKeepsManifestTagsAndPayload() throws IOException {
        final String text = ThingMongoEventAdapterTest.readFixture("thing-postgres.json");
        final ThingCreated written = ThingCreated.of(thing(), 1L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-thing").journalTags(TAGS).build(), null);

        assertThat(underTest.manifest(written)).isEqualTo(MANIFEST);
        final Tagged tagged = (Tagged) underTest.toJournal(written);
        assertThat(CollectionConverters.asJava(tagged.tags())).isEqualTo(TAGS);
        assertThat(JsonFactory.newObject((String) tagged.payload())).isEqualTo(JsonFactory.newObject(text));
    }

    @Test
    public void manifestOfGoldenRowMatchesItsType() throws IOException {
        final String text = ThingMongoEventAdapterTest.readFixture("thing-postgres.json");

        assertThat(JsonFactory.newObject(text).getValue("type")).contains(JsonFactory.newValue(MANIFEST));
    }

    private static Thing thing() {
        return Thing.newBuilder()
                .setId(ThingId.of("golden:thing"))
                .setModified(TIMESTAMP)
                .setCreated(TIMESTAMP)
                .setLifecycle(ThingLifecycle.ACTIVE)
                .setRevision(1)
                .setPolicyId(PolicyId.of("golden:thing"))
                .setAttributes(Attributes.newBuilder().set("hello", "golden").build())
                .build();
    }

    private static ThingCreated recovered() {
        // recovery resets the revision to the default and restores only the persisted historical headers
        return ThingCreated.of(thing(), 0L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-thing").build(), null);
    }

}
