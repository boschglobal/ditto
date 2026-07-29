# Postgres backend — IT test review & re-plan: CONTEXT HANDOFF

- **Created:** 2026-06-02
- **Purpose:** Self-contained briefing so a FRESH session can (1) **review** the existing integration tests for the Ditto PostgreSQL persistence backend, confirm where coverage is weak/wrong, then (2) **plan** a proper IT suite. This file is the entry point — read it first, then start the review.
- **Author's premise (the requester's, and the evidence supports it):** the current ITs are *not well implemented and the real flows are not correctly covered*. They assert at the wrong altitude (the SQL `*Ops`/`journal` API), bypass the real Pekko event/snapshot **adapter seam**, cover only one entity (`things`) and one happy-path flow, and silently turn into a no-op when Docker is absent.

> This doc is CONTEXT + a review/plan process. It is deliberately **not** the IT implementation plan itself — producing that plan is the next task.

---

## 0. The task, in two phases

1. **Review** (read-only, evidence-first): walk the IT files listed in §3, confirm/extend the weaknesses in §5 against the *current* tree, and produce a findings list (what each IT really exercises, what it only appears to, what is missing). Use `/code-review` or a read-only explore pass; do not change code during the review.
2. **Plan**: from the confirmed findings + the "correct coverage" target in §6, write an implementation plan for a real IT suite (new ITs, refactors, test infra, CI gating). Use the project's planning flow (e.g. `superpowers:brainstorming` → `superpowers:writing-plans`, or `/forge-plan`). Save the plan to `docs/superpowers/specs/`.

Open questions the plan must resolve are collected in §10.

---

## 1. TL;DR — what the review will most likely confirm

- **W1 (structural, biggest):** ITs persist **hand-built `JsonObject`/`String`** straight into `journal.writeMessages(...)` and recover via `.match(String.class, …)`. They never drive a real domain `ThingEvent`/`PolicyEvent` through the configured **`event-adapter-bindings`**, and never load a snapshot through a per-service **snapshot adapter**. That is the exact seam the round-2 C-1/C-2 fixes added — and it is currently **untested end-to-end** (only unit-tested in isolation).
- **W2:** Coverage is **single-entity (`things`) + single-flow (graceful restart)**. No `policies`/`connections`/`wot-validation-config`; no hard crash; no concurrent same-`(pid,sn)`; no ping/wake flow.
- **W3:** **No service-module ITs.** `things`/`policies` service modules have only *unit* adapter/wiring tests; `connectivity` and `wot` services have **no Postgres tests at all**. So C-2 (per-service snapshot adapters) and C-3 (actor→provider wiring) have **no real-actor, real-DB coverage**.
- **W4:** All 7 ITs wrap container startup in `Assume.assumeNoException(...)` → on a Docker-less runner the whole IT matrix **reports green while asserting nothing** (round-2 L-3). No `require-docker` CI profile or sentinel exists.
- **W5:** Assertions are often at the wrong altitude or too weak (round-2 L-2/L-4). Proof it bites: `PostgresParityMatrixIT.duplicateWriteWithDifferentPayloadIsFatal` asserted the **pre-H-6 buggy contract** for a year and only the 2026-06-02 `verify` caught it (see §8 "recent lesson").

These map directly onto round-2 findings **H-13, M-11, L-1..L-5, L-15** (see §7) — most of which were **never ticketed** (only the 10 defect tickets were).

---

## 2. Where the code lives — review target

Multiple worktrees of this repo exist; **verify CWD before editing**. Branch/worktree map (all LOCAL, nothing pushed, no PR):

