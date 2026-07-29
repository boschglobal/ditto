# Cleanup Loose-Index-Scan Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pathological `DISTINCT ON` newest-per-pid queries (C1 bench: PG 887 ms vs Mongo 42 ms, 21×) with two-phase LATERAL loose-index-scan queries, drop the now-redundant snaps `(pid, sn DESC)` index, and prove the fix with a full-scale bench re-run.

**Architecture:** Phase 1 of each rewritten query selects winner pids using only index columns (index-only PK scan, early-stopping at the page LIMIT); phase 2 fetches exactly the winning rows via per-pid backward PK scans inside a `CROSS JOIN LATERAL`. Semantics (Mongo parity: pre-grouping age/regex filters, `written_at` tiebreak, Java-side DELETED filtering, raw-page cursor) are pinned by existing ITs plus one new active-min-age parity test written BEFORE the rewrite.

**Tech Stack:** Java 17-level sources on OpenJDK 25, r2dbc-postgresql (production, `$n` binds with reuse), JDBC (bench, positional `?` binds), JUnit 4 + Testcontainers ITs, Maven.

**Spec:** `docs/superpowers/specs/2026-07-13-cleanup-loose-index-scan-design.md`

## Global Constraints

- Maven binary: `/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn` (no wrapper). Export `MVN` for the bench script.
- Run all commands from repo root `/Users/sta1sf3/Develop/projects/Bosch/ditto-ws/ditto_feat__postgres-persistance` (branch `feat/postgres-persistance`).
- PostgreSQL floor is 16 — no PG-18 native skip scan; the LATERAL emulation is the design.
- Behavior parity is non-negotiable: NO existing test's assertions may be loosened. Only the SQL shape, the mock-SQL markers, and comments that describe the SQL shape may change in tests.
- License headers: existing files keep their original year; no new files are created by this plan except none — all edits touch existing files.
- Every commit message ends with BOTH trailers:
  `Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>`
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- Do not run `bd` (beads was removed from this repo).
- Docker must be running for ITs (Testcontainers) and for Task 7 (bench containers `ditto-persistence-bench-pg`/`-mongo` on ports 55433/57017).

---

### Task 1: Pin the active-min-age parity semantics with a new IT (before touching the query)

The rewrite's riskiest parity point: with an ACTIVE age filter, (a) the winner is the newest row
*older than the cutoff* — not the overall newest — and (b) a pid whose rows are ALL younger than
the cutoff consumes no page slot and must not terminate pagination early. No existing test covers
minAge > 0. This test passes against the CURRENT query (it pins existing behavior) and becomes the
tripwire for the rewrite: with batchSize=1, a rewrite that filters only in phase 2 returns an empty
first page and truncates the stream (assertion (b) fails); one that filters only in phase 1 returns
the fresh sn=2 row (assertion (a) fails).

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/PostgresReadJournalPaginationIT.java`

**Interfaces:**
- Consumes: `readJournal.getNewestSnapshotsAbove(String lowerBoundPid, int batchSize, boolean includeDeleted, Duration minAgeFromNow, Materializer mat)` → `Source<SnapshotEntry, NotUsed>`; `snapshots.saveAsync(SnapshotMetadata, String json)` (existing IT fixtures).
- Produces: test `activeMinAgeUsesNewestQualifyingRowAndSkipsFreshOnlyPids` — later tasks must keep it green.

- [ ] **Step 1: Add the import**

In `PostgresReadJournalPaginationIT.java`, add to the imports (alphabetical position among the `org.eclipse.ditto` imports):

```java
import org.eclipse.ditto.internal.utils.persistence.api.SnapshotEntry;
```

- [ ] **Step 2: Add the test method**

Append this test method before the closing brace of the class:

```java
    @Test
    public void activeMinAgeUsesNewestQualifyingRowAndSkipsFreshOnlyPids() throws Exception {
        // Mongo-parity STAGE ORDER: the min-age predicate filters ROWS before the newest-per-pid
        // grouping. Two consequences this test pins, with batchSize=1 so slot accounting is visible:
        //  (a) a pid with an old sn=1 and a fresh sn=2 surfaces sn=1 — the newest QUALIFYING row;
        //  (b) a pid whose rows are ALL fresh consumes no page slot and must not end pagination early
        //      ("aa" sorts before "zz", so a phase-2-only filter would emit an empty first page and
        //      truncate the stream before reaching "zz").
        await(snapshots.saveAsync(new SnapshotMetadata("thing:agefilter:aa-freshonly", 1L,
                System.currentTimeMillis()), "{\"v\":\"fresh\"}"));
        await(snapshots.saveAsync(new SnapshotMetadata("thing:agefilter:zz-mixed", 1L, 1000L),
                "{\"v\":\"old\"}"));
        await(snapshots.saveAsync(new SnapshotMetadata("thing:agefilter:zz-mixed", 2L,
                System.currentTimeMillis()), "{\"v\":\"fresh\"}"));

        final List<SnapshotEntry> entries =
                readJournal.getNewestSnapshotsAbove("", 1, true, Duration.ofHours(1), mat)
                        .filter(e -> e.getPid().filter(p -> p.startsWith("thing:agefilter:")).isPresent())
                        .runWith(Sink.seq(), mat)
                        .toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertThat(entries).as("fresh-only pid must not appear; mixed pid must appear once").hasSize(1);
        assertThat(entries.get(0).getPid()).contains("thing:agefilter:zz-mixed");
        assertThat(entries.get(0).getSequenceNumber().getAsLong())
                .as("the newest QUALIFYING (old-enough) row wins, not the overall-newest fresh row")
                .isEqualTo(1L);
    }
