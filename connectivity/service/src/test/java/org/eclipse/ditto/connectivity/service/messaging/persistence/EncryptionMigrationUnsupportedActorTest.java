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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.UnsupportedSignalException;
import org.eclipse.ditto.base.model.signals.commands.Command;
import org.eclipse.ditto.connectivity.service.messaging.persistence.migration.MigrateConnectionEncryption;
import org.eclipse.ditto.connectivity.service.messaging.persistence.migration.MigrateConnectionEncryptionAbort;
import org.eclipse.ditto.connectivity.service.messaging.persistence.migration.MigrateConnectionEncryptionStatus;
import org.eclipse.ditto.internal.utils.persistence.api.BackendFamily;
import org.eclipse.ditto.json.JsonObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests {@link EncryptionMigrationUnsupportedActor}: on a non-MongoDB backend each encryption-migration command gets a
 * 501 {@code signal.unsupported} that says the migration is MongoDB-only and that the old key must stay configured.
 */
public final class EncryptionMigrationUnsupportedActorTest {

    private static final String EXPECTED_MESSAGE = "Encryption migration is only supported on the MongoDB " +
            "persistence backend, not on the active PostgreSQL backend. On PostgreSQL keep " +
            "'old-symmetrical-key' configured.";
    private static final String EXPECTED_DESCRIPTION = "Connection data encrypted with the old key is not " +
            "re-encrypted in bulk on this backend. Reads fall back to 'old-symmetrical-key', and new or changed " +
            "connection data is encrypted with 'symmetrical-key'. Removing the old key makes connections that " +
            "still hold data encrypted with it fail to recover.";

    private static ActorSystem actorSystem;

    @BeforeClass
    public static void setUp() {
        actorSystem = ActorSystem.create("EncryptionMigrationUnsupportedActorTest");
    }

    @AfterClass
    public static void tearDown() {
        if (actorSystem != null) {
            TestKit.shutdownActorSystem(actorSystem);
            actorSystem = null;
        }
    }

    @Test
    public void migrateEncryptionIsRefused() {
        assertRefused(MigrateConnectionEncryption.of(false, false, headers("migrate")));
    }

    @Test
    public void dryRunAndResumeAreRefusedToo() {
        assertRefused(MigrateConnectionEncryption.of(true, true, headers("migrate-dry-run-resume")));
    }

    @Test
    public void migrateEncryptionStatusIsRefused() {
        assertRefused(MigrateConnectionEncryptionStatus.of(headers("status")));
    }

    @Test
    public void migrateEncryptionAbortIsRefused() {
        assertRefused(MigrateConnectionEncryptionAbort.of(headers("abort")));
    }

    @Test
    public void otherMessagesGetNoReply() {
        new TestKit(actorSystem) {{
            final ActorRef underTest =
                    actorSystem.actorOf(EncryptionMigrationUnsupportedActor.props(BackendFamily.POSTGRESQL));
            underTest.tell("not a migration command", getRef());
            expectNoMessage(Duration.ofMillis(300));
        }};
    }

    @Test
    public void theRefusalKeepsStatusAndTextThroughJson() {
        // DevOpsCommandsActor forwards exception.toJson(): the 501 and the texts must survive it
        final UnsupportedSignalException refusal = EncryptionMigrationUnsupportedActor.refusal(
                BackendFamily.POSTGRESQL, MigrateConnectionEncryptionStatus.of(headers("json")));
        final JsonObject json = refusal.toJson();

        assertThat(json.getValue(DittoRuntimeException.JsonFields.STATUS)).contains(501);
        assertThat(json.getValue(DittoRuntimeException.JsonFields.ERROR_CODE))
                .contains(UnsupportedSignalException.ERROR_CODE);
        assertThat(json.getValue(DittoRuntimeException.JsonFields.MESSAGE)).contains(EXPECTED_MESSAGE);
        assertThat(json.getValue(DittoRuntimeException.JsonFields.DESCRIPTION)).contains(EXPECTED_DESCRIPTION);
        assertThat(UnsupportedSignalException.fromJson(json, DittoHeaders.empty()).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    public void refusingOnMongoDbIsAWiringError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EncryptionMigrationUnsupportedActor.props(BackendFamily.MONGODB));
    }

    @Test
    public void backendNamesAreHumanReadable() {
        assertThat(EncryptionMigrationUnsupportedActor.backendName(BackendFamily.MONGODB)).isEqualTo("MongoDB");
        assertThat(EncryptionMigrationUnsupportedActor.backendName(BackendFamily.POSTGRESQL)).isEqualTo("PostgreSQL");
    }

    private static void assertRefused(final Command<?> command) {
        new TestKit(actorSystem) {{
            final ActorRef underTest =
                    actorSystem.actorOf(EncryptionMigrationUnsupportedActor.props(BackendFamily.POSTGRESQL));
            underTest.tell(command, getRef());

            // a plain reply (not Status.Failure), so the DevOps piggyback keeps the 501 and the text
            final UnsupportedSignalException refusal = expectMsgClass(UnsupportedSignalException.class);
            assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
            assertThat(refusal.getErrorCode()).isEqualTo(UnsupportedSignalException.ERROR_CODE);
            assertThat(refusal.getMessage()).isEqualTo(EXPECTED_MESSAGE);
            assertThat(refusal.getDescription()).contains(EXPECTED_DESCRIPTION);
            assertThat(refusal.getDittoHeaders().getCorrelationId())
                    .isEqualTo(command.getDittoHeaders().getCorrelationId());
        }};
    }

    private static DittoHeaders headers(final String correlationId) {
        return DittoHeaders.newBuilder().correlationId(correlationId).build();
    }
}
