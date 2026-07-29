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

import java.util.List;

import javax.annotation.Nullable;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.Select;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.Sql;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.SqlExpression;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.rql.query.expression.FilterFieldExpression;
import org.eclipse.ditto.rql.query.expression.visitors.FilterFieldExpressionVisitor;

/**
 * The SQL counterpart of {@code GetFilterBsonVisitor} (thingsearch/service): turns a {@code FilterFieldExpression} +
 * resolved {@link SqlPredicate} into the AST boolean condition over {@code search_things st}.
 * <p>
 * <b>Side-by-side comment map (Mongo → here):</b>
 * <ul>
 *   <li>{@code visitAttribute:79-81} → {@link #visitAttribute}</li>
 *   <li>{@code visitFeatureDefinition:84-90}/{@code visitFeatureIdProperty:93-99}/
 *       {@code visitFeatureIdDesiredProperty:102-108} (wildcard {@code *} → {@code matchWildcardFeatureValue}) →
 *       {@link #visitFeatureDefinition}/{@link #visitFeatureIdProperty}/{@link #visitFeatureIdDesiredProperty}</li>
 *   <li>{@code visitMetadata:111-113} → {@link #visitMetadata}; {@code visitPointer:116-118} → {@link #visitPointer}</li>
 *   <li>{@code visitRootLevelField:121-123} (predicate on the field, NO auth — §3.3 exception 1) →
 *       {@link #visitRootLevelField}</li>
 *   <li>{@code matchValue:125-131} ({@code and(keyValueFilter, authBson)}) → {@link #matchValue}</li>
 *   <li>{@code matchWildcardFeatureValue:133-139} ({@code elemMatch(f, and(keyValueFilter, authBson))}, per-f_id) →
 *       {@link #matchWildcardFeatureValue}</li>
 * </ul>
 */
final class GetFilterSqlVisitor extends AbstractFieldSqlCreator implements FilterFieldExpressionVisitor<SqlExpression> {

    private static final String FEATURE_ID_WILDCARD = "*";
    private static final String WILDCARD_ALIAS = "a";
    private static final String WILDCARD_NEG_ALIAS = "b";
    private static final String THING_ALIAS = "sf";

    private final SqlPredicate predicate;

    private GetFilterSqlVisitor(final SqlPredicate predicate, @Nullable final List<String> authorizationSubjectIds) {
        super(authorizationSubjectIds);
        this.predicate = predicate;
    }

    static SqlExpression apply(final FilterFieldExpression expression, final SqlPredicate predicate,
            @Nullable final List<String> authorizationSubjectIds) {
        return expression.acceptFilterVisitor(new GetFilterSqlVisitor(predicate, authorizationSubjectIds));
    }

    @Override
    public SqlExpression visitAttribute(final String key) {
        return matchValue(JsonPointer.of("/attributes/" + key));
    }

    @Override
    public SqlExpression visitFeatureDefinition(final String featureId) {
        if (isWildcard(featureId)) {
            return matchWildcardFeatureValue(JsonPointer.of("definition"));
        }
        return matchValue(JsonPointer.of("/features/" + featureId + "/definition"));
    }

    @Override
    public SqlExpression visitFeatureIdProperty(final String featureId, final String property) {
        if (isWildcard(featureId)) {
            return matchWildcardFeatureValue(JsonPointer.of("/properties/" + property));
        }
        return matchValue(JsonPointer.of("/features/" + featureId + "/properties/" + property));
    }

    @Override
    public SqlExpression visitFeatureIdDesiredProperty(final CharSequence featureId, final CharSequence desiredProperty) {
        if (isWildcard(featureId)) {
            return matchWildcardFeatureValue(JsonPointer.of("/desiredProperties/" + desiredProperty));
        }
        return matchValue(JsonPointer.of("/features/" + featureId + "/desiredProperties/" + desiredProperty));
    }

    @Override
    public SqlExpression visitMetadata(final String key) {
        return matchValue(JsonPointer.of("/_metadata/" + key));
    }

    @Override
    public SqlExpression visitPointer(final String pointer) {
        return matchValue(JsonPointer.of(pointer));
    }

