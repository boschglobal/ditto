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

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.Props;
import org.apache.pekko.japi.pf.ReceiveBuilder;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.signals.UnsupportedSignalException;
import org.eclipse.ditto.base.model.signals.commands.Command;
import org.eclipse.ditto.connectivity.service.messaging.persistence.migration.MigrateConnectionEncryption;
import org.eclipse.ditto.connectivity.service.messaging.persistence.migration.MigrateConnectionEncryptionAbort;
import org.eclipse.ditto.connectivity.service.messaging.persistence.migration.MigrateConnectionEncryptionStatus;
import org.eclipse.ditto.internal.utils.pekko.logging.DittoDiagnosticLoggingAdapter;
import org.eclipse.ditto.internal.utils.pekko.logging.DittoLoggerFactory;
import org.eclipse.ditto.internal.utils.persistence.api.BackendFamily;

/**
 * Answers the connection encryption-migration DevOps commands ({@code migrateEncryption},
 * {@code migrateEncryptionStatus}, {@code migrateEncryptionAbort}) on persistence backends other than MongoDB.
 * <p>
 * The migration ({@code EncryptionMigrationActor}) reads and rewrites the MongoDB collections of the connection journal
 * and snapshot store. On another backend it would work on a MongoDB that does not hold the connections, report success
 * and invite the operator to remove the old key that the stored connections still need. The connectivity root actor
 * therefore starts this actor instead, under the same name, and builds no MongoDB client. Every command is answered
 * with an {@link UnsupportedSignalException} (status 501), sent as a plain reply so that the DevOps piggyback passes
 * its status and text on to the caller (a {@code Status.Failure} would arrive as a 503 ask error).
 * <p>
 * The actor is a local child of the root actor on every connectivity node, not a cluster singleton: each node answers
 * the commands it receives.
 */
public final class EncryptionMigrationUnsupportedActor extends AbstractActor {

    private final DittoDiagnosticLoggingAdapter log = DittoLoggerFactory.getDiagnosticLoggingAdapter(this);

    private final BackendFamily backendFamily;

    @SuppressWarnings("unused")
    private EncryptionMigrationUnsupportedActor(final BackendFamily backendFamily) {
        this.backendFamily = backendFamily;
    }

    /**
     * Creates the Props of this actor.
     *
     * @param backendFamily the active persistence backend, which the refusal names.
     * @return the Props.
     * @throws NullPointerException if {@code backendFamily} is {@code null}.
     * @throws IllegalArgumentException if {@code backendFamily} is MongoDB, where the migration is supported.
     */
    public static Props props(final BackendFamily backendFamily) {
        checkNotNull(backendFamily, "backendFamily");
        if (backendFamily == BackendFamily.MONGODB) {
            throw new IllegalArgumentException("The encryption migration is supported on MongoDB and must not be " +
                    "refused there.");
        }
        return Props.create(EncryptionMigrationUnsupportedActor.class, backendFamily);
    }

    /**
     * Returns the name of a persistence backend as the refusal and the connectivity boot warning show it.
     *
     * @param backendFamily the backend family.
     * @return its human-readable name.
     */
    public static String backendName(final BackendFamily backendFamily) {
        return switch (backendFamily) {
            case MONGODB -> "MongoDB";
            case POSTGRESQL -> "PostgreSQL";
        };
    }

    /**
     * Builds the refusal of an encryption-migration command.
     *
     * @param backendFamily the active (non-MongoDB) persistence backend.
     * @param command the refused command; its headers are kept.
     * @return the exception to reply with.
     */
    static UnsupportedSignalException refusal(final BackendFamily backendFamily, final Command<?> command) {
        final String backend = backendName(backendFamily);
        return UnsupportedSignalException.newBuilder(command.getType())
                .httpStatus(HttpStatus.NOT_IMPLEMENTED) // must come first: the inherited setters return the base type
                .message("Encryption migration is only supported on the MongoDB persistence backend, not on the " +
                        "active " + backend + " backend. On " + backend + " keep 'old-symmetrical-key' configured.")
                .description("Connection data encrypted with the old key is not re-encrypted in bulk on this " +
                        "backend. Reads fall back to 'old-symmetrical-key', and new or changed connection data is " +
                        "encrypted with 'symmetrical-key'. Removing the old key makes connections that still hold " +
                        "data encrypted with it fail to recover.")
                .dittoHeaders(command.getDittoHeaders())
                .build();
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(MigrateConnectionEncryption.class, this::refuse)
                .match(MigrateConnectionEncryptionStatus.class, this::refuse)
                .match(MigrateConnectionEncryptionAbort.class, this::refuse)
                .build();
    }

    private void refuse(final Command<?> command) {
        final UnsupportedSignalException refusal = refusal(backendFamily, command);
        log.withCorrelationId(command).warning("Refused <{}>: {}", command.getType(), refusal.getMessage());
        getSender().tell(refusal, getSelf());
    }

}
