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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.eclipse.ditto.internal.utils.persistence.postgres.search.flatten.ThingFlattener;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.ComparisonOperator;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.Sql;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.query.ast.SqlExpression;
import org.eclipse.ditto.placeholders.PlaceholderResolver;
import org.eclipse.ditto.rql.model.ParsedPlaceholder;
import org.eclipse.ditto.rql.query.criteria.visitors.PredicateVisitor;

/**
 * The SQL counterpart of {@code CreateBsonPredicateVisitor} (thingsearch/service): turns an RQL {@code Predicate} into a
 * {@link SqlPredicate}. Value resolution mirrors {@code CreateBsonPredicateVisitor.resolveValue} exactly (the
 * {@code TimePlaceholder} chain, §3.4/§3.5) — a {@code ParsedPlaceholder} is resolved to its concrete value BEFORE it
 * ever becomes a bind. The collation used by string comparisons is the flat column's default {@code COLLATE "C"}.
 * <p>
 * <b>Side-by-side comment map (Mongo class:line → this method):</b>
 * <ul>
 *   <li>{@code visitEq:101-108} (incl. the {@code eq(null)} = {@code and(eq(null), exists)} special) → {@link #visitEq}</li>
 *   <li>{@code visitGe/Gt/Le/Lt:110-171} → {@link #visitGe}/{@link #visitGt}/{@link #visitLe}/{@link #visitLt}</li>
 *   <li>{@code visitNe:173-176} ({@code and(ne, exists)}, incl. the {@code ne(null)} null special-case mirroring
 *       {@code visitEq}'s) → {@link #visitNe}</li>
 *   <li>{@code visitIn:120-123} → {@link #visitIn}</li>
 *   <li>{@code visitLike/visitILike:130-151} — regex path bypassed; the wildcard overrides build native LIKE
 *       (A6 {@code visitLikeWithWildcards}) → {@link #visitLikeWithWildcards}/{@link #visitILikeWithWildcards}</li>
 *   <li>{@code resolveValue:178-191} → {@link #resolveValue}</li>
 * </ul>
 */
final class CreateSqlPredicateVisitor implements PredicateVisitor<SqlPredicate> {

    private final List<PlaceholderResolver<?>> additionalPlaceholderResolvers;

    CreateSqlPredicateVisitor(final Collection<PlaceholderResolver<?>> additionalPlaceholderResolvers) {
        this.additionalPlaceholderResolvers = List.copyOf(additionalPlaceholderResolvers);
    }

    @Override
    public SqlPredicate visitEq(@Nullable final Object value) {
        if (value == null) {
            // eq(null): a null-rank (type_rank = TYPE_NULL) flat row exists — "missing" does NOT match (Mongo adds
            // exists() to disambiguate; on the flat table a null-rank row *is* an existing row). Column: IS NULL.
            return new SqlPredicate(
                    alias -> Sql.eq(Sql.col(alias, "type_rank"), Sql.integer(ThingFlattener.TYPE_NULL)),
                    Sql::isNull,
                    false);
        }
        final Object resolved = resolveValue(value);
        return comparison(ComparisonOperator.EQ, resolved);
    }

    @Override
    public SqlPredicate visitGe(@Nullable final Object value) {
        return comparison(ComparisonOperator.GE, resolveValue(value));
    }

    @Override
    public SqlPredicate visitGt(@Nullable final Object value) {
        return comparison(ComparisonOperator.GT, resolveValue(value));
    }

    @Override
    public SqlPredicate visitLe(@Nullable final Object value) {
        return comparison(ComparisonOperator.LE, resolveValue(value));
    }

    @Override
    public SqlPredicate visitLt(@Nullable final Object value) {
        return comparison(ComparisonOperator.LT, resolveValue(value));
    }

    @Override
    public SqlPredicate visitNe(@Nullable final Object value) {
        if (value == null) {
            // ne(null): Mongo parity -- must match ONLY a leaf that EXISTS with a NON-null value (a null-rank row,
            // like "missing", is excluded). Mirrors visitEq's null special-case: negate the SAME
            // type_rank = TYPE_NULL equality instead of falling into the generic bind path below, where
            // SqlValues.bind(null) would render the TEXT literal 'null' (String.valueOf(null)) -- never equal to a
            // null-rank row's SQL-NULL val_text column, so the NOT-EXISTS probe would be vacuously true and the
            // predicate would silently degenerate to a bare exists(). Column form: IS NOT NULL (the root-mapped
            // columns this can reach, thing_id/namespace, are never actually null).
            return new SqlPredicate(
                    alias -> Sql.eq(Sql.col(alias, "type_rank"), Sql.integer(ThingFlattener.TYPE_NULL)),
                    Sql::isNotNull,
                    true);
        }
        final Object resolved = resolveValue(value);
        // ne = and(ne, exists) (Mongo, multikey "no element equals"): flatCondition is the equality that gets NEGATED
        // by the field visitor (EXISTS(wpath) ∧ NOT EXISTS(wpath ∧ value=)); column condition is a direct <>.
        final String valueColumn = SqlValues.valueColumn(resolved);
        return new SqlPredicate(
                alias -> Sql.eq(Sql.col(alias, valueColumn), SqlValues.bind(resolved)),
                column -> Sql.ne(column, Sql.text(String.valueOf(resolved))),
                true);
    }

