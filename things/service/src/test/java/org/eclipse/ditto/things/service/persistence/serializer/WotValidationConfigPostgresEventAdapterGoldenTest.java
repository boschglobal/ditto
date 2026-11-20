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
import java.util.Collections;
import java.util.Set;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.persistence.journal.Tagged;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.entity.metadata.MetadataModelFactory;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.adapters.WotValidationConfigPostgresEventAdapter;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.things.model.devops.FeatureValidationConfig;
import org.eclipse.ditto.things.model.devops.FeatureValidationEnforceConfig;
import org.eclipse.ditto.things.model.devops.FeatureValidationForbidConfig;
import org.eclipse.ditto.things.model.devops.ThingValidationConfig;
import org.eclipse.ditto.things.model.devops.ThingValidationEnforceConfig;
import org.eclipse.ditto.things.model.devops.ThingValidationForbidConfig;
import org.eclipse.ditto.things.model.devops.WotValidationConfig;
import org.eclipse.ditto.things.model.devops.WotValidationConfigId;
import org.eclipse.ditto.things.model.devops.WotValidationConfigRevision;
import org.eclipse.ditto.things.model.devops.events.WotValidationConfigCreated;
import org.junit.AfterClass;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Replays the PostgreSQL journal row {@code WotValidationConfigPostgresEventAdapter} wrote before the serializer split
 * ({@code golden/journal/wot-validation-config-postgres.json}, a byte-identical copy of the persistence-r2dbc fixture)
 * through the real {@link WotValidationConfigPostgresEventAdapter}, which delegates to this module's
 * {@code WotValidationConfigEventSerializer}, and pins the write path to the same manifest, tags and payload.
 */
public final class WotValidationConfigPostgresEventAdapterGoldenTest {

    private static final String MANIFEST = "wot.validation.config.events:wotValidationConfigCreated";
    private static final Set<String> TAGS = Set.of("wot-validation-config");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-19T00:00:00Z");
    private static final WotValidationConfigId CONFIG_ID = WotValidationConfigId.of("golden:wot");

    private static final ExtendedActorSystem SYSTEM = (ExtendedActorSystem) ActorSystem.create(
            "WotValidationConfigPostgresEventAdapterGoldenTest", ConfigFactory.load("test.conf"));

    private final WotValidationConfigPostgresEventAdapter underTest =
            new WotValidationConfigPostgresEventAdapter(SYSTEM);

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(SYSTEM);
    }

    @Test
    public void goldenRowWrittenBeforeTheSplitReplaysFromText() throws IOException {
        final String text = ThingMongoEventAdapterTest.readFixture("wot-validation-config-postgres.json");

        assertThat(CollectionConverters.asJava(underTest.fromJournal(text, MANIFEST).events()))
                .containsExactly(recovered());
    }

    @Test
    public void goldenRowWrittenBeforeTheSplitReplaysFromBytes() throws IOException {
        final byte[] bytes = ThingMongoEventAdapterTest.readFixture("wot-validation-config-postgres.json")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(CollectionConverters.asJava(underTest.fromJournal(bytes, MANIFEST).events()))
                .containsExactly(recovered());
    }

    @Test
    public void writeKeepsManifestTagsAndPayload() throws IOException {
        final String text = ThingMongoEventAdapterTest.readFixture("wot-validation-config-postgres.json");
        final WotValidationConfigCreated written = WotValidationConfigCreated.of(CONFIG_ID, config(), 1L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-wot")
                        .journalTags(Set.of("always-alive", "priority-3")).build(), null);

        assertThat(underTest.manifest(written)).isEqualTo(MANIFEST);
        final Tagged tagged = (Tagged) underTest.toJournal(written);
        assertThat(CollectionConverters.asJava(tagged.tags())).isEqualTo(TAGS);
        assertThat(JsonFactory.newObject((String) tagged.payload())).isEqualTo(JsonFactory.newObject(text));
    }

    @Test
    public void manifestOfGoldenRowMatchesItsType() throws IOException {
        final String text = ThingMongoEventAdapterTest.readFixture("wot-validation-config-postgres.json");

        assertThat(JsonFactory.newObject(text).getValue("type")).contains(JsonFactory.newValue(MANIFEST));
    }

    private static WotValidationConfig config() {
        return WotValidationConfig.of(CONFIG_ID, true, false,
                ThingValidationConfig.of(ThingValidationEnforceConfig.of(true, true, true, true, true),
                        ThingValidationForbidConfig.of(false, true, false, true)),
                FeatureValidationConfig.of(
                        FeatureValidationEnforceConfig.of(true, false, true, false, true, false, true),
                        FeatureValidationForbidConfig.of(false, true, false, true, false, true)),
                Collections.emptyList(), WotValidationConfigRevision.of(1L), TIMESTAMP, TIMESTAMP,
                false, null);
    }

    private static WotValidationConfigCreated recovered() {
        // recovery resets the revision to the default and restores only the persisted historical headers; the
        // stored "_metadata": null parses to null metadata, not to absent metadata
        return WotValidationConfigCreated.of(CONFIG_ID, config(), 0L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-wot").build(), MetadataModelFactory.nullMetadata());
    }

}
