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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.FlatRow;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.ThingFlattener;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.junit.Test;

/**
 * Unit test of the write-soak twin, measured with the shipped mapper and flattener: what an update rewrites, how
 * text-heavy the thing is, and that the long texts survive the index-length restriction.
 */
public final class SoakTwinTest {

    private static final SoakTwin TWIN = new SoakTwin(42L);

    @Test
    public void documentsAreDeterministicPerIndexAndRevision() {
        assertThat(new SoakTwin(42L).thing(17, 5)).isEqualTo(TWIN.thing(17, 5));
        assertThat(TWIN.thing(17, 5)).isNotEqualTo(TWIN.thing(18, 5));
        assertThat(TWIN.thing(17, 5)).isNotEqualTo(TWIN.thing(17, 6));
    }

    @Test
    public void eachUpdateRewritesExactlyOneHotGroupPlusRevisionAndModified() {
        for (long index = 0; index < 200; index++) {
            for (int revision = 2; revision <= 7; revision++) {
                final Set<FlatRow> before = new HashSet<>(flatten(index, revision - 1));
                final Set<FlatRow> after = new HashSet<>(flatten(index, revision));
                final Set<FlatRow> deleted = minus(before, after);
                final Set<FlatRow> inserted = minus(after, before);
                final Set<String> expected = new HashSet<>(revision % 2 == 0 ? SoakTwin.HOT_GROUP_0
                        : SoakTwin.HOT_GROUP_1);
                expected.addAll(SoakTwin.SYSTEM_PATHS_CHANGED_PER_UPDATE);

                assertThat(paths(deleted)).as("index %d revision %d", index, revision).isEqualTo(expected);
                assertThat(paths(inserted)).as("index %d revision %d", index, revision).isEqualTo(expected);
                assertThat(inserted).hasSameSizeAs(deleted);
                assertThat(deleted).allMatch(row -> SoakTwin.isRewrittenOnUpdate(row.path()));
            }
        }
    }

    @Test
    public void theTwinIsTextHeavyAndItsUpdatesHitTheTextAndMultiValueIndexes() {
        long valueRows = 0;
        long textRows = 0;
        long changedRows = 0;
        long changedTextRows = 0;
        long changedOrdOneRows = 0;
        for (long index = 0; index < 500; index++) {
            for (final FlatRow row : flatten(index, 1)) {
                if (row.valText() != null || row.valNum() != null || row.valBool() != null) {
                    valueRows++;
                    if (row.valText() != null) {
                        textRows++;
                    }
                }
            }
            for (int revision = 2; revision <= 3; revision++) {
                final Set<FlatRow> inserted = minus(new HashSet<>(flatten(index, revision)),
                        new HashSet<>(flatten(index, revision - 1)));
                changedRows += inserted.size();
                changedTextRows += inserted.stream().filter(row -> row.valText() != null).count();
                changedOrdOneRows += inserted.stream().filter(row -> row.ord() == 1).count();
            }
        }
        System.out.printf(Locale.ROOT, "soak twin: %.1f %% text value rows, %.1f flat rows changed per update "
                        + "(%.1f %% text)%n", 100.0 * textRows / valueRows, changedRows / 1_000.0,
                100.0 * changedTextRows / changedRows);
        assertThat((double) textRows / valueRows).isGreaterThanOrEqualTo(0.6);
        assertThat((double) changedTextRows / changedRows).isGreaterThanOrEqualTo(0.5);
        assertThat(changedOrdOneRows).as("group 1's two-element array writes ord = 1 rows (sf_multi)").isPositive();
    }

    @Test
    public void aboutOneThingInTenCarriesLongTextsThatTheIndexLengthRestrictionKeepsWhole() {
        int withLongTexts = 0;
        for (long index = 0; index < 2_000; index++) {
            final JsonObject thing = TWIN.thing(index, 1);
            final Optional<JsonValue> notes = thing.getValue(JsonPointer.of("/attributes/notes"));
            if (notes.isPresent()) {
                withLongTexts++;
                assertThat(notes.get().asString().length())
                        .isBetween(SoakTwin.LONG_TEXT_MIN, SoakTwin.LONG_TEXT_MAX);
                final JsonObject indexed = TWIN.writeModel(index, 1).getDocument().thing();
                for (final String leaf : List.of("notes", "maintenanceLog", "installationNotes")) {
                    final JsonPointer pointer = JsonPointer.of("/attributes/" + leaf);
                    assertThat(indexed.getValue(pointer)).as("%s of thing %d", leaf, index)
                            .isEqualTo(thing.getValue(pointer));
                }
            }
        }
        assertThat(withLongTexts).isBetween(160, 240);
    }

    @Test
    public void rowsPerThingStayNearTheSizingAssumption() {
        long rows = 0;
        for (long index = 0; index < 1_000; index++) {
            rows += flatten(index, 1).size();
        }
        final double perThing = rows / 1_000.0;
        System.out.printf(Locale.ROOT, "soak twin: %.1f flat rows per thing%n", perThing);
        assertThat(perThing).isBetween(60.0, 100.0);
    }

    @Test
    public void theLoadedStateIsRevisionOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> TWIN.thing(1, 0));
        assertThat(TWIN.thing(3, 1).getValue("_revision")).contains(JsonValue.of(1));
        assertThat(TWIN.writeModel(3, 1).getDocument().policyId()).map(Object::toString)
                .contains(SoakTwin.policyId(3));
    }

    private static List<FlatRow> flatten(final long index, final int revision) {
        return ThingFlattener.flatten(TWIN.writeModel(index, revision).getDocument());
    }

    private static Set<FlatRow> minus(final Set<FlatRow> a, final Set<FlatRow> b) {
        final Set<FlatRow> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static Set<String> paths(final Set<FlatRow> rows) {
        return rows.stream().map(FlatRow::path).collect(Collectors.toSet());
    }

}
