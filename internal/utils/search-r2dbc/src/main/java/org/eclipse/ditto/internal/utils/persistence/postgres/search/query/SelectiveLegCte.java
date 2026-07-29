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
import java.util.Optional;

import javax.annotation.Nullable;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.Select;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.Sql;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.placeholders.PlaceholderFactory;
import org.eclipse.ditto.placeholders.TimePlaceholder;
import org.eclipse.ditto.rql.query.criteria.Criteria;
import org.eclipse.ditto.rql.query.criteria.Predicate;
import org.eclipse.ditto.rql.query.criteria.visitors.CriteriaVisitor;
import org.eclipse.ditto.rql.query.criteria.visitors.PredicateVisitor;
import org.eclipse.ditto.rql.query.expression.ExistsFieldExpression;
import org.eclipse.ditto.rql.query.expression.FilterFieldExpression;
import org.eclipse.ditto.rql.query.expression.visitors.FilterFieldExpressionVisitor;

/**
 * The Task&nbsp;D4 <em>selective-leg rescue CTE</em> heuristic (plan §3.5, evidenced by Task&nbsp;0.2b's read-path
 * mitigation benches). A search {@code WHERE} is a chain of correlated {@code EXISTS} probes over {@code search_flat};
 * with a generic plan the planner can pick a poor drive order and re-scan {@code search_things}. When the criteria has
 * one <em>clearly selective</em> conjunct — a top-level {@code eq(non-null)} or {@code in(...)} on a flattened
 * (attribute / non-wildcard feature) path — staging exactly that leg as a {@code WITH … AS MATERIALIZED} thing-id set,
 * computed once from the {@code sf_num}/{@code sf_text}/{@code sf_bool} indexes, then semi-joining {@code search_things}
 * to it, pins the drive order to the selective index scan.
 * <p>
 * <b>Correctness invariant.</b> The CTE is a pure <em>superset pre-filter</em>: the full translated predicate (auth
 * rechecks included) is still applied unchanged in the main {@code WHERE}, so the CTE can only ever narrow the driven
 * rows, never change the result set. This is why a leg is extracted ONLY when it is a top-level {@code AND} conjunct
 * (or the sole criterion): a leg inside an {@code OR}/{@code NOR} is NOT a superset of the result and must never be
 * hoisted (doing so would wrongly drop rows satisfying the other disjunct). {@link #visitOr}/{@link #visitNor} therefore
 * return empty, and {@link #visitAnd} takes the first qualifying conjunct.
 * </p>
 * <p>
 * <b>Which legs qualify (kept deliberately simple).</b> Only {@code eq(non-null)} and {@code in(non-empty)} — the two
 * point-lookup predicates the composite {@code (wpath, val_*)} B-trees serve with high selectivity. Ranges
 * ({@code gt}/{@code lt}), {@code ne}, {@code like}/{@code ilike}, {@code eq(null)}, {@code exists}/{@code empty}, and
 * every root-mapped or wildcard-feature field are left to the plain {@code EXISTS} chain (empty prelude). One CTE at
 * most per query (the first qualifying conjunct); the rest of the predicate stays inline.
 * </p>
 */
final class SelectiveLegCte {

    /** The alias of the {@code search_flat} row the CTE body scans (distinct from the inline probe aliases). */
    private static final String CTE_FLAT_ALIAS = "sfc";

    private static final TimePlaceholder TIME_PLACEHOLDER = TimePlaceholder.getInstance();

    private SelectiveLegCte() {
        throw new AssertionError("no instances");
    }

    /**
     * @param criteria the (already thing-id-truncated) query criteria.
     * @return the CTE body {@code SELECT sfc.thing_id FROM search_flat sfc WHERE sfc.wpath = $ AND <value>} for the
     * first qualifying selective leg, or empty when no leg qualifies (⇒ plain {@code EXISTS} chain).
     */
    static Optional<Select> extract(final Criteria criteria) {
        return criteria.accept(new Visitor());
    }

    private static final class Visitor implements CriteriaVisitor<Optional<Select>> {

        @Override
        public Optional<Select> visitAny() {
            return Optional.empty();
        }

        @Override
        public Optional<Select> visitExists(final ExistsFieldExpression fieldExpression) {
            return Optional.empty();
        }

        @Override
        public Optional<Select> visitEmpty(final ExistsFieldExpression fieldExpression) {
            return Optional.empty();
        }

        @Override
        public Optional<Select> visitField(final FilterFieldExpression fieldExpression, final Predicate predicate) {
            if (!Boolean.TRUE.equals(predicate.accept(SelectivePredicateProbe.INSTANCE))) {
                return Optional.empty();
            }
            final Optional<String> wpath = fieldExpression.acceptFilterVisitor(FlatWpathVisitor.INSTANCE);
            if (wpath.isEmpty()) {
                return Optional.empty();
            }
            final SqlPredicate sqlPredicate = predicate.accept(new CreateSqlPredicateVisitor(
                    List.of(PlaceholderFactory.newPlaceholderResolver(TIME_PLACEHOLDER, new Object()))));
            // eq/in never scope-negate; the guard documents the invariant the probe already enforces.
            if (sqlPredicate.scopedNegation()) {
                return Optional.empty();
            }
            final Select cteBody = Sql.select()
                    .column(Sql.col(CTE_FLAT_ALIAS, AbstractFieldSqlCreator.THING_ID))
                    .from(AbstractFieldSqlCreator.FLAT_TABLE, CTE_FLAT_ALIAS)
                    .where(Sql.and(
                            Sql.eq(Sql.col(CTE_FLAT_ALIAS, AbstractFieldSqlCreator.WPATH), Sql.text(wpath.get())),
                            sqlPredicate.flatCondition(CTE_FLAT_ALIAS)))
                    .build();
            return Optional.of(cteBody);
        }

