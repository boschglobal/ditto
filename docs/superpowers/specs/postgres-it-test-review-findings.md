# Postgres backend — IT test review FINDINGS (Phase 1)

- **Created:** 2026-06-02
- **Branch reviewed:** `pg-r2-integration-final` @ `6f8462c4bb` (worktree `…/pg-r2-wt/integration-final`) — the only place all 10 round-2 fixes coexist.
- **Method:** evidence-first, read-only. 7 parallel reviewers walked every IT method, tracing into production code; load-bearing claims spot-checked by hand. Each test classified `real-flow` / `ops-seam-only` / `stub` / `skip-risk`. Test names were *not* trusted (the H-6 parity-drift incident proves they lie).
- **Input:** `postgres-it-test-review-context.md` (the handoff; hypotheses W1–W7, target matrix §6, open questions §10).
- **Verdict in one line:** the suite is a competent **SQL/Ops-layer** test set, but as verification for the round-2 blocker fixes (C-1/C-2/C-3) it is **largely decorative** — not one IT drives a real domain event/state through the adapter binding and a real persistent actor against a real DB, and a Docker-less CI reports the whole matrix green. **W1–W7 CONFIRMED.**

---

## 1. Headline

The production flow the ITs must cover:

> domain event → persistent actor → `persist(event)` → PostgresJournal → event-adapter `toJournal(event)` = `Tagged(json, getJournalTags(event))` → JSONB row (tags/manifest/lifecycle/written_at) → **restart** → `replayMessages` → adapter `fromJournal(json)` → actor `.match(getEventClass())` → rebuilt state. Snapshots: `saveSnapshot(state)` → snapshot-adapter `toSnapshotStore` → JSONB → `loadAsync` → `fromSnapshotStore` → domain object.

Across **31 IT methods**, **zero** construct a genuine domain event/state, route it through the configured `event-adapter-bindings`, and recover it through a real persistent actor against a real DB. Every journal/snapshot write is a **hand-built `JsonObject`/`String` pushed through `journal.writeMessages(...)` / `*Ops`**. This is structural: the adapters live in `internal/utils/persistence/src/main/.../serializer/` (`AbstractPostgresEventAdapter`, `PostgresSnapshotAdapter`) and the service modules — `persistence-r2dbc/src/main` contains **no** `EventAdapter`/`toJournal`/`fromJournal` code, so the r2dbc ITs *cannot* reach the binding.

---

## 2. Altitude map (7 ITs / 31 methods + service modules)

| IT (methods) | Dominant tag | What it really proves |
|---|---|---|
| `PostgresParityMatrixIT` (14) | **ops-seam-only** + skip-risk | SQL invariants: GIN index, priority regex, HWM, TOAST round-trip, tx rollback, H-6 fatal-Future, idempotent upsert, bounded deletes; + 2 genuine schema-boot methods (checksum, DDL race). Never an adapter. |
| `PostgresPersistenceIT` (5) | **ops-seam-only** + skip-risk | HWM-survives-physical-delete, priority-tag tolerance, snapshot criteria/delete overloads, same-payload idempotency, replay-in-sequence — all hand-built payloads. |
| `PgBouncerTransactionPoolingIT` (1) | **ops-seam-only** + skip-risk | A generic write→recover→cleanup that would pass on *any* Postgres; **sidesteps the actual PgBouncer hazard** (F1). |
| `PostgresProviderBootstrapIT` (2) | **real-flow** (DDL only) + skip-risk | The *only* IT crossing the real `PersistenceBackendProvider.get` → `PostgresPersistenceBackendProvider.bootstrapSchema` → `PostgresSchemaManager` seam; verifies tables for **all 4 entities**. No actor/event/data path. |
| `PostgresSchemaManagerIT` (2) | real-flow (schema-mgr direct) + skip-risk | Real catalog inspection + checksum equality + divergent-PK refuse-to-boot — but bypasses the provider seam; divergence is PK-only / things-only. |
| `PostgresMigratorIT` (6 + 1 `@Ignore`) | real-flow (COPY happy path) + skip-risk | Real PG COPY / resume-skip / H-10 watermark / checksum-guard — but **Mongo source is a stub**; all fault paths untested. |
| Service modules (things/policies/connectivity/wot) | **unit-only / none** | No Postgres `*IT.java` anywhere; no real-actor-real-DB test for any entity. |