```

- [ ] **Step 3: Run the test — expect PASS against the current DISTINCT ON query**

```bash
cd /Users/sta1sf3/Develop/projects/Bosch/ditto-ws/ditto_feat__postgres-persistance
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc verify \
  -Dit.test=PostgresReadJournalPaginationIT -DfailIfNoTests=false
```

Expected: `PostgresReadJournalPaginationIT` runs (needs Docker) and ALL its tests PASS, including the new one. This proves the test pins current behavior, not new behavior. If it FAILS, stop — the parity understanding is wrong; re-read the spec §3.1 before proceeding.

- [ ] **Step 4: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/PostgresReadJournalPaginationIT.java
git commit -m "test(persistence-r2dbc): pin active-min-age newest-per-pid parity semantics

Pins (a) newest-QUALIFYING-row wins under an active age filter and (b) a
fresh-only pid consumes no page slot — the two properties the upcoming
loose-index-scan rewrite of getNewestSnapshotsAbove must preserve.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Rewrite `getNewestSnapshotsAbove` to the two-phase LATERAL shape

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/ops/PostgresPersistenceOperations.java:493-528` (javadoc + method body)
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournal.java:216-226` (javadoc wording only)
- Modify: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/SnapshotTieBreakIT.java` (comment wording only — lines 52, 106, 119 reference the old shape)

**Interfaces:**
- Consumes: existing `querySource(String sql, Consumer<Statement> binder, Function<Row,T> mapper)`; `PostgresPersistenceOperations::mapSnapshotRow` (reads columns `pid, sn, snapshot, lifecycle, written_at` by name).
- Produces: `getNewestSnapshotsAbove(String lowerBoundPid, int batchSize, Duration minAgeFromNow, String pidFilterRegex)` — signature, bind order ($1..$5) and returned columns UNCHANGED; only the SQL text changes. Callers (`PostgresReadJournal.newestSnapshots`) need no edits.

- [ ] **Step 1: Replace the SQL in `getNewestSnapshotsAbove`**

In `PostgresPersistenceOperations.java`, replace the current method body's SQL (the
`querySource("SELECT pid, sn, snapshot, lifecycle, written_at FROM (" + "SELECT DISTINCT ON (pid) …` call)
so the whole method reads:

```java
    public Source<SnapshotRow, NotUsed> getNewestSnapshotsAbove(final String lowerBoundPid, final int batchSize,
            final java.time.Duration minAgeFromNow, final String pidFilterRegex) {
        final boolean noAgeFilter = minAgeFromNow.isZero();
        final String interval = minAgeFromNow.toSeconds() + " seconds";
        return querySource("SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM ("
                        + "SELECT pid FROM " + tables.snapsTable()
                        + " WHERE pid > $1"
                        + " AND ($2 = '' OR pid ~ $2)"
                        + " AND ($3 OR written_at < now() - $4::interval)"
                        + " GROUP BY pid ORDER BY pid LIMIT $5"
                        + ") p CROSS JOIN LATERAL ("
                        + "SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM "
                        + tables.snapsTable()
                        + " WHERE pid = p.pid"
                        + " AND ($3 OR written_at < now() - $4::interval)"
                        + " ORDER BY sn DESC, written_at DESC LIMIT 1"
                        + ") s ORDER BY p.pid",
                stmt -> {
                    stmt.bind(0, lowerBoundPid);
                    stmt.bind(1, pidFilterRegex);
                    stmt.bind(2, noAgeFilter);
                    stmt.bind(3, interval);
                    stmt.bind(4, batchSize);
                },
                PostgresPersistenceOperations::mapSnapshotRow);
    }
```

