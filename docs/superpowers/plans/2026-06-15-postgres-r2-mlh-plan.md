# Postgres backend — round-2 Mediums / Lows / Hygiene remediation (Plan 2)

- **Date:** 2026-06-15
- **Author:** /forge-plan (triage-driven)
- **Base commit:** `2fc7dd9ba1` on `postgres-190626-feat-dev`
- **Target branch:** `pg-r2-mlh` (off `2fc7dd9ba1`)
- **Source review:** `docs/superpowers/specs/postgres-persistence-backend-critical-review-round2.md`
- **Companion plan:** `docs/superpowers/plans/2026-06-02-postgres-it-suite-plan.md` (Plan 1, IT-suite — owns M-11 and L-3)

## 1. Goal

Close every still-open Medium, Low, and Hygiene finding from the round-2 critical review on a single branch, plus the two **High** findings that survived the round-2 remediation (`H-11`, `H-12`). Use the same per-ticket-implement → adversarial-review-panel → fresh-agent-on-rejection workflow pattern that delivered the round-2 remediation.

## 2. Scope decision

Triage performed 2026-06-15 against HEAD = `2fc7dd9ba1`. Out of 31 nominally-open findings (M-1..M-12, L-1..L-15, HY-1..HY-4):

| Bucket | Count | Verdict |
|---|---|---|
| Already fixed | 4 | Drop — round-2 remediation incidentally closed |
| Partial (narrow remaining gap) | 2 | Keep, scoped to the remaining gap |
| Still open | 19 | Keep — all in this plan |
| Documented "no action" | 1 | Keep as confirmation only (no work) |
| Defer to Plan 1 (IT-suite) | 2 | Out of scope of this plan |
| **Plus: open Highs from round-2 that survived remediation** | **2** | **Escalated in** |
| **Total tickets this plan** | **24** |  |

### 2.1 Already fixed — dropped from scope

| Item | Why dropped | Evidence |
|---|---|---|
| M-2 | `DISTINCT ON (pid)` is present in `getLatestJournalEntries`. | `PostgresPersistenceOperations.java:463-467` |
| M-3 | Shared client registered with `CoordinatedShutdown` (awaits `disposeLater().toFuture()`); per-plugin `postStop` dispose comments confirm removal. | `PostgresClientExtension.java:96-118`, `PostgresJournal.java:116-117`, `PostgresSnapshotStore.java:113-114` |
| M-6 | `PostgresTableNames.ENTITY_TYPE_ALIASES` carries `wot-validation-config → wot`; `of(...)` normalises before validating. | `PostgresTableNames.java:50-51, 71-80` |
| H-5 | `AbstractPostgresEventAdapter.toJournal` wraps the JSON payload in `Tagged(json, getJournalTags(event))`. | `AbstractPostgresEventAdapter.java:60-71` |

### 2.2 Partial — scope narrowed

| Item | Remaining gap | Notes |
|---|---|---|
| M-5 | SSL boot guard / `ssl.mode` still validated at **pool-build** time inside `PostgresClientExtension.createExtension`. After H-8 there is only ONE pool per actor system, so impact is reduced from "per-plugin restart loop" to "lazy-on-first-resolution". Still NOT at config-load time, so a typo in `ssl.mode` still fails as `IllegalArgumentException` from `SSLMode.fromValue` rather than `DittoConfigError` at HOCON construction. | Move only the `ssl.mode` validation to `DefaultSslConfig` constructor; leave `validateBootGuard()` at pool-build. |
| HY-3 | `poolMetricsPoller` field deleted in H-9. `client` field still exists but is repurposed as a test seam — keep as-is. **Only the no-op `Math.min(max, Long.MAX_VALUE)` clamp at `PostgresPersistenceOperations.java:184` remains as cosmetic dead code.** | Single-line cleanup. |

### 2.3 Deferred to Plan 1 (IT-suite)

| Item | Why deferred |
|---|---|
| M-11 (end-to-end IT through production journal wiring; Policy/Connection/WoT adapter coverage) | Plan 1 owns: `ProviderWiredTestActor` + `PostgresEventSourceITBase` + real-`ThingPersistenceActor` smoke IT. Duplicating here would conflict. |
| L-3 (Testcontainers ITs silently skip) | Plan 1 Task 1 is the "always-require-Docker sweep" across the same 7 ITs + `PostgresDbResource`. Owning it here would race Plan 1. |

### 2.4 Escalated into scope (round-2 Highs that survived remediation)