---

## 3. Per-method evidence

### 3.1 `PostgresParityMatrixIT` (the workhorse)

| method | tag(s) | what it ACTUALLY executes | evidence |
|---|---|---|---|
| `tagContainmentUsesGinIndexNotSeqScan` | ops-seam-only | 200× `journal.writeMessages(write(...,tags))` then `EXPLAIN` of raw `tags @> ARRAY[...]`; asserts GIN/bitmap plan text | IT:146-158 |
| `priorityOrderingIsStrictlyDescendingWithDistinctPriorities` | ops-seam-only | 5× hand-built writes → `getJournalPidsWithTagOrderedByPriorityTag`; asserts pid order | IT:172-184 |
| `historicalRevisionRecoveryRoundTripsRealEventViaJsonObject` | ops-seam-only (name lies — "RealEvent") | writes `event.toString()` JsonObject, reads `currentEventsByPersistenceId`, asserts payload equals input | IT:197-207 |
| `jsonbSnapshotDecodesToJsonObjectWithoutBsonValueCast` | ops-seam-only | `saveAsync(meta, snapshot.toString())` → `loadAsync`; payload-only compare | IT:215-223 |
| `duplicateWriteWithDifferentPayloadIsFatal` | ops-seam-only (real H-6 reconcile path) | two writes same `(pid,1)`, asserts 2nd Future **throws** `ExecutionException` "DIFFERENT payload" | IT:234-242 |
| `snapshotRetryWithSameMetadataIsIdempotentUpsert` | ops-seam-only | two `saveAsync` same meta (ON CONFLICT); asserts rowCount==1 | IT:248-258 |
| `readJournalDeleteEventsAndSnapshotsAreBoundedRanges` | ops-seam-only | delete ranges, assert survivors [1,5], snap rowCount=2 | IT:268-285 |
| `getNewestSnapshotsAbovePicksNewestPerPid` | ops-seam-only | two saves (sn 1,9); asserts seqNo=9 | IT:295-303 |
| `oversizeToastPayloadRoundTrips` | ops-seam-only | ~2 MB JsonObject round-trip; payload-only | IT:313-322 |
| `invalidJsonbInTransactionRollsBackEntirely` | ops-seam-only (raw SQL) | two INSERTs in one tx (2nd `'NOT_JSON'::jsonb`); `.onErrorReturn(-1L)` + rowCount==0 | IT:335-351 |
| `checksumMismatchRefusesToBoot` | **real-flow** (schema boot) | poisons `schema_version.checksum`, asserts `SchemaManager.bootstrap()` throws `SchemaBootException` | IT:361-373 |
| `concurrentFirstBootDdlRaceBothSucceed` | **real-flow** (schema boot) | fresh `race_db`, 2 threads bootstrap; both succeed, table exists | IT:382-419 |
| `poolAcquireTimeoutFailsTheWrite` | ops-seam-only | size-1 pool, hold connection, write fails with `R2dbcTimeoutException` (250ms window → flake risk) | IT:430-447 |
| `observabilityEmitsNeutralCommandMetric` | ops-seam-only | `SELECT 1` via proxy; asserts metric op-kind/status | IT:457-497 |

`write()` helper (`IT:507-513`) builds the payload as a bare `JsonFactory.newObject(json)` or `Tagged(JsonObject, tags)` with `manifest` hardcoded to `"manifest"`, then `AtomicWrite.apply(PersistentRepr.apply(...))`. No `*EventAdapter` referenced anywhere; tags are String literals, never `getJournalTags(event)`.

### 3.2 `PostgresPersistenceIT`

| method | tag | evidence |
|---|---|---|
| `highWaterMarkSurvivesPhysicalDelete` | ops-seam-only; asserts HWM long only | IT:120-130 |
| `priorityTagOrderingIsDescendingAndToleratesMalformedTag` | ops-seam-only (strongest: priority regex + order) | IT:132-152 |
| `loadAsyncHonorsCriteriaAndDeleteOverloadsDiffer` | ops-seam-only; snapshot via raw String, no snapshot adapter | IT:154-175 |
| `duplicateWriteWithSamePayloadIsIdempotent` | ops-seam-only; asserts `Optional.empty()` only | IT:177-186 |
| `replayReturnsEventsInSequenceOrder` | ops-seam-only; samples `sequenceNr`/row count only; payload is raw String | IT:188-203 |