    @Override
    public SqlExpression visitRootLevelField(final String fieldName) {
        // §3.3 exception 1: root-mapped columns carry NO per-field auth; the predicate is applied straight to the column.
        final SqlExpression column = rootColumn(fieldName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown root-level search field: " + fieldName));
        return predicate.columnCondition(column);
    }

    // ---- thing-level (Mongo matchValue) ---------------------------------------------------------------------------

    private SqlExpression matchValue(final JsonPointer pointer) {
        final String wpath = wpathOf(pointer);
        final SqlExpression core;
        if (predicate.scopedNegation()) {
            // ne = and(ne, exists): the leaf must exist AND no leaf equals the value (multikey parity).
            final SqlExpression exists = Sql.exists(flatProbe(THING_ALIAS, wpath));
            final SqlExpression noneEqual =
                    Sql.notExists(flatProbe(THING_ALIAS, wpath, predicate.flatCondition(THING_ALIAS)));
            core = Sql.and(exists, noneEqual);
        } else {
            core = Sql.exists(flatProbe(THING_ALIAS, wpath, predicate.flatCondition(THING_ALIAS)));
        }
        return thingLevelAuth(pointer).map(authFilter -> Sql.and(core, authFilter)).orElse(core);
    }

    // ---- wildcard feature (Mongo matchWildcardFeatureValue → elemMatch, per-f_id scoped §3.5) ----------------------

    private SqlExpression matchWildcardFeatureValue(final JsonPointer propertyPointer) {
        final String wpath = wildcardWpath(propertyPointer);
        if (predicate.scopedNegation()) {
            // Per-f_id scoped ne (§3.5): EXISTS a (auth on a.f_id) ∧ NOT EXISTS b within the SAME feature (b.f_id=a.f_id).
            final Select inner = Sql.select().column(Sql.raw("1")).from(FLAT_TABLE, WILDCARD_NEG_ALIAS)
                    .where(Sql.and(
                            Sql.eq(Sql.col(WILDCARD_NEG_ALIAS, THING_ID), Sql.col(WILDCARD_ALIAS, THING_ID)),
                            Sql.eq(Sql.col(WILDCARD_NEG_ALIAS, F_ID), Sql.col(WILDCARD_ALIAS, F_ID)),
                            Sql.eq(Sql.col(WILDCARD_NEG_ALIAS, WPATH), Sql.text(wpath)),
                            predicate.flatCondition(WILDCARD_NEG_ALIAS)))
                    .build();
            final List<SqlExpression> outerConds = new java.util.ArrayList<>();
            outerConds.add(Sql.eq(Sql.col(WILDCARD_ALIAS, THING_ID), Sql.col(DOC_ALIAS, THING_ID)));
            outerConds.add(Sql.eq(Sql.col(WILDCARD_ALIAS, WPATH), Sql.text(wpath)));
            wildcardFeatureAuth(WILDCARD_ALIAS, propertyPointer).ifPresent(outerConds::add);
            outerConds.add(Sql.notExists(inner));
            return Sql.exists(Sql.select().column(Sql.raw("1")).from(FLAT_TABLE, WILDCARD_ALIAS)
                    .where(Sql.and(outerConds)).build());
        }
        // Positive wildcard predicate: value + auth coupled to the SAME feature row (a.f_id).
        final List<SqlExpression> conds = new java.util.ArrayList<>();
        conds.add(Sql.eq(Sql.col(WILDCARD_ALIAS, THING_ID), Sql.col(DOC_ALIAS, THING_ID)));
        conds.add(Sql.eq(Sql.col(WILDCARD_ALIAS, WPATH), Sql.text(wpath)));
        conds.add(predicate.flatCondition(WILDCARD_ALIAS));
        wildcardFeatureAuth(WILDCARD_ALIAS, propertyPointer).ifPresent(conds::add);
        return Sql.exists(Sql.select().column(Sql.raw("1")).from(FLAT_TABLE, WILDCARD_ALIAS)
                .where(Sql.and(conds)).build());
    }

    private static boolean isWildcard(final CharSequence featureId) {
        return FEATURE_ID_WILDCARD.contentEquals(featureId);
    }
}