        @Override
        public Optional<Select> visitAnd(final List<Optional<Select>> conjuncts) {
            return conjuncts.stream().filter(Optional::isPresent).findFirst().orElse(Optional.empty());
        }

        @Override
        public Optional<Select> visitOr(final List<Optional<Select>> disjoints) {
            // A leg inside an OR is NOT a superset of the result set — never hoist it (correctness).
            return Optional.empty();
        }

        @Override
        public Optional<Select> visitNor(final List<Optional<Select>> negativeDisjoints) {
            return Optional.empty();
        }
    }

    /** Recognizes the two point-lookup predicates a {@code (wpath, val_*)} B-tree serves selectively: eq(non-null)/in. */
    private static final class SelectivePredicateProbe implements PredicateVisitor<Boolean> {

        private static final SelectivePredicateProbe INSTANCE = new SelectivePredicateProbe();

        @Override
        public Boolean visitEq(@Nullable final Object value) {
            return value != null;
        }

        @Override
        public Boolean visitIn(final List<?> values) {
            return !values.isEmpty();
        }

        @Override
        public Boolean visitNe(@Nullable final Object value) {
            return false;
        }

        @Override
        public Boolean visitGe(@Nullable final Object value) {
            return false;
        }

        @Override
        public Boolean visitGt(@Nullable final Object value) {
            return false;
        }

        @Override
        public Boolean visitLe(@Nullable final Object value) {
            return false;
        }

        @Override
        public Boolean visitLt(@Nullable final Object value) {
            return false;
        }

        @Override
        public Boolean visitLike(@Nullable final String value) {
            return false;
        }

        @Override
        public Boolean visitILike(@Nullable final String value) {
            return false;
        }

        @Override
        public Boolean visitLikeWithWildcards(@Nullable final String value) {
            return false;
        }

        @Override
        public Boolean visitILikeWithWildcards(@Nullable final String value) {
            return false;
        }
    }

    /**
     * Resolves a filter field expression to its flattened {@code wpath}, or empty for a field that has no single
     * selective flat leg — a root-mapped system column ({@code _id}/{@code _namespace}), or a wildcard feature path
     * ({@code features/*}), which spans a whole row family and is not a point lookup. Mirrors
     * {@link GetFilterSqlVisitor}'s pointer construction.
     */
    private static final class FlatWpathVisitor implements FilterFieldExpressionVisitor<Optional<String>> {

        private static final FlatWpathVisitor INSTANCE = new FlatWpathVisitor();
        private static final String FEATURE_ID_WILDCARD = "*";

        @Override
        public Optional<String> visitAttribute(final String key) {
            return Optional.of(AbstractFieldSqlCreator.wpathOf(JsonPointer.of("/attributes/" + key)));
        }

        @Override
        public Optional<String> visitFeatureDefinition(final String featureId) {
            return isWildcard(featureId) ? Optional.empty()
                    : Optional.of(AbstractFieldSqlCreator.wpathOf(
                            JsonPointer.of("/features/" + featureId + "/definition")));
        }

        @Override
        public Optional<String> visitFeatureIdProperty(final String featureId, final String property) {
            return isWildcard(featureId) ? Optional.empty()
                    : Optional.of(AbstractFieldSqlCreator.wpathOf(
                            JsonPointer.of("/features/" + featureId + "/properties/" + property)));
        }

        @Override
        public Optional<String> visitFeatureIdDesiredProperty(final CharSequence featureId,
                final CharSequence desiredProperty) {
            return isWildcard(featureId) ? Optional.empty()
                    : Optional.of(AbstractFieldSqlCreator.wpathOf(
                            JsonPointer.of("/features/" + featureId + "/desiredProperties/" + desiredProperty)));
        }

        @Override
        public Optional<String> visitMetadata(final String key) {
            return Optional.of(AbstractFieldSqlCreator.wpathOf(JsonPointer.of("/_metadata/" + key)));
        }

        @Override
        public Optional<String> visitSimple(final String fieldName) {
            // Slash-mapped ⇒ a real flat leg; root-mapped (no leading slash) ⇒ a doc column, no selective flat leg.
            return fieldName.startsWith("/")
                    ? Optional.of(AbstractFieldSqlCreator.wpathOf(JsonPointer.of(fieldName)))
                    : Optional.empty();
        }

        private static boolean isWildcard(final CharSequence featureId) {
            return FEATURE_ID_WILDCARD.contentEquals(featureId);
        }
    }
}