    @Override
    public SqlPredicate visitIn(final List<?> values) {
        final List<Object> resolved = new ArrayList<>(values.size());
        for (final Object v : values) {
            resolved.add(resolveValue(v));
        }
        return new SqlPredicate(
                alias -> inFlatCondition(alias, resolved),
                column -> Sql.eqAny(column, Sql.textArray(toStringList(resolved))),
                false);
    }

    @Override
    public SqlPredicate visitLike(@Nullable final String value) {
        // Bypassed in production: LikePredicateImpl.accept calls visitLikeWithWildcards, which this visitor overrides to
        // build a native SQL LIKE without the intermediate Java regex. Regex→LIKE is not expressible, so reaching here
        // means a caller invoked visitLike directly, which the Postgres translator does not support.
        throw new UnsupportedOperationException(
                "CreateSqlPredicateVisitor supports visitLikeWithWildcards (native LIKE), not the regex visitLike");
    }

    @Override
    public SqlPredicate visitILike(@Nullable final String value) {
        throw new UnsupportedOperationException(
                "CreateSqlPredicateVisitor supports visitILikeWithWildcards (native ILIKE), not the regex visitILike");
    }

    @Override
    public SqlPredicate visitLikeWithWildcards(@Nullable final String wildcardExpression) {
        final String pattern = toSqlLikePattern(wildcardExpression);
        return new SqlPredicate(
                alias -> Sql.like(Sql.col(alias, SqlValues.VAL_TEXT), Sql.text(pattern)),
                column -> Sql.like(column, Sql.text(pattern)),
                false);
    }

    @Override
    public SqlPredicate visitILikeWithWildcards(@Nullable final String wildcardExpression) {
        final String pattern = toSqlLikePattern(wildcardExpression);
        return new SqlPredicate(
                alias -> Sql.ilike(Sql.col(alias, SqlValues.VAL_TEXT), Sql.text(pattern), "C.utf8"),
                column -> Sql.ilike(column, Sql.text(pattern), "C.utf8"),
                false);
    }

    private SqlPredicate comparison(final ComparisonOperator operator, final Object resolved) {
        final String valueColumn = SqlValues.valueColumn(resolved);
        return new SqlPredicate(
                alias -> Sql.compare(Sql.col(alias, valueColumn), operator, SqlValues.bind(resolved)),
                column -> Sql.compare(column, operator, Sql.text(String.valueOf(resolved))),
                false);
    }

    private static SqlExpression inFlatCondition(final String alias, final List<Object> resolved) {
        final List<BigDecimal> numbers = new ArrayList<>();
        final List<Boolean> booleans = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (final Object v : resolved) {
            if (v instanceof Boolean b) {
                booleans.add(b);
            } else if (v instanceof Number n) {
                numbers.add(SqlValues.toBigDecimal(n));
            } else {
                texts.add(String.valueOf(v));
            }
        }
        final List<SqlExpression> groups = new ArrayList<>();
        if (!numbers.isEmpty()) {
            groups.add(Sql.eqAny(Sql.col(alias, SqlValues.VAL_NUM), Sql.numericArray(numbers)));
        }
        if (!texts.isEmpty()) {
            groups.add(Sql.eqAny(Sql.col(alias, SqlValues.VAL_TEXT), Sql.textArray(texts)));
        }
        if (!booleans.isEmpty()) {
            groups.add(Sql.eqAny(Sql.col(alias, SqlValues.VAL_BOOL), Sql.boolArray(booleans)));
        }
        return groups.size() == 1 ? groups.get(0) : Sql.or(groups);
    }

    private static List<String> toStringList(final List<Object> resolved) {
        final List<String> strings = new ArrayList<>(resolved.size());
        for (final Object v : resolved) {
            strings.add(String.valueOf(v));
        }
        return strings;
    }

    /**
     * Converts an RQL wildcard expression ({@code *} / {@code ?}) to a SQL LIKE pattern with {@code ESCAPE '\'}:
     * {@code *} → {@code %}, {@code ?} → {@code _}, and a literal {@code %}/{@code _}/{@code \} is backslash-escaped.
     */
    @Nullable
    private static String toSqlLikePattern(@Nullable final String wildcardExpression) {
        if (wildcardExpression == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(wildcardExpression.length() + 4);
        for (int i = 0; i < wildcardExpression.length(); i++) {
            final char c = wildcardExpression.charAt(i);
            switch (c) {
                case '*' -> sb.append('%');
                case '?' -> sb.append('_');
                case '%', '_', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Resolves a {@link ParsedPlaceholder} against the additional resolvers (e.g. {@code time:now}); passes any other
     * value through unchanged — a 1:1 transcription of {@code CreateBsonPredicateVisitor.resolveValue}.
     */
    @Nullable
    private Object resolveValue(@Nullable final Object value) {
        if (value instanceof ParsedPlaceholder parsed) {
            final String prefix = parsed.getPrefix();
            final String name = parsed.getName();
            return additionalPlaceholderResolvers.stream()
                    .filter(pr -> prefix.equals(pr.getPrefix()))
                    .filter(pr -> pr.supports(name))
                    .flatMap(pr -> pr.resolveValues(name).stream())
                    .findFirst()
                    .orElse(null);
        }
        return value;
    }
}