Note the binder lambda is byte-identical to today — `$2/$3/$4` are simply referenced twice in the
SQL (the PostgreSQL extended protocol allows parameter reuse; `$2` is already reused in the current
query).

- [ ] **Step 2: Update the method's javadoc**

Replace the existing javadoc block above the method (the one that starts
`One PAGE of newest-snapshot-per-pid rows above an (exclusive) lower-bound pid`) with:

```java
    /**
     * One PAGE of newest-snapshot-per-pid rows above an (exclusive) lower-bound pid, in pid order —
     * as a two-phase loose index scan: phase 1 selects the page's winner pids touching ONLY
     * PK-index columns (index-only scan + LIMIT, early-stopping like Mongo's IXSCAN), phase 2
     * fetches exactly one row per winner via a backward PK scan ({@code ORDER BY sn DESC,
     * written_at DESC LIMIT 1}), so only the page's winning snapshot payloads are ever detoasted.
     * The former single-query {@code DISTINCT ON (pid)} shape seq-scanned and externally sorted
     * every payload above the lower bound per page (bench C1: 21× slower than Mongo at 250k pids).
     * <p>
     * Semantics mirror {@code MongoReadJournal.listNewestActiveSnapshotsByBatch} STAGE ORDER exactly:
     * the pid lower bound, the optional pid regex and the optional min-age filter apply to the ROWS
     * (pre-grouping) — hence the age predicate appears in BOTH phases: in phase 1 so a pid with no
     * qualifying row consumes no page slot, in phase 2 so the newest QUALIFYING row wins. Lifecycle/
     * DELETED filtering intentionally does NOT happen here — the caller filters AFTER the
     * newest-per-pid selection, so a pid whose NEWEST snapshot is DELETED is excluded entirely
     * instead of resurrecting an older live snapshot.
     * <p>
     * Age filter: skipped entirely when {@code minAgeFromNow.isZero()} (Mongo parity) — {@code written_at}
     * is bound from the app-server clock, so an unconditional {@code < now()} would hide fresh snapshots
     * under app-ahead-of-DB clock skew.
     *
     * @param pidFilterRegex POSIX-ERE pid filter, or {@code ""} for no filter.
     */
```

- [ ] **Step 3: Update the two comment sites that describe the old shape**

In `PostgresReadJournal.java`, in the `newestSnapshots` javadoc (line ~218), change
`DELETED filtering happens HERE, post-DISTINCT (Mongo post-$group parity)` to
`DELETED filtering happens HERE, post-newest-per-pid-selection (Mongo post-$group parity)`.

In `SnapshotTieBreakIT.java` line 52, change
`the query is {@code SELECT DISTINCT ON (pid) … ORDER BY pid, sn DESC, written_at DESC}.` to
`the per-pid winner is picked by {@code ORDER BY sn DESC, written_at DESC LIMIT 1} (loose index scan).`
At lines ~106 and ~119 replace the two comment mentions of `DISTINCT ON` with `the newest-per-pid pick`
(comment text only; assertions untouched).

- [ ] **Step 4: Run the pinning ITs**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc verify \
  -Dit.test='PostgresReadJournalPaginationIT,SnapshotTieBreakIT,PostgresParityMatrixIT,PostgresStreamingIT' \
  -DfailIfNoTests=false
