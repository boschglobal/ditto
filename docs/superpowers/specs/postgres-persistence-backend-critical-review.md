# PostgreSQL Persistence Backend — Critical Review Findings

- **Date:** 2026-06-01
- **Scope:** commits `409d7e784e..e8fc36324f` on branch `postgres-190626-feat-dev` (~12k LOC, new `internal/utils/persistence-r2dbc` module + serializer seam refactor)
- **Method:** multi-agent fan-out — 11 critical finders (one per subsystem + reactive/security cross-cutting lenses), each finding independently verified by an adversarial verifier, then synthesised. 53 raw findings → **42 confirmed**, 11 rejected as false positives.
- **Spec under review:** `postgres-persistence-backend.md` (same folder)

---

## 1. Verdict

**Needs rework before merge.** The persistence *correctness* core is excellent — every locked constraint (C1, H1, H2, H4, H9, H13, G5) is honored and verified against real Postgres 16. But two **boot-time blockers** mean the feature cannot run as wired today, and both are masked by tests that drive the `*Ops` classes directly rather than the Pekko plugin lifecycle. Fix the blockers plus the high-severity data-integrity and observability gaps, address the code-hygiene items in §6, and this becomes mergeable.

---

## 2. Confirmed Issues

### 🔴 Critical

- **Pekko plugin classes lack no-arg constructors — feature cannot boot** — `journal/PostgresJournal.java:60`, `snapshot/PostgresSnapshotStore.java:61`.
  Both declare only `(PostgresPersistenceDao dao)` constructors. Pekko's reflective plugin loader calls a **no-arg** constructor at actor-recovery time, so the first persistent-actor recovery fails with `ActorInitializationException` / `NoSuchMethodException`. Masked by the test suite, which exercises the `*Ops` classes directly and never the Pekko plugin lifecycle (`InitOrderRegressionTest` documents the gap).
  **Fix:** add a public no-arg constructor that resolves the DAO from `context().system()`, and add a contract test that instantiates both plugins *through Pekko* — not via `*Ops`.

### 🟠 High

- **Snapshot-adapter wiring has no deployment path — first snapshot load crashes** — `snapshot/PostgresSnapshotStoreOps.java:94-99` (root cause spans the HOCON profile).
  The store correctly returns raw JSONB and `PostgresSnapshotAdapter` decodes it, but `ditto-postgres-persistence.conf` configures no per-entity snapshot adapters, so services keep defaulting to `ThingMongoSnapshotAdapter` (from `things.conf`), which receives a `String`/`JsonObject` and throws `IllegalArgumentException`/`ClassCastException`. No `*PostgresSnapshotAdapter` variants exist to slot in, and nothing documents the requirement.
  **Fix:** add per-entity snapshot-adapter blocks to the Postgres profile (or a backend-driven adapter factory), and document the manual override in the operator checklist until then.

- **COPY batch failures cause silent data loss** — `migration/Migrator.java:143-155`.
  `flushBatch()` advances the checkpoint cursor to the last row of the batch (`newCursor = Cursor(last.pid(), last.sequenceNr())`) independently of the `copied` count. If COPY aborts mid-batch (constraint violation, NUL byte) the whole batch rolls back but the cursor moves past it; resume permanently skips those rows and `failed()` is never incremented. No partial-batch-rollback test exists despite the spec requiring it.
  **Fix:** assert `copied == batch.size()` and throw on mismatch; document COPY's all-or-nothing batch semantics.

- **Migration checksum query swallows all errors** — `migration/R2dbcMigrationTarget.java:66-74`.
  `storedSchemaChecksum()` uses `.onErrorReturn("")`, converting timeouts, bad-grammar, and connection failures into "no checksum stored yet." This masks real DB failures and defeats the M1/H13 refuse-to-boot guard — the operator never learns the schema-version check failed.
  **Fix:** use `.switchIfEmpty()` for the genuine no-row case and `.onErrorMap(...)` so query errors propagate.

