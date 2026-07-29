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
package org.eclipse.ditto.internal.utils.persistence.serializer;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.eclipse.ditto.base.model.json.Jsonifiable;
import org.eclipse.ditto.internal.utils.persistence.api.serializer.SnapshotAdapter;
import org.eclipse.ditto.internal.utils.persistence.api.serializer.SnapshotSerializer;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;

import org.apache.pekko.persistence.SelectedSnapshot;
import org.apache.pekko.persistence.SnapshotOffer;

/**
 * PostgreSQL specific {@link SnapshotAdapter} that wraps a backend-neutral {@link SnapshotSerializer} with a JSONB
 * envelope.
 * <p>
 * The PostgreSQL snapshot store loads snapshots as JSONB, which the r2dbc driver surfaces as {@code String} (the JSONB
 * text), {@code byte[]} (UTF-8 JSONB bytes), or an already-parsed {@link JsonObject}. {@code AbstractMongoSnapshotAdapter}
 * rejects every one of these because it requires the loaded object to be a {@code org.bson.BsonValue} and would throw
 * an {@code IllegalArgumentException} on the first PostgreSQL snapshot recovery. This adapter instead decodes the JSONB
 * representation to a {@link JsonObject} and feeds the <em>same</em> {@link SnapshotSerializer} the MongoDB adapter
 * uses, so the domain&harr;JSON logic (deleted-lifecycle detection, revision handling, parsing) is shared and only the
 * storage envelope differs.
 *
 * @param <T> the jsonifiable type to snapshot.
 */
@ThreadSafe
public final class PostgresSnapshotAdapter<T extends Jsonifiable.WithFieldSelectorAndPredicate<JsonField>>
        implements SnapshotAdapter<T> {

    private final SnapshotSerializer<T> serializer;

    /**
     * Constructs a new {@code PostgresSnapshotAdapter} delegating to the given backend-neutral serializer.
     *
     * @param serializer the pure {@link JsonObject}&harr;domain serializer (also used by the MongoDB adapter).
     */
    public PostgresSnapshotAdapter(final SnapshotSerializer<T> serializer) {
        this.serializer = checkNotNull(serializer, "serializer");
    }

    @Override
    public Object toSnapshotStore(final T snapshot) {
        // Return the snapshot as JSON text: the serializer's JsonObject rendered to a String. The snapshot
        // store binds this text to the ::jsonb column parameter; this adapter only produces the payload.
        return serializer.toJson(checkNotNull(snapshot, "snapshot")).toString();
    }

    @Nullable
    @Override
    public T fromSnapshotStore(final SnapshotOffer snapshotOffer) {
        return convert(snapshotOffer.snapshot());
    }

    @Nullable
    @Override
    public T fromSnapshotStore(final SelectedSnapshot selectedSnapshot) {
        return convert(selectedSnapshot.snapshot());
    }

    @Nullable
    private T convert(final Object rawSnapshot) {
        return serializer.fromJson(decodeJsonb(rawSnapshot));
    }

    /**
     * Decodes the raw snapshot value as loaded from a JSONB column to a {@link JsonObject}. Accepts the shapes the
     * r2dbc PostgreSQL driver can surface for JSONB: a {@code JsonObject} (already parsed), a {@code String} (JSONB
     * text), or {@code byte[]} (UTF-8 JSONB bytes).
     *
     * @param rawSnapshot the raw loaded snapshot value.
     * @return the decoded JsonObject.
     * @throws NullPointerException if {@code rawSnapshot} is {@code null}.
     * @throws IllegalArgumentException if {@code rawSnapshot} is not a JSONB-decodable type.
     */
    static JsonObject decodeJsonb(final Object rawSnapshot) {
        checkNotNull(rawSnapshot, "raw snapshot");
        if (rawSnapshot instanceof JsonObject jsonObject) {
            return jsonObject;
        }
        if (rawSnapshot instanceof CharSequence charSequence) {
            return JsonFactory.newObject(charSequence.toString());
        }
        if (rawSnapshot instanceof byte[] bytes) {
            return JsonFactory.newObject(new String(bytes, StandardCharsets.UTF_8));
        }
        final String pattern =
                "Unable to create a Jsonifiable from <{0}>! Expected a JSONB-decodable value (JsonObject, String or byte[]).";
        throw new IllegalArgumentException(MessageFormat.format(pattern, rawSnapshot.getClass()));
    }

}