```

Expected: BUILD SUCCESS; all four ITs pass — including Task 1's
`activeMinAgeUsesNewestQualifyingRowAndSkipsFreshOnlyPids`, `getNewestSnapshotsAbovePicksNewestWrittenAtOnPidSnTie`,
`deletedNewestSnapshotExcludesPidEntirely`, `minAgeZeroIncludesFutureWrittenAt`,
`snapshotFilterPidRegexIsApplied`, `newestSnapshotsPaginateAcrossPages`.

- [ ] **Step 5: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/ops/PostgresPersistenceOperations.java \
        internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournal.java \
        internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/SnapshotTieBreakIT.java
git commit -m "perf(persistence-r2dbc): loose-index-scan rewrite of getNewestSnapshotsAbove

Two-phase LATERAL: index-only pid page + per-pid top-1 backward PK scan.
Replaces the DISTINCT ON shape that seq-scanned + externally sorted every
snapshot payload above the lower bound per page (bench C1 flag: 21x).
Binds, signature and semantics unchanged; parity pinned by ITs.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Rewrite `getLatestJournalEntries` to the same shape (test-first)

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournalBackpressureTest.java` (mock-SQL markers + javadoc)
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/ops/PostgresPersistenceOperations.java:480-491` (javadoc + method)
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournal.java:143-150` (comment)

**Interfaces:**
- Consumes: `streamingQuerySource(String sql, Consumer<Statement> binder, Function<Row,T> mapper)` (binds positive per-statement `fetchSize` → server-side portal); `PostgresPersistenceOperations::mapJournalRow` (reads `pid, sn, seq, manifest, tags, event` by name).
- Produces: `getLatestJournalEntries()` — signature and returned columns unchanged; SQL marker for tests becomes `CROSS JOIN LATERAL`.

- [ ] **Step 1: Update the mock-SQL markers in the backpressure test (the failing test)**

In `PostgresReadJournalBackpressureTest.java`, in test
`getLatestJournalEntriesUsesSingleDistinctOnQueryNotPerPidNPlus1`:

- change `factory.onSql("DISTINCT ON (pid)", List.of(` to `factory.onSql("CROSS JOIN LATERAL", List.of(`
- change `assertThat(factory.executedContaining("DISTINCT ON (pid)"))` to `assertThat(factory.executedContaining("CROSS JOIN LATERAL"))`
- change the assertion description `"getLatestJournalEntries must use a single DISTINCT ON (pid) query"` to `"getLatestJournalEntries must use a single loose-index-scan (CROSS JOIN LATERAL) query"`
- rename the test method to `getLatestJournalEntriesUsesSingleLooseScanQueryNotPerPidNPlus1`
- in the class javadoc, replace the M-2 sentence
  `a single {@code SELECT DISTINCT ON (pid) ... ORDER BY pid, sn DESC} returns the newest event per pid.` with
  `a single loose-index-scan query (distinct-pid phase + per-pid top-1 {@code CROSS JOIN LATERAL}) returns the newest event per pid.`

The per-pid-N+1 guard assertion (`executedCountContaining("sn >= $2 AND sn <= $3 ORDER BY sn ASC") … isZero()`)
stays EXACTLY as is — the LATERAL's inner `WHERE pid = p.pid ORDER BY sn DESC LIMIT 1` lives inside the
single statement and must not match it.

- [ ] **Step 2: Run the test to verify it fails against the old SQL**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc test -Dtest=PostgresReadJournalBackpressureTest
```

Expected: FAIL — `getLatestJournalEntriesUsesSingleLooseScanQueryNotPerPidNPlus1` fails because the
executed SQL still contains `DISTINCT ON (pid)`, not `CROSS JOIN LATERAL`.

- [ ] **Step 3: Rewrite the production query**

In `PostgresPersistenceOperations.java`, replace `getLatestJournalEntries()` (method + javadoc) with:

```java
    /**
     * The newest journal event per pid as a two-phase loose index scan streamed via a server-side
     * portal: a distinct-pid phase (index-only PK scan — no payload detoast, no sort) feeding a
     * per-pid top-1 {@code CROSS JOIN LATERAL} backward PK scan, so exactly one event per pid is
     * ever detoasted. Replaces the former single {@code DISTINCT ON (pid) … ORDER BY pid, sn DESC}
     * scan, whose mixed-direction sort order no ASC-PK index can satisfy — it externally sorted the
     * ENTIRE journal including event payloads. Still NOT an N+1 per-pid replay (M-2): one statement.
     */
    public Source<JournalRow, NotUsed> getLatestJournalEntries() {
        return streamingQuerySource("SELECT p.pid, j.sn, j.seq, j.manifest, j.tags, j.event FROM ("
                        + "SELECT DISTINCT pid FROM " + tables.journalTable()
                        + ") p CROSS JOIN LATERAL ("
                        + "SELECT sn, seq, manifest, tags, event::text AS event FROM " + tables.journalTable()
                        + " WHERE pid = p.pid ORDER BY sn DESC LIMIT 1"
                        + ") j ORDER BY p.pid",
                null, PostgresPersistenceOperations::mapJournalRow);
    }
