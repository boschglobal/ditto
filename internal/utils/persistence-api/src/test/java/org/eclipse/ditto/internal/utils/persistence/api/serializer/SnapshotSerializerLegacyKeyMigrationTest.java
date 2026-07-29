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
package org.eclipse.ditto.internal.utils.persistence.api.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.function.Predicate;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.json.Jsonifiable;
import org.eclipse.ditto.base.model.json.JsonSchemaVersion;
import org.eclipse.ditto.internal.utils.config.DittoConfigError;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Regression guard for the renamed {@code ditto.extensions.snapshot-adapter} &rarr; {@code snapshot-serializer}
 * extension key: a deployment that still overrides the OLD key must fail fast at boot with a precise migration
 * message instead of silently falling back to the default serializer (which would be a snapshot-format mismatch
 * risk once a service actually persists snapshots).
 */
public final class SnapshotSerializerLegacyKeyMigrationTest {

    private static ActorSystem actorSystem;

    @BeforeClass
    public static void setUp() {
        actorSystem = ActorSystem.create("SnapshotSerializerLegacyKeyMigrationTest");
    }

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(actorSystem);
    }

    @Test
    public void legacySnapshotAdapterKeyFailsFastWithMigrationMessage() {
        final Config dittoExtensionsConfig = ConfigFactory.parseString(
                "snapshot-adapter = \"com.example.LegacyAdapter\"\n"
                        + "snapshot-serializer { extension-class = \"" + StubSnapshotSerializer.class.getName()
                        + "\" }");

        assertThatThrownBy(() -> SnapshotSerializer.get(actorSystem, dittoExtensionsConfig))
                .isInstanceOf(DittoConfigError.class)
                .hasMessageContaining("snapshot-adapter")
                .hasMessageContaining("snapshot-serializer");
    }

    @Test
    public void configWithoutLegacyKeyResolvesNormally() {
        final Config dittoExtensionsConfig = ConfigFactory.parseString(
                "snapshot-serializer { extension-class = \"" + StubSnapshotSerializer.class.getName() + "\" }");

        final SnapshotSerializer<?> serializer = SnapshotSerializer.get(actorSystem, dittoExtensionsConfig);

        assertThat(serializer).isInstanceOf(StubSnapshotSerializer.class);
    }

    /**
     * Minimal, otherwise-inert {@link SnapshotSerializer} used only as the {@code extension-class} fixture value for
     * this test. Loaded reflectively by the Ditto extension mechanism via its {@code (ActorSystem, Config)}
     * constructor.
     */
    public static final class StubSnapshotSerializer extends SnapshotSerializer<StubJsonifiable> {

        @SuppressWarnings("unused")
        public StubSnapshotSerializer(final ActorSystem actorSystem, final Config config) {
            super(LoggerFactory.getLogger(StubSnapshotSerializer.class));
        }

        @Override
        protected boolean isDeleted(final StubJsonifiable snapshotEntity) {
            return false;
        }

        @Override
        protected JsonField getDeletedLifecycleJsonField() {
            return JsonField.newInstance("__lifecycle", JsonObject.empty());
        }

        @Override
        protected Optional<JsonField> getRevisionJsonField(final StubJsonifiable entity) {
            return Optional.empty();
        }

        @Override
        protected StubJsonifiable createJsonifiableFrom(final JsonObject jsonObject) {
            return new StubJsonifiable();
        }
    }

    /** Trivial {@link Jsonifiable.WithFieldSelectorAndPredicate} implementor to satisfy the serializer's type bound. */
    public static final class StubJsonifiable implements Jsonifiable.WithFieldSelectorAndPredicate<JsonField> {

        @Override
        public JsonObject toJson() {
            return JsonObject.empty();
        }

        @Override
        public JsonObject toJson(final JsonSchemaVersion schemaVersion, final Predicate<JsonField> predicate) {
            return JsonObject.empty();
        }

        @Override
        public JsonObject toJson(final JsonSchemaVersion schemaVersion, final JsonFieldSelector fieldSelector) {
            return JsonObject.empty();
        }
    }
}