| Item | Why included |
|---|---|
| H-11 | Snapshot migration cursor `(lastPid, lastSn)` is lossy when a batch boundary lands inside a same-`(pid, sn)` group (snapshot PK is `(pid, sn, written_at)`). Round-2 doc High; not in the "10 remediated" list. Lives in the same migrator surface as M-8/M-9/L-4/L-5/L-8/H-12 — bundling is cheaper than a separate branch. |
| H-12 | `CopyEncoder.field()`/`arrayLiteral()` does not detect embedded NUL → COPY aborts the entire batch (combined with M-8's non-atomic resume = unrecoverable stall). Round-2 doc High; not remediated. |

## 3. Branch strategy

```
postgres-190626-feat-dev (2fc7dd9ba1)
└── pg-r2-mlh        # this plan's integration branch
    ├── pg-r2-mlh/ops       # Group A
    ├── pg-r2-mlh/migrator  # Group B (largest)
    ├── pg-r2-mlh/pool      # Group C
    ├── pg-r2-mlh/poller    # Group D
    ├── pg-r2-mlh/hocon     # Group E
    └── pg-r2-mlh/tests     # Group F
```

Each group branch off `pg-r2-mlh` head, work tickets in order (within a group, items in the same file are sequential; cross-file items can parallelise inside the group). Merge group branch → `pg-r2-mlh` only after `mvn verify` (full module, Docker required) is green. Groups are file-disjoint so no cross-group merge conflicts.

## 4. Conflict groups (the tickets)

Each ticket below maps 1:1 to a beads ticket (`/forge-to-beads` will create them). Acceptance criteria are TDD: every ticket includes the failing test that proves the bug, then the fix, then the same test asserting the fix. Build gate per group is `mvn -pl :ditto-internal-utils-persistence-r2dbc verify` (Docker required).

### Group A — `PostgresPersistenceOperations.java` (Group leader; serial within group)

All five tickets touch the same source file. Branch `pg-r2-mlh/ops`; tickets must merge sequentially.

#### A.1 — `r2mlh-m1`: `inTransaction` cancel arm leaks open transactions
- **Evidence:** `PostgresPersistenceOperations.java:540-550`. The 3-arg `Mono.usingWhen` calls `Connection::close` on both completion and cancel; `onErrorResume(rollback)` runs only on signalled error. Cancellation returns an "idle in transaction" connection to the pool.
- **Acceptance:**
  - **Failing test:** unit test that subscribes to an `inTransaction(c -> Mono.never())` and cancels the subscription mid-flight, then asserts that the connection's transaction was rolled back before it returned to the pool (R2DBC `MockConnection`/`H2ConnectionFactory` based — no Docker required at unit level).
  - **Fix:** switch to the **5-arg** `Mono.usingWhen(resourceSupplier, resourceClosure, asyncComplete, asyncError, asyncCancel)` overload (Reactor exposes only 3-arg and 5-arg variants; there is no 4-arg form). Wire:
    - `asyncComplete = conn -> Mono.from(conn.commitTransaction()).then(Mono.from(conn.close()))`
    - `asyncError = (conn, err) -> Mono.from(conn.rollbackTransaction()).onErrorComplete().then(Mono.from(conn.close()))`
    - `asyncCancel = conn -> Mono.from(conn.rollbackTransaction()).onErrorComplete().then(Mono.from(conn.close()))`
    Remove the inline `onErrorResume(rollback)` inside the closure (lines 546-548) to avoid double-rollback on the error path.
  - **Re-run:** `mvn -pl :ditto-internal-utils-persistence-r2dbc test` green.
  - **Notes:** Audit any other `Mono.usingWhen(...)` call sites in `PostgresPersistenceOperations` / `R2dbcMigrationTarget` (`R2dbcMigrationTarget.java:162-170` is the obvious peer — its closure has no transactional state, so the 3-arg form is correct, but document the audit in the ticket comment).

#### A.2 — `r2mlh-m7`: `getNewestSnapshotsAbove` non-deterministic on (pid, sn) ties
- **Evidence:** `PostgresPersistenceOperations.java:473-475` — `ORDER BY pid, sn DESC` with no `written_at DESC` tiebreaker; contrast `loadSnapshot` line 248 which orders `sn DESC, written_at DESC`.
- **Acceptance:**
  - **Failing test:** IT inserts two snapshots for the same `(pid, sn)` with distinct `written_at` and asserts that the later `written_at` is returned (currently arbitrary).
  - **Fix:** `ORDER BY pid, sn DESC, written_at DESC`.
  - **Re-run:** `mvn -pl :ditto-internal-utils-persistence-r2dbc verify` green.

#### A.3 — `r2mlh-l6`: `readHighestSequenceNr` ignores `fromSequenceNr`
- **Evidence:** `PostgresJournalOps.java:174-177` — parameter shadowed.
- **Acceptance:**
  - **Failing test:** unit test where the journal's HWM (`_journal_seq.highest_sn`) is `5` but `fromSequenceNr = 10`; assert return value `>= 10`.
  - **Fix:** `return operations.readHighestSequenceNr(persistenceId).map(hwm -> Math.max(fromSequenceNr, hwm)).toFuture();`.

#### A.4 — `r2mlh-l15a`: journal INSERT uses `now()` (txn-start), not `clock_timestamp()`
- **Evidence:** `PostgresPersistenceOperations.java:131`. Latent today (Ditto never produces multi-event AtomicWrites), but a future change to AtomicWrite size > 1 would silently collapse all event timestamps to a single instant within the txn.
- **Acceptance:**
  - **Audit step (do this first):** grep `PostgresPersistenceOperations.java` for every literal `now()` occurrence. For each, document in the ticket comment whether it requires txn-start (`now()`) or wall-clock (`clock_timestamp()`) semantics. **Round-3 correction:** the `_journal_seq` upsert inside the same txn (`PostgresPersistenceOperations.java:139-146`) binds only `(pid, highest_sn)` and carries NO timestamp column — so the originally-feared "watermark row keeps `now()` while the journal row moves to `clock_timestamp()`" ordering inconsistency cannot occur. The audit step still runs (to confirm no other co-txn `now()` exists), but the journal INSERT at line 131 is the only site needing the change.
  - **Failing test:** preferred — IT issuing an artificial 2-event AtomicWrite (synthesised by injecting a `Tagged` envelope carrying two payloads, OR by calling the underlying `insertEvents(List<JournalRow>)` directly with two rows) and asserting distinct `written_at` values per event. Fallback if AtomicWrite size > 1 cannot be synthesised in-test: unit-level test that loads the SQL constant and asserts the `written_at` token equals `clock_timestamp()`. The test comment must explain the trade-off and link to this ticket.
  - **Fix:** `now()` → `clock_timestamp()` for `written_at` only on the journal INSERT. Keep `now()` (or convert it) for the other call sites per the audit decision. Snapshot INSERT `written_at` is supplied by the caller and is not affected.

#### A.5 — `r2mlh-hy3`: drop `Math.min(max, Long.MAX_VALUE)` no-op clamp
- **Evidence:** `PostgresPersistenceOperations.java:184`.
- **Acceptance:** code-only; the existing test surface must remain green (this is dead code, no behaviour change).
- **Notes:** Keep the `client` field on the provider as-is (it's a test seam now — see triage).

### Group B — Migrator subsystem (`Migrator.java`, `R2dbcMigrationTarget.java`, `CopyEncoder.java`, `MongoMigrationSource.java`)

Branch `pg-r2-mlh/migrator`. Largest group (7 tickets). Within the group, tickets that touch the same file are sequential; cross-file pairs can parallelise.

#### B.1 — `r2mlh-h12`: `CopyEncoder` does not reject COPY-incompatible bytes (`\x00`, invalid UTF-8, `\.`) → unrecoverable batch stall
- **Evidence:** `CopyEncoder.java:105-118` (`field`), `:127-148` (`arrayLiteral`). NUL falls through `default` and is emitted verbatim; Postgres rejects it; combined with M-8 the cursor never advances → wedged.
- **Acceptance (scope widened — see Critical #7):**
  - Reject the full set of bytes Postgres COPY-text mode cannot accept in a `jsonb`/`TEXT` column: (1) `\x00` (NUL — jsonb explicitly rejects), (2) invalid UTF-8 byte sequences (server-side `client_encoding` mismatch → batch abort), (3) an unescaped bare `\.` on its own line in the joined COPY body (the COPY end-of-data marker — would truncate the batch). Centralise as `validateCopyPayload(String value, String pid, long sn)` used by both `field` and `arrayLiteral` elements; throw a new `CopyEncodingException(pid, sn, reason)` where `reason` is one of `EMBEDDED_NUL`, `INVALID_UTF8`, `EMBEDDED_END_OF_DATA`, with a byte-offset for diagnostics.
  - **Failing tests (one per reason):** NUL-embedded payload, lone invalid UTF-8 surrogate (`\uD800`), payload whose joined COPY line starts with `\.` — each must raise `CopyEncodingException` naming `(pid, sn, reason)` BEFORE the COPY bytes are returned.
  - **Wire-in:** in `Migrator.flushBatch`, treat a per-row `CopyEncodingException` from the encoder as a counted `failed` row that the batch may skip past, rather than the current "any mismatch aborts the batch" path (`flushBatch:193-195`). Without this split, a single rogue row still aborts the whole COPY.
  - **Where validation must run (review pass #4):** the encoder must reject the bad row **at row-encode time, before the COPY body is assembled** — i.e. the offending row is excluded from the row list up front, and the resume cursor advances past its `(pid, sn)`. Validating after the `copied != rows.size()` guard (`flushBatch:193`) cannot recover, because the COPY has already been built and submitted as one stream. The `EMBEDDED_END_OF_DATA` (`\.`) check is special: a bare `\.` is the COPY end-of-data marker only when it is a *whole line* in the assembled body, not a substring of one field. `validateCopyPayload(field)` can flag a field that *equals* `\.` or *contains* a newline-delimited `\.` token, but the test must assert against the **assembled COPY line/body**, not the raw field in isolation, to prove the marker can't slip through after delimiter-joining.
  - **Notes:** Round-2 doc reclassified this from finder-Critical to High; the mechanism is "unrecoverable stall" not "silent corruption". The original (NUL-only) acceptance test is preserved as the first of the three failing tests above.

#### B.2 — `r2mlh-h11`: snapshot resume cursor must include `written_at`
- **Evidence:** `MongoMigrationSource.Cursor` (`:110-118`) is `(lastPid, lastSn)`; `Migrator.java:196-198` builds the same. Snaps PK is `(pid, sn, written_at)` (`PostgresSchema.java:189`). A batch boundary inside a same-`(pid, sn)` group skips the surviving rows on resume.
- **Acceptance:**
  - **DDL migration (new — addresses round-2 review Critical #2):** `migration_progress` table (defined in `R2dbcMigrationTarget.java:84-87` as `last_pid TEXT, last_sn BIGINT`) must gain a nullable `last_written_at TIMESTAMPTZ NULL`. Add this to `ensureProgressTable`'s `CREATE TABLE IF NOT EXISTS` block AND add an idempotent `ALTER TABLE migration_progress ADD COLUMN IF NOT EXISTS last_written_at TIMESTAMPTZ NULL` call from the same method so existing deployments self-upgrade.
  - **Failing test (3-tuple semantics):** unit/IT seeds source snaps with two rows at `(pid=A, sn=1)` differing only by `written_at`; cursor batch-size = 1; assert both rows are migrated after resume.
  - **Failing test (forward-compat):** seed a `migration_progress` row written under the 2-tuple cursor (`last_pid='A', last_sn=42, last_written_at=NULL`); resume; assert the migrator interprets NULL as "no further filtering on `written_at`" and the resumed run completes without re-COPYing the entire collection. The journal source MUST continue ignoring `last_written_at` (2-tuple semantics).
  - **Fix:** generalise `Cursor` to carry `Optional<Instant> lastWrittenAt`; journal source ignores it (keeps 2-tuple `readAfter` predicate `(pid, sn) > cursor`); snapshot source uses 3-tuple predicate `(pid, sn, written_at) > cursor` when `lastWrittenAt` is present, falls back to 2-tuple when NULL. `mapProgress` in `R2dbcMigrationTarget` maps the new column; `saveProgress` binds it. `R2dbcMigrationTarget.MigrationProgress` record gains the field.
  - **Cross-file:** `Cursor`, `MongoMigrationSource.readAfter`, `R2dbcMigrationTarget.{ensureProgressTable, loadProgress, saveProgress, mapProgress}`, `Migrator.flushBatch` newCursor construction.

#### B.3 — `r2mlh-m8`: COPY commit + `saveProgress` are not atomic
- **Evidence:** `Migrator.java:184-204`. Two r2dbc round-trips with no enclosing txn; a crash between commits leaves journal/snap rows persisted but cursor un-advanced → resume re-COPYs, PK aborts the entire batch. Note the current topology: `R2dbcMigrationTarget.copyIn` (lines 162-170) acquires its own connection via `client.getConnectionPool().create()` while `saveProgress` (lines 134-152) goes through `client.executeUpdatePublisher` — these are TWO DIFFERENT pooled connections today, so "wrap them in a transaction" requires a connection-pinning seam first.
- **Acceptance:**
  - **New seam (required prerequisite):** add `MigrationTarget.copyAndCheckpoint(String targetTable, String columns, List<String> copyRows, MigrationProgress nextProgress) → long` that owns ONE connection end-to-end. Inside that one connection: `BEGIN; TRUNCATE migration_staging_<target>; COPY migration_staging_<target> FROM STDIN; INSERT INTO <targetTable> … FROM migration_staging_<target> … ON CONFLICT DO NOTHING; UPSERT migration_progress; COMMIT;`. `Migrator.flushBatch` becomes a single call to this seam — the old `copyIn` + `saveProgress` separation moves behind the new method (the legacy methods remain for non-batch use: `loadProgress`, `ensureProgressTable`, `upsertHighWaterMarks`).
  - **Return value = staging-COPY row count, NOT the target-INSERT count (addresses round-3 review Critical #1).** `copyAndCheckpoint` MUST return the number of rows the `COPY … FROM STDIN` loaded into the staging table — which is exact and all-or-nothing, so `Migrator.flushBatch`'s `if (copied != rows.size()) throw CopyBatchException` guard at `Migrator.java:193` still detects COPY framing/encoding corruption. It MUST NOT return the count of rows the `INSERT … FROM staging … ON CONFLICT DO NOTHING` actually wrote to the target: on an idempotent resume that count is legitimately *lower* than `rows.size()` (duplicate `(pid, sn[, written_at])` keys are silently absorbed), which the existing guard would misread as a rolled-back batch and abort every resume. Document this contract on `copyAndCheckpoint`'s Javadoc and on the `CopyBatchException` throw site. **Failing test (resume idempotency):** run a batch to completion, then re-run the SAME batch (cursor not advanced) and assert `copyAndCheckpoint` returns `rows.size()` (staging COPY count) and does NOT throw `CopyBatchException`, while the target table gains zero net rows.
  - **`copyAndCheckpoint` transaction MUST use the 5-arg `Mono.usingWhen` (addresses round-3 review Critical #2).** The new method owns an explicit `BEGIN→COMMIT`, so it has transactional state on cancellation — unlike the legacy `copyIn` (`R2dbcMigrationTarget.java:162-170`) whose 3-arg `usingWhen(create, work, Connection::close)` is safe ONLY because it runs no explicit transaction. Reuse the exact pattern from A.1/`r2mlh-m1`: `asyncComplete = conn -> commit.then(close)`, `asyncError`/`asyncCancel = conn -> rollback.onErrorComplete().then(close)`. Copying the 3-arg form here would re-introduce M-1's "idle in transaction" leak inside the very same PR that fixes it. The pattern is code-local (not a shared symbol), so B.3 can apply it independently even though A.1 lives on the parallel `pg-r2-mlh/ops` branch — no cross-group merge gate, but land A.1 first if convenient so the reference implementation exists. **Failing test (cancel-mid-COPY):** subscribe to `copyAndCheckpoint` with a `Mono.never()`-style stall inside the COPY phase, cancel the subscription, and assert the connection's transaction was rolled back (no "idle in transaction") before it returned to the pool.
  - **Fix design (Option A — staging table):**
    - Use a **permanently-named** staging table per target (`migration_staging_<target>`, e.g. `migration_staging_things_journal`) created by `ensureProgressTable` (renamed in spirit but kept for back-compat) so PgBouncer transaction-pooling cannot lose it across borrows; truncate at txn start (`TRUNCATE migration_staging_<target>`) — TRUNCATE inside a txn is rolled back on abort, so crash safety is preserved.
    - `INSERT INTO <target> SELECT ... FROM migration_staging_<target> ON CONFLICT DO NOTHING` inside the same txn as the cursor upsert. The `ON CONFLICT DO NOTHING` makes resume idempotent: re-running the staging→target insert on duplicate keys is silently absorbed.
    - **Cost note:** staging adds one extra INSERT-from-staging round-trip per batch (~2× the I/O of pure COPY). Document the throughput trade-off in the ticket; if pre-merge benchmarks show >25% regression, switch to Option B with an explicit non-pooled connection acquisition.
    - **Rejected — Option B (COPY-in-txn against a single non-pooled connection):** would skip the staging double-write but requires `client.getConnectionPool().create()` to hand back an unpooled connection that bypasses PgBouncer; not currently supported by the client surface. Keep as a fallback path documented in the ticket.
  - **Failing test:** IT with a test-only hook that throws between the COPY+staging-insert phase and the `saveProgress` upsert (both must now be in the SAME txn — the hook actually throws AFTER `COMMIT` to simulate a kill in the post-commit gap, then resume; assert the collection migrates to completion (no wedge). A second IT throws BEFORE `COMMIT` and asserts that target rows are NOT persisted and the next resume re-COPYs cleanly.
  - **Cross-file:** `Migrator.flushBatch`, `R2dbcMigrationTarget` (new `copyAndCheckpoint`, updated `ensureProgressTable` to also create staging tables), `PostgresMigrationTarget` interface.

#### B.4 — `r2mlh-m9`: `pool.shutdownNow()` interrupts in-flight COPY
- **Evidence:** `Migrator.java:103-105`. The pool drain is half the story: `copyIn` and `saveProgress` call `Mono.block(BLOCK_TIMEOUT)` with a 10-minute timeout, so the migrator thread itself is parked in `block()` — pool shutdown does not signal it.
- **Acceptance:**
  - **Failing test (pool drain):** unit test mocks an in-flight `Mono.block` and verifies a graceful shutdown waits for completion (or surfaces a clear `CopyBatchException` instead of a generic interrupt).
  - **Failing test (in-flight cancel):** start a migration on a worker thread, request shutdown, and assert the worker either (a) completes the current batch within a documented bound, or (b) sees an explicit `MigrationShutdownException` from `block()` — never a silent return of a partial value.
  - **Fix:** two parts.
    1. **Pool drain:** `pool.shutdown(); pool.awaitTermination(Duration.ofSeconds(30));` with an `if (!terminated) pool.shutdownNow();` fallback.
    2. **In-flight signal:** expose `Migrator.shutdownSignal` (an `AtomicBoolean` or `Disposable.Composite`) checked at the top of every batch loop iteration in `Migrator.migrateCollection` and `Migrator.migrateHighWaterMarks`; when set, complete the current batch then exit cleanly. Document the worst-case latency as "≤ one batch worth of COPY+staging time, bounded by `BLOCK_TIMEOUT`".

#### B.5 — `r2mlh-l8`: migrated journal rows reset `written_at` to `now()`
- **Evidence:** `CopyEncoder.JOURNAL_COLUMNS = "(pid, sn, manifest, tags, event)"` — `written_at` excluded; schema defaults `DEFAULT now()`.
- **Acceptance:**
  - **Failing test:** seed a `MigrationRecord` with a Mongo-side `written_at`; assert the migrated Postgres row carries that exact timestamp.
  - **Fix:** add `written_at` to `JOURNAL_COLUMNS`; have `MongoMigrationSource` carry the original timestamp on the record; encoder emits it in ISO instant format (same path as snapshot row at `CopyEncoder.java:95`). When the source record carries no timestamp, the encoder MUST log a per-batch WARN with `(pid, sn)` count and the chosen fallback (`now()` semantics retained for that row), so an operator sees the divergence even without reading the guide. Silent default is rejected.
- **Reordered:** moved BEFORE L-4 so L-4 can assert `written_at` parity end-to-end.

#### B.6 — `r2mlh-l4`: `--verify` only compares event payload
- **Evidence:** `Migrator.java:286-304` (`assertParity`); `R2dbcMigrationTarget.readPayload` (`:175-189`) reads only `event`/`snapshot` columns.
- **Acceptance:**
  - **Failing test:** seed a target row with corrupt `tags[]` (or wrong `manifest`/`lifecycle`/`written_at`) and assert `assertParity` reports the mismatch (currently silent).
  - **Fix:** extend `readPayload` to return all relevant columns; extend `assertParity` to compare them. With L-8 merged first, `written_at` is also compared (no tolerance needed); if the L-8 WARN-fallback path fires for a row, that fact is surfaced in the parity report.
- **Blocked by:** `r2mlh-l8` (must merge first so `written_at` is migrated, not synthesised).

#### B.7 — `r2mlh-l5`: `verifySchema` reports unknown collection as `MissingSchemaException`
- **Evidence:** `Migrator.java:129-140` catch `RuntimeException` rewraps everything as `MissingSchemaException`; `validateTargetTable` (`R2dbcMigrationTarget.java:271`) throws `IllegalArgumentException` for unknown names.
- **Acceptance:**
  - **Failing test:** invoke `verifySchema("doesnotexist_journal")`; assert a distinct `UnknownTargetTableException` (or precise message) is raised — NOT "run bootstrap".
  - **Fix:** introduce `UnknownTargetTableException`. In `verifySchema`, add an explicit `catch (final IllegalArgumentException iae)` clause BEFORE the `catch (final RuntimeException e)` rewrap — `validateTargetTable` is the documented source of `IllegalArgumentException` for unknown table names; rewrap it as `UnknownTargetTableException(targetTable, iae)`. Document the catch order in a code comment so future edits don't reorder it.

### Group C — `ConnectionPoolFactory.java`, `DefaultPostgresConfig.java`, `DefaultSslConfig.java`

Branch `pg-r2-mlh/pool`. 4 tickets; `M-5`/`L-11` share `ConnectionPoolFactory` so are sequential; `M-12` is `DefaultPostgresConfig`; `L-12` is `ConnectionPoolFactory` warn block.

#### C.1 — `r2mlh-m5`: validate `ssl.mode` at HOCON-load time
- **Evidence:** `DefaultSslConfig.java:48-55` only lowercases; pool-build calls `SSLMode.fromValue(...)` lazily.
- **Acceptance:**
  - **Failing test:** `DittoConfigError`-asserting test constructing `DefaultSslConfig` from HOCON `mode = "verify_full"` (typo).
  - **Fix:** validate against `SSLMode.values()` (lower-cased set) in the `DefaultSslConfig` constructor; throw `DittoConfigError` with a precise message naming the offending key. Leave `validateBootGuard()` at pool-build (scope narrowed).
  - **Note (review pass #4):** `SSLMode` is `io.r2dbc.postgresql.client.SSLMode`; `SSLMode.fromValue(...)` throws `IllegalArgumentException`, NOT `DittoConfigError`. The constructor must do a **membership check** against the lower-cased `SSLMode.values()` set (or wrap a `try { SSLMode.fromValue } catch (IllegalArgumentException)`) and rethrow `DittoConfigError` itself — do not let the raw r2dbc `IllegalArgumentException` escape, or the failing test (which asserts `DittoConfigError`) will not pass.

#### C.2 — `r2mlh-m12`: redact credentials embedded in `uri` in `toString()`
- **Evidence:** `DefaultPostgresConfig.java:122-131` emits `"uri=" + uri` raw.
- **Acceptance:**
  - **Parser choice (corrected — review pass #4):** R2DBC URLs use the compound scheme `r2dbc:postgresql:` (and `r2dbc:pool:postgresql:` for the pool wrapper) which `java.net.URI` parses as scheme=`r2dbc` + opaque part — userinfo is NOT extracted by `URI.getUserInfo()`. **Do NOT use `ConnectionFactoryOptions.parse(...)` as the redaction mechanism:** it returns a parsed options *bag* (`ConnectionFactoryOptions.Builder`, as already used at `ConnectionPoolFactory.java:153`) with **no public API to render the options back into a redacted URI string** — `toString()` here must emit a string, and the SPI parser cannot rebuild one without hand-assembling every part (userinfo, the `r2dbc:pool:` wrapper, options-map ordering), which is strictly more work than string surgery. **Chosen mechanism: targeted string redaction on the stored `uri` String.** Use a hand-written regex to (1) rewrite userinfo `user:secret@` → `user:****@` anchored on `r2dbc:(?:[^:]+:)?postgresql://(?:([^:@]+)(?::([^@]+))?@)?`, and (2) replace the values of known secret query params (`sslPassword`, `password`, `sslcert`, `sslkey`) with `****`. `ConnectionFactoryOptions.parse(...)` MAY be used read-only to *discover* secret option keys, but the emitted string is built by string replacement, never by rebuilding from the parsed bag.
  - **Failing tests (parametrised):**
    - `r2dbc:postgresql://user:secret@host:5432/db?sslPassword=x` → `user:****@host:5432/db?sslPassword=****`
    - `r2dbc:postgresql://u:p@h/db` (no port, no query)
    - `r2dbc:pool:postgresql://u:p@h/db?maxIdleTime=PT1M&password=p2` (pool wrapper + alt password param)
    - Malformed URI (e.g. unbalanced brackets) → literal `"<redacted>"`, no exception thrown.
    - URI with no userinfo and no secret params → pass through verbatim.
  - **Fix:** string-redact the raw `uri` (see corrected parser choice above); rewrite userinfo to `user:****`; replace values of known secret query params (`sslPassword`, `password`, `sslcert`, `sslkey`) with `****`; fall back to a literal `"<redacted>"` if the regex match fails or throws. Centralise as `redactSecrets(String uri)`. Also redact the value in `LOGGER.info("Postgres config: ...")`-style call sites if any reference `uri` directly.

#### C.3 — `r2mlh-l11`: add statement / connect timeout
- **Evidence:** `ConnectionPoolFactory.java:145-175` (`baseOptions`) sets neither.
- **Acceptance:**
  - **Semantics — PgBouncer interaction:** plain `SET statement_timeout = ...` on a session is **lost across PgBouncer TRANSACTION-pooling borrows**. The fix must enforce the timeout in a way that survives `RESET` between borrows. Three viable mechanisms — pick (b):
    - (a) `SET LOCAL statement_timeout` inside every txn — requires hooking every write path (many sites; rejected).
    - (b) **r2dbc-postgresql `options` runtime parameter:** applied at connection startup and reissued by PgBouncer's `server_reset_query` if `options=` is in the startup packet. THIS is the chosen approach. **Encoding correction (review pass #4):** r2dbc-postgresql 1.0.7's `PostgresqlConnectionFactoryProvider.OPTIONS` expects a **`Map<String,String>` of GUC name→value** (e.g. `Map.of("statement_timeout", "60s")`), applied as startup parameters — NOT a libpq `-c statement_timeout=60s` string. Bind via `builder.option(PostgresqlConnectionFactoryProvider.OPTIONS, Map.of("statement_timeout", "60s"))` (or the equivalent `Option.valueOf("options")` carrying a Map). The unit assertion must check the bound Map entry, and the IT (below) is the real proof the GUC took effect. Confirm the exact key/value shape against r2dbc-postgresql 1.0.7 before writing the unit test — a raw `-c ...` string is likely silently ignored.
    - (c) Document "no enforcement under TRANSACTION-mode PgBouncer" — rejected; defeats the ticket.
  - **Failing tests:**
    - Unit: `baseOptions` carries non-null `CONNECT_TIMEOUT` and the `options` startup parameter contains `-c statement_timeout=...`.
    - IT (`PgBouncerTransactionPoolingIT` neighbour): execute a query that sleeps longer than the configured `statement-timeout` via PgBouncer transaction-pooling; assert it aborts with the expected `57014` (`query_canceled`) SQLSTATE.
  - **Fix:** introduce `statement-timeout` and `connect-timeout` keys in `DefaultPostgresConfig` with documented defaults (`connect-timeout = 5s`, `statement-timeout = 60s`); wire `CONNECT_TIMEOUT` directly and `statement_timeout` via the `options` runtime parameter in `baseOptions`. Document interaction with `max-acquire-time` AND the PgBouncer-survival rationale in HOCON comments.

#### C.4 — `r2mlh-l12`: pooler-mode warn — fire whenever `prepared-statement-cache-queries > 0`
- **Evidence:** `ConnectionPoolFactory.java:203-216` — warn only inside `if (poolerMode == TRANSACTION)`.
- **Acceptance:**
  - **Failing test:** unit test with `poolerMode = DIRECT` and `prepared-statement-cache-queries = 256`, asserts a WARN log line ("a future flip to TRANSACTION pooling would break this cache").
  - **Fix:** restructure `logPoolerModeCrossCheck` to warn on any mode where `cacheQueries > 0` AND `poolerMode != SESSION`; keep the existing INFO for the safe TRANSACTION + 0 case.

### Group D — `PoolMetricsPoller.java`

Branch `pg-r2-mlh/poller`. 1 ticket (two sub-fixes in one file).

#### D.1 — `r2mlh-l10`: poller robustness + gauge reset on close
- **Evidence:** `PoolMetricsPoller.java:165` (`catch RuntimeException`), `:178-185` (close doesn't reset gauges).
- **Acceptance:**
  - **Catch scope (important):** `catch (Throwable)` is wrong — it swallows `OutOfMemoryError`, `StackOverflowError`, and other `VirtualMachineError`s, hiding JVM-fatal conditions. Use a narrower pattern: `catch (final Exception | LinkageError e)` (covers checked + most runtime + classloader failures), and let `VirtualMachineError` / `ThreadDeath` propagate. Alternative: `catch (final Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; LOGGER.warn(...); }` — pick whichever the surrounding module already uses.
  - **Failing test (a, resilience):** `metricsSupplier.get()` throws a plain `Error` (e.g. `AssertionError`); assert the scheduler task continues running on the next tick.
  - **Failing test (a-bis, JVM-fatal propagation):** `metricsSupplier.get()` throws `OutOfMemoryError`; assert it propagates out of the tick and is NOT silently logged.
  - **Failing test (b, gauge reset):** unit test calls `close()` and asserts gauges `POOL_ACQUIRED/POOL_ALLOCATED/POOL_IDLE/POOL_PENDING` are set to `0` (currently last-value forever).
  - **Fix:** widen `catch` per the chosen pattern; in `close()` **cancel the scheduled task first** (`scheduledTask.cancel(true)` and let any in-flight tick drain), **then** emit zero values for the four gauges through the `GaugePublisher` sink. **Ordering correction (review pass #5):** emitting zero *before* cancelling leaves a race where a tick firing between the zero-emit and the cancel re-publishes the stale last value. The unit test passes either ordering (no concurrent poll), so assert the cancel-then-reset order explicitly in code and a comment.

### Group E — `ditto-postgres-persistence.conf` + `PostgresPersistenceBackendProvider.java`

Branch `pg-r2-mlh/hocon`. 2 tickets; both touch the conf file but at non-overlapping line ranges.

#### E.1 — `r2mlh-m4`: require explicit `read-journal.entity`
- **Evidence:** `ditto-postgres-persistence.conf:54-55` (default `"things"`); `PostgresPersistenceBackendProvider.java:87-88, 203-210` (silent default — `resolveReadJournalEntity()` falls back to `DEFAULT_READ_JOURNAL_ENTITY = "things"` at line 206).
- **BREAKING-CHANGE GUARD (review pass #4 — Critical #2):** Removing the HOCON default and throwing on missing breaks startup of **every** service that activates the Postgres profile, **including things**. Verified at HEAD: only the profile sets `read-journal.entity`; no service conf sets it explicitly — `things.conf`, `policies.conf`, `connectivity.conf` each set only `read-journal-batch-size`. So this ticket MUST also add the explicit per-service override, or green-main service startup/ITs break.
- **WoT correction (review pass #5 — Critical #1):** there is **no** separate WoT service/conf and WoT is **not** a read-journal consumer. WoT validation-config persistence runs inside the **Things** actor system (`ThingsRootActor` starts both the `thing` and `wot-validation-config` shard regions), `read-journal.entity` is a **single system-wide value**, and the Things JVM's lone `getReadJournal()` (`ThingsRootActor.java:120`) serves the Things read side only. WoT writes/recovers via its own `ditto-postgres-wot-journal/snapshots` plugin IDs, independent of `read-journal.entity`. So the per-service override targets are **three** services: `things` (→ `things`), `policies` (→ `policies`), `connectivity` (→ `connections`). Setting `read-journal.entity="wot"` anywhere in the Things JVM would point its single read journal at `wot_*` tables and break Thing read-side queries — do **not** add a WoT override.
- **Acceptance:**
  - **Failing test:** provider construction with `read-journal.entity` unset on a non-things service asserts `DittoConfigError` (currently silently binds to `things_*`).
  - **Fix (code/profile):** remove the default in HOCON; `resolveReadJournalEntity()` throws `DittoConfigError("ditto.persistence.r2dbc.read-journal.entity is required when the Postgres profile is active")` when missing.
  - **Fix (per-service config — required, same ticket):** add an explicit `ditto.persistence.r2dbc.read-journal.entity` (with `${?POSTGRES_READ_JOURNAL_ENTITY}` env override) to each consuming service config: `things/service/src/main/resources/things.conf = "things"`, `policies/service/src/main/resources/policies.conf = "policies"`, `connectivity/service/src/main/resources/connectivity.conf = "connections"`. **No WoT override** (see WoT correction above — WoT runs in the Things JVM and is not a read-journal consumer). Grep-confirm no other service activates the profile before finalising the list.
  - **Per-service acceptance:** for each of the **three** services, a config-load/startup smoke assertion that the provider resolves the correct entity (and does NOT throw). At minimum a test that loads each service's HOCON and asserts `resolveReadJournalEntity()` returns the expected value.

#### E.2 — `r2mlh-hy2`: delete dead `ditto-postgres-<entity>-journal-read` HOCON blocks
- **Evidence:** `ditto-postgres-persistence.conf` lines 155-159 (things), 188-192 (policies), 221-225 (connections), 255-259 (wot). `PostgresReadJournal` has no Pekko-plugin `(ExtendedActorSystem, Config, String)` ctor.
- **Acceptance:**
  - **No code test needed** — this is dead config. Gate with the existing `PostgresPluginLifecycleIT` + `mvn verify` to confirm nothing in the runtime path references them. Optional: a unit test that loads the profile and asserts `PersistenceQuery.readJournalFor(...)` against the deleted block IDs fails fast — but this is nice-to-have.
  - **Fix:** delete the 4 blocks + their describing comment header.

### Group F — Tests, hygiene, documented-no-action (parallelisable, mostly independent files)

Branch `pg-r2-mlh/tests`. 6 tickets; all in distinct files so fully parallel.

#### F.1 — `r2mlh-l1`: DELETED-lifecycle exclusion IT
- **File:** `PostgresParityMatrixIT.java` (extend `:293-304`).
- **Acceptance:** add IT case that seeds two snapshots (`{"__lifecycle":"DELETED"}` + ACTIVE), runs `getNewestSnapshotsAbove(includeDeleted=false, ageWindow=Duration.ZERO)`, asserts only ACTIVE returned. Re-run includeDeleted=true asserts both.
  - **ageWindow correction (review pass #5):** use `Duration.ZERO` (matching the working `PostgresParityMatrixIT:293-304`). The SQL predicate is `written_at < now() - $2::interval`; a **non-zero** window would exclude the freshly-seeded rows (their `written_at ≈ now()`), so the assertion would run against an empty set. If a non-zero window is ever needed for this case, the seeded rows' `written_at` must be explicitly backdated past the window.

#### F.2 — `r2mlh-l2`: read-journal contract test value assertions
- **File:** `PostgresReadJournalContractTest.java:90-139`.
- **Acceptance:** for at least `currentEventsByTag`, `getNewestSnapshotsAbove`, `getLatestEventSeqNo`, stub representative rows on the mock `PostgresPersistenceOperations` and assert mapped output values — not just `doesNotThrowAnyException`.

#### F.3 — `r2mlh-m10`: jsonb number canonicalization round-trip
- **File:** new IT or extend `PostgresParityMatrixIT.java`.
- **Pre-decided path (probe run 2026-06-15 against Postgres 16):** option **(i) — keep `jsonb`, assert the post-canonical form**. Probe input `{"intish": 21.0, "expish": 1E10, "bigint": 9007199254740993, "negexp": -2.5E-3, "keys":{"b":1,"a":2}, "  ws  ":"x"}` round-tripped through a `jsonb` column emits `{"keys": {"a": 2, "b": 1}, "  ws  ": "x", "bigint": 9007199254740993, "expish": 10000000000, "intish": 21.0, "negexp": -0.0025}`. Documented canonicalisation rules:
    1. **Object keys are sorted alphabetically** within each nested object (note: top-level shows insertion-order in PG16 output, but jsonb does NOT promise top-level order — treat as undefined).
    2. **Scientific notation is flattened** to plain decimal (`1E10` → `10000000000`, `-2.5E-3` → `-0.0025`).
    3. **Arbitrary-precision integers preserved** beyond IEEE 754 double range (no float-precision loss).
    4. **Whitespace inside string keys/values preserved**; inter-token whitespace stripped.
- **Acceptance:**
  - **Failing test (semantic equality, the contract):** round-trip the probe payload through journal write → `readPayload` → `JsonFactory.newObject(...)`; assert semantic equality (key-value) with the original input. THIS is the production contract (consumers re-parse via `JsonFactory`).
  - **Failing test (canonical-form snapshot):** round-trip the same payload; assert the raw `event::text` output matches the documented canonical form. This pins the canonicalisation behaviour so a future PG upgrade that changes it surfaces as a test break, not a silent divergence.
  - **Documentation:** the four rules above become a code comment on `CopyEncoder.JOURNAL_COLUMNS`/`event` and a paragraph in the operator guide.
- **Rejected — option (ii) `event TEXT`:** would preserve byte-exact JSON but loses jsonb indexability, increases storage cost, and prevents query-side jsonb predicates. Documented as rejected here so a future maintainer doesn't reopen.

#### F.4 — `r2mlh-l15b`: lifecycle IT covers post-snapshot replay
- **File:** `PostgresPluginLifecycleIT.java:123-144`.
- **Acceptance:** extend the existing scenario — after the snapshot, persist 2 more events, stop the actor, recover, assert `state.size() == 5` (3 in snapshot + 2 from replay).

#### F.5 — `r2mlh-hy1`: strip `(WU 8)` and `[G1]` plan tags
- **Files:** `internal/utils/persistence/src/main/java/.../PostgresSnapshotAdapter.java:64`, `internal/utils/persistence/src/test/java/.../SnapshotAdapterParityTest.java:171`.
- **Acceptance:** rewrite comments behaviour-first; no plan tags.

#### F.6 — `r2mlh-hy4-confirm`: documented no-action confirmation
- Round-2 doc explicitly says no action required for shared dispatchers in WoT Postgres blocks (`ditto-postgres-persistence.conf:237, 257, 263`). Open as a closed-on-arrival ticket carrying the rationale + a pointer to the round-2 doc, so future audits don't re-raise it. **No code change.**

## 5. Build / verify gates

- **Per ticket:** TDD — failing test first, fix, test green, `mvn -pl :ditto-internal-utils-persistence-r2dbc test` (or appropriate module).
- **Per group merge into `pg-r2-mlh`:** full module `mvn verify` with enforcer ON, Docker available. Must be green (158+ unit + 30+ ITs). For Group A/F changes that touch `internal/utils/persistence`, also `mvn -pl :ditto-internal-utils-persistence test`.
- **Pre-PR:** full repo `mvn verify` and the existing `PostgresParityMatrixIT`, `PostgresPluginLifecycleIT`, `PgBouncerTransactionPoolingIT` must stay green.

## 6. Workflow

Run the round-2 pattern via `/forge-implement`:
- One agent per group branch (6 agents); within a group, agent works tickets sequentially in the order above.
- Each ticket: implement (TDD) → spawn 2-lens adversarial review panel (correctness + reuse/altitude); on rejection, hand the diff + findings to a FRESH agent — never the author.
- After group is green, fast-forward merge into `pg-r2-mlh`.
- Final `mvn verify` on `pg-r2-mlh`; then squash-merge (or fast-forward) into `postgres-190626-feat-dev` after user confirmation.

## 7. Out of scope (explicit)

- Plan 1's IT-suite work (M-11, L-3, real-flow harness, service-module ITs) — owned by `docs/superpowers/plans/2026-06-02-postgres-it-suite-plan.md`.
- Round-2 Highs already remediated (C-1, C-2, C-3, H-1, H-2, H-6, H-8, H-9, H-10, R-1).
- Load / OOM threshold testing (residual risk noted in round-2 §6) — needs a populated DB and replicas; tracked separately.
- Migration fault injection beyond the M-8 resume case (full crash-during-COPY matrix from round-2 §6) — Group B covers the unit-level reproduction; full fault-injection matrix is a separate plan.
- PgBouncer transaction-pooling validation behind real PgBouncer with positive `fetch-size` — H-2 fix is in place; full validation needs an environment with PgBouncer-in-CI.

## 8. Tickets summary table (for `/forge-to-beads`)

| Ticket | Group | File(s) | Severity | Blocked by |
|---|---|---|---|---|
| `r2mlh-m1` | A | `PostgresPersistenceOperations.java` | M | — |
| `r2mlh-m7` | A | `PostgresPersistenceOperations.java` | M | `r2mlh-m1` |
| `r2mlh-l6` | A | `PostgresJournalOps.java` | L | `r2mlh-m7` |
| `r2mlh-l15a` | A | `PostgresPersistenceOperations.java` | L | `r2mlh-l6` |
| `r2mlh-hy3` | A | `PostgresPersistenceOperations.java` | HY | `r2mlh-l15a` |
| `r2mlh-h12` | B | `CopyEncoder.java` | H | — |
| `r2mlh-h11` | B | `MongoMigrationSource.java`, `Migrator.java`, `R2dbcMigrationTarget.java` | H | `r2mlh-h12` |
| `r2mlh-m8` | B | `Migrator.java`, `R2dbcMigrationTarget.java` | M | `r2mlh-h11` |
| `r2mlh-m9` | B | `Migrator.java` | M | `r2mlh-m8` |
| `r2mlh-l8` | B | `CopyEncoder.java`, `MongoMigrationSource.java` | L | `r2mlh-m9` |
| `r2mlh-l4` | B | `Migrator.java`, `R2dbcMigrationTarget.java` | L | `r2mlh-l8` |
| `r2mlh-l5` | B | `Migrator.java`, `R2dbcMigrationTarget.java` | L | `r2mlh-l4` |
| `r2mlh-m5` | C | `DefaultSslConfig.java` | M | — |
| `r2mlh-m12` | C | `DefaultPostgresConfig.java` | M | — |
| `r2mlh-l11` | C | `ConnectionPoolFactory.java`, `DefaultPostgresConfig.java` | L | `r2mlh-m5` |
| `r2mlh-l12` | C | `ConnectionPoolFactory.java` | L | `r2mlh-l11` |
| `r2mlh-l10` | D | `PoolMetricsPoller.java` | L | — |
| `r2mlh-m4` | E | `ditto-postgres-persistence.conf`, `PostgresPersistenceBackendProvider.java`, `things.conf`, `policies.conf`, `connectivity.conf` | M | — |
| `r2mlh-hy2` | E | `ditto-postgres-persistence.conf` | HY | `r2mlh-m4` |
| `r2mlh-l1` | F | `PostgresParityMatrixIT.java` | L | — |
| `r2mlh-l2` | F | `PostgresReadJournalContractTest.java` | L | — |
| `r2mlh-m10` | F | `PostgresParityMatrixIT.java` | M | `r2mlh-l1` |
| `r2mlh-l15b` | F | `PostgresPluginLifecycleIT.java` | L | — |
| `r2mlh-hy1` | F | `PostgresSnapshotAdapter.java`, `SnapshotAdapterParityTest.java` | HY | — |
| `r2mlh-hy4-confirm` | F | (no change) | HY | — |

**24 tickets total.** Within-group `blocked by` chains enforce serial work on the same file; across groups all are parallel. Estimated wall-clock with 6 agents running their group serially: longest = Group B (7 tickets in the migrator subsystem). Plan-1 work on `postgres-it-suite` can proceed in parallel — no file overlap.

---

## Review Pass — 2026-06-15 (HOLD SCOPE)

Mode: HOLD SCOPE. Plan claims were verified file-by-file against HEAD `2fc7dd9ba1`; all 23 source-citation claims confirmed (4 already-fixed safe to drop, 19 bugs confirmed present). Triage and branch strategy are sound; complexity is justified (24 tickets across 6 file-disjoint groups). Findings below are **fix-design** defects in individual tickets, not scope challenges.

### Critical (must fix before proceeding to beads)

1. **A.1 — Reactor API mismatch in fix description.** Plan says "switch to the 4-arg `usingWhen(resource, work, commit, rollback)` overload." Reactor's `Mono.usingWhen` has only **3-arg** (`resource, closure, cleanup`) and **5-arg** (`resource, closure, asyncComplete, asyncError, asyncCancel`) overloads. There is no 4-arg variant. Rewrite the fix as: use the **5-arg** overload with `asyncComplete = commit`, `asyncError = (conn, err) -> rollback(conn).onErrorComplete().then(close(conn))`, `asyncCancel = conn -> rollback(conn).onErrorComplete().then(close(conn))`. The existing inline `onErrorResume(rollback)` inside the closure (lines 546-548) should be removed to avoid double-rollback on error.

2. **B.2 / H-11 — Missing DDL migration for `migration_progress` schema change.** Adding `lastWrittenAt` to `Cursor` requires altering the `migration_progress` table (`R2dbcMigrationTarget.java:84-87` defines `last_pid TEXT, last_sn BIGINT` only — no `last_written_at` column). The plan never mentions: (a) `ALTER TABLE migration_progress ADD COLUMN last_written_at TIMESTAMPTZ NULL`, (b) how `loadProgress` maps a NULL `last_written_at` from a 2-tuple-era checkpoint, (c) how `saveProgress` binds the new column. Add an explicit DDL step and a forward-compat acceptance test: "Resume from a pre-existing 2-tuple checkpoint must complete without re-COPYing the entire collection."

3. **B.3 / M-8 — Option A (staging table) collides with current connection topology.** The plan picks Option A but `copyIn` (lines 162-170) acquires its own pooled connection while `saveProgress` (lines 134-152) goes through `client.executeUpdatePublisher` (a separately-acquired connection). Both Option A and Option B require **pinning a single connection** for the COPY + INSERT-from-staging + saveProgress sequence. The ticket must specify the connection-pinning seam (likely a new `MigrationTarget.copyAndCheckpoint(...)` method that owns one connection end-to-end) or the whole atomicity argument is defeated. Also: TEMP TABLE with `ON COMMIT DROP` works through PgBouncer transaction-pooling only if all statements run in the same txn on the same backend — confirm or use a permanently-named `migration_staging_<collection>` table truncated per batch.

4. **B.5 / L-4 depends on B.7 / L-8 but plan orders L-4 → L-5 → L-8.** L-4 asks `assertParity` to compare `written_at`, which makes sense only after L-8 migrates the source `written_at`. As ordered, L-4 must either (a) skip `written_at` (defeating its acceptance test) or (b) accept a divergence the underlying code will only fix in L-8 (failing on green main). Reorder: **L-8 before L-4** in Group B (`r2mlh-l8` blocks `r2mlh-l4`), and update the ticket table.

5. **F.3 / M-10 — "Decide in ticket comment after observing failing assertion" violates TDD discipline.** This punts the design decision (jsonb canonicalization tolerated vs. schema change `event jsonb → event TEXT`) onto the implementing agent. Option (ii) is a **schema change with migration impact on all existing rows**; deciding it inside a ticket without prior alignment risks an unreviewed schema flip mid-stream. Action: run the canonicalization probe **before opening the ticket**, write the verdict into the plan, then open a single concrete ticket for the chosen path.

### Important (fix in next review pass)

6. **A.4 / L-15a — Audit other `now()` usages in the same transaction.** The plan fixes only the journal INSERT. If `_journal_seq` upserts or any other co-txn DML also use `now()`, an artificial multi-event AtomicWrite would get `clock_timestamp()` events but `now()` watermark rows — inconsistent ordering. Add an acceptance bullet: grep `PostgresPersistenceOperations.java` for every `now()`, document which require txn-start vs. wall-clock semantics, and either flip them too or annotate why each stays.

7. **B.1 / H-12 — NUL-only rule may under-cover.** Postgres' COPY-from-STDIN rejects more than just `\x00`; backslash-`.` end-of-data and invalid UTF-8 sequences also fail per-row. Either (a) tighten the rejection set to "NUL + invalid UTF-8 + bare `\.`" with a single `validateCopyPayload(String)` helper used by both `field` and `arrayLiteral`, or (b) explicitly scope the ticket to "NUL only" and open a follow-up. Don't quietly limit it.

8. **C.2 / M-12 — `java.net.URI` may not parse `r2dbc:postgresql://` cleanly.** R2DBC URLs use a compound scheme (`r2dbc:postgresql:`) that `java.net.URI` parses as scheme `r2dbc` + opaque part `postgresql://user:secret@host`. The plan's "parse `uri` with `URI`; rewrite userinfo" pseudocode will silently fall through to `<redacted>` for most real configs. Acceptance test must exercise the actual r2dbc URI form: `r2dbc:postgresql://u:p@h:5432/db?sslPassword=x` AND `r2dbc:postgresql://u:p@h/db` AND `r2dbc:pool:postgresql://u:p@h/db` (the pool wrapper). Decide whether to use the r2dbc-spi `ConnectionFactoryOptions.parse` helper (which knows compound schemes) or a hand-written regex.

9. **C.3 / L-11 — `statement_timeout` semantics under PgBouncer transaction-pooling.** Setting `statement_timeout` via driver `options` translates to `SET statement_timeout = ...` per session, which **does not survive across PgBouncer-transaction borrows**. To enforce it reliably in TRANSACTION mode, either (a) issue `SET LOCAL statement_timeout` at txn start (requires write-path hook), (b) prepend `statement_timeout` via the r2dbc-postgresql `options` parameter (driver-side runtime parameter, applied at startup and reissued by pgbouncer's `RESET` flow), or (c) accept that TRANSACTION-mode users get no enforcement and document it. Pick one and write the test against that mode.

10. **B.4 / M-9 — `pool.awaitTermination(30s)` doesn't drain in-flight `Mono.block()` calls.** `copyIn` and `saveProgress` block the calling thread for up to 10 minutes (`BLOCK_TIMEOUT`). Pool shutdown doesn't cancel a thread blocked on `Mono.block`. The ticket must specify how shutdown signals an in-flight COPY: either (a) thread-interrupt and let `block` throw, (b) expose a `Disposable` for the running migration and dispose it, or (c) document that a 10-min worst-case wait is acceptable. The current "30s awaitTermination then shutdownNow" treats only the pool's idle connections, not the work.

11. **D.1 / L-10 — `catch Throwable` swallows `OutOfMemoryError`/`StackOverflowError`.** Widening to `Throwable` keeps the scheduler running through a JVM-fatal error and masks real problems. Better: `catch (final Exception | Error e) { if (e instanceof VirtualMachineError) throw (VirtualMachineError) e; LOGGER.warn(...); }`. Acceptance test: throw a synthetic `Error` (e.g. `AssertionError`) and assert the next tick runs; separately, throw `OutOfMemoryError` and assert it propagates.

12. **B.6 / L-5 — Catch-order specificity.** Plan says "catch and propagate `UnknownTargetTableException` before the `MissingSchemaException` catch", but the underlying `validateTargetTable` throws `IllegalArgumentException` (`R2dbcMigrationTarget.java:271`). Either (a) wrap/rethrow `IllegalArgumentException` as the new exception at the boundary, or (b) catch `IllegalArgumentException` directly before the `RuntimeException` catch. Be explicit about which.

### Nice-to-have (add to TODOS)

13. **F.6 / hy4-confirm** doesn't need a ticket. Add a one-line comment in `ditto-postgres-persistence.conf` near line 237 pointing to `postgres-persistence-backend-critical-review-round2.md#HY-4`; that's the durable anti-rediscovery anchor.

14. **B.7 / L-8 fallback wording.** "If Mongo's journal does not carry one, document the fallback explicitly in the operator guide" reads as a silent-failure smell. Better: WARN-log per-batch with the affected `(pid, sn)` count and the chosen fallback (current `now()` semantics retained), so an operator sees it even without reading the guide.

15. **A.4 unit-level SQL string assertion is brittle.** "Assert journal INSERT contains `clock_timestamp()` (not `now()`)" breaks on any whitespace change. Prefer the IT alternative (2-event AtomicWrite asserting distinct `written_at`), but the plan owes a confirmation that AtomicWrite size > 1 can be synthesised in-test. If it cannot, accept the brittle assertion and add a comment in the test explaining the trade-off.

### Accepted scope changes

- No scope expansion. The 24-ticket scope holds.

### Explicitly NOT in scope

- Re-litigating the "already-fixed" set (M-2, M-3, M-6, H-5) — verified.
- Re-opening the Plan-1 boundary (M-11, L-3 stay in Plan 1).
- Connection-topology refactor beyond what M-8 strictly needs.
- Reactor-cancellation audit across the whole module beyond `inTransaction` (M-1) — flagged but out of scope.

---

## Review Pass — 2026-06-15 #2 (HOLD SCOPE, post-edit)

Pass #1 surfaced 5 Critical and 7 Important findings. All 12 have been applied to the ticket bodies above. Specific changes:

### Critical findings resolved in-plan

1. **A.1 / M-1** — Fix description rewritten to use the **5-arg** `Mono.usingWhen` overload with explicit `asyncComplete`/`asyncError`/`asyncCancel` closures. Inline `onErrorResume(rollback)` removed to avoid double-rollback. Audit note added covering peer `usingWhen` call sites.
2. **B.2 / H-11** — DDL migration step added (`ALTER TABLE migration_progress ADD COLUMN IF NOT EXISTS last_written_at TIMESTAMPTZ NULL`). New forward-compat acceptance test seeds a 2-tuple-era `migration_progress` row and asserts resume completes without re-COPYing. Journal source explicitly keeps 2-tuple semantics; snapshot source uses 3-tuple when `last_written_at` is non-null.
3. **B.3 / M-8** — Connection-pinning seam specified: new `MigrationTarget.copyAndCheckpoint(...)` owns one connection BEGIN→COMMIT. Permanently-named staging tables (`migration_staging_<target>`) keep PgBouncer transaction-pooling viable; `TRUNCATE` inside the txn is rolled back on abort. Option B (single non-pooled connection) documented as rejected with reason.
4. **B.5 ↔ B.7 reorder** — `r2mlh-l8` now sits at B.5 and blocks `r2mlh-l4` (B.6). Ticket-summary `blocked by` chain updated: `m9 → l8 → l4 → l5`.
5. **F.3 / M-10** — Pre-decided as **option (i) keep `jsonb`** based on a real probe against Postgres 16 (run 2026-06-15). Four canonicalisation rules enumerated in the ticket (key sort within objects, scientific-notation flatten, arbitrary-precision integer preservation, in-string whitespace preserved). Two failing tests: semantic equality (the contract) and canonical-form snapshot (regression guard). Option (ii) documented as rejected with reason.

### Important findings resolved in-plan

6. **A.4 / L-15a** — Added an audit step listing every `now()` use in `PostgresPersistenceOperations`; ticket comment must record which require txn-start vs. wall-clock semantics. Test fragility flagged with explicit fallback comment requirement.
7. **B.1 / H-12** — Scope widened from NUL-only to full set of COPY-incompatible bytes (NUL + invalid UTF-8 + bare `\.`). Centralised `validateCopyPayload(...)` helper specified; `CopyEncodingException(pid, sn, reason)` with three reasons. Per-row split-out in `Migrator.flushBatch` (so one bad row no longer wedges the batch).
8. **C.2 / M-12** — Parser choice documented: `ConnectionFactoryOptions.parse` (r2dbc-spi) is the chosen mechanism; `java.net.URI` rejected as it parses `r2dbc:postgresql:` as opaque. Five parametrised tests added (incl. pool wrapper and malformed URI).
9. **C.3 / L-11** — PgBouncer-survival rationale documented; chosen mechanism is the r2dbc-postgresql `options` runtime parameter (`-c statement_timeout=...`), applied at connection startup so it survives `RESET` between borrows. IT against `PgBouncerTransactionPoolingIT` neighbour asserts SQLSTATE `57014`.
10. **B.4 / M-9** — Fix now has two parts: (1) pool drain via `awaitTermination(30s)`, (2) in-flight shutdown signal checked at batch-loop iteration head with worst-case-latency bound documented. New "in-flight cancel" failing test added.
11. **D.1 / L-10** — Catch scope narrowed from `Throwable` to `Exception | LinkageError` (or explicit `VirtualMachineError` rethrow). Three failing tests: resilience to plain `Error`, propagation of `OutOfMemoryError`, gauge reset on `close()`.
12. **B.6 / L-5** — Catch order made explicit: `IllegalArgumentException` before the `RuntimeException` rewrap, with a code comment to prevent future reorderings. `UnknownTargetTableException` documented as the wrapper class.

### Residual nice-to-have (not applied)

- F.6 / hy4-confirm — ticket retained as the safer durable-anchor option; can collapse to a code comment later.
- Reactor-cancellation module-wide audit — explicitly out of scope.

### Verdict

All Critical findings resolved in-plan. All Important findings resolved in-plan. The plan is ready for `/forge-to-beads`.

---

## Review Pass — 2026-06-15 #3 (HOLD SCOPE)

Mode: HOLD SCOPE. Third pass after pass #2 declared "ready for beads." This pass re-grounded the two largest tickets (B.3/M-8, A.1/M-1, B.1/H-12, A.4/L-15a) against HEAD `2fc7dd9ba1` source rather than re-reading plan claims. All previously-verified citations re-confirmed: `inTransaction` 3-arg `usingWhen` + inline `onErrorResume` (`PostgresPersistenceOperations.java:540-550`); journal INSERT `now()` (`:131`); `getNewestSnapshotsAbove` missing `written_at` tiebreaker (`:473-475`); `Math.min` no-op (`:184`); 2-tuple `Cursor` (`MongoMigrationSource.java:110`); `migration_progress` has no `last_written_at` (`R2dbcMigrationTarget.java:84-87`); two-connection split for `copyIn`/`saveProgress` (`:162-170` vs `:134-152`); `toString` raw `uri` (`DefaultPostgresConfig.java:126`); `CopyEncoder.field` NUL falls through `default` (`:114`); `JOURNAL_COLUMNS` excludes `written_at` (`:43`).

Pass #1 and #2 covered the M-8 *connection-pinning* problem (one connection for COPY+INSERT+checkpoint). This pass found **two further M-8 landmines that the redesign itself creates**, plus one false-premise correction. All three are now applied to the ticket bodies above.

### Critical (resolved in-plan this pass)

1. **B.3/M-8 — `copied != rows.size()` guard breaks under `ON CONFLICT DO NOTHING`.** M-8 switches the target write to `INSERT … FROM staging … ON CONFLICT DO NOTHING`, which on an idempotent resume legitimately inserts *fewer* rows than were submitted (duplicate keys absorbed). The existing all-or-nothing guard at `Migrator.java:193` (`if (copied != rows.size()) throw CopyBatchException`) would misread that as a rolled-back batch and abort **every** resume — re-creating the very wedge class this plan closes. **Resolved:** `copyAndCheckpoint` returns the **staging-COPY row count** (exact, all-or-nothing for COPY framing errors) rather than the target-INSERT count, so the guard still detects COPY corruption while ON-CONFLICT dup-absorption is decoupled from it. Contract documented on the method Javadoc + the `CopyBatchException` throw site; new resume-idempotency failing test added to B.3.

2. **B.3/M-8 — new `copyAndCheckpoint` transaction re-introduces M-1's cancellation leak.** The new method owns an explicit `BEGIN→COMMIT`; if it copies the legacy `copyIn` 3-arg `usingWhen(create, work, Connection::close)` form, a cancelled COPY closes the connection without rolling back → "idle in transaction" leak — M-1 reborn in the same PR. **Resolved:** B.3 now mandates the 5-arg `usingWhen` pattern from A.1 (`asyncCancel`/`asyncError = rollback.onErrorComplete().then(close)`) with a cancel-mid-COPY failing test. Noted that the pattern is code-local so no cross-group merge gate is needed, but A.1 should land first if convenient.

### Nice-to-have / corrections (applied)

3. **A.4/L-15a — false premise in the evidence.** The ticket told the agent to check whether the `_journal_seq` upsert "keeps `now()`"; the actual upsert (`PostgresPersistenceOperations.java:139-146`) binds only `(pid, highest_sn)` with no timestamp column, so the feared per-row-vs-watermark ordering inconsistency cannot occur. Corrected in-ticket: the journal INSERT at line 131 is the only site needing the `clock_timestamp()` change; the audit step still runs to confirm no other co-txn `now()` exists.

### Confirmed correct (no change)

- **A.2/M-7 scoping.** `getLatestJournalEntries` (`:463-466`) uses the same `DISTINCT ON (pid) … ORDER BY pid, sn DESC` shape but the journal `(pid, sn)` PK forbids ties, so it is deterministic by construction. Limiting the `written_at` tiebreaker to the snaps side (whose PK is `(pid, sn, written_at)`) is correct — no journal-side fix needed.
- **B.7/L-5 catch order.** `readPayload` calls `validateTargetTable` first (`:176`), which throws `IllegalArgumentException` (`:271`) for unknown tables, caught by `verifySchema`'s `catch (RuntimeException)` (`:136-137`) and rewrapped as `MissingSchemaException`. The planned `catch (IllegalArgumentException)`-before-`RuntimeException` fix is correct.

### Verdict

Two new Critical M-8 landmines and one evidence correction resolved in-plan. No scope change. The 24-ticket scope holds. The plan is ready for `/forge-to-beads`.

---

## Review Pass — 2026-06-15 #4 (HOLD SCOPE)

Mode: HOLD SCOPE. Fourth pass after #3 declared "ready for beads." Method: re-ran an independent file-by-file ground-truthing of **all 24 tickets** against HEAD `2fc7dd9ba1` via parallel readers, plus first-hand reads of the four highest-stakes sites (`inTransaction`, `getNewestSnapshotsAbove`, `DefaultPostgresConfig.toString`, `ConnectionPoolFactory.baseOptions`) and the `read-journal.entity` consumer graph. All 24 bug citations re-confirmed present. This pass found **2 new Critical fix-design defects** (C.2 parser, E.1 breaking change) that survived passes #1–#3, plus 4 Important refinements. No scope challenge. CEO/strategic lens added nothing beyond the engineering findings — the triage, branch strategy, and complexity (24 tickets / 6 disjoint groups) remain justified; doing nothing leaves real round-2 review debt unmerged, and no simpler framing was available since each ticket is a discrete confirmed bug.

### Critical (fixed in-plan this pass)

1. **C.2 / M-12 — chosen redaction parser cannot produce the redacted string.** The plan mandated `io.r2dbc.spi.ConnectionFactoryOptions.parse(...)` and *rejected* regex string-redaction. But `parse(...)` returns a `ConnectionFactoryOptions.Builder` options-bag (the exact call already at `ConnectionPoolFactory.java:153`) with **no public API to render a redacted URI back into the String that `toString()` must emit** (`DefaultPostgresConfig.java:126` emits `"uri=" + uri`, a stored String). Two independent readers flagged this. **Resolved (user decision):** flipped to **targeted string redaction** on the raw `uri` — regex-rewrite userinfo `user:****@` + secret query-param values; `CFO.parse` may be used read-only to discover secret keys but never to rebuild the string. Ticket body updated.

2. **E.1 / M-4 — removing the HOCON default breaks startup of every service activating the profile.** Verified at HEAD: `read-journal.entity` is set **only** in `ditto-postgres-persistence.conf` (default `"things"`); no service conf sets it explicitly (`things.conf`/`policies.conf`/`connectivity.conf` set only `read-journal-batch-size`). The plan removed the default and made `resolveReadJournalEntity()` throw on missing, but only said "document the override" — so green-main startup/ITs for **all four** services (incl. things itself) would throw `DittoConfigError`. **Resolved (user decision):** E.1 expanded to also add explicit `read-journal.entity` to `things.conf="things"`, `policies.conf="policies"`, `connectivity.conf="connections"`, and the WoT path `="wot"`, with a per-service config-load assertion. Ticket file-set and summary-table row updated. **Build-gate consequence:** E.1 now touches three service modules — its merge gate must `mvn verify` those modules (or at least config-load them), not only `:ditto-internal-utils-persistence-r2dbc`. Group E is no longer fully file-disjoint from the service modules, but still conflict-free vs. other r2mlh groups.

### Important (fixed in-plan this pass)

3. **C.1 / M-5 — `SSLMode.fromValue` throws the wrong exception type.** `io.r2dbc.postgresql.client.SSLMode.fromValue(...)` throws `IllegalArgumentException`, not `DittoConfigError`. The constructor must membership-check (or catch+rethrow) and raise `DittoConfigError` itself, else the `DittoConfigError`-asserting failing test won't pass. Note added to ticket.

4. **C.3 / L-11 — `options` runtime-parameter encoding.** r2dbc-postgresql 1.0.7's `PostgresqlConnectionFactoryProvider.OPTIONS` expects a `Map<String,String>` of GUC name→value (`Map.of("statement_timeout","60s")`), NOT a libpq `-c statement_timeout=60s` string. The plan's `-c ...` form is likely silently ignored. Encoding correction + "verify against 1.0.7 before writing the unit test" note added; the `57014` IT remains the real proof.

5. **B.1 / H-12 — validation must run at row-encode time, and the `\.` marker is line-scoped.** Per-row "skip past" recovery only works if the bad row is excluded **before** the COPY body is assembled and the cursor advances past its `(pid,sn)`; validating after the `copied != rows.size()` guard (`flushBatch:193`) can't recover. The `EMBEDDED_END_OF_DATA` (`\.`) check is a property of an assembled COPY *line*, not a lone field — the test must assert against the joined body. Notes added to ticket.

### Confirmations (no change needed — recorded to prevent re-litigation)

6. **A.1 / M-1 — leak is real; "3-arg cleans up on cancel so it's safe" is a misread.** The 3-arg `usingWhen` cleanup (`Connection::close`, `:549`) *does* run on cancel, but it closes **without** rolling back; `onErrorResume(rollback)` (`:546-548`) fires only on error signal, not cancel. So a cancelled in-flight txn returns to the pool un-rolled-back. The plan's 5-arg fix with explicit `asyncCancel` rollback is correct. Implementer: the failing test must cancel mid-flight (plan already specifies this).

7. **A.2 / M-7 — fix is valid under `DISTINCT ON`.** `getNewestSnapshotsAbove` (`:473-475`) is `SELECT DISTINCT ON (pid) … ORDER BY pid, sn DESC LIMIT $4`. The proposed `ORDER BY pid, sn DESC, written_at DESC` keeps `pid` leftmost, so it satisfies Postgres' DISTINCT-ON-leftmost rule and yields the intended "highest sn, newest written_at on ties" per pid. No change.

8. **B.3 / M-8 — feasible on the existing raw-connection surface.** `copyIn` already acquires a raw `Connection` via `client.getConnectionPool().create()` and runs COPY on it (`R2dbcMigrationTarget.java:162-170`); `copyAndCheckpoint` can run the whole `BEGIN→…→COMMIT` multi-statement txn on that same raw connection (repeated `connection.createStatement(...)`) without a new `client` method. The ticket should state it reuses that raw-connection path, not the single-statement `client.executeUpdate` helpers. (Clarification only — design already sound.)

9. **D.1 / L-10 — gauge reset via the publisher.** The 4 gauges are written through a `gaugeSink.publish(name, tags, value)` abstraction, not held as Kamon refs; `close()` must emit `0` for each metric through that same sink. Implementation note only.

### Verdict

2 new Criticals and 4 Importants resolved in-plan (2 Criticals + parser/config decisions confirmed by the user). 4 prior-pass confirmations re-grounded against source. No scope change — the 24-ticket scope holds (E.1's file-set widened within its own ticket; ticket count unchanged). The plan is ready for `/forge-to-beads`.

---

## Review Pass — 2026-06-15 #5 (HOLD SCOPE)

Mode: HOLD SCOPE. Fifth pass after #4 declared "ready for beads." Method: re-verified **all 24 ticket citations** against HEAD `2fc7dd9ba1` via three parallel file-by-file readers (Group B migrator; Group A/C/D; Group E/F) — **all 24 confirmed present and accurate**, no citation drift. Then I steered the pass deliberately at the tickets the prior four passes touched *least* (the F-group test acceptance criteria, the D/C low-severity fixes, and the E.1 consumer graph) rather than re-litigating M-8/M-1/H-12/M-12 yet again. That surfaced **1 Critical fix-design defect that survived passes #1–#4** plus 2 Important test/ordering defects.

### Critical (must fix before proceeding to beads)

1. **E.1 / M-4 — the "wot → wot" per-service sub-item is unactionable and, if forced, breaking.** Pass #4 expanded M-4 to add an explicit `read-journal.entity` to four targets: `things.conf="things"`, `policies.conf="policies"`, `connectivity.conf="connections"`, and "the WoT activation path `="wot"`." **Verified at HEAD the WoT target does not exist and is conceptually wrong:**
   - There is **no standalone WoT service** — no WoT `main` class, no WoT `src/main/resources/*.conf`. WoT validation-config persistence runs **inside the Things service actor system**: `ThingsRootActor` starts both the `"thing"` shard region and the `"wot-validation-config"` shard region in the same JVM (`things/service/.../ThingsRootActor.java:140-151`).
   - `read-journal.entity` is a **single system-wide value**, resolved once per actor system (`PostgresPersistenceBackendProvider.resolveReadJournalEntity()`, path `read-journal.entity`). The Things JVM has exactly **one** `getReadJournal()` call (`ThingsRootActor.java:120`), used for the Things read side. There is **zero** `wot-validation-config` tag/search/background-sync consumer in the repo (grep-confirmed) — WoT does not use the shared read journal at all.
   - WoT's write-side persistence and recovery go through its **own** plugin IDs (`ditto-postgres-wot-journal` / `ditto-postgres-wot-snapshots`, resolved per-entity via `getJournalPluginId("wot-validation-config")`), which are **independent of `read-journal.entity`**. So WoT needs no read-journal override.
   - **Consequence of the current ticket text:** an implementing agent honoring "add `="wot"` to the WoT activation path" will either (a) find no file to edit and stall, or (b) wrongly set `read-journal.entity="wot"` in `things.conf` — which points the Things JVM's single read journal at the `wot_*` tables and **breaks Thing read-side queries** (background cleanup/sync) on green main. That is the exact breaking-change class pass #4 was trying to prevent, re-introduced by the fix itself.
   - **Resolution:** Drop the WoT sub-item. M-4's concrete per-service edits are **three files**: `things.conf="things"`, `policies.conf="policies"`, `connectivity.conf="connections"`. Update the E.1 ticket body, its file-set, and the §8 summary-table row (remove `(+wot)`), and drop the now-nonexistent "WoT module" from pass #4's widened build-gate note. If the intent was ever that WoT needs read-side queries, that is a **separate architecture problem** (one actor system cannot bind its single read journal to both `things` and `wot`) and must be raised explicitly as its own ticket, not folded into a config-add. The four supported `read-journal.entity` values still exist for the entity→table-set mapping; only the per-service *override list* drops to three.

### Important (fix in next review pass)

2. **F.1 / L-1 — `ageWindow = non-zero` filters out the freshly-seeded snapshots, so the test asserts against an empty set.** `getNewestSnapshotsAbove`'s SQL filter is `written_at < now() - $2::interval` (`PostgresPersistenceOperations.java:474`). With a non-zero `ageWindow`, rows inserted at ~`now()` are **excluded** (their `written_at` is not older than `now() - window`), so "assert only ACTIVE returned" would get **zero** rows, not the ACTIVE snapshot. The existing, working `PostgresParityMatrixIT:293-304` calls this with `Duration.ZERO`. **Fix the acceptance:** specify `ageWindow = Duration.ZERO` (match the proven IT) **or** explicitly backdate the seeded rows' `written_at` so they clear a non-zero window. As written ("ageWindow=non-zero") the test is unsound. Pin the choice before beads.

3. **D.1 / L-10 — the gauge-reset ordering re-introduces a race.** The ticket says "emit zero values in `close()` **before** cancelling the scheduler." On a live poller, a scheduled tick firing in the window between the zero-emit and `scheduledTask.cancel(true)` will re-publish the **stale last value**, defeating the reset. Reorder: `cancel(true)` (and let the in-flight tick drain) **then** emit `0` through the sink. The gauges are written via the `GaugePublisher` sink (`DittoMetrics.gauge(name).tags(tags).set(value)`), so `close()` publishes `0` through that same sink. Note: the unit test passes either ordering (it has no concurrent poll), so this defect is invisible to the acceptance test — call out the production race in the ticket and assert order explicitly.

### Confirmations (re-grounded this pass; no change)

- **B.7 / L-5 catch order is sound.** `readPayload` computes `validateTargetTable(targetTable)` **eagerly** while building the SQL-string argument to `executeSqlPublisher` (before any subscribe/`.block()`), so the `IllegalArgumentException` for an unknown table propagates **synchronously** and is caught by the planned `catch (IllegalArgumentException)`-before-`catch (RuntimeException)` clause in `verifySchema`. No reactive wrapping. The fix works as written.
- **All 24 source citations re-verified present at HEAD `2fc7dd9ba1`** (3 parallel readers, file-by-file): `inTransaction` 3-arg `usingWhen`+inline `onErrorResume` (`:541-550`); journal INSERT `now()` (`:131`); `_journal_seq` upsert binds only `(pid, highest_sn)`, no timestamp (`:139-146`); `getNewestSnapshotsAbove` `SELECT DISTINCT ON (pid) … ORDER BY pid, sn DESC`, no `written_at` tiebreaker (`:473-475`); `Math.min` no-op (`:184`); `readHighestSequenceNr` ignores `fromSequenceNr` (`:174-176`); `DefaultSslConfig` lowercases only (`:48-54`); `toString` raw `uri` (`:126`); `baseOptions` sets neither timeout, pooler warn gated on `TRANSACTION` (`:206-210`); `PoolMetricsPoller` `catch(RuntimeException)`, `close()` does not reset gauges (`:165, :179-185`); migrator `copied != rows.size()` → `CopyBatchException` (`:193`); `copyIn` 3-arg `usingWhen` bare COPY vs `saveProgress` via `executeUpdatePublisher` — two connections (`:162-169` vs `:134-151`); `migration_progress` has no `last_written_at` (`:84-87`); `Cursor(lastPid, lastSn)` only (`:110-117`); `JOURNAL_COLUMNS` excludes `written_at`, NUL falls through `default` verbatim in both `field`/`arrayLiteral` (`:43, :105-148`); `BLOCK_TIMEOUT = 10min` (`:50`); `assertParity` compares payload only (`:286-303`); `verifySchema` rewraps `RuntimeException`→`MissingSchemaException` (`:129-139`), `validateTargetTable` throws `IllegalArgumentException` (`:269-276`). Plan tags `(WU 8)` (`PostgresSnapshotAdapter.java:64`) and `[G1]` (`SnapshotAdapterParityTest.java:171`) confirmed for HY-1.

### Accepted scope changes

- No expansion. The scope shrinks by **one sub-item only**: M-4 loses its phantom WoT config target (3 service files, not 4). Ticket count unchanged at 24.

### Verdict

1 new Critical (M-4 WoT sub-item) and 2 Important (L-1 ageWindow, L-10 reset ordering) fix-design defects found and resolved in-plan. All 24 citations re-grounded. After applying the three fixes above to the E.1, F.1, and D.1 ticket bodies (and the §8 table row for E.1), the plan is ready for `/forge-to-beads`. **The M-4 Critical should be applied before beads** — it changes the E.1 ticket's file-set and acceptance.