- **SQL injection via unvalidated table name** — `migration/R2dbcMigrationTarget.java:144-146` (same root cause as the COPY statement at `:127`).
  `targetTable` is string-concatenated into `SELECT ... FROM <targetTable>` and `COPY <targetTable> ...`. It flows unvalidated from `Migrator.sourceToTargetTable()` (Mongo collection name, returned unchanged). Identifiers can't be parameterized, so a malicious/buggy collection name injects. Mitigated today only by trusting the Mongo source.
  *Contradiction noted: the migration finder rated this Low on trust grounds; the security finder rated it High. Treated as **High** — defense-in-depth on an admin tool is cheap and the trust boundary is implicit.*
  **Fix:** validate `targetTable` against the `PostgresSchema`/`PostgresTableNames` whitelist (or regex `^(things|policies|connections|wot)_(journal|snaps)$`) before building any SQL. Also covers the COPY-command medium finding at `:127`.

- **Pool gauge metrics are dead code — no production pool observability** — `monitoring/PoolMetricsPoller.java:32-98`.
  The poller is fully built and unit-tested but never instantiated or started in production; `PostgresPersistenceBackendProvider` creates the client but no poller. The mandated gauges (`POOL_ACQUIRED/ALLOCATED/IDLE/PENDING`) are therefore absent at runtime.
  **Fix:** instantiate and `.start()` the poller in the provider, hold it as a field, shut it down on disposal / ActorSystem termination — or explicitly defer and remove the dead code + test.

### 🟡 Medium

- **Missing `COLLATE "C"` on `pid` columns (`_snaps` and `_journal_seq`)** — `schema/PostgresSchema.java:154-155, 162-163`.
  The journal table has it; these two do not (G5 violation). `_snaps.pid` drives cursor pagination (`WHERE pid > $1`, `ORDER BY pid`), so ordering silently regresses under OS-locale drift; `_journal_seq.pid` is the C1 PK and risks uniqueness/plan drift.
  **Fix:** add `COLLATE "C"` to both `pid` declarations; extend `PostgresSchemaTest` to assert collation on *all* `pid` columns.

- **Schema verification can't detect collation drift** — `schema/PostgresSchema.java:75-78`.
  `JOURNAL_SEQ_COLUMNS` declares `pid` as `"text"`; `information_schema.columns.data_type` returns `"text"` regardless of collation, so `SchemaVerifier.verifyTable` (H13) cannot catch a pre-existing table missing `COLLATE "C"` on first boot.
  **Fix:** read `collation_name` from `information_schema.columns` and assert it (or document that collation enforcement rests on the M1 checksum guard alone, and ensure the checksum changes when DDL does). *Largely neutralised once the two `COLLATE` fixes above change the checksum.*

- **`R2dbcMetricsListener` has no exception handling** — `monitoring/R2dbcMetricsListener.java:58-73`.
  `recordCommand`/`recordAcquire` are called with no try-catch; a Kamon failure (registry error, tag-cardinality blowup) propagates into the r2dbc-proxy listener chain and can break the reactive data path.
  **Fix:** wrap both calls in try-catch, log at WARN/DEBUG, never rethrow.

- **`PoolMetricsPoller` lifecycle/exception gaps** — `monitoring/PoolMetricsPoller.java:40-96, 78-85`.
  The daemon scheduler's `ScheduledFuture` is discarded (uncancellable) and `close()` is only reachable if the poller is stored; `pollOnce()` doesn't guard `metricsSupplier.get()`, so a throw from a disposed pool silently kills polling forever (`scheduleAtFixedRate` stops the task on exception with no log).
  **Fix:** retain the future, integrate `close()` into the provider's shutdown, wrap `pollOnce()` in try-catch with DEBUG logging. *Resolve alongside the dead-code wiring fix above.*

### 🟢 Low

- **No schema-bootstrap precheck in migrator** — `migration/Migrator.java:74-76`.
  `guardSchemaVersion()` accepts an empty checksum and `ensureProgressTable()` creates only `migration_progress`; if the operator skips bootstrap, the first `copyIn` fails with a confusing table-not-found instead of a clear boot error.
  **Fix:** add a `verifySchema()` check of `information_schema.tables` and fail with an explicit "run `PostgresSchemaManager.ensureSchema()` first" message.