### 3.3 `PgBouncerTransactionPoolingIT`

| method | tag | evidence |
|---|---|---|
| `writeRecoverCleanupThroughTransactionPooling` | ops-seam-only | IT:146-158; pins `PREPARED_STATEMENT_CACHE_QUERIES=0`, `fetch-size=0` (IT:141-142); `edoburu/pgbouncer:latest` (IT:88) |

**Proves vs. claims:** proves DDL + a basic write/recover/delete completes through PgBouncer `pool_mode=transaction` *with prepared-statement caching disabled (unnamed statements)*. Does **not** prove transaction-pooling safety of the production driver config — by pinning cache=0 and fetch-size=0 it disables the exact named-prepared-statement / server-side-portal streaming path that is the real r2dbc-under-PgBouncer hazard (flagged in `PostgresPersistenceOperations` comments). No assertion on `server_pid` stability, statement reuse, or `DEALLOCATE`/`prepared statement already exists`.

### 3.4 `PostgresProviderBootstrapIT`

| method | tag | evidence |
|---|---|---|
| `bootstrapThroughProviderSeamCreatesAllTables` | **real-flow** | IT:90-120 — real `PersistenceBackendProvider.get(...)` → `provider.bootstrapSchema()`; asserts all 4 entities' tables absent→present via `information_schema`; re-runs for idempotency |
| `bootstrapThroughProviderSeamFailsBootWhenDatabaseUnreachable` | real-flow (boot path) / **weak assert** | IT:128-146 — dead port + `ssl.mode=disable`; asserts only `isInstanceOf(RuntimeException.class)` (rescued by "no `things_journal` created") |

### 3.5 `PostgresSchemaManagerIT`

| method | tag | evidence |
|---|---|---|
| `bootstrapCreatesAllTablesAndIsIdempotent` | real-flow (schema-mgr direct; bypasses provider) | IT:58-74 — real catalog inspection + `schema_version.checksum == SchemaChecksum.current()` |
| `divergentPreExistingPkRefusesToBoot` | real-flow | IT:76-92 — creates old PK `(pid,sn,written_at)`, asserts `SchemaBootException` "divergent primary key"; things-only |

### 3.6 `PostgresMigratorIT`

| method | tag | evidence |
|---|---|---|
| `copiesJournalAndSnapshotRowsViaCopyProtocol` | real-flow (PG) / stub Mongo | IT:100-122 — COPY from `StubMigrationSource` (in-memory, NOT Mongo) |
| `resumesFromCheckpointAndIsIdempotentOnReRun` | real-flow / overstated | IT:124-144 — idempotency only via "finished collection skipped", not mid-collection PK dedup |
| `verifyModeAssertsFieldLevelParity` | real-flow / shallow | IT:146-160 — `--verify` re-reads only the `event` payload (L-4) |
| `migratesJournalSeqWatermarkSoRecoveryDoesNotRewind` (H-10) | real-flow | IT:162-181 — `GREATEST(MAX(sn),highest_sn)` → 100 |
| `refusesToRunOnSchemaChecksumMismatch` | real-flow | IT:183-201 — poison checksum, assert `SchemaMismatchException` |
| `resumeAndVerifyAgainstRealMongoContainer` | **skip-risk** (`@Ignore`) | IT:203-211 — empty body; module lacks `mongodb-driver-sync` + `testcontainers:mongodb` (not in BOM) |

`--verify` (`Migrator.assertParity` → `R2dbcMigrationTarget.readPayload`) compares **only** the `event`/`snapshot` payload JSON. Migrated-but-unverified columns: journal `manifest`, `tags`; snapshot `lifecycle`, `written_at`. Journal `written_at` defaults to `now()` at COPY time → **original event timestamps are silently lost**, and `--verify` can't detect it.

---

## 4. W1–W7 verdicts

