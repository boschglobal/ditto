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
package org.eclipse.ditto.connectivity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.eclipse.ditto.connectivity.service.ConnectivityRootActor.EncryptionMigrationStart;
import org.eclipse.ditto.connectivity.service.config.DefaultFieldsEncryptionConfig;
import org.eclipse.ditto.connectivity.service.config.FieldsEncryptionConfig;
import org.eclipse.ditto.internal.utils.persistence.api.BackendFamily;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Tests what {@link ConnectivityRootActor} starts for the connection encryption migration: the MongoDB migration
 * singleton only on MongoDB, the refusing responder (and no MongoDB client) on any other backend, nothing when
 * encryption is off and no old key is set; and when it warns at boot.
 */
public final class ConnectivityRootActorEncryptionMigrationTest {

    private static final String KEY = "vJFSTPE9PO2BtZlcMAwNjs8jdFvQCk0Ya9MVdYjRJUU=";
    private static final String OTHER_KEY = "anotherBase64Key256bitsLongForAESGCM32Bytes=";

    @Test
    public void mongoDbWithEncryptionEnabledStartsTheMigrationSingleton() {
        assertThat(ConnectivityRootActor.encryptionMigrationStart(BackendFamily.MONGODB, encryption(true, KEY, "")))
                .isEqualTo(EncryptionMigrationStart.MONGODB_SINGLETON);
    }

    @Test
    public void mongoDbWithOnlyAnOldKeyStartsTheMigrationSingleton() {
        // the documented "disable encryption" set-up
        assertThat(ConnectivityRootActor.encryptionMigrationStart(BackendFamily.MONGODB, encryption(false, "", KEY)))
                .isEqualTo(EncryptionMigrationStart.MONGODB_SINGLETON);
    }

    @Test
    public void postgreSqlWithEncryptionEnabledStartsTheRefusingResponder() {
        assertThat(ConnectivityRootActor.encryptionMigrationStart(BackendFamily.POSTGRESQL,
                encryption(true, OTHER_KEY, KEY)))
                .isEqualTo(EncryptionMigrationStart.UNSUPPORTED_RESPONDER);
        assertThat(ConnectivityRootActor.encryptionMigrationStart(BackendFamily.POSTGRESQL,
                encryption(true, KEY, "")))
                .isEqualTo(EncryptionMigrationStart.UNSUPPORTED_RESPONDER);
    }

    @Test
    public void postgreSqlWithOnlyAnOldKeyStartsTheRefusingResponder() {
        assertThat(ConnectivityRootActor.encryptionMigrationStart(BackendFamily.POSTGRESQL,
                encryption(false, "", KEY)))
                .isEqualTo(EncryptionMigrationStart.UNSUPPORTED_RESPONDER);
    }

    @Test
    public void withoutEncryptionAndOldKeyNothingStartsOnAnyBackend() {
        for (final BackendFamily backendFamily : BackendFamily.values()) {
            assertThat(ConnectivityRootActor.encryptionMigrationStart(backendFamily, encryption(false, "", "")))
                    .as(backendFamily.name())
                    .isEqualTo(EncryptionMigrationStart.NONE);
        }
    }

    @Test
    public void postgreSqlWithAnOldKeyWarnsAtBoot() {
        assertThat(ConnectivityRootActor.encryptionMigrationOldKeyWarning(BackendFamily.POSTGRESQL,
                encryption(true, OTHER_KEY, KEY)))
                .contains("Connection encryption: 'old-symmetrical-key' is set, but encryption migration is only " +
                        "supported on the MongoDB persistence backend, not on the active PostgreSQL backend. Keep " +
                        "'old-symmetrical-key' configured: connection data encrypted with it is not re-encrypted " +
                        "in bulk and still needs it to be read.");
        assertThat(ConnectivityRootActor.encryptionMigrationOldKeyWarning(BackendFamily.POSTGRESQL,
                encryption(false, "", KEY)))
                .isPresent();
    }

    @Test
    public void noBootWarningWithoutAnOldKeyOrOnMongoDb() {
        assertThat(ConnectivityRootActor.encryptionMigrationOldKeyWarning(BackendFamily.POSTGRESQL,
                encryption(true, KEY, "")))
                .isEmpty();
        assertThat(ConnectivityRootActor.encryptionMigrationOldKeyWarning(BackendFamily.MONGODB,
                encryption(true, OTHER_KEY, KEY)))
                .isEmpty();
    }

    private static FieldsEncryptionConfig encryption(final boolean enabled, final String key, final String oldKey) {
        return DefaultFieldsEncryptionConfig.of(ConfigFactory.parseMap(Map.of(
                "encryption.encryption-enabled", enabled,
                "encryption.symmetrical-key", key,
                "encryption.old-symmetrical-key", oldKey)));
    }
}
