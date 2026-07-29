# PostgreSQL persistence backend for Eclipse Ditto

Status: phase 1 complete (commit `bda04b5009`). **Phase 2 IMPLEMENTED + verified against real Postgres on 2026-06-01** via `/forge-implement` — all 18 beads tickets closed across 9 commits (`409d7e784e`..`e8fc36324f`, local on `postgres-190626-feat-dev`, not yet pushed). `mvn verify` is green with the Maven enforcer enabled: 100 unit tests + 27 Testcontainers integration tests against real PostgreSQL 16 and real PgBouncer, 0 failures (1 documented skip — live-Mongo migrator IT). Every keystone correctness gate passed on real PG (`[C1]` HWM-survives-delete, `[H1]` GIN `@>` index hard-gate + descending priority, `[H4]` idempotency, `[H9]` criteria/two-form delete, `[G1]` JSONB recovery, `[H13]` refuse-to-boot, N=2 concurrent-DDL race, `[H12]` lost-write). No defects found in the implementation. **Known follow-ups (NOT done):** (1) only `ThingPersistenceActor` is wired to the provider — Policy/Connection/WotValidationConfig still return hardcoded Mongo plugin-ids, so Postgres activates Thing-first; (2) push branch + open PR; (3) live-Mongo migrator IT needs `mongodb-driver-sync` + `testcontainers:mongodb` in the BOM; (4) `[M8]` COPY-throughput + `[H12]` pool-sizing load spikes in a load environment. **Implementation note:** the bom Maven-enforcer execution must keep `<inherited>false</inherited>` on its `requireUpperBoundDeps`/pin guards (bom is the parent pom of nearly every module; an inherited `requireUpperBoundDeps` breaks the whole build on a pre-existing byte-buddy/assertj convergence gap) — split into an inherited `enforce-no-pekko-r2dbc-plugin` ban + a bom-only `enforce-bom-version-pins`.

> **Single source of truth.** This file folds in the former companion docs
> `postgres-tags-approach.md` (now §10) and `postgres-cleanup-approach.md` (now §11),
> which have been deleted. All schema, SQL, tag and cleanup details live here.

> **Review provenance.** Corrections in this revision come from two multi-agent critical
> reviews (2026-05-29, §9) that verified every decision against Pekko 1.6.0 source,
> PostgreSQL/PgBouncer/r2dbc-postgresql docs, and the repo at `bda04b5009`. Findings are
> referenced inline as `[C1]`, `[H1]`, `[M1]`, etc. (see §12 for the index). The **second
> adversarial pass** (55 agents, every claim independently re-derived + a devil's-advocate
> challenge round) confirmed the keystone decisions (C1/H1/H2/H4/init-order) but found
> further defects — now fixed inline and indexed as `[C2]`, `[H9]`–`[H15]`, `[G1]`–`[G5]`.

## 1. Goal

