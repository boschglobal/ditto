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
package org.eclipse.ditto.internal.utils.persistence.postgres.client.schema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bootstraps a component's PostgreSQL schema programmatically, driven by a {@link PostgresSchemaDescriptor}.
 * <p>
 * The manager is backend-agnostic: it owns everything shared across every Postgres-backed component — the
 * {@code schema_version} bookkeeping table ({@link #SCHEMA_VERSION_DDL}) and the cluster-wide first-boot advisory lock
 * ({@link #ADVISORY_LOCK_KEY}) — and applies them for whichever descriptor it is given. The descriptor supplies only its
 * own DDL statements, table contracts, component name and version.
 * </p>
 * <p>
 * The whole bootstrap is a <strong>single transaction</strong>:
 * </p>
 * <ol>
 *     <li>{@code pg_advisory_xact_lock(<constant>)} — a transaction-scoped advisory lock (NOT session
 *     {@code pg_advisory_lock}, which is unsafe over a pooled connection because the lock could be acquired and
 *     released on different physical connections); auto-released on commit. The key is SHARED across every component so
 *     concurrent first-boot DDL against one fresh database is serialised.</li>
 *     <li>the shared {@code schema_version} table plus all of the descriptor's {@code CREATE TABLE}/{@code CREATE
 *     INDEX}/{@code ALTER TABLE … autovacuum} statements (each {@code IF NOT EXISTS}).</li>
 *     <li><strong>live-catalog verification</strong> — {@code pg_get_constraintdef} on every PK +
 *     {@code information_schema.columns} per table; refuse-to-boot on any divergence (because
 *     {@code CREATE TABLE IF NOT EXISTS} silently keeps a pre-existing divergent table).</li>
 *     <li>{@code schema_version} checksum upsert; refuse-to-boot on a stored-vs-code mismatch (scoped by component).</li>
 *     <li>commit — the advisory xact lock auto-releases.</li>
 * </ol>
 * <p>
 * There is deliberately no {@code SELECT indisvalid} rebuild step: invalid indexes only arise from {@code CREATE INDEX
 * CONCURRENTLY}, which is never used here and cannot run in a transaction; plain {@code CREATE INDEX} rolls back
 * cleanly. The bootstrap blocks the calling (startup) thread — it is a one-time step run before any actor starts.
 * </p>
 * <h2>Checksum semantics</h2>
 * The {@code schema_version} checksum is computed over the <em>effective</em> DDL — the shared {@code schema_version}
 * table statement followed by the descriptor's own statements ({@link #effectiveDdl(PostgresSchemaDescriptor)}), i.e.
 * exactly the statement set the bootstrap executes — so the checksum can never drift away from what is actually run.
 */
public final class PostgresSchemaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresSchemaManager.class);

    /**
     * The constant advisory-lock key. Arbitrary but fixed; serialises concurrent first-boot DDL across the cluster.
     * ("DITTO" in a mnemonic encoding.) SHARED across every component so a single fresh database's first-boot DDL is
     * serialised regardless of which component bootstraps first.
     */
    static final long ADVISORY_LOCK_KEY = 0x0D17_70_5C_4E_4AL;

    /**
     * The single, shared {@code schema_version(component, version, checksum)} table DDL, owned by the manager and
     * prepended to every descriptor's own statements. Each component owns one row (keyed by {@code component}) that
     * backs the refuse-to-boot checksum/version guard.
     */
    public static final String SCHEMA_VERSION_DDL = "CREATE TABLE IF NOT EXISTS schema_version ("
            + "component TEXT PRIMARY KEY, "
            + "version INT NOT NULL, "
            + "checksum TEXT NOT NULL"
            + ")";

    private static final Duration DEFAULT_BOOTSTRAP_TIMEOUT = Duration.ofSeconds(60L);

    private final ConnectionFactory ddlConnectionFactory;
    private final PostgresSchemaDescriptor descriptor;
    private final Duration bootstrapTimeout;

    private PostgresSchemaManager(final ConnectionFactory ddlConnectionFactory,
            final PostgresSchemaDescriptor descriptor, final Duration bootstrapTimeout) {
        this.ddlConnectionFactory = ddlConnectionFactory;
        this.descriptor = descriptor;
        this.bootstrapTimeout = bootstrapTimeout;
    }

    /**
     * Creates a schema manager that runs the given descriptor's DDL through the given (DDL-role) connection factory.
     *
     * @param ddlConnectionFactory a connection factory authenticated as the schema-owning DDL role.
     * @param descriptor the component schema to bootstrap.
     * @return the manager.
     */
    public static PostgresSchemaManager of(final ConnectionFactory ddlConnectionFactory,
            final PostgresSchemaDescriptor descriptor) {
        return new PostgresSchemaManager(ddlConnectionFactory,
                Objects.requireNonNull(descriptor, "descriptor"), DEFAULT_BOOTSTRAP_TIMEOUT);
    }

    /**
     * Creates a schema manager with an explicit bootstrap timeout (for tests).
     *
     * @param ddlConnectionFactory a connection factory authenticated as the schema-owning DDL role.
     * @param descriptor the component schema to bootstrap.
     * @param bootstrapTimeout how long to wait for the bootstrap transaction to complete.
     * @return the manager.
     */
    public static PostgresSchemaManager of(final ConnectionFactory ddlConnectionFactory,
            final PostgresSchemaDescriptor descriptor, final Duration bootstrapTimeout) {
        return new PostgresSchemaManager(ddlConnectionFactory,
                Objects.requireNonNull(descriptor, "descriptor"), bootstrapTimeout);
    }

    /**
     * The effective DDL executed by (and checksummed for) a component: the shared {@code schema_version} table followed
     * by the descriptor's own statements. This is exactly the pre-extraction statement list (the {@code schema_version}
     * statement was historically element 0 of each descriptor's own list), so the persisted checksum is preserved
     * across the postgres-client extraction.
     *
     * @param descriptor the component schema.
     * @return the ordered effective DDL statements.
     */
    public static List<String> effectiveDdl(final PostgresSchemaDescriptor descriptor) {
        final List<String> statements = new ArrayList<>();
        statements.add(SCHEMA_VERSION_DDL);
        statements.addAll(descriptor.ddlStatements());
        return List.copyOf(statements);
    }

    /**
     * @param descriptor the component schema.
     * @return the {@code schema_version} checksum for the component (SHA-256 over the effective DDL).
     */
    public static String checksum(final PostgresSchemaDescriptor descriptor) {
        return SchemaChecksum.compute(effectiveDdl(descriptor));
    }

    /**
     * Runs the bootstrap synchronously, blocking until commit or failure.
     *
     * @throws SchemaBootException on a checksum mismatch or a live-catalog divergence (refuse to boot).
     */
    public void bootstrap() {
        bootstrapReactive().block(bootstrapTimeout);
        LOGGER.info("PostgreSQL schema bootstrap complete for component '{}' (version {}, checksum {}).",
                descriptor.component(), descriptor.version(), checksum(descriptor));
    }

    /**
     * @return the bootstrap as a reactive pipeline (single transaction, released on every terminal path).
     */
    public Mono<Void> bootstrapReactive() {
        return Mono.usingWhen(
                Mono.from(ddlConnectionFactory.create()),
                this::bootstrapWithin,
                Connection::close);
    }

    /**
     * The <strong>create-only</strong> sibling of {@link #bootstrapReactive()}, used by the runtime schema self-heal
     * when a statement hits a missing table ({@code 42P01}). It runs the exact same {@code CREATE … IF NOT EXISTS} DDL
     * inside one transaction, guarded by the <em>same</em> {@link #ADVISORY_LOCK_KEY} advisory xact lock as the boot
     * bootstrap (so a hot heal serialises against a concurrent first-boot bootstrap cluster-wide), but deliberately
     * <strong>omits</strong> {@code verifyCatalog(...)} and {@code upsertAndVerifyChecksum(...)}.
     * <p>
     * Those two steps are the refuse-to-boot guards that throw {@link SchemaBootException} on any catalog/checksum
     * divergence — correct at boot, but wrong for a hot heal path: a single missing table must not trigger a
     * full-catalog verify that could fail for an unrelated reason (e.g. a peer node having already advanced the schema
     * version). The DDL is idempotent, so re-running it when only some tables are missing is safe.
     * </p>
     *
     * @return the create-only heal as a reactive pipeline (single transaction, connection released on every terminal
     * path).
     */
    public Mono<Void> ensureTablesReactive() {
        return Mono.usingWhen(
                Mono.from(ddlConnectionFactory.create()),
                this::ensureWithin,
                Connection::close);
    }

    Mono<Void> ensureWithin(final Connection connection) {
        return Mono.from(connection.beginTransaction())
                // Same advisory xact lock as the boot bootstrap -> a hot heal serialises against a concurrent first-boot
                // DDL cluster-wide; auto-released on commit.
                .then(executeUpdate(connection, "SELECT pg_advisory_xact_lock($1)", ADVISORY_LOCK_KEY))
                // All CREATE/ALTER statements, each IF NOT EXISTS. No verifyCatalog / checksum upsert (see Javadoc).
                .thenMany(runDdl(connection))
                .then()
                .then(Mono.from(connection.commitTransaction()))
                .onErrorResume(error -> Mono.from(connection.rollbackTransaction())
                        .then(Mono.error(error)));
    }

    private Mono<Void> bootstrapWithin(final Connection connection) {
        return Mono.from(connection.beginTransaction())
                // 1) transaction-scoped advisory lock — serialises concurrent first-boot DDL across the cluster.
                .then(executeUpdate(connection, "SELECT pg_advisory_xact_lock($1)", ADVISORY_LOCK_KEY))
                // 2) all CREATE/ALTER statements, each IF NOT EXISTS.
                .thenMany(runDdl(connection))
                .then()
                // 3) live-catalog verification inside the same txn.
                .then(verifyCatalog(connection))
                // 4) schema_version checksum upsert + mismatch guard.
                .then(upsertAndVerifyChecksum(connection))
                // 5) commit -> advisory xact lock auto-releases.
                .then(Mono.from(connection.commitTransaction()))
                .onErrorResume(error -> Mono.from(connection.rollbackTransaction())
                        .then(Mono.error(error)));
    }

    private Flux<Void> runDdl(final Connection connection) {
        return Flux.fromIterable(effectiveDdl(descriptor))
                .concatMap(ddl -> executeUpdate(connection, ddl));
    }

    private Mono<Void> verifyCatalog(final Connection connection) {
        return Mono.fromRunnable(() -> { })
                .thenMany(Flux.fromIterable(descriptor.tableContracts())
                        .concatMap(contract -> verifyTable(connection, contract)))
                .then();
    }

    private Mono<Void> verifyTable(final Connection connection, final TableContract contract) {
        final Mono<String> pkDef = Flux.from(connection.createStatement(
                        "SELECT pg_get_constraintdef(c.oid) AS def "
                                + "FROM pg_constraint c "
                                + "JOIN pg_class t ON c.conrelid = t.oid "
                                + "JOIN pg_namespace n ON t.relnamespace = n.oid "
                                + "WHERE t.relname = $1 AND n.nspname = current_schema() AND c.contype = 'p'")
                        .bind(0, contract.tableName())
                        .execute())
                .flatMap(result -> result.map((row, meta) -> row.get("def", String.class)))
                .next()
                .defaultIfEmpty("");

        // Read data_type AND collation_name in one pass: data_type alone cannot reveal a missing COLLATE "C" (it always
        // reports "text"), so the verifier additionally checks collation_name on the columns that require it.
        final Mono<List<ColumnInfo>> columnInfos = Flux.from(connection.createStatement(
                        "SELECT column_name, data_type, collation_name FROM information_schema.columns "
                                + "WHERE table_name = $1 AND table_schema = current_schema()")
                        .bind(0, contract.tableName())
                        .execute())
                .flatMap(result -> result.map((row, meta) -> new ColumnInfo(
                        row.get("column_name", String.class),
                        row.get("data_type", String.class),
                        row.get("collation_name", String.class))))
                .collectList();

        return Mono.zip(pkDef, columnInfos)
                .doOnNext(tuple -> {
                    final Map<String, String> types = new HashMap<>();
                    final Map<String, String> collations = new HashMap<>();
                    for (final ColumnInfo info : tuple.getT2()) {
                        types.put(info.columnName(), info.dataType());
                        // collation_name is NULL for columns without an explicit collation; the verifier treats a
                        // null/absent collation as drift for the columns that must be COLLATE "C".
                        collations.put(info.columnName(), info.collationName());
                    }
                    SchemaVerifier.verifyTable(contract,
                            tuple.getT1().isEmpty() ? null : tuple.getT1(), types, collations);
                })
                .then();
    }

    private Mono<Void> upsertAndVerifyChecksum(final Connection connection) {
        final String expected = checksum(descriptor);
        final String component = descriptor.component();
        final int version = descriptor.version();
        return Flux.from(connection.createStatement(
                        "SELECT version, checksum FROM schema_version WHERE component = $1")
                        .bind(0, component)
                        .execute())
                .flatMap(result -> result.map((Row row, io.r2dbc.spi.RowMetadata meta) ->
                        new StoredSchemaVersion(row.get("version", Integer.class),
                                row.get("checksum", String.class))))
                .next()
                .defaultIfEmpty(StoredSchemaVersion.ABSENT)
                .doOnNext(stored -> SchemaVerifier.verifyChecksum(component, expected, version,
                        stored.checksum(), stored.version()))
                .then(executeUpdate(connection,
                        "INSERT INTO schema_version (component, version, checksum) VALUES ($1, $2, $3) "
                                + "ON CONFLICT (component) DO UPDATE SET version = excluded.version, "
                                + "checksum = excluded.checksum",
                        component, version, expected));
    }

    /**
     * The {@code schema_version} row for the bootstrapped component, or {@link #ABSENT} on first boot (no row yet).
     */
    private record StoredSchemaVersion(@Nullable Integer version, @Nullable String checksum) {
        static final StoredSchemaVersion ABSENT = new StoredSchemaVersion(null, null);
    }

    private static Mono<Void> executeUpdate(final Connection connection, final String sql, final Object... binds) {
        final io.r2dbc.spi.Statement statement = connection.createStatement(sql);
        for (int i = 0; i < binds.length; i++) {
            statement.bind(i, binds[i]);
        }
        return Flux.from(statement.execute())
                .flatMap(Result::getRowsUpdated)
                .then();
    }

    /** Exposed for tests: the ordered effective DDL + the catalog contracts that the bootstrap executes/verifies. */
    public Map<String, Object> describe() {
        final Map<String, Object> description = new LinkedHashMap<>();
        description.put("statements", effectiveDdl(descriptor));
        description.put("contracts", descriptor.tableContracts());
        description.put("checksum", checksum(descriptor));
        return description;
    }

    /**
     * A single row of {@code information_schema.columns} for a verified table. {@code collationName} is {@code null}
     * when the column declares no explicit collation (it then uses the database default).
     */
    private record ColumnInfo(String columnName, String dataType, @Nullable String collationName) {}

}
