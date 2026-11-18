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
package org.eclipse.ditto.policies.service.persistence.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.persistence.journal.Tagged;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.bson.BsonDocument;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.mongo.adapters.DefaultPolicyMongoEventAdapter;
import org.eclipse.ditto.policies.model.PoliciesModelFactory;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.policies.model.PolicyLifecycle;
import org.eclipse.ditto.policies.model.SubjectIssuer;
import org.eclipse.ditto.policies.model.signals.events.PolicyCreated;
import org.junit.AfterClass;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Replays the policy journal documents written before the serializer split through the moved
 * {@link DefaultPolicyMongoEventAdapter}: a current {@code PolicyCreated} and a hand-written row of the removed legacy
 * type {@code policies:importsAliasCreated}.
 */
public final class PolicyMongoEventAdapterGoldenTest {

    private static final String MANIFEST = "policies.events:policyCreated";
    private static final String LEGACY_MANIFEST = "policies:importsAliasCreated";
    private static final Set<String> TAGS = Set.of("always-alive", "priority-3");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-19T00:00:00Z");

    private static final ExtendedActorSystem SYSTEM = (ExtendedActorSystem) ActorSystem.create(
            "PolicyMongoEventAdapterGoldenTest", ConfigFactory.load("test"));

    private final DefaultPolicyMongoEventAdapter underTest = new DefaultPolicyMongoEventAdapter(SYSTEM);

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(SYSTEM);
    }

    @Test
    public void currentGoldenDocumentReplaysAndIsWrittenUnchanged() throws IOException {
        final BsonDocument golden = BsonDocument.parse(readFixture("policy-mongo.json"));
        final Policy policy = PoliciesModelFactory.newPolicyBuilder(PolicyId.of("golden:policy"))
                .forLabel("DEFAULT")
                .setSubject(SubjectIssuer.GOOGLE, "golden-subject")
                .setGrantedPermissions("thing", "/", "READ", "WRITE")
                .setGrantedPermissions("policy", "/", "READ", "WRITE")
                .setLifecycle(PolicyLifecycle.ACTIVE)
                .setRevision(1L)
                .setModified(TIMESTAMP)
                .setCreated(TIMESTAMP)
                .build();
        final PolicyCreated written = PolicyCreated.of(policy, 1L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-policy").journalTags(TAGS).build(), null);
        // recovery resets the revision to the default and restores only the persisted historical headers
        final PolicyCreated recovered = PolicyCreated.of(policy, 0L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-policy").build(), null);

        assertThat(CollectionConverters.asJava(underTest.fromJournal(golden, MANIFEST).events()))
                .containsExactly(recovered);
        assertThat(underTest.manifest(written)).isEqualTo(MANIFEST);
        final Tagged tagged = (Tagged) underTest.toJournal(written);
        assertThat(CollectionConverters.asJava(tagged.tags())).isEqualTo(TAGS);
        assertThat(tagged.payload()).isEqualTo(golden);
    }

    @Test
    public void legacyGoldenDocumentIsDiscardedWithOneInfoLine() throws IOException {
        final BsonDocument legacy = BsonDocument.parse(readFixture("policy-legacy-importsAliasCreated-mongo.json"));
        final PolicyEventSerializerTest.LogCapture logCapture = new PolicyEventSerializerTest.LogCapture();
        logCapture.start();
        try {
            assertThat(CollectionConverters.asJava(underTest.fromJournal(legacy, LEGACY_MANIFEST).events()))
                    .isEmpty();
            logCapture.assertSingleDiscardLineFor(LEGACY_MANIFEST);
        } finally {
            logCapture.stop();
        }
    }

    private static String readFixture(final String name) throws IOException {
        try (final InputStream in = Objects.requireNonNull(
                PolicyMongoEventAdapterGoldenTest.class.getResourceAsStream("/golden/journal/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