```

- [ ] **Step 4: Update the caller comment**

In `PostgresReadJournal.java` `getLatestJournalEntries` (line ~146), replace the comment

```java
        // The newest event per pid as a backend-neutral JournalEntry (pid + manifest + payload JSON), via a single
        // SELECT DISTINCT ON (pid) ... ORDER BY pid, sn DESC streaming scan — NOT an N+1 per-pid replay (M-2).
```

with

```java
        // The newest event per pid as a backend-neutral JournalEntry (pid + manifest + payload JSON), via a single
        // loose-index-scan streaming query (distinct-pid phase + per-pid top-1 LATERAL) — NOT an N+1 per-pid replay (M-2).
```

- [ ] **Step 5: Run the unit test to verify it passes, then the streaming/contract ITs**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc test -Dtest='PostgresReadJournalBackpressureTest,PostgresReadJournalContractTest'
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc verify -Dit.test=PostgresStreamingIT -DfailIfNoTests=false
```

Expected: BUILD SUCCESS on both; the renamed backpressure test now passes; `PostgresStreamingIT`
(real Testcontainers round-trip of `getLatestJournalEntries`) passes unchanged.

- [ ] **Step 6: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/ops/PostgresPersistenceOperations.java \
        internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournal.java \
        internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournalBackpressureTest.java
git commit -m "perf(persistence-r2dbc): loose-index-scan rewrite of getLatestJournalEntries

The DISTINCT ON shape ordered by (pid, sn DESC) — unsatisfiable by the ASC
PK index — externally sorted the entire journal including event payloads.
The LATERAL form streams index-only distinct pids + per-pid top-1 lookups
through the same server-side portal. Single statement, M-2 intact.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Drop the redundant snaps index from the schema (VERSION 2)

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/PostgresSchemaTest.java:86-93`
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/PostgresSchema.java` (VERSION constant line 60, `ddlStatements()` line 121, method `createSnapsPidSnIndex` lines 193-196)

**Interfaces:**
- Consumes: `PostgresSchema.ddlStatements()` list contract (stable order → deterministic checksum); `SchemaVerifier.verifyChecksum` upgrade rule (`storedVersion < currentVersion` ⇒ additive upgrade allowed, DDL re-runs idempotently).
- Produces: `PostgresSchema.VERSION == 2`; DDL list contains `DROP INDEX IF EXISTS <entity>_snaps_pid_sn_desc_idx` and NO `CREATE INDEX … _snaps_pid_sn_desc_idx`. Task 5 mirrors this end state in the bench loader.

- [ ] **Step 1: Update the schema unit test (the failing test)**

In `PostgresSchemaTest.java`, replace the test `snapshotTableHasThreeColumnPkAndPartialDeletedIndex` with:

```java
    @Test
    public void snapshotTableHasThreeColumnPkAndPartialDeletedIndex() {
        final String snaps = stmtContaining("things_snaps (");
        assertThat(snaps).contains("snapshot JSONB NOT NULL");
        assertThat(snaps).contains("PRIMARY KEY (pid, sn, written_at)");
        // v2: the (pid, sn DESC) index is GONE — it never satisfied the newest-per-pid sort (missing
        // written_at tiebreak) and the loose-index-scan queries left nothing needing it; the PK
        // (pid, sn, written_at) serves every snaps access path. v1 databases converge via DROP.
        assertThat(DDL).noneMatch(s -> s.startsWith("CREATE INDEX") && s.contains("_snaps_pid_sn_desc_idx"));
        assertThat(DDL).anyMatch(s -> s.equals("DROP INDEX IF EXISTS things_snaps_pid_sn_desc_idx"));
        assertThat(stmtContaining("things_snaps_lifecycle_idx")).contains("WHERE lifecycle = 'DELETED'");
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc test -Dtest=PostgresSchemaTest
```

Expected: FAIL — `noneMatch` is violated (the CREATE INDEX statement still exists) and no DROP statement is found.

- [ ] **Step 3: Implement the schema change**

In `PostgresSchema.java`:

1. Bump the version constant (line ~60) and document why:

