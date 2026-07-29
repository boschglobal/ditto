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
package org.eclipse.ditto.internal.utils.persistence.postgres.search.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.JsonPointerSegments;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.FlatRow;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.ThingFlattener;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.RenderedSql;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.Sql;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.SqlBindType;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.rql.query.criteria.Criteria;
import org.eclipse.ditto.rql.query.criteria.CriteriaFactory;
import org.eclipse.ditto.rql.query.expression.ThingsFieldExpressionFactory;
import org.junit.Test;

/**
 * Golden-SQL unit tests for the RQL→SQL criteria translation (Task D2) — one assertion per §3.5 table row + each §3.3
 * auth exception, plus adversarial cases (revoke-below-grant chains, wildcard-ne divergence, RFC-6901 escaping parity,
 * configured simple-field-mappings, hostile strings). No database: exact rendered SQL + ordered binds via the D1
 * renderer.
 */
public final class RqlToSqlTranslationTest {

    private static final String G = "·g";
    private static final String R = "·r";
    private static final List<String> SUBJECTS = List.of("s1", "s2");

    /** The service's default simple-field-mappings (search.conf:145-153). */
    private static final Map<String, String> MAPPINGS = Map.of(
            "thingId", "_id",
            "namespace", "_namespace",
            "policyId", "/policyId",
            "_revision", "/_revision",
            "_modified", "/_modified",
            "_created", "/_created",
            "definition", "/definition");

    private final ThingsFieldExpressionFactory fef = ThingsFieldExpressionFactory.of(MAPPINGS);
    private final CriteriaFactory cf = CriteriaFactory.getInstance();

    // =========================================================================================== §3.5 predicate rows

