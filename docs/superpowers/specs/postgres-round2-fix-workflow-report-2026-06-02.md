# Ditto Postgres Backend — Round-2 Fix Workflow Report

**Date:** 2026-06-02
**Base SHA:** `2b44232333e4255cbd1eb3cdda22300b87bd6fd3` (branch `postgres-190626-feat-dev`)
**Scope:** Remediation of the round-2 critical-review blockers and key high-severity findings (see `postgres-persistence-backend-critical-review-round2.md`).
**Status:** All worked tickets approved by review. Work remains **LOCAL** (no PR, no merge to `main`) per project policy until the blockers land.

---

## 1. Per-ticket results

| Finding | Ticket | Branch | Status | Rounds | One-line summary |
|---------|--------|--------|--------|--------|------------------|
| R-1 | `ditto-postgres-ysd` | `pg-r2/operations` | approved | 1 | `deleteMessagesTo` INSERT path no longer fabricates `highest_sn`; seeds `highest_sn=0`, feeds `toSequenceNr` only into `deleted_to`. |
| H-2 | `ditto-postgres-xvt` | `pg-r2/operations` | approved | 1 | Positive per-statement `fetchSize` (256) portal on the 3 unbounded read-journal streams restores backpressure; folds in M-2 N+1 fix. |
| H-10 | `ditto-postgres-8tf` | `pg-r2/migrator` | approved | 2 | Split migrator whitelist into disjoint target vs. `_journal_seq` sets; COPY into a seq table now structurally impossible. |
| C-2 | `ditto-postgres-2yg` | `pg-r2/snapshot` | approved | 1 | Per-service Postgres snapshot adapters (Thing/Policy/Connection) wired; env-gated `snapshot-adapter` override; L-13 async-failure fix. |
| C-3 | `ditto-postgres-ym6` | `pg-r2/actor-wiring` | approved | 2 | Policy/Connection/WoT actors route through `PersistenceBackendProvider`; added missing per-entity Mongo plugin-id overrides to fix the default backend. |

**Approved:** 5 tickets (R-1, H-2, H-10, C-2, C-3).
**Unresolved (not worked this run):** 1 — H-1 (`ditto-postgres-8hi`).
**Aborted at integration:** 1 branch — `pg-r2/pool` (carries H-1 + H-8).

### Dependency note: C-1 (`ditto-postgres-ess`, branch `pg-r2/journal-seam`)
C-1 (Postgres event-adapter wiring / journal seam) is **present and integrated** — it is the base off which `pg-r2/actor-wiring` was branched (HEAD `8590c501c9`), and it merged cleanly into the integration branch. It is not listed as a separately-reviewed line item in this run's per-ticket outcomes, but its content (event adapter + journal/event-adapter `.conf` block) is in the integration tree. Treat C-1 as **landed-pending-final-review** in the merge order below.

---

## 2. Open findings on unresolved / aborted tickets

### H-1 — `ditto-postgres-8hi` — Wire `PostgresSchemaManager.bootstrap()` + ddl-credentials into a real boot path  (OPEN / NOT MERGED)
Carried on `pg-r2/pool` together with H-8. **This branch did NOT integrate.** The schema-bootstrap path remains unwired in the integration branch, so a fresh Postgres database will not have its journal/snapshot/`_journal_seq` tables created automatically — this is still a functional blocker for real entity traffic against an empty database.

**Why it aborted (structural conflict in `ConnectionPoolFactory.java`):**
- The already-merged side (operations/journal) uses a single `buildOptions(config)` that sets USER/PASSWORD inline; `createConnectionPool` calls `ConnectionFactories.get(buildOptions(config))`.
- The pool side (H-1) introduces `baseOptions()` + `buildDdlOptions()` + `createDdlConnectionFactory()` with per-caller credential layering so the DDL role can override the runtime role.
- Git's textual auto-merge produced a broken blend (`buildOptions`, `baseOptions`, AND `buildDdlOptions` all present; `buildOptions` declared to return `ConnectionFactoryOptions` but its body calls `baseOptions()` which returns a `Builder`). The merge was aborted (`git merge --abort`) rather than guessed.

