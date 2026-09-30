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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.PostgresSearchSchema;
import org.junit.Test;

/**
 * Pins that the production-config bench mode applies the SHIPPED search DDL and nothing else: the pre-load and
 * post-load phases together are exactly the statements of {@link PostgresSearchSchema#ddlStatements()}, each once, in
 * production order within each phase, so no bench-only statement (statistics targets, covering indexes, scoped
 * trigram) can ride along. The expectations are written out here (not re-derived with the code's own
 * {@code CREATE INDEX} predicate), so a change of either side fails the test (review round 1, M6).
 */
public final class ProductionConfigSchemaTest {

    @Test
    public void preLoadIsTheExtensionAndTheThreeTablesInProductionOrder() {
        final List<String> pre = ProductionConfigSchema.preLoadStatements();

        assertThat(pre).hasSize(4);
        assertThat(pre.get(0)).isEqualTo("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        assertThat(pre.get(1)).startsWith("CREATE TABLE IF NOT EXISTS search_things (");
        assertThat(pre.get(2)).startsWith("CREATE TABLE IF NOT EXISTS search_flat (");
        assertThat(pre.get(3)).startsWith("CREATE TABLE IF NOT EXISTS search_sync (");
    }

    @Test
    public void postLoadIsTheElevenProductionIndexesInProductionOrder() {
        final List<String> names = new ArrayList<>();
        for (final String statement : ProductionConfigSchema.postLoadStatements()) {
            names.add(statement.replaceFirst("^CREATE INDEX IF NOT EXISTS (\\S+) ON .*$", "$1"));
        }

        assertThat(names).containsExactly("st_namespace", "st_global_read", "st_policy", "st_referenced_pols",
                "st_delete_at", "st_modified", "sf_num", "sf_text", "sf_bool", "sf_exists", "sf_trgm");
    }

    @Test
    public void bothPhasesTogetherAreExactlyTheProductionStatements() {
        final List<String> applied = new ArrayList<>(ProductionConfigSchema.preLoadStatements());
        applied.addAll(ProductionConfigSchema.postLoadStatements());

        assertThat(applied).containsExactlyInAnyOrderElementsOf(PostgresSearchSchema.ddlStatements());
    }

    @Test
    public void expectedIndexNamesAreParsedFromTheProductionDdl() {
        assertThat(ProductionConfigSchema.expectedIndexNames()).isEqualTo(Set.of(
                "search_things_pkey", "search_flat_pkey", "search_sync_pkey",
                "st_namespace", "st_global_read", "st_policy", "st_referenced_pols", "st_delete_at", "st_modified",
                "sf_num", "sf_text", "sf_bool", "sf_exists", "sf_trgm"));
    }

}
