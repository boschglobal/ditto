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
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.FlatRow;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.policies.api.PolicyTag;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.thingsearch.persistence.api.model.SearchIndexDocument;
import org.eclipse.ditto.things.model.ThingId;
import org.junit.Test;

/**
 * Unit test of the COPY lines for shipped search documents and shipped flat rows: one tab-separated field per
 * {@code search_things} / {@code search_flat} column, as {@code PostgresSearchUpdaterFlow} binds them.
 */
public final class CopyTextFormatTest {

    @Test
    public void aDocumentLineCarriesTheTwelveColumnsTheShippedWriterBinds() {
        final ThingId thingId = ThingId.of("org.eclipse.ditto.soak.ns1", "soak-1");
        final PolicyTag tag = PolicyTag.of(PolicyId.of("org.eclipse.ditto.soak.ns1", "policy-0"), 1L);
        final SearchIndexDocument document = SearchIndexDocument.newBuilder(thingId)
                .namespace("org.eclipse.ditto.soak.ns1")
                .revision(3L)
                .policyId(tag.getEntityId())
                .policyRevision(1L)
                .referencedPolicies(Set.of(tag))
                .globalRead(List.of("integration:soak-owner", "soak:auditor-1"))
                .thing(JsonObject.newBuilder()
                        .set("thingId", thingId.toString())
                        .set("_modified", "2026-01-01T00:00:03Z")
                        .set("attributes", JsonObject.newBuilder().set("note", "tab\there").build())
                        .build())
                .policyAuth(JsonObject.newBuilder().set("g", "r").build())
                .features(List.of(SearchIndexDocument.FeatureEntry.of("env", JsonObject.empty(),
                        JsonObject.newBuilder().set("g", "o").build())))
                .build();

        final String line = CopyTextFormat.documentLine(document);

        assertThat(line).endsWith("\n");
        final String[] fields = line.substring(0, line.length() - 1).split("\t", -1);
        assertThat(fields).hasSize(12);
        assertThat(fields[0]).isEqualTo("org.eclipse.ditto.soak.ns1:soak-1");
        assertThat(fields[2]).isEqualTo("3");
        assertThat(fields[3]).isEqualTo("org.eclipse.ditto.soak.ns1:policy-0");
        assertThat(fields[5]).isEqualTo("[" + tag.toJson() + "]");
        assertThat(fields[6]).isEqualTo("{\"integration:soak-owner\",\"soak:auditor-1\"}");
        assertThat(fields[7]).contains("tab\\\\there");           // JSON's \t, backslash-escaped for COPY text
        assertThat(fields[9]).isEqualTo("{\"env\":{\"g\":\"o\"}}");
        assertThat(fields[10]).isEqualTo("2026-01-01T00:00:03Z");
        assertThat(fields[11]).isEqualTo("\\N");                 // delete_at
    }

    @Test
    public void aShippedFlatRowBecomesOneSearchFlatLine() {
        final FlatRow row = new FlatRow("ns:t", "/features/env/properties/temperature",
                "/features/*/properties/temperature", "env", 0, (short) 2, null, new BigDecimal("21.50"), null);

        assertThat(CopyTextFormat.flatLine(row)).isEqualTo("ns:t\t/features/env/properties/temperature\t"
                + "/features/*/properties/temperature\tenv\t0\t2\t\\N\t21.50\t\\N\n");
    }

}