**Required manual reconciliation:** choose the final method topology + credential-layering model in `ConnectionPoolFactory.java` (single `buildOptions` vs. `baseOptions`/`buildDdlOptions` split), then re-merge `pg-r2/pool`. This also brings in H-8 (shared pool / 3x max-size connections) which the pool branch addresses.

### H-8 — shared per-plugin connection pool (`ditto-postgres-nie`, P2)
Bundled on `pg-r2/pool`; blocked behind the same `ConnectionPoolFactory.java` reconciliation. Not independently reviewed this run.

### Other still-open review findings NOT addressed this run (for tracking)
- **H-6** (`ditto-postgres-br5`): duplicate-payload mismatch + non-dup integrity errors returned as recoverable rejections (silent write loss). Open.
- **ditto-postgres-176** (P2): pool metrics. Open.

---

## 3. Integration result

A throwaway integration branch `pg-r2-integration` was cut off the base SHA in worktree `/Users/sta1sf3/Develop/projects/Bosch/ditto-ws/pg-r2-wt/integration`.

| Branch | Finding(s) | Merge result |
|--------|-----------|--------------|
| `pg-r2/snapshot` | C-2 | fast-forward → `f5f8810cdb` |
| `pg-r2/journal-seam` | C-1 | merge commit `445dcc565a` |
| `pg-r2/actor-wiring` | C-3 | merge commit `e3e2697853` (already contained journal-seam+snapshot) |
| `pg-r2/operations` | R-1, H-2, M-2 | merge commit `b81c3140c3` |
| `pg-r2/migrator` | H-10 | merge commit `c19ae38e77` |
| `pg-r2/pool` | **H-1, H-8** | **ABORTED** — structural conflict in `ConnectionPoolFactory.java` (see §2) |

- **5 of 6 branches integrated cleanly.** The only shared `.conf` (`ditto-postgres-persistence.conf`) auto-merged with no conflict markers across snapshot/journal-seam (both extended it additively; the union was produced automatically).
- **Compile:** `mvn -o -q -pl :ditto-internal-utils-persistence-r2dbc -am test-compile` exited 0 (only benign JVM native-access/Unsafe warnings). Module output: 56 main + 63 test `.class` files, including merged `PostgresJournalEventAdapterBindingTest` and `ConnectionPoolFactory`.
- The Signed-off-by hook (not a content conflict) initially blocked the auto-generated merge messages; completed with a Signed-off-by trailer.
- **This integration branch is advisory/local only** — not pushed, not merged into `postgres-190626-feat-dev` or `main`. `main` and the feature branch are untouched.

---

## 4. Recommended HUMAN MERGE ORDER

Merge in this order. Each entry lists the files that **multiple branches touch** so the human can pre-empt conflicts.

> **`ditto-postgres-persistence.conf` is the hot file** — it is touched by **journal-seam, snapshot, pool, AND actor-wiring**. The first four merges layered additively and auto-merged in the trial integration, but re-verify the union after each merge (it must contain: journal + event-adapter block from C-1; snapshot-store + per-service snapshot-adapter blocks from C-2; the actor-wiring entity/alias overrides; and the pool/DDL block from H-1).

1. **`pg-r2/snapshot` (C-2)**
   - Shared file: `ditto-postgres-persistence.conf` (adds snapshot-store + per-service snapshot-adapter blocks). Base of the chain — no conflict expected.

2. **`pg-r2/journal-seam` (C-1)**
   - Shared file: `ditto-postgres-persistence.conf` (adds journal + event-adapter block). Verify additive union with the snapshot blocks.

3. **`pg-r2/actor-wiring` (C-3)**
   - Already contains journal-seam + snapshot in its history (merge `80f90a6eee`), so this is mostly a no-op re-merge if 1–2 are in.
   - Shared file: `ditto-postgres-persistence.conf` (entity/alias + provider overrides). Also touches `internal/utils/persistence/src/main/resources/reference.conf` (new per-entity Mongo plugin-id overrides) and the Policy/Connection/WoT persistent-actor classes — **no other branch touches these**, so no conflict.

4. **`pg-r2/operations` (R-1, H-2, M-2)**
   - **Owns `PostgresPersistenceOperations.java`** (R-1 INSERT fix, H-2 `streamingQuerySource`, M-2 `getLatestJournalEntries`) — **no other landed branch edits this file**, so clean. Also touches `ConnectionPoolFactory` **javadoc/comments only** (fetch-size=0 caveat) — watch for a trivial comment-level overlap when pool lands next.