```java
    /** Schema version 2: v1's (pid, sn DESC) snaps index dropped — redundant under loose-index-scan queries. */
    public static final int VERSION = 2;
```

2. In `ddlStatements()`, replace `statements.add(createSnapsPidSnIndex(entity));` with
   `statements.add(dropSnapsPidSnIndex(entity));` (same list position — keeps ordering deterministic).

3. Replace the `createSnapsPidSnIndex` method with:

```java
    private static String dropSnapsPidSnIndex(final String entity) {
        // v2 upgrade: the v1 (pid, sn DESC) index never satisfied the newest-per-pid sort (it lacks the
        // written_at tiebreak column) and the loose-index-scan rewrite left no query that needs it — the
        // PK (pid, sn, written_at) serves every snaps access path, including per-pid backward scans.
        return "DROP INDEX IF EXISTS " + entity + "_snaps_pid_sn_desc_idx";
    }
```

- [ ] **Step 4: Run the schema tests + one bootstrap-exercising IT**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc test -Dtest=PostgresSchemaTest
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc verify -Dit.test=SnapshotTieBreakIT -DfailIfNoTests=false
```

Expected: both BUILD SUCCESS. The IT proves a fresh bootstrap executes the DROP (no-op) cleanly
inside the bootstrap transaction and everything still works without the index.

- [ ] **Step 5: Verify no other reference to the dropped index remains**

```bash
grep -rn "snaps_pid_sn_desc_idx" --include="*.java" --include="*.md" --include="*.conf" \
  internal/ services/ deployment/ docs/ | grep -v "persistence-bench" | grep -v "docs/superpowers"
```

Expected output: ONLY the two files edited in this task (`PostgresSchema.java` with the DROP,
`PostgresSchemaTest.java` with the assertions). The persistence-bench hit is Task 5's job;
`docs/superpowers` hits are historical specs/reviews and stay. Any OTHER hit must be updated in
this step to reflect the drop.

- [ ] **Step 6: Commit**

```bash
git add internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/PostgresSchema.java \
        internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/PostgresSchemaTest.java
git commit -m "feat(persistence-r2dbc): schema v2 — drop redundant snaps (pid, sn DESC) index

The index never satisfied the newest-per-pid sort (missing written_at) and
the loose-index-scan rewrite leaves no query needing it; the 3-column PK
serves every snaps access path. One less index write per snapshot. v1 DBs
converge via idempotent DROP INDEX IF EXISTS at bootstrap (version 1 -> 2).

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Sync the bench to the new query shape and schema

**Files:**
- Modify: `internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/PersistenceShapes.java:86-95`
- Modify: `internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/CleanupShapeBench.java:53-71`
- Modify: `internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/SweepEngine.java:127-132`
- Modify: `internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/PgLoadBench.java:51-63`

**Interfaces:**
- Consumes: the JDBC convention in this module — positional `?` placeholders (no `$n` reuse), `BenchProtocol.inlineLiterals(String sql, Object... literals)` replacing each `?` in order.
- Produces: `PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE` with EIGHT placeholders in textual order: (1) lower-bound pid, (2) regex-empty check, (3) regex, (4) phase-1 age flag, (5) phase-1 interval, (6) LIMIT, (7) phase-2 age flag, (8) phase-2 interval. Both call sites bind all eight.

- [ ] **Step 1: Replace the shared SQL constant**

In `PersistenceShapes.java`, replace `PG_NEWEST_SNAPSHOTS_ABOVE` (and its preceding comment about
bind order) with:

```java
    /**
     * Production shape of PostgresPersistenceOperations.getNewestSnapshotsAbove — two-phase loose
     * index scan (JDBC ? placeholders cannot be reused, so the R2DBC original's $1..$5-with-reuse
     * becomes EIGHT positional binds: lowerBound, regexEmptyCheck, regex, ageFlag, interval, limit,
     * ageFlag again, interval again).
     */
    public static final String PG_NEWEST_SNAPSHOTS_ABOVE =
            "SELECT p.pid, s.sn, s.snapshot, s.lifecycle, s.written_at FROM ("
                    + "SELECT pid FROM things_snaps WHERE pid > ? AND (? = '' OR pid ~ ?) "
                    + "AND (? OR written_at < now() - ?::interval) "
                    + "GROUP BY pid ORDER BY pid LIMIT ?"
                    + ") p CROSS JOIN LATERAL ("
                    + "SELECT sn, snapshot::text AS snapshot, lifecycle, written_at FROM things_snaps "
                    + "WHERE pid = p.pid AND (? OR written_at < now() - ?::interval) "
                    + "ORDER BY sn DESC, written_at DESC LIMIT 1"
                    + ") s ORDER BY p.pid";
```

