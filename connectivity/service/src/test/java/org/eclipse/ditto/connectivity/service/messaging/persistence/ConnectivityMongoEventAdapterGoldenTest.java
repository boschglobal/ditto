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
package org.eclipse.ditto.connectivity.service.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.persistence.journal.Tagged;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.bson.BsonDocument;
import org.eclipse.ditto.base.model.auth.AuthorizationContext;
import org.eclipse.ditto.base.model.auth.AuthorizationSubject;
import org.eclipse.ditto.base.model.auth.DittoAuthorizationContextType;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectionId;
import org.eclipse.ditto.connectivity.model.ConnectionType;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.ConnectivityStatus;
import org.eclipse.ditto.connectivity.model.Topic;
import org.eclipse.ditto.connectivity.model.signals.events.ConnectionCreated;
import org.eclipse.ditto.internal.utils.persistence.mongo.adapters.ConnectivityMongoEventAdapter;
import org.junit.After;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Replays the encrypted connection journal row ({@code golden/journal/connection-mongo.json}) that today's
 * {@code ConnectivityMongoEventAdapter} wrote before the serializer split, with the key it was written with
 * and after a key rotation that keeps it as the old key. The write payload is not compared: the AES-GCM IV is random.
 */
public final class ConnectivityMongoEventAdapterGoldenTest {

    private static final String MANIFEST = "connectivity.events:connectionCreated";
    private static final Set<String> TAGS = Set.of("always-alive", "priority-3");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-19T00:00:00Z");
    private static final String PLAIN_URI = "amqps://user:golden-secret@golden-host:5671";

    private final List<ActorSystem> systems = new ArrayList<>();

    @After
    public void shutdownSystems() {
        systems.forEach(TestKit::shutdownActorSystem);
    }

    @Test
    public void goldenRowDecryptsWithTheKeyItWasWrittenWith() throws IOException {
        assertReplaysToPlaintext(adapter(ConnectivityEventSerializerTest.K_OLD, null));
    }

    @Test
    public void goldenRowDecryptsWithItsKeyAsTheOldKeyAfterARotation() throws IOException {
        assertReplaysToPlaintext(
                adapter(ConnectivityEventSerializerTest.K_NEW, ConnectivityEventSerializerTest.K_OLD));
    }

    @Test
    public void writeKeepsManifestAndTags() {
        final ConnectivityMongoEventAdapter underTest = adapter(ConnectivityEventSerializerTest.K_OLD, null);
        final ConnectionCreated written = ConnectionCreated.of(connection(), 1L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-connection").journalTags(TAGS).build(), null);

        assertThat(underTest.manifest(written)).isEqualTo(MANIFEST);
        final Tagged tagged = (Tagged) underTest.toJournal(written);
        assertThat(CollectionConverters.asJava(tagged.tags())).isEqualTo(TAGS);
    }

    private static void assertReplaysToPlaintext(final ConnectivityMongoEventAdapter underTest) throws IOException {
        final String text = readFixture();
        assertThat(text).doesNotContain("golden-secret");
        // recovery resets the revision to the default and restores only the persisted historical headers
        final ConnectionCreated recovered = ConnectionCreated.of(connection(), 0L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-connection").build(), null);

        final List<Object> events =
                CollectionConverters.asJava(underTest.fromJournal(BsonDocument.parse(text), MANIFEST).events());

        assertThat(events).containsExactly(recovered);
        assertThat(((ConnectionCreated) events.get(0)).getConnection().getUri()).isEqualTo(PLAIN_URI);
    }

    private ConnectivityMongoEventAdapter adapter(final String key, @Nullable final String oldKey) {
        final ActorSystem system = ActorSystem.create("ConnectivityMongoEventAdapterGoldenTest",
                ConfigFactory.parseString(ConnectivityEventSerializerTest.encryptionBlock(true, key, oldKey))
                        .withFallback(ConfigFactory.load("test")));
        systems.add(system);
        return new ConnectivityMongoEventAdapter((ExtendedActorSystem) system);
    }

    private static Connection connection() {
        return ConnectivityModelFactory.newConnectionBuilder(ConnectionId.of("golden-connection"),
                        ConnectionType.AMQP_10, ConnectivityStatus.CLOSED, PLAIN_URI)
                .targets(List.of(ConnectivityModelFactory.newTargetBuilder()
                        .address("golden/target")
                        .authorizationContext(AuthorizationContext.newInstance(
                                DittoAuthorizationContextType.PRE_AUTHENTICATED_CONNECTION,
                                AuthorizationSubject.newInstance("golden:subject")))
                        .topics(Topic.TWIN_EVENTS)
                        .build()))
                .build();
    }

    private static String readFixture() throws IOException {
        try (final InputStream in = Objects.requireNonNull(ConnectivityMongoEventAdapterGoldenTest.class
                .getResourceAsStream("/golden/journal/connection-mongo.json"), "connection-mongo.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
