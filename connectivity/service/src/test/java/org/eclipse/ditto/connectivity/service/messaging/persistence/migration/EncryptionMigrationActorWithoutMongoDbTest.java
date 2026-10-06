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
package org.eclipse.ditto.connectivity.service.messaging.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Props;
import org.apache.pekko.actor.Status;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.connectivity.service.config.ConnectivityConfig;
import org.eclipse.ditto.connectivity.service.config.DittoConnectivityConfig;
import org.eclipse.ditto.connectivity.service.messaging.TestConstants;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mongodb.MongoTimeoutException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Characterises {@link EncryptionMigrationActor} when no MongoDB is reachable. The actor builds its client from the
 * config when it starts, so this also covers that path. Until the connectivity root actor gated the migration on the
 * persistence backend, this is what a deployment on another backend (PostgreSQL) got: the migration command is
 * accepted (202) before any MongoDB I/O, and its failure shows only in the log; a status query fails once the driver's
 * server selection times out. No Docker is needed: nothing listens on 127.0.0.1:1.
 */
public final class EncryptionMigrationActorWithoutMongoDbTest {

    private static final int SERVER_SELECTION_TIMEOUT_MS = 6_000;
    private static final String UNREACHABLE_MONGODB_URI = "mongodb://127.0.0.1:1/connectivity?serverSelectionTimeoutMS="
            + SERVER_SELECTION_TIMEOUT_MS + "&connectTimeoutMS=500";

    private static ActorSystem actorSystem;
    private static ConnectivityConfig connectivityConfig;

    @BeforeClass
    public static void setUp() {
        final Config config = ConfigFactory.parseString("""
                        ditto.mongodb.uri = "%s"
                        ditto.connectivity.connection.encryption {
                          encryption-enabled = true
                          symmetrical-key = "vJFSTPE9PO2BtZlcMAwNjs8jdFvQCk0Ya9MVdYjRJUU="
                        }
                        """.formatted(UNREACHABLE_MONGODB_URI))
                .withFallback(TestConstants.CONFIG)
                .resolve();
        connectivityConfig = DittoConnectivityConfig.of(DefaultScopedConfig.dittoScoped(config));
        actorSystem = ActorSystem.create("EncryptionMigrationActorWithoutMongoDbTest");
    }

    @AfterClass
    public static void tearDown() {
        if (actorSystem != null) {
            TestKit.shutdownActorSystem(actorSystem);
            actorSystem = null;
        }
    }

    @Test
    public void migrateEncryptionIsAcceptedBeforeAnyMongoDbIo() {
        new TestKit(actorSystem) {{
            final ActorRef underTest = actorSystem.actorOf(newProps());
            underTest.tell(MigrateConnectionEncryption.of(false, false, DittoHeaders.empty()), getRef());

            // well inside the server-selection timeout: the 202 does not wait for MongoDB
            final MigrateConnectionEncryptionResponse response =
                    expectMsgClass(Duration.ofSeconds(3), MigrateConnectionEncryptionResponse.class);
            assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.ACCEPTED);
        }};
    }

    @Test
    public void statusFailsOnceTheServerSelectionTimesOut() {
        new TestKit(actorSystem) {{
            final ActorRef underTest = actorSystem.actorOf(newProps());
            underTest.tell(MigrateConnectionEncryptionStatus.of(DittoHeaders.empty()), getRef());

            final Status.Failure failure = expectMsgClass(Duration.ofSeconds(20), Status.Failure.class);
            assertThat(Stream.iterate(failure.cause(), Objects::nonNull, Throwable::getCause))
                    .anyMatch(MongoTimeoutException.class::isInstance);
        }};
    }

    private static Props newProps() {
        return EncryptionMigrationActor.props(connectivityConfig);
    }
}
