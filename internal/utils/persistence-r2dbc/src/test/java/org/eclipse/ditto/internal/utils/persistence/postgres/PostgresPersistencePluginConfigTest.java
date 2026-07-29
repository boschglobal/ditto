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
package org.eclipse.ditto.internal.utils.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.api.PersistencePluginConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit test for {@link PostgresPersistencePluginConfig} (task E2, mirroring the Mongo B1 test): per-entity IDs delegate
 * to the provider, the per-service auto-start IDs match the shipped Postgres profile, and the remember-store IDs are the
 * documented (ddata-inert) safe value. Runs offline against the real shipped {@code ditto-postgres-persistence.conf}.
 */
public final class PostgresPersistencePluginConfigTest {

    private static final Config PROFILE_EXTENSION_CONFIG =
            ConfigFactory.parseResources("ditto-postgres-persistence.conf")
                    .getConfig("ditto.extensions.persistence-backend-provider.extension-config");

    private static ActorSystem system;
    private static PersistencePluginConfig pluginConfig;

    @BeforeClass
    public static void setUp() {
        system = ActorSystem.create("PostgresPersistencePluginConfigTest",
                ConfigFactory.parseString("ditto.postgresql.ssl.mode = \"disable\"")
                        .withFallback(ConfigFactory.load()));
        final PostgresPersistenceBackendProvider provider =
                new PostgresPersistenceBackendProvider(system, PROFILE_EXTENSION_CONFIG);
        pluginConfig = provider.pluginConfig();
    }

    @AfterClass
    public static void tearDown() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
            system = null;
        }
    }

    @Test
    public void perEntityIdsDelegateToProvider() {
        assertThat(pluginConfig.getJournalPluginId("thing")).isEqualTo("ditto-postgres-things-journal");
        assertThat(pluginConfig.getSnapshotPluginId("thing")).isEqualTo("ditto-postgres-things-snapshots");
        assertThat(pluginConfig.getJournalPluginId("connection")).isEqualTo("ditto-postgres-connections-journal");
        assertThat(pluginConfig.getSnapshotPluginId("connection")).isEqualTo("ditto-postgres-connections-snapshots");
    }

    @Test
    public void autoStartIdsMatchShippedProfilePerService() {
        assertThat(pluginConfig.getAutoStartPluginIds("things"))
                .containsExactly("ditto-postgres-things-journal", "ditto-postgres-things-snapshots");
        assertThat(pluginConfig.getAutoStartPluginIds("policies"))
                .containsExactly("ditto-postgres-policies-journal", "ditto-postgres-policies-snapshots");
        assertThat(pluginConfig.getAutoStartPluginIds("connectivity"))
                .containsExactly("ditto-postgres-connections-journal", "ditto-postgres-connections-snapshots");
    }

    @Test
    public void autoStartIdsForUnknownServiceIsEmpty() {
        assertThat(pluginConfig.getAutoStartPluginIds("gateway")).isEmpty();
    }

    @Test
    public void rememberStoreIdsForConnectionAreSafeValidPluginIds() {
        // On Postgres the remember-entities store is ddata, so the IDs are inert; the connection's own real Postgres
        // plugin IDs are returned as a safe, resolvable value (documented decision).
        final PersistencePluginConfig.RememberStorePluginIds ids =
                pluginConfig.getRememberStorePluginIds("connection");

        assertThat(ids.journalPluginId()).isEqualTo("ditto-postgres-connections-journal");
        assertThat(ids.snapshotPluginId()).isEqualTo("ditto-postgres-connections-snapshots");
    }

    @Test
    public void rememberStoreIdsForUnknownEntityFailsClearly() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> pluginConfig.getRememberStorePluginIds("thing"))
                .withMessageContaining("thing");
    }
}
