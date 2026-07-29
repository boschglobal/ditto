# Ditto things-search on PostgreSQL — Architecture Research & Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Phases B–H each get a per-task detail plan at execution time (same pattern as the 2026-06-18 pluggable-persistence effort).

**Goal:** Make the things-search service run on PostgreSQL as a drop-in alternative to MongoDB, completing the Postgres story started by the pluggable-persistence refactor (`feat/postgres-persistance`), with full RQL feature parity — including wildcard (`like`/`ilike`) search and range predicates on arbitrary attribute/feature paths, which MongoDB serves via wildcard indexes (`$**`) and PostgreSQL cannot.

**Architecture:** Extract a `SearchPersistenceProvider` composite-root seam (mirroring `PersistenceBackendProvider`), keep MongoDB as the bundled default, and add a PostgreSQL implementation using a **hybrid schema**: one JSONB document row per thing (source of truth for projection, auth trees, count) plus a **flattened `(thing_id, path, wpath, typed-value)` side table** with composite B-tree and pg_trgm trigram indexes — the only stock-Postgres design that index-serves eq/range/like/sort on an unbounded, user-defined path space. RQL criteria are translated to parameterized SQL by new visitor implementations parallel to the existing BSON visitors.

**Tech Stack:** Java (services), R2DBC (`r2dbc-postgresql` + `r2dbc-pool`, reused from `internal/utils/persistence-r2dbc`), PostgreSQL ≥ 16 with `pg_trgm`, Pekko streams, Testcontainers for ITs.

## Global Constraints