Add a configurable PostgreSQL backend alongside MongoDB so a Ditto deployment can choose its persistence at boot. JSONB for journal event payloads and snapshots, separate `TEXT[]` column for tags. Reactive end-to-end (mirror today's Mongo reactive-streams driver).

## 2. Locked-in architectural decisions

| Decision | Rationale |
|---|---|
| **R2DBC + r2dbc-pool driver libraries** | True reactive streams to match the existing Mongo reactive-streams pipeline (verified: Mongo path is reactive end-to-end via `mongodb-driver-reactivestreams` + pervasive `Source<>`). r2dbc-postgresql is 1.x GA / production-mature. JDBC + thread pool rejected, but the justification is *reactive parity*, not the unquantified "burns threads" slogan — a well-sized HikariCP pool is a legitimate simpler fallback if reactive parity is ever dropped `[M9, LOW]`. **Note:** r2dbc-postgresql is Reactor/Netty-based, so a reactive-streams → Pekko Streams bridge is needed that the Mongo path does not require — budget for it (WU 4/7). |
| **Custom Ditto Postgres Pekko persistence plugin on top of the r2dbc driver libs** — NOT the upstream `pekko-persistence-r2dbc` Pekko plugin | **Verified:** upstream `R2dbcReadJournal` genuinely does not implement `EventsByTagQuery` / `CurrentEventsByTagQuery` (slice queries only), and its **snapshot** plugin hardcodes `Array[Byte]` → `snapshot BYTEA` with a single `PRIMARY KEY (persistence_id)` and **no payload-type config knob** (verified `SnapshotDao.scala` 1.1.0; `grep` of upstream `reference.conf` finds none) `[H10]`. The real gap is the **tag query path + multi-snapshot retention**. JSONB is **not** a discriminator either way: verified against 1.1.0 sources, the upstream **journal** payload (`event_payload`) is unconditionally `Array[Byte]`/**BYTEA** — `reference.conf` has **no** payload-type/JSONB knob (grep: zero matches) — so both journal **and** snapshot are BYTEA-only, and Ditto's JSONB (written from its own `JsonObject`/BSON adapters, not Pekko Jackson-JSON) requires a **custom serializer + custom DAO regardless** `[M9, H10]`. `pekko-persistence-jdbc` rejected primarily because Slick is thread-pool-backed blocking JDBC and it lacks Ditto's tag/multi-snapshot query semantics. **Scope — SETTLED `[H6, G2]`:** build **all three** write-side classes custom (the spike is done, see §6). Upstream's write side has three fatal blockers: BYTEA payload via Pekko serialization, `deleteMessagesTo` uses an **insert-delete-marker not a physical delete** (structurally incompatible with Ditto's physical-delete cleanup + `[C1]`), and `HighestSequenceNrDao` is a bare `MAX(seq_nr)` (reusing it reintroduces `[C1]`); the snapshot store is single-row `PK(persistence_id)`; the read journal implements no `EventsByTagQuery` SPI. |
| **JSONB payloads + separate `TEXT[]` tags column with GIN index** | Preserves Ditto's flexible MongoDB-style event shape and gives a compact tag-containment index. **Containment must use `tags @> ARRAY[$1]::text[]`, NOT `= ANY(tags)`** — GIN `array_ops` supports only `<@ @> = &&`; `scalar = ANY(array)` silently sequential-scans `[H1]`. Schema and SQL: §10 (tags) and §11 (cleanup). |
| **Multi-snapshot retention on snapshot tables** | Required by `getNewestSnapshotsAbove` + Ditto's cleanup actors. Verified: Ditto actors never self-prune on save (`AbstractPersistenceActor.java:951-967`), so >1 row per pid is real. **Snapshot PK is `(pid, sn, written_at)`** — mapped from `SnapshotMetadata.timestamp`, matching Pekko's `(persistenceId, sequenceNr, timestamp)` uniqueness key — **not** `(pid, sn)`, and `saveAsync` is an **idempotent upsert** (`ON CONFLICT DO UPDATE`), mirroring the Mongo plugin's `replaceOne(..., upsert(true))`. A bare INSERT + `(pid,sn)` PK + dup→fatal would crash an actor on a benign SnapshotStore retry `[H4]`. Details in §11. |
| **Per-pid journal high-water-mark metadata** `[C1, CRITICAL]` | Ditto cleanup does **physical** `DELETE`s, but Pekko requires the journal's highest sequence number to *never decrease* after deletion. `SELECT MAX(sn)` over a physically-pruned table regresses the high-water mark → recovering actors reuse sequence numbers → dup-key violations + silent event loss. A per-pid metadata row (`highest_sn` / `deleted_to`) maintained transactionally inside `asyncWriteMessages` / `asyncDeleteMessagesTo` is mandatory. Schema in §11. |
| **Per-actor-type plugin IDs + per-entity tables** | Mirror Mongo: `things_journal`, `policies_journal`, `connections_journal`, `wot_journal` (and `*_snaps`) so cleanup retention and dispatcher tuning can vary by entity type. |
| **Provider extension via `DittoExtensionPoint`** for backend selection | Verified landed and correct (`PersistenceBackendProvider extends DittoExtensionPoint`, standard `ExtensionId` semantics). Future backends slot in as new providers. |
| **Strict either/or per deployment** | No mixed-cluster mode. One provider per `ActorSystem` — structurally enforced by Pekko extension semantics (verified). |
| **Programmatic DDL via R2DBC at startup**, `IF NOT EXISTS` on every statement, bootstrap wrapped in **`pg_advisory_xact_lock(<constant>)` inside a single transaction** `[H2]` | Avoids a second driver dependency (no Flyway). **`pg_advisory_xact_lock`, not `pg_advisory_lock`**: session-scoped advisory locks are unsafe over an r2dbc-pool (lock/DDL/unlock can land on different pooled connections → race protection no-ops, lock leaks). Plain `CREATE` statements are transactional, so lock + all DDL + `schema_version` upsert compose in one txn, auto-released on commit/rollback. The `SELECT indisvalid` + rebuild step is **removed** — invalid indexes only arise from `CREATE INDEX CONCURRENTLY`, which is not used and cannot run in a transaction; plain `CREATE INDEX` rolls back cleanly `[M2]`. **Refuse-to-boot guard is two-layer `[M1, H13]`:** (1) a `schema_version` **checksum** (not a bare INT) catches code-vs-stored downgrade/drift; (2) a **live-catalog verification** step inside the same txn — `pg_get_constraintdef` on each PK + `information_schema.columns` per table — because `CREATE TABLE IF NOT EXISTS` **silently keeps a pre-existing table's divergent PK/columns** (e.g. an old `(pid,sn)` survives the new `(pid,sn,written_at)` DDL, and the `ON CONFLICT (pid,sn,written_at)` upsert then fails at *runtime*, not boot). The checksum alone is a no-op on first boot against a divergent pre-existing table; the catalog check refuses-to-boot with a precise diff. |
| **PgBouncer: pooling-agnostic, not session-pooling-required** `[H3, M3]` | The earlier "must be session pooling, detect via `SHOW pool_mode`, else refuse to boot" is wrong: `SHOW pool_mode` does not exist (`pool_mode` is a *column* of `SHOW POOLS`, reachable only on the `pgbouncer` admin DB), and steady-state only needs **`preparedStatementCacheQueries=0`** on r2dbc-postgresql (or PgBouncer ≥ 1.21, which supports prepared statements in transaction mode). With the xact-lock bootstrap fix `[H2]`, no step needs session pooling. Provide an explicit `ditto.postgresql.pooler-mode = direct\|session\|transaction` config flag for a **soft WARN**, not a boot block. |
| **Offline CLI migrator (hand-rolled), no online dual-write** | Operator takes downtime, runs the tool, swaps backend. Resumable checkpoint table + verification mode. Mongo source read-only during migration; Ditto `Cleanup` must be stopped. **Acknowledge** Ditto's existing in-house online-migration precedent (connectivity) and add a catch-up delta pass to bound downtime (§6). Bulk load uses `COPY`, not the runtime per-AtomicWrite path `[M8]`. |
| **Cutover rollback = explicit RPO + time-box, not "restore from backup"** `[H7]` | "Restore Mongo from backup" is a one-way door: post-cutover it discards every write Postgres accepted. Split by phase — pre-cutover failure → abort, stay on Mongo (clean); post-cutover → either a reverse Postgres→Mongo export or a documented RPO ("all post-cutover writes lost on rollback") plus a rollback time-box. State the RPO in the operator contract. |
| **R2DBC → Kamon observability: NEW neutral metric family, NOT "parity with Mongo"** `[M5, M6, H14]` | Command **timer** via `r2dbc-proxy` (`afterQuery` / `getExecuteDuration`), recorded on **success and error**. r2dbc-proxy has **no pool-event hooks**, so pool gauges come from `ConnectionPool.getMetrics()` (`PoolMetrics`, polled ~1s) — and `PoolMetrics` exposes only instantaneous counts, **never acquire wait-time**; add acquire-latency via r2dbc-proxy's `before/afterCreateOnConnectionFactory` to a histogram `[G4]`. **The "shared base name + `engine=` tag, parity with Mongo" premise was fiction `[H14]`:** Ditto's real Mongo metrics (`KamonCommandListener:38`) use a `_mongodb` name **suffix** with `command_name`/`cluster_id` tags, **no** `engine=` tag, and exist **only in thingsearch**; `persistence-api` has zero metric constants — there is nothing engine-neutral to be parity-with. **Decision:** emit a fresh neutral family `ditto_persistence_command_duration{engine=postgres, op_kind=…, status=…}`, leave Mongo metrics untouched, **no shared constants**. (True cross-engine parity would require retrofitting `KamonCommandListener` — a breaking dashboard/alert change; out of scope.) |
| **Plugin-ID accessors resolve without constructor-injected fields; Pekko version pinned** | Phase-1 ships the `context().system()` lookup (verified correct against `Eventsourced.scala:98-104` + `ActorCell.scala:622-623`; same pattern already in production for `SnapshotAdapter` resolution). It relies on `@InternalApi` init order, hence the exact `pekko-bom.version` pin + init-order regression test. **`Props`-injection is NOT a valid hardening `[H8]`, corrected `[H8/refuted]`:** the earlier "inject plugin IDs via `Props`, args are closure-captured" is **wrong** — `Props.create(clazz, args...)` uses `ArgsReflectConstructor` (a reflective constructor call), so the injected value lands in a subclass field that is **still null** when `Eventsourced`'s eager `maxMessageBatchSize` val calls `journalPluginId()` during super-init — the **exact same null-field trap**. Only the discouraged *function/closure-creator* `Props` form captures, and that form is unsafe for sharded/remote actors (serialization). **Therefore the field-free `context().system()` lookup stays the primary mechanism** (no "preferred" replacement). If further hardening is wanted, read a *system-scoped immutable holder*, never a constructor field. The regression test MUST probe the value *during construction*, not merely "before first message". |
| **Postgres 16 minimum** (was "14+"), CI'd in a matrix 15/16/17 `[LOW, corrected H15]` | Floor justified by the **support/security lifecycle**, not features (every cited feature — `DISTINCT ON`, JSONB, GIN, partial indexes — predates 13 by 8–25 years; the "modern partial-index planner" claim was filler). The earlier "14+" floor is self-contradictory on a lifecycle basis: PG 13 EOL'd Nov 2025 and **PG 14 EOL is Nov 12 2026** — already within ~5.5 months of this plan's date. PG 16 (or ≥15) keeps a real support window at zero code cost. Do not invoke MERGE — not needed. |

## 3. Phase 1 — done

Commit: `bda04b5009 introduce backend-agnostic persistence API as seam for postgres backend`

### What landed (verified against the commit)

- New module `internal/utils/persistence-api/` containing:
  - `DittoReadJournal` — interface extracted from `MongoReadJournal` (~25 public methods, incl. Ditto-specific `getJournalPidsWithTagOrderedByPriorityTag`, `getNewestSnapshotsAbove`, `deleteEvents`, etc.)
  - `PersistenceBackendProvider` — Pekko extension SPI with `getJournalPluginId(entityType)`, `getSnapshotPluginId(entityType)`, `getReadJournal()`
  - `SnapshotFilter` — pure data record moved from the Mongo streaming package
- `MongoPersistenceBackendProvider` in the existing `persistence` module
  - Plugin IDs follow `pekko-contrib-mongodb-persistence-<entityType>s-*` convention with per-entity HOCON overrides
  - `getReadJournal()` is `synchronized` lazy — see "Gotchas" below
- `reference.conf` in the persistence module declaring the Mongo provider as the default
- `MongoReadJournal` implements `DittoReadJournal` (no behavior change)
- `AbstractPersistenceActor`, `AbstractPersistenceSupervisor` constructors widened from `MongoReadJournal` to `DittoReadJournal`
- All concrete persistent actors widened too (Thing, Policy, Connection, WotValidationConfig, Cleanup, Ping) — **no `(MongoReadJournal)` casts left anywhere** (verified: zero casts remain)
- `ThingPersistenceActor.journalPluginId()` / `snapshotPluginId()` resolve through the provider via `context().system()` (not constructor-injected; see "Gotchas")
- `ThingsRootActor`, `PoliciesRootActor`, `ConnectivityRootActor` all obtain the read journal from `PersistenceBackendProvider` instead of `MongoReadJournal.newInstance(actorSystem)`

### Gotchas worth remembering

- **Pekko's `Eventsourced` trait initializer calls `journalPluginId()` during the super constructor**, before subclass fields are assigned (verified: `Eventsourced.scala:98-104` eager `maxMessageBatchSize` val). A `final String journalPluginId` field initialized in the subclass constructor reads as `null` when Pekko queries it. **Therefore the plugin-ID accessors must NOT use instance fields injected through the constructor.** They look up the provider via `context().system()` — `ActorCell` pushes the actor context onto the contextStack before invoking the constructor (`ActorCell.scala:622-623`), so `context()` is available even mid-super-call. The lookup happens once per actor (Pekko caches via lazy val). This is `@InternalApi`-dependent — but note `Props`-injection does **NOT** fix it (`Props.create(clazz, args...)` uses a reflective constructor call, so the field is still null at super-init time — same trap) `[H8]`. Keep the field-free lookup; pin Pekko + keep the init-order regression test.
- **The provider's `getReadJournal()` is lazy** because `MongoReadJournal.newInstance(actorSystem)` reads a Mongo-shaped HOCON block (`overrides.journal-collection` etc.) that's absent in tests using the in-memory persistence plugin. Plugin-ID lookups are pure config reads, so they're safe to fire during actor super-construction. The read journal only materializes when `Cleanup` / `PersistencePingActor` / historical queries actually use it.
- **`DittoReadJournal` still returns `org.bson.Document` and `com.mongodb.client.result.DeleteResult`** in several methods. Intentional phase-1 tech debt — replaced with neutral DTOs in phase 2 (WU 1.5).
- **`MongoReadJournal.J_*` / `S_*` / `PRIORITY_TAG_PREFIX` / `LIFECYCLE` static constants** are still referenced from `Cleanup`, `ConnectionPersistenceActor`, `ConnectionIdsRetrievalActor`. Convention-based ones (`PRIORITY_TAG_PREFIX`, `LIFECYCLE`, `JOURNAL_TAG_ALWAYS_ALIVE`) get hoisted to `DittoReadJournal` in WU 1.6.
- **`Cleanup.java` is NOT backend-agnostic yet** `[M4]`, corrected — it statically imports `MongoReadJournal` **constants** (`LIFECYCLE`, `S_ID`, `S_SN` — verified `Cleanup.java:15-17`). It does **NOT** import `org.bson.Document` (verified: zero `org.bson` references, zero `Document` tokens in the file — the earlier claim was wrong). Its coupling is the constant hoist, decoupled in **WU 1.6** (not the DTO purge). (The other four cleanup classes — `PersistenceCleanupActor`, `Credits`, `ClusterResponsibilitySupplier`, `AbstractPersistentActorWithTimersAndCleanup` — are genuinely backend-agnostic and verified unchanged.)
- **CRITICAL runtime trap — `(BsonDocument)` cast in backend-"agnostic" actors** `[C2]` — `AbstractPersistenceActor.java:1083` and `AbstractPersistenceSupervisor.java:319` both do `final BsonDocument event = (BsonDocument) eventEnvelope.event();` then call `DittoBsonJson`. `EventEnvelope.event()` is typed `Object`, so this **compiles but throws `ClassCastException`** the moment a Postgres ReadJournal returns a JSONB-derived payload. Live paths: historical-revision recovery (`AbstractPersistenceActor:483`) and the persisted-event subscription (`AbstractPersistenceSupervisor:284`). **WU 1.5 does NOT fix this** — it only purges Mongo types from interface *signatures*, not this payload cast. New **WU 1.7** (must precede WU 9) decouples both call sites. See §4.
- **Plugin ID naming heuristic in `MongoPersistenceBackendProvider.pluralize`** adds "s" to entity type names (`thing` → `things`). Returns wrong default for `connection` (actual plugin is singular). Latent today because only `ThingPersistenceActor` goes through the provider for plugin IDs; lights up under WU 1.6 (connection migrates onto provider path). Deleted in WU 10.

## 4. Phase 2 — Postgres implementation (roadmap)

Schema and query specifics now live in §10 (tags) and §11 (cleanup) of this document.

Commit-sized work units (DTO purge and constants hoist front-loaded so subsequent WUs work against neutral types):

1. **WU 1.5 — Neutral DTOs in `DittoReadJournal`.** Define `JournalEntry`, `SnapshotEntry`, `DeleteOutcome` in `internal/utils/persistence-api/`. Replace `org.bson.Document` and `com.mongodb.client.result.DeleteResult` in the interface and update the Mongo impl + every consumer (`ConnectionIdsRetrievalActor`, `MigrationProgressTracker`, `MongoReadJournalIT`, `SnapshotStreamingActor`). Acceptance: no Mongo type leaks past the interface. (Note: `Cleanup.java` does **not** import `org.bson.Document` — its only Mongo coupling is to `MongoReadJournal` constants, handled in WU 1.6 `[M4]`.)

2. **WU 1.6 — Hoist Ditto-convention constants.** Move `PRIORITY_TAG_PREFIX`, `LIFECYCLE`, `JOURNAL_TAG_ALWAYS_ALIVE`, `S_ID`, `S_SN` from `MongoReadJournal` / `AbstractPersistenceActor` to `DittoReadJournal`. Update `ConnectionPersistenceActor:467,496`, `Cleanup` (`:15-17` static imports), `ConnectionIdsRetrievalActor`. Acceptance: no Mongo-class import in any non-Mongo consumer.

   **WU 1.7 — Decouple the `(BsonDocument)` payload cast `[C2]` (BLOCKS WU 9).** `AbstractPersistenceActor.java:1083` and `AbstractPersistenceSupervisor.java:319` cast `eventEnvelope.event()` to `BsonDocument` and call `DittoBsonJson` — a `ClassCastException` under any non-Mongo backend. Introduce a per-backend neutral mapper — e.g. `JsonObject DittoReadJournal.toEventJson(EventEnvelope)` (Mongo: BSON→`JsonObject`; Postgres: JSONB/text→`JsonObject`) — and refactor **both** call sites off the cast; remove the `org.bson.BsonDocument` + `DittoBsonJson` imports from both classes. Acceptance: `grep -rn "org.bson" internal/utils/persistent-actors/src/main` returns zero, AND a Postgres-backed historical-revision-recovery IT (`AbstractPersistenceActor:483` path) round-trips a real event.

3. **New module `internal/utils/persistence-r2dbc/`.** POM mirrors `internal/utils/persistence-api`; depends on `r2dbc-postgresql`, `r2dbc-pool`, `r2dbc-spi`. **Pin exact versions as new properties in `bom/pom.xml`** — none exist today `[G3]`; add `r2dbc-postgresql.version` (`.m2` resolves `1.0.7.RELEASE`), `r2dbc-pool.version`, `r2dbc-spi.version` (`1.0.0`), and **`r2dbc-proxy.version`** (observability, WU 11) — no ranges, mirroring how `mongo-java-driver.version` is managed. Confirm `r2dbc-proxy` converges with `r2dbc-spi 1.0.0`. **Add a Maven enforcer ban on `org.apache.pekko:pekko-persistence-r2dbc*`** `[M10]` — only the pure SPI libs (`io.r2dbc:r2dbc-spi`, `io.r2dbc:r2dbc-pool`, `org.postgresql:r2dbc-postgresql`) may hit the classpath; they carry no Pekko coupling. Confirm Pekko 1.6.0 resolves cleanly alongside them.

4. **`DittoPostgresClient`.** R2DBC `ConnectionPool` wrapper analogous to `DittoMongoClient`. The real work is **per-statement connection lifecycle** — acquire from pool → `Statement.bind` → `Result.map` → release-on-completion **and** on failure/cancel — bridged to Pekko via `Source.fromPublisher` (both Mongo and r2dbc expose `org.reactivestreams.Publisher`, so this is the *same* engine-agnostic bridge Mongo already uses at ~40 call sites; there is no special "Reactor→Pekko" bridge, that framing was overstated `[G3]`). Config via `ditto.postgresql.{uri,username,password,pool.*,ssl.*,pooler-mode}`. Defaults `[H12, G3]`: **`pool.max-size = 100`** (≥ the Mongo code default of 100; per-service Helm override — raise things/policies toward 200 to match their deployed Mongo `maxPoolSize`), **`pool.max-acquire-time = 30s`** (matches Mongo; 5s risks acquire-timeout → failed writes under load), **`fetch-size = 0`** (no server-side portal/cursor, keeps the "no session pooling needed" claim true under transaction pooling), SSL `sslmode = verify-full` (opt-out only; see §6 cert-provisioning gap `[G5]`), `preparedStatementCacheQueries = 0` default-safe for poolers `[M3]`, separate DDL-runner role surfaced as `pool.ddl-credentials` block. **Pool sizing vs the thousands of multiplexing persistent actors is a load-test gate, not a guessed default** `[H12]`. Concrete HOCON in §7.

5. **`PostgresSchemaManager`.** Programmatic DDL with `IF NOT EXISTS` on every statement. Bootstrap = a **single transaction**: `pg_advisory_xact_lock(<constant>)` → all `CREATE TABLE`/`CREATE INDEX` → **live-catalog verification** (`pg_get_constraintdef` on each PK + `information_schema.columns`) → `schema_version` checksum upsert → commit (auto-releases the lock) `[H2, M1, H13]`. **No** `pg_advisory_lock` (session) and **no** `SELECT indisvalid` rebuild `[M2]`. Refuse to boot on **either** a `schema_version` checksum mismatch (code-vs-stored downgrade/drift) **or** a live-catalog mismatch — the latter is mandatory because `CREATE TABLE IF NOT EXISTS` silently keeps a pre-existing table's divergent PK/columns, which the checksum alone cannot catch on first boot `[H13]`. (Note: per-table `ALTER TABLE … SET (autovacuum_*)` from `[H5]` is also transactional and belongs in this same bootstrap txn.) DDL statements per §10–§11:
   - `things_journal(pid TEXT, sn BIGINT, seq BIGINT GENERATED ALWAYS AS IDENTITY, manifest TEXT, tags TEXT[] NOT NULL DEFAULT '{}', event JSONB NOT NULL, written_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (pid, sn))` + `CREATE INDEX … USING GIN (tags)`. The `seq` IDENTITY column is the **monotonic global offset for `EventsByTagQuery`** `[H11]` — `written_at = now()` is transaction-start time (identical across a multi-row `AtomicWrite`, not strictly ordered across committers) and **cannot** be the offset. If the EventsByTag methods are stubbed (no in-repo consumer today, see WU 9), `seq` may be deferred — but the decision must be explicit, not implicit.
   - `things_journal_seq(pid TEXT PRIMARY KEY, highest_sn BIGINT NOT NULL, deleted_to BIGINT NOT NULL DEFAULT 0)` — high-water-mark metadata `[C1]`.
   - `things_snaps(pid TEXT, sn BIGINT, snapshot JSONB NOT NULL, lifecycle TEXT, written_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (pid, sn, written_at))` + `(pid, sn DESC)` btree + partial index on `lifecycle = 'DELETED'` `[H4]`.
   - Per-entity tables for `policies_*`, `connections_*`, `wot_*` following the same shape.
   - `schema_version(component TEXT PRIMARY KEY, version INT NOT NULL, checksum TEXT NOT NULL)` for refuse-to-boot guard on downgrade/drift.

6. **Adapter serializer split (×7) + a non-Mongo `PostgresSnapshotAdapter` `[G1]`.** Extract pure `*EventSerializer` / `*SnapshotSerializer` (`JsonObject` ↔ domain type) so Mongo wraps with BSON encoding and Postgres wraps with JSONB encoding. Adapters: 3 snapshot (`Thing/Policy/Connection MongoSnapshotAdapter`) + 4 event (`Thing/Connectivity/DefaultPolicy/WotValidationConfig MongoEventAdapter`) = **7 concrete classes** (re-grep confirmed §6); plus the **intermediate `AbstractPolicyMongoEventAdapter`** which carries policy-event serialization logic and must also be split — **8 files total**. **Snapshot-load payload contract `[G1]`:** `AbstractMongoSnapshotAdapter:136-143` throws unless the loaded object is `instanceof BsonValue`; r2dbc `loadAsync` returns `Json`/`String`/`byte[]`, **never** `BsonValue`, so first snapshot recovery throws `IllegalArgumentException`. WU 8's `loadAsync` MUST decode JSONB→`JsonObject` and feed a **Postgres-specific snapshot adapter** (not `AbstractMongoSnapshotAdapter`). Acceptance: same `JsonObject` round-trips through both adapter pairs **with field-level equality** (BSON→JSONB fidelity holds only through Ditto's `JsonObject` serializer — raw JSONB normalizes numbers and drops key order/duplicates `[LOW]`) **and** an end-to-end "recover a real `ThingPersistenceActor` from a Postgres snapshot" IT passes. (Re-grep adapter scope first — §6 critical item.)

7. **`PostgresJournal extends AsyncWriteJournal`.**
   - `asyncWriteMessages(Seq[AtomicWrite])` — per-`AtomicWrite` transaction (verified: matches the Pekko contract verbatim; Ditto produces **zero multi-event `AtomicWrite`s** — no `persistAll` call-sites, every `AtomicWrite` wraps a single `PersistentRepr` — so cross-`AtomicWrite` independence is safe; §6 important #1). Inside the same transaction, upsert `things_journal_seq.highest_sn = GREATEST(highest_sn, <max sn written>)` `[C1]`.
   - `asyncReadHighestSequenceNr` — `SELECT GREATEST(COALESCE(MAX(sn),0), COALESCE((SELECT highest_sn FROM things_journal_seq WHERE pid=$1),0))` — **never bare `MAX(sn)`** `[C1]`.
   - `asyncReplayMessages` — index scan on `(pid, sn)` with limit.
   - `asyncDeleteMessagesTo` — PK range delete per §11; set `things_journal_seq.deleted_to` in the same txn (does not touch `highest_sn`) `[C1]`.
   - Error mapping: dup `(pid, sn)` → **first SELECT the existing row; if payload+manifest match, complete the Future successfully** (idempotent handling of commit-then-connection-blip retries); only on genuine mismatch raise a fatal `JournalFailure` `[H4]`. `R2dbcTimeoutException` (pool acquire-timeout) → **failed Future**. **Correction `[H12]`:** a failed `asyncWriteMessages` Future is **NOT** backpressure — Pekko backpressure is mailbox/stash; a failed write **stops the persistent actor**. So a pool-acquire-timeout = a lost write + a stopped actor, which is exactly why `max-size`/`max-acquire-time` must be sized ≥ the Mongo baseline and load-tested (WU 4). `R2dbcBadGrammarException` at startup → schema-drift refuse-to-boot (verified non-transient).

8. **`PostgresSnapshotStore extends SnapshotStore`.** Multi-snapshot per §11.
   - `loadAsync(pid, criteria)` — **MUST honor the `SnapshotSelectionCriteria` Pekko always passes `[H9]`** (Pekko calls `loadAsync(pid, criteria.limit(toSequenceNr))`; Ditto uses non-default criteria for historical retrieval, `AbstractPersistenceActor:418-423`). Bind all four bounds: `SELECT … WHERE pid=$1 AND sn<=$2(maxSeq) AND sn>=$4(minSeq) AND written_at<=$3(maxTs) AND written_at>=$5(minTs) ORDER BY sn DESC, written_at DESC LIMIT 1` (guard `Long.MAX_VALUE`/epoch-millis→`TIMESTAMPTZ`). A bare `ORDER BY sn DESC LIMIT 1` returns the wrong snapshot for an at-revision request and breaks the recovery `toSequenceNr` bound. Decode JSONB→`JsonObject` and feed the Postgres snapshot adapter `[G1]`.
   - `saveAsync` — `INSERT … ON CONFLICT (pid, sn, written_at) DO UPDATE SET snapshot = excluded.snapshot, lifecycle = excluded.lifecycle` (idempotent; `written_at` mapped from `SnapshotMetadata.timestamp`, **not** `now()`) `[H4]`.
   - `deleteAsync` — **two distinct Pekko overloads `[H9]`**, do NOT collapse: `deleteAsync(SnapshotMetadata)` deletes exactly `(pid, sn, written_at)` (a bare sn-range would wrongly delete all `written_at` rows at that sn under the multi-snapshot PK); `deleteAsync(pid, SnapshotSelectionCriteria)` is upper-bounded `sn<=maxSeq AND written_at<=maxTs` with no lower bound. (The bounded `sn>=minSn AND sn<=maxSn` range belongs to the *separate* `DittoReadJournal.deleteSnapshots` read-journal method in WU 9 / §11 — not to `SnapshotStore.deleteAsync`.)

9. **`PostgresReadJournal implements DittoReadJournal`.** **`DittoReadJournal` has 26 abstract methods; every one needs an explicit disposition `[H10/coverage]` — `{SQL | documented no-op | documented throw}` — with a contract test asserting no method throws `UnsupportedOperationException` at runtime.** §10–§11 previously specced only ~6; the omissions below all have **live production consumers** and must be specced before ticketing. (Irony: `eventsByTag`/`currentEventsByTag`, which *were* specced, have **zero in-repo consumers**.)
   - `getJournalPidsWithTagOrderedByPriorityTag` — exact SQL in §10; containment via `tags @> ARRAY[$1]::text[]` (hits GIN), then `DISTINCT ON (pid)` over PK. **Must NOT drop pids that carry `always-alive` but no `priority-N` tag** (LEFT JOIN, default priority 0) `[H1]`.
   - `getNewestSnapshotsAbove` — exact SQL in §11, hits `(pid, sn DESC)` index.
   - `getJournalPidsWithTag` — pid-ID-order variant (`PersistencePingActor:93`).
   - `getJournalPidsAbove` / `getJournalPids` — pid streaming (`AbstractPersistenceStreamingActor:137/140`).
   - `currentEventsByPersistenceId` — historical recovery (`AbstractPersistenceActor:483`, `AbstractPersistenceSupervisor:278`); `WHERE pid=$1 AND sn BETWEEN $2 AND $3 ORDER BY sn`.
   - `getSmallestEventSeqNo` / `getSmallestSnapshotSeqNo` — cleanup bounds (`Cleanup.java:108/123`).
   - `getLastSnapshotSequenceNumberBeforeTimestamp` — (`AbstractPersistenceSupervisor:258`).
   - `getLatestEventSeqNo` / any highest-seq query — **MUST consult `things_journal_seq.highest_sn`** per `[C1]`, never bare `MAX(sn)`.
   - the 4 `ensure*Index` methods — **documented no-ops** (DDL is created upfront by `PostgresSchemaManager`).
   - `eventsByTag` / `currentEventsByTag` — `WHERE tags @> ARRAY[$1]::text[]` (GIN) `[H1]`. **`Offset` decision `[H11]`:** `EventEnvelope.offset()` is non-optional and the query needs a resumable global order — either build `Offset.sequence(seq)` from the `seq BIGINT IDENTITY` column (document the commit-visibility gap) **or**, since there is no in-repo consumer, return `Offset.noOffset()` and pin the stub with a test. Do not leave unspecified.
   - `deleteEvents` / `deleteSnapshots` — bounded PK range delete (`sn>=minSn AND sn<=maxSn`) returning `DeleteOutcome` (distinct from `SnapshotStore.deleteAsync`, WU 8).

10. **`PostgresPersistenceBackendProvider implements PersistenceBackendProvider`.** Resolves Ditto plugin IDs (`ditto-postgres-*`) and the `PostgresReadJournal`. `getReadJournal()` is `synchronized`-lazy — **matching `MongoPersistenceBackendProvider.java:91`, which already uses `synchronized` (NOT `Suppliers.memoize` — the earlier "retro-apply memoize" note was wrong; `synchronized` lazy is already correct and thread-safe)**. **Delete `pluralize()` heuristic** — require explicit HOCON `plugin-ids.<entity>.{journal,snapshot}` for every entity.

11. **R2DBC → Kamon observability** `[M5, M6, H14]`. Command timer via `r2dbc-proxy` listener (record on success **and** error). Pool gauges polled from `ConnectionPool.getMetrics()` (`PoolMetrics`, ~1s) — r2dbc-proxy has no pool hooks. Add **acquire-latency** via r2dbc-proxy `before/afterCreateOnConnectionFactory` (PoolMetrics has no wait-time) `[G4]`. **Emit a NEW neutral family `ditto_persistence_command_duration{engine=postgres, op_kind=…, status=…}` — do NOT claim "parity with Mongo" `[H14]`:** Ditto's Mongo metrics (`KamonCommandListener:38`) use a `_mongodb` name *suffix* + `command_name`/`cluster_id` tags, no `engine=` tag, only in thingsearch; `persistence-api` has zero metric constants. Leave Mongo untouched; no shared constants. Contract test asserts the Postgres family's tag keys + neutral op-kind value domain (not cross-engine name parity).

12. **HOCON profiles.** `ditto-postgres-{entity}-journal` / `-snapshots` / `-journal-read` blocks. Alternate `pekko.persistence.journal.auto-start-journals` for the Postgres profile.

13. **Offline CLI migrator.** Standalone JAR. Reads Mongo collections, writes Postgres tables via **`COPY`** (not the per-AtomicWrite runtime path) `[M8]`. **No second driver needed — verified `[G/confirmed]`:** r2dbc-postgresql exposes `PostgresqlConnection.copyIn(String, Publisher<ByteBuf>)` in the pinned 1.0.x jar, so the COPY path stays on r2dbc. Contract:
    - Checkpoint table `migration_progress(collection TEXT PRIMARY KEY, phase TEXT NOT NULL, last_pid TEXT, last_sn BIGINT, processed BIGINT DEFAULT 0, skipped BIGINT DEFAULT 0, failed BIGINT DEFAULT 0, finished BOOLEAN DEFAULT false, updated_at TIMESTAMPTZ DEFAULT now())` — **PK is `collection` only**; cursor columns (`last_pid`, `last_sn`) are mutable, single-row-per-collection upsert, mirroring `MigrationProgressTracker`'s phase + counter pattern `[M7]`.
    - Resumable from checkpoint after any failure; idempotent on re-run.
    - Schema-version emit (writes checksum to `schema_version`) and refuse-to-run on mismatch.
    - Parallelism flag `--workers=N`.
    - Verification mode `--verify`: random-sample re-read both sides, assert **field-level** parity post `JsonObject` serializer round-trip (not raw byte parity — JSONB normalizes) `[LOW]`.
    - Operator must stop Ditto `Cleanup` before running (documented). Mongo source is read-only.
    - Optional catch-up delta pass to bound downtime (acknowledge in-house online-migration precedent) `[H7]`.
    - Throughput: state as a **gate measured during the phase-2 spike**, not an a-priori floor — per-row transactions expect < 1k events/sec; the migrator MUST use batched `COPY` to reach useful rates `[M8]`.
    - Operator CHECKLIST shipped in the migrator's user-facing docs: read-only Mongo source, validation query, schema-version pre-check, downtime measurement, **explicit RPO + rollback time-box** (post-cutover rollback loses Postgres writes; "restore from backup" is not a free rollback) `[H7]`.

14. **Init-order regression tests.** Real `ActorSystem` (not mock). Instantiate **all four** persistent actor types (`ThingPersistenceActor`, `PolicyPersistenceActor`, `ConnectionPersistenceActor`, `WotValidationConfigPersistenceActor`); assert `journalPluginId()` and `snapshotPluginId()` return the configured Postgres values **during construction** (probe actor), not merely "before first message" `[H8]`. CI gate. (Test the **field-free `context().system()` lookup** — `Props`-injection is NOT adopted because it hits the same null-field trap, see `[H8]`.)

15. **BOM Pekko pin + enforcer rule.** The exact property is **`pekko-bom.version`** (`bom/pom.xml:46`), value `1.6.0` — **not** `pekko.version`, which does not exist `[LOW]`. Add Maven `requireUpperBoundDeps` + a banned-range check that fails CI if anyone changes `pekko-bom.version` (and the related `pekko-http-bom.version`, `pekko-persistence-mongodb.version`) to a range or overrides it in a downstream `<dependencyManagement>`.

16. **Integration tests.** Testcontainers PostgreSQL. `PostgresDbResource` mirroring `MongoDbResource`. Parity matrix vs Mongo IT covering: write/recover/cleanup round-trip; **high-water-mark survives physical delete** (write to sn=N, snapshot, delete ≤N, assert `asyncReadHighestSequenceNr` still returns N) `[C1]`; **`(BsonDocument)`-cast removal — Postgres historical-revision recovery round-trips a real event** `[C2]`; **EXPLAIN shows GIN index usage for `tags @> ARRAY[...]`** (hard gate) `[H1]`; **priority-tag query survives a malformed `priority-x` / `priority-` tag without aborting** (negative test) `[H1/cast]`; **priority ordering parity** (fixture: pid1=`priority-10`, pid2=`priority-2`, pid3=`priority-3`, pid4=`priority-4`, pid5=`always-alive`-no-priority; assert Postgres pid order == Mongo order = exactly `[pid1,pid4,pid3,pid2]` with **distinct** priorities so `containsExactly` is well-defined, and pid5 PRESENT in any position) `[H1]`; **PgBouncer txn-pooling smoke** (run IT suite behind a PgBouncer container in `pool_mode=transaction` with `prepared-statement-cache-queries=0`; assert write/recover/cleanup + xact-lock DDL bootstrap all succeed) `[H3]`; dup `(pid, sn)` on the **journal** (idempotent-success on match, fatal on mismatch) `[H4]`; snapshot retry idempotency (same metadata → upsert, no crash) `[H4]`; **`loadAsync` honors `SnapshotSelectionCriteria`** (save sn=5/10/20, assert `maxSequenceNr=10` returns sn=10) `[H9]`; **snapshot-store `deleteAsync` two-form correctness** (metadata form deletes one `(pid,sn,written_at)` row; criteria form respects `sn<=maxSeq AND ts<=maxTs`) `[H9]`; **recover a real `ThingPersistenceActor` from a Postgres JSONB snapshot** (PostgresSnapshotAdapter, no `BsonValue` cast) `[G1]`; oversize JSONB / TOAST; partial-batch rollback; N=2 concurrent first-boot (DDL race under xact-lock); **schema drift via divergent pre-existing PK** (old `(pid,sn)` table → live-catalog check refuses to boot) `[H13]`; schema drift (checksum mismatch refuse-to-boot); advisory-lock holder SIGKILL mid-DDL (txn rollback, clean retry); **pool acquire-timeout under slow PG stops the actor (asserts the failed-write semantics, not "backpressure")** `[H12]`; **pool sizing load test vs N concurrent persistent actors** (gate before freezing defaults) `[H12]`; migrator partial-batch resume; migrator verification mode; observability — Postgres `ditto_persistence_command_duration{engine=postgres}` tag-key + op-kind value domain (no cross-engine name parity) `[H14]`.

## 5. Settled phase-2 questions

| Question | Decision |
|---|---|
| Tag column type | `TEXT[]` + GIN, queried with `@>` containment (§10). |
| Manifest column | Fully-qualified event-type FQN (Pekko compatibility). |
| Migration tool | Offline CLI, hand-rolled, `COPY`-based; contract per WU 13. |
| Per-actor-type plugin IDs | Mirror Mongo: per-actor-type plugin keys + per-entity tables. |
| Postgres version floor | **PG 16** (was 14; PG 14 EOL Nov 2026 makes a "14+ lifecycle floor" self-contradictory), CI'd in a 15/16/17 matrix `[H15]`. |
| Journal high-water-mark | Per-pid metadata table; `asyncReadHighestSequenceNr` never bare `MAX(sn)` (§11). |
| Snapshot uniqueness | PK `(pid, sn, written_at)` from `SnapshotMetadata.timestamp`; idempotent upsert (§11). |

## 6. Open items before /forge-to-beads

### Critical

1. **Adapter scope — re-grep DONE (2026-05-29), count of 7 CONFIRMED, two caveats.** The 7 concrete adapters are: snapshot ×3 — `ConnectionMongoSnapshotAdapter`, `PolicyMongoSnapshotAdapter`, `ThingMongoSnapshotAdapter`; event ×4 — `ConnectivityMongoEventAdapter`, `DefaultPolicyMongoEventAdapter`, `ThingMongoEventAdapter`, `WotValidationConfigMongoEventAdapter`. **Caveat (a):** the originally-suggested grep `extends Abstract.*MongoEventAdapter` is **wrong** — it misses `DefaultPolicyMongoEventAdapter` (which extends the intermediate `AbstractPolicyMongoEventAdapter`, not `Abstract…MongoEventAdapter` directly) and instead catches the abstract intermediate. Use `grep -rn --include="*.java" -E "extends [A-Za-z0-9_]*Mongo(Event|Snapshot)Adapter"` and exclude `abstract class` lines. **Caveat (b):** the serializer split must also touch the **intermediate `AbstractPolicyMongoEventAdapter`** (`policies/.../serializer/`), so the WU 6 work spans **8 files** even though there are 7 concrete adapters.
2. **Write-side reuse — SETTLED (2026-05-29), no longer a spike `[H6, G2]`: build all three classes custom.** The spike was executed against `pekko-persistence-r2dbc 1.1.0` sources; the conclusion is confirmed with no remaining unknown, so no timebox is needed. Per-class disconfirmation-of-reuse:

   | Class | Verdict | Disconfirmation (why fork yields nothing reusable) |
   |---|---|---|
   | **PostgresJournal** (write) | CUSTOM | Upstream `JournalDao` has three independent each-fatal blockers: (a) payload is `Option[Array[Byte]]` bound as **BYTEA via Pekko `SerializationExtension`**, **no JSONB knob** (`reference.conf` has none); (b) `deleteMessagesTo` does a physical `DELETE … WHERE seq_nr<=?` **AND inserts a `deleted=true` tombstone row** (`JournalDao.scala:146-152`) — incompatible with Ditto's physical-delete cleanup and the tombstone is exactly what reintroduces `[C1]`; (c) `HighestSequenceNrDao` is bare `SELECT MAX(seq_nr)` (`:50-52`) — reusing it reintroduces `[C1]`. Forking would replace `writeEvents` + `deleteMessagesTo` + `readHighestSequenceNr` — the entire DAO. |
   | **PostgresSnapshotStore** | CUSTOM | Upstream `SnapshotDao`: payload `Array[Byte]` BYTEA (no JSONB knob), PK is **`persistence_id` ONLY** with single-row `ON CONFLICT (persistence_id) DO UPDATE` (`:105-118`) — **no multi-row / keepN retention**. Incompatible with Ditto's `PRIMARY KEY (pid, sn, written_at)` multi-row JSONB retention `[H4, H9, G1]`; the PK shape alone forbids at-revision snapshot history. |
   | **PostgresReadJournal** | CUSTOM (already settled) | `R2dbcReadJournal` (`:60-69`) implements only slice/persistence-id/paged queries — **no `EventsByTagQuery`/`CurrentEventsByTagQuery` SPI** and no tag-ordered-PID query. `eventsBySlices` is a timestamp-ordered **event** stream; it cannot express "distinct **PIDs** ranked by an int extracted from each PID's most-recent event's tags" — wrong cardinality (events not pids), wrong ordering key (timestamp not priority), no group-by-latest-tags primitive. Coercion is impossible, not awkward. |

   **Spec correction this surfaced:** the journal payload is BYTEA-only too (not "config-selectable to JSONB" — `reference.conf` has zero payload-type knobs); both journal and snapshot are BYTEA-only, which *strengthens* the build-custom case. Fixed in §2.

### Important

1. **Transaction-boundary spec for `asyncWriteMessages(AtomicWrite[])` — RESOLVED (2026-05-29).** Ditto produces **zero multi-event AtomicWrites**: there are **no `persistAll`/`persistAllAsync` call-sites anywhere** (0 matches incl. tests), and the only event-persisting base class `AbstractPersistenceActor` uses single-event `persist(event, handler)` at exactly 2 sites (`AbstractPersistenceActor.java:906` normal events, `:671` `PersistEmptyEvent`). Per Pekko 1.6.0 `Eventsourced.internalPersist` (`Eventsourced.scala:399-412`) a single-event `persist` produces an `AtomicWrite` wrapping **one** `PersistentRepr`; only `internalPersistAll` (`:419-436`) wraps a `Seq`, and Ditto never calls it. So **per-`AtomicWrite` transaction + cross-`AtomicWrite` independence** (each `AtomicWrite` gets its own `Optional[Exception]` slot in `WriteMessages`) is trivially correct. The design keys the transaction on the `AtomicWrite` (all its `PersistentRepr`s in one tx), so it stays correct even if a multi-event `persistAll` is ever added. **Re-grep gate before WU 7:** `grep -rEn "persistAll" --include=*.java --include=*.scala . | grep -v /target/`; if any hit appears, re-validate only the cross-batch-independence simplification.
2. **Priority ordering direction** `[H1]` — **RESOLVED (2026-05-29): DESCENDING (highest priority recovered first), `ORDER BY … DESC` locked.** Proven empirically: the Mongo runtime sort is `Sorts.descending(J_TAGS)` (`MongoReadJournal.java:870`) with a **separate** numeric-ordering collation `Collation…numericOrdering(true)` (`:884`) so `priority-10 > priority-2` numerically — two distinct mechanisms, not a conflation. ITs confirm: `MongoReadJournalIT.java:401-417` inserts priorities 10/2/3/4 and asserts order `[pid1,pid4,pid3,pid2]` (= 10,4,3,2, highest-first, numeric). Recovery consumes the `Source` in stream order (`PersistencePingActor.java:190-193/209` → first pid pinged first). The `:470` javadoc word "ascending" is a **stale doc bug** contradicting the correct `:463-464` "Descending" — fix that javadoc as a drive-by. `always-alive`-without-`priority-N` pids are KEPT at default 0 but **unordered among themselves** (`MongoReadJournalIT.java:517-528` `containsExactlyInAnyOrder`), which the §10 `COALESCE(p.prio,0)` LEFT JOIN already matches. **Parity-test caveat:** equal-priority pid order is undefined on both engines (no tiebreak), so the strict-order assertion must use **distinct** priorities; duplicate-priority fixtures assert group-wise. (See §10 + the WU 16 parity row.)
3. **Schema-evolution policy — RESOLVED (2026-05-29).** Written as new subsection §11 "Schema-evolution policy". Summary: **DDL is additive-only** (new tables / nullable-or-defaulted columns / indexes; indexes on *populated* tables via `CREATE INDEX CONCURRENTLY` out-of-band post-launch, never in the boot txn `[M2]`); breaking DDL (type changes, NOT NULL without default, PK/constraint redefinition, drops/renames) = an explicit migration WU, never silent `CREATE TABLE IF NOT EXISTS` (the live-catalog check `[H13]` refuses to boot on the resulting divergence). **Payload evolution:** additive JSONB fields need no DDL; breaking event-shape/TYPE changes go through `EventAdapter.manifest` + `fromJournal`/`performFromJournalMigration` exactly as Mongo does today (verified live precedents: `AbstractPolicyMongoEventAdapter` legacy-TYPE discard, `ConnectivityMongoEventAdapter` encrypt/decrypt + `ConnectionMigrationUtil`), reused unchanged via the WU 6 serializer split. The `schema_version` checksum + live-catalog check `[M1/H13]` enforce **DDL drift ONLY** and explicitly do **not** version payloads (`JsonSchemaVersion` is a `toJson` field-filter, not a stored stamp). `[SE1]`
4. **r2dbc SSL config and DDL-runner role — CLOSED (2026-05-29).** r2dbc-postgresql `1.0.7` Option keys verified (`sslMode`/`sslRootCert`/`sslCert`/`sslKey`/`sslPassword`; `sslMode` accepts `disable|allow|prefer|require|verify-ca|verify-full|tunnel`). **Two `verify-full` failure modes documented `[G5]`:** a configured-but-missing cert *path* → `IllegalArgumentException` at **boot** (fail-fast); `verify-full` with **unset** `sslRootCert` → driver silently uses the **JVM default truststore** and the TLS handshake fails **at first connect, not boot**. Recommend a client-side boot guard (`ssl.allow-system-truststore = false` default) to convert the silent connect-time failure into a loud boot error. **Two-role split finalized:** DDL role **owns** the schema (`CREATE`), runtime role is **DML-only** via `ALTER DEFAULT PRIVILEGES`. Confirmed: `pg_advisory_xact_lock` needs **no** privilege; `GENERATED ALWAYS AS IDENTITY` INSERT needs **no** `USAGE ON SEQUENCE` (unlike `SERIAL` — so do not use SERIAL); cleanup uses `DELETE` not `TRUNCATE` (no TRUNCATE grant). Concrete `ssl{}`/`ddl-credentials{}` HOCON in §7; GRANT script in §11.
5. **PgBouncer stance** `[H3, M3]` — **RESOLVED (2026-05-29).** `prepared-statement-cache-queries = 0` **is sufficient** and ships as the default floor: verified that `0` → `DisabledStatementCache.getName()` returns the empty/unnamed statement name, so r2dbc issues `Parse("")/Bind(portal,"")` per execution and **no named server-side statement persists across a transaction boundary** — safe on **any** PgBouncer version and any `pool_mode`, at the cost of re-parsing. Opt-in alternative (named cache, `>0` or driver default `-1`) is allowed **only** behind PgBouncer **≥ 1.21.0** with `max_prepared_statements > 0` (default was 0 in 1.21, later 200 — operator must verify; the `≥1.21` claim is confirmed). **`pooler-mode` is informational only** (no boot block, no `SHOW pool_mode` probe — that command doesn't exist): a single startup line — `transaction` + cache≠0 → WARN; `transaction` + cache=0 → INFO "safe"; `session`/`direct` → silent. Residual session-state under transaction pooling all verified safe (xact-lock bootstrap pinned to one backend, `fetch-size=0`, no session `pg_advisory_lock`, no LISTEN/NOTIFY, unnamed statements) — **add the explicit constraint that any required session GUC must be `SET` inside the same transaction**, never assumed to persist.
6. **Test parity matrix** — WU 16 enumerates; ensure each row maps to a test file when beads tickets get cut.
7. **Observability bridge approach validation — RESOLVED (2026-05-29).** Confirmed: **no upstream Kamon r2dbc instrumentation module exists** (no `kamon-r2dbc` on Maven Central / Kamon docs / GitHub) for the Pekko 1.6 / **Kamon 2.8.1** (`bom/pom.xml:79`) era. The WU 11 design — r2dbc-proxy listener for the command timer + `ConnectionPool.getMetrics()` (`PoolMetrics`) polling for gauges + r2dbc-proxy `before/afterCreateOnConnectionFactory` for acquire-latency — is the correct build-it-ourselves choice. **Add `io.r2dbc:r2dbc-proxy` to the BOM pins** (WU 3, `[G3]`) and confirm it converges with `r2dbc-spi 1.0.0`.

### Nice-to-have

1. `EXPLAIN ANALYZE` expectations as acceptance criteria for `getJournalPidsWithTagOrderedByPriorityTag` and `getNewestSnapshotsAbove` (WU 9) — partly promoted to a **hard gate** for the GIN containment path `[H1]`.
2. ~~Caffeine 30s TTL cache around `getJournalPidsWithTagOrderedByPriorityTag`~~ — **drop or re-justify**: the "called every 60s" premise is wrong, the real ping `interval` default is **10m** (`policies.conf:229`, and the connectivity equivalent), so a 30s TTL almost never serves a hit and the cache adds little. Only worth it if a deployment tunes the ping interval far down.
3. Extend `PersistenceBackendProvider` with `getJournalAdapter(entityType)` / `getSnapshotAdapter(entityType)` (phase 3).
4. **Autovacuum tuning shipped as a default, not just docs** `[H5]` — `autovacuum_vacuum_scale_factor = 0.01` for `*_journal`/`*_snaps` tables; add row-count/bloat alerting; consider a keep-N-snapshots hard cap as defense-in-depth against cleanup starvation.

### Operational / completeness gaps surfaced by the second review `[G1–G5]`

1. **Postgres backup / PITR / RTO story `[G5]`** — Mongo (the backend being replaced) ships backed-up; the plan defines no equivalent for Postgres. Define backup cadence, PITR (WAL archiving), and a tested restore RTO before cutover. This is independent of the `[H7]` rollback RPO.
2. **thingsearch stays on Mongo at cutover** — be explicit: this plan migrates the *persistence* journals/snapshots; the search index (thingsearch) remains MongoDB. Document the coexistence (two datastores post-cutover) so operators don't decommission Mongo prematurely.
3. **`sslmode = verify-full` needs CA/cert provisioning `[G5]`** — defaulted-on with no trust-store/CA provisioning will **hard-fail boot** when the cert chain is absent. Ship the CA-mount/rotation guidance alongside the default, or it becomes a boot blocker.
4. **`pid TEXT` collation `[G5]`** — cursor pagination (`WHERE pid > $1`) and ordering depend on collation; declare **`COLLATE "C"`** on `pid` columns for byte-stable, OS-locale-upgrade-immune ordering that matches Mongo's byte ordering. Cheap hardening.
5. **Concurrent-write isolation per pid** — Mongo had per-document atomicity; state the Postgres isolation expectation for `asyncWriteMessages` (the `(pid,sn)` PK already serializes same-pid writes via unique violation, but document it and the read-committed assumptions for the high-water-mark upsert).
6. **Effort re-estimate** — the 16-WU list is a **lower bound**. With WU 1.7, the WU 9 method expansion, the snapshot adapter, and the EventsByTag offset work, **expect +4–6 WUs**.

## 7. Backend selection runtime

Today (Mongo only):

```hocon
ditto.extensions.persistence-backend-provider = {
  extension-class = "org.eclipse.ditto.internal.utils.persistence.mongo.MongoPersistenceBackendProvider"
}
```

Declared in `internal/utils/persistence/src/main/resources/reference.conf`; services may override per environment.

Phase 2 (after Postgres impl):

```hocon
ditto.extensions.persistence-backend-provider = {
  extension-class = "org.eclipse.ditto.internal.utils.persistence.postgres.PostgresPersistenceBackendProvider"
  extension-config {
    uri = ${POSTGRES_URI}           # runtime role: DML-only (SELECT/INSERT/UPDATE/DELETE), no CREATE
    pooler-mode = direct            # direct | session | transaction — informational only; drives a
                                    # soft-WARN cross-check vs prepared-statement-cache-queries. No boot block. [H3]
    pool {
      max-size = 100                # >= Mongo code default (100); per-service Helm override:
                                    #   things/policies -> 200 (match deployed Mongo maxPoolSize),
                                    #   connectivity -> 50, search n/a (stays Mongo). LOAD-TEST GATE [H12].
      max-acquire-time = 30s        # matches Mongo; 5s risks acquire-timeout -> failed writes -> stopped actors
      fetch-size = 0                # no server-side portal/cursor; keeps transaction-pooling safe
      prepared-statement-cache-queries = 0   # 0 = unnamed statements -> safe under PgBouncer txn pooling on
                                    # ANY version. Set >0 only behind PgBouncer>=1.21 with max_prepared_statements>0. [M3]
      ddl-credentials {             # DDL role: OWNS the schema/tables (CREATE). Bootstrap-only. See §11 GRANT script.
        username = ${POSTGRES_DDL_USER}
        password = ${POSTGRES_DDL_PASSWORD}
      }
    }
    ssl {
      mode = verify-full            # disable|allow|prefer|require|verify-ca|verify-full ; r2dbc Option "sslMode"
      mode = ${?POSTGRES_SSL_MODE}  # verify-full validates the server cert chain AND the hostname.
      # root-cert => r2dbc "sslRootCert": PEM CA bundle the server cert must chain to. [G5]
      #   UNSET with verify-full/verify-ca -> driver uses the JVM default truststore and the TLS handshake
      #     FAILS AT FIRST CONNECT (not boot) unless the server cert is system-trusted.
      #   SET to a non-existent path -> r2dbc throws IllegalArgumentException at BOOT (fail-fast).
      root-cert = ${?POSTGRES_SSL_ROOT_CERT}        # e.g. /etc/ditto/pg-tls/ca.crt
      allow-system-truststore = false               # Ditto guard: refuse to boot if verify-(ca|full) AND root-cert unset
      # Optional mTLS (r2dbc "sslCert"/"sslKey"/"sslPassword"); PEM cert + PKCS#8 key:
      cert         = ${?POSTGRES_SSL_CERT}
      key          = ${?POSTGRES_SSL_KEY}
      key-password = ${?POSTGRES_SSL_KEY_PASSWORD}
    }
  }
}
```

Wiring note (`DittoPostgresClient`): set `builder.option(SSL_MODE, SSLMode.fromValue(mode))`; only set `SSL_ROOT_CERT`/`SSL_CERT`/`SSL_KEY`/`SSL_PASSWORD` when non-empty (r2dbc's `requireExistingFilePath` throws on empty/missing). If `mode` ∈ {`verify-ca`,`verify-full`} and `root-cert` is unset and `allow-system-truststore = false`, **fail-fast at boot** — converts the silent connect-time handshake failure `[G5]` into a clear boot error. Cert provisioning: mount the PG (or PgBouncer-termination) CA PEM read-only at `/etc/ditto/pg-tls/ca.crt`; CA rotation = staged multi-CA PEM + **pod restart** (r2dbc reads the cert once at `ConnectionFactory` build).

Plus alternate `pekko.persistence.journal.auto-start-journals` listing the Postgres plugin keys.

## 8. Explicitly NOT in scope

- Multi-backend mixed-cluster mode (some entities Mongo, some Postgres).
- Flyway / external migration tool for DDL (revisit only if the xact-lock + checksum bootstrap proves insufficient).
- Per-service phased rollout (violates strict either/or per deployment).
- Online dual-write migration (offline CLI only; a catch-up delta pass to bound downtime is in scope per WU 13).
- Adopting `pekko-persistence-r2dbc` as a Pekko plugin (only its driver-level dependencies are reused; write-side reuse is SETTLED as "build all three custom" — §6 `[H6]`).

## 9. Decision log

- **2026-05-22** — First `/forge-review-plan` pass (HOLD SCOPE). 8 decisions recorded (C1–C8): adopt upstream r2dbc plugin, add observability bridge, `IF NOT EXISTS` + advisory-lock DDL, defer rollout to ops, offline CLI migrator, BSON-leak purge before read-journal impl, hoist convention constants, pin Pekko + init-order test.
- **2026-05-28** — Second `/forge-review-plan` pass found upstream `pekko-persistence-r2dbc` 1.1.x cannot host Ditto's tag queries or multi-snapshot cleanup, and stores BYTEA not JSONB. C1 refined to the hybrid path ("Custom Ditto Postgres Pekko persistence plugin on top of the r2dbc driver libs"). Companion docs `postgres-tags-approach.md` and `postgres-cleanup-approach.md` written. Phase-2 roadmap rewritten as the 16 WUs in §4; per-actor-type plugin-ID and PG-floor decisions settled.
- **2026-05-29** — **Multi-agent critical review** (12 specialist verifiers + synthesis, checked against Pekko 1.6.0 source, PostgreSQL/PgBouncer/r2dbc docs, and the repo at `bda04b5009`). Architecture and keystone decisions confirmed sound; the following defects were fixed in-place and the companion docs folded into this file (now §10–§11):
  - **`[C1]` CRITICAL** — `asyncReadHighestSequenceNr = MAX(sn)` regressed sequence numbers after physical-delete cleanup → added per-pid high-water-mark metadata.
  - **`[H1]`** — `= ANY(tags)` does not use GIN → switched to `tags @> ARRAY[$1]`; priority CTE no longer drops `always-alive`-without-`priority-N` pids; EXPLAIN promoted to a hard gate.
  - **`[H2]`** — session `pg_advisory_lock` unsafe over r2dbc-pool → `pg_advisory_xact_lock` in one transaction.
  - **`[H3]`** — `SHOW pool_mode` not implementable → dropped detection; explicit `pooler-mode` config + soft WARN.
  - **`[H4]`** — snapshot bare INSERT + `(pid,sn)` PK + dup→fatal crashes on retry → PK `(pid,sn,written_at)`, idempotent upsert; fatal-on-dup reserved for the journal (with idempotent-on-match handling).
  - **`[H5]`** — autovacuum tuning + bloat alerting promoted from nice-to-have toward default.
  - **`[H6]`** — "build all three plugin classes" downgraded to a spike; only ReadJournal is unavoidable.
  - **`[H7]`** — "rollback = restore from backup" reframed as explicit RPO + time-box; catch-up delta pass added.
  - **`[H8]`** — Pekko init-order workaround verified correct but fragile; `Props`-injection preferred; regression test must probe during construction.
  - **`[M1]`–`[M10]`, LOW** — schema_version checksum, `indisvalid` dead-code removal, PgBouncer prepared-statement fix, `Cleanup.java` decoupling, r2dbc-proxy pool-hook gap, metric `engine=` tag, `migration_progress` PK fix, `COPY`-based throughput gate, jdbc-rejection rationale, enforcer ban on `pekko-persistence-r2dbc*`, PG-floor re-justified on support lifecycle (→ 14+), `pekko-bom.version` property-name fix, JSONB fidelity caveat, Reactor/Netty bridge note.
- **2026-05-29 (second adversarial pass)** — **55-agent hostile re-review** (13 specialists, each claim independently re-derived against the repo + Pekko 1.6.0 source + PG/PgBouncer/r2dbc docs, plus a devil's-advocate challenge round on every non-confirmed claim). **Keystones C1/H1/H2/H4 and the init-order `context()` lookup all UPHELD** under double scrutiny. New defects fixed in-place this revision:
  - **`[C2]` CRITICAL** — `(BsonDocument) eventEnvelope.event()` cast in the *backend-agnostic* `AbstractPersistenceActor:1083` / `AbstractPersistenceSupervisor:319` throws under Postgres; scheduled by no WU → added **WU 1.7** (blocks WU 9).
  - **`[H8]` refuted-fix** — "`Props`-injection is the preferred hardening" is **false** (reflective constructor → same null-field trap); field-free `context().system()` stays primary.
  - **`[H9]`** — snapshot `loadAsync` ignored `SnapshotSelectionCriteria`, and `deleteAsync`'s two Pekko overloads were collapsed into one wrong sn-range → both fixed (WU 8, §11).
  - **`[H10]`** — WU 9 specced ~6 of 26 read-journal methods → enumerated all with explicit dispositions; upstream snapshot plugin confirmed BYTEA-only (no JSONB knob); JSONB needs a custom Pekko serializer for Ditto.
  - **`[H11]`** — no `EventsByTagQuery` offset column → added `seq BIGINT IDENTITY` (or documented `noOffset()` stub).
  - **`[H12]`** — pool 50/5s below Mongo baseline + a failed write mislabeled "backpressure" → defaults raised to ≥100/30s + load-test gate; semantics corrected (failed write stops the actor).
  - **`[H13]`** — `schema_version` checksum can't catch a divergent pre-existing PK under `CREATE TABLE IF NOT EXISTS` → added live-catalog verification in the bootstrap txn.
  - **`[H14]`** — observability "parity with Mongo via `engine=` tag" was fiction (Mongo uses `_mongodb` suffix, thingsearch-only) → emit a new neutral family instead.
  - **`[H15]`** — PG-floor "14+" self-contradictory (PG 14 EOL Nov 2026) → raised to **PG 16**.
  - **`[G1]`–`[G5]`** — gaps: Postgres snapshot adapter (`instanceof BsonValue` trap), write-side reuse is a near-total rewrite (3 blockers), r2dbc version pins missing in `bom/pom.xml` + `fetch-size=0`, pool acquire-latency metric, operational (backup/PITR, thingsearch coexistence, SSL CA provisioning, `COLLATE "C"`).
  - **Stale-citation sweep** — `Cleanup.java` does NOT import `org.bson.Document` (constants only); ping cadence is **10m** not 60s (guts the 30s-cache rationale); `MongoPersistenceBackendProvider` uses `synchronized`-lazy not `Suppliers.memoize`; issue `#181` misattribution dropped; `:870/:884` priority-direction conflation noted.
- **2026-05-29 (open-items resolution pass)** — **7-agent isolated research fan-out** (one per remaining §6 open item, repo + Pekko 1.6.0 + PG/PgBouncer/r2dbc 1.0.7 / pekko-persistence-r2dbc 1.1.0 sources). All resolved:
  - **§6 important #1 (AtomicWrite)** — verified ZERO multi-event `AtomicWrite`s (no `persistAll` call-sites; single-event `persist` at `AbstractPersistenceActor:906/671`); per-`AtomicWrite` txn trivially correct. Re-grep gate retained.
  - **§6 important #2 (priority direction `[H1]`)** — locked **DESCENDING** (`MongoReadJournal.java:870` `Sorts.descending` + `:884` numeric collation; IT `extractJournalPidsInOrderOfTags` 10>4>3>2); `:470` javadoc "ascending" is a stale bug (drive-by fix). Parity test + tie-order caveat added.
  - **§6 important #3 (schema-evolution)** — two-axis policy written as §11 subsection (additive-only DDL; payload evolution via `manifest`+`fromJournal`; `schema_version` guards DDL drift only). `[SE1]`.
  - **§6 important #4 (SSL/role)** — r2dbc `1.0.7` SSL keys verified; two `verify-full` failure modes + boot guard; two-role GRANT script (IDENTITY needs no sequence grant; advisory lock needs no privilege). §7 HOCON + §11 script.
  - **§6 important #5 (PgBouncer `[H3,M3]`)** — `prepared-statement-cache-queries=0` → unnamed statements, safe on ANY PgBouncer version; `pooler-mode` soft-WARN finalized; session-GUC-in-same-txn constraint added.
  - **§6 important #7 (observability)** — confirmed NO upstream `kamon-r2dbc` (Kamon `2.8.1`); r2dbc-proxy + `PoolMetrics` is correct; `r2dbc-proxy` added to BOM pins.
  - **`[H6]` write-side reuse** — downgraded from spike to **SETTLED: build all three custom**, per-class disconfirmation table in §6 (journal: BYTEA + delete-marker + `MAX(seq_nr)`; snapshot: `PK(persistence_id)` single-row; read-journal: no `EventsByTagQuery`). Spec corrected: upstream **journal** payload is BYTEA-only too (no JSONB knob).
- **2026-06-01 — IMPLEMENTED via `/forge-implement`** (7 dispatch waves of parallel/merged subagents, collision-aware grouping by shared file/module). All 18 beads tickets closed; 9 commits `409d7e784e`..`e8fc36324f` (local on branch). `mvn verify` green with enforcer ON: 100 unit + 27 Testcontainers ITs on real PostgreSQL 16 + real PgBouncer, 0 failures (1 documented skip). Notes:
  - **Keystones verified on real PG** — C1 HWM-survives-physical-delete, H1 GIN `@>` EXPLAIN hard-gate + descending priority (malformed tag tolerated), H4 dup idempotent/fatal, H9 criteria + two-form delete, G1 JSONB-snapshot Thing recovery, H13 checksum + divergent-PK refuse-to-boot, N=2 concurrent-DDL race, H12 lost-write-stops-actor. No defects found in WU4–WU14 code.
  - **Enforcer inheritance fix** — the bom enforcer execution had no `<inherited>false</inherited>`; since bom is the parent pom of nearly every module, the inherited `requireUpperBoundDeps` broke the whole build on a pre-existing byte-buddy/assertj convergence gap. Split into inherited `enforce-no-pekko-r2dbc-plugin` (M10 ban, project-wide) + bom-only `enforce-bom-version-pins`.
  - **Testcontainers `api.version` trap** — TC 1.20.6's shaded docker-java clamps to Docker API 1.32 (modern daemons reject), which had been SILENTLY skipping the early ITs; `PostgresDbResource` sets `api.version=1.43` before TC loads.
  - **Thing-first wiring (follow-up):** only `ThingPersistenceActor` resolves the provider in production; Policy/Connection/WotValidationConfig still return hardcoded Mongo plugin-ids.
  - **Deferred:** `[M8]` COPY throughput + `[H12]` pool-sizing load gates (load env, not TC); live-Mongo migrator IT (needs `mongodb-driver-sync` + `testcontainers:mongodb` in BOM).

---

## 10. Tag-based queries (schema + SQL)

> Folded in from the former `postgres-tags-approach.md` (research 2026-05-28), corrected per `[H1]`.

### What tags exist and where they come from

Tags are string labels attached to each journal event at persist time.

**Attachment flow:**
1. Each persistence actor's `modifyEventBeforePersist()` sets the `ditto-event-journal-tags` header on the event's `DittoHeaders`.
2. `AbstractMongoEventAdapter.toJournal()` reads `event.getDittoHeaders().getJournalTags()` and wraps the serialized event in Pekko's `Tagged(payload, tags)` object.
3. The journal plugin stores tags in the `tags` column (Mongo: array field; Postgres: `TEXT[]`).

**Vocabulary:**

| Tag | Constant location | Purpose |
|---|---|---|
| `"always-alive"` | `AbstractPersistenceActor.JOURNAL_TAG_ALWAYS_ALIVE` | Marks entities recovered on startup (connections with `desiredStatus=OPEN`). |
| `"priority-N"` | `MongoReadJournal.PRIORITY_TAG_PREFIX + N` | Orders entity recovery — higher N recovered first. |
| User-defined tags | `Connection.getTags()` | Operator-specified tags on connections. |
| Event-type tags | e.g. `"wot-validation-config-created"` | Used by WoT validation config adapters. |

**Connection example** (`ConnectionPersistenceActor.activeConnectionTags()`, lines 463-471): if `desiredStatus == OPEN`, tags = `{"always-alive", "priority-<N>"}` (default N=0); if closed, `{}`; user-defined `Connection.getTags()` always included.

### How tags are queried

- **`getJournalPidsWithTagOrderedByPriorityTag(tag, interval)`** — the critical consumer. Called by `PersistencePingActor` (`streamingOrder = TAGS`) to decide which connections to recover and in what order. Mongo does a `$match` (tag) → `$group` by pid taking `$last` tags → `$filter` `priority-*` → `$sort`. **Membership semantics:** `Filters.eq(J_TAGS, tag)` is element-membership (verified `MongoReadJournal.java:442/851/910`), which maps to `@>` containment, not `= ANY`.
- **`eventsByTag` / `currentEventsByTag`** — standard Pekko query SPI (`EventsByTagQuery` / `CurrentEventsByTagQuery`). Upstream `pekko-persistence-r2dbc` does **not** implement these (slice queries only) — the reason a custom ReadJournal is unavoidable.
- **`getJournalPidsWithTag(tag, batchSize, interval, …)`** — pid-ID-order variant for `streamingOrder = ID`.

### Schema

```sql
CREATE TABLE IF NOT EXISTS things_journal (
    pid          TEXT        NOT NULL COLLATE "C",  -- byte-stable cursor order, Mongo-parity [G5]
    sn           BIGINT      NOT NULL,
    seq          BIGINT      GENERATED ALWAYS AS IDENTITY,  -- monotonic EventsByTag offset [H11]
    manifest     TEXT        NOT NULL,
    tags         TEXT[]      NOT NULL DEFAULT '{}',
    event        JSONB       NOT NULL,
    written_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pid, sn)
);

CREATE INDEX IF NOT EXISTS things_journal_tags_idx ON things_journal USING GIN (tags);
```

> **`seq` is the `EventsByTagQuery` offset `[H11]`.** `written_at = now()` is transaction-start
> time — identical across a multi-row `AtomicWrite` and not strictly ordered across committers —
> so it cannot serve as a resumable `Offset`. Build `Offset.sequence(seq)` (mind commit-visibility
> gaps), or, since no in-repo consumer reads the tag offset today, stub the EventsByTag methods with
> `Offset.noOffset()` and pin the choice with a test (WU 9). Do not leave it implicit.

**Why `TEXT[]` over tags-in-JSONB:** compact GIN index over tag values only; `tags @> ARRAY[$1]` is a direct GIN-indexable containment check; `unnest(tags)` operates on a compact inline column (no TOAST decompression per row); the event payload stays a clean serialized form; matches upstream's own `tags TEXT[]` column shape.

### Priority-sort query (corrected `[H1]`)

```sql
-- Postgres equivalent of getJournalPidsWithTagOrderedByPriorityTag.
-- Containment uses @> (GIN-indexable). Pids with always-alive but no
-- priority-N tag are KEPT (default priority 0), matching Mongo $last semantics.
WITH newest AS (
    SELECT DISTINCT ON (pid) pid, tags
    FROM things_journal
    WHERE tags @> ARRAY['always-alive']::text[]
    ORDER BY pid, sn DESC
)
SELECT n.pid
FROM newest n
LEFT JOIN LATERAL (
    -- Anchor to NUMERIC priority tags only. A bare ::int on 'priority-x' / 'priority-'
    -- (both pass a LIKE 'priority-%' filter) throws "invalid input syntax for integer"
    -- and ABORTS THE WHOLE QUERY -> zero pids recovered. User tags flow unvalidated into
    -- the tags array (Connection.getTags()), so this is reachable. [H1/cast]
    SELECT max((substring(tag from '^priority-([0-9]+)$'))::int) AS prio
    FROM unnest(n.tags) AS tag
    WHERE tag ~ '^priority-[0-9]+$'
) p ON true
ORDER BY COALESCE(p.prio, 0) DESC;   -- HIGHEST priority first. Direction LOCKED [H1]:
-- matches Mongo runtime (MongoReadJournal.java:870 Sorts.descending + :884 numericOrdering
-- collation; IT extractJournalPidsInOrderOfTags asserts 10>4>3>2). The :470 javadoc
-- "ascending" is a stale doc bug; :463-464 "Descending" is correct.
```

The containment filter hits `GIN(tags)`; the `DISTINCT ON (pid) … ORDER BY pid, sn DESC` uses `PRIMARY KEY (pid, sn)`. No JSONB decompression. **Hard gate:** an `EXPLAIN` acceptance test must confirm GIN usage (the `@>` vs `= ANY` trap is silent).

**Tie / no-priority semantics `[H1]`.** Equal-priority pids have **no defined relative order** on either engine (Mongo: equal sort keys; Postgres: no tiebreak after `… DESC`). `always-alive`-without-`priority-N` pids are KEPT at default `0` via the `COALESCE` LEFT JOIN, matching Mongo `$last` + the IT `…WhenPriorityTagMissing` (`containsExactlyInAnyOrder`). The §16 parity test therefore uses **distinct** priorities for the strict-order assertion and asserts no-priority pids only as *present*; numeric multi-digit ordering matches because both the `::int` cast and Mongo's `numericOrdering(true)` collation compare numerically (`priority-10 > priority-9`).

### Write path

Tags are immutable per journal entry — each event is an append-only `INSERT`. When a connection's priority changes, the next persisted event carries the new tags; the priority-sort query's `DISTINCT ON (pid) ORDER BY sn DESC` picks the newest event's tags (Mongo `$last`).

```sql
INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at)
VALUES ($1, $2, $3, $4, $5::jsonb, now());
-- plus, in the SAME transaction (per AtomicWrite), high-water-mark upkeep:
INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2)
ON CONFLICT (pid) DO UPDATE SET highest_sn = GREATEST(things_journal_seq.highest_sn, excluded.highest_sn);
```

## 11. Cleanup & snapshots (schema + SQL)

> Folded in from the former `postgres-cleanup-approach.md` (research 2026-05-28), corrected per `[C1]`, `[H4]`, `[H5]`, `[M4]`.

### How cleanup works today (Mongo)

Snapshot-based retention: once a snapshot exists at sequence number N, events with `sn < N` and older snapshots are eligible for deletion, subject to a `history-retention-duration` (default 3d) time floor. Two mechanisms:

1. **Background cleaner (`PersistenceCleanupActor`)** — FSM on a timer: `getNewestSnapshotsAbove(lowerBoundPid)` scans snapshots in pid-sorted batches → per pid, `deleteEvents(pid, minSn, snapshotSn)` + `deleteSnapshots(...)`. Rate-limited by `Credits` (backs off when DB latency > `timer-threshold = 150ms`). Work partitioned by pid-hash (`ClusterResponsibilitySupplier`).
2. **Per-entity cleanup (`AbstractPersistentActorWithTimersAndCleanup`)** — `CleanupPersistence` command; keeps 1 stale event + latest snapshot.

`getNewestSnapshotsAbove` (Mongo): `$match` (lower-bound pid + `minAgeFromNow`) → `$sort (pid ASC, sn DESC)` → `$group` `$first` per pid → `$filter` out `DELETED` unless `includeDeleted`. Returns `(pid, sn, lifecycle)` tuples. Deleted-entity snapshots carry `"__lifecycle": "DELETED"` (`AbstractMongoSnapshotAdapter.isDeleted()`); `delete-final-deleted-snapshot` (default false) controls preservation of the final DELETED snapshot.

```hocon
cleanup {
  enabled = true
  history-retention-duration = 3d
  quiet-period = 5m
  interval = 3s
  timer-threshold = 150ms
  credits-per-batch = 3
  reads-per-query = 100
  writes-per-credit = 100
  delete-final-deleted-snapshot = false
}
```

### Snapshot schema (corrected `[H4]`)

```sql
CREATE TABLE IF NOT EXISTS things_snaps (
    pid        TEXT        NOT NULL,
    sn         BIGINT      NOT NULL,
    snapshot   JSONB       NOT NULL,
    lifecycle  TEXT,                            -- 'DELETED', 'ACTIVE', or NULL
    written_at TIMESTAMPTZ NOT NULL,            -- mapped from SnapshotMetadata.timestamp, NOT now()
    PRIMARY KEY (pid, sn, written_at)           -- mirrors Pekko (persistenceId, sequenceNr, timestamp)
);

CREATE INDEX IF NOT EXISTS things_snaps_pid_sn_desc_idx ON things_snaps (pid, sn DESC);
CREATE INDEX IF NOT EXISTS things_snaps_lifecycle_idx   ON things_snaps (lifecycle) WHERE lifecycle = 'DELETED';
```

`lifecycle` and `written_at` are top-level columns (queried directly by the cleanup stream). The `(pid, sn DESC)` index supports `DISTINCT ON` without a sort. The partial index accelerates the deletion filter without indexing every row.

**Save (idempotent upsert, `[H4]`):**

```sql
INSERT INTO things_snaps (pid, sn, snapshot, lifecycle, written_at)
VALUES ($1, $2, $3::jsonb, $4, $5)
ON CONFLICT (pid, sn, written_at) DO UPDATE
  SET snapshot = excluded.snapshot, lifecycle = excluded.lifecycle;
```

**Load (MUST honor `SnapshotSelectionCriteria` `[H9]`):**

```sql
-- Pekko always calls loadAsync(pid, criteria.limit(toSequenceNr)); a bare
-- "ORDER BY sn DESC LIMIT 1" returns the wrong snapshot for at-revision requests
-- (AbstractPersistenceActor:418-423) and breaks the recovery toSequenceNr bound.
SELECT pid, sn, snapshot, lifecycle, written_at
FROM things_snaps
WHERE pid = $1
  AND sn         <= $2          -- criteria.maxSequenceNr (guard Long.MAX_VALUE)
  AND sn         >= $3          -- criteria.minSequenceNr
  AND written_at <= $4          -- criteria.maxTimestamp (epoch-millis -> TIMESTAMPTZ)
  AND written_at >= $5          -- criteria.minTimestamp
ORDER BY sn DESC, written_at DESC
LIMIT 1;
```

The loaded `snapshot` JSONB is decoded to `JsonObject` and handed to a **Postgres-specific snapshot adapter**, not `AbstractMongoSnapshotAdapter` (which requires `instanceof BsonValue` and would throw) `[G1]`.

### High-water-mark metadata (`[C1]`)

Because cleanup physically deletes journal rows, the journal's highest sequence number must be tracked out-of-band so it never regresses:

```sql
CREATE TABLE IF NOT EXISTS things_journal_seq (
    pid        TEXT   PRIMARY KEY,
    highest_sn BIGINT NOT NULL,
    deleted_to BIGINT NOT NULL DEFAULT 0
);
```

- `asyncWriteMessages` upserts `highest_sn = GREATEST(highest_sn, <written max sn>)` in the per-AtomicWrite transaction.
- `asyncDeleteMessagesTo` sets `deleted_to` (never touches `highest_sn`) in the delete transaction.
- `asyncReadHighestSequenceNr(pid)` = `SELECT GREATEST(COALESCE(MAX(sn),0), COALESCE((SELECT highest_sn FROM things_journal_seq WHERE pid=$1),0)) FROM things_journal WHERE pid=$1`.

### Event & snapshot deletion — two distinct call paths, do not conflate `[H9]`

**(a) `DittoReadJournal.deleteEvents/deleteSnapshots(pid, minSn, maxSn)`** — the read-journal cleanup path (WU 9). Bounded PK range delete:

```sql
DELETE FROM things_journal WHERE pid = $1 AND sn >= $2 AND sn <= $3;  -- hits PK (pid, sn)
DELETE FROM things_snaps   WHERE pid = $1 AND sn >= $2 AND sn <= $3;
```

**(b) `SnapshotStore.deleteAsync` (WU 8)** — Pekko's two overloads, which the bounded range above does **NOT** cover:
- `deleteAsync(SnapshotMetadata)` → delete exactly one row: `WHERE pid=$1 AND sn=$2 AND written_at=$3` (a bare sn-range would wrongly drop every `written_at` row at that sn under the multi-snapshot PK).
- `deleteAsync(pid, SnapshotSelectionCriteria)` → upper-bounded only: `WHERE pid=$1 AND sn<=$2(maxSeq) AND written_at<=$3(maxTs)` (no lower bound; `AbstractPersistentActorWithTimersAndCleanup:198`).

B-tree range/point delete on the PK in one pass; MVCC marks rows dead (non-blocking reads) and autovacuum reclaims later. The credit system already batches (`writes-per-credit = 100`), so a single ranged `DELETE` per batch is efficient.

### `getNewestSnapshotsAbove` (Postgres)

```sql
SELECT DISTINCT ON (pid) pid, sn, lifecycle
FROM things_snaps
WHERE pid > $1                                       -- lower-bound pid (cursor pagination)
  AND written_at < now() - $2::interval              -- age filter (replaces Mongo ObjectId timestamp)
  AND ($3 OR lifecycle IS DISTINCT FROM 'DELETED')   -- includeDeleted flag
ORDER BY pid, sn DESC
LIMIT $4;                                            -- batch size (reads-per-query)
```

`DISTINCT ON (pid) ORDER BY pid, sn DESC` = Mongo's `$group + $first`. Single pass via the `(pid, sn DESC)` index.

### What stays the same — and what does NOT (`[M4]`)

Unchanged (verified backend-agnostic, operate through `DittoReadJournal`): `PersistenceCleanupActor`, `Credits`, `ClusterResponsibilitySupplier`, `AbstractPersistentActorWithTimersAndCleanup`.

**Changed:** `Cleanup.java` is **not** backend-agnostic yet — it statically imports `MongoReadJournal` **constants** (`LIFECYCLE`, `S_ID`, `S_SN`; `Cleanup.java:15-17`) and must be decoupled in **WU 1.6** (constant hoist). It does **not** import `org.bson.Document` (the earlier claim was wrong — verified zero `org.bson` references) `[M4, corrected]`.

### Schema-evolution policy (`[SE1]`, resolves §6 important #3)

Evolution has two independent axes; conflating them is a design error.

**Axis 1 — DDL (table/column/index structure): strictly additive.**
- Allowed in the normal boot path: new tables (`CREATE TABLE IF NOT EXISTS`), new columns that are `NULL`-able or carry a `DEFAULT`, new btree/GIN/partial indexes on **empty/new** tables (plain `CREATE INDEX` inside the xact-locked bootstrap).
- New indexes on **existing populated** tables are added **out-of-band, post-launch** via `CREATE INDEX CONCURRENTLY`, run by an operator/migration step **outside** `PostgresSchemaManager`'s bootstrap transaction (`CONCURRENTLY` cannot run in a txn — deliberately excluded `[M2]`).
- **Forbidden in the boot path:** in-place column TYPE changes, adding `NOT NULL` without a default, PK/constraint redefinition, column drops/renames. These are breaking DDL → a new `schema_version` generation handled by an **explicit migration WU**, never by `CREATE TABLE IF NOT EXISTS` (which silently keeps a divergent pre-existing table; the live-catalog check refuses to boot on exactly that `[H13]`).

**Axis 2 — Payload (JSONB event/snapshot content): application-layer, engine-agnostic.**
- JSONB is schema-flexible: **additive** event/snapshot fields require **no DDL** — new fields just appear in the document.
- **Breaking** event-shape changes (renamed/removed/restructured fields, removed event TYPEs) are handled exactly as on Mongo today: `EventAdapter.manifest` (the event TYPE string) drives per-event up-casting in `fromJournal` via `performFromJournalMigration(JsonObject)`. The Postgres adapters reuse the **same** pure `*EventSerializer`/`*SnapshotSerializer` and the **same** migration hooks (WU 6 split); only the BSON↔JSONB envelope differs. Live precedents that ship unchanged: `AbstractPolicyMongoEventAdapter` discards removed legacy policy event TYPEs by manifest during recovery; `ConnectivityMongoEventAdapter` runs encrypt/decrypt + `ConnectionMigrationUtil.connectionFromJsonWithMigration`.
- `JsonSchemaVersion` (currently single live value `V_2 = LATEST`) is a `toJson` **field-filter predicate** (`__schemaVersion` `JsonFieldMarker`), **not** a stored payload-version stamp — `toJournal` serializes with `FieldType.regularOrSpecial()` and persists no version field. Orthogonal to journal evolution; inherited unchanged.

**Enforcement boundary (explicit).** The `schema_version` checksum + live-catalog verification `[M1, H13]` guard **DDL drift ONLY** (code-vs-stored structure divergence → refuse-to-boot). They **do not, and must not, version or validate payload contents.** Payload/event-shape compatibility is owned entirely by the `EventAdapter`/`manifest`/`fromJournal` layer, which is engine-agnostic and already cluster-roll-forward tested on Mongo. (Caveat: snapshots have no manifest-keyed dispatch — breaking snapshot-shape changes rely on the domain type's JSON-parser tolerance + snapshot regeneration, same as Mongo today.)

### Role privileges — two-role split (`[G5]`, resolves §6 important #4)

DDL role **owns** the schema/tables (runs `CREATE` in the `[H2]` bootstrap txn); runtime role is **DML-only**. Confirmed: `pg_advisory_xact_lock` needs no privilege; `GENERATED ALWAYS AS IDENTITY` INSERT needs **no** `USAGE ON SEQUENCE` (unlike `SERIAL` — do not use SERIAL); cleanup uses `DELETE` not `TRUNCATE` (no TRUNCATE grant). Run once as superuser/db-owner at provisioning (schema `ditto`, DDL role `ditto_ddl`, runtime role `ditto_app`):

```sql
CREATE ROLE ditto_ddl LOGIN PASSWORD :'ddl_pw';
CREATE ROLE ditto_app LOGIN PASSWORD :'app_pw';
CREATE SCHEMA IF NOT EXISTS ditto AUTHORIZATION ditto_ddl;  -- DDL role owns the schema => can CREATE
GRANT USAGE, CREATE ON SCHEMA ditto TO ditto_ddl;
GRANT USAGE ON SCHEMA ditto TO ditto_app;
REVOKE CREATE ON SCHEMA ditto FROM ditto_app;               -- no DDL on the runtime path
REVOKE CREATE ON SCHEMA ditto FROM PUBLIC;
-- FUTURE objects created by ditto_ddl => runtime auto-gets DML (set BEFORE the bootstrap DDL runs):
ALTER DEFAULT PRIVILEGES FOR ROLE ditto_ddl IN SCHEMA ditto
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ditto_app;
-- Deliberately NO "GRANT ... ON SEQUENCES": GENERATED ALWAYS AS IDENTITY needs none.
--   (Only if SERIAL / DEFAULT nextval() is ever introduced:
--    ALTER DEFAULT PRIVILEGES FOR ROLE ditto_ddl IN SCHEMA ditto GRANT USAGE ON SEQUENCES TO ditto_app;)
-- For pre-existing tables (re-provisioning):
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ditto TO ditto_app;
ALTER ROLE ditto_ddl SET search_path = ditto;
ALTER ROLE ditto_app SET search_path = ditto;
```

Schema-wide grants cover all phase-2 tables (`*_journal`, `*_journal_seq`, `*_snaps` ×4 entity types + `schema_version`) — no per-table grants. (If PgBouncer terminates TLS, the cert `verify-full` checks is PgBouncer's, not PG's — provision for whichever endpoint the r2dbc URI hits.)

### VACUUM considerations (`[H5]`)

Physical deletes leave dead tuples until autovacuum runs. Ship as a **default** (not just docs): `autovacuum_vacuum_scale_factor = 0.01` for `*_journal` / `*_snaps` (vs the 0.2 default), or a fixed `autovacuum_vacuum_threshold` matching delete batch size. Add row-count/bloat alerting and consider a keep-N-snapshots hard cap as defense-in-depth against cleanup starvation (`Credits` backs off under latency; a disabled cleaner causes unbounded growth). `VACUUM` runs concurrently — space reclamation, not a correctness concern.

## 12. Finding-reference index

> Refs `C1`–`LOW` are from the first review (2026-05-29); `C2`, `H9`–`H15`, `G1`–`G5` from the second adversarial pass.

| Ref | Severity | One-line |
|---|---|---|
| C1 | CRITICAL | `asyncReadHighestSequenceNr` must not be bare `MAX(sn)`; add per-pid high-water-mark. |
| C2 | CRITICAL | `(BsonDocument)` cast in backend-agnostic `AbstractPersistenceActor:1083`/`Supervisor:319` → CCE under Postgres; new WU 1.7 blocks WU 9. |
| H1 | HIGH | Tag containment via `@>`, not `= ANY`; priority CTE keeps no-priority pids; EXPLAIN gate. |
| H2 | HIGH | `pg_advisory_xact_lock` in one txn, not session `pg_advisory_lock`. |
| H3 | HIGH | `SHOW pool_mode` not implementable; explicit `pooler-mode` flag + soft WARN. |
| H4 | HIGH | Snapshot PK `(pid,sn,written_at)` + idempotent upsert; fatal-on-dup only for journal. |
| H5 | HIGH | Unbounded growth + bloat → autovacuum defaults + alerting + keep-N cap. |
| H6 | HIGH | Write-side reuse SETTLED — build all three custom (BYTEA-only payload, delete-marker, `MAX(seq_nr)` HWM, no `EventsByTagQuery`). Per-class table in §6. |
| H7 | HIGH | "Restore from backup" is a one-way door; state RPO + time-box; catch-up delta. |
| H8 | HIGH | Init-order `context()` lookup correct; **`Props`-injection does NOT fix it** (reflective ctor → same null-field) — keep field-free lookup; probe at construction. |
| H9 | HIGH | `loadAsync` MUST honor `SnapshotSelectionCriteria`; `deleteAsync` has two Pekko overloads — don't collapse into one sn-range. |
| H10 | HIGH | WU 9 must spec all 26 `DittoReadJournal` methods; upstream snapshot = BYTEA-only (no JSONB knob); JSONB needs a custom Pekko serializer. |
| H11 | HIGH | `EventsByTagQuery` needs a monotonic offset — add `seq BIGINT IDENTITY` or documented `noOffset()` stub; `now()` can't be the offset. |
| H12 | HIGH | Pool 50/5s < Mongo baseline; a failed write is NOT backpressure (stops the actor) → ≥100/30s + load-test gate. |
| H13 | HIGH | `schema_version` checksum can't catch a divergent pre-existing PK under `CREATE … IF NOT EXISTS`; add live-catalog verification. |
| H14 | HIGH | Observability "Mongo parity via `engine=` tag" is fiction (Mongo = `_mongodb` suffix, thingsearch-only) → emit a NEW neutral family. |
| H15 | HIGH | PG-floor "14+" self-contradictory (PG 14 EOL Nov 2026) → raise to PG 16, matrix 15/16/17. |
| M1 | MED | `schema_version` checksum (not bare INT), written in the DDL txn. |
| M2 | MED | Remove `indisvalid` rebuild — dead code without `CREATE INDEX CONCURRENTLY`. |
| M3 | MED | Only `preparedStatementCacheQueries=0` needed; two-tier + WARN, not refuse-to-boot. |
| M4 | MED | `Cleanup.java` is not backend-agnostic; decouple from `org.bson.Document`. |
| M5 | MED | r2dbc-proxy has no pool hooks; gauges from `ConnectionPool.getMetrics()`; record errors. |
| M6 | MED | Metric `engine=` tag, not `_mongodb` suffix; assert tag keys + value domains. |
| M7 | MED | `migration_progress` PK = `collection` only; mirror precedent counters. |
| M8 | MED | ≥5k/sec is a `COPY`-based gate, not an a-priori floor. |
| M9 | MED | jdbc rejection: lead with reactive + tag/multi-snapshot, not BYTEA-vs-JSONB. |
| M10 | MED | Enforcer ban on `org.apache.pekko:pekko-persistence-r2dbc*`; pure SPI libs only. |
| G1 | HIGH | Postgres-specific snapshot adapter required — `AbstractMongoSnapshotAdapter:136-143` throws unless `instanceof BsonValue`; `loadAsync` must decode JSONB→`JsonObject`. |
| G2 | HIGH | Write-side reuse SETTLED — build all three custom (BYTEA payload, delete-marker not physical delete, `MAX(seq_nr)` HWM, single-row snapshot PK, no `EventsByTagQuery`). Per-class table §6. |
| G3 | MED | Pin r2dbc-postgresql/pool/spi versions in `bom/pom.xml` (none today); `fetch-size=0`; the "Reactor→Pekko bridge" is just `Source.fromPublisher` (overstated). |
| G4 | LOW | `PoolMetrics` has no acquire-latency — add it via r2dbc-proxy `before/afterCreateOnConnectionFactory` histogram. |
| G5 | MED | Operational gaps: Postgres backup/PITR/RTO, thingsearch stays Mongo, `sslmode=verify-full` needs CA provisioning (+ boot guard), `pid` `COLLATE "C"`. SSL keys + two-role GRANT script in §7/§11. |
| SE1 | MED | Schema-evolution: additive-only DDL (CONCURRENTLY index adds out-of-band); breaking DDL = explicit migration WU; payload evolution via `EventAdapter.manifest`+`fromJournal` as on Mongo; `schema_version` checksum guards DDL drift ONLY. §11. |
| LOW | LOW | `pekko-bom.version` property (verified); 26 methods (not ~25); JSONB fidelity via `JsonObject`; `Source.fromPublisher` symmetric across engines; "burns threads" unquantified. |