- [ ] **Step 2: Extend the binds at both call sites**

In `CleanupShapeBench.java` `c1PidStreamPage` (after the existing `ps.setInt(6, …)` line), add:

```java
                    ps.setBoolean(7, true);          // phase-2 age filter disabled (mirrors bind 4)
                    ps.setString(8, "0 seconds");
```

and extend the EXPLAIN literal call to eight literals:

```java
                plans.append("### PG plan (mid-corpus lower bound)\n\n").append(BenchProtocol.pgExplain(c,
                        BenchProtocol.inlineLiterals(PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE,
                                mid, "", "", true, "0 seconds", PersistenceShapes.READS_PER_QUERY,
                                true, "0 seconds")));
```

In `SweepEngine.java` `sweepPg` (after `page.setInt(6, PersistenceShapes.READS_PER_QUERY);`), add:

```java
                page.setBoolean(7, true);
                page.setString(8, "0 seconds");
```

- [ ] **Step 3: Mirror the schema change in the bulk loader**

In `PgLoadBench.java` `POST_LOAD`, delete the line

```java
            "CREATE INDEX things_snaps_pid_sn_desc_idx ON things_snaps (pid, sn DESC)",
```

(the class comment already promises "end state matches PostgresSchema.ddlStatements()" — that
statement list no longer creates the index).

- [ ] **Step 4: Compile the bench module and run its unit test**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-bench test -Dtest=CorpusGeneratorTest -DfailIfNoTests=false
```

Expected: BUILD SUCCESS (compiles all bench sources; `CorpusGeneratorTest` passes).

- [ ] **Step 5: Commit**

```bash
git add internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/PersistenceShapes.java \
        internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/CleanupShapeBench.java \
        internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/SweepEngine.java \
        internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/PgLoadBench.java
git commit -m "feat(persistence-bench): sync C1/sweep shape + loader to loose-index-scan rewrite

PG_NEWEST_SNAPSHOTS_ABOVE now mirrors the production two-phase LATERAL query
(8 JDBC binds); PgLoadBench end state matches schema v2 (no snaps DESC index).

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Full-module verification

**Files:** none modified (verification only; fix-forward anything this uncovers within the task).

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: green `verify` for the touched modules — the gate for the bench re-run.

- [ ] **Step 1: Run the full persistence-r2dbc module (unit + all ITs, Docker required)**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc verify
```

Expected: BUILD SUCCESS. All unit tests and ITs pass (pagination, parity matrix, tie-break,
streaming, backpressure, schema, ops).

- [ ] **Step 2: Build the dependent extension + bench modules**

```bash
/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn \
  -pl internal/utils/persistence-r2dbc,internal/utils/persistence-r2dbc-extension,internal/utils/persistence-bench \
  install -DskipTests -q
