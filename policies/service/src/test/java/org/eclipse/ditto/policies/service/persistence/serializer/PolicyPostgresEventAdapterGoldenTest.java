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
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.persistence.postgres.adapters.PolicyPostgresEventAdapter;
import org.eclipse.ditto.json.JsonFactory;
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
 * Replays the PostgreSQL journal row {@code PolicyPostgresEventAdapter} wrote before the serializer split
 * ({@code golden/journal/policy-postgres.json}, a byte-identical copy of the persistence-r2dbc fixture) through the
 * real {@link PolicyPostgresEventAdapter}, which delegates to this module's {@code PolicyEventSerializer}, and pins the
 * write path to the same manifest, tags and payload.
 */
public final class PolicyPostgresEventAdapterGoldenTest {

    private static final String MANIFEST = "policies.events:policyCreated";
    private static final Set<String> TAGS = Set.of("always-alive", "priority-3");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-19T00:00:00Z");

    private static final ExtendedActorSystem SYSTEM = (ExtendedActorSystem) ActorSystem.create(
            "PolicyPostgresEventAdapterGoldenTest", ConfigFactory.load("test"));

    private final PolicyPostgresEventAdapter underTest = new PolicyPostgresEventAdapter(SYSTEM);

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(SYSTEM);
    }

    @Test
    public void goldenRowWrittenBeforeTheSplitReplaysFromText() throws IOException {
        final String text = readFixture();

        assertThat(CollectionConverters.asJava(underTest.fromJournal(text, MANIFEST).events()))
                .containsExactly(recovered());
    }

    @Test
    public void goldenRowWrittenBeforeTheSplitReplaysFromBytes() throws IOException {
        final byte[] bytes = readFixture().getBytes(StandardCharsets.UTF_8);

        assertThat(CollectionConverters.asJava(underTest.fromJournal(bytes, MANIFEST).events()))
                .containsExactly(recovered());
    }

    @Test
    public void writeKeepsManifestTagsAndPayload() throws IOException {
        final String text = readFixture();
        final PolicyCreated written = PolicyCreated.of(policy(), 1L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-policy").journalTags(TAGS).build(), null);

        assertThat(underTest.manifest(written)).isEqualTo(MANIFEST);
        final Tagged tagged = (Tagged) underTest.toJournal(written);
        assertThat(CollectionConverters.asJava(tagged.tags())).isEqualTo(TAGS);
        assertThat(JsonFactory.newObject((String) tagged.payload())).isEqualTo(JsonFactory.newObject(text));
    }

    @Test
    public void manifestOfGoldenRowMatchesItsType() throws IOException {
        assertThat(JsonFactory.newObject(readFixture()).getValue("type")).contains(JsonFactory.newValue(MANIFEST));
    }

    private static Policy policy() {
        return PoliciesModelFactory.newPolicyBuilder(PolicyId.of("golden:policy"))
                .forLabel("DEFAULT")
                .setSubject(SubjectIssuer.GOOGLE, "golden-subject")
                .setGrantedPermissions("thing", "/", "READ", "WRITE")
                .setGrantedPermissions("policy", "/", "READ", "WRITE")
                .setLifecycle(PolicyLifecycle.ACTIVE)
                .setRevision(1L)
                .setModified(TIMESTAMP)
                .setCreated(TIMESTAMP)
                .build();
    }

    private static PolicyCreated recovered() {
        // recovery resets the revision to the default and restores only the persisted historical headers
        return PolicyCreated.of(policy(), 0L, TIMESTAMP,
                DittoHeaders.newBuilder().correlationId("golden-policy").build(), null);
    }

    private static String readFixture() throws IOException {
        try (final InputStream in = Objects.requireNonNull(PolicyPostgresEventAdapterGoldenTest.class
                .getResourceAsStream("/golden/journal/policy-postgres.json"), "policy-postgres.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
