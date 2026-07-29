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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Plain, Docker-independent unit test for {@link Flattener}: feeds small in-memory thing payloads straight to
 * {@link Flattener#flatten(String, Map)} and asserts the flattening rules documented on that class, without any
 * database round-trip. Runs unconditionally in the default (unit) test phase — unlike
 * {@link PostgresSearchBenchSmokeIT}, which is Testcontainers-backed and self-skips when Docker is unreachable.
 */
public class FlattenerTest {

    private static final String THING_ID = "flattener.test:thing-1";

    @Test
    public void featureSubtreeScalarLeafEmitsTwoRowsWithFeatureId() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("temperature", new BigDecimal("21.5"));
        final Map<String, Object> envFeature = new LinkedHashMap<>();
        envFeature.put("properties", properties);
        final Map<String, Object> features = new LinkedHashMap<>();
        features.put("env", envFeature);
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("features", features);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        final String path = "/features/env/properties/temperature";
        final List<FlatRow> matching = rowsAtPath(rows, path);
        assertThat(matching).hasSize(2);
        assertThat(matching).allSatisfy(row -> {
            assertThat(row.path()).isEqualTo(path);
            assertThat(row.fId()).isEqualTo("env");
            assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_NUMBER);
            assertThat(row.valNum()).isEqualByComparingTo("21.5");
            assertThat(row.valBool()).isNull();
            assertThat(row.valText()).isNull();
        });
        assertThat(matching.stream().map(FlatRow::wpath))
                .containsExactlyInAnyOrder(path, "/features/*/properties/temperature");
    }

    @Test
    public void featureSubtreeObjectAndEmptyContainerRowsAlsoEmitTwice() {
        // The dual-row-on-every-feature-subtree-row behavior applies to container rows too, not just scalar
        // leaves: an object node, an empty object, and an empty array under a feature all get two rows each.
        final Map<String, Object> emptyObj = new LinkedHashMap<>();
        final List<Object> emptyArr = new ArrayList<>();
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("emptyObj", emptyObj);
        properties.put("emptyArr", emptyArr);
        final Map<String, Object> envFeature = new LinkedHashMap<>();
        envFeature.put("properties", properties);
        final Map<String, Object> features = new LinkedHashMap<>();
        features.put("env", envFeature);
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("features", features);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        // The "properties" object itself (an object/container row) is duplicated.
        final List<FlatRow> propertiesRows = rowsAtPath(rows, "/features/env/properties");
        assertThat(propertiesRows).hasSize(2);
        assertThat(propertiesRows).allSatisfy(row -> {
            assertThat(row.fId()).isEqualTo("env");
            assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_OBJECT);
        });
        assertThat(propertiesRows.stream().map(FlatRow::wpath))
                .containsExactlyInAnyOrder("/features/env/properties", "/features/*/properties");

        // The empty object row is duplicated.
        final List<FlatRow> emptyObjRows = rowsAtPath(rows, "/features/env/properties/emptyObj");
        assertThat(emptyObjRows).hasSize(2);
        assertThat(emptyObjRows).allSatisfy(row -> {
            assertThat(row.fId()).isEqualTo("env");
            assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_OBJECT);
        });
        assertThat(emptyObjRows.stream().map(FlatRow::wpath))
                .containsExactlyInAnyOrder("/features/env/properties/emptyObj", "/features/*/properties/emptyObj");

        // The empty array row is duplicated.
        final List<FlatRow> emptyArrRows = rowsAtPath(rows, "/features/env/properties/emptyArr");
        assertThat(emptyArrRows).hasSize(2);
        assertThat(emptyArrRows).allSatisfy(row -> {
            assertThat(row.fId()).isEqualTo("env");
            assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_ARRAY);
        });
        assertThat(emptyArrRows.stream().map(FlatRow::wpath))
                .containsExactlyInAnyOrder("/features/env/properties/emptyArr", "/features/*/properties/emptyArr");
    }

    @Test
    public void nonFeatureRowsAreSingleWithNullFeatureId() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("color", "red");
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        // Both the "attributes" container row and the "color" leaf row are single, non-feature rows.
        final List<FlatRow> attributesRows = rowsAtPath(rows, "/attributes");
        assertThat(attributesRows).hasSize(1);
        assertThat(attributesRows.get(0).fId()).isNull();
        assertThat(attributesRows.get(0).wpath()).isEqualTo("/attributes");
        assertThat(attributesRows.get(0).typeRank()).isEqualTo(Flattener.TYPE_OBJECT);

        final List<FlatRow> colorRows = rowsAtPath(rows, "/attributes/color");
        assertThat(colorRows).hasSize(1);
        final FlatRow colorRow = colorRows.get(0);
        assertThat(colorRow.fId()).isNull();
        assertThat(colorRow.path()).isEqualTo(colorRow.wpath());
        assertThat(colorRow.typeRank()).isEqualTo(Flattener.TYPE_STRING);
        assertThat(colorRow.valText()).isEqualTo("red");
    }

    @Test
    public void ordEnumeratesRepeatOccurrencesAtSamePathIncludingThroughNestedObjectExplosion() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        // Plain scalar array: 3 elements sharing one path -> ord 0, 1, 2.
        attributes.put("tags", List.of("red", "green", "blue"));

        // Array of objects, exploded recursively through object nesting: each object element reuses the
        // array's own path, and the nested "value" leaf inside each object element also reuses ITS own path
        // for each of the 2 repetitions -> ord 0, 1 at that nested path too.
        final Map<String, Object> reading1 = new LinkedHashMap<>();
        reading1.put("value", 10);
        final Map<String, Object> reading2 = new LinkedHashMap<>();
        reading2.put("value", 20);
        attributes.put("readings", List.of(reading1, reading2));

        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        final List<FlatRow> tagRows = rowsAtPath(rows, "/attributes/tags");
        assertThat(tagRows).extracting(FlatRow::ord).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(tagRows).extracting(FlatRow::valText).containsExactlyInAnyOrder("red", "green", "blue");

        // The "readings" array itself contributes no container row (non-empty, non-nested array) — only its
        // exploded object elements, both sharing "/attributes/readings" as their own path.
        final List<FlatRow> readingsContainerRows = rows.stream()
                .filter(r -> r.path().equals("/attributes/readings"))
                .filter(r -> r.typeRank() == Flattener.TYPE_ARRAY)
                .toList();
        assertThat(readingsContainerRows).isEmpty();

        final List<FlatRow> readingsObjectRows = rowsAtPath(rows, "/attributes/readings");
        assertThat(readingsObjectRows).extracting(FlatRow::ord).containsExactlyInAnyOrder(0, 1);
        assertThat(readingsObjectRows).allSatisfy(row -> assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_OBJECT));

        final List<FlatRow> valueRows = rowsAtPath(rows, "/attributes/readings/value");
        assertThat(valueRows).extracting(FlatRow::ord).containsExactlyInAnyOrder(0, 1);
        assertThat(valueRows.stream().map(r -> r.valNum().intValue()).toList())
                .containsExactlyInAnyOrder(10, 20);
    }

    @Test
    public void ordResetsBetweenSeparateFlattenCalls() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tags", List.of("red", "green", "blue"));
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> firstCall = Flattener.flatten(THING_ID, thing);
        final List<FlatRow> secondCall = Flattener.flatten(THING_ID, thing);

        assertThat(rowsAtPath(firstCall, "/attributes/tags")).extracting(FlatRow::ord)
                .containsExactlyInAnyOrder(0, 1, 2);
        assertThat(rowsAtPath(secondCall, "/attributes/tags")).extracting(FlatRow::ord)
                .containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    public void directArrayOfArrayIsNotExplodedAndYieldsValuelessArrayStubRows() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("matrix", List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6)));
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        final List<FlatRow> matrixRows = rowsAtPath(rows, "/attributes/matrix");
        assertThat(matrixRows).hasSize(3);
        assertThat(matrixRows).allSatisfy(row -> {
            assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_ARRAY);
            assertThat(row.valBool()).isNull();
            assertThat(row.valNum()).isNull();
            assertThat(row.valText()).isNull();
        });
        assertThat(matrixRows).extracting(FlatRow::ord).containsExactlyInAnyOrder(0, 1, 2);

        // No row was ever emitted for the inner arrays' own content/path — confirming no descent happened.
        assertThat(rows).noneMatch(r -> r.valNum() != null
                && List.of(1, 2, 3, 4, 5, 6).contains(r.valNum().intValue()));
    }

    @Test
    public void typeRankMappingAndExclusiveScalarValueSlots() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("aNull", null);
        attributes.put("aNumber", new BigDecimal("3.5"));
        attributes.put("aString", "hello");
        attributes.put("aBoolean", Boolean.TRUE);
        final Map<String, Object> nestedObj = new LinkedHashMap<>();
        nestedObj.put("k", "v");
        attributes.put("anObject", nestedObj);
        attributes.put("anArray", List.of("x", "y"));
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        final FlatRow nullRow = onlyRowAtPath(rows, "/attributes/aNull");
        assertThat(nullRow.typeRank()).isEqualTo(Flattener.TYPE_NULL);
        assertThat(nullRow.valBool()).isNull();
        assertThat(nullRow.valNum()).isNull();
        assertThat(nullRow.valText()).isNull();

        final FlatRow numberRow = onlyRowAtPath(rows, "/attributes/aNumber");
        assertThat(numberRow.typeRank()).isEqualTo(Flattener.TYPE_NUMBER);
        assertThat(numberRow.valNum()).isEqualByComparingTo("3.5");
        assertThat(numberRow.valBool()).isNull();
        assertThat(numberRow.valText()).isNull();

        final FlatRow stringRow = onlyRowAtPath(rows, "/attributes/aString");
        assertThat(stringRow.typeRank()).isEqualTo(Flattener.TYPE_STRING);
        assertThat(stringRow.valText()).isEqualTo("hello");
        assertThat(stringRow.valBool()).isNull();
        assertThat(stringRow.valNum()).isNull();

        final FlatRow booleanRow = onlyRowAtPath(rows, "/attributes/aBoolean");
        assertThat(booleanRow.typeRank()).isEqualTo(Flattener.TYPE_BOOLEAN);
        assertThat(booleanRow.valBool()).isTrue();
        assertThat(booleanRow.valNum()).isNull();
        assertThat(booleanRow.valText()).isNull();

        final FlatRow objectRow = onlyRowAtPath(rows, "/attributes/anObject");
        assertThat(objectRow.typeRank()).isEqualTo(Flattener.TYPE_OBJECT);
        assertThat(objectRow.valBool()).isNull();
        assertThat(objectRow.valNum()).isNull();
        assertThat(objectRow.valText()).isNull();

        final List<FlatRow> arrayRows = rowsAtPath(rows, "/attributes/anArray");
        // "anArray" is a non-empty, non-nested array of scalars: no container row, only the two exploded
        // string element rows (type_rank = 3, not 5) at its own path.
        assertThat(arrayRows).hasSize(2);
        assertThat(arrayRows).allSatisfy(row -> assertThat(row.typeRank()).isEqualTo(Flattener.TYPE_STRING));
    }

    @Test
    public void emptyObjectAndEmptyArrayEachProduceExactlyOneRow() {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("emptyObj", new LinkedHashMap<>());
        attributes.put("emptyArr", new ArrayList<>());
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        final FlatRow emptyObjRow = onlyRowAtPath(rows, "/attributes/emptyObj");
        assertThat(emptyObjRow.typeRank()).isEqualTo(Flattener.TYPE_OBJECT);
        assertThat(emptyObjRow.fId()).isNull();

        final FlatRow emptyArrRow = onlyRowAtPath(rows, "/attributes/emptyArr");
        assertThat(emptyArrRow.typeRank()).isEqualTo(Flattener.TYPE_ARRAY);
        assertThat(emptyArrRow.fId()).isNull();

        // Neither empty container contributes any child rows.
        assertThat(rows.stream().filter(r -> r.path().startsWith("/attributes/emptyObj/"))).isEmpty();
        assertThat(rows.stream().filter(r -> r.path().startsWith("/attributes/emptyArr/"))).isEmpty();
    }

    @Test
    public void onlyThePayloadIsFlattenedNoExtraBookkeepingRows() {
        // Flattener.flatten() only accepts the thing's payload map (thingId is copied verbatim into every
        // row, not derived from the payload) — there is no auth/bookkeeping input to the API at all, so the
        // only observable guarantee is that the row count exactly matches what the payload's shape implies.
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("color", "red");
        final Map<String, Object> thing = new LinkedHashMap<>();
        thing.put("attributes", attributes);

        final List<FlatRow> rows = Flattener.flatten(THING_ID, thing);

        // Exactly the "attributes" container row and the "color" leaf row - nothing else.
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.thingId()).isEqualTo(THING_ID));
        assertThat(rows.stream().map(FlatRow::path)).containsExactlyInAnyOrder("/attributes", "/attributes/color");
    }

    private static List<FlatRow> rowsAtPath(final List<FlatRow> rows, final String path) {
        return rows.stream().filter(r -> r.path().equals(path)).toList();
    }

    private static FlatRow onlyRowAtPath(final List<FlatRow> rows, final String path) {
        final List<FlatRow> matching = rowsAtPath(rows, path);
        assertThat(matching).as("rows at path %s", path).hasSize(1);
        return matching.get(0);
    }

}
