# Postgres backend real IT suite — DESIGN (Plan 1)

- **Created:** 2026-06-02
- **Status:** design approved; ready for implementation-plan (writing-plans).
- **Companions:** `postgres-it-test-review-findings.md` (Phase-1 review, the evidence this design answers), `postgres-it-test-review-context.md` (handoff; §6 target matrix, §10 open questions), `postgres-persistence-backend-critical-review-round2.md` (contracts).
- **Premise (confirmed by the review):** the existing Postgres ITs are an SQL/Ops-layer suite — not one IT drives a real domain event/state through the adapter binding + provider + a real persistent actor against a real DB, and a Docker-less CI reports the whole matrix green. This design builds the missing real-flow coverage, starting with a reusable harness and a `things` reference.

---

## 1. Goals & non-goals

**Goals (Plan 1):**
1. A **reusable Postgres real-flow IT harness** that other entities can extend with minimal code.
2. End-to-end coverage of the `things` entity across flows **A–H** (§4), each asserting at the **actor/adapter altitude** and inspecting persisted row columns (tags/manifest/lifecycle/written_at) — directly verifying round-2 blockers **C-1** (event adapter wiring), **C-2** (snapshot adapters), **C-3** (provider plugin-id routing).
3. **Kill the Docker-absent silent-green** (W4/L-3): always-require-Docker; container-absence becomes a build failure.