---

## 3. Subsystem Assessment

| Subsystem | Assessment |
|---|---|
| Write journal (WU7-9) | Solid — all constraints honored, parameterized, reactive resource cleanup correct; no defects in scope. |
| Snapshot store | Correct by design (PK, idempotency, two-form delete, all four criteria bounds) but blocked by the adapter-wiring deployment gap. |
| Read journal | Clean — C1 high-water-mark, H1 GIN containment + priority regex, H11 offsets, C2/G1 cast removal verified; zero defects. |
| Schema bootstrap & verification | C1/H2/H4/H13 correct; only the `COLLATE "C"` gaps (DDL + verifier) outstanding. |
| Offline migrator | Sound checkpoint/resume and COPY escaping, but undermined by the batch-loss, error-swallowing, and table-name-injection findings. |
| R2DBC client & pool | Strong — `Flux.usingWhen` release-on-all-paths, H3 PgBouncer safeguards, G5 SSL guard, redacted credentials all correct. |
| Serialization seam & Mongo refactor | Backend-neutral DTOs and C2 fix are clean; defeated only by the missing plugin no-arg constructors. |
| Observability / metrics | Command-duration timer is correct and tested; pool gauges are unwired dead code with listener/lifecycle robustness gaps. |
| Provider wiring / HOCON / init order | Plugin-ID resolution, profile opt-in structure, and init order are correct. |
| Security | Strong defense-in-depth (SSL guard, advisory xact-lock, prepared statements, credential redaction); one real injection vector in the migrator. |

---

## 4. What Looks Solid (verified, credit due)

The hard, easy-to-get-wrong invariants are all correct and independently verified against real Postgres 16:

- **C1 high-water-mark** maintained across all three paths (`GREATEST` upsert on insert, `GREATEST(MAX(sn), highest_sn)` on read, `deleted_to`-only on delete) — sequence regression after physical delete is impossible.
- **H1 tag containment** uses GIN-indexable `@> ARRAY[$1]::text[]`, with a `^priority-[0-9]+$` regex guard before the `::int` cast and `COALESCE(prio,0) DESC` keeping always-alive PIDs at default — full parity with Mongo's descending numeric ordering.
- **H2 DDL bootstrap** uses transaction-scoped `pg_advisory_xact_lock` in a single transaction on one connection — no session-lock leak across the pool.
- **H4 snapshot idempotency** via `ON CONFLICT (pid, sn, written_at)` with `written_at` from `SnapshotMetadata.timestamp` (not `now()`), preserving multi-snapshot retention.
- **H9** distinct exact-vs-range `deleteAsync` overloads and all four `SnapshotSelectionCriteria` bounds, with a `Long.MAX_VALUE` overflow guard — well covered by tests.
- **H13/M1** two-layer refuse-to-boot (live `pg_get_constraintdef` + `information_schema` catalog check *and* checksum) catches divergent pre-existing tables that `CREATE TABLE IF NOT EXISTS` would silently keep.
- **G5** SSL boot guard (loud boot failure on `verify-ca|full` without root-cert), `Flux.usingWhen` connection release on completion/error/cancel, and consistent credential redaction across config `toString()`.
- **C2** the `BsonDocument` cast trap is fully eliminated via `toEventJson()`, with backend-neutral DTOs keeping Mongo types off the `DittoReadJournal` interface.

---

## 5. Filtered False Positives

The adversarial verification pass rejected 11 of 53 raw findings, including:

- *"Critical: connection pool leak on lazy read-journal init"* — entity validation runs before pool creation; no leak possible.
- *"NUL byte (0x00) not escaped in COPY text-format encoder"* — not required by the documented COPY text-format spec.
- Several *"— CORRECT"* confirmations that finders mislabeled as findings (H4 upsert, H9 criteria bounds, H2 xact-lock, backward-compat of `EventSerializer`).

---

## 6. Code-Hygiene Findings (required cleanup, separate from defects)

These are not behavioral defects but must be fixed before merge for the code to match Ditto conventions.

### 6.1 Javadoc / comments must not reference the implementation plan

