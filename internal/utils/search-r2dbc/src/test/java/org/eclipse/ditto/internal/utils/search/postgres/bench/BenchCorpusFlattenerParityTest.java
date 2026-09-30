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

import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.ThingFlattener;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.junit.Test;

/**
 * The production-config bench keeps loading the corpus with the Phase-0 bench {@link Flattener} (so its flat rows are
 * byte-identical to the original bench corpus and the numbers stay comparable). This test proves that, for the bench
 * generator's things, those rows are the rows the SHIPPED {@link ThingFlattener} would write — same
 * {@code path}/{@code wpath}/{@code f_id}/{@code ord}/{@code val_bool}/{@code val_text}, numerically equal
 * {@code val_num} (generated two-decimal doubles keep their scale in the bench, e.g. {@code 23.40} vs {@code 23.4};
 * {@code numeric} compares them as equal) — with exactly ONE known divergence:
 * <p>
 * <b>A {@code null} element inside an array</b> is a {@code type_rank = 1} (null) row in the bench but a
 * {@code type_rank = 5} (array stub) row from {@link ThingFlattener}: Ditto's null literal reports
 * {@code isArray() == true}, and the shipped flattener's array loop checks {@code element.isArray()} before it recurses
 * into the element. No benchmarked shape touches such rows (none queries {@code null} or sorts by an array path), so
 * the benchmark numbers are unaffected; the divergence itself is recorded as a follow-up of the production-config
 * baseline (probable Mongo-parity gap for {@code eq(path,null)} on arrays that contain {@code null}).
 * </p>
 */
public final class BenchCorpusFlattenerParityTest {

    private static final long SEED = 42L;
    private static final int THINGS = 2_000;

    @Test
    public void benchFlattenerRowsEqualProductionFlattenerRowsForTheGeneratedCorpus() {
        long rows = 0;
        long nullArrayElementRows = 0;
        for (long index = 0; index < THINGS; index++) {
            final ThingRecord thing = CorpusGenerator.generate(index, SEED);
            final List<FlatRow> bench = Flattener.flatten(thing.thingId(), thing.thing());
            final JsonObject json = JsonFactory.newObject(JsonWriter.write(thing.thing()));
            final List<org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.FlatRow> production =
                    ThingFlattener.flatten(thing.thingId(), json);

            assertThat(production).as("row count of %s", thing.thingId()).hasSameSizeAs(bench);
            for (int i = 0; i < bench.size(); i++) {
                final FlatRow b = bench.get(i);
                final var p = production.get(i);
                final String row = thing.thingId() + " row " + i + " " + b + " vs " + p;
                assertThat(p.thingId()).as(row).isEqualTo(b.thingId());
                assertThat(p.path()).as(row).isEqualTo(b.path());
                assertThat(p.wpath()).as(row).isEqualTo(b.wpath());
                assertThat(p.fId()).as(row).isEqualTo(b.fId());
                assertThat(p.ord()).as(row).isEqualTo(b.ord());
                assertThat(p.valBool()).as(row).isEqualTo(b.valBool());
                assertThat(p.valText()).as(row).isEqualTo(b.valText());
                assertThat(numericallyEqual(p.valNum(), b.valNum())).as(row).isTrue();
                if (isKnownNullArrayElementDivergence(b, p.typeRank())) {
                    nullArrayElementRows++;
                } else {
                    assertThat(p.typeRank()).as(row).isEqualTo(b.typeRank());
                }
            }
            rows += bench.size();
        }
        System.out.printf("compared %,d flat rows of %,d things; known null-array-element divergences: %,d%n",
                rows, THINGS, nullArrayElementRows);
        assertThat(rows).as("rows compared").isGreaterThan(THINGS * 10L);
    }

    private static boolean isKnownNullArrayElementDivergence(final FlatRow bench, final short productionTypeRank) {
        return bench.typeRank() == Flattener.TYPE_NULL && productionTypeRank == ThingFlattener.TYPE_ARRAY
                && bench.valBool() == null && bench.valNum() == null && bench.valText() == null;
    }

    private static boolean numericallyEqual(final BigDecimal a, final BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

}
