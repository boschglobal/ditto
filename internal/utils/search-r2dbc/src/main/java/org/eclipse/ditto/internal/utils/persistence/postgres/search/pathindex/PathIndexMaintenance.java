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
package org.eclipse.ditto.internal.utils.persistence.postgres.search.pathindex;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.eclipse.ditto.internal.utils.persistence.postgres.client.ConnectionPoolFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.search.PostgresSearchSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Builds, repairs and drops the operator-configured per-path {@code search_flat} indexes ({@link PathIndex}). Runs once
 * per service start, after a successful schema bootstrap, in the background; it is not part of the checksummed schema
 * DDL, so changing the path list never changes the schema checksum.
 * <p>
 * <strong>Not configured</strong> ({@code paths} unset or blank): the run manages nothing — it builds and drops
 * nothing, takes no lock, and only logs (INFO) which managed indexes exist. Only an explicitly configured list,
 * including an empty one, is reconciled; an empty list drops every managed index.
 * </p>
 * <p>
 * <strong>One run with a configured list:</strong>
 * </p>
 * <ol>
 *     <li>Read the managed indexes ({@value PathIndex#NAME_PREFIX}*) on {@code search_flat} and their
 *     {@code pg_index.indisvalid}, and compute the {@link #plan(List, List) plan}. If there is nothing to do, stop: an
 *     unchanged configuration takes no lock and changes no session setting.</li>
 *     <li>Otherwise, if the connection cannot hold a server session (a transaction-mode pooler without a direct URI),
 *     log an error and stop.</li>
 *     <li>{@code pg_try_advisory_lock} (session-scoped: it has to cover several autocommit statements, which a
 *     transaction-scoped lock cannot). If another instance holds it, that instance is doing the work: stop. Then
 *     verify that the lock is held by the backend that runs the following statements; if not, the connection is not
 *     one server session (a transaction-mode pooler): stop. This check usually detects a transaction pooler, but not
 *     when the pooler happens to route both statements to the same server connection; the documented requirement of a
 *     direct connection with {@code pooler-mode = transaction} is the guard.</li>
 *     <li>{@code SET statement_timeout = 0} and {@code SET lock_timeout = 0} on this session: an index build over a
 *     large {@code search_flat} runs for minutes and waits for concurrent transactions, and a cancelled build leaves
 *     an INVALID index behind.</li>
 *     <li>Stop if the stored search schema version is not this code's: during a rolling upgrade the newer code owns
 *     the indexes.</li>
 *     <li>Re-read the catalog under the lock and apply the plan, one statement at a time: drop every INVALID managed
 *     index (a failed or killed {@code CREATE INDEX CONCURRENTLY} leaves one behind, and {@code IF NOT EXISTS} would
 *     skip it forever), drop every valid managed index whose path is no longer configured, then build each missing
 *     configured index with {@code CREATE INDEX CONCURRENTLY}, logging the build progress
 *     ({@code pg_stat_progress_create_index}) periodically. Every drop is {@code DROP INDEX CONCURRENTLY}. A failed
 *     build is logged, its INVALID leftover dropped where possible, and the run continues with the next index.</li>
 *     <li>{@code RESET statement_timeout} and {@code RESET lock_timeout}, release the lock and close the
 *     connection.</li>
 * </ol>
 * <p>
 * The connection is a dedicated, non-pooled DDL-role connection: a build never holds a connection of the runtime pool.
 * The build itself does not block the running instances' reads and writes, and a search instance that starts
 * meanwhile does not wait for it: a start whose search schema is up to date runs no schema DDL (no lock on
 * {@code search_flat}), and a start that has to run DDL gives up after the schema bootstrap's 500 ms
 * {@code lock_timeout} instead of stalling writes or deadlocking the build.
 * </p>
 * <p>
 * If the service stops mid-build, PostgreSQL keeps running the {@code CREATE INDEX CONCURRENTLY} and releases the
 * lock when that backend ends; if the build is cancelled, aborted or the backend killed, the INVALID index is
 * repaired at the next start of any instance, which is a full build again.
 * </p>
 */
@ThreadSafe
public final class PathIndexMaintenance {

    /**
     * The session advisory-lock key of the path-index maintenance. Arbitrary but fixed, and distinct from the schema
     * bootstrap's and the reaper's keys: a build holds it for minutes, which must not block either of them.
     */
    static final long ADVISORY_LOCK_KEY = 0x0D17_70_5F_1D_E7L;

    static final Duration DEFAULT_PROGRESS_INTERVAL = Duration.ofSeconds(30L);

    private static final Logger LOGGER = LoggerFactory.getLogger(PathIndexMaintenance.class);

    private static final String LIST_MANAGED_SQL = "SELECT c.relname::text AS name, i.indisvalid AS valid "
            + "FROM pg_index i "
            + "JOIN pg_class c ON c.oid = i.indexrelid "
            + "JOIN pg_class t ON t.oid = i.indrelid "
            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
            + "WHERE n.nspname = current_schema() AND t.relname = 'search_flat' AND starts_with(c.relname, $1) "
            + "ORDER BY c.relname";

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock($1) AS locked, pg_backend_pid() AS pid";

    /**
     * Whether the advisory lock is held by the backend that executes this statement. A bigint advisory key is stored
     * as {@code classid} (high 32 bits) and {@code objid} (low 32 bits) with {@code objsubid = 1}.
     */
    private static final String SESSION_CHECK_SQL = "SELECT pg_backend_pid() AS pid, EXISTS (SELECT 1 FROM pg_locks l "
            + "WHERE l.locktype = 'advisory' AND l.pid = pg_backend_pid() AND l.granted AND l.objsubid = 1 "
            + "AND ((l.classid::bigint << 32) | l.objid::bigint) = $1) AS held";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock($1)";

    private static final List<String> SESSION_SETTINGS = List.of("SET statement_timeout = 0", "SET lock_timeout = 0");

    /**
     * Undoes {@link #SESSION_SETTINGS} before the lock is released, so a connection that is not closed afterwards (or
     * a server connection a pooler reuses) never keeps the disabled timeouts.
     */
    private static final List<String> SESSION_RESETS = List.of("RESET statement_timeout", "RESET lock_timeout");

    private static final String SCHEMA_VERSION_SQL = "SELECT version FROM schema_version WHERE component = $1";

    private static final String UNDEFINED_TABLE = "42P01";

    private static final String VALID_SQL = "SELECT i.indisvalid AS valid, pg_size_pretty(pg_relation_size(c.oid)) "
            + "AS size FROM pg_index i "
            + "JOIN pg_class c ON c.oid = i.indexrelid "
            + "JOIN pg_class t ON t.oid = i.indrelid "
            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
            + "WHERE n.nspname = current_schema() AND t.relname = 'search_flat' AND c.relname = $1";

    private static final String PROGRESS_SQL = "SELECT phase, blocks_done, blocks_total, tuples_done, tuples_total "
            + "FROM pg_stat_progress_create_index WHERE pid = $1";

    private final ConnectionFactory connectionFactory;
    private final List<PathIndex> configured;
    private final boolean listConfigured;
    private final boolean sessionCapable;
    private final Duration progressInterval;
    @Nullable private final ConnectionFactory ownedConnectionFactory;

    private PathIndexMaintenance(final ConnectionFactory connectionFactory, @Nullable final List<PathIndex> configured,
            final boolean sessionCapable, final Duration progressInterval, final boolean ownsConnectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        listConfigured = configured != null;
        this.configured = configured == null ? List.of() : List.copyOf(configured);
        this.sessionCapable = sessionCapable;
        this.progressInterval = Objects.requireNonNull(progressInterval, "progressInterval");
        ownedConnectionFactory = ownsConnectionFactory ? connectionFactory : null;
    }

    /**
     * Creates the maintenance for a service: a dedicated, non-pooled DDL-role connection to the configured direct URI
     * (or the runtime URI), without the {@code statement_timeout} startup option.
     *
     * @param runtimeConfig the runtime client config ({@code ditto.postgresql}).
     * @param pathIndexConfig the path-index config.
     * @return the maintenance; {@link #run()} closes its connection factory when it terminates.
     */
    public static PathIndexMaintenance of(final PostgresConfig runtimeConfig,
            final PostgresSearchPathIndexConfig pathIndexConfig) {
        final PostgresConfig connectionConfig =
                PathIndexConnectionConfig.of(runtimeConfig, pathIndexConfig.getDirectUri());
        return new PathIndexMaintenance(ConnectionPoolFactory.createDdlConnectionFactory(connectionConfig),
                pathIndexConfig.isPathListConfigured() ? pathIndexConfig.getPathIndexes() : null,
                PathIndexConnectionConfig.canHoldASession(runtimeConfig, pathIndexConfig.getDirectUri()),
                DEFAULT_PROGRESS_INTERVAL, true);
    }

    /**
     * @param connectionFactory a (DDL-role) connection factory whose connections are one server session each.
     * @param configured the configured indexes ({@code null}: the list is not configured, nothing is managed).
     * @param sessionCapable whether the connections can hold a server session (see
     * {@link PathIndexConnectionConfig#canHoldASession}).
     * @param progressInterval how often a running build's progress is logged.
     * @return the maintenance; the connection factory stays the caller's.
     */
    static PathIndexMaintenance forConnectionFactory(final ConnectionFactory connectionFactory,
            @Nullable final List<PathIndex> configured, final boolean sessionCapable, final Duration progressInterval) {
        return new PathIndexMaintenance(connectionFactory, configured, sessionCapable, progressInterval, false);
    }

    /**
     * Runs the maintenance once (see the class Javadoc). Failures of single drops or builds are logged and reported in
     * the {@link Outcome}; the returned {@link Mono} fails only if the catalog cannot be read at all or the connection
     * breaks outside of a build.
     *
     * @return the outcome.
     */
    public Mono<Outcome> run() {
        final Mono<Outcome> run = Mono.usingWhen(Mono.from(connectionFactory.create()), this::runOn,
                Connection::close);
        return ownedConnectionFactory == null ? run : run.doFinally(signal -> disposeQuietly(ownedConnectionFactory));
    }

    private Mono<Outcome> runOn(final Connection connection) {
        return listManaged(connection).flatMap(existing -> {
            if (!listConfigured) {
                logNotConfigured(existing);
                return Mono.just(Outcome.of(Status.NOT_CONFIGURED));
            }
            final Plan plan = plan(configured, existing);
            if (plan.isEmpty()) {
                logUpToDate();
                return Mono.just(Outcome.of(Status.UP_TO_DATE));
            }
            if (!sessionCapable) {
                LOGGER.error("Search path indexes need changes ({}), but the connection cannot hold a server session: "
                        + "ditto.postgresql.pooler-mode is 'transaction' and no "
                        + "ditto.postgresql.search.path-indexes.direct-uri (POSTGRES_SEARCH_PATH_INDEXES_DIRECT_URI) "
                        + "is configured. Configure a direct (non-PgBouncer) connection to the same database for the "
                        + "index maintenance. Nothing was changed.", plan);
                return Mono.just(Outcome.of(Status.NO_SESSION_CAPABLE_CONNECTION));
            }
            return lockAndApply(connection);
        });
    }

    private static void logNotConfigured(final List<ExistingIndex> existing) {
        if (existing.isEmpty()) {
            LOGGER.debug("Search path indexes are not configured (ditto.postgresql.search.path-indexes.paths unset) "
                    + "and none are present.");
        } else {
            // INFO, not WARN: unset is a documented, non-destructive setting (e.g. a blue/green twin on the same
            // database that leaves the indexes to the other side), and nothing is changed
            LOGGER.info("Search path indexes are not configured (ditto.postgresql.search.path-indexes.paths unset), "
                            + "{} are present: {}. Leaving them as they are. Configure the list to manage them, or "
                            + "set it to an empty list to drop them.", existing.size(),
                    existing.stream().map(ExistingIndex::name).collect(Collectors.joining(", ")));
        }
    }

    private void logUpToDate() {
        if (configured.isEmpty()) {
            LOGGER.debug("No search path indexes configured and none present.");
        } else {
            LOGGER.info("Search path indexes are up to date: {}.", configured.stream()
                    .map(index -> index.name() + " (" + index.kind().getConfigValue() + " on <" + index.path() + ">)")
                    .collect(Collectors.joining(", ")));
        }
    }

    private Mono<Outcome> lockAndApply(final Connection connection) {
        return Flux.from(connection.createStatement(TRY_LOCK_SQL).bind("$1", ADVISORY_LOCK_KEY).execute())
                .flatMap(result -> result.map((row, meta) -> new LockAttempt(
                        Boolean.TRUE.equals(row.get("locked", Boolean.class)),
                        Objects.requireNonNull(row.get("pid", Integer.class)))))
                .single()
                .flatMap(lock -> {
                    if (!lock.locked()) {
                        LOGGER.info("Search path indexes need changes, but another instance holds the maintenance lock "
                                + "and is applying them. Nothing was changed by this instance.");
                        return Mono.just(Outcome.of(Status.LOCKED_BY_ANOTHER_INSTANCE));
                    }
                    return Mono.defer(() -> applyLocked(connection, lock.pid()))
                            .flatMap(outcome -> resetAndUnlockQuietly(connection).thenReturn(outcome))
                            .onErrorResume(error -> resetAndUnlockQuietly(connection).then(Mono.error(error)));
                });
    }

    private Mono<Outcome> applyLocked(final Connection connection, final int lockPid) {
        return sessionCheck(connection, lockPid).flatMap(isSession -> {
            // usually detects a transaction pooler; see the class Javadoc for the case it cannot see
            if (!isSession) {
                LOGGER.error("The search path index maintenance connection is not one server session (statements ran "
                        + "on different backends, e.g. behind a transaction-mode PgBouncer): the session advisory "
                        + "lock, SET statement_timeout and CREATE INDEX CONCURRENTLY would not apply to the same "
                        + "backend. "
                        + "Configure ditto.postgresql.search.path-indexes.direct-uri "
                        + "(POSTGRES_SEARCH_PATH_INDEXES_DIRECT_URI) with a direct connection to the database. "
                        + "Nothing was changed.");
                return Mono.just(Outcome.of(Status.NOT_A_SESSION));
            }
            return Flux.fromIterable(SESSION_SETTINGS)
                    .concatMap(sql -> execute(connection, sql))
                    .then(storedSchemaVersion(connection))
                    .flatMap(stored -> {
                        if (stored.isEmpty() || stored.get() != PostgresSearchSchema.VERSION) {
                            LOGGER.info("Search path indexes need changes, but the stored search schema version is "
                                    + "{} and this code's is {}: the code that owns the schema manages them. Nothing "
                                    + "was changed.", stored.map(String::valueOf).orElse("absent"),
                                    PostgresSearchSchema.VERSION);
                            return Mono.just(Outcome.of(Status.SCHEMA_VERSION_MISMATCH));
                        }
                        // re-read under the lock: another instance may have applied the changes in the meantime
                        return listManaged(connection).flatMap(existing -> {
                            final Plan plan = plan(configured, existing);
                            if (plan.isEmpty()) {
                                logUpToDate();
                                return Mono.just(Outcome.of(Status.UP_TO_DATE));
                            }
                            return apply(connection, lockPid, plan);
                        });
                    });
        });
    }

    private Mono<Outcome> apply(final Connection connection, final int pid, final Plan plan) {
        final List<String> repaired = new CopyOnWriteArrayList<>();
        final List<String> dropped = new CopyOnWriteArrayList<>();
        final List<String> built = new CopyOnWriteArrayList<>();
        final List<String> failed = new CopyOnWriteArrayList<>();
        LOGGER.info("Applying search path index changes: {}", plan);
        final int builds = plan.build().size();
        return Flux.fromIterable(plan.dropInvalid())
                .concatMap(name -> drop(connection, name,
                        "it is INVALID (a CREATE INDEX CONCURRENTLY failed or was cancelled)", repaired, failed))
                .thenMany(Flux.fromIterable(plan.dropUnconfigured())
                        .concatMap(name -> drop(connection, name, "its path is no longer configured", dropped,
                                failed)))
                .thenMany(Flux.range(0, builds)
                        .concatMap(i -> build(connection, pid, plan.build().get(i), i + 1, builds, built, failed)))
                .then(Mono.fromSupplier(() -> {
                    final Outcome outcome = new Outcome(Status.APPLIED, List.copyOf(built), List.copyOf(repaired),
                            List.copyOf(dropped), List.copyOf(failed));
                    if (failed.isEmpty()) {
                        LOGGER.info("Search path index changes applied: {}", outcome);
                    } else {
                        LOGGER.error("Search path index changes applied with failures (retried at the next start): {}",
                                outcome);
                    }
                    return outcome;
                }));
    }

    /*
     * Drops are by name in the current schema, and names come only from the catalog query on search_flat in that schema
     * or from PathIndex: "only per-path indexes of search_flat are ever dropped" rests on the sf_path_ prefix (no other
     * index of this backend uses it) plus the hash in the name (two different definitions do not share a name).
     */
    private Mono<Void> drop(final Connection connection, final String name, final String reason,
            final List<String> done, final List<String> failed) {
        LOGGER.info("Dropping search path index {} because {}.", name, reason);
        return execute(connection, PathIndex.dropStatement(name))
                .doOnSuccess(v -> done.add(name))
                .onErrorResume(error -> {
                    LOGGER.error("Dropping search path index {} failed: {}", name, error.getMessage(), error);
                    failed.add(name);
                    return Mono.empty();
                });
    }

    private Mono<Void> build(final Connection connection, final int pid, final PathIndex index, final int position,
            final int total, final List<String> built, final List<String> failed) {
        return Mono.defer(() -> {
            final Instant start = Instant.now();
            LOGGER.info("Building search path index {} ({}/{}): {} on <{}> (wpath <{}>) with CREATE INDEX "
                            + "CONCURRENTLY. The build blocks neither the running instances' reads and writes nor "
                            + "starting instances. Until the index is valid, queries on this path use the table-wide "
                            + "index.", index.name(), position, total,
                    index.kind().getConfigValue(), index.path(), index.wpath());
            final Disposable progress = Flux.interval(progressInterval, progressInterval)
                    .concatMap(tick -> progress(pid).onErrorResume(error -> Mono.empty()))
                    .subscribe(state -> LOGGER.info("Building search path index {}: {}, {} s elapsed.", index.name(),
                            state, Duration.between(start, Instant.now()).toSeconds()));
            return execute(connection, index.createStatement())
                    .doFinally(signal -> progress.dispose())
                    .then(validity(connection, index.name()))
                    .flatMap(validity -> {
                        if (!validity.valid()) {
                            return Mono.error(new IllegalStateException("the index " + index.name()
                                    + " is missing or not valid after CREATE INDEX CONCURRENTLY"));
                        }
                        LOGGER.info("Built search path index {} in {} s ({}).", index.name(),
                                Duration.between(start, Instant.now()).toSeconds(), validity.size());
                        built.add(index.name());
                        return Mono.<Void>empty();
                    })
                    .onErrorResume(error -> {
                        LOGGER.error("Building search path index {} for <{}> failed after {} s: {}", index.name(),
                                index.path(), Duration.between(start, Instant.now()).toSeconds(), error.getMessage(),
                                error);
                        failed.add(index.name());
                        return dropLeftover(connection, index.name());
                    });
        });
    }

    private static Mono<Void> dropLeftover(final Connection connection, final String name) {
        return execute(connection, PathIndex.dropStatement(name))
                .doOnSuccess(v -> LOGGER.info("Dropped the leftover of the failed build of {}.", name))
                .onErrorResume(error -> {
                    LOGGER.warn("Could not drop the leftover of the failed build of {} ({}); it stays INVALID and is "
                            + "dropped and rebuilt at the next start.", name, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<String> progress(final int pid) {
        return Mono.usingWhen(Mono.from(connectionFactory.create()),
                monitor -> Flux.from(monitor.createStatement(PROGRESS_SQL).bind("$1", pid).execute())
                        .flatMap(result -> result.map((row, meta) -> describeProgress(
                                row.get("phase", String.class),
                                row.get("blocks_done", Long.class), row.get("blocks_total", Long.class),
                                row.get("tuples_done", Long.class), row.get("tuples_total", Long.class))))
                        .next(),
                Connection::close);
    }

    static String describeProgress(@Nullable final String phase, @Nullable final Long blocksDone,
            @Nullable final Long blocksTotal, @Nullable final Long tuplesDone, @Nullable final Long tuplesTotal) {
        final StringBuilder sb = new StringBuilder("phase '").append(phase).append('\'');
        if (blocksTotal != null && blocksTotal > 0) {
            sb.append(", blocks ").append(blocksDone).append('/').append(blocksTotal)
                    .append(" (").append(blocksDone == null ? 0 : blocksDone * 100 / blocksTotal).append("%)");
        }
        if (tuplesTotal != null && tuplesTotal > 0) {
            sb.append(", tuples ").append(tuplesDone).append('/').append(tuplesTotal);
        }
        return sb.toString();
    }

    private static Mono<Boolean> sessionCheck(final Connection connection, final int lockPid) {
        return Flux.from(connection.createStatement(SESSION_CHECK_SQL).bind("$1", ADVISORY_LOCK_KEY).execute())
                .flatMap(result -> result.map((row, meta) ->
                        isOneSession(lockPid, row.get("pid", Integer.class), row.get("held", Boolean.class))))
                .single();
    }

    /**
     * @param lockPid the backend pid that took the advisory lock.
     * @param checkPid the backend pid that ran the session check.
     * @param heldByCheckingBackend whether the backend that ran the check holds the lock.
     * @return whether the two statements ran in one server session that holds the lock.
     */
    static boolean isOneSession(final int lockPid, @Nullable final Integer checkPid,
            @Nullable final Boolean heldByCheckingBackend) {
        return checkPid != null && checkPid == lockPid && Boolean.TRUE.equals(heldByCheckingBackend);
    }

    /**
     * Resets the session settings, then releases the session advisory lock. A failure (e.g. the backend was killed) is
     * only logged: the lock and the settings end with the session, and closing the connection ends the session.
     */
    private static Mono<Void> resetAndUnlockQuietly(final Connection connection) {
        return Flux.fromIterable(SESSION_RESETS)
                .concatMap(sql -> execute(connection, sql))
                .then()
                .onErrorResume(error -> {
                    LOGGER.debug("Resetting the search path index maintenance session settings failed: {}",
                            error.getMessage());
                    return Mono.empty();
                })
                .then(Mono.defer(() -> unlockQuietly(connection)));
    }

    private static Mono<Void> unlockQuietly(final Connection connection) {
        return Flux.from(connection.createStatement(UNLOCK_SQL).bind("$1", ADVISORY_LOCK_KEY).execute())
                .flatMap(result -> result.map((row, meta) -> row.get(0, Boolean.class)))
                .then()
                .onErrorResume(error -> {
                    LOGGER.debug("Releasing the search path index maintenance lock failed (it ends with the session): "
                            + "{}", error.getMessage());
                    return Mono.empty();
                });
    }

    private static Mono<Optional<Integer>> storedSchemaVersion(final Connection connection) {
        return Flux.from(connection.createStatement(SCHEMA_VERSION_SQL)
                        .bind("$1", PostgresSearchSchema.COMPONENT)
                        .execute())
                .flatMap(result -> result.map((row, meta) -> Optional.ofNullable(row.get("version", Integer.class))))
                .next()
                .defaultIfEmpty(Optional.empty())
                // no schema_version table at all (the bootstrap, which creates it, did not run): treat as absent
                .onErrorResume(error -> error instanceof R2dbcException r2dbc
                                && UNDEFINED_TABLE.equals(r2dbc.getSqlState()),
                        error -> Mono.just(Optional.empty()));
    }

    private static Mono<List<ExistingIndex>> listManaged(final Connection connection) {
        return Flux.from(connection.createStatement(LIST_MANAGED_SQL).bind("$1", PathIndex.NAME_PREFIX).execute())
                .flatMap(result -> result.map((row, meta) -> new ExistingIndex(
                        Objects.requireNonNull(row.get("name", String.class)),
                        Boolean.TRUE.equals(row.get("valid", Boolean.class)))))
                .filter(existing -> PathIndex.isManagedName(existing.name()))
                .collectList();
    }

    private static Mono<Validity> validity(final Connection connection, final String name) {
        return Flux.from(connection.createStatement(VALID_SQL).bind("$1", name).execute())
                .flatMap(result -> result.map((row, meta) -> new Validity(
                        Boolean.TRUE.equals(row.get("valid", Boolean.class)), row.get("size", String.class))))
                .next()
                .defaultIfEmpty(new Validity(false, null));
    }

    private static Mono<Void> execute(final Connection connection, final String sql) {
        final Statement statement = connection.createStatement(sql);
        return Flux.from(statement.execute()).flatMap(Result::getRowsUpdated).then();
    }

    private static void disposeQuietly(final ConnectionFactory connectionFactory) {
        if (connectionFactory instanceof Closeable closeable) {
            Mono.from(closeable.close())
                    .onErrorResume(error -> {
                        LOGGER.warn("Failed to close the search path index connection factory.", error);
                        return Mono.empty();
                    })
                    .subscribe();
        }
    }

    /**
     * What a run has to do, given the configured indexes and the managed indexes present.
     *
     * @param dropInvalid managed indexes that are INVALID: dropped (configured ones are then rebuilt).
     * @param dropUnconfigured valid managed indexes whose path is no longer configured: dropped.
     * @param build configured indexes that are missing or INVALID: built, in configuration order.
     */
    record Plan(List<String> dropInvalid, List<String> dropUnconfigured, List<PathIndex> build) {

        boolean isEmpty() {
            return dropInvalid.isEmpty() && dropUnconfigured.isEmpty() && build.isEmpty();
        }

        @Override
        public String toString() {
            return "drop INVALID " + dropInvalid + ", drop unconfigured " + dropUnconfigured + ", build "
                    + build.stream().map(index -> index.name() + " (" + index.kind().getConfigValue() + " on <"
                    + index.path() + ">)").toList();
        }
    }

    /**
     * A managed index present on {@code search_flat}.
     *
     * @param name its name.
     * @param valid {@code pg_index.indisvalid}.
     */
    record ExistingIndex(String name, boolean valid) {}

    /**
     * @param configured the configured indexes.
     * @param existing the managed indexes present.
     * @return the plan.
     */
    static Plan plan(final List<PathIndex> configured, final List<ExistingIndex> existing) {
        final Map<String, Boolean> validByName = new LinkedHashMap<>();
        existing.forEach(index -> validByName.put(index.name(), index.valid()));
        final Set<String> configuredNames = configured.stream().map(PathIndex::name).collect(Collectors.toSet());
        final List<String> dropInvalid = new ArrayList<>();
        final List<String> dropUnconfigured = new ArrayList<>();
        validByName.forEach((name, valid) -> {
            if (!valid) {
                dropInvalid.add(name);
            } else if (!configuredNames.contains(name)) {
                dropUnconfigured.add(name);
            }
        });
        final List<PathIndex> build = configured.stream()
                .filter(index -> !Boolean.TRUE.equals(validByName.get(index.name())))
                .toList();
        return new Plan(List.copyOf(dropInvalid), List.copyOf(dropUnconfigured), build);
    }

    private record LockAttempt(boolean locked, int pid) {}

    private record Validity(boolean valid, @Nullable String size) {}

    /**
     * The result of a run.
     */
    public enum Status {
        /** The path list is not configured: nothing was built or dropped, no lock was taken. */
        NOT_CONFIGURED,
        /** Nothing to do: every configured index is present and valid, and no other managed index is present. */
        UP_TO_DATE,
        /** Changes were applied (see the lists of the {@link Outcome}; some may have failed). */
        APPLIED,
        /** Another instance holds the maintenance lock; nothing was changed by this one. */
        LOCKED_BY_ANOTHER_INSTANCE,
        /** Changes are needed, but the configured topology cannot carry a server session; nothing was changed. */
        NO_SESSION_CAPABLE_CONNECTION,
        /** Changes are needed, but the statements did not run on one server session; nothing was changed. */
        NOT_A_SESSION,
        /** Changes are needed, but the stored schema version is not this code's; nothing was changed. */
        SCHEMA_VERSION_MISMATCH
    }

    /**
     * The outcome of a run.
     *
     * @param status what the run did.
     * @param built the indexes built.
     * @param repaired the INVALID indexes dropped (configured ones among them were then rebuilt).
     * @param dropped the valid indexes dropped because their path is no longer configured.
     * @param failed the indexes whose drop or build failed.
     */
    public record Outcome(Status status, List<String> built, List<String> repaired, List<String> dropped,
            List<String> failed) {

        static Outcome of(final Status status) {
            return new Outcome(status, List.of(), List.of(), List.of(), List.of());
        }
    }

}