| Worktree | Branch | Contents |
|---|---|---|
| `…/postgres-190626-feat-dev` | `postgres-190626-feat-dev` | base `2b44232333` + docs commits (this file's home) |
| `…/pg-r2-wt/integration-final` | `pg-r2-integration-final` @ `6f8462c4bb` | **all 10 round-2 fixes merged + IT matrix GREEN** ← **review the ITs here** |
| `…/pg-r2-wt/journal-seam` | `pg-r2/journal-seam` | C-1 event adapter + H-6 |
| `…/pg-r2-wt/snapshot` | `pg-r2/snapshot` | C-2 snapshot adapters |
| `…/pg-r2-wt/actor-wiring` | `pg-r2/actor-wiring` | C-1+C-2 merged + C-3 |
| `…/pg-r2-wt/operations` | `pg-r2/operations` | R-1 + H-2 |
| `…/pg-r2-wt/migrator` | `pg-r2/migrator` | H-10 |
| `…/pg-r2-wt/pool` | `pg-r2/pool` | H-1 + H-8 + H-9 |

**Review on `pg-r2-integration-final` (`6f8462c4bb`)** — it is the only place all the round-2 production fixes coexist, so it reflects the contracts the ITs must actually cover. Module under test: `internal/utils/persistence-r2dbc` (artifactId `ditto-internal-utils-persistence-r2dbc`). Service modules: `things/service`, `policies/service`, `connectivity/service`, `wot/…`.

Run the suite: `mvn -o -pl :ditto-internal-utils-persistence-r2dbc verify` (needs Docker). Maven binary: `/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn` (no wrapper). Last green run logs: `/tmp/pg_r2_it_verify2.log`.

---

## 3. Current IT inventory (persistence-r2dbc module)

All under `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/`. **7 IT classes, ~22 IT methods**, all Testcontainers-gated.

- **`PostgresParityMatrixIT`** (14 methods) — the workhorse. Covers GIN tag containment, priority ordering, historical revision round-trip, JSONB snapshot decode, **divergent-dup-is-fatal** (just realigned to H-6), idempotent snapshot upsert, bounded read-journal deletes, `getNewestSnapshotsAbove`, TOAST oversize payload, in-transaction rollback, checksum-refuses-boot, concurrent-first-boot DDL race, pool-acquire-timeout-fails-write, neutral command metric. **BUT every write is `journal.writeMessages(write(pid, sn, "<hand-built json>"))` — the `*Ops`/journal seam, never a real event adapter.**
- **`PostgresPersistenceIT`** (5) — HWM survives physical delete, priority tag ordering tolerates malformed tag, `loadAsync` criteria + delete overloads, **same-payload duplicate is idempotent**, replay returns events in sequence order. Same direct-`journal` seam.
- **`PostgresPluginLifecycleIT`** (1) — `pluginsBootThroughPekkoAndRecoverAcrossRestart`. The ONLY "real Pekko actor" IT. **`BootTestActor`** (`:157-216`): hardcodes `journalPluginId()="ditto-postgres-things-journal"`/`snapshotPluginId()="ditto-postgres-things-snapshots"` (NOT via the provider — so C-3 path untested), **persists a hand-built `JsonFactory.newObject("{\"seq\":…}")`** and recovers via `.match(String.class, event -> count++)` — deliberately the *post-adapter* payload. Single entity (`things`), graceful `PoisonPill` restart only.
- **`PostgresProviderBootstrapIT`** (2 + container lifecycle) — `bootstrapThroughProviderSeamCreatesAllTables`, `…FailsBootWhenDatabaseUnreachable`. Closest to H-1 end-to-end, but provider-level, `things`-shaped.
- **`schema/PostgresSchemaManagerIT`** (2) — bootstrap creates-all-tables + idempotent; divergent pre-existing PK refuses to boot.
- **`PgBouncerTransactionPoolingIT`** (1) — write→recover→cleanup through a real PgBouncer container.
- **`migration/PostgresMigratorIT`** (7) — COPY journal+snaps, resume idempotency, `--verify` parity, **`_journal_seq` watermark (H-10)**, checksum-mismatch refusal, plus `resumeAndVerifyAgainstRealMongoContainer` (the one currently **SKIPPED** — needs `mongodb-driver-sync` + `testcontainers:mongodb` in the BOM).

### Service-module Postgres tests (all UNIT, not ITs)
- `things/service/.../persistence/serializer/`: `ThingPostgresEventAdapterTest`, `ThingPostgresSnapshotAdapterTest`, `ThingAdapterSerializerParityTest` — call `toJournalJson`/`fromJournalJson` **directly** (round-2 M-11: methods Pekko's journal never invokes; they bypass the binding).
- `policies/service/.../persistence/actors/PolicyPersistenceActorPluginIdWiringTest` — unit, stub `PersistenceBackendProvider`.
- `connectivity/service`, `wot/…`: **none.**

---

## 4. Test infrastructure & gotchas

- **`PostgresDbResource.java`** — the Testcontainers harness. Container start is guarded so an unreachable daemon becomes a JUnit **skip** (`Assume.assumeNoException`, `:90`), not a failure → see W4/L-3.
- **`api.version` trap (already mitigated, keep it):** Testcontainers 1.20.6's shaded docker-java clamps to Docker API 1.32, which modern daemons reject; `PostgresDbResource` sets `api.version=1.43` before TC loads. This had silently skipped the early ITs before — any new test infra must preserve it.
- **Surefire vs Failsafe:** unit tests = `*Test.java` (surefire), ITs = `*IT.java` (failsafe `integration-test`/`verify`). The 2026-06-02 green run = **158 unit + 30 IT pass, 1 skip, 0 fail**.
- **Conventions:** EPL-2.0 header, new files year **2026**; commit hook requires `Signed-off-by:` (`git commit -s`); JDK 25, Pekko 1.6.0, Scala 2.13.18; artifact versions only in `bom/pom.xml`.

---

## 5. The weaknesses, with evidence (this is the review's spine — confirm/expand each)

- **W1 — ITs bypass the real event/snapshot adapter seam.** *Files:* `PostgresPluginLifecycleIT.java:157-216` (BootTestActor: hand-built JsonObject, `match(String.class)`, hardcoded plugin IDs); `PostgresParityMatrixIT.java:146,172-176,197-198,234-239` (`journal.writeMessages(write(pid,sn,json))`); service-module `*AdapterTest` call `toJournalJson`/`fromJournalJson` directly. *Consequence:* a real `ThingEvent` → `event-adapter-binding` → `toJournal`(`Tagged(json, getJournalTags)`) → JSONB → replay → `fromJournal` → `match(getEventClass())` chain is **never exercised**. Round-2 **M-11** + the rationale under **C-1**.
- **W2 — single-entity, single-flow.** `PostgresPluginLifecycleIT` is `things`-only, one method, graceful stop. No hard-crash-mid-write, no concurrent same-`(pid,sn)` at the *actor* level (only at the `*Ops` level in the parity IT), no post-snapshot replay tail (round-2 **L-15**), no ping/wake. Round-2 **H-13**.
- **W3 — no service-module end-to-end ITs.** C-2 (per-service snapshot adapters in things/policies/connectivity) and C-3 (actors routing through `backendProvider()`) are only unit/stub-tested. No real `PolicyPersistenceActor`/`ConnectionPersistenceActor`/WoT actor boots on the **Postgres profile** against a real DB and recovers. `connectivity`/`wot` services have zero Postgres tests.
- **W4 — Docker-absent ⇒ silent green.** 7/7 ITs `Assume.assumeNoException` on container start (see §4). No `-Dditto.it.require-docker=true` profile, no sentinel assertion. "30 IT green" is only meaningful on a Docker-equipped runner. Round-2 **L-3**.
- **W5 — wrong-altitude / weak assertions.** Read-journal contract tests assert "does not throw" against empty stubs (round-2 **L-2**); `--verify`/parity samples only payload JSON, not tags/manifest/lifecycle/written_at (round-2 **L-4**); DELETED-lifecycle exclusion never tested with an actual DELETED row (round-2 **L-1**). The parity-IT contract drift (§8) is the canonical proof that testing at the `*Ops` seam misses actor-seam contract changes.
- **W6 — migration fault-injection gaps.** No kill-`-9`-between-COPY-and-checkpoint, NUL-bearing document, or sibling-failure-interrupt cases (round-2 **M-8/M-9/H-12**, §6 Residual Risk). `--verify` blind to watermark/columns (L-4).
- **W7 — backpressure/load not IT-covered.** H-2 (`fetch-size`) is only unit-tested via `RecordingConnectionFactory`; no IT streams a large result set against real PG to prove demand-driven fetch / no OOM. H-8 connection-budget and M-2 N+1 likewise unproven at scale (round-2 §6 Residual Risk).

---

## 6. What "correct coverage" should look like (target for the plan)

The real production flow is: **domain command → persistent actor → `persist(event)` → Pekko journal plugin → event-adapter `toJournal` → JSONB row (with tags) → … restart … → `replayMessages` → adapter `fromJournal` → `match(getEventClass())` → in-memory state**; snapshots: **`saveSnapshot(state)` → snapshot-adapter `toSnapshotStore` → JSONB → `loadAsync` → `fromSnapshotStore` → domain object**. ITs must cover *that*, per entity, on the Postgres profile.

Proposed coverage matrix — **entity ∈ {things, policies, connections, wot-validation-config} × flow**:

- **A. Boot via provider** — actor boots on the Postgres profile; `journalPluginId()/snapshotPluginId()` resolve **through `backendProvider()`** (C-3), not hardcoded.
- **B. Real event round-trip** — persist a genuine domain event through the configured **`event-adapter-binding`**; assert the JSONB row carries the event AND `getJournalTags(event)` (C-1/H-5).
- **C. Recover across restart** — replayed events decode via `fromJournal`; recovered actor state == pre-restart (C-1).
- **D. Snapshot round-trip** — save+load through the per-service **snapshot adapter**; no `ClassCastException`; JSONB not BSON (C-2); a serialization failure surfaces as a failed Future, not a sync throw (L-13).
- **E. Hard-crash durability** — kill the actor mid-write (not `PoisonPill`); recover; assert no lost/duplicated sequence numbers (H-13).
- **F. Concurrent same-`(pid,sn)` at the actor level** — identical payload ⇒ idempotent success; divergent ⇒ **`WriteMessageFailure`, actor stops** (H-6 end-to-end, the contract the parity IT now asserts only at the `*Ops` level).
- **G. Ping/wake** — `PersistencePingActor` re-activates an always-alive entity after restart because journal **tags were persisted** (H-5).
- **H. Fresh-DB auto-bootstrap** — first write on an un-pre-seeded DB triggers `PostgresSchemaManager.bootstrap()` through the real boot path and succeeds (H-1), per entity/service, incl. the DDL-role credential path.
- **I. Backpressure / scale** — stream a large `currentPersistenceIds()`/`eventsByTag()` against real PG; assert demand-driven fetch (positive `fetchSize`) and no full-result-set buffering (H-2/M-2).
- **J. Migration end-to-end + faults** — watermark migrated (H-10); resume idempotent across a crash between COPY-commit and checkpoint (M-8); NUL-bearing doc fails per-row, not a wedge (H-12); `--verify` checks tags/manifest/lifecycle/written_at (L-4); live-Mongo source (un-skip `resumeAndVerifyAgainstRealMongoContainer` — needs BOM deps).

**Cross-cutting:**
- A **`require-docker` CI profile** (`-Dditto.it.require-docker=true`) that turns container-absence into a FAILURE, and/or a sentinel assertion (kills W4/L-3).
- Assert at the **actor/adapter altitude**, not the `*Ops`/journal API, for any contract an actor depends on.
- A shared **real-event IT harness** (build genuine `ThingEvent`/`PolicyEvent`/… via the existing test factories) so B/C/F/G aren't re-rolled per test.
- Decide where service-actor ITs live (service modules vs a new cross-module IT module) — see §10.

---

## 7. Round-2 findings that ARE the test problem (index)

From `docs/superpowers/specs/postgres-persistence-backend-critical-review-round2.md` (read §4 Medium, §5 Low, §6 Residual Risk):

- **M-11** — parity tests assert serializer equality but bypass the journal/adapter wiring, masking C-1. *(core of W1)*
- **H-13** — no e2e coverage for concurrent same-pid writes, hard-crash resume, or multi-entity (Policy/Connection/WoT) recovery. *(W2)*
- **L-1** — snapshot-cleanup DELETED-lifecycle exclusion never tested with an actual DELETED row.
- **L-2** — read-journal contract test asserts only "does not throw" against an empty stub.
- **L-3** — all Testcontainers ITs silently SKIP when Docker is absent. *(W4)*
- **L-4** — Migrator `--verify` samples only event-payload JSON (not tags/manifest/lifecycle/written_at).
- **L-5** — `verifySchema` misreports unknown collection as "schema missing".
- **L-15** — lifecycle IT proves only snapshot recovery, never Pekko-driven replay of post-snapshot events.
- **§6 Residual Risk** — real-cluster load/OOM (H-2/M-2/H-8), multi-service deploy split-brain (C-3/M-4), crash-during-COPY (M-8/M-9/H-12) all *unverified by any test*.

**Important:** these test-quality findings were **NOT turned into beads tickets** — only the 10 defect tickets (`ditto-postgres-*`) were. The plan should decide whether to ticket them.

---

## 8. Pointers, commits, and the recent lesson

- **Round-2 review:** `docs/superpowers/specs/postgres-persistence-backend-critical-review-round2.md` (the authority on contracts + test gaps).
- **Workflow report:** `docs/superpowers/specs/postgres-round2-fix-workflow-report-2026-06-02.md` (what the 10 fixes changed + their tests).
- **Memory:** `…/.claude/projects/-Users-sta1sf3-…-ditto/memory/project_postgres_backend_status.md` (full phase history + the IT-matrix lesson).
- **bd tickets:** `bd list` — the 10 `ditto-postgres-*` defect tickets (all commented, none closed); test-quality findings are NOT yet ticketed.
- **Recent lesson (read this — it's the thesis in miniature):** on 2026-06-02 the first `verify` on `pg-r2-integration-final` failed one IT: `PostgresParityMatrixIT.duplicateWriteWithDifferentPayloadIsFatal`. It was *not* a regression — the test (written pre-H-6, last touched `2b44232333`) asserted the **old buggy contract** (divergent duplicate ⇒ a present `Optional<Exception>` in the result = recoverable `WriteMessageRejected`, i.e. the silent-write-loss bug). H-6 made it a **failed Future**. The test was realigned (commit `b419c86c5a` on `pg-r2/journal-seam`, merged to `6f8462c4bb`). **Why it matters here:** the test "covering" H-6 asserted at the `*Ops`/journal altitude and encoded the bug; an actor-level IT would have made the contract obvious. This is W1/W5 in one incident.

---

## 9. Suggested process for the next session

1. **Re-orient (5 min):** open `pg-r2-integration-final`, run `git log --oneline 2b44232333..HEAD`, skim this doc's §3/§5.
2. **Review (evidence-first):** for each IT in §3, open it and answer: *what production path does this actually execute?* Tag each as `real-flow` / `ops-seam-only` / `stub` / `skip-risk`. Confirm W1–W7. Capture file:line evidence. (Read-only; consider `/code-review` or a read-only explore agent. Do NOT trust test names — the parity IT proves names lie.)
3. **Define the target matrix (§6):** decide the entity × flow grid you actually want, and the harness needed (real-event factory, require-docker profile, where service-actor ITs live).
4. **Plan:** `superpowers:brainstorming` → `superpowers:writing-plans` (or `/forge-plan`). Output a plan doc in `docs/superpowers/specs/` with: new ITs (by flow/entity), refactors (kill the hand-built-payload harness; route BootTestActor through the provider), infra (require-docker, BOM deps for live-Mongo IT), CI gating, and a build/verify sequence. Decide ticketing of the test-quality findings.
5. **(Optional) validate the plan** with `/forge-review-plan` before implementing.

---

## 10. Open questions the plan must resolve

1. **Where do service-actor ITs live?** In each service module (`things/policies/connectivity/wot` service) — adds Postgres+Testcontainers test deps to four modules — or a new dedicated cross-module IT module that depends on all four? (Trade-off: dependency weight + build time vs. locality.)
2. **Real-event construction:** reuse each service's existing event test factories (e.g. Thing event POJOs) to feed the adapter, or a thinner synthetic event? Must go through the *real* `event-adapter-binding`, not `toJournalJson` directly.
3. **Docker gating policy:** make `require-docker` the default in CI only, or always? How to keep local `mvn verify` runnable without Docker without re-introducing silent-green.
4. **Scale/backpressure ITs (H-2/M-2/H-8):** in-IT (a few thousand rows, assert fetch behavior) vs. a separate load harness (the round-2 doc treats these as load-env gates, not TC asserts). Pick the boundary.
5. **Live-Mongo migrator IT:** add `mongodb-driver-sync` + `testcontainers:mongodb` to the BOM to un-skip `resumeAndVerifyAgainstRealMongoContainer`? (Currently the lone documented skip.)
6. **Ticketing:** create beads tickets for H-13/M-11/L-1..L-5/L-15, or track the new IT suite as one epic?
7. **Branch strategy:** build the IT suite on top of `pg-r2-integration-final`, or after it merges back to `postgres-190626-feat-dev`?

---

*End of handoff. Start with §0, review against §3/§5 on `pg-r2-integration-final`, then plan toward §6.*