    @Test
    public void eqNumberProbesValNum() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("temperature"), cf.eq(23)));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.val_num = $2))");
        assertThat(r.bindValues()).containsExactly("/attributes/temperature", BigDecimal.valueOf(23));
    }

    @Test
    public void eqStringProbesValText() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("name"), cf.eq("bob")));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.val_text = $2))");
        assertThat(r.bindValues()).containsExactly("/attributes/name", "bob");
    }

    @Test
    public void eqBooleanProbesValBool() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("on"), cf.eq(true)));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.val_bool = $2))");
        assertThat(r.bindValues()).containsExactly("/attributes/on", true);
    }

    @Test
    public void eqNullMatchesNullRankRowNotMissing() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.eq(null)));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.type_rank = $2))");
        assertThat(r.bindValues()).containsExactly("/attributes/x", 1); // TYPE_NULL
    }

    @Test
    public void gtGeLtLeRenderRangeComparisons() {
        assertThat(sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.gt(5))).sql())
                .contains("sf.val_num > $2");
        assertThat(sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.ge(5))).sql())
                .contains("sf.val_num >= $2");
        assertThat(sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.lt(5))).sql())
                .contains("sf.val_num < $2");
        assertThat(sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.le(5))).sql())
                .contains("sf.val_num <= $2");
    }

    @Test
    public void neNonWildcardIsExistsAndNotExists() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.ne(5)));
        assertThat(r.sql()).isEqualTo(
                "(EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = $1)) AND "
                        + "NOT EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $2 AND sf.val_num = $3)))");
        assertThat(r.bindValues()).containsExactly("/attributes/x", "/attributes/x", BigDecimal.valueOf(5));
    }

    @Test
    public void neNullMatchesTypeRankNegationNotTextLiteral() {
        // Mongo parity: ne(p,null) must match ONLY a leaf that EXISTS with a NON-null value -- a JSON-null leaf,
        // like a missing one, is excluded. Before the fix, `value == null` fell straight into the generic bind
        // path: SqlValues.bind(null) renders the TEXT literal 'null' (String.valueOf(null)), which never equals a
        // null-rank row's SQL-NULL val_text column, so the NOT-EXISTS probe was vacuously true and ne(p,null)
        // silently degenerated to bare exists(p). The fix mirrors visitEq's null special-case (see
        // eqNullMatchesNullRankRowNotMissing above): negate the SAME type_rank = TYPE_NULL equality instead.
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.ne(null)));
        assertThat(r.sql()).isEqualTo(
                "(EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = $1)) AND "
                        + "NOT EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $2 AND sf.type_rank = $3)))");
        assertThat(r.bindValues()).containsExactly("/attributes/x", "/attributes/x", 1); // TYPE_NULL, never 'null'
    }

    @Test
    public void neStringLiteralNullStillUsesGenericTextBind() {
        // Guards the OTHER half of the bug: the Java String "null" (a genuine RQL string value, not the absence of
        // a value) must NOT be conflated with Java null -- it keeps using the generic val_text = 'null' bind. This
        // is what fixes the second, string-"null"-wrongly-excluded symptom "for free": once eq/ne no longer treat
        // Java null and the string "null" as the same thing, a leaf whose actual value IS the string "null" can be
        // matched again.
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.ne("null")));
        assertThat(r.sql()).isEqualTo(
                "(EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = $1)) AND "
                        + "NOT EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $2 AND sf.val_text = $3)))");
        assertThat(r.bindValues()).containsExactly("/attributes/x", "/attributes/x", "null");
    }

    @Test
    public void neNullOnRootColumnRendersIsNotNull() {
        // Root-mapped columns (thingId/namespace) go through columnCondition, not flatCondition. Before the fix
        // this rendered `<> 'null'` (the same String.valueOf(null) conflation); the fix renders IS NOT NULL,
        // the column-leg mirror of visitEq's IS NULL for eq(null).
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByNamespace(), cf.ne(null)));
        assertThat(r.sql()).isEqualTo("st.namespace IS NOT NULL");
        assertThat(r.bindValues()).isEmpty();
    }

    @Test
    public void inNumbersRenderAnyNumericArray() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.in(List.of(1, 2, 3))));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.val_num = ANY($2::numeric[])))");
        assertThat(r.binds().get(1).type()).isEqualTo(SqlBindType.NUMERIC_ARRAY);
    }

    @Test
    public void inMixedTypesOrsPerTypeArrays() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("x"), cf.in(List.of(1, "a"))));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND "
                + "(sf.val_num = ANY($2::numeric[]) OR sf.val_text = ANY($3::text[]))))");
    }

    @Test
    public void likeBuildsNativeLikePatternFromWildcards() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("name"), cf.like("a*b?c")));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.val_text LIKE $2 ESCAPE '\\'))");
        assertThat(r.bindValues()).containsExactly("/attributes/name", "a%b_c");
    }

    @Test
    public void likeEscapesSqlWildcardCharacters() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("name"), cf.like("50%_x\\y")));
        // literal % _ \ are backslash-escaped; there are no * or ? so nothing becomes a wildcard.
        assertThat(r.bindValues().get(1)).isEqualTo("50\\%\\_x\\\\y");
    }

    @Test
    public void ilikeAddsCollationOverride() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("name"), cf.ilike("a*")));
        assertThat(r.sql()).isEqualTo("EXISTS (SELECT 1 FROM search_flat sf WHERE "
                + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND "
                + "sf.val_text COLLATE \"C.utf8\" ILIKE $2 ESCAPE '\\'))");
        assertThat(r.bindValues()).containsExactly("/attributes/name", "a%");
    }

    // ============================================================================================ boolean tree rows

    @Test
    public void andOrNotRenderOverExistsTerms() {
        final Criteria and = cf.and(List.of(
                cf.fieldCriteria(fef.filterByAttribute("a"), cf.eq(1)),
                cf.fieldCriteria(fef.filterByAttribute("b"), cf.eq(2))));
        assertThat(sudo(and).sql()).startsWith("(EXISTS (").contains(" AND EXISTS (");

        final Criteria or = cf.or(List.of(
                cf.fieldCriteria(fef.filterByAttribute("a"), cf.eq(1)),
                cf.fieldCriteria(fef.filterByAttribute("b"), cf.eq(2))));
        assertThat(sudo(or).sql()).contains(" OR EXISTS (");

        // nor = NOT OR (Mongo nor semantics preserved)
        final Criteria nor = cf.nor(List.of(cf.fieldCriteria(fef.filterByAttribute("a"), cf.eq(1))));
        assertThat(sudo(nor).sql()).startsWith("NOT EXISTS (");
    }

    @Test
    public void anyMatchesAll() {
        assertThat(sudo(cf.any()).sql()).isEqualTo("true");
    }

    // ============================================================================ exists() rows + §3.3 exception 2

    @Test
    public void existsConcretePathCarriesNoAuthUnderSudo() {
        final RenderedSql r = sudo(cf.existsCriteria(fef.existsByAttribute("x")));
        assertThat(r.sql()).isEqualTo(
                "EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = $1))");
        assertThat(r.bindValues()).containsExactly("/attributes/x");
    }

    @Test
    public void existsWildcardFeatureIsBareProbeWithNoAuthEvenWhenRestricted() {
        // §3.3 exception 2: exists(features/*) — bare feature-boundary wpath probe, NO auth chain at all.
        final Criteria c = cf.existsCriteria(fef.existsByFeatureId("*"));
        final RenderedSql r = render(CreateSqlVisitor.apply(c, SUBJECTS).predicate());
        assertThat(r.sql()).isEqualTo(
                "(EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = $1)) AND "
                        + "st.global_read && $2::text[])");
        assertThat(r.bindValues().get(0)).isEqualTo("/features/*");
        // only the wpath and the global-read subjects — no features_auth extraction.
        assertThat(r.sql()).doesNotContain("features_auth");
    }

    // ============================================================================================== empty() row

    @Test
    public void emptyConcretePathOrsAllEmptyMarkersWithDeeperObjectAntiProbe() {
        final RenderedSql r = sudo(cf.emptyCriteria(fef.existsByAttribute("x")));
        assertThat(r.sql()).isEqualTo(
                "(NOT EXISTS (SELECT 1 FROM search_flat sf WHERE (sf.thing_id = st.thing_id AND sf.wpath = $1)) OR "
                        + "EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $2 AND sf.type_rank = $3)) OR "
                        + "EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $4 AND sf.type_rank = $5)) OR "
                        + "EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $6 AND sf.val_text = $7)) OR "
                        + "(EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $8 AND sf.type_rank = $9)) AND "
                        + "NOT EXISTS (SELECT 1 FROM search_flat c WHERE "
                        + "(c.thing_id = st.thing_id AND starts_with(c.path, $10)))))");
        assertThat(r.bindValues()).containsExactly(
                "/attributes/x", "/attributes/x", 1, "/attributes/x", 5, "/attributes/x", "",
                "/attributes/x", 4, "/attributes/x/");
    }

    @Test
    public void emptyOverMatchesDirectArrayOfArrayContentDivergingFromMongoLiteralEmptyArraySemantics() {
        // DOCUMENTED DIVERGENCE, pinned deliberately (see GetEmptySqlVisitor javadoc + plan §3.2 stub rule): a
        // NON-EMPTY direct array-of-arrays such as x=[[1,2],[3]] emits one valueless type_rank=5 (TYPE_ARRAY) stub
        // row per OUTER element at the exact same wpath a literal x=[] would occupy. The two are indistinguishable
        // at the flat-row level, so Postgres empty(x) spuriously matches x=[[1,2],[3]] where Mongo's
        // GetEmptyBsonVisitor matches ONLY the literal empty array []. If this test ever starts failing because the
        // flattener or the visitor changed to disambiguate the two cases, that is a DELIBERATE parity improvement —
        // update this pin, don't just "fix" it back.
        final JsonObject arrayOfArrayThing = JsonFactory.newObject("""
                { "attributes": { "x": [[1,2],[3]] } }
                """);
        final List<FlatRow> stubRowsAtX = ThingFlattener.flatten("ns:t", arrayOfArrayThing).stream()
                .filter(row -> row.path().equals("/attributes/x"))
                .toList();
        // 2 outer elements -> 2 stub rows, all rank=TYPE_ARRAY (5), no value in ANY slot -- not disambiguable from
        // the single rank-5 stub row a literal x=[] would produce.
        assertThat(stubRowsAtX).hasSize(2);
        assertThat(stubRowsAtX).allSatisfy(row -> {
            assertThat(row.typeRank()).isEqualTo(ThingFlattener.TYPE_ARRAY);
            assertThat(row.valBool()).isNull();
            assertThat(row.valNum()).isNull();
            assertThat(row.valText()).isNull();
        });

        // The empty() predicate's array-branch (emptyCore's `emptyArray`) is a bare EXISTS(type_rank = 5) at wpath
        // -- it carries no way to tell "stub from a non-empty array-of-arrays" apart from "stub from a literal
        // empty array", so it IS satisfied by the rows above. Pin the exact bind (5 = TYPE_ARRAY) that drives it,
        // identical to the literal-empty-array case exercised in
        // emptyConcretePathOrsAllEmptyMarkersWithDeeperObjectAntiProbe.
        final RenderedSql r = sudo(cf.emptyCriteria(fef.existsByAttribute("x")));
        assertThat(r.bindValues()).contains(5); // TYPE_ARRAY -- the divergent, over-matching branch
    }

    // ================================================================================= simple/system fields (§3.5)

    @Test
    public void rootMappedThingIdIsDocColumnNoAuthNoExists() {
        // §3.3 exception 1 + §3.5: thingId → _id → st.thing_id column, no auth, no flat probe.
        final RenderedSql r = render(CreateSqlVisitor.apply(
                cf.fieldCriteria(fef.filterByThingId(), cf.eq("ns:thing1")), SUBJECTS).predicate());
        assertThat(r.sql()).isEqualTo("(st.thing_id = $1 AND st.global_read && $2::text[])");
        assertThat(r.bindValues().get(0)).isEqualTo("ns:thing1");
    }

    @Test
    public void rootMappedNamespaceIsDocColumn() {
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByNamespace(), cf.eq("ns")));
        assertThat(r.sql()).isEqualTo("st.namespace = $1");
        assertThat(r.bindValues()).containsExactly("ns");
    }

    @Test
    public void slashMappedModifiedIsFlatProbeWithStringComparison() {
        // _modified → /_modified: slash-mapped flat probe, string (val_text) comparison, WITH auth.
        final RenderedSql r = render(CreateSqlVisitor.apply(
                cf.fieldCriteria(fef.filterBy("_modified"), cf.gt("2020-01-01T00:00:00Z")), SUBJECTS).predicate());
        assertThat(r.sql()).contains("sf.wpath = $1 AND sf.val_text > $2");
        assertThat(r.bindValues().get(0)).isEqualTo("/_modified");
        assertThat(r.bindValues().get(1)).isEqualTo("2020-01-01T00:00:00Z");
        // slash-mapped fields DO carry auth (unlike root-mapped)
        assertThat(r.sql()).contains("st.policy_auth #>");
    }

    @Test
    public void configuredMappingOverrideIsHonored() {
        // A deployment that remaps thingId to a slash path makes it a flat probe WITH auth instead of a column.
        final ThingsFieldExpressionFactory custom = ThingsFieldExpressionFactory.of(Map.of("thingId", "/thingId"));
        final RenderedSql r = render(CreateSqlVisitor.apply(
                cf.fieldCriteria(custom.filterByThingId(), cf.eq("ns:t")), SUBJECTS).predicate());
        assertThat(r.sql()).contains("sf.wpath = $1").contains("st.policy_auth #>");
        assertThat(r.bindValues().get(0)).isEqualTo("/thingId");
    }

    // ================================================================================= §3.3 auth-tree exact nesting

    @Test
    public void thingLevelAuthRendersExactNestedRevokeGrantTree() {
        final RenderedSql r = render(GetFilterSqlVisitor.apply(fef.filterByAttribute("temp"),
                new CreateSqlPredicateVisitor(List.of()).visitEq(23), SUBJECTS));
        // /attributes/temp = 2 segments → 3 auth levels (leaf, attributes, root), leaf OUTERMOST (matches §3.3 golden).
        assertThat(r.sql()).isEqualTo(
                "(EXISTS (SELECT 1 FROM search_flat sf WHERE "
                        + "(sf.thing_id = st.thing_id AND sf.wpath = $1 AND sf.val_num = $2)) AND "
                        + "(NOT COALESCE((st.policy_auth #> $3::text[]) ?| $4::text[], false) AND "
                        + "(COALESCE((st.policy_auth #> $5::text[]) ?| $6::text[], false) OR "
                        + "(NOT COALESCE((st.policy_auth #> $7::text[]) ?| $8::text[], false) AND "
                        + "(COALESCE((st.policy_auth #> $9::text[]) ?| $10::text[], false) OR "
                        + "(NOT COALESCE((st.policy_auth #> $11::text[]) ?| $12::text[], false) AND "
                        + "COALESCE((st.policy_auth #> $13::text[]) ?| $14::text[], false)))))))");
        assertThat((String[]) r.binds().get(2).value()).containsExactly("attributes", "temp", R);
        assertThat((String[]) r.binds().get(4).value()).containsExactly("attributes", "temp", G);
        assertThat((String[]) r.binds().get(6).value()).containsExactly("attributes", R);
        assertThat((String[]) r.binds().get(8).value()).containsExactly("attributes", G);
        assertThat((String[]) r.binds().get(3).value()).containsExactly("s1", "s2"); // subjects
    }

    @Test
    public void revokeBelowGrantChainKeepsRevokeWinsAtEveryLevel() {
        // Adversarial: a 3-level path — every level has its own NOT revoke AND (grant OR parent). The structure is
        // uniform regardless of policy contents; correctness is that revoke is AND-ed (wins) at each level.
        final RenderedSql r = render(GetFilterSqlVisitor.apply(fef.filterByFeatureProperty("f1", "temp"),
                new CreateSqlPredicateVisitor(List.of()).visitEq(1), SUBJECTS));
        // 5 path levels: features / features.f1 / features.f1.properties / .temp + root → 5 revoke + 5 grant coalesces.
        final long revokes = countOccurrences(r.sql(), "NOT COALESCE(");
        final long grants = countOccurrences(r.sql(), "OR COALESCE(") + countOccurrences(r.sql(), "AND COALESCE(");
        assertThat(revokes).isEqualTo(5);
        assertThat(r.sql()).contains("st.policy_auth #>");
        // innermost bind (root grant array) is a bare [·g]; outermost is the full leaf path.
        assertThat((String[]) r.binds().get(2).value()).containsExactly("features", "f1", "properties", "temp", R);
    }

    // ============================================================= wildcard-feature scoping (§3.5 divergence example)

    @Test
    public void wildcardNeIsPerFeatureScopedWithAuthOnSameFeature() {
        // ne(features/*/x, 5): §3.5 per-f_id shape — EXISTS a (auth over a.f_id) ∧ NOT EXISTS b within the SAME feature.
        final RenderedSql r = render(GetFilterSqlVisitor.apply(fef.filterByFeatureProperty("*", "x"),
                new CreateSqlPredicateVisitor(List.of()).visitNe(5), SUBJECTS));
        assertThat(r.sql()).contains("FROM search_flat a WHERE");
        assertThat(r.sql()).contains("a.wpath = $1");
        assertThat(r.sql()).contains("jsonb_extract_path(st.features_auth, a.f_id");
        assertThat(r.sql()).contains("NOT EXISTS (SELECT 1 FROM search_flat b WHERE "
                + "(b.thing_id = a.thing_id AND b.f_id = a.f_id AND b.wpath = ");
        assertThat(r.bindValues().get(0)).isEqualTo("/features/*/properties/x");
    }

    @Test
    public void wildcardEqCouplesValueAndAuthToSameFeature() {
        final RenderedSql r = render(GetFilterSqlVisitor.apply(fef.filterByFeatureProperty("*", "temp"),
                new CreateSqlPredicateVisitor(List.of()).visitEq(9), SUBJECTS));
        assertThat(r.sql()).startsWith("EXISTS (SELECT 1 FROM search_flat a WHERE "
                + "(a.thing_id = st.thing_id AND a.wpath = $1 AND a.val_num = $2 AND ");
        assertThat(r.sql()).contains("jsonb_extract_path(st.features_auth, a.f_id");
        assertThat(r.bindValues().get(0)).isEqualTo("/features/*/properties/temp");
        assertThat(r.bindValues().get(1)).isEqualTo(BigDecimal.valueOf(9));
    }

    @Test
    public void wildcardAuthChainHasFixedFeaturesIdLevelsBeforeProperty() {
        // The fixed chain [<root>, /features, /id] precedes the property path (getFeatureWildcardAuthorizationBson).
        final RenderedSql r = render(GetExistsSqlVisitor.apply(fef.existsByFeatureProperty("*", "temp"), SUBJECTS));
        // extract-path arg lists (text binds) should include the fixed 'features' and 'id' segments and 'properties'.
        final List<Object> binds = r.bindValues();
        assertThat(binds).contains("features").contains("id").contains("properties").contains("temp");
        assertThat(binds).contains(G).contains(R);
    }

    // ================================================================================= global read (§3.3) sudo split

    @Test
    public void applyAddsGlobalReadSudoOmitsIt() {
        // root-mapped thingId keeps the bind numbering deterministic (no auth tree in front of global-read).
        final Criteria c = cf.fieldCriteria(fef.filterByThingId(), cf.eq("ns:t"));
        assertThat(render(CreateSqlVisitor.apply(c, SUBJECTS).predicate()).sql())
                .endsWith("AND st.global_read && $2::text[])");
        assertThat(sudo(c).sql()).doesNotContain("global_read");
        // with an auth-bearing field the global-read term is still the LAST conjunct (bind index shifts).
        final Criteria withAuth = cf.fieldCriteria(fef.filterByAttribute("x"), cf.eq(1));
        assertThat(render(CreateSqlVisitor.apply(withAuth, SUBJECTS).predicate()).sql())
                .contains("AND st.global_read && $");
    }

    // ====================================================================== RFC-6901 escaping parity with flattener

    @Test
    public void queryPathEscapingMatchesFlattenerByteForByte() {
        // A tilde in a real JSON key round-trips unambiguously (a literal '/' in a JsonKey is stored pre-encoded by
        // Ditto's JsonKey, so we cover the slash direction via the shared-utility identity assertion below instead).
        final String rawKey = "a~b";
        final JsonObject thing = JsonObject.of("{\"attributes\":{\"a~b\":1}}");
        final String flattenerWpath = ThingFlattener.flatten("ns:t", thing).stream()
                .map(FlatRow::wpath)
                .filter(w -> w.startsWith("/attributes/"))
                .findFirst()
                .orElseThrow();

        // Translator side: the RQL field carries the RFC-6901-escaped segment; the visitor decodes then re-escapes.
        final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute(JsonPointerSegments.escape(rawKey)), cf.eq(1)));

        assertThat(r.bindValues().get(0)).isEqualTo(flattenerWpath);
        assertThat(flattenerWpath).isEqualTo("/attributes/a~0b");
    }

    @Test
    public void escapingUtilityIsLiterallySharedWithTheFlattener() {
        // The relocation obligation: the flattener now DELEGATES to JsonPointerSegments, so query-path escaping and
        // flat-column escaping are byte-for-byte the same code — including the slash direction and the order trap.
        for (final String raw : List.of("plain", "a~b", "a/b", "a/b~c", "~1", "~0", "a~/b")) {
            assertThat(ThingFlattener.escapeJsonPointerSegment(raw)).isEqualTo(JsonPointerSegments.escape(raw));
        }
        assertThat(JsonPointerSegments.escape("a/b~c")).isEqualTo("a~1b~0c");
    }

    // ================================================================================ hostile strings reach only binds

    @Test
    public void hostileStringsInPathsAndValuesAreBindsNeverSqlText() {
        for (final String hostile : List.of("'; DROP TABLE search_things;--", "\") OR 1=1 --", "x'y")) {
            final RenderedSql r = sudo(cf.fieldCriteria(fef.filterByAttribute("k"), cf.eq(hostile)));
            assertThat(r.sql()).doesNotContain(hostile);
            assertThat(r.bindValues()).contains(hostile);
        }
    }

    // ========================================================================================================= helpers

    private RenderedSql sudo(final Criteria criteria) {
        return render(CreateSqlVisitor.sudoApply(criteria).predicate());
    }

    private static RenderedSql render(final org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.SqlNode node) {
        return Sql.render(node);
    }

    private static long countOccurrences(final String haystack, final String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