**Non-goals (deferred to later, separately-spec'd phases):**
- Replicating the matrix to `policies` / `connections` / `wot-validation-config`.
- Migration fault-injection (W6: crash-between-COPY-and-checkpoint, NUL-byte doc, sibling-worker failure, `CopyBatchException`).
- Scale / backpressure ITs (W7: large result-set streaming, fetch behaviour, connection budget).
- PgBouncer rework + image pinning (F1); live-Mongo migrator IT (needs BOM deps); `ThingAdapterSerializerParityTest` redirect (M-11 offender).

---

## 2. Decisions (locked during brainstorming)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| Scope | Size of Plan 1 | Harness + `things` reference (flows A–H), then phase out | §6 matrix too large for one plan; prove the pattern before scaling |
| IT location | Where ITs/harness live | Shared abstract base + `PostgresResource` `@ClassRule`; thin per-service subclasses | Mirrors existing `MongoEventSourceITAssertions` (test-jar consumed by service modules) |
| Actor vehicle | What drives recover flow | **Both** — deep `ProviderWiredTestActor` matrix + one real-`ThingPersistenceActor` smoke IT | Deep matrix is light/replicable; smoke proves the genuine domain actor |
| Docker gate | Silent-green fix | **Always require Docker** (remove `Assume`/`catch(Throwable)`) | Simplest; no property toggle; surfaces real container-boot bugs |
| Branch | Where work lands | Merge `pg-r2-integration-final` → `postgres-190626-feat-dev` **first**, then branch | Promote round-2 work, then build ITs on the promoted base |
| Ticketing | Tracking | beads epic + findings tickets + phase tickets | Track alongside the existing `ditto-postgres-*` defects |

---

## 3. Architecture & components

### 3.1 Shared harness (in `internal/utils/persistence-r2dbc` test sources, exposed via a new test-jar)

- **`PostgresEventSourceITBase`** (abstract) — owns:
  - the `PostgresResource` `@ClassRule` (always-require-Docker; no `Assume`),
  - schema bootstrap through the real `PersistenceBackendProvider` → `PostgresSchemaManager`,
  - a PG-profile `ActorSystem` built from a reusable HOCON fragment,
  - **helpers:** `persistEvent(...)`, `restartActor(...)`, and row-level inspectors `readJournalRow(pid, sn)` / `readSnapshotRow(pid)` returning the stored `event`/`snapshot` JSONB **plus** `tags`, `manifest`, `__lifecycle`, `written_at` so tests assert the full row, not just payload (closes W5).
  - **parameterization (abstract hooks subclasses implement):** entity type; the event-adapter-binding config fragment; a real-event factory; the expected event class; a state-equality assertion.

- **`ProviderWiredTestActor`** — a persistent actor whose `journalPluginId()`/`snapshotPluginId()` resolve **through `PersistenceBackendProvider`** (the real C-3 path — replacing `BootTestActor`'s hardcoded `"ditto-postgres-things-journal"` literals). It persists **genuine domain events** (so Pekko applies the configured `event-adapter-binding` → `ThingPostgresEventAdapter.toJournal` → `Tagged(json, tags)`), and recovers via `.match(getEventClass())`. Respects the known Pekko trait-init constraint on plugin-id accessors (see round-2 C-3 / the persistence-API gotchas memory).

### 3.2 Two vehicles

- **Deep matrix** → `ProviderWiredTestActor` drives flows A–H. Light, replicable per entity in later phases.
- **Smoke** → one IT booting the **real `ThingPersistenceActor`** on the Postgres profile, extending a PG variant of the existing `MongoEventSourceITAssertions`-style setup. Proves the genuine domain actor boots + recovers from Postgres.

### 3.3 Real-event source

Reuse the `things` test factories already used by `ThingPostgresEventAdapterTest` (`ThingCreated`/`ThingModified` builders). Events are fed through the configured `event-adapter-bindings` — **never** `toJournal`/`toJournalJson` directly.

---

## 4. The `things` flow matrix (Plan 1)

| Flow | What the IT does / asserts | Closes |
|---|---|---|
| **A. Boot via provider** | actor boots on PG profile; `journalPluginId()/snapshotPluginId()` resolve via `backendProvider()`, not hardcoded literals | C-3 |
| **B. Real event round-trip** | persist a genuine `ThingEvent`; assert the JSONB row carries the event **and** `getJournalTags(event)` (+ manifest, lifecycle) | C-1, H-5, W5 |
| **C. Recover across restart** | restart; replay decodes via `fromJournal`; recovered state == pre-restart | C-1 |
| **D. Snapshot round-trip** | save+load via `ThingPostgresSnapshotAdapter`; assert JSONB (not BSON), no `ClassCastException`; a serialization failure surfaces as a **failed Future**, not a sync throw | C-2, L-13 |
| **E. Hard-crash durability** | kill the actor mid-write (not `PoisonPill`); recover; assert no lost/duplicated sequence numbers | H-13 |
| **F. Concurrent same-`(pid,sn)`** (actor level) | identical payload → idempotent success; divergent payload → `WriteMessageFailure`, actor stops | H-6 end-to-end |
| **G. Ping/wake** | `PersistencePingActor` re-activates an always-alive entity after restart because journal **tags were persisted** | H-5 (heaviest) |
| **H. Fresh-DB bootstrap** | first write on an un-pre-seeded DB triggers `bootstrap()` through the real boot path | H-1 |

**Plus smoke:** real `ThingPersistenceActor` boot + recover on Postgres.

> Flow **G** is the most involved (it pulls in the ping machinery). It is retained in Plan 1 per decision; if implementation reveals it needs disproportionate scaffolding it may be split into its own task, but it stays in Plan 1's scope.

---

## 5. Infra & module changes

1. **`internal/utils/persistence-r2dbc/pom.xml`** — add `maven-jar-plugin` `test-jar` goal to expose `PostgresEventSourceITBase` + `PostgresResource` cross-module (mirrors `internal/utils/persistence`).
2. **`things/service/pom.xml`** — add test-scope deps: `ditto-internal-utils-persistence-r2dbc` (`type=test-jar`) + `org.testcontainers:postgresql`.
3. **Docker-gate sweep** — remove `Assume.assumeNoException` / `catch(Throwable)` from the existing 7 ITs and use them via the centralized `PostgresResource` lifecycle, so container-absence **fails** the build. (In scope because docker-gating is Plan-1 infra; it touches existing ITs.)
4. **BOM** — confirm `org.testcontainers:postgresql` version is managed (already present). No Mongo deps for Plan 1.

---

## 6. Verification

- `mvn -o -pl :ditto-internal-utils-persistence-r2dbc verify` (Docker required) — harness + existing ITs green, **no skips**.
- `mvn -o -pl :ditto-things-service verify` (Docker required) — new `things` real-flow ITs + smoke IT green.
- Every new IT asserts at the actor/adapter altitude and inspects row columns; the suite must **fail** (not skip) if Docker is absent.
- Maven binary: `/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn` (no wrapper). JDK 25, Pekko 1.6.0, Scala 2.13.18.

---

## 7. Phasing & branch strategy

- **Phase 0 (prerequisite, gated on explicit user go-ahead):** merge `pg-r2-integration-final` @ `6f8462c4bb` → `postgres-190626-feat-dev` (promotes the round-2 work, currently local/un-PR'd), then branch `postgres-it-suite` off feat-dev.
- **Plan 1:** this spec (harness + `things` A–H + docker sweep).
- **Later:** policies/connections/wot replication → migration faults → scale.

---

## 8. Ticketing

- beads **epic**: "Postgres backend real IT suite".
- **Findings tickets** (test-quality, never previously ticketed): H-13, M-11, L-1, L-2, L-3, L-4, L-5, L-15.
- **Plan-1 phase tasks** as children of the epic.
- Link all to the existing `ditto-postgres-*` defect tickets.

---

## 9. Conventions

EPL-2.0 license header on new `.java` files, year **2026** ("Contributors to the Eclipse Foundation"); existing files keep their original year. Commit hook requires `Signed-off-by:` (`git commit -s`). All artifact versions in `bom/pom.xml`; module POMs omit `<version>`.

---

*End of design. Next: writing-plans → implementation plan saved to `docs/superpowers/specs/`.*