- **W1 — bypass the adapter seam — CONFIRMED (strongest).** Parity `write()` `IT:507-513`; `BootTestActor` `JsonFactory.newObject(...)` persist + `.match(String.class)` recover (`PostgresPluginLifecycleIT:196,207`). Structural — no adapter in this module.
- **W2 — single-entity / single-flow — CONFIRMED.** `PostgresPluginLifecycleIT` is the only real-actor IT: one `things` actor, one method, graceful `PoisonPill` only. No hard crash, no concurrent same-`(pid,sn)`, no policies/connections/wot. L-15 also confirmed — snapshot taken after all events, none appended after, so the `.match(String.class)` replay branch never runs on the recovering actor (`:188-198`).
- **W3 — no service-module ITs — CONFIRMED.** All Postgres service tests are unit. **connectivity & wot-validation-config have zero Postgres tests of any kind** despite shipping production adapters. *Refinement:* the schema-bootstrap ITs DO cover all 4 entities' tables; the true gap is real actors/events, not table shape.
- **W4 — Docker-absent ⇒ silent green — CONFIRMED, worse than stated.** Every IT wraps startup in `Assume.assumeNoException` over a **`catch (Throwable)`** (7 classes / 31 methods), so any container-boot bug — not just daemon absence — becomes a skip; failsafe `verify` passes green. No `require-docker` profile/sentinel anywhere (grep clean across module + root poms). `api.version=1.43` mitigation present (`PostgresDbResource:56-64`) but copy-pasted into the PgBouncer IT and not enforced JVM-wide.
- **W5 — wrong-altitude / weak assertions — CONFIRMED.** Round-trips assert payload JSON only (never tags/manifest/`__lifecycle`/`written_at`); `invalidJsonbInTransactionRollsBackEntirely` `.onErrorReturn(-1L)` swallows the real failure mode; `FailsBootWhenDatabaseUnreachable` asserts only `RuntimeException`.
- **W6 — migration fault gaps — CONFIRMED.** No crash-between-COPY-and-checkpoint, no NUL-byte doc, no sibling-worker failure; the safety-critical `CopyBatchException` all-or-nothing path is triggered by no test; missing-schema fail-fast untested; `--verify` payload-only (L-4).
- **W7 — backpressure/scale not IT-covered — CONFIRMED.** Only `RecordingConnectionFactory` unit coverage; no large-result-set stream against real PG (H-2/M-2/H-8 unproven at scale).

---

## 5. Correction to the handoff hypotheses

**M-11 is only PARTIALLY true.** `ThingPostgresEventAdapterTest` is actually the *strongest* test in the repo: `recoversARealThingEventThroughTheStringBindingSeam` (`:193-212`) drives the real Pekko `toJournal` → String payload → `EventAdapters.apply(...).get(String.class).fromJournal(...)` and asserts a real `ThingEvent`; `resolvesTheStringReplayPayloadToTheJsonbAdapterNotIdentity` (`:174-184`) proves the String→JSONB-adapter binding resolution. **But these are *unit* tests** (in-memory `ActorSystem`, no DB, no actor). The genuine M-11 offender is **`ThingAdapterSerializerParityTest`**, which calls `toJournalJson`/`fromJournalJson` directly on the **Mongo** adapter — its name oversells; it exercises no Postgres wiring. Also: `toJournalJson`/`fromJournalJson` are *not* methods Pekko never calls — `toJournal`/`fromJournal` delegate to them internally. So: the binding seam *is* unit-tested for `things`; what's missing is **end-to-end (real journal + actor + DB)** and **any** coverage for the other three entities.

---

## 6. Service-module Postgres coverage matrix

| entity | unit event-adapter test | unit snapshot test | wiring test | real-actor IT (real DB) | any Postgres test |
|---|---|---|---|---|---|
| **things** | ✅ `ThingPostgresEventAdapterTest` (drives binding) | ✅ `ThingPostgresSnapshotAdapterTest` | ❌ | ❌ | ✅ |
| **policies** | ❌ (none) | ❌ (none) | ✅ `PolicyPersistenceActorPluginIdWiringTest` (stub provider) | ❌ | ⚠️ wiring only |
| **connections** | ❌ | ❌ | ❌ | ❌ | ❌ **none** (adapters ship) |
| **wot-validation-config** | ❌ (only Mongo test) | ❌ (no PG snapshot adapter exists) | ❌ | ❌ | ❌ **none** (event adapter ships) |