```

Expected: BUILD SUCCESS (locally installs the rewritten artifacts the bench script's `build` stage
would otherwise rebuild; catches any cross-module compile break).

- [ ] **Step 3: Commit (only if fixes were needed)**

If Steps 1-2 required source fixes, commit them with a message describing the actual fix, ending in
the two standard trailers. If everything was already green, there is nothing to commit.

---

### Task 7: Full-scale bench re-run + evidence + README refresh (the proof)

This is a multi-hour stage (corpus reload on both backends + sweep). Both backends are re-run so
every overwritten evidence file keeps symmetric PG/Mongo columns from the SAME corpus generation.
The `write` stage precedes `cleanup`/`sweep` per the README's ordering rules (W1/W3/M1 insert
deterministic probe pids — a reload must precede a re-run of `write`/`mixed`).

**Files:**
- Regenerated: `internal/utils/persistence-bench/src/test/resources/bench/results/*.md` (load-pg, load-mongo, r1-r3, w1-w3, c1-c4, m1)
- Modify: `internal/utils/persistence-bench/benchmark/README.md` (§5 headline table + flag lines, sweep-bloat note if numbers moved)

**Interfaces:**
- Consumes: `internal/utils/persistence-bench/benchmark/run-benchmark.sh` stages; `MVN` env var override.
- Produces: committed evidence proving the C1 fix at full scale.

- [ ] **Step 1: Start containers, build, reload the corpus at full scale (both backends)**

```bash
cd /Users/sta1sf3/Develop/projects/Bosch/ditto-ws/ditto_feat__postgres-persistance
export MVN=/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn
internal/utils/persistence-bench/benchmark/run-benchmark.sh up build
internal/utils/persistence-bench/benchmark/run-benchmark.sh load --count 250000 --seed 42
```

Expected: both containers up; corpus loaded (`status` shows ~34.75M journal rows per backend).
Duration: roughly the same as the previous 250k load. Disk: ~30 GB steady, peaks ~80 GB during the
later sweep — verify free space first with `df -h`.

- [ ] **Step 2: Run the scenario stages in README order**

```bash
internal/utils/persistence-bench/benchmark/run-benchmark.sh recovery --count 250000 --seed 42
internal/utils/persistence-bench/benchmark/run-benchmark.sh write    --count 250000 --seed 42
internal/utils/persistence-bench/benchmark/run-benchmark.sh cleanup  --count 250000 --seed 42
internal/utils/persistence-bench/benchmark/run-benchmark.sh sweep    --count 250000 --seed 42
internal/utils/persistence-bench/benchmark/run-benchmark.sh mixed    --count 250000 --seed 42
```

Expected: each stage completes and rewrites its evidence files under
`internal/utils/persistence-bench/src/test/resources/bench/results/`. Immediately after `cleanup`,
inspect the fresh `c1-pid-stream-page.md`:
- PG p50/p95 in the low single-digit ms (success bar from the spec: p95 ≤ 42 ms);
- the PG plan shows Nested Loop + index scans, NO `Sort Method: external merge`, NO `temp read/written`,
  buffers proportional to the page (hundreds), not the table (was 181,397).
If the C1 bar is missed, STOP the run and debug the plan (capture `EXPLAIN (ANALYZE, BUFFERS)` output
from the evidence file) before burning hours on `sweep`.

- [ ] **Step 3: Refresh the curated README §5**

In `internal/utils/persistence-bench/benchmark/README.md` §5, using ONLY numbers from the fresh
evidence files:
- update the run date line (`run 2026-07-13` → the actual re-run date) and, if it moved, the row-count line;
- replace the **C1** row: new PG p95, keep Mongo p95, verdict becomes e.g. `PG faster` / `parity`
  (whichever the numbers say) — the `FLAG: 21×` text and bold markers go away;
- update the **C3** row (sweep total should drop — its page scans dominated PG's 875 s) and the
  C3 deleted-row counts in the "Cross-backend determinism" paragraph if they changed;
- update **C4**, **M1**, **W1-W3**, **R1-R3** rows from the fresh evidence (expected: W2 neutral or
  slightly better after the index drop; recovery rows unchanged within noise);
- if C4's PG flag still stands (dead-space replays pre-vacuum — NOT addressed by this rewrite),
  keep its flag row as is with fresh numbers.
- add one sentence at the end of the §5 intro paragraph:
  `C1's former 21× flag was closed by the loose-index-scan rewrite (see docs/superpowers/specs/2026-07-13-cleanup-loose-index-scan-design.md).`

Then check for stale shape descriptions elsewhere in the README:

```bash
grep -n "DISTINCT" internal/utils/persistence-bench/benchmark/README.md
```

Expected: update any hit that describes the OLD C1/pid-stream shape (e.g. in §4 Method or §8
storyline) to say "loose index scan (two-phase LATERAL)"; hits describing Mongo pipelines stay.

- [ ] **Step 4: Commit evidence + README**

```bash
git add internal/utils/persistence-bench/src/test/resources/bench/results/ \
        internal/utils/persistence-bench/benchmark/README.md
git commit -m "docs(persistence-bench): full-scale re-run evidence after loose-index-scan rewrite

C1 pid-stream page re-measured at count=250000 on the rewritten query
(former FLAG: 21x vs Mongo); C3/C4/M1 + write/recovery refreshed on the
same corpus generation for symmetric evidence; README section 5 updated.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Report the before/after to the user**

Present a short table: C1 p50/p95 before (526/887) vs after, Mongo (19.6/42.5) unchanged; C3 sweep
total before (875 s) vs after; plan excerpt showing the Nested Loop + zero temp usage. Ask whether
to also update the status docs/memory per the session-end convention.