5. **`pg-r2/migrator` (H-10)**
   - Self-contained in the migrator sources/tests. No shared files with 1–4. Clean.

6. **`pg-r2/pool` (H-1, H-8) — EXPECT CONFLICT; reconcile manually.**
   - **`ConnectionPoolFactory.java`**: structural conflict with the operations-side `buildOptions(config)` refactor. The pool side adds `baseOptions()` + `buildDdlOptions()` + `createDdlConnectionFactory()` with credential layering. **Do NOT accept the textual auto-merge** (it produces an inconsistent method topology — `buildOptions` returning `ConnectionFactoryOptions` while calling `baseOptions()` which returns a `Builder`). Decide the final method shape first, then reconcile so the DDL role can override the runtime role without leaking into `createConnectionPool`.
   - **`ditto-postgres-persistence.conf`**: pool/DDL credentials block — re-verify the additive union one final time.
   - Also reconcile against the operations-side `ConnectionPoolFactory` javadoc comment from step 4.

### Cross-branch conflict matrix

| File | journal-seam | snapshot | actor-wiring | operations | migrator | pool |
|------|:---:|:---:|:---:|:---:|:---:|:---:|
| `ditto-postgres-persistence.conf` | ✎ | ✎ | ✎ | | | ✎ |
| `PostgresPersistenceOperations.java` | | | | ✎ (owner) | | |
| `ConnectionPoolFactory.java` | | | | ✎ (comments) | | ✎ (structural) |
| `reference.conf` (mongo plugin-ids) | | | ✎ | | | |
| Policy/Connection/WoT actor classes | | | ✎ | | | |
| migrator sources/tests | | | | | ✎ | |

✎ = branch modifies this file. The **only** genuine code conflict is `ConnectionPoolFactory.java` between operations (comments) and pool (structural); plan that reconciliation when landing step 6.

---

## 5. Verification caveat — ITs / Docker NOT run

**Integration tests and Docker / Testcontainers were NOT executed by this workflow.** The only build signal collected was a `test-compile` of `:ditto-internal-utils-persistence-r2dbc` on the trial integration branch (exit 0). Per-ticket "approved" verdicts include `verified-pass` build checks at the unit level only.

