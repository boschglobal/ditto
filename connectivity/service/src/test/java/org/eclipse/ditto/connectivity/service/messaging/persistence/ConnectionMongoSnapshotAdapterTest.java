/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
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

import java.net.URI;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.bson.BsonDocument;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectionLifecycle;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.service.config.DefaultFieldsEncryptionConfig;
import org.eclipse.ditto.connectivity.service.messaging.TestConstants;
import org.eclipse.ditto.internal.utils.persistence.api.serializer.NeutralSnapshotAdapter;
import org.eclipse.ditto.internal.utils.persistence.mongo.DittoBsonJson;
import org.eclipse.ditto.internal.utils.persistence.mongo.MongoSnapshotCodec;
import org.eclipse.ditto.json.JsonObject;
import org.junit.Test;

public class ConnectionMongoSnapshotAdapterTest {

    @Test
    public void makeEmptySnapshotsForDeletedConnections() {
        final Config config = ConfigFactory.load("connection-fields-encryption-test");
        final var serializer = new ConnectionMongoSnapshotAdapter(DefaultFieldsEncryptionConfig.of(config.getConfig("connection")));
        // The Mongo BSON envelope is now the injected snapshot codec; the serializer holds only the domain logic.
        final var underTest = new NeutralSnapshotAdapter<>(serializer, MongoSnapshotCodec.INSTANCE);
        final var deletedSnapshot = (BsonDocument) underTest.toSnapshotStore(
                TestConstants.createConnection().toBuilder().lifecycle(ConnectionLifecycle.DELETED).build());
        final JsonObject snapshotJson = DittoBsonJson.getInstance().serialize(deletedSnapshot);
        assertThat(snapshotJson).containsOnly(serializer.getDeletedLifecycleJsonField());
    }

    @Test
    public void encryptDecryptSnapshot() {
        final Config config = ConfigFactory.load("connection-fields-encryption-test");
        final ConnectionMongoSnapshotAdapter serializer = new ConnectionMongoSnapshotAdapter(DefaultFieldsEncryptionConfig.of(config.getConfig("connection")));
        final var underTest = new NeutralSnapshotAdapter<>(serializer, MongoSnapshotCodec.INSTANCE);
        final Connection connection = TestConstants.createConnection();
        final URI uri = URI.create(connection.getUri());
        final String userInfo = uri.getUserInfo();

        final BsonDocument snapshotConnection = (BsonDocument) underTest.toSnapshotStore(connection);
        final Connection encryptedConnection = ConnectivityModelFactory.connectionFromJson(JsonObject.of(snapshotConnection.toJson()));
        final Connection decryptedConnection = serializer.createJsonifiableFrom(JsonObject.of(snapshotConnection.toJson()));
        final String userInfoAfterDecrypt = URI.create(decryptedConnection.getUri()).getUserInfo();

        assertThat(encryptedConnection.getUri()).contains("encrypted_");
        assertThat(userInfoAfterDecrypt).isEqualTo(userInfo);
    }
}