- MongoDB remains the bundled default; Postgres search ships as a **separate, optional** drop-in extension JAR (`/opt/ditto/extensions/`) — decided 2026-07-04, §6 D2: layered packaging with a shared `postgres-client` base JAR (§3.1). Behavior on Mongo must be 100% preserved (byte-identical queries, same indexes).
- R2DBC/Postgres classes are banned from service modules — `enforce-no-r2dbc-in-service` enforcer + ArchUnit `PersistenceBoundaryArchTest` pattern must stay intact (thingsearch's existing ArchUnit test at `thingsearch/service/.../PersistenceBoundaryArchTest.java` must keep banning Postgres from the *service* module; the new impl module is exempt by construction).
- All artifact versions in `bom/pom.xml`; module POMs omit `<version>`.
- License headers: EPL-2.0 "Contributors to the Eclipse Foundation"; new files year 2026, existing files keep their year.
- Commits: `Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>` + `Co-Authored-By:` trailer.
- Maven: `/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn`.
- PostgreSQL floor: 16 (matches `PostgresDbResource` Testcontainers version); `pg_trgm` is a hard requirement (contrib module, **trusted** extension on PG ≥ 13 — installable by a non-superuser DDL role with CREATE on the database; verified on PG 16).
- Public-API changes (e.g. `rql/query` `PredicateVisitor`) must be binary-compatible (default methods only) — japicmp gate. **Caveat (round 2):** `thingsearch/service` itself is NOT japicmp-gated, but it hosts two *user-facing* extension points (`SearchUpdateMapper` @since 2.1.0, `SearchUpdateObserver` @since 2.3.0) whose re-typing is a **documented breaking change** (§3.4, §6 D4) — it must be declared in release notes, never silent.

---

## 1. Current state (verified in code, 2026-07-03)

### 1.1 What the branch already provides

The `feat/postgres-persistance` base delivers Postgres **event-sourcing** persistence (journal/snapshots) for things/policies/connectivity behind the `PersistenceBackendProvider` seam (`internal/utils/persistence-api/.../PersistenceBackendProvider.java`), with:

- Two-module split: `internal/utils/persistence-r2dbc` (impl) + `internal/utils/persistence-r2dbc-extension` (shaded drop-in JAR with explicit artifact whitelist).
- Single opt-in config: `include classpath("ditto-postgres-persistence")` (top-level include).
- Programmatic DDL bootstrap (`PostgresSchemaManager`, checksum-guarded, DDL-role credentials).
- Shared pool via `PostgresClientExtension` (actor-system extension, one pool per JVM).
- 3-layer switchability proof: ArchUnit + boot self-check (`PersistenceBackendSelfCheck`) + HOCON lint.
- Search-relevant: background-sync input (`SudoStreamSnapshots` → `SnapshotStreamingActor` → `PostgresReadJournal.getNewestSnapshotsAbove`) already works on Postgres end-to-end (`PostgresStreamingIT`).

Search was **explicitly out of scope** ("search n/a (stays Mongo)" — `ditto-postgres-persistence.conf:319`; spec line 28-29). This plan is the net-new follow-up.

### 1.2 things-search today (100% MongoDB)

- **Collections:** `search` (index) + `searchSync` (background-sync bookmark) — `thingsearch/service/.../persistence/PersistenceConstants.java`.
- **Document shape** (built by `EnforcedThingMapper.toBsonDocument`, `.../write/mapping/EnforcedThingMapper.java:118-141`):
  `{_id, _namespace, gr:[subjects], _revision, policyId, __policyRev, __referencedPolicies:[{type,id,revision}], t:{enforced thing}, p:{auth tree}, f:[{id, …, p:{auth tree}}]}` — `__referencedPolicies` holds the FULL policy tags (`{"type":"policy","id":…,"revision":N}`), NOT a bare `[{id}]` (C2 review adjudication — the earlier `[{id}]` sketch was wrong); auth leaves are `·g`/`·r` (granted/revoked subject arrays, char-183 prefix) computed per JSON pointer by `EvaluatedPolicy`.
- **Indexes** (`.../persistence/Indices.java`): two **wildcard indexes** `v_wildcard` (`$**`) and `v_wildcard_id` (`$**`,`_id`) with projections excluding auth/bookkeeping fields, plus `gr+_id`, `policyId+__policyRev`, `__referencedPolicies`, `_namespace+_id`, and a TTL index on `deleteAt`. **DocumentDB-compat mode already runs with the wildcard indexes omitted** (`Indices.all`, lines 119-125) — proof that *correctness* never depends on the wildcard index, only performance.
- **Query translation:** RQL criteria → BSON via `CreateBsonVisitor`/`CreateBsonPredicateVisitor` (`like`→`Filters.regex`), field paths via `GetFilterBsonVisitor` (`t.attributes.…`, `t.features.<id>.properties.…`; wildcard feature `features/*/…` → `elemMatch` on `f`), auth filter AND-ed per field by `AbstractFieldBsonCreator.getAuthFilter` (revoke-wins, grant-inherits-down, built **statically** over the ancestor chain of the queried path), `gr` filter AND-ed globally. **Auth exceptions (round-2 verified):** root-level fields get NO per-field auth (`GetFilterBsonVisitor.visitRootLevelField:118-127`); `exists(features/*)` is a bare `Filters.exists("f.id")` with NO auth (`GetExistsBsonVisitor.visitFeature:79-86`); sort keys are never auth-filtered (`GetSortBsonVisitor` — no auth logic). Sort via `GetSortBsonVisitor`; cursor paging via `ThingsSearchCursor` (backend-neutral deflate+base64 JSON of filter/namespaces/sort-values; resume criteria generated as backend-neutral `Criteria` via `getNextDimensionCriteria`). Aggregation metrics via `MongoThingsAggregationPersistence` (`$match`+`$group`).
- **Write path:** `SearchUpdaterStream` → `EnforcementFlow` (thing+policy retrieve, `SearchUpdateMapper`) → `MongoSearchUpdaterFlow` (`bulkWrite`, unordered — note: today each "bulk" is exactly ONE write model, `MongoSearchUpdaterFlow.create():92-96`) → `BulkWriteResultAckFlow`; per-thing `ThingUpdater` shard actors with revision-guarded optimistic writes and BSON-diff incremental patches (`BsonDiff`). **Write-guard semantics (round-2 corrected):** a full replace (`ThingWriteModel.java:150-176`) is guarded by `_id` ONLY — the revision-*equality* filter applies only to patch updates (`isPatchUpdate`); ordering safety comes from per-thing `ThingUpdater` serialization + the in-actor `isNextWriteModelOutDated` check, NOT from a DB-side monotonic guard. Policy fan-out via `MongoThingsSearchUpdaterPersistence.getPolicyReferenceTags` (projects `_id`, `policyId`, `__referencedPolicies`; emits one `PolicyReferenceTag` per referenced policy present in the incoming revision map — `:110-155`) produces writes with the SAME thing revision but newer policy data. Namespace purge = `deleteAt` marker (epoch 0, `BsonDateTime(0)` — `:161-166`) + TTL; `findAll`/`count` do NOT filter `deleteAt`-marked docs (only `sudoStreamMetadata` does).
- **Seams that exist:** `ThingsSearchPersistence` (read), `ThingsSearchUpdaterPersistence` (write/ops), `ThingsAggregationPersistence` — one Mongo impl each, but **constructed concretely** in `SearchRootActor`/`SearchUpdaterRootActor` (aggregation persistence in `OperatorAggregateMetricsProviderActor:118` — provider wiring must reach there too); `MongoClientExtension` unconditionally created (including in the `SearchUpdateMapper` base constructor — `SearchUpdateMapper.java:52-55`, `getMaxWireVersion()`); `AbstractWriteModel.toMongo()` leaks `WriteModel<BsonDocument>`; `WriteResultAndErrors` wraps `com.mongodb.bulk.*` types; `recoverLastWriteModel(ThingId)` exists only on the CONCRETE `MongoThingsSearchPersistence:295` (not the interface) and is wired as a method reference into `ThingUpdater.props` (`SearchUpdaterRootActor.java:102`); `SearchActor.logSlowQueryIfNeeded:655-684` renders the Mongo BSON filter directly in the service module. The `Query`/`QueryBuilderFactory` *interfaces* are backend-neutral (`rql/query`) — the Mongo coupling is a runtime downcast to `MongoQuery` in `MongoThingsSearchPersistence:429` plus the concrete `MongoQueryBuilderFactory`. `ThingsSearchCursor`'s *container/encoding* is backend-neutral, but the class imports Mongo's `JsonToBson` for resume-criterion values (see §3.5 cursor row). **There is no provider seam for search yet.**
- **Scale of the port:** 38 of 123 classes in `thingsearch/service` import `org.bson`/`com.mongodb` (~10 read/query, ~13 write/mapping, ~9 streaming/actors, + shared `internal/utils/persistence` Mongo helpers).

---

## 2. The wildcard-index problem and its Postgres answer

### 2.1 Why this is the hard problem

Ditto RQL allows **eq/ne/gt/ge/lt/le/in/exists, empty (since 3.9.0 — absent OR null OR `[]` OR `{}` OR `""`, `GetEmptyBsonVisitor`), like/ilike (`*`,`?` wildcards), and/or/not, sort, cursor paging** on **arbitrary user-defined JSON paths** (attributes/features are an unbounded key space). MongoDB wildcard indexes store B-tree-ordered `(path, value)` entries for every leaf — so eq, range, per-path ordering and (anchored) regex all get index support without declaring paths. (Verified restriction worth exploiting: a Mongo wildcard index serves only **one** predicate field per query — a two-predicate AND is index-served on one leg only, whereas the flat table below serves both legs. This is a capability *win* for the Postgres design; the Phase-0 EXISTS-vs-semi-join benchmark should measure it.)

PostgreSQL has **no equivalent single index**:

| Postgres option | eq/in/exists (any path) | gt/lt (any path) | like/ilike (any path) | sort (any path) | Notes |
|---|---|---|---|---|---|
| **A. JSONB + GIN (`jsonb_ops`/`jsonb_path_ops`) + jsonpath** | ✅ indexed (`@>`, `@?`) | ❌ never indexed (GIN jsonpath extraction is equality-only) | ❌ never indexed | ❌ (GIN is unordered/bitmap) | The "obvious" analog is strictly weaker than Mongo `$**`; also whole-doc GIN write amplification |
| **B. Expression B-tree / STORED generated columns per path** | ✅ | ✅ | ✅ (`gin_trgm_ops` expr) | ✅ | **Requires knowing paths in advance** — fails the unbounded-key-space requirement; right tool only for fixed system fields |
| **C. Flattened key/value side table (EAV)** `(thing_id, path, val_text/val_num/val_bool)` + composite B-trees + trigram GIN on `val_text` | ✅ (`path=? AND val=?`) | ✅ (`(path,val_num)` range scan) | ✅ (one `gin_trgm_ops` index; ≥3-char-literal caveat) | ✅ (LATERAL join, keyset paging) | Structurally what a Mongo wildcard index is internally (ordered (path,value) entries), as first-class rows |
| **D. Hybrid: JSONB doc row (truth) + C for predicates/sort + B for system fields** | ✅ | ✅ | ✅ | ✅ | **Chosen** |
| **E. pg_documentdb (Microsoft DocumentDB extension, powers FerretDB v2)** | ✅ | ✅ | partial | partial | True wildcard-index clone (RUM-derived BSON index AM), MIT/Linux Foundation — but custom index AM, BSON-typed function API hostile to R2DBC, not available on managed Postgres, young (GA 2025). Fallback/benchmark yardstick only |
| jsquery (Postgres Pro ext) | ✅ | ✅ (`jsonb_path_value_ops`) | ❌ | ❌ | Non-core, own query language, no like — rejected |
| ParadeDB pg_search / FTS / RUM | token search only | fast-fields | tokenized ≠ exact `like` | partial | Wrong semantics for exact-pattern RQL `like`; rejected as engine (possible future opt-in fuzzy search) |

Key citations: PostgreSQL docs (GIN builtin opclasses; jsonpath extraction equality-only), Crunchy Data "Indexing JSONB in Postgres", pganalyze "GIN — the good and the bad", pgsql-hackers thread confirming `==` uses GIN but `>=` does not, Azure DocumentDB indexing docs. Full sourced research is in the review record for this plan.

### 2.2 "But Ditto 2.x already tried flattening and abandoned it"

True — and it doesn't transfer. Ditto 2.x flattened attributes into arrays *inside the same Mongo document*, duplicating authorization subjects per flattened entry; every update rewrote the giant document and its multikey entries. The 3.0 release notes cite exactly this (index docs shrank to 10% after switching to `$**`). On Postgres the equivalents are structurally different:

- Flat entries are **independent rows** — an attribute change touches only its rows, not a mega-document.
- Authorization is **not duplicated per entry**: the auth trees stay once per thing (JSONB columns on the doc row), and the global-read set is one `text[]`; flat rows carry no auth data at all (see §3.3).
- Doc row + flat rows are updated in **one transaction** — no dual-write consistency gap (Mongo has multi-document transactions since 4.0, but Ditto's search write path doesn't use them; on Postgres transactional consistency is the free default).

### 2.3 Decision (D1): Strategy D — hybrid JSONB + flattened side table

- **eq/ne/in/exists/empty/gt/ge/lt/le** on arbitrary paths → composite B-trees on the flat table `(wpath, val_num)` / `(wpath, val_text)`.
- **like/ilike** on arbitrary paths → single trigram GIN index on `flat.val_text` + `LIKE`/`ILIKE` rewrite of the RQL wildcard pattern (`*`→`%`, `?`→`_`). Documented caveat mirroring Mongo's own regex-anchoring caveat: patterns without a ≥3-char literal degrade to scan-recheck.
- **sort on arbitrary paths** → LATERAL join per sort key on the flat table; keyset (cursor) paging via translated resume criteria (§3.5 cursor row — NOT SQL row-value tuples).
- **auth checks** → evaluated on the doc row's JSONB auth columns as *rechecks* (they need no index: selectivity comes from value predicates + `gr`), reproducing the exact Mongo boolean structure since queried paths are static at translation time — including Mongo's *exceptions* where no per-field auth applies (§3.3).
- **fixed system fields** (`_id`, `_namespace`) → plain columns + B-trees; all other "simple" fields are slash-mapped and served from the flat table (§3.5 — round-2 correction).
- **No document-level GIN index in v1** (avoids whole-doc GIN churn); revisit only if Phase-0 benchmarks show containment-heavy workloads that the flat table serves poorly.
- **Fallback documented:** pg_documentdb if the hybrid fails perf gates at scale (accepting its operational costs); jsquery rejected; pure-JSONB rejected (fails hard requirements).

---

## 3. Target architecture

### 3.1 Module & seam design (D2)

New SPI module extracted from `thingsearch/service`, mirroring `internal/utils/persistence-api`:

```
thingsearch/persistence-api          (ditto-thingsearch-persistence-api)     ← NEW, backend-neutral
thingsearch/service                  (unchanged artifactId; Mongo impls stay here, now behind the SPI)
internal/utils/postgres-client       (ditto-internal-utils-postgres-client)  ← NEW, shared PG infra EXTRACTED from persistence-r2dbc
internal/utils/persistence-r2dbc     (event-sourcing backend; now depends on postgres-client)
internal/utils/search-r2dbc          (ditto-internal-utils-search-r2dbc)     ← NEW, Postgres search impl
+ extension packaging: THREE layered drop-in JARs (§6 D2; packaging bullet below)
```

`postgres-client` contents (extraction, behavior-preserving): `PostgresClientExtension`, `PostgresConfig`/`DefaultPostgresConfig`, `ConnectionPoolFactory` (becomes public module API — resolves the round-2 "package-private" finding structurally), the DDL-credentials connection-factory helper, `PostgresSchemaManager` **parameterized over a schema descriptor** (statements/contracts/component/version/checksum — likewise mandated by round 2), the advisory-lock key + shared `schema_version` DDL (§3.2 coordination), `PostgresHealthChecker`, metrics/Kamon glue, and the shared `ditto-postgres-client.conf` (§3.6). With this split, `search-r2dbc` needs **no dependency on `persistence-r2dbc` at all** — no event-sourcing coupling.

(Note: an unrelated sibling module `internal/utils/search` = `ditto-internal-utils-search` (SubscriptionManager) already exists — the `search-r2dbc` name is distinct; don't confuse them.)

- `thingsearch/persistence-api` contains: the three persistence interfaces (`ThingsSearchPersistence`, `ThingsSearchUpdaterPersistence`, `ThingsAggregationPersistence` — moved **and re-typed for neutrality**, see below), the neutral write model + write-result types (§3.4), the neutral index-document model, `TimestampPersistence` contract for the sync bookmark, the `SearchUpdaterFlow` seam interface, and the new extension point:

```java
public interface SearchPersistenceProvider extends DittoExtensionPoint {
    // config key: ditto.extensions.search-persistence-provider
    //
    // Constructed with (ActorSystem, Config) per the DittoExtensionPoint pattern (exactly like
    // PersistenceBackendProvider); implementations read their own config block from the
    // actor-system config (Postgres: ditto.postgresql). Factory methods take NO service config:
    // SearchConfig lives in thingsearch/service and is Mongo-coupled (extends WithMongoDbConfig,
    // WithIndexInitializationConfig; exposes mongo.indices.Index) — passing it would create a
    // module cycle AND Mongo-taint persistence-api (round-2 Critical). The Mongo provider impl
    // (in thingsearch/service) rebuilds SearchConfig from the actor system internally.
    void bootstrapSchema();
        // SYNCHRONOUS fail-fast, called in SearchRootActor's constructor BEFORE any persistence
        // is created — exact mirror of PersistenceBackendProvider.bootstrapSchema() and the
        // ThingsRootActor:118 call order. NOT CompletionStage: async invites starting the updater
        // stream before DDL completes. Mongo impl: performs today's index initialization here
        // (initializeIndices is REMOVED from the neutral read interface — it took the
        // Mongo-package type IndexInitializationConfig).
    ThingsSearchPersistence createSearchPersistence();
    ThingsSearchUpdaterPersistence createUpdaterPersistence();
    ThingsAggregationPersistence createAggregationPersistence();
    SearchUpdaterFlow createUpdaterFlow();                 // bulk-writer seam, neutral types — §3.4
    TimestampPersistence createBackgroundSyncBookmarkPersistence();
    Props healthCheckProps();
    QueryBuilderFactory queryBuilderFactory(LimitsConfig limitsConfig);  // LimitsConfig is base/service — acyclic
    String renderForDiagnostics(Query query, @Nullable List<String> authSubjects);  // null = sudo (no-auth) form
        // SearchActor.logSlowQueryIfNeeded currently renders the BSON filter inline in the
        // service module (SearchActor:655-684) — that call site moves behind this hook so the
        // service stays backend-clean and Postgres operators see the rendered SQL.
    static SearchPersistenceProvider get(ActorSystem system, Config config) { … }  // DittoExtensionPoint pattern
}
```

- **Interface re-typing required for neutrality (round 2):** the three interfaces are NOT movable verbatim:
  - `ThingsSearchPersistence.initializeIndices(IndexInitializationConfig)` takes a `…persistence.mongo.config` type → removed from the interface; Mongo index init becomes provider-internal (inside `bootstrapSchema()`).
  - `ThingsSearchPersistence` gains `recoverLastWriteModel(ThingId)` (today only on the concrete `MongoThingsSearchPersistence:295` yet required by `ThingUpdater.props` — the provider seam must supply it; Postgres reconstructs a neutral write model from the doc row).
  - `ThingsAggregationPersistence.aggregateThings` re-typed from `Source<org.bson.Document,…>` to `Source<JsonObject,…>` (the sole consumer already converts via `JsonFactory.newObject(doc.toJson())` — `AggregateThingsMetricsActor:97`).
- `internal/utils/search-r2dbc` depends on `thingsearch/persistence-api` + `internal/utils/postgres-client` ONLY. It must NOT depend on `thingsearch/service` and does NOT depend on `internal/utils/persistence-r2dbc` (the previously assumed reuse targets all move into `postgres-client`).
- **Why not inside `persistence-r2dbc`:** dependency direction — the event-sourcing backend must stay free of thingsearch types; the search impl is large enough to warrant its own reviewable module; and the layered packaging (below) requires the split anyway.
- **Packaging (§6 D2, decided 2026-07-04): THREE layered extension JARs — search is a separate, OPTIONAL drop-in.**
  - `ditto-postgres-client-extension` (base): shades `postgres-client` + ALL third-party deps (r2dbc-postgresql, r2dbc-pool, netty incl. the resolver-dns whitelist entries). The third-party shade whitelist MOVES here from today's `persistence-r2dbc-extension`. **Merge coordination:** the netty-resolver-dns whitelist fix exists only uncommitted in the sibling `ditto_feat__postgres-persistance` worktree — coordinate before reworking this block.
  - `ditto-postgres-persistence-extension` (thin): only the `persistence-r2dbc` classes.
  - `ditto-postgres-search-extension` (thin): only the `search-r2dbc` classes.
  - Deployment matrix: things/policies/connectivity mount **base + persistence** (2 JARs — a documented change from today's single JAR); search mounts **base + search**. Users who keep search on Mongo mount nothing on the search service. All three JARs must come from the SAME Ditto release — the boot self-check verifies a version marker across the loaded extension artifacts (Phase F).
  - `ditto-thingsearch-persistence-api` is **NOT shaded** — precedent: `persistence-api` is deliberately absent from the existing `<artifactSet><includes>` whitelist (`persistence-r2dbc-extension/pom.xml:78-92`, rationale comment at 68-77) and relied upon from the service classpath. Mirror that exactly; Phase F needs classloading ITs for BOTH deployment shapes (persistence pair on a things-like classpath, search pair on a search-like classpath).
- **Enforcement symmetry:** `thingsearch/service` gets the `enforce-no-r2dbc-in-service` enforcer block (per-service pom pattern, cf. `things/service/pom.xml:319-344`); its `PersistenceBoundaryArchTest` keeps Rule 2 (no Postgres); `thingsearch/persistence-api` gets a neutrality ArchUnit test (no Mongo, no Postgres) like `PersistenceApiNeutralityArchTest`; `search-r2dbc` gets a no-Mongo test. Boot self-check: extend the pattern — provider self-describes expected impl classes, verified at `SearchRootActor` start.

### 3.2 PostgreSQL schema (D3)

Single-source-of-truth programmatic DDL in `search-r2dbc` (new `PostgresSearchSchema`, same pattern/versioning/checksum as `PostgresSchema`, own `schema_version` component row `ditto-postgres-search`, VERSION=1). **Shared-database coordination (round 2):** two schema managers (persistence + search) may bootstrap against the same database concurrently — they MUST use the **same advisory-lock key** and a **single shared definition** of the `schema_version` table DDL + contract, or fresh-DB concurrent boots hit the pg_type duplicate-key race and cross-component contract verification trips; each component verifies only its own tables. (Structurally solved by the `postgres-client` extraction — lock key, `schema_version` DDL and the parameterized schema manager all live there, §3.1.)

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;               -- DDL role; trusted extension (PG ≥13) — CREATE on the DB suffices; fail with clear error if not permitted

CREATE TABLE search_things (
    thing_id       text COLLATE "C" PRIMARY KEY,       -- Mongo _id
    namespace      text COLLATE "C" NOT NULL,          -- _namespace
    revision       bigint NOT NULL,                    -- _revision
    policy_id      text,                               -- policyId
    policy_rev     bigint,                             -- __policyRev
    referenced_policies jsonb,                         -- __referencedPolicies: FULL policy tags
                                                       --  [{"type":"policy","id":"...","revision":N}] (C2
                                                       --  adjudication; NOT a bare [{"id":"..."}]). The fan-out
                                                       --  containment probe wraps a PARTIAL object [{"id":"..."}]
                                                       --  for the @> GIN test (§3.4).
    global_read    text[] NOT NULL DEFAULT '{}',       -- gr
    thing          jsonb NOT NULL,                     -- t (enforced thing payload)
    policy_auth    jsonb,                              -- p (grant/revoke tree, ·g/·r leaves)
    features_auth  jsonb,                              -- map feature-id -> per-feature auth tree (f[].p)
    t_modified     timestamptz,                        -- parsed from thing._modified by the WRITER
                                                       -- (NOT a generated column: text→timestamptz cast is
                                                       --  STABLE, not IMMUTABLE — GENERATED ALWAYS AS fails
                                                       --  with "generation expression is not immutable";
                                                       --  verified on PG 16).
                                                       -- Serves the WRITER/streaming paths ONLY — NEVER RQL
                                                       -- translation: RQL /_modified is a slash-mapped field
                                                       -- read from inside t with per-field auth and STRING
                                                       -- comparison semantics (§3.5 simple fields).
    delete_at      timestamptz,                        -- deleteAt marker (reaper-driven, §3.6)
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX st_namespace          ON search_things (namespace, thing_id);
CREATE INDEX st_global_read       ON search_things USING gin (global_read);      -- gr && :subjects
CREATE INDEX st_policy            ON search_things (policy_id, policy_rev);
CREATE INDEX st_referenced_pols   ON search_things USING gin (referenced_policies jsonb_path_ops);
CREATE INDEX st_delete_at         ON search_things (delete_at) WHERE delete_at IS NOT NULL;
CREATE INDEX st_modified          ON search_things (t_modified);

CREATE TABLE search_flat (
    thing_id   text COLLATE "C" NOT NULL REFERENCES search_things (thing_id) ON DELETE CASCADE,
    path       text COLLATE "C" NOT NULL,   -- '/attributes/temp', '/features/env/properties/temp'
    wpath      text COLLATE "C" NOT NULL,   -- feature rows additionally get '/features/*/properties/temp'; else = path
    f_id       text,                        -- feature id for feature-subtree rows, else NULL
    ord        integer NOT NULL DEFAULT 0,  -- ROW-ENUMERATION COUNTER per (path, wpath) within a thing
                                            -- (round-2 redefinition). NOT an array index: multi-level
                                            -- array explosion means one leaf path can carry entries from
                                            -- several array dimensions (e.g. {a:[{x:[1,2]},{x:[3]}]}
                                            -- yields 3 leaves at path '/attributes/a/x' — an array index
                                            -- collides in the PK). Its only jobs are PK uniqueness and
                                            -- multikey multiplicity; Mongo guarantees no element order in
                                            -- matching anyway. integer (not smallint): a 100 KiB thing can
                                            -- exceed 32k tiny array elements.
    type_rank  smallint NOT NULL,           -- cross-type sort emulation (§3.5): 1 null,2 number,3 string,4 object,5 array,6 boolean
    val_bool   boolean,
    val_num    numeric,
    val_text   text COLLATE "C",            -- string form for strings; NULL for other types.
                                            -- COLLATE "C" is REQUIRED for Mongo parity: BSON compares
                                            -- strings as UTF-8 code units; the default DB collation
                                            -- orders 'a' < 'B' (verified) while "C"/Mongo order 'B' < 'a'.
                                            -- Affects every string gt/ge/lt/le and every string sort.
    PRIMARY KEY (thing_id, path, wpath, ord)
    -- NOT (thing_id, wpath, ord): two features sharing a property path both emit the SAME wildcard
    -- row ('/features/*/properties/temp', ord=0) — proven duplicate-key violation on PG 16. `path`
    -- disambiguates features; `wpath` disambiguates the exact-vs-wildcard row pair per leaf; f_id
    -- can't be a PK column (nullable).
);
CREATE INDEX sf_num   ON search_flat (wpath, val_num)  WHERE val_num  IS NOT NULL;
CREATE INDEX sf_text  ON search_flat (wpath, val_text) WHERE val_text IS NOT NULL;
CREATE INDEX sf_bool  ON search_flat (wpath, val_bool) WHERE val_bool IS NOT NULL;
CREATE INDEX sf_exists ON search_flat (wpath);                                    -- exists(): wpath = P OR prefix scan
CREATE INDEX sf_trgm  ON search_flat USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops) WHERE val_text IS NOT NULL;
-- sf_trgm collation override (round-2 fix): ILIKE case-folds via the COLUMN collation's ctype — under
-- COLLATE "C", 'Ärger' ILIKE 'ärger' is FALSE (ASCII-only folding) while Mongo's $options:"i" matches.
-- Building the trigram index on (val_text COLLATE "C.utf8") and applying the same override in the
-- ilike translation restores non-ASCII folding AND stays index-served (verified: Bitmap Index Scan,
-- count=1 for '%ärger%' on PG 16). eq/range/sort keep the plain COLLATE "C" column + sf_text.
-- Phase-0 slot: consider INCLUDE (thing_id) on sf_num/sf_text/sf_bool for index-only semi-join builds.
-- Phase-0 OUTCOME (Task 0.2b, E3 — REJECTED): `sf_num_covering`/`sf_text_covering` (INCLUDE (thing_id))
-- were built and measured; they do NOT materially help the auth-heavy shapes (1/5) whose cost lives in
-- the per-candidate `search_things` doc-row probes (~69k of 73.5k buffers), not the flat-index lookup —
-- a committed negative result (bench-results doc, E3 section). NOT part of the schema; dropped before
-- Task 0.3's write-path pricing so the design isn't billed for an index the read gate itself rejected.
-- Phase-0 OUTCOME (Task 0.2b, E1/E2 one-liner): bumping `wpath`'s (and even val_text/val_num's)
-- per-column statistics target helps LITTLE on its own (pooled cross-column misestimate stays ~34x wrong
-- regardless of target — a negative result, not a fix) — see bench-results doc §"misestimate persistence".
-- Laptop-class memory settings (shared_buffers=4GB/effective_cache_size=12GB/work_mem=64MB) are ASSUMED
-- as the baseline hardware class for every Phase-0 number; see the bench-results doc for the full stack.
```

```sql
CREATE TABLE search_sync (            -- searchSync bookmark (MongoTimestampPersistence replacement)
    id         text PRIMARY KEY,      -- 'backgroundSync'
    ts         timestamptz NOT NULL,
    tag        text
);
```

Flattening rules (from `t` only — auth/bookkeeping fields are never flattened, mirroring the Mongo wildcard projection exclusions):
- Every scalar leaf of `t` → one row; `path` = JSON pointer with `/`-separators; rows under `/features/<id>/…` get `f_id=<id>` and a second addressable form `wpath='/features/*/…'` **in addition to** a row with `wpath=path` (two rows per feature leaf) so both exact-feature and wildcard-feature predicates hit a B-tree exactly. (Alternative — one row + `LIKE '/features/%/…'` — rejected: mid-string wildcard defeats the index.) **Clarification (adjudicated during Task 0.1, confirmed by the reference `Flattener`/`FlattenerTest`):** "two rows per feature leaf" applies to EVERY row anywhere under a feature subtree, not scalar leaves only — object exists-rows (`type_rank=4`, incl. empty objects) and array stub-rows (`type_rank=5`, incl. empty arrays) under `/features/<id>/…` also get the dual exact+wildcard `wpath` emission. This is required, not cosmetic: a wildcard `exists(features/*/desiredProperties)` ("some feature has this — possibly empty — object") needs a `/features/*/…` row to exist even when `desiredProperties` is `{}`, exactly like the scalar-leaf case.
- **Arrays exploded RECURSIVELY through object/array nesting** (round-2 fix): Mongo *query* semantics match into arrays nested under array-of-objects (`{"a.x":1}` matches `{a:[{x:[1,2]}]}` — verified on mongo:7); index limitations never change Mongo results (the plan's own DocumentDB-mode argument), so a one-level stub would change *Postgres results*, not just performance. Multikey multiplicity is carried by `ord` as an enumeration counter (see column comment). The ONLY stub: direct array-of-array *inner content* (`a:[[1,2]]`) is not element-matched by Mongo itself (`{a:1}` matches `a:[1]` but not `a:[[1]]`) — store the inner array as a `type_rank=5` row without value; parity-test this case.
- Objects contribute an `exists`-able row (`type_rank=4`, no value) so `exists(attributes/complex)` and prefix-existence work without `LIKE`; empty objects/arrays included (also needed by `empty()` — §3.5). **Empty-array sort exception (round 2, verified on mongo:7):** in Mongo *sorts*, an empty array ties with null/missing (while `eq(path,null)` does NOT match `[]`) — the sort lateral must map empty-array rows to the null rank (CASE or a separate sort-rank expression); non-empty arrays never sort "as rank 5" either (multikey min/max element, §3.5), so rank 5 appears in sort keys only via this exception.
- Value length cap: reuse `IndexLengthRestrictionEnforcer` **on the document itself, exactly as Mongo does** — on Mongo the enforcer truncates the value stored in `t` (950-byte budget minus id/namespace/pointer overhead, `EnforcedThingMapper.java:121-126`), so the truncated string is also what search *returns*. Postgres must store the same enforced (truncated) thing in `thing` and flatten from it — do NOT store the untruncated payload, or result payloads diverge between backends. Flattening from the enforced document makes predicate behavior identical for free. (Side benefit verified: the 950-byte bound keeps worst-case PK/index tuples far under the B-tree ~2704-byte limit.)
- **Key names are stored RAW** (round 2): Mongo applies `KeyNameReviser` (`.`→U+FF0E, `$`→U+FF04) to keys in `t`, auth trees, and query paths. The neutral `SearchIndexDocument` carries **raw** keys; the Mongo encoder applies the reviser; Postgres stores raw and must NOT apply it in the translator — otherwise flat paths/auth-tree keys mismatch, or fullwidth characters leak into returned `thing` payloads.

### 3.3 Authorization model (D4) — exact Mongo-semantics port

- **Global read:** `st.global_read && :subjects` AND-ed onto every non-sudo query (replaces `Filters.in("gr", …)`), GIN-served. (`sudoApply` omits it — same split as `CreateBsonVisitor.apply:64-80`.)
- **Per-field grant/revoke:** the translator reproduces `AbstractFieldBsonCreator.getAuthFilter`'s nested structure over the *statically known* ancestor chain of each queried path, evaluated against `policy_auth` (thing-level) or `features_auth -> f_id` (wildcard-feature level). For path `/attributes/temp`, subjects `:s` (text[]):

```sql
-- helper shape; ·g/·r are the literal char-183-prefixed keys.
-- ALL jsonb path arrays are BIND PARAMETERS ($n::text[]) — user keys may contain , { } " ;
-- inline '{…}' literals are shown for readability only. The ?| / ? operators are safe with
-- r2dbc-postgresql's $n placeholders (no JDBC-style '?' clash — no operator escaping needed).
NOT COALESCE( (policy_auth #> '{attributes,temp,·r}') ?| :s, false)
AND ( COALESCE( (policy_auth #> '{attributes,temp,·g}') ?| :s, false)
      OR ( NOT COALESCE( (policy_auth #> '{attributes,·r}') ?| :s, false)
           AND ( COALESCE( (policy_auth #> '{attributes,·g}') ?| :s, false)
                 OR ( NOT COALESCE( (policy_auth #> '{·r}') ?| :s, false)
                      AND COALESCE( (policy_auth #> '{·g}') ?| :s, false) ) ) ) )
```

  (Exact nesting to be transcribed 1:1 from `getAuthFilter` during implementation, revoke-wins/grant-inherits-down; `?|` needs jsonb `#>` NULL-coalescing as shown — `?|` on a jsonb ARRAY of subject strings checks element membership, verified on PG 16 incl. the U+00B7 keys.) These run as **rechecks on candidate rows** — no index needed; selectivity comes from flat-table probes + `global_read`.
- **Auth EXCEPTIONS — where Mongo applies NO per-field auth (round 2; blanket auth would be *stricter* than Mongo = missing results):**
  1. Root-level mapped fields (`_id`/`thingId`, `_namespace`/`namespace`) — `GetFilterBsonVisitor.visitRootLevelField:118-127`, no auth filter.
  2. `exists(features/*)` ("any feature exists") — bare `Filters.exists("f.id")`, no auth (`GetExistsBsonVisitor.visitFeature:79-86`).
  3. Sort keys — `GetSortBsonVisitor` applies no auth at all (and always projects `t._modified`).
  All other cases, including `exists()` on concrete paths, DO carry the per-field auth filter (`GetExistsBsonVisitor.matchKey:139-146`).
- **Wildcard-feature coupling:** Mongo's `elemMatch` couples value+auth to the *same* feature. Postgres equivalent: the flat row carries `f_id`, and the auth recheck references it inside the EXISTS. **The auth chain for wildcard-feature paths has the fixed levels `[<root>, /features, /<id>]` BEFORE the property path** (`AbstractFieldBsonCreator.getFeatureWildcardAuthorizationBson:73-88`) — transcribe the full chain, not just the property-path suffix:

```sql
EXISTS (SELECT 1 FROM search_flat sf
        WHERE sf.thing_id = st.thing_id
          AND sf.wpath = '/features/*/properties/temp'
          AND sf.val_num > $1
          AND <full auth chain over jsonb_extract_path(st.features_auth, sf.f_id, …):
               root → features → <id> → properties → temp>)
-- jsonb_extract_path with the DYNAMIC sf.f_id column as a path argument is expressible and
-- correct as a per-row recheck (verified on PG 16).
```

- **No auth duplication in flat rows** — the 2.x-killer explicitly avoided.

### 3.4 Neutral write model & write path (D5)

- `EnforcedThingMapper`/`EvaluatedPolicy` refactored to emit a backend-neutral `SearchIndexDocument` (JsonObject `t` with RAW keys — §3.2, auth trees `p`/per-feature, `gr` set, ids/revisions) in `persistence-api`; the Mongo impl converts it to today's BsonDocument (byte-identical output incl. `KeyNameReviser` application — golden-file tested), the Postgres impl derives `(doc row, flat rows)`.
- Neutral write-model hierarchy replaces the Mongo-typed one at actor level: `ThingWriteModel` keeps `Metadata` + `SearchIndexDocument`; `toMongo()`/`MongoWriteModel`/`BsonDiff` move behind the Mongo `SearchUpdaterFlow` implementation (`ThingUpdater.recoverLastWriteModel` and the patch-vs-replace decision become backend-internal). **The seam is wider than one flow type (round 2)** — the neutral surface in `persistence-api` comprises:
  - `SearchUpdaterFlow` — `Flow<UpdaterData, UpdaterResult, NotUsed> create()` (mirrors package-private `MongoSearchUpdaterFlow`'s `Flow<MongoWriteModel, ThingUpdater.Result,…>`).
  - Neutral `UpdaterData`/`UpdaterResult` replacing `ThingUpdater.Data`/`Result` (today: records wrapping `MongoWriteModel` + `WriteResultAndErrors`).
  - A neutral **write result** replacing `WriteResultAndErrors` (which wraps `com.mongodb.bulk.BulkWriteResult/BulkWriteError/MongoBulkWriteException`), carrying acknowledged/matched/upserted counts + per-thing errors, and a status classifier preserving `BulkWriteResultAckFlow`'s business rules: **duplicate-key error counts as SUCCESS** (`:129-131`), per-index error→metadata mapping, `sendAck/sendNAck/sendWeakAck` routing, `INCORRECT_PATCH` vs `WRITE_ERROR` vs `CONSISTENCY_ERROR` classification. Both backends produce this type; the Postgres flow must emulate the unique-violation⇒success rule.
  - `recoverLastWriteModel(ThingId)` on the neutral `ThingsSearchPersistence` interface (§3.1).
  - **`SearchUpdateMapper` / `SearchUpdateObserver` extension points (decided — §6 D4):** both are user-facing DittoExtensionPoints; `SearchUpdateMapper`'s abstract API exposes `MongoWriteModel`/`AbstractWriteModel` and its **base constructor unconditionally creates a Mongo client** (`MongoClientExtension.get(system)…getMaxWireVersion()`, `SearchUpdateMapper.java:52-55`) — fatal on a Postgres-only service. Decision (§6 D4): **declared, documented breaking change** — re-type both to the neutral write model/`Metadata`, move the wire-version probe into the Mongo `SearchUpdaterFlow` impl, release-note + migration text in Phase H. (Alternative — deprecated Mongo-typed parallel path — rejected: it would leak Mongo into the neutral seam permanently. japicmp does not gate `thingsearch/service`, so this break is invisible to tooling — it MUST be release-noted.)
  This is the messiest refactor in the plan — Phase A isolates it and proves Mongo-parity before any Postgres code exists.
- **Postgres bulk write** (`PostgresSearchUpdaterFlow`): per incoming write model (today's "bulk" is exactly ONE write model — `MongoSearchUpdaterFlow.create():92-96`; keep per-thing framing), one transaction **per thing**:
  1. **Upsert doc row — UNCONDITIONAL for full writes (round-2 Critical fix):**
     `INSERT … ON CONFLICT (thing_id) DO UPDATE SET …` with NO revision predicate. Mongo's full `ReplaceOneModel` is guarded by `_id` only (`ThingWriteModel.java:150-176` — the revision-*equality* filter applies ONLY to patch updates); ordering safety comes from per-thing `ThingUpdater` serialization + `isNextWriteModelOutDated`, not the DB. A `revision < EXCLUDED.revision` guard would 0-row every policy-fan-out write (same thing revision, newer auth data → `INCORRECT_PATCH` → infinite retry, **auth changes never reach the index**) and every force-update/re-index write. The 0-row-⇒-conflict signal mapping applies only to the future v2 patch path, which uses the revision-EQUALITY guard exactly like Mongo's patch filter.
  2. `DELETE FROM search_flat WHERE thing_id = $1` + multi-row `INSERT` (v1: full re-flatten per changed thing). **Insert form (round 2):** use `INSERT INTO search_flat SELECT * FROM unnest($1::text[], $2::text[], …)` — one bind parameter per COLUMN regardless of row count; naive multi-row VALUES hits the extended-protocol cap of 65,535 bind params (a 100 KiB thing can carry ~17k leaves → ~34k rows × 9 cols ≈ 300k params). If VALUES batching is used anyway, chunk at ≤ ~5k rows/statement. In the same transaction as step 1 (with the v1 unconditional upsert there is no rejected-stale case; when the v2 patch path adds the equality guard, the flat rewrite MUST be conditional on the guard passing — an unconditional rewrite after a rejected stale patch would silently commit doc-row/flat-row divergence). *Churn note:* v1.5 candidate evaluated in Phase 0 — delete-changed/insert-changed via anti-join against the freshly flattened row set (most updates touch few leaves; removes ~99% of index churn without leaf-diff machinery). **Decided (Task 0.3, 1M-scale / 2x5min reduced-scale comparison — see the bench-results doc):** adopt v1.5. Measured at steady state (repeat touches to the same thing_id, disjoint pools, identical rate/worker config): ~67x fewer flat-row deletes and ~23x fewer inserts per transaction than the v1 blanket form, ~7x less WAL, ~45x-256x less index/table growth, ~89x less dead-tuple growth — direct, mechanism-attributable measurements that alone justify the decision. Phase B also showed lower latency (p50 3ms vs 14ms) and closer-to-target throughput (197.7/s vs 185.2/s against a 200/s target), but that comparison is partially confounded by autovacuum phase-boundary timing — see bench results doc — so the decision rests on the write-amplification/WAL/bloat ratios, not the latency comparison. Tradeoff: v1.5's SQL is materially more complex (two writable CTEs sharing one `MATERIALIZED` unnest, full-row-identity matching via `IS NOT DISTINCT FROM` on every value column) than v1's two-statement form, and its advantage is a steady-state property — a thing's first touch under either form pays full churn (no prior-row identity overlap). This does NOT require leaf-level diffing (that remains the separate, more surgical v2 optimization, §3.7).
  3. Deletes → `DELETE FROM search_things WHERE thing_id = $1` (cascade clears flat rows); tombstone variant mirrors `ThingWriteModel.ofEmptiedOut` (and note `noopWriteModel` exists too).
  - Unordered-bulk parity: per-thing failures collected into the neutral write result without aborting anything else; concurrent thing-DELETE racing a flat INSERT surfaces as an FK violation → map to the per-thing error path (retry resolves). **Per-thing transactions are the default** (doc row + flat rows of one thing stay atomic; failures stay local); savepoints-within-one-bulk-txn is a Phase-C benchmark alternative only. Deadlock analysis: updater and cascade both lock doc-row-then-flat-rows — same order, no cycle.
- **Policy fan-out (round-2 corrected projection):** `getPolicyReferenceTags` → `SELECT thing_id, policy_id, referenced_policies FROM search_things WHERE policy_id = ANY($1) OR referenced_policies @> ANY(…)` (GIN-served — verified on PG 16: `@> ANY(jsonb[])` appears directly in the Bitmap Index Scan condition), then per row emit one `PolicyReferenceTag` per referenced policy (own `policy_id` + every `referenced_policies[].id`) present in the incoming policy-revisions map, carrying the map's NEW revision — mirroring `MongoThingsSearchUpdaterPersistence:110-155`'s mapConcat (`policy_rev` is NOT part of this projection). **Storage shape (C2 adjudication):** `referenced_policies` stores the FULL policy tags Mongo-faithfully (`[{"type":"policy","id":…,"revision":N}]`), NOT a bare `[{id}]`; the `@>` containment probe wraps a PARTIAL object `[{"id":…}]` per changed policy (`id`-only — GIN-served by `st_referenced_pols jsonb_path_ops`), and the per-row emit reads back each stored tag's `.id`. Streamed with the existing throttle config.
- **Background sync (round-2 completed projection):** `sudoStreamMetadata` →
  `SELECT thing_id, revision, policy_id, policy_rev, referenced_policies, t_modified FROM search_things WHERE delete_at IS NULL AND thing_id > $lowerBound ORDER BY thing_id`
  (keyset-streamed, R2DBC fetch-size). Mongo projects `_id, _revision, policyId, __policyRev, t._modified, __referencedPolicies` and filters `deleteAt` absent (`MongoThingsSearchPersistence:346-360`); `BackgroundSyncStream` needs `modified` for the tolerance window (`:124-126`) and `referencedPolicies` for imported-policy staleness (`:250`) — omitting them causes spurious re-syncs and undetectable stale imports. Bookmark via `search_sync` upsert. Source side (things-service snapshots via pub/sub) already works on Postgres — no change.
- **Value resolution:** predicate values pass through the `TimePlaceholder` resolver chain (`CreateBsonVisitor.visitField:93-99` / `CreateBsonPredicateVisitor.resolveValue`) — the SQL predicate visitor needs the identical resolution before binding.

### 3.5 Query translation (D6) — RQL → SQL

New visitors in `search-r2dbc`, parallel to the BSON ones, producing a parameterized SQL AST (no string concatenation of values — bind parameters only; this includes all jsonb PATH arrays, bound as `$n::text[]` — §3.3).

**Wildcard-feature scoping rule (round-2 High fix):** for `features/*/…` paths, EVERY predicate — including its negative components and the auth recheck — is evaluated **within one `f_id` group**, mirroring Mongo's `elemMatch` (which wraps the whole predicate function, `GetFilterBsonVisitor.matchWildcardFeatureValue:137-143`). Verified divergence example: `ne(features/*/x, 5)` on `{f:[{x:5},{x:7}]}` MATCHES in Mongo ("some feature has x present and ≠5") but a thing-global `EXISTS ∧ NOT EXISTS` says no-match. Shape:

```sql
-- ne(features/*/x, $v), per-feature scoped:
EXISTS (SELECT 1 FROM search_flat a
        WHERE a.thing_id = st.thing_id AND a.wpath = $p AND <auth over a.f_id>
          AND NOT EXISTS (SELECT 1 FROM search_flat b
                          WHERE b.thing_id = a.thing_id AND b.f_id = a.f_id
                            AND b.wpath = $p AND b.val_num = $v))
```

| RQL | SQL shape (all value probes on `search_flat sf` via `EXISTS`, auth recheck per §3.3 AND-ed inside — EXCEPT the §3.3 no-auth cases) |
|---|---|
| `eq(path,v)` | `EXISTS(… sf.wpath=$p AND sf.val_<type> = $v …)`; `eq(path,null)` → row with `type_rank=1` exists (matches Ditto's Mongo visitor `and(eq(null), exists)` — missing does NOT match; verified) |
| `ne(path,v)` | non-wildcard: `EXISTS(… sf.wpath=$p …)` AND `NOT EXISTS(… wpath=$p AND val=$v …)` (Mongo `and(ne, exists)` parity incl. multikey no-element-equals — verified); wildcard-feature paths: per-`f_id` scoped shape above |
| `gt/ge/lt/le` | `EXISTS(… wpath=$p AND val_num > $v …)` (or `val_text`/collation for strings) — B-tree range scan |
| `in(path,v1..vn)` | `EXISTS(… wpath=$p AND val_<type> = ANY($arr) …)` |
| `exists(path)` | `EXISTS(… wpath=$p …)` — object/leaf rows guarantee hits at any depth; `exists(features/*)` = bare probe with NO auth (§3.3 exception 2) |
| `empty(path)` *(3.9.0 — was missing, round 2)* | OR of: no row at wpath (absent) / `type_rank=1` row (null) / empty-object row / empty-array row / `val_text = ''` — transcribing `GetEmptyBsonVisitor:43-49`, with auth recheck; needs the flattener's empty-object/empty-array rows (§3.2) |
| `like(path,pat)` | `EXISTS(… wpath=$p AND val_text LIKE $sqlpat ESCAPE '\' …)` — trigram-served; explicit `ESCAPE '\'` for self-documentation (backslash is the default; escaping of literal `%_\` verified). Cosmetic divergence: `?`→`_` matches newline where Mongo's regex `.` doesn't — document |
| `ilike(path,pat)` | `… val_text COLLATE "C.utf8" ILIKE $sqlpat ESCAPE '\' …` — the collation override matches the sf_trgm expression index and restores non-ASCII case folding (§3.2); residual libc/ICU-vs-PCRE2 folding edge cases (ß, dotted İ) documented. **Open, UNDECIDED residual risk (Task 0.2b/0.3 empirical, bench-results doc):** the global table-wide `sf_trgm` alone leaves high-cardinality-path ilike at ~2.4s warm p95 at 1M (RED-flagged); the only lever that closed it (Task 0.2b E4, ~47x) is a PER-PATH partial trigram index — a mechanism §3.7 currently declares out of scope ("no custom indexes"). Options on the table (NOT decided here, flagged for the plan owner): (a) accept slow generic ilike, documented like Mongo's own unanchored-regex-degrades-to-scan caveat (§5's "Accepted" risk row); (b) revisit §3.7 with an opt-in scoped-index knob; (c) translator-managed hot-path indexes (auto-created for configured/observed hot wpaths). See the bench-results doc's residual-tensions section for the full evidence — this plan deliberately does NOT resolve it. |
| `and/or/not` | SQL `AND`/`OR`/`NOT` over the EXISTS terms (NOT over EXISTS keeps Mongo `nor` semantics; parity-test negation-with-missing-field cases carefully) |
| sort | `LEFT JOIN LATERAL (SELECT type_rank, val_num, val_text, val_bool FROM search_flat s WHERE s.thing_id=st.thing_id AND s.wpath=$sortpath ORDER BY type_rank [DESC], val_num [DESC], val_text [DESC], val_bool [DESC] LIMIT 1) k<i> ON true` + `ORDER BY COALESCE(k<i>.type_rank, 1), k<i>.val_num, k<i>.val_text, k<i>.val_bool, st.thing_id`. Parity requirements: **(a) missing == null** — Mongo sorts missing and explicit null as *ties* and `ThingsSearchCursor.getNextDimensionCriteria:766-775` hard-codes that equivalence, so the LEFT-JOIN NULL must `COALESCE` into the null rank (plain `NULLS FIRST` sorts missing *strictly before* null — verified divergence). **(b) arrays sort by MIN element ascending / MAX descending** (Mongo multikey), and the boundary element must be selected **row-wise via ORDER BY … LIMIT 1, NOT per-column min()/max() aggregates** (round 2: independent column aggregates build chimera tuples on mixed-type arrays — rank from one element, value from another). **(c) `val_bool` is part of the tuple** (round 2: rank-6 rows have NULL val_num/val_text; without val_bool, false/true order falls to the thing_id tiebreaker — BSON orders false < true). **(d) empty arrays map to the null rank in sort** (§3.2, verified). (Mongo cannot sort on `features/*` wildcard paths at all — sort visitor has no wildcard branch — so Postgres needn't support it either.) `type_rank` order verified against Mongo's observed sort order (null < number < string < object < … < boolean), modulo the empty-array exception. |
| cursor | **Translate the resume criteria, NOT SQL row-value tuples** (round 2): `(a,b,c,d) > ($1,…)` silently drops rows whenever a tuple component is NULL — the *normal* case here (each rank populates one value column; verified `(2,5,NULL,'x') > (2,5,NULL,'a')` → NULL). The existing `ThingsSearchCursor.getDimensionLtCriteria`/`getNextDimensionCriteria` already generates backend-neutral `Criteria` with explicit null/missing branches — run them through the normal predicate visitors (NULL-safe by construction; type-bracketed resume behaves identically on both backends). Sort-value projection for cursor encoding: the aggregated boundary element from the lateral (row-wise min/max) — a deliberate, documented divergence from Mongo's whole-array cursor value (which is inexpressible on the flat table). Numeric round-trip is safe: Ditto numbers are int/long/double only (no BigDecimal). Container/encoding of `ThingsSearchCursor` reused as-is. |
| count | `SELECT count(*)` over the same WHERE |
| namespace report | `SELECT namespace, count(*) … GROUP BY namespace` |
| operator metrics (`$group`) | `SELECT <group-cols via thing #>> paths>, count(*) … GROUP BY …` (periodic, doesn't need flat-table support) |

- **Simple/system fields (round-2 correction to "fixed system fields → columns"):** per the service's actual `simple-field-mappings` (`search.conf:142-150`), only `thingId → "_id"` and `namespace → "_namespace"` are ROOT-mapped — served from doc-row columns with NO per-field auth (§3.3 exception 1). `policyId`, `_revision`, `_modified`, `_created`, `definition` (and `features/<id>/definition`) are SLASH-mapped: read from inside `t`, per-field auth-checked, compared with BSON semantics — `_modified` is an ISO **string** compared lexicographically. On Postgres these are ordinary flat-table probes over the `t` leaves with auth rechecks; `st.t_modified` (timestamptz) is never used for RQL translation (§3.2). Mappings are user-configurable (`SearchConfig:84`) — the translator must honor the configured map, not hard-code it.
- **Query-builder semantics transcription:** default sort `_id ASC`; user sort options are truncated after a `thingId` entry (`MongoQueryBuilder:52-57, 96-110`) — required for total order / keyset correctness on both backends.
- **findAll result projection:** each hit needs `(thing_id, modified)` (for `TimestampedThingId`) and the last hit's sort values (cursor encoding) — the SELECT returns these alongside the id (`MongoThingsSearchPersistence.toResultList:362-389`, `GetSortBsonVisitor.projections:62-87`).
- **like-pattern plumbing:** `LikePredicateImpl.accept` currently pre-converts to a Java regex (`LikeHelper.convertToRegexSyntax` — constrained grammar `^?(\Q…\E|.*|.)*$?`). Add a **default method** to `rql/query` `PredicateVisitor` (e.g. `visitLikeWithWildcards(@Nullable String wildcardExpression)` defaulting to `visitLike(LikeHelper.convertToRegexSyntax(...))`) and have `LikePredicateImpl`/`ILikePredicateImpl` call it — binary-compatible (japicmp-safe), Mongo visitor unchanged, SQL visitor overrides to build `LIKE` patterns (`*`→`%`, `?`→`_`, escape `%_\`).
- **Type coercion rules** (document + parity-test): RQL numbers probe `val_num` (numeric preserves int/double unification like BSON — verified `1::numeric = 1.0::numeric`), strings probe `val_text`, booleans `val_bool`. Cross-type `gt` etc. follows `type_rank` ordering only in sort, not in predicates (matches Mongo behavior where a range predicate is type-bracketed).
- **Selectivity note:** two ANDed arbitrary-path predicates become two EXISTS probes; Postgres estimates these well (per-column stats on `wpath` MCV). Phase 0 benchmarks compare EXISTS-chain vs. `IN (SELECT thing_id … GROUP BY … HAVING count=n)` plan shapes at 10M scale and fix the translator's strategy. **Decided (Task 0.2/0.2b/0.3, 1M-scale evidence — see the bench-results doc):** EXISTS-chain wins every measured mix (~1.3x-4.5x over the semi-join); for the auth-heavy shapes whose EXISTS-chain plan still nested-loops from an unselective leg, the translator additionally forces the selective leg into a `MATERIALIZED` CTE first (the "rescue" form) — 6.3x less I/O and a ~43x faster cold start than the plain EXISTS-chain in the same plan family. **HARD requirement for Phase D (plancache guard):** r2dbc-postgresql reuses named prepared statements; Task 0.2b measured PostgreSQL's plancache flipping a wpath-parameterized query to a catastrophic generic plan (55s vs 0.4s) once statistics made the generic plan look artificially cheap — the translator MUST guard against this (candidates: inlining wpath literals via a strict injection-safe whitelist, or `plan_cache_mode=force_custom_plan` on search connections).

### 3.6 Config, ops, packaging (D7)

- New conf: `internal/utils/search-r2dbc/src/main/resources/ditto-postgres-search.conf` — sets `ditto.extensions.search-persistence-provider` to `PostgresSearchPersistenceProvider` and includes the shared `ditto.postgresql` client block. **HOCON factoring (round 2):** the `ditto.postgresql` defaults currently live only inside the monolithic `ditto-postgres-persistence.conf`, whose auto-start journal lists crash a service lacking the other entities' dispatchers (its own header warns this) — the search service must NOT include it. Factor the client block into a shared `ditto-postgres-client.conf` living in the new `postgres-client` module's resources (§3.1), included by BOTH profiles (Phase B task; extend checksum/lint accordingly). Pool/SSL/credentials identical; one pool per search JVM via `PostgresClientExtension` (the *code* config path `ditto.postgresql` is already service-agnostic — `DefaultPostgresConfig:30`). Search service opt-in = single top-level `include classpath("ditto-postgres-search")` (+ the existing persistence include remains for the other services). Mongo default declared in `thingsearch/service` `search.conf` (`MongoSearchPersistenceProvider`).
- **TTL replacement:** Mongo's `deleteAt` TTL index → in-service reaper: periodic batched loop of `DELETE FROM search_things WHERE thing_id IN (SELECT thing_id FROM search_things WHERE delete_at < now() LIMIT $batch FOR UPDATE SKIP LOCKED)` (PostgreSQL `DELETE` has **no** `LIMIT` clause — verified syntax error; the subselect form is required; `FOR UPDATE SKIP LOCKED` (round 2) keeps a reaper batch from stalling behind an in-flight `ThingUpdater` transaction), cluster-singleton or advisory-lock-guarded, interval/batch configurable. Namespace purge sets the marker exactly like today — which is **epoch 0**, not now(): `UPDATE … SET delete_at = to_timestamp(0) WHERE namespace = $1` (Mongo writes `BsonDateTime(0)` — `MongoThingsSearchUpdaterPersistence:161-166`; the parity IT must not assert now()). Reads (`findAll`/`count`) do NOT filter `delete_at` — only `sudoStreamMetadata` does (§3.4); Postgres reads must match.
- **Mongo-only operational knobs (round 2):** `mongoHintsByNamespace`, `mongoCountHintIndexName`, `index-initialization.custom-indexes`, per-metric `index-hint` — on Postgres all are **ignored with a WARN log** at provider construction; documented. (A PG analog for custom-indexes — extra flat-table expression indexes — is explicitly out of scope, §3.7.)
- Health check: `SELECT 1` prober reusing `PostgresHealthChecker` pattern under the search health label (Mongo today: `MongoHealthChecker` + background-sync singleton reporter, `SearchHealthCheckingActorFactory:56-68` — the provider's `healthCheckProps()` supplies the backend prober; the background-sync reporter part stays neutral).
- Dev/run: `search-pg-dev.conf`, extend `deployment/postgres-local/` compose + `run-compound-postgres.sh` with the search service, add SearchService (Postgres) IntelliJ run config via `ide-postgres-launcher` (add `ditto-thingsearch-service` + `search-r2dbc` deps there).
- Docs: extend `installation-extending.md` Postgres section; release-note snippet on the like-pattern trigram caveat + the `SearchUpdateMapper`/`SearchUpdateObserver` breaking change (§3.4).

### 3.7 Explicitly out of scope

- Data migration Mongo-search → Postgres-search (the search index is a rebuildable projection: cutover = point at empty Postgres, trigger full background sync re-index; document this as the migration story).
- Leaf-level flat-row diffing (v2 optimization; the anti-join delete-changed/insert-changed variant is a Phase-0-evaluated v1.5 candidate — §3.4).
- DocumentDB-compat parity work, Mongo-side changes of any kind.
- Fuzzy/relevance search (ParadeDB et al.).
- Postgres analog of Mongo custom indexes (extra expression indexes on the flat table).

---

## 4. Phased implementation plan

Execution model: each phase = one or more per-task detail plans + subagent implementation + per-task review, exactly like the pluggable-persistence effort (SDD ledger). Phases are sequential except where noted; each ends with a green `mvn install` of touched modules + committed work.

### Phase 0 — De-risking spike & benchmark harness *(no production code; GATE for D1)*

**Files:** `docs/superpowers/specs/2026-07-03-postgres-search-bench-results.md` (findings) + bench code under `internal/utils/search-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/search/postgres/bench/` (kept as ITs, `@Ignore`d in CI; the module skeleton from Phase B can be created early with just the test tree if Phase 0 runs first).

- [x] Build a corpus generator: 1M and 10M synthetic twins (realistic shape: 5–50 attributes, 1–20 features, mixed types, skewed namespaces), loader for `search_things`+`search_flat` (COPY via JDBC for load speed is fine here). **DONE at 1M (Task 0.1: 68,513,542 flat rows, ~9.4 min load)** — parameterizable (`bench.count`), the 10M run is the scheduled full-scale follow-up (not yet run).
- [x] Benchmark the five canonical query shapes with EXPLAIN(ANALYZE, BUFFERS) gates: (1) `eq` two-predicate AND; (2) `gt` range on numeric path; (3) `ilike '*substr*'` (with the `COLLATE "C.utf8"` expression form); (4) sort-by-arbitrary-path + keyset page 1 and page 100; (5) count with auth filter. Gate: p95 < 200 ms at 10M rows on a laptop-class PG 16 with warm cache, all plans index-backed (no seq scan of `search_flat`). **DONE — `1M / 10 min` corpus, PROVISIONAL: full `10M / ≥1h`-equivalent scale scheduled follow-up.** Raw (Task 0.2): shapes 1/3-high/5 red-flagged at 1M (p95 up to ~6.9s); all closed under Task 0.2b's mitigation stack (stats+memory+scoped-trgm+CTE rescue: 87/71/49/50ms) — see bench-results doc for which lever mattered and the residual 10M-scale risk.
- [x] Benchmark write fan-out: full re-flatten upsert of a 500-leaf twin at 200 updates/s sustained (`unnest` insert form); watch trigram-GIN pending-list behavior (`gin_pending_list_limit`, `fastupdate`), autovacuum lag. **Run ≥ 1 h and gate on table/index bloat PLATEAUING** (round 2 — throughput alone hides bloat runaway; record per-table autovacuum storage parameters used: `autovacuum_vacuum_scale_factor`, cost limit). Gate: sustained without bloat runaway. **DONE at `1M / ~10 min` — PROVISIONAL (user's reduced-scale decision): a 10-minute run cannot prove a bloat plateau; the trend is reported honestly (not a plateau claim) in the bench-results doc. The `≥1h`/10M plateau gate itself is NOT YET RUN — scheduled full-scale follow-up.**
- [x] Benchmark the v1.5 churn-reduction candidate: anti-join delete-changed/insert-changed vs. blanket DELETE+INSERT (§3.4 step 2 note); record the decision. **DONE (Task 0.3, 1M/2x5min reduced scale) — decision recorded in §3.4 and the bench-results doc.**
- [x] Compare EXISTS-chain vs. semi-join plan shapes for multi-predicate AND; record the chosen translator strategy in this doc (amend §3.5). **DONE (Task 0.2: EXISTS-chain wins every mix, ~1.3x-4.5x; Task 0.2b/0.3 refined the strategy to EXISTS-chain + materialized selective-leg CTE for the selective-leg-first shapes) — recorded in §3.5.**
- [x] Decision checkpoint: if gates fail → evaluate pg_documentdb fallback before proceeding (stop and report). **DONE — see the bench-results doc's provisional gate verdict: proceed under the stated 1M mitigations; the full 10M/≥1h scale remains unproven and is a scheduled follow-up, not a stop condition by itself.**
- [x] Commit bench + findings doc. **DONE — Tasks 0.1/0.2/0.2b/0.3, see `docs/superpowers/specs/2026-07-03-postgres-search-bench-results.md`.**

### Phase A — SPI extraction (Mongo-parity refactor, independently mergeable)

**Files:** Create `thingsearch/persistence-api/**` (pom, moved+re-typed interfaces, `SearchPersistenceProvider`, `SearchIndexDocument`, neutral write models + write result + `SearchUpdaterFlow`); Modify `thingsearch/service` (SearchRootActor, SearchUpdaterRootActor, ThingUpdater, **SearchActor** (slow-query rendering → provider hook), EnforcedThingMapper, SearchUpdateMapper/SearchUpdateObserver, BulkWriteResultAckFlow, MongoSearchUpdaterFlow ↔ new `MongoSearchPersistenceProvider`, search.conf defaults); Modify `bom/pom.xml` + **`thingsearch/pom.xml` `<modules>`**; Test: golden-file BSON parity + all existing thingsearch tests.

**Interfaces produced:** `SearchPersistenceProvider` (§3.1 signatures), `SearchIndexDocument` (accessors: `thingId()`, `namespace()`, `revision()`, `policyId()`, `policyRevision()`, `referencedPolicies()`, `globalRead()`, `thing() : JsonObject` — RAW keys, `policyAuth() : JsonObject`, `featureAuth() : Map<String,JsonObject>`), neutral `ThingWriteModel`/`ThingDeleteModel` (+ emptied-out/noop variants) holding `Metadata` + `SearchIndexDocument`, `SearchUpdaterFlow` + `UpdaterData`/`UpdaterResult` + neutral write result/status classifier (§3.4).

- [ ] Move + re-type the three persistence interfaces (drop `initializeIndices(IndexInitializationConfig)`; add `recoverLastWriteModel(ThingId)`; `aggregateThings → Source<JsonObject,…>` + adapt `AggregateThingsMetricsActor`) + `TimestampPersistence` usage behind the new module; introduce provider (no `SearchConfig` in signatures — §3.1); wire `SearchRootActor`/`SearchUpdaterRootActor` **and `OperatorAggregateMetricsProviderActor`** (which constructs the aggregation persistence at `:118`, not the root actors) through it; make ALL `MongoClientExtension` creation provider-internal — including the call in `SearchUpdateMapper`'s base constructor.
- [ ] Neutralize the write-path plumbing: `ThingUpdater.Data`/`Result`, `WriteResultAndErrors` → neutral write result, `BulkWriteResultAckFlow`'s classification rules (duplicate-key⇒success etc.) moved behind the seam; re-type `SearchUpdateMapper`/`SearchUpdateObserver` (documented breaking change — §3.4/§6 Q4); move the wire-version probe into the Mongo flow.
- [ ] Split `EnforcedThingMapper` into neutral document builder (raw keys) + Mongo BSON encoder (applies `KeyNameReviser`); **golden-file test**: for a corpus of representative things+policies, byte-compare new BsonDocument output vs. pre-refactor output (capture fixtures before refactor).
- [ ] Neutralize `ThingUpdater`'s write-model handling (recovery via the interface method, patch-vs-replace stays Mongo-internal).
- [ ] Move `SearchActor`'s slow-query filter rendering behind `renderForDiagnostics(Query)`.
- [ ] Add `rql/query` `PredicateVisitor.visitLikeWithWildcards` default methods (+ ILike); japicmp green.
- [ ] ArchUnit: persistence-api neutrality test; keep service Rule 2; add `enforce-no-r2dbc-in-service` to thingsearch/service pom.
- [ ] Verify: `mvn install` on thingsearch/* + full existing test suite green; run search service against Mongo locally (existing .run config) — behavior unchanged.
- [ ] Commit (this phase alone is a mergeable refactor PR; release-note stub for the extension-point break per §6 D4).

### Phase B — Schema & module bootstrap

**Files:** Create `internal/utils/postgres-client/` (**extraction from `persistence-r2dbc`, behavior-preserving** — §3.1 contents list: client extension, config, public `ConnectionPoolFactory`, DDL-credentials factory, parameterized `PostgresSchemaManager` + advisory-lock key + shared `schema_version` DDL, health checker, metrics glue, `ditto-postgres-client.conf`); Modify `internal/utils/persistence-r2dbc` (depend on `postgres-client`, delete moved classes, `PostgresSchema` implements the schema-descriptor interface, `ditto-postgres-persistence.conf` includes the shared client conf); Create `internal/utils/search-r2dbc/` (pom — deps `postgres-client` + `thingsearch/persistence-api` only; `PostgresSearchSchema`, `PostgresSearchSchemaManager`; `PostgresSearchPersistenceProvider` skeleton with `bootstrapSchema()`); `ditto-postgres-search.conf`; Modify `bom/pom.xml` + **`internal/utils/pom.xml` `<modules>`**; Test: `PostgresSearchSchemaManagerIT` (Testcontainers, `PostgresDbResource` pattern) incl. a concurrent-bootstrap test (persistence + search schema managers against one fresh DB).

- [ ] Extract `postgres-client`; full `persistence-r2dbc` unit+IT suite must stay GREEN unchanged (the extraction is pure relocation + the schema-descriptor parameterization).
- [ ] DDL per §3.2 (incl. `CREATE EXTENSION pg_trgm` with actionable error if the DDL role lacks permission); checksum/versioning like `PostgresSchema`; shared `schema_version` DDL + advisory-lock key via `postgres-client` (§3.2 coordination note).
- [ ] Provider skeleton resolvable via `ditto.extensions.search-persistence-provider`; boot self-check wiring; Mongo-only knobs ignored-with-WARN (§3.6).
- [ ] Verify: ITs green (`mvn -pl internal/utils/postgres-client,internal/utils/persistence-r2dbc,internal/utils/search-r2dbc -am verify`); commit.

### Phase C — Write path

**Files:** Create in `search-r2dbc`: `ThingFlattener` (SearchIndexDocument → flat rows per §3.2 rules incl. wpath duplication, RECURSIVE array explosion + ord enumeration, empty-object/array rows, raw keys), `PostgresSearchUpdaterFlow` (unnest inserts, neutral write result incl. unique-violation⇒success), `PostgresThingsSearchUpdaterPersistence` (policy fan-out per §3.4 corrected projection, purge with epoch-0 marker, namespace ops), `PostgresTimestampPersistence` (search_sync), delete-reaper actor (SKIP LOCKED). Test: `ThingFlattenerTest` (unit, exhaustive shape matrix incl. nested arrays + array-of-array stub + empties), `PostgresSearchWritePathIT`, `PostgresPolicyFanoutIT` (MUST cover same-thing-revision policy-only writes — the round-2 Critical), `PostgresPurgeReaperIT`.

- [ ] TDD the flattener first (it encodes most schema semantics); then the transactional write per §3.4 (UNCONDITIONAL full-write upsert; doc-row builder computes `t_modified` from `thing._modified` — no generated column, §3.2); error mapping to the neutral write result (FK-race → per-thing error; duplicate-key ⇒ success).
- [ ] `sudoStreamMetadata` per §3.4 completed projection (modified + referenced_policies + delete_at filter + lowerBound keyset).
- [ ] Verify + commit per component.

### Phase D — Read path (the core)

**Files:** Create in `search-r2dbc`: SQL AST + renderer (`SqlNode`, bind-param collector — binds values AND jsonb path arrays), `CreateSqlVisitor`/`CreateSqlPredicateVisitor` (criteria incl. `empty()` — `GetEmptySqlVisitor`), `GetFilterSqlVisitor`/`GetExistsSqlVisitor`/`GetSortSqlVisitor`/auth-filter builder (field expressions, transcribing `AbstractFieldBsonCreator` semantics INCLUDING the §3.3 no-auth exceptions and the full wildcard-feature auth chain), `PostgresQuery`/`PostgresQueryBuilderFactory` (default `_id ASC` sort + thingId-truncation semantics), `PostgresThingsSearchPersistence` (full interface: findAll — with modified+sort-value projection, findAllUnlimited — idleTimeout streaming for `StreamThings`, count, sudoCount(query, headers, hint→ignored), sudoStreamMetadata, namespace report, recoverLastWriteModel — doc row → neutral write model), cursor support via translated resume criteria, `TimePlaceholder` resolution chain, `renderForDiagnostics`. Test: translator unit tests (RQL string → expected SQL+params), `PostgresSearchReadIT`.

- [ ] Implement predicate/exists/empty/auth translation per §3.3/§3.5 (transcribe the Mongo visitors' nesting exactly, incl. per-`f_id` scoping for wildcard-feature predicates; keep a side-by-side comment map Mongo-visitor-line → SQL builder).
- [ ] Sort (row-wise-LIMIT-1 lateral, val_bool in tuple, empty-array→null rank) + cursor paging via `getNextDimensionCriteria` translation; sort-value projection for cursor encoding (reuse `ThingsSearchCursor`).
- [ ] Simple-field handling per configured `simple-field-mappings` (§3.5).
- [ ] Verify + commit per component.

### Phase E — Aggregation & metrics

**Files:** Create `PostgresThingsAggregationPersistence` (returns `Source<JsonObject,…>` per the re-typed interface); Test `PostgresAggregationIT`. GROUP-BY translation of the operator-metrics config (per-metric `index-hint` ignored with WARN); namespace count report.

### Phase F — Packaging, enforcement, health

**Files:** Rework extension packaging to the three-JAR layout (§3.1/§6 D2): repurpose `internal/utils/persistence-r2dbc-extension` → `ditto-postgres-client-extension` (base: `postgres-client` + full third-party whitelist incl. netty-resolver-dns — coordinate with the uncommitted fix in the sibling worktree) + thin `ditto-postgres-persistence-extension` + new thin `ditto-postgres-search-extension`; update `deployment/` docs/compose mounts for the 2-JAR-per-service matrix; Create ArchUnit tests in search-r2dbc (no-Mongo) and persistence-api (neutrality — if not done in A); health-check props; HOCON lint for `ditto-postgres-search.conf` + `ditto-postgres-client.conf` (**search-specific positive anchor** — the existing `PostgresProfileHoconLintTest` asserts auto-start journal IDs that don't exist for search; anchor on the provider class set instead; note `ditto.mongodb` remains *present* in search.conf defaults, so no naive "Mongo-free" assertion); boot self-check coverage incl. the cross-JAR same-release version-marker check (§3.1).

- [ ] Extension-JAR classloading ITs for BOTH deployment shapes: base+persistence pair on a things-like classpath, base+search pair on a search-like classpath; each resolves its provider; a missing base JAR fails with an actionable error.
- [ ] Verify: full `mvn install` (all modules) green; commit.

### Phase G — Parity & system verification *(the correctness backstop)*

**Files:** Create `internal/utils/search-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/search/postgres/parity/SearchBackendParityIT.java`; fixture corpus as JSON resources under the same module's `src/test/resources/parity/` (things + policies + queries + expected-divergence allowlist).

- [x] **Parity matrix IT** (the single most important test of this effort): one corpus of things+policies (covering: nested attributes, all scalar types, arrays **incl. arrays nested under array-of-objects and direct array-of-array**, feature wildcards **incl. features disagreeing on a value (`ne` scoping)**, grants/revokes at every level incl. revoke-below-grant, null vs. missing vs. **empty (`""`/`{}`/`[]` — `empty()` operator)**, unicode/special chars in keys — `KeyNameReviser` cases, **non-ASCII `ilike`**) is written to BOTH a Mongo container and a Postgres container through their respective full write paths; a battery of RQL queries (every operator — incl. `empty()` — × path kind × auth situation, sort asc/desc **incl. empty-array and boolean sort positions**, cursor walk to exhaustion **incl. pages crossing type boundaries and null sort values**, count) is executed against both; result sets (IDs + order where sort is total) must be identical. Divergences allowed only where documented in this plan (sub-3-char like performance — not results; array-of-array inner content; whole-array cursor sort-value encoding; ilike folding edge cases ß/İ). **DONE (Task G1, commits e81ed6aa07+e524acd528): `SearchBackendParityIT` — 32 things × 158 query cases × 9 auth situations = 1419 comparisons + 4 real-`ThingsSearchCursor` walks, both real production write paths, machine-readable allowlist with closure test (silent widening structurally impossible). ONE new divergence found and adjudicated as allowlist entry #8: sudo + feature-wildcard value predicates — Mongo's `matchWildcardFeatureValue` degrades to a dotted top-level path matching nothing while Postgres answers semantically (PG strict superset; no auth impact — sudo bypasses auth). Both sides pinned. Fold #8 into the §4 allowlist row in Phase H.**
- [x] Background-sync end-to-end IT on Postgres-only stack (things+search on PG): write → sync → query; force-update path; **policy-update fan-out with unchanged thing revision** (round-2 Critical regression test); imported-policy staleness detection (referenced_policies in the metadata stream). **DONE (Task G2, commit f02b5217aa): `PostgresBackgroundSyncE2eIT` — 5 scenarios vs real PG16, detection path 100% production code (real `BackgroundSyncStream` × `sudoStreamMetadata` × `PostgresSearchUpdaterFlow`), things-side Metadata fixture byte-faithful to `ThingsMetadataSource.toMetadata`; every scenario pins its negative + second-pass-silent convergence; force-update scenario drives the `BackgroundSyncActor` force-mode bypass seam below the actor layer (real actor route covered by the G3 stack run).**
- [x] `deployment/postgres-local/` full-stack manual run: all 4 services + search on Postgres, UI query smoke test. **DONE (Task G3, 2026-07-05, fresh DB): Policies+Things+Connectivity+Gateway+Search booted via extended `run-compound-postgres.sh` + new `search-postgres.conf` overlay (search-r2dbc jar prepended = extension-JAR-pair equivalent); FIRST real boot of the search service on Postgres — provider resolved via config key, `ditto-postgres-search` schema v1 bootstrapped, reaper started; REST battery green (eq / feature-gt / like+sort-desc / count; update→index follows; delete→count drops); `search_things`/`search_flat`/`search_sync` rows verified in psql; health = persistence prober UP + backgroundSync UP; ZERO ERROR lines in all five logs; UI smoke via Ditto explorer (pre-auth `nginx:ditto`, `ENABLE_CORS=true`): RQL filter answers from the PG backend.**
- [x] Commit; update this doc's status block. **DONE — Phase G complete 2026-07-05.**

### Phase H — Dev experience & docs

- [ ] `search-pg-dev.conf`, IntelliJ `SearchService (Postgres)` run config via `ide-postgres-launcher` (add `ditto-thingsearch-service` + `search-r2dbc`; `postgres-client` comes transitively), compose updates, `installation-extending.md` + release-note snippets (trigram caveat, re-index cutover story — §6 D3, `pg_trgm` prerequisite, **`SearchUpdateMapper`/`SearchUpdateObserver` breaking change + migration guide** — §6 D4, Mongo-only knobs ignored on PG, **the three-JAR deployment matrix incl. the persistence-services 1→2 JAR change** — §6 D2).
- [ ] Final full `mvn clean install`; commit.

### 4.1 Divergence allowlist (consolidated)

The parity IT (Phase G) freezes the Mongo↔Postgres divergences to a machine-readable allowlist, exercised and
closure-checked so silent widening is structurally impossible. The canonical, machine-readable source of truth is
`internal/utils/search-r2dbc/src/test/resources/parity/divergence-allowlist.json` (the `SearchBackendParityIT`
asserts `referenced == declared`). The consolidated rows (5 documented in §3.5 as "D3"-report items + 2 plan-level
items already implied by §3.5, + the G1-adjudicated entry #8, + the C2-adjudicated write-acknowledgement item #9 —
which is scoped to write-ACK behavior only and is deliberately NOT part of the machine-readable query allowlist json,
since it never changes a query result):

| # | Allowlist id | Divergence (both sides pinned per backend) |
|---|---|---|
| 1 | `cursor-sort-value-whole-array-object-null` | Cursor sort-value ENCODING for null / object / empty-array boundary elements diverges (PG encodes null; Mongo encodes the whole container) — each backend's own walk still terminates in its pinned sequence. |
| 2 | `object-sort-ties-by-thing-id` | Object-valued sort keys tie on PG (rank-4, NULL tuple → thing-id tiebreak); Mongo orders objects by recursive BSON field content. |
| 3 | `array-of-array-asc-desc-asymmetry` | A scalar sibling sharing a path with a nested array sorts by its real value under ASC but as-if-missing under DESC on PG; Mongo sorts by min(ASC)/max(DESC) element consistently. |
| 4 | `wildcard-sort-rejection-vs-noop` | PG hard-rejects a `features/*/…` sort key; Mongo silently builds a never-matching literal path (query still returns, just unordered by that key). |
| 5 | `empty-overmatch-array-of-array` | `empty()` against a direct array-of-array path collapses onto the rank-5 empty stub on PG, so PG matches `empty()` where Mongo does not. |
| 6 | `array-of-array-inner-content` | Content inside a direct array-of-array is a single valueless rank-5 stub on PG (inner elements not enumerated); Mongo keeps them as BSON. Not RQL-scalar-reachable on either backend. |
| 7 | `ilike-fold-edges-sharp-s-dotted-i` | Case-insensitive folding of U+00DF (ß) and U+0130 (İ) differs (Mongo case-insensitive regex vs PG `lower()`-based ILIKE). |
| 8 | `sudo-wildcard-value-mongo-degenerate-path` | **(G1 adjudication)** sudo + feature-wildcard value predicates: Mongo's `matchWildcardFeatureValue` degrades to a dotted top-level path matching nothing (empty result); PG answers `features/*` value predicates semantically — **PG strict superset; no auth impact** (sudo bypasses auth). Mimicking Mongo's degenerate answer on PG would deliberately return wrong results, so PG's semantic answer is pinned. |
| 9 | `empty-write-weak-ack` | **(C2 adjudication — scope: write-ACKNOWLEDGEMENT only, NOT query results; deliberately NOT part of the machine-readable query allowlist json.)** When a thing write model maps to no storage change, the emitted acknowledgement differs: Mongo computes an incremental diff and, when it is empty, skips the write and dispatches a **weak** acknowledgement (`MongoSearchUpdaterFlow.create` → `metadata.sendWeakAck(null)`); Postgres does no diff — a full write is an UNCONDITIONAL doc-row upsert (plan §3.4 step 1) that always executes and returns a **normal (strong) success** acknowledgement instead. Both backends converge on the SAME persisted index state and therefore identical query results — only which ack is emitted for a no-change re-write diverges. (The upstream `isNoop()` fast-path is identical on both.) |

---

## 5. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Auth-filter semantic divergence (revoke/grant inheritance) | **Highest** — silent data leaks | 1:1 transcription of `getAuthFilter` structure **incl. the no-auth exceptions (§3.3 — blanket auth = missing results, the inverse failure)** + parity matrix with adversarial policy fixtures (revoke-below-grant, feature-level revokes, partial subtrees) |
| Write-guard semantics (policy fan-out / force-update starvation) | **Highest** — was a round-2 Critical in this plan's own §3.4 | Unconditional full-write upsert (§3.4 step 1); dedicated `PostgresPolicyFanoutIT` same-revision case + Phase-G sync IT |
| Flat-table write amplification at high twin-update rates | High | Phase 0 gate (now incl. ≥1 h bloat plateau); `unnest` inserts; v1.5 anti-join churn reduction evaluated in Phase 0; v2 leaf-diff slot; trigram pending-list tuning; per-thing txn isolation keeps failures local |
| `SearchUpdateMapper`/`SearchUpdateObserver` user-extension break | Medium — invisible to japicmp | Declared, release-noted breaking change + migration text (Phase H); decided (§6 D4) |
| Sort/type-ordering parity (BSON total order vs. type_rank emulation) | Medium | Row-wise lateral + val_bool in tuple + empty-array→null rank + missing==null tie + `COLLATE "C"` now specified (§3.2/§3.5); remaining nuances → Phase 0/parity IT; document any deliberate divergence |
| Planner regressions at scale (EXISTS chains, skewed `wpath` stats) | Medium | Phase 0 plan-shape decision: EXISTS-chain + materialized selective-leg CTE (bench-results doc); `default_statistics_target` bump on `wpath` is a confirmed-INSUFFICIENT lever alone (~34x misestimate persists — E1 negative result); slow-query log already exists in search config (now rendering SQL via `renderForDiagnostics`); r2dbc plancache generic-plan-flip guard is a HARD Phase-D requirement (bench-results doc) |
| `rql/query` public-API change | Low | default methods only, japicmp gate |
| Refactor regression on Mongo path (Phase A) | High | golden-file BSON tests + full existing suite + manual Mongo run before any Postgres code |
| Concurrent schema bootstrap on shared DB | Low | shared advisory-lock key + shared `schema_version` DDL via `postgres-client` (§3.2); concurrent-bootstrap IT (Phase B) |
| Version skew between the three layered extension JARs | Low | same-release requirement documented; boot self-check version-marker across loaded extension artifacts (Phase F); missing-base-JAR classloading IT with actionable error |
| pg_trgm unavailable / DDL-role restrictions | Low | trusted extension on PG≥13 (verified); explicit bootstrap error message + docs prerequisite |
| Sub-3-char like patterns degrade to scans | Accepted | mirrors Mongo's own unanchored-regex degradation; document; slow-query log catches abuse |
| High-cardinality-path ilike stays slow (~2.4s warm p95 at 1M) under the global table-wide `sf_trgm` alone; the only lever that fixed it (per-path partial trigram) conflicts with §3.7 "no custom indexes" | Medium — **UNDECIDED, flagged for the plan owner** | Task 0.2b/0.3 empirical (bench-results doc); three options on the table (accept-and-document / opt-in scoped-index knob revisiting §3.7 / translator-managed hot-path indexes) — this plan deliberately does not choose one (§3.5 ilike row) |

## 6. Decisions (ALL settled with the user, 2026-07-04 — no open questions remain)

1. **D1 — PG floor: 16.** Matches the persistence-backend floor, the `PostgresDbResource` Testcontainers version, and all round-1/round-2 empirical verification; nothing in the design depends on >16.
2. **D2 — Packaging: separate, OPTIONAL search extension, three layered JARs** (user-chosen design): extract shared-infra module `internal/utils/postgres-client`; ship `ditto-postgres-client-extension` (base, all third-party) + thin `ditto-postgres-persistence-extension` + thin `ditto-postgres-search-extension`. Persistence services mount base+persistence (2 JARs — documented change from today's single JAR); search mounts base+search; Mongo-search users mount nothing on the search service. Same-release pairing enforced by boot self-check (§3.1, Phase F).
3. **D3 — Migration: cutover-by-re-index is the ONLY path.** The search index is a rebuildable projection; background sync regenerates it. The re-index window (search incomplete until sync finishes) is documented as the operator story; no copy tooling.
4. **D4 — `SearchUpdateMapper`/`SearchUpdateObserver`: documented breaking change.** Both re-typed to the neutral write model in one go; wire-version probe moves into the Mongo flow; release note + migration guide in Phase H. No deprecated Mongo-typed parallel path (it would keep Mongo in the neutral seam alive for a release).

## 7. Research provenance

- Codebase maps (search service + Postgres seam) and the wildcard-alternatives web research (PostgreSQL docs, Crunchy Data, pganalyze, pgsql-hackers, FerretDB/pg_documentdb, Ditto 3.0 release notes, Pachot's wildcard-index surveys) were produced 2026-07-03; key findings are inlined in §1–§3. The full sourced web-research report should be attached to the eventual PR description or review record.
- **Fact-check review (round 1, 2026-07-03, post-write):** all §1 codebase claims re-verified against the worktree with file:line evidence (corrections folded in: 38/123 Mongo-coupled classes; `Query`/`QueryBuilderFactory` interfaces are neutral — coupling is the `MongoQuery` downcast; aggregation persistence constructed in `OperatorAggregateMetricsProviderActor`; `persistence-api` is not shaded — precedent). All §2 external claims source-verified (Ditto 3.0 "10%" note, Mongo wildcard eq+range+single-predicate-field restriction, GIN jsonpath equality-only, pg_trgm, pg_documentdb/Linux Foundation/FerretDB v2). SQL claims tested empirically on `postgres:16`: `?|`+`COALESCE` auth shape ✓, `@> ANY(jsonb[])` GIN index condition ✓, and four defects found and fixed in this doc — generated-column immutability (§3.2), flat-PK wildcard-row collision (§3.2), `DELETE … LIMIT` non-syntax (§3.6), default-collation vs. BSON string order (§3.2) — plus the missing-vs-null sort divergence (§3.5).
- **Second-round review (2026-07-03, three independent lenses: Mongo-implementation fidelity / PostgreSQL semantics (empirical, postgres:16 + mongo:7) / architecture & executability):** 2 Critical + 7 High + ~15 Medium/Low findings, ALL folded into this revision. Criticals: (1) the former `revision < EXCLUDED.revision` upsert guard would have starved policy fan-out and force-update — full writes are now unconditional (§3.4); (2) the former SPI signatures (`SearchConfig` params) could not compile in a neutral module — provider now takes no service config, interfaces re-typed (§3.1). Highs: per-`f_id` scoping for wildcard-feature predicates; `ilike` collation fix (`C.utf8` expression index); recursive array flattening + `ord` redefinition (integer enumeration counter); missing `empty()` operator added; `SearchUpdateMapper`/`SearchUpdateObserver` break identified + decided (§6 Q4); `SearchUpdaterFlow` seam widened to the full neutral type set; background-sync projection completed. Also verified correct in round 2 (no change needed): §3.3 auth boolean structure vs. real `getAuthFilter`, `eq(null)`/thing-level `ne` translations vs. the actual Mongo visitors, `type_rank` order vs. observed Mongo sort order, pg_trgm trusted-extension status, `numeric` int/double unification, B-tree tuple-size headroom under the 950-byte enforcer, `jsonb ?| text[]` on arrays + dynamic `f_id` in `jsonb_extract_path`, LIKE backslash-escaping. The three full review reports are in the session record; attach to the eventual PR.
- **Decision round (2026-07-04):** all four formerly open questions settled with the user (§6 D1–D4). The packaging decision (separate optional search JAR, three-layer split with a shared `postgres-client` base) was the user's own design and triggered the §3.1 module restructuring — `postgres-client` extraction, `search-r2dbc` decoupled from `persistence-r2dbc`, Phase B/F reworked accordingly.
