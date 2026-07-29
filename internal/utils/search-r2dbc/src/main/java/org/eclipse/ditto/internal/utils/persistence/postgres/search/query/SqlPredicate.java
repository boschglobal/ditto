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

import java.util.function.Function;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.SqlExpression;

/**
 * The SQL counterpart of Mongo's {@code Function<String, Bson>} predicate closure (CreateBsonPredicateVisitor): a
 * resolved comparison the field visitors turn into a flat-table probe or a doc-row column condition.
 * <p>
 * It carries two renderings because Postgres has two field worlds (unlike Mongo's uniform dotted paths):
 * <ul>
 *   <li>{@link #flatCondition(String)} — the value condition on a {@code search_flat} row alias's typed value columns,
 *       used inside an {@code EXISTS} probe over a {@code wpath};</li>
 *   <li>{@link #columnCondition(SqlExpression)} — the condition on a doc-row column, used for the two ROOT-mapped
 *       system fields ({@code _id}/{@code thing_id}, {@code _namespace}/{@code namespace}) which have no flat probe and
 *       no per-field auth (§3.3 exception 1).</li>
 * </ul>
 * {@link #scopedNegation()} marks {@code ne(...)}: Mongo emits {@code and(ne, exists)} (multikey "no element equals"),
 * which the flat/wildcard field visitors realize as {@code EXISTS(wpath) ∧ NOT EXISTS(wpath ∧ value=)} — so
 * {@link #flatCondition(String)} of a {@code ne} predicate is the equality that gets NEGATED, not the match itself.
 * For a doc-row column {@link #columnCondition(SqlExpression)} already encodes the full {@code <>} directly.
 */
final class SqlPredicate {

    private final Function<String, SqlExpression> flatCondition;
    private final Function<SqlExpression, SqlExpression> columnCondition;
    private final boolean scopedNegation;

    SqlPredicate(final Function<String, SqlExpression> flatCondition,
            final Function<SqlExpression, SqlExpression> columnCondition,
            final boolean scopedNegation) {
        this.flatCondition = flatCondition;
        this.columnCondition = columnCondition;
        this.scopedNegation = scopedNegation;
    }

    /**
     * @param flatAlias the alias of the {@code search_flat} row the value columns belong to.
     * @return the value condition on that row (the positive match, or the equality-to-negate for {@code ne}).
     */
    SqlExpression flatCondition(final String flatAlias) {
        return flatCondition.apply(flatAlias);
    }

    /**
     * @param column the doc-row column expression.
     * @return the full condition on that column (already includes {@code <>} / {@code IS NULL} where applicable).
     */
    SqlExpression columnCondition(final SqlExpression column) {
        return columnCondition.apply(column);
    }

    /**
     * @return {@code true} for {@code ne(...)} — the flat/wildcard probe must be {@code EXISTS ∧ NOT EXISTS(value=)}.
     */
    boolean scopedNegation() {
        return scopedNegation;
    }
}