**Remaining manual verification steps before any PR:**
1. Run the full Postgres Testcontainers IT matrix (the WU16 parity suite) against a real PG instance with **all** approved branches merged.
2. Land H-1 (`pg-r2/pool`) so schema bootstrap runs against an empty database, then re-run ITs against a freshly-created (un-pre-seeded) schema.
3. Exercise real entity traffic for Thing/Policy/Connection/WoT through the Pekko adapter seam (the round-2 review's core concern) — confirm event + snapshot adapters are actually invoked, not bypassed.
4. Re-run the offline COPY migrator end-to-end and confirm `--verify` catches a watermark rewind (H-10).

Until H-1 lands and the IT matrix is green on the merged set, the backend should still be considered **not yet mergeable for real entity traffic**.

---

## ADDENDUM — Round-2 FINISH workflow (2026-06-02)

Follow-up run that **completed the previously-aborted `pg-r2/pool` line** (H-1 reconciliation landed via the integration merge) and worked the three open high-severity findings deferred by the first run — **H-6, H-8, H-9** — plus a final `ConnectionPoolFactory.java` integration reconcile. All four lines are **approved** by correctness + regression review (1 round each), `verified-pass`, 0 blocking. Work remains **LOCAL** — no PR, no merge to `main`, no push.

### Addendum per-ticket results

| Code | Ticket | Branch | Status | Rounds | One-line summary |
|------|--------|--------|--------|--------|------------------|
| H-6 | `ditto-postgres-br5` | `pg-r2/journal-seam` | approved | 1 | Divergent-payload `(pid,sn)` duplicate + non-unique integrity violations now fail the write (`WriteMessageFailure`, actor stops) instead of mapping to a recoverable `WriteMessageRejected`; empty `Optional` reserved for the truly-idempotent matching duplicate; `recoverWrite` distinguishes SQLSTATE 23505 before `reconcileDuplicate`. TDD red-then-green proven. |
| H-8 | `ditto-postgres-nie` | `pg-r2/pool` | approved | 1 | Pre-existing committed impl (commit `2850ff2309`) — shared single per-service pool owned by `PostgresClientExtension`; reviewed and approved this run (was NEVER reviewed before). |
| H-9 | `ditto-postgres-176` | `pg-r2/pool` | approved | 1 | Pool metrics now polled for the write-path pool: poller start moved out of the lazy `getReadJournal()` path into `PostgresClientExtension.createExtension` (fires on first journal/snapshot/read-journal resolve); low-cardinality `pool=shared` tag via `GaugePublisher` sink; role-derived shutdown-task + thread names; pool renamed `ditto-postgres-pool-shared`. Reconciled with H-8's collapsed single-pool topology. TDD red-then-green. |
| INT | `integration-reconcile` | `pg-r2-integration-final` | approved | 1 | Resolved the only unmerged file (`ConnectionPoolFactory.java`) in the `pg-r2/pool` ← `pg-r2-integration-final` merge; reconciled to the pool branch's structurally-correct `baseOptions`/`buildOptions`/`buildDdlOptions`/`createDdlConnectionFactory` shape while re-applying the operations-side H-2 backpressure javadoc. `ditto-postgresql.conf` auto-merged cleanly (additive union). |

**Addendum approved:** 4 lines (H-6, H-8, H-9, INT). **No unresolved / crashed / review-failed tickets in this run** — there are no open findings to carry from the addendum scope.

### Updated status of the §2 "still-open" findings

The first run's §2 listed H-6, H-8 (`pg-r2/pool`), and `ditto-postgres-176` (H-9) as open, and H-1 (`ditto-postgres-8hi`) / `pg-r2/pool` as aborted at integration. **This addendum closes all of those gaps at the code/review level (tickets left OPEN per policy):**
- **H-6** — fixed and reviewed-approved on `pg-r2/journal-seam`.
- **H-8** — its committed impl reviewed-approved on `pg-r2/pool`.
- **H-9** — fixed and reviewed-approved on `pg-r2/pool`.
- **H-1 / `pg-r2/pool` abort** — the `ConnectionPoolFactory.java` structural conflict from §2/§6 is **now resolved**: `pg-r2/pool` merged into `pg-r2-integration-final`, so the DDL credential-layering topology (`baseOptions` → `buildOptions`/`buildDdlOptions`/`createDdlConnectionFactory`) and the H-8 shared pool are in the integration tree.

### Final integration result

`pg-r2-integration-final` (worktree `/Users/sta1sf3/Develop/projects/Bosch/ditto-ws/pg-r2-wt/integration-final`) now carries **all branches including `pg-r2/pool`**, which previously aborted.

- **HEAD:** `fd5e6b2206` — `Merge branch 'pg-r2/pool' into pg-r2-integration-final` (preceding merges: migrator `889e8289d6`, operations `4d879c260f`, actor-wiring `864be2da16`, journal-seam `b6eca22f69`, base H-9 commit `9365975c2d`).
- **Did it compile with pool merged? YES.** `mvn -o -q -pl :ditto-internal-utils-persistence-r2dbc -am test-compile` exited **0** on the merged tree (only benign JVM native-access / `Unsafe` warnings). Module output: **59 main + 69 test** `.class` files, including the merged `ConnectionPoolFactory`, `PostgresClientExtension`, `PoolMetricsPoller`, and the new `ConnectionPoolFactoryDdlTest` (which exercises `buildDdlOptions()` / `createDdlConnectionFactory()`).
- Only `ConnectionPoolFactory.java` required manual conflict resolution; `ditto-postgresql.conf` auto-merged as an additive union with no conflict markers.
- Working tree clean. **Still LOCAL — not pushed, not merged to `main` or `postgres-190626-feat-dev`.**

### Recommended next manual step

Run the full Postgres Testcontainers IT matrix (WU16 parity suite) against a **fresh, un-pre-seeded** PG instance on the `pg-r2-integration-final` HEAD (`fd5e6b2206`) — H-1 schema bootstrap + DDL credentials are now wired, so this is the first run that can validate auto-creation of journal/snapshot/`_journal_seq` tables and real entity traffic through the event + snapshot adapter seams end-to-end. Only after that matrix is green should a PR be opened.