---

## 7. New issues beyond W1–W7

- **F1 — PgBouncer IT proves almost nothing about PgBouncer.** Pins `PREPARED_STATEMENT_CACHE_QUERIES=0` + `fetch-size=0`, disabling the named-prepared-statement / server-side-portal path that is the actual hazard; `edoburu/pgbouncer:latest` unpinned. The streaming read-journal path (`streamingQuerySource`, positive fetchSize) — the single most PgBouncer-relevant code — is untested.
- **F2 — coverage asymmetry.** `policies` has no Postgres adapter unit tests at all (only stub wiring); `wot-validation-config` has an event adapter but **no snapshot adapter** and no tests.
- **F3 — shared mutable DB, no per-test truncation** in `PostgresPersistenceIT` & `PostgresParityMatrixIT`; isolation rests on a fragile "unique pid per method" convention; the GIN test runs `ANALYZE` on the shared table (planner-stat pollution). Only `PostgresMigratorIT` resets in `@Before`.
- **F4 — refuse-to-boot coverage narrow.** Divergent-PK only / things-only; collation, column-type, and checksum-mismatch-through-the-provider guards have no IT.
- **Misc smells:** over-broad `catch (Throwable)` masks real bugs as skips; `checksumMismatchRefusesToBoot` best-effort restore can poison the shared DB; `poolAcquireTimeoutFailsTheWrite` 250 ms timing window; `concurrentFirstBootDdlRaceBothSucceed` leaks `race_db`/connection factories on JVM crash; migrator idempotency overstated (only "finished collection skipped", never mid-collection PK dedup).

---

## 8. Round-2 finding ↔ evidence cross-reference

| Round-2 finding | Status | Evidence |
|---|---|---|
| M-11 (parity bypasses binding) | PARTIAL | §5 — `ThingAdapterSerializerParityTest` (Mongo) confirms; dedicated ThingPostgres tests refute |
| H-13 (no e2e concurrent/crash/multi-entity) | CONFIRMED | W2; `PostgresPluginLifecycleIT` single method |
| L-1 (DELETED-lifecycle never tested with real DELETED row) | CONFIRMED | snapshot payloads carry no `__lifecycle` |
| L-2 (read-journal "does not throw") | CONFIRMED | W5 |
| L-3 (silent skip when Docker absent) | CONFIRMED+ | W4; `catch(Throwable)` |
| L-4 (`--verify` samples payload only) | CONFIRMED | §3.6 |
| L-15 (no Pekko-driven post-snapshot replay) | CONFIRMED | `PostgresPluginLifecycleIT:188-198` |
| §6 Residual Risk (load/OOM, split-brain, crash-during-COPY) | CONFIRMED unverified | W6/W7 |

**Note:** these test-quality findings were never ticketed (only the 10 `ditto-postgres-*` defect tickets exist). Phase 2 must decide ticketing.

---

## 9. Assessment & next step

The suite genuinely validates schema bootstrap, the GIN/priority SQL, the realigned H-6 fatal-Future contract, and COPY happy paths against real Postgres. But **C-1 (event adapter wiring), C-2 (per-service snapshot adapters), and C-3 (provider plugin-id routing) — the three blocker fixes — have no real-actor, real-DB integration coverage**, and a Docker-less CI reports all of it green. W1–W7 are confirmed; the handoff's premise holds. **Do not treat "30 IT green" as evidence the round-2 fixes work end-to-end.**

➡ **Phase 2:** implementation plan for a real IT suite — target matrix in handoff §6 (entity × {boot-via-provider, real event round-trip, recover-across-restart, snapshot round-trip, hard-crash, concurrent same-`(pid,sn)`, ping/wake, fresh-DB bootstrap, backpressure, migration faults}), resolving the 7 open questions in handoff §10 (where service-actor ITs live, real-event construction, docker gating, scale boundary, live-Mongo IT, ticketing, branch strategy).

---
*End of Phase-1 findings. Companion: `postgres-it-test-review-context.md` (handoff), `postgres-persistence-backend-critical-review-round2.md` (contracts).*