**Rule:** Javadoc is *documentation of behavior*. It must explain **what a class/method does and why**, for a reader who has never seen the plan. It must **not** contain work-unit identifiers (`WU7`, `WU11`, …), spec defect tags (`[C1]`, `[H3]`, `[M3]`, `[G5]`, …), "wave N", bead/ticket IDs, or any reference to the planning artefacts that produced the code. Those identifiers are meaningless to a future maintainer and leak the build process into the API surface.

- ❌ `Applies the SSL boot guard ({@code [G5]}) ... see {@code [M3]} ... never a boot block ({@code [H3]}).`
- ✅ `Applies the SSL boot guard and the connection-pooling-safe defaults (unnamed prepared statements). The configured pooler mode produces an informational startup log line only and never blocks boot.`

If a constraint is worth recording, state the *behavior and its rationale* in prose; do not cite the tag. Tags may remain only in the spec document, never in source javadoc.

**Affected files (124 occurrences across 31 Java files + 2 conf files):** highest density in
`dao/PostgresPersistenceDao.java` (19), `ConnectionPoolFactory.java` (11), `schema/PostgresSchemaManager.java` (8), `readjournal/PostgresReadJournal.java` (8), `schema/PostgresSchema.java` (7), `snapshot/PostgresSnapshotStoreOps.java` (6), `schema/SchemaVerifier.java` (6) — and most other new files in the module plus `ditto-postgres-persistence.conf` and `ditto-postgresql.conf`. Strip all `WU*` / `[C#]` / `[H#]` / `[M#]` / `[G#]` / "wave N" tokens from javadoc and comments, rewriting each into behavior-first prose.

### 6.2 `Dao` is not a Ditto naming pattern — rename for consistency

Ditto has **zero** classes named `*Dao`/`*DAO`. The established pattern for a persistence-access type is **`*PersistenceOperations`** (e.g. `EntityPersistenceOperations` in package `…persistence.operations`, `MongoEntitiesPersistenceOperations` / `MongoNamespacePersistenceOperations` in package `…persistence.mongo.ops…`). Package abbreviation `ops` is used by Ditto; the abbreviation `Dao` is not.

**Proposed renames** (mirroring the Mongo side):

| Current | Proposed |
|---|---|
| package `…postgres.dao` | package `…postgres.ops` |
| `dao/PostgresPersistenceDao` | `ops/PostgresPersistenceOperations` |
| `dao/JournalRow` | `ops/JournalRow` (record stays, package moves) |
| `dao/SnapshotRow` | `ops/SnapshotRow` |
| `dao/PostgresTableNames` | `ops/PostgresTableNames` |

Update all referencing imports/usages: `journal/PostgresJournal`, `journal/PostgresJournalOps`, `snapshot/PostgresSnapshotStore`, `snapshot/PostgresSnapshotStoreOps`, `readjournal/PostgresReadJournal`, `PostgresPersistenceBackendProvider`, `migration/{CopyEncoder,MigrationRecord}`, and the test package `…postgres.dao` (`RecordingConnectionFactory`, `StubClientSupport`).

**Secondary (note, lower priority):** the `*Ops` suffix on `PostgresJournalOps` / `PostgresSnapshotStoreOps` is also a coined abbreviation — Ditto spells the concept out as `…PersistenceOperations`. Consider aligning these too (e.g. `PostgresJournalPersistenceOperations`) if the team wants full consistency, though the package-level `ops` already carries the meaning.

---

## 7. Recommended Next Steps

1. **Fix the two boot blockers first** (§2 Critical + the snapshot-adapter High) behind a **real Pekko-lifecycle boot test** — that single test surfaces both at once.
2. Fix the migrator data-integrity / error-swallowing / injection High items.
3. Wire (or remove) the pool-metrics poller; harden the metrics listener.
4. Add the `COLLATE "C"` clauses + verifier collation check.
5. Code-hygiene sweep §6.1 (strip plan references from javadoc) and §6.2 (rename `dao` → `ops` / `PersistenceOperations`).
6. Re-run `mvn verify` (enforcer on) + the Testcontainers parity matrix.
