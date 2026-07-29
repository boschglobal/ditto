# Postgres Persistence Review-Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 15 verified findings (14 CONFIRMED, 1 PLAUSIBLE) from the 2026-07-02 max-effort review of branch `feat/postgres-persistance`, in blast-radius order: read-journal Mongo-parity first, then write-path error semantics, event-adapter bindings, profile completeness, the Mongo-path streaming regression, and the remaining hardening items.

**Architecture:** All fixes stay inside the existing seam: SQL/pagination fixes in `PostgresPersistenceOperations` + `PostgresReadJournal` (mirroring `MongoReadJournal`'s `unfoldBatchedSource` contract), error-classification in `PostgresJournalOps`, HOCON fixes in `ditto-postgres-persistence.conf`, and two targeted changes on the backend-neutral side (`SnapshotStreamingActor` props, `PersistenceBackendSelfCheck`). No interface signature changes visible to the three services.

**Tech Stack:** Java (Pekko 1.6 actors/streams, Project Reactor, R2DBC r2dbc-postgresql), HOCON/Typesafe config, JUnit4 + AssertJ + Testcontainers (PG 16), Maven.

## Global Constraints

- Maven binary: `/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn` (call it `$MVN` below; no wrapper exists).
- The `git` command is shadowed by a broken shell alias in non-interactive shells — ALWAYS invoke as `command git`.
- Integration tests (`*IT`) need a running Docker daemon (Testcontainers PG 16). They self-skip when Docker is absent (`Assume.assumeNoException` pattern) — a skipped IT is NOT a pass for verification purposes; run with Docker up.
- Module verify commands (run from repo root):
  - r2dbc module: `$MVN -pl internal/utils/persistence-r2dbc -am verify -DskipITs=false -q`
  - persistence-api: `$MVN -pl internal/utils/persistence-api -am verify -q`
  - mongo persistence: `$MVN -pl internal/utils/persistence -am verify -q`
- License headers: every NEW file gets the EPL-2.0 header with `Copyright (c) 2026 Contributors to the Eclipse Foundation`; existing files KEEP their original year (never bump).
- All dependency versions are managed in `bom/pom.xml`; never add a literal `<version>` to a module POM.
- Every commit message ends with BOTH trailers, in this order:
  ```
  Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  ```
- Out of scope for this plan (tracked separately): event-adapter dedup refactor (4 duplicated pairs), orphan `PostgresSnapshotAdapter` removal, dead `DittoReadJournal` surface pruning, write-path efficiency (CTE single round-trip), index additions, Kamon timer caching.

---

### Task 1: Cursor-paginated pid streaming (fixes truncation of `getJournalPids*`)

The single most damaging finding: `PostgresReadJournal.getJournalPids` / `getJournalPidsAbove` / `getJournalPidsAboveWithTag` run ONE `LIMIT batchSize` query and complete, while the `DittoReadJournal` contract (see `MongoReadJournal.unfoldBatchedSource`, `internal/utils/persistence/.../streaming/MongoReadJournal.java:872-892`) streams ALL pids using batchSize only as the page size. Consumers (cleanup pid iteration, `SudoStreamPids`) silently see only the first page.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournal.java`
- Test: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/PostgresReadJournalPaginationIT.java` (new)

**Interfaces:**
- Consumes: `PostgresPersistenceOperations.getPidsAbove(String lowerBoundPid, int batchSize)` and `getPidsAboveWithTag(String lowerBoundPid, String tag, int batchSize)` — unchanged.
- Produces: `private static <T> Source<List<T>, NotUsed> unfoldBatchedSource(String lowerBoundPid, Materializer mat, Function<T, String> seedCreator, Function<String, Source<T, NotUsed>> pageCreator)` in `PostgresReadJournal` — Task 2 reuses this helper verbatim.

- [ ] **Step 1: Write the failing IT**

Create `PostgresReadJournalPaginationIT.java` following the exact harness pattern of `PostgresStreamingIT` (same `@BeforeClass` container start with `Assume.assumeNoException`, same pool/client/ops/readJournal construction with `PostgresPersistenceOperations.of(client, "things")`, same `@AfterClass`, same `await` helper — copy lines 68-114 of `PostgresStreamingIT.java` adapting the class/pool name):

```java
@Test
public void getJournalPidsStreamsAllPidsAcrossPages() throws Exception {
    // 7 pids, batch size 3 -> 3 pages (3+3+1). Pre-fix: only the first 3 pids arrive.
    for (int i = 1; i <= 7; i++) {
        await(operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(
                "thing:page:p" + i, 1L, "manifest", List.of(), "{\"n\":" + i + "}"))).toFuture());
    }
    final List<String> pids = readJournal.getJournalPids(3, Duration.ofSeconds(10), mat)
            .filter(pid -> pid.startsWith("thing:page:"))
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).containsExactly("thing:page:p1", "thing:page:p2", "thing:page:p3",
            "thing:page:p4", "thing:page:p5", "thing:page:p6", "thing:page:p7");
}

@Test
public void getJournalPidsAboveResumesFromLowerBound() throws Exception {
    // reuses the 7 pids from the test above if ordering is not guaranteed, insert independently:
    for (int i = 1; i <= 5; i++) {
        await(operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(
                "thing:above:a" + i, 1L, "manifest", List.of(), "{}"))).toFuture());
    }
    final List<String> pids = readJournal.getJournalPidsAbove("thing:above:a2", 2, mat)
            .filter(pid -> pid.startsWith("thing:above:"))
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).containsExactly("thing:above:a3", "thing:above:a4", "thing:above:a5");
}
```

Note: `JournalInsert` is `public record JournalInsert(String pid, long sequenceNr, String manifest, List<String> tags, String eventJson)` (`PostgresPersistenceOperations.java:712`); `insertEvents` returns a `Mono<Void>` — bridge with `.toFuture()`.

- [ ] **Step 2: Run the IT to verify it fails**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -Dit.test=PostgresReadJournalPaginationIT -DskipITs=false -q`
Expected: FAIL — `getJournalPidsStreamsAllPidsAcrossPages` gets only `p1,p2,p3` (single page).

- [ ] **Step 3: Implement the unfold helper and rewire the three pid methods**

In `PostgresReadJournal.java`, add imports:

```java
import java.util.List;
import java.util.function.Function;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Attributes;
import org.apache.pekko.stream.javadsl.Sink;
```

Add the helper (mirror of `MongoReadJournal.unfoldBatchedSource`):

```java
/**
 * Streams the FULL result set by repeating a bounded page query until an empty page arrives —
 * the exact contract of {@code MongoReadJournal#unfoldBatchedSource}: {@code batchSize} is the page size,
 * never a cap on the total. The seed of the next page is derived from the LAST element of the raw page
 * (before any caller-side filtering), so filtered-out trailing rows cannot stall the cursor.
 */
private static <T> Source<List<T>, NotUsed> unfoldBatchedSource(final String lowerBoundPid,
        final Materializer mat,
        final Function<T, String> seedCreator,
        final Function<String, Source<T, NotUsed>> pageCreator) {
    return Source.unfoldAsync("",
                    startPid -> {
                        final String actualStart =
                                lowerBoundPid.compareTo(startPid) >= 0 ? lowerBoundPid : startPid;
                        return pageCreator.apply(actualStart)
                                .runWith(Sink.<T>seq(), mat)
                                .thenApply(page -> page.isEmpty()
                                        ? Optional.<Pair<String, List<T>>>empty()
                                        : Optional.of(Pair.create(
                                                seedCreator.apply(page.get(page.size() - 1)),
                                                (List<T>) page)));
                    })
            .withAttributes(Attributes.inputBuffer(1, 1));
}
```

Replace the three pid methods (currently lines 107-112, 136-139, 146-150):

```java
@Override
public Source<String, NotUsed> getJournalPids(final int batchSize, final Duration maxIdleTime,
        final Materializer mat) {
    return getJournalPidsAbove("", batchSize, mat);
}

@Override
public Source<String, NotUsed> getJournalPidsAbove(final String lowerBoundPid, final int batchSize,
        final Materializer mat) {
    // Cursor-paginated to exhaustion: batchSize is the page size, never a cap (Mongo parity).
    return unfoldBatchedSource(lowerBoundPid, mat, Function.identity(),
            start -> operations.getPidsAbove(start, batchSize))
            .mapConcat(page -> page);
}

@Override
public Source<String, NotUsed> getJournalPidsAboveWithTag(final String lowerBoundPid, final String tag,
        final int batchSize, final Materializer mat) {
    return unfoldBatchedSource(lowerBoundPid, mat, Function.identity(),
            start -> operations.getPidsAboveWithTag(start, tag, batchSize))
            .mapConcat(page -> page);
}
```

- [ ] **Step 4: Run the IT to verify it passes**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -Dit.test=PostgresReadJournalPaginationIT -DskipITs=false -q`
Expected: PASS (both tests).

- [ ] **Step 5: Run the whole r2dbc module (unit + IT) to catch regressions**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
command git add internal/utils/persistence-r2dbc
command git commit -m "fix(persistence-r2dbc): paginate pid streaming to exhaustion (Mongo parity)

getJournalPids/getJournalPidsAbove/getJournalPidsAboveWithTag returned a
single LIMIT-batchSize page and completed, silently truncating cleanup pid
iteration and SudoStreamPids to the first page. Mirror MongoReadJournal's
unfoldBatchedSource: repeat the bounded page query seeded by the last pid
until an empty page arrives.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `getNewestSnapshotsAbove` — pagination, post-DISTINCT DELETED filter, minAge-zero bypass, pidFilter

Four verified findings share this one query: (a) single-page truncation (starves cleanup, truncates search sync); (b) `lifecycle` filtered in WHERE **before** `DISTINCT ON (pid)` resurrects deleted entities; (c) `written_at < now() - interval` applied even for `Duration.ZERO` hides app-clock-ahead snapshots; (d) `SnapshotFilter.pidFilter()` (namespace regex) is silently dropped.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/ops/PostgresPersistenceOperations.java:474-489`
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/readjournal/PostgresReadJournal.java:162-182`
- Test: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/PostgresReadJournalPaginationIT.java` (extend Task 1's class)

**Interfaces:**
- Consumes: `unfoldBatchedSource(...)` from Task 1; `SnapshotRow` record `(String pid, long sequenceNr, @Nullable String lifecycle, Instant writtenAt, String snapshotJson)`; `SnapshotFilter` record `(String lowerBoundPid, String pidFilter, Duration minAgeFromNow)`.
- Produces: NEW ops signature `public Source<SnapshotRow, NotUsed> getNewestSnapshotsAbove(String lowerBoundPid, int batchSize, Duration minAgeFromNow, String pidFilterRegex)` — the `includeDeleted` boolean moves OUT of SQL into Java (post-DISTINCT filtering in the read journal, exactly like Mongo's post-$group $filter stage). The DittoReadJournal-facing overloads keep their signatures.

- [ ] **Step 1: Write the failing ITs (add to `PostgresReadJournalPaginationIT`)**

```java
@Test
public void newestSnapshotsPaginateAcrossPages() throws Exception {
    for (int i = 1; i <= 7; i++) {
        // timestamp 1000L = epoch past, safely below now() for the age filter
        await(snapshots.saveAsync(new SnapshotMetadata("thing:spage:s" + i, 1L, 1000L), "{\"n\":" + i + "}"));
    }
    final List<String> pids = readJournal.getNewestSnapshotsAbove("", 3, false, Duration.ZERO, mat)
            .filter(e -> e.getPid().filter(p -> p.startsWith("thing:spage:")).isPresent())
            .map(e -> e.getPid().orElseThrow())
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).hasSize(7);
}

@Test
public void deletedNewestSnapshotExcludesPidEntirely() throws Exception {
    // Older LIVE snapshot + newer DELETED snapshot: Mongo semantics = the pid must NOT appear at all.
    await(snapshots.saveAsync(new SnapshotMetadata("thing:del:d1", 50L, 1000L), "{\"__lifecycle\":\"ACTIVE\"}"));
    await(snapshots.saveAsync(new SnapshotMetadata("thing:del:d1", 60L, 2000L), "{\"__lifecycle\":\"DELETED\"}"));
    final List<String> pids = readJournal.getNewestSnapshotsAbove("", 100, false, Duration.ZERO, mat)
            .filter(e -> e.getPid().filter(p -> p.equals("thing:del:d1")).isPresent())
            .map(e -> e.getPid().orElseThrow())
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).as("deleted-newest pid must not be resurrected via its older live snapshot").isEmpty();
}

@Test
public void minAgeZeroIncludesFutureWrittenAt() throws Exception {
    // written_at bound from app clock: simulate app-ahead-of-DB skew with a future timestamp.
    final long future = System.currentTimeMillis() + 60_000L;
    await(snapshots.saveAsync(new SnapshotMetadata("thing:skew:f1", 1L, future), "{\"v\":1}"));
    final List<String> pids = readJournal.getNewestSnapshotsAbove("", 100, false, Duration.ZERO, mat)
            .filter(e -> e.getPid().filter(p -> p.equals("thing:skew:f1")).isPresent())
            .map(e -> e.getPid().orElseThrow())
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).as("Duration.ZERO must bypass the age filter (Mongo isZero() parity)").hasSize(1);
}

@Test
public void snapshotFilterPidRegexIsApplied() throws Exception {
    await(snapshots.saveAsync(new SnapshotMetadata("thing:org.acme:in1", 1L, 1000L), "{}"));
    await(snapshots.saveAsync(new SnapshotMetadata("thing:org.other:out1", 1L, 1000L), "{}"));
    final SnapshotFilter filter = SnapshotFilter.of("", "^thing:org\\.acme:.*");
    final List<String> pids = readJournal.getNewestSnapshotsAbove(filter, 100, mat)
            .map(e -> e.getPid().orElseThrow())
            .filter(p -> p.startsWith("thing:org."))
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).containsExactly("thing:org.acme:in1");
}
```

(Add `import org.eclipse.ditto.internal.utils.persistence.api.SnapshotFilter;` and a `snapshots` field constructed as in `PostgresStreamingIT.java:97`: `snapshots = PostgresSnapshotStoreOps.of(operations);`. If `SnapshotFilter.of(String, String)` has a different factory name, check `internal/utils/persistence-api/.../SnapshotFilter.java` — the record is `SnapshotFilter(String lowerBoundPid, String pidFilter, Duration minAgeFromNow)` and `SnapshotFilter.of(start, pidFilter)` hardcodes `Duration.ZERO` per `SnapshotFilter.java:32-34`.)

- [ ] **Step 2: Run to verify all four fail**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -Dit.test=PostgresReadJournalPaginationIT -DskipITs=false -q`
Expected: FAIL — pagination gets 3 of 7; deleted pid resurrects `sn=50`; future written_at row missing; both namespaces returned.

- [ ] **Step 3: Rewrite the ops query**

Replace `PostgresPersistenceOperations.getNewestSnapshotsAbove` (lines 474-489) with:

```java
/**
 * One PAGE of newest-snapshot-per-pid rows above an (exclusive) lower-bound pid, in pid order.
 * <p>
 * Semantics mirror {@code MongoReadJournal.listNewestActiveSnapshotsByBatch} STAGE ORDER exactly:
 * the pid lower bound, the optional pid regex and the optional min-age filter apply to the ROWS
 * (pre-grouping); lifecycle/DELETED filtering intentionally does NOT happen here — the caller filters
 * AFTER the newest-per-pid selection (post-{@code DISTINCT ON}), so a pid whose NEWEST snapshot is
 * DELETED is excluded entirely instead of resurrecting an older live snapshot.
 * <p>
 * Age filter: skipped entirely when {@code minAgeFromNow.isZero()} (Mongo parity) — {@code written_at}
 * is bound from the app-server clock, so an unconditional {@code < now()} would hide fresh snapshots
 * under app-ahead-of-DB clock skew.
 *
 * @param pidFilterRegex POSIX-ERE pid filter, or {@code ""} for no filter.
 */
public Source<SnapshotRow, NotUsed> getNewestSnapshotsAbove(final String lowerBoundPid, final int batchSize,
        final java.time.Duration minAgeFromNow, final String pidFilterRegex) {
    final boolean noAgeFilter = minAgeFromNow.isZero();
    final String interval = minAgeFromNow.toSeconds() + " seconds";
    return querySource("SELECT pid, sn, snapshot, lifecycle, written_at FROM ("
                    + "SELECT DISTINCT ON (pid) pid, sn, snapshot::text AS snapshot, lifecycle, written_at FROM "
                    + tables.snapsTable()
                    + " WHERE pid > $1"
                    + " AND ($2 = '' OR pid ~ $2)"
                    + " AND ($3 OR written_at < now() - $4::interval)"
                    + " ORDER BY pid, sn DESC, written_at DESC"
                    + ") newest ORDER BY pid LIMIT $5",
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

Delete the old 4-arg `(lowerBoundPid, batchSize, includeDeleted, minAgeFromNow)` overload — the compiler will point at every caller; they are all inside `PostgresReadJournal` plus tests.

- [ ] **Step 4: Rewire the three read-journal overloads**

Replace `PostgresReadJournal.getNewestSnapshotsAbove` overloads (lines 162-182) with a common private method (uses Task 1's `unfoldBatchedSource`; note the seed comes from the raw page BEFORE the deleted-filter, so a page whose trailing pids are all DELETED still advances the cursor):

```java
@Override
public Source<SnapshotEntry, NotUsed> getNewestSnapshotsAbove(final String lowerBoundPid, final int batchSize,
        final Materializer mat, final String... snapshotFields) {
    return newestSnapshots(lowerBoundPid, "", batchSize, false, Duration.ZERO, mat);
}

@Override
public Source<SnapshotEntry, NotUsed> getNewestSnapshotsAbove(final String lowerBoundPid, final int batchSize,
        final boolean includeDeleted, final Duration minAgeFromNow, final Materializer mat,
        final String... snapshotFields) {
    return newestSnapshots(lowerBoundPid, "", batchSize, includeDeleted, minAgeFromNow, mat);
}

@Override
public Source<SnapshotEntry, NotUsed> getNewestSnapshotsAbove(final SnapshotFilter snapshotFilter,
        final int batchSize, final Materializer mat, final String... snapshotFields) {
    return newestSnapshots(snapshotFilter.lowerBoundPid(), snapshotFilter.pidFilter(), batchSize, false,
            snapshotFilter.minAgeFromNow(), mat);
}

/**
 * Newest-per-pid snapshot streaming, paginated to exhaustion. DELETED filtering happens HERE,
 * post-DISTINCT (Mongo post-$group parity): a pid whose newest snapshot is DELETED is dropped
 * entirely. The pagination seed is taken from the raw page before filtering.
 */
private Source<SnapshotEntry, NotUsed> newestSnapshots(final String lowerBoundPid, final String pidFilterRegex,
        final int batchSize, final boolean includeDeleted, final Duration minAgeFromNow,
        final Materializer mat) {
    return unfoldBatchedSource(lowerBoundPid, mat, SnapshotRow::pid,
            start -> operations.getNewestSnapshotsAbove(start, batchSize, minAgeFromNow, pidFilterRegex))
            .mapConcat(page -> page.stream()
                    .filter(row -> includeDeleted || !"DELETED".equals(row.lifecycle()))
                    .map(PostgresReadJournal::toSnapshotEntry)
                    .collect(java.util.stream.Collectors.toList()));
}
```

Known limitation to note in the `newestSnapshots` javadoc: `snapshotFields` projection is still not pushed into SQL (efficiency follow-up, out of scope); the pid regex uses POSIX ERE (`~`), which covers the `^…(a|b)…` patterns `SnapshotStreamingActor.getSnapshotFilterFromCommand` generates.

- [ ] **Step 5: Fix compilation of other callers/tests**

Run: `$MVN -pl internal/utils/persistence-r2dbc test-compile -q` and fix every use of the deleted ops overload (tests such as `PostgresParityMatrixIT` / contract tests): a call `operations.getNewestSnapshotsAbove(x, n, includeDeleted, age)` becomes `operations.getNewestSnapshotsAbove(x, n, age, "")` — and if the test asserted DELETED filtering at ops level, move the assertion to the `readJournal` method instead.

- [ ] **Step 6: Run the ITs to verify all four pass**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -Dit.test=PostgresReadJournalPaginationIT -DskipITs=false -q`
Expected: PASS (all Task-1 + Task-2 tests).

- [ ] **Step 7: Run the whole module, then commit**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q` → BUILD SUCCESS.

```bash
command git add internal/utils/persistence-r2dbc
command git commit -m "fix(persistence-r2dbc): Mongo-parity newest-snapshot streaming

- paginate getNewestSnapshotsAbove to exhaustion (was one LIMIT page:
  starved cleanup, truncated search background-sync)
- filter DELETED lifecycle AFTER DISTINCT ON (pid): a pid whose newest
  snapshot is DELETED is excluded entirely instead of resurrecting an
  older live snapshot
- skip the written_at age filter for Duration.ZERO (app-clock skew hid
  fresh snapshots)
- honor SnapshotFilter.pidFilter() (namespace-scoped SudoStreamSnapshots
  streamed ALL namespaces)

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Empty journal tag = "match all" (fixes PersistencePingActor never pinging)

On Mongo, an empty tag skips the tag predicate entirely (`MongoReadJournal.java:894-901` adds the `$match` only `if (!tag.isEmpty())`; `getJournalPidsWithTag` javadoc: "or an empty string to select all journal entries"). The Postgres queries bind `''` into `tags @> ARRAY['']::text[]`, which matches nothing — so connectivity's `PersistencePingActor` (default `journal-tag = ""`, `connectivity.conf:908`) never wakes any connection after restart.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/ops/PostgresPersistenceOperations.java:357-413`
- Test: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/PostgresReadJournalPaginationIT.java` (extend)

**Interfaces:**
- Produces: unchanged signatures; `getPidsWithTag("", …)`, `getPidsWithTagOrderedByPriority("")` and `getPidsAboveWithTag(lb, "", n)` now return ALL pids.

- [ ] **Step 1: Write the failing IT**

```java
@Test
public void emptyTagMatchesAllPids() throws Exception {
    await(operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(
            "thing:tag:none", 1L, "manifest", List.of(), "{}"))).toFuture());
    await(operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(
            "thing:tag:tagged", 1L, "manifest", List.of("always-alive", "priority-3"), "{}"))).toFuture());

    // Empty tag -> ALL pids (Mongo parity: PersistencePingActor default journal-tag is "").
    final List<String> all = readJournal.getJournalPidsWithTagOrderedByPriorityTag("", Duration.ofSeconds(10))
            .filter(p -> p.startsWith("thing:tag:"))
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(all).containsExactlyInAnyOrder("thing:tag:none", "thing:tag:tagged");

    // Non-empty tag still filters.
    final List<String> tagged = readJournal.getJournalPidsWithTag("always-alive", 100,
                    Duration.ofSeconds(10), mat, false)
            .filter(p -> p.startsWith("thing:tag:"))
            .runWith(Sink.seq(), mat)
            .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(tagged).containsExactly("thing:tag:tagged");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -Dit.test=PostgresReadJournalPaginationIT -DskipITs=false -q`
Expected: FAIL — empty-tag query returns `[]`.

- [ ] **Step 3: Make the tag predicate conditional in all three queries**

In `PostgresPersistenceOperations`, change the tag predicates to the `($n = '' OR …)` pattern (keeps a single prepared statement shape, no dynamic SQL branching needed):

`getPidsWithTagOrderedByPriority` (line 357): change the CTE's WHERE to

```java
final String sql = "WITH newest AS ("
        + "  SELECT DISTINCT ON (pid) pid, tags FROM " + tables.journalTable()
        + "  WHERE ($1 = '' OR tags @> ARRAY[$1]::text[]) ORDER BY pid, sn DESC"
        + ") "
        + "SELECT n.pid AS pid FROM newest n "
        + "LEFT JOIN LATERAL ("
        + "  SELECT max((substring(tag from '^priority-([0-9]+)$'))::int) AS prio "
        + "  FROM unnest(n.tags) AS tag WHERE tag ~ '^priority-[0-9]+$'"
        + ") p ON true "
        + "ORDER BY COALESCE(p.prio, 0) DESC";
```

`getPidsWithTag` (line 374): both branches get the same guard —
`"... latest WHERE ($1 = '' OR latest.tags @> ARRAY[$1]::text[]) ORDER BY pid"` (considerOnlyLatest branch, note the DISTINCT ON subquery stays unchanged) and
`"SELECT DISTINCT pid FROM " + tables.journalTable() + " WHERE ($1 = '' OR tags @> ARRAY[$1]::text[]) ORDER BY pid"`.

`getPidsAboveWithTag` (line 405):
`" WHERE pid > $1 AND ($2 = '' OR tags @> ARRAY[$2]::text[]) ORDER BY pid LIMIT $3"`.

Update each method's javadoc: "An empty {@code tag} selects ALL pids (Mongo parity — the PersistencePingActor default journal-tag is empty)."

- [ ] **Step 4: Run IT to verify pass, run module, commit**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q` → BUILD SUCCESS.

```bash
command git add internal/utils/persistence-r2dbc
command git commit -m "fix(persistence-r2dbc): empty journal tag selects ALL pids (Mongo parity)

tags @> ARRAY['']::text[] matched no rows, so PersistencePingActor
(default journal-tag \"\") never pinged/woke any connection on Postgres.
Mongo skips the tag match for an empty tag; mirror that in the three
tag-filtered pid queries.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: SPI-correct write-error classification (no more silent event loss)

`PostgresJournalOps.recoverWrite` (line 119) maps every unclassified error — including transient connection loss — to a Pekko REJECTION (`Optional.of(...)`), which advances the sequence number and continues the actor: a silently lost event. The Pekko SPI is explicit: "Data store connection problems must not be signaled as rejections"; rejections are ONLY for serialization failures. Conversely, `toInsert`'s serialization `IllegalArgumentException` currently escapes `writeMessages` synchronously (whole-batch failure → actor stop → crash loop on retry, e.g. the U+0000/JSONB case).

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/journal/PostgresJournalOps.java:77-120`
- Test: `internal/utils/persistence-r2dbc/src/test/java/org/eclipse/ditto/internal/utils/persistence/postgres/journal/PostgresJournalOpsErrorMappingTest.java` (new unit test; check first whether a `PostgresJournalOpsTest` already exists in that package and extend it instead)

**Interfaces:**
- Consumes: `PostgresPersistenceOperations.insertEvents(List<JournalInsert>) : Mono<Void>`; `writeMessages(Iterable<AtomicWrite>) : CompletionStage<Iterable<Optional<Exception>>>` — signatures unchanged.
- Produces: new classification: store/transient errors → FAILED future (actor stops, backoff restarts); serialization failures (from `toInsert`) → per-write REJECTION (`Optional.of`).

- [ ] **Step 1: Write the failing unit tests**

Use Mockito (already on the module's test classpath — verify with `grep -n mockito internal/utils/persistence-r2dbc/pom.xml`; if absent it is bom-managed, add the dependency WITHOUT a version). Build a real `AtomicWrite` around a `PersistentRepr` with a String payload for the happy path and an unserializable payload for the rejection path:

```java
public final class PostgresJournalOpsErrorMappingTest {

    private final PostgresPersistenceOperations operations = mock(PostgresPersistenceOperations.class);
    private final PostgresJournalOps journalOps = PostgresJournalOps.of(operations);

    private static AtomicWrite atomicWrite(final Object payload) {
        final PersistentRepr repr = PersistentRepr.apply(payload, 1L, "thing:err:x", "", false,
                ActorRef.noSender(), "writer");
        return AtomicWrite.apply(repr);
    }

    @Test
    public void transientConnectionErrorFailsTheFuture() {
        when(operations.insertEvents(any())).thenReturn(Mono.error(
                new R2dbcNonTransientResourceException("connection closed")));
        final CompletionStage<Iterable<Optional<Exception>>> result =
                journalOps.writeMessages(List.of(atomicWrite("{\"k\":1}")));
        assertThatThrownBy(() -> result.toCompletableFuture().get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(R2dbcNonTransientResourceException.class);
    }

    @Test
    public void serializationFailureIsARejectionNotAnActorStop() throws Exception {
        // A payload toInsert cannot serialize (not JsonValue/CharSequence) must become a per-write
        // REJECTION (Optional.of), NOT a synchronous throw / failed future.
        final CompletionStage<Iterable<Optional<Exception>>> result =
                journalOps.writeMessages(List.of(atomicWrite(new Object())));
        final List<Optional<Exception>> slots = new ArrayList<>();
        result.toCompletableFuture().get(5, TimeUnit.SECONDS).forEach(slots::add);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0)).isPresent();
        assertThat(slots.get(0).get()).isInstanceOf(IllegalArgumentException.class);
        verify(operations, never()).insertEvents(any());
    }
}
```

(Imports: `org.apache.pekko.persistence.AtomicWrite`, `org.apache.pekko.persistence.PersistentRepr`, `org.apache.pekko.actor.ActorRef`, `io.r2dbc.spi.R2dbcNonTransientResourceException`, `reactor.core.publisher.Mono`, Mockito statics. If `PostgresPersistenceOperations` is final and unmockable, add a package-private seam instead: extract the two behaviors into package-private static `classifyWriteError(Throwable)` and test that directly — but try the mock first.)

- [ ] **Step 2: Run to verify both fail**

Run: `$MVN -pl internal/utils/persistence-r2dbc test -Dtest=PostgresJournalOpsErrorMappingTest -q`
Expected: FAIL — test 1 completes normally with a rejection slot; test 2 throws synchronously out of `writeMessages`.

- [ ] **Step 3: Implement the classification swap**

In `PostgresJournalOps`:

(a) `writeAtomic` (line 85): wrap the serialization loop so a `toInsert` failure becomes a rejection:

```java
private CompletionStage<Optional<Exception>> writeAtomic(final AtomicWrite atomicWrite) {
    final List<PostgresPersistenceOperations.JournalInsert> inserts = new ArrayList<>();
    try {
        for (final PersistentRepr repr : CollectionConverters.asJava(atomicWrite.payload())) {
            inserts.add(toInsert(repr));
        }
    } catch (final IllegalArgumentException serializationFailure) {
        // Pekko SPI: serialization failures ARE the rejection case — the message is not persisted,
        // the actor continues, and onPersistRejected logs it. Covers unbound payload types and
        // payloads PostgreSQL jsonb cannot store (e.g. U+0000), where Mongo/BSON would accept them.
        LOGGER.warn("Rejecting unserializable journal write for pid <{}>: {}",
                atomicWrite.persistenceId(), serializationFailure.getMessage());
        return CompletableFuture.completedFuture(Optional.of(serializationFailure));
    }
    return operations.insertEvents(inserts)
            .toFuture()
            .<Optional<Exception>>thenApply(ignored -> Optional.<Exception>empty())
            .exceptionallyCompose(throwable -> recoverWrite(atomicWrite, throwable));
}
```

(b) `recoverWrite` default arm (line 119): replace

```java
        return CompletableFuture.completedFuture(Optional.of(asException(cause)));
```

with

```java
        // Pekko AsyncWriteJournal SPI: "Data store connection problems must not be signaled as
        // rejections." Every unclassified store-side error (connection reset, pool disposed,
        // transient resource failures) is a lost write -> failed Future -> WriteMessageFailure ->
        // the actor stops and backoff-restarts, exactly like the Mongo plugin. Rejections are
        // reserved for serialization failures, which are classified in writeAtomic before the
        // insert is attempted.
        LOGGER.error("PostgreSQL write failed with an unclassified store error; treating as a lost "
                + "write that stops the actor.", cause);
        return CompletableFuture.failedFuture(asException(cause));
```

(c) Add a guard for U+0000 with a precise message where `toJsonText` produces the JSON text (find `toJsonText` in the same file; after the JSON text is obtained):

```java
        if (jsonText.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Journal payload for the PostgreSQL backend contains U+0000 "
                    + "(NUL), which PostgreSQL jsonb cannot store. Rejecting the write.");
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `$MVN -pl internal/utils/persistence-r2dbc test -Dtest=PostgresJournalOpsErrorMappingTest -q` → PASS.
Then the whole module: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q` → BUILD SUCCESS (existing recoverWrite tests may assert the OLD rejection behavior for transient errors — update those assertions to expect a failed future; that is the point of this task).

- [ ] **Step 5: Commit**

```bash
command git add internal/utils/persistence-r2dbc
command git commit -m "fix(persistence-r2dbc): SPI-correct write-error classification

Transient/unclassified store errors were returned as Pekko write
REJECTIONS, which advance the sequence number and continue the actor ->
silently lost events on e.g. connection loss. Per the AsyncWriteJournal
SPI, store errors now FAIL the future (actor stop + backoff restart,
Mongo parity) and rejections are reserved for serialization failures,
which now include a precise U+0000/jsonb guard instead of a synchronous
throw that crash-looped the actor.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Bind the base `Event` interface in all four Postgres journal blocks (fixes EmptyEvent crash)

The connections journal binds only `ConnectivityEvent` + `java.lang.String`; `EmptyEvent` (`internal/utils/persistent-actors/.../EmptyEvent.java:43`, `implements Event<EmptyEvent>`) is persisted by `ConnectionPersistenceActor` (lines 533-535 always-alive, 848-850 priority-update) and falls to the identity adapter → `toJsonText` rejection. Mongo binds the BASE interface (`connectivity.conf:1264`: `"org.eclipse.ditto.base.model.signals.events.Event" = mongodbobject`). Binding the base interface also restores the journal TAGS (`always-alive`, `priority-N`) that only the adapter's `Tagged` wrapping transfers.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/resources/ditto-postgres-persistence.conf` (all four `event-adapter-bindings` blocks: things ~line 128, policies ~156, connections ~183, wot ~211)
- Test: `internal/utils/persistence-r2dbc/src/test/java/.../PostgresProfileHoconLintTest.java` (find exact path with `command git ls-files | grep PostgresProfileHoconLintTest`)
- Test: `connectivity/service/src/test/java/org/eclipse/ditto/connectivity/service/messaging/persistence/ConnectivityPostgresEventAdapterEmptyEventTest.java` (new)

**Interfaces:**
- Consumes: `ConnectivityPostgresEventAdapter.toJournal(Object)` — must accept any `Event<?>` (it matches `event instanceof Event<?>` per `AbstractPostgresEventAdapter`); `EmptyEvent(String effect, long revision, DittoHeaders headers)` — check the actual constructor/factory in `EmptyEvent.java` before writing the test and use it verbatim.
- Produces: HOCON binding `"org.eclipse.ditto.base.model.signals.events.Event" = <adapter>` in each journal block.

- [ ] **Step 1: Write the failing lint assertion**

In `PostgresProfileHoconLintTest`, add (following the test's existing config-loading helpers):

```java
@Test
public void everyPostgresJournalBindsTheBaseEventInterface() {
    // EmptyEvent (always-alive / priority-update) implements only the base Event interface. Mongo
    // binds the base interface (connectivity.conf); without it Pekko's IdentityEventAdapter passes
    // the raw object to the JSONB write path, which rejects it.
    final Config profile = ConfigFactory.parseResources("ditto-postgres-persistence.conf").resolve();
    for (final String block : List.of("ditto-postgres-things-journal", "ditto-postgres-policies-journal",
            "ditto-postgres-connections-journal", "ditto-postgres-wot-journal")) {
        final Config bindings = profile.getConfig(block).getConfig("event-adapter-bindings");
        assertThat(bindings.hasPath("\"org.eclipse.ditto.base.model.signals.events.Event\""))
                .as("journal block <%s> must bind the base Event interface", block)
                .isTrue();
    }
}
```

- [ ] **Step 2: Write the failing adapter round-trip test**

In the new `ConnectivityPostgresEventAdapterEmptyEventTest` (connectivity service module — it can see both `EmptyEvent` and the adapter): read `EmptyEvent.java` first for the exact factory (the ConnectionPersistenceActor call sites at lines 533-535 construct it as `new EmptyEvent(EmptyEvent.EFFECT_ALWAYS_ALIVE, <revision>, <headers>)` — copy that construction), then:

```java
@Test
public void emptyEventSerializesToTaggedJsonb() {
    final ActorSystem system = ActorSystem.create("EmptyEventAdapterTest");
    try {
        final ConnectivityPostgresEventAdapter adapter =
                new ConnectivityPostgresEventAdapter(ExtendedActorSystem.class.cast(system));
        final DittoHeaders headers = DittoHeaders.newBuilder()
                .journalTags(Set.of("always-alive", "priority-3")).build();
        final EmptyEvent emptyEvent = new EmptyEvent(EmptyEvent.EFFECT_ALWAYS_ALIVE, 5L, headers);

        final Object journalEntry = adapter.toJournal(emptyEvent);

        assertThat(journalEntry).isInstanceOf(Tagged.class);
        final Tagged tagged = (Tagged) journalEntry;
        assertThat(tagged.tags()).contains("always-alive", "priority-3");
        assertThat(tagged.payload()).isInstanceOf(String.class); // JSONB text
    } finally {
        TestKit.shutdownActorSystem(system);
    }
}
```

(Adapt the adapter's constructor invocation to its real signature — check `ConnectivityPostgresEventAdapter.java`; Postgres event adapters take an `ExtendedActorSystem` like their Mongo twins. If the adapter constructor is package-private/different, use the same instantiation the existing `ConnectivityPostgresEventAdapter` tests use — `command git ls-files | grep -i ConnectivityPostgresEventAdapter` to find them.)

- [ ] **Step 3: Run both to verify they fail**

Run: `$MVN -pl internal/utils/persistence-r2dbc test -Dtest=PostgresProfileHoconLintTest -q` → the new lint test FAILS.
Run: `$MVN -pl connectivity/service test -Dtest=ConnectivityPostgresEventAdapterEmptyEventTest -q` → PASSES or FAILS depending on adapter tolerance; if the adapter itself rejects non-ConnectivityEvent payloads, that is a second gap — extend the adapter's `toJournal` to accept any `Event<?>` exactly as the Mongo adapter does (compare `ConnectivityMongoEventAdapter`).

- [ ] **Step 4: Add the base-Event binding to all four journal blocks**

In `ditto-postgres-persistence.conf`, in EACH of the four `event-adapter-bindings` blocks, add the base interface binding as the FIRST entry (Pekko resolves the MOST SPECIFIC matching binding, so the concrete `ThingEvent`/`PolicyEvent`/`ConnectivityEvent`/`WotValidationConfigEvent` bindings keep winning for domain events; the base binding is the fallback for `EmptyEvent`-style internal events — exactly Mongo's layout):

```hocon
  event-adapter-bindings {
    # Fallback for internal events implementing only the base Event interface (e.g. EmptyEvent used by
    # the always-alive/priority journal-tag path) — Mongo parity with connectivity.conf's base binding.
    "org.eclipse.ditto.base.model.signals.events.Event" = postgresjsonb
    "org.eclipse.ditto.connectivity.model.signals.events.ConnectivityEvent" = postgresjsonb
    "java.lang.String" = postgresjsonb
  }
```

(Analogous for the things (`postgresjsonb`), policies (`postgresjsonb`) and wot (`wotvalidation`) blocks — keep each block's existing adapter name.)

- [ ] **Step 5: Run tests to verify pass, run module, commit**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q` and `$MVN -pl connectivity/service test -Dtest=ConnectivityPostgresEventAdapterEmptyEventTest -q` → PASS.

```bash
command git add internal/utils/persistence-r2dbc connectivity/service
command git commit -m "fix(persistence-r2dbc): bind base Event interface in Postgres journals

EmptyEvent (always-alive/priority journal-tag path) implements only the
base Event interface; without a base binding it fell to Pekko's identity
adapter and crashed the JSONB write path, stopping the connection
persistence actor and dropping its journal tags. Mirror Mongo's base
Event binding in all four Postgres journal blocks.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Make the single-include profile contract true (auto-start + remember-entities)

The profile writes its auto-start lists under `ditto.persistence.*` — a path NOTHING reads (Pekko reads `pekko.persistence.*`) — and never switches `pekko.cluster.sharding.remember-entities-store` to `ddata`, so a single-include connectivity deployment keeps the eventsourced remember store. The pg-dev overlays hand-fix both, proving the gap.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/resources/ditto-postgres-persistence.conf:70-88`
- Modify: `things/service/src/main/resources/things-pg-dev.conf:52-54`, `policies/service/src/main/resources/policies-pg-dev.conf`, `connectivity/service/src/main/resources/connectivity-pg-dev.conf` (comment updates only; keep the narrowing overrides)
- Test: `PostgresProfileHoconLintTest` (extend)

**Interfaces:**
- Produces: the profile itself now sets `pekko.persistence.journal.auto-start-journals` / `pekko.persistence.snapshot-store.auto-start-snapshot-stores` (full 4-entity set, services may narrow) and `pekko.cluster.sharding.remember-entities-store = "ddata"`.

- [ ] **Step 1: Write the failing lint tests**

```java
@Test
public void profileAloneRedirectsPekkoAutoStartLists() {
    // The header promises: include this SINGLE file and be "responsible for nothing else".
    // Pekko reads pekko.persistence.*, never ditto.persistence.* — the profile must set the real path.
    final Config profile = ConfigFactory.parseResources("ditto-postgres-persistence.conf").resolve();
    assertThat(profile.getStringList("pekko.persistence.journal.auto-start-journals"))
            .contains("ditto-postgres-things-journal", "ditto-postgres-policies-journal",
                    "ditto-postgres-connections-journal", "ditto-postgres-wot-journal");
    assertThat(profile.getStringList("pekko.persistence.snapshot-store.auto-start-snapshot-stores"))
            .contains("ditto-postgres-things-snapshots", "ditto-postgres-policies-snapshots",
                    "ditto-postgres-connections-snapshots", "ditto-postgres-wot-snapshots");
    assertThat(profile.hasPath("ditto.persistence.journal")).as("dead ditto.persistence.* block removed")
            .isFalse();
}

@Test
public void profileSwitchesRememberEntitiesStoreToDdata() {
    final Config profile = ConfigFactory.parseResources("ditto-postgres-persistence.conf").resolve();
    assertThat(profile.getString("pekko.cluster.sharding.remember-entities-store")).isEqualTo("ddata");
}
```

- [ ] **Step 2: Run to verify both fail**

Run: `$MVN -pl internal/utils/persistence-r2dbc test -Dtest=PostgresProfileHoconLintTest -q` → FAIL.

- [ ] **Step 3: Fix the profile**

In `ditto-postgres-persistence.conf`, DELETE the whole `ditto { persistence { … } }` block (lines ~70-88, the `journal.auto-start-journals` / `snapshot-store.auto-start-snapshot-stores` lists under `ditto`) and add, at TOP LEVEL (outside `ditto { }`), immediately after the `ditto { extensions { … } }` section:

```hocon
# ---------------------------------------------------------------------------------------------------------------------
# Pekko wiring the single include must own (the header's "responsible for nothing else" contract).
#
# Auto-start: Pekko reads pekko.persistence.* (NOT ditto.persistence.*). The service configs hardwire the
# Mongo plugins there; this profile is included AFTER the service config (later include wins), so these lists
# replace the Mongo ones. The full 4-entity set keeps a generic include self-contained; a service overlay MAY
# narrow the lists to its own entity (see *-pg-dev.conf) — narrowing is an optimization, not a requirement.
# ---------------------------------------------------------------------------------------------------------------------
pekko.persistence {
  journal.auto-start-journals = [
    "ditto-postgres-things-journal",
    "ditto-postgres-policies-journal",
    "ditto-postgres-connections-journal",
    "ditto-postgres-wot-journal"
  ]
  snapshot-store.auto-start-snapshot-stores = [
    "ditto-postgres-things-snapshots",
    "ditto-postgres-policies-snapshots",
    "ditto-postgres-connections-snapshots",
    "ditto-postgres-wot-snapshots"
  ]
}

# Remember-entities: connectivity's default eventsourced store would persist Pekko-internal remember-entities
# events through a journal whose adapter bindings cannot encode them. The ddata store needs no journal at all;
# inert for things/policies (they do not enable remember-entities).
pekko.cluster.sharding.remember-entities-store = "ddata"
```

- [ ] **Step 4: Update stale overlay comments**

In `connectivity-pg-dev.conf` (~line 46-49): keep the `remember-entities-store = "ddata"` override but rewrite its comment to: `# Redundant with ditto-postgres-persistence.conf (which now sets ddata itself); kept as belt-and-braces for overlays that include this file without the profile.` In `things-pg-dev.conf`/`policies-pg-dev.conf` (~52-54): update the auto-start comment to say the override NARROWS the profile's 4-entity default to this service's entity (no longer "redirects" — the profile now does that). Also fix the include-order comment block (`things-pg-dev.conf:31`) that still says the profile "sets ditto.persistence.*.auto-start-* lists" → "sets pekko.persistence.*.auto-start-* lists".

- [ ] **Step 5: Run lint + module, commit**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q` → BUILD SUCCESS. (If `PostgresProfileHoconLintTest` has an existing assertion on the `ditto.persistence.*` lists, delete that assertion — the path is gone by design.)

```bash
command git add internal/utils/persistence-r2dbc things/service policies/service connectivity/service
command git commit -m "fix(persistence-r2dbc): make the single-include Postgres profile self-sufficient

The profile's auto-start lists lived under ditto.persistence.*, a path
nothing reads, so single-include deployments kept auto-starting the
Mongo plugins; and remember-entities-store was never switched, breaking
connectivity's shard remember-entities on Postgres. Move the lists to
pekko.persistence.*, add remember-entities-store=ddata, delete the dead
block, and demote the pg-dev overlay overrides to narrowing/belt-and-braces.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Self-check layer 3 — verify the EFFECTIVE pekko auto-start lists

`PersistenceBackendSelfCheck.verify` (line 98) only checks the PROVIDER-DECLARED plugin IDs, so the Task-6 class of misconfiguration (Mongo plugins still auto-starting under a Postgres provider) passed the boot check. Add a third layer: every plugin id in the effective `pekko.persistence.journal.auto-start-journals` and `...snapshot-store.auto-start-snapshot-stores` must resolve to a class of the active backend family.

**Files:**
- Modify: `internal/utils/persistence-api/src/main/java/org/eclipse/ditto/internal/utils/persistence/api/PersistenceBackendSelfCheck.java`
- Test: the existing self-check unit test (find with `command git ls-files | grep PersistenceBackendSelfCheck`) — extend.

**Interfaces:**
- Consumes: `provider.expectedPluginClassNames() : Set<String>` (`PersistenceBackendProvider.java:90`), the existing `resolvePluginClass(Config, String, BackendFamily, String)` helper, and `DittoConfigError`.
- Produces: `verify(...)` additionally validates the two effective auto-start lists; signature unchanged.

- [ ] **Step 1: Write the failing unit test**

In the existing self-check test class, add (mirroring its existing fixture style for building a fake provider + config):

```java
@Test
public void verifyFailsWhenEffectiveAutoStartListsWireTheOtherBackend() {
    // Provider = Postgres family, but the effective config still auto-starts a Mongo-classed plugin
    // (the exact single-include misconfiguration the profile fix addresses).
    final Config effective = ConfigFactory.parseString(
            "pekko.persistence.journal.auto-start-journals = [ \"pekko-contrib-mongodb-persistence-things-journal\" ]\n"
            + "pekko.persistence.snapshot-store.auto-start-snapshot-stores = []\n"
            + "\"pekko-contrib-mongodb-persistence-things-journal\".class = \"pekko.contrib.persistence.mongodb.MongoJournal\"\n"
            + "\"ditto-postgres-things-journal\".class = \"org.eclipse.ditto.internal.utils.persistence.postgres.journal.PostgresJournal\"\n"
            + "\"ditto-postgres-things-snapshots\".class = \"org.eclipse.ditto.internal.utils.persistence.postgres.snapshot.PostgresSnapshotStore\"");
    // build the same fake Postgres-family provider the existing tests use, whose
    // pluginConfig().getAutoStartPluginIds(...) returns the ditto-postgres-* ids present above
    assertThatThrownBy(() -> PersistenceBackendSelfCheck.verify(effective, postgresFamilyProvider(),
            "things", expectedReadJournalClassNameOf(postgresFamilyProvider())))
            .isInstanceOf(DittoConfigError.class)
            .hasMessageContaining("auto-start")
            .hasMessageContaining("pekko-contrib-mongodb-persistence-things-journal");
}
```

(Adapt `postgresFamilyProvider()` / the read-journal-class argument to the fixtures the test file already defines — do NOT invent a new fixture style; read the file first.)

- [ ] **Step 2: Run to verify it fails**

Run: `$MVN -pl internal/utils/persistence-api test -Dtest=PersistenceBackendSelfCheckTest -q` (adjust class name to the real one). Expected: FAIL — current verify() passes.

- [ ] **Step 3: Implement layer 3**

In `PersistenceBackendSelfCheck.verify`, after the existing read-journal check, add:

```java
        // Layer 3: the EFFECTIVE Pekko auto-start lists. Layers 1-2 validate the provider-declared
        // plugin ids; they cannot see a deployment whose pekko.persistence.* lists still auto-start
        // the OTHER backend's plugins (the classic partial-include misconfiguration). Every effective
        // auto-start entry must resolve to a class of the active backend family.
        verifyEffectiveAutoStartList(effectiveConfig, "pekko.persistence.journal.auto-start-journals",
                expectedPluginClasses, family, serviceName);
        verifyEffectiveAutoStartList(effectiveConfig,
                "pekko.persistence.snapshot-store.auto-start-snapshot-stores",
                expectedPluginClasses, family, serviceName);
```

and the helper:

```java
    private static void verifyEffectiveAutoStartList(final Config effectiveConfig, final String listPath,
            final Set<String> expectedPluginClasses, final BackendFamily family, final String serviceName) {
        if (!effectiveConfig.hasPath(listPath)) {
            return;
        }
        for (final String pluginId : effectiveConfig.getStringList(listPath)) {
            final String actualPluginClass = resolvePluginClass(effectiveConfig, pluginId, family, serviceName);
            if (!expectedPluginClasses.contains(actualPluginClass)) {
                throw new DittoConfigError(String.format(
                        "Persistence backend mismatch: the effective <%s> list auto-starts plugin <%s> "
                                + "(class <%s>), which does not belong to the selected backend family <%s> "
                                + "(expected one of %s). The deployment kept the other backend's "
                                + "pekko.persistence auto-start wiring — include the backend profile at a "
                                + "position where its pekko.persistence.* lists win, or fix the lists.",
                        listPath, pluginId, actualPluginClass, family, expectedPluginClasses));
            }
        }
    }
```

- [ ] **Step 4: Run persistence-api tests, then BOTH downstream service test suites that exercise RootActors**

Run: `$MVN -pl internal/utils/persistence-api -am verify -q` → PASS.
Run: `$MVN -pl things/service test -Dtest=ThingsRootActorTest -q` → PASS (root-actor tests load the production Mongo config where the auto-start lists are Mongo-classed under a Mongo provider — must still pass).

- [ ] **Step 5: Commit**

```bash
command git add internal/utils/persistence-api
command git commit -m "feat(persistence-api): self-check layer 3 verifies effective pekko auto-start lists

Layers 1-2 validated only the provider-declared plugin ids and could not
detect a deployment whose pekko.persistence.* lists still auto-start the
other backend's plugins. Fail boot fast with a precise DittoConfigError
instead.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Per-incarnation streaming resources (fixes the Mongo closed-client-on-restart regression)

`MongoPersistenceBackendProvider.streaming()` (lines 159-168) builds a `DittoMongoClient` at Props-BUILD time; `SnapshotStreamingActor.props` uses the arg-capturing `Props.create` form (line 129) and `postStop` closes the captured client — Pekko's default `preRestart` invokes `postStop`, so a restarted incarnation reuses a permanently closed client. On master the actor built a fresh client per incarnation.

**Files:**
- Modify: `internal/utils/persistence-api/src/main/java/org/eclipse/ditto/internal/utils/persistence/api/streaming/SnapshotStreamingActor.java:84-130`
- Modify: `internal/utils/persistence/src/main/java/org/eclipse/ditto/internal/utils/persistence/mongo/MongoPersistenceBackendProvider.java:159-168`
- Test: `internal/utils/persistence-api/src/test/java/org/eclipse/ditto/internal/utils/persistence/api/streaming/SnapshotStreamingActorRestartTest.java` (new)

**Interfaces:**
- Produces: `public record StreamingResources(DittoReadJournal readJournal, Closeable resourceToClose)` (nested in `SnapshotStreamingActor`) and `public static Props props(Function<String, EntityId> pid2EntityId, Function<EntityId, String> entityId2Pid, Supplier<StreamingResources> resourcesPerIncarnation)`. The existing 4-arg `props(...)` stays (Postgres uses it with stateless resources: shared read journal + `NoOpCloseable`, restart-safe by construction).

- [ ] **Step 1: Write the failing restart test**

```java
public final class SnapshotStreamingActorRestartTest {

    private static ActorSystem system;

    @BeforeClass public static void setUp() { system = ActorSystem.create("SnapshotStreamingActorRestartTest"); }
    @AfterClass public static void tearDown() { TestKit.shutdownActorSystem(system); }

    /** Parent that restarts its child on ANY exception (mirrors DittoRootActor's restart cases). */
    private static final class RestartingParent extends AbstractActor {
        private final Props childProps;
        private ActorRef child;
        private RestartingParent(final Props childProps) { this.childProps = childProps; }
        @Override public void preStart() { child = getContext().actorOf(childProps, "streaming"); }
        @Override public SupervisorStrategy supervisorStrategy() {
            return new OneForOneStrategy(DeciderBuilder.matchAny(e -> SupervisorStrategy.restart()).build());
        }
        @Override public Receive createReceive() {
            return receiveBuilder().matchAny(msg -> child.forward(msg, getContext())).build();
        }
    }

    @Test
    public void restartBuildsFreshResourcesAndClosesOldOnes() {
        final AtomicInteger built = new AtomicInteger();
        final List<AtomicBoolean> closedFlags = new CopyOnWriteArrayList<>();
        final DittoReadJournal readJournal = Mockito.mock(DittoReadJournal.class);

        final Props props = SnapshotStreamingActor.props(
                pid -> EntityId.of(EntityType.of("thing"), pid.substring("thing:".length())),
                id -> "thing:" + id,
                () -> {
                    built.incrementAndGet();
                    final AtomicBoolean closed = new AtomicBoolean();
                    closedFlags.add(closed);
                    return new SnapshotStreamingActor.StreamingResources(readJournal,
                            () -> closed.set(true));
                });

        final ActorRef parent = system.actorOf(Props.create(RestartingParent.class, () ->
                new RestartingParent(props)));
        // force a restart of the child
        system.actorSelection(parent.path().child("streaming")).tell(Kill.getInstance(), ActorRef.noSender());

        Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(built.get()).as("a fresh resource set per incarnation").isEqualTo(2);
            assertThat(closedFlags.get(0)).as("first incarnation's resource closed").isTrue();
            assertThat(closedFlags.get(1)).as("live incarnation's resource NOT closed").isFalse();
        });
    }
}
```

(If Awaitility is not on the persistence-api test classpath — check `internal/utils/persistence-api/pom.xml` — replace with a `TestKit.awaitAssert` or a bounded poll loop; keep the three assertions identical. `Kill` needs an OneForOneStrategy that restarts on `ActorKilledException`, which `matchAny` covers.)

- [ ] **Step 2: Run to verify it fails**

Run: `$MVN -pl internal/utils/persistence-api test -Dtest=SnapshotStreamingActorRestartTest -q`
Expected: FAIL to compile — the 3-arg `props(Supplier)` overload and `StreamingResources` do not exist yet.

- [ ] **Step 3: Implement supplier-based props**

In `SnapshotStreamingActor`, add:

```java
    /**
     * The per-incarnation resource pair for backends whose streaming resources are STATEFUL (Mongo: a
     * dedicated client the actor owns and closes). Pekko's default {@code preRestart} runs {@code postStop},
     * which closes {@code resourceToClose}; arg-captured Props would hand the CLOSED resource to the next
     * incarnation. The supplier-based {@link #props(Function, Function, Supplier)} rebuilds fresh resources
     * per incarnation instead.
     */
    public record StreamingResources(DittoReadJournal readJournal, Closeable resourceToClose) {}

    @SuppressWarnings("unused") // called by reflection
    private SnapshotStreamingActor(final Function<String, EntityId> pid2EntityId,
            final Function<EntityId, String> entityId2Pid,
            final Supplier<StreamingResources> resourcesPerIncarnation) {
        final StreamingResources resources = resourcesPerIncarnation.get();
        this.pid2EntityId = pid2EntityId;
        this.entityId2Pid = entityId2Pid;
        this.readJournal = resources.readJournal();
        this.resourceToClose = resources.resourceToClose();
        this.pubSubMediator = DistributedPubSub.get(getContext().getSystem()).mediator();
    }

    /**
     * Props whose resources are rebuilt for EVERY incarnation (supervised restarts included). Backends whose
     * streaming actor owns a closeable client (Mongo) MUST use this overload; backends with stateless/shared
     * resources (Postgres: shared read journal + NoOpCloseable) may keep the instance-capturing overload.
     */
    public static Props props(final Function<String, EntityId> pid2EntityId,
            final Function<EntityId, String> entityId2Pid,
            final Supplier<StreamingResources> resourcesPerIncarnation) {
        return Props.create(SnapshotStreamingActor.class, pid2EntityId, entityId2Pid, resourcesPerIncarnation);
    }
```

(add `import java.util.function.Supplier;`)

In `MongoPersistenceBackendProvider.streaming` (lines 159-168), replace the body with:

```java
        return SnapshotStreamingActor.props(pid2EntityId, entityId2Pid, () -> {
            // Rebuilt per incarnation: postStop (also invoked by preRestart) closes the client, so a
            // captured instance would be permanently closed after the first supervised restart.
            final Config config = actorSystem.settings().config();
            final MongoDbConfig mongoDbConfig = DefaultMongoDbConfig.of(DefaultScopedConfig.dittoScoped(config));
            final DittoMongoClient mongoClient = MongoClientWrapper.newInstance(mongoDbConfig);
            final MongoReadJournal mongoReadJournal = MongoReadJournal.newInstance(config, mongoClient,
                    mongoDbConfig.getReadJournalConfig(), actorSystem);
            return new SnapshotStreamingActor.StreamingResources(mongoReadJournal, mongoClient);
        });
```

- [ ] **Step 4: Run tests, both modules, commit**

Run: `$MVN -pl internal/utils/persistence-api -am verify -q` and `$MVN -pl internal/utils/persistence -am verify -q` → BUILD SUCCESS.

```bash
command git add internal/utils/persistence-api internal/utils/persistence
command git commit -m "fix(persistence): rebuild Mongo streaming resources per actor incarnation

MongoPersistenceBackendProvider.streaming() captured a DittoMongoClient
in the Props; SnapshotStreamingActor.postStop (also run by preRestart)
closed it, so any supervised restart left every later incarnation with a
permanently closed client — snapshot streaming (search sync) dead until
service restart. Add a supplier-based props overload that rebuilds the
resources per incarnation, matching the pre-refactor behaviour.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Kill the `pluralize()` fallback (fixes the "policys" outage class)

`MongoPersistenceBackendProvider.getJournalPluginId` (line 107) falls back to `PLUGIN_ID_PREFIX + pluralize(entityType) + JOURNAL_SUFFIX`, which is WRONG for `policy` ("policys"), `connection` (real block is singular `-connection-journal`) and `wot-validation-config`. The fallback fires whenever the `extension-config.plugin-ids` overrides are absent — which the framework's string-shorthand extension declaration makes trivially easy (assigning a string to `ditto.extensions.persistence-backend-provider` wipes the reference.conf object; `DittoExtensionPoint.ExtensionIdConfig.of` then supplies `ConfigFactory.empty()`).

**Files:**
- Modify: `internal/utils/persistence/src/main/java/org/eclipse/ditto/internal/utils/persistence/mongo/MongoPersistenceBackendProvider.java:105-115` and the `pluralize` helper (line 220)
- Test: the provider's existing unit test (find with `command git ls-files | grep MongoPersistenceBackendProviderTest`) — extend.

**Interfaces:**
- Produces: unchanged signatures; resolution order becomes override → WELL-KNOWN map → `DittoConfigError` (no heuristic).

- [ ] **Step 1: Write the failing unit test**

```java
@Test
public void emptyExtensionConfigResolvesCorrectPluginIdsForAllEntities() {
    // The string-shorthand extension declaration yields ConfigFactory.empty() — the provider must
    // still resolve the REAL shipped plugin blocks, not a pluralize() guess ("policys").
    final MongoPersistenceBackendProvider provider =
            new MongoPersistenceBackendProvider(actorSystem, ConfigFactory.empty());
    assertThat(provider.getJournalPluginId("thing"))
            .isEqualTo("pekko-contrib-mongodb-persistence-things-journal");
    assertThat(provider.getJournalPluginId("policy"))
            .isEqualTo("pekko-contrib-mongodb-persistence-policies-journal");
    assertThat(provider.getJournalPluginId("connection"))
            .isEqualTo("pekko-contrib-mongodb-persistence-connection-journal");
    assertThat(provider.getJournalPluginId("wot-validation-config"))
            .isEqualTo("pekko-contrib-mongodb-persistence-wot-validation-config-journal");
    assertThat(provider.getSnapshotPluginId("policy"))
            .isEqualTo("pekko-contrib-mongodb-persistence-policies-snapshots");
}

@Test
public void unknownEntityTypeWithoutOverrideFailsFast() {
    final MongoPersistenceBackendProvider provider =
            new MongoPersistenceBackendProvider(actorSystem, ConfigFactory.empty());
    assertThatThrownBy(() -> provider.getJournalPluginId("gadget"))
            .isInstanceOf(DittoConfigError.class)
            .hasMessageContaining("gadget");
}
```

IMPORTANT: before writing, verify the exact expected ids against the shipped Mongo blocks: `grep -n "pekko-contrib-mongodb-persistence-.*-journal {" things/service/src/main/resources/things.conf policies/service/src/main/resources/policies.conf connectivity/service/src/main/resources/connectivity.conf` and the snapshot suffix convention (`-snapshots`). Use exactly what ships.

- [ ] **Step 2: Run to verify it fails** (`"policys"` comes back for policy, no error for gadget).

- [ ] **Step 3: Implement the well-known map**

```java
    /**
     * The shipped Mongo plugin BLOCK NAMES per entity type — the single in-code source of truth, immune to
     * extension-config loss (the string-shorthand extension declaration replaces the reference.conf object
     * with an empty config). The former pluralize() heuristic produced non-existent blocks ("policys") and
     * is removed: an unknown entity type without an explicit override is a hard DittoConfigError.
     */
    private static final Map<String, String> WELL_KNOWN_BLOCK_NAMES = Map.of(
            "thing", "things",
            "policy", "policies",
            "connection", "connection",
            "wot-validation-config", "wot-validation-config");

    @Override
    public String getJournalPluginId(final String entityType) {
        return getOverride(entityType, JOURNAL_KEY)
                .orElseGet(() -> PLUGIN_ID_PREFIX + blockName(entityType) + JOURNAL_SUFFIX);
    }

    @Override
    public String getSnapshotPluginId(final String entityType) {
        return getOverride(entityType, SNAPSHOT_KEY)
                .orElseGet(() -> PLUGIN_ID_PREFIX + blockName(entityType) + SNAPSHOT_SUFFIX);
    }

    private static String blockName(final String entityType) {
        final String known = WELL_KNOWN_BLOCK_NAMES.get(entityType);
        if (known == null) {
            throw new DittoConfigError("No Mongo persistence plugin block is known for entity type <"
                    + entityType + "> and no extension-config.plugin-ids override is configured. "
                    + "Declare plugin-ids." + entityType + ".{journal|snapshot} in "
                    + "ditto.extensions.persistence-backend-provider.extension-config.");
        }
        return known;
    }
```

Delete `pluralize` (line 220-222) and update the reference.conf comment in `internal/utils/persistence/src/main/resources/reference.conf` that documents the pluralize hazard — the overrides there are now redundant-but-harmless documentation; say so.

- [ ] **Step 4: Run module + downstream services compile, commit**

Run: `$MVN -pl internal/utils/persistence -am verify -q` and `$MVN -pl things/service,policies/service,connectivity/service test-compile -q` → SUCCESS.

```bash
command git add internal/utils/persistence
command git commit -m "fix(persistence): replace pluralize() plugin-id fallback with well-known map

The heuristic produced non-existent plugin blocks (policy->'policys',
connection->'connections') whenever the extension-config overrides were
lost — trivially triggered by the framework-supported string-shorthand
extension declaration, taking down policies/connectivity persistence
past a green boot self-check. Resolution is now override -> well-known
block map -> hard DittoConfigError.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Schema-qualify the catalog verification queries

`PostgresSchemaManager.verifyTable` (lines 150-165) queries `pg_constraint` by `relname` and `information_schema.columns` by `table_name` with NO schema predicate; a same-named table in ANY other schema of the database merges/corrupts verification (false `SchemaBootException` or false pass). DDL runs unqualified into the `search_path` schema — verification must scope to `current_schema()`.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/PostgresSchemaManager.java:150-165`
- Test: the schema-manager IT (find with `command git ls-files | grep -i "SchemaManager.*IT\|Schema.*IT" `; if none exists, add the test method to `PostgresReadJournalPaginationIT`'s class or a new `PostgresSchemaQualificationIT` using the same harness).

- [ ] **Step 1: Write the failing IT**

```java
@Test
public void bootstrapIgnoresSameNamedTablesInOtherSchemas() throws Exception {
    // A decoy schema with a WRONG-shaped things_journal must not affect verification of the real one.
    await(client.executeUpdatePublisher("CREATE SCHEMA IF NOT EXISTS decoy", stmt -> {}) ...);
    // NOTE: use the harness's raw-SQL execution path; if DittoPostgresClient has no raw helper, obtain a
    // connection from POSTGRES.newConnectionFactory() directly:
    //   Mono.usingWhen(connectionFactory.create(), c -> Flux.from(c.createStatement(sql).execute()).then(), Connection::close)
    // Execute: CREATE SCHEMA IF NOT EXISTS decoy;
    //          CREATE TABLE IF NOT EXISTS decoy.things_journal (wrong BIGINT PRIMARY KEY);
    // Then re-run the bootstrap against the REAL schema and assert it does not throw:
    PostgresSchemaManager.of(POSTGRES.newConnectionFactory()).bootstrap();
}
```

(The pre-fix failure mode: the columns query merges `decoy.things_journal`'s `wrong BIGINT` column into the real table's column map → `SchemaBootException`. Write the arrange SQL with the exact raw-connection pattern found in `PostgresDbResource`/existing schema ITs — read those files first and reuse their helper.)

- [ ] **Step 2: Run to verify it fails** (bootstrap throws `SchemaBootException` mentioning an unexpected column or PK).

- [ ] **Step 3: Add the schema predicates**

PK query (line 151-154):

```java
        final Mono<String> pkDef = Flux.from(connection.createStatement(
                        "SELECT pg_get_constraintdef(c.oid) AS def "
                                + "FROM pg_constraint c "
                                + "JOIN pg_class t ON c.conrelid = t.oid "
                                + "JOIN pg_namespace n ON t.relnamespace = n.oid "
                                + "WHERE t.relname = $1 AND n.nspname = current_schema() AND c.contype = 'p'")
                        .bind(0, contract.tableName())
                        .execute())
```

Columns query (line 162-165):

```java
                        "SELECT column_name, data_type, collation_name FROM information_schema.columns "
                                + "WHERE table_name = $1 AND table_schema = current_schema()")
```

- [ ] **Step 4: Run the IT + module, commit**

```bash
command git add internal/utils/persistence-r2dbc
command git commit -m "fix(persistence-r2dbc): schema-qualify catalog verification to current_schema()

pg_constraint/information_schema lookups by bare table name merged rows
from same-named tables in other schemas of the database (false boot
refusal or false pass). Scope both queries to current_schema(), matching
the search_path the unqualified DDL bootstrap writes into.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: Version-aware schema checksum + operator runbook

`SchemaVerifier.verifyChecksum` (line 48) is a strict equality over the exact DDL text with the stored `version` column never read: ANY future DDL change refuse-boots every existing database with no documented recovery, and the single shared `schema_version` row deadlocks mixed-version rolling upgrades.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/SchemaVerifier.java:47-53`
- Modify: `internal/utils/persistence-r2dbc/src/main/java/org/eclipse/ditto/internal/utils/persistence/postgres/schema/PostgresSchemaManager.java:191-206` (`upsertAndVerifyChecksum`)
- Modify: `deployment/postgres/README.md` (runbook section)
- Test: the SchemaVerifier unit test (find with `command git ls-files | grep SchemaVerifierTest`).

**Interfaces:**
- Produces: `SchemaVerifier.verifyChecksum(String expectedChecksum, int currentVersion, @Nullable String storedChecksum, @Nullable Integer storedVersion)`; rules: no row → pass (first boot); `storedVersion == currentVersion` → checksums must match; `storedVersion < currentVersion` → pass (additive upgrade in flight — the CREATE-IF-NOT-EXISTS DDL has already run; the upsert then advances the row); `storedVersion > currentVersion` → throw (downgrade).

- [ ] **Step 1: Write the failing unit tests**

```java
@Test public void firstBootPasses() {
    SchemaVerifier.verifyChecksum("abc", 2, null, null); // no throw
}
@Test public void sameVersionSameChecksumPasses() {
    SchemaVerifier.verifyChecksum("abc", 2, "abc", 2); // no throw
}
@Test public void sameVersionDifferentChecksumThrows() {
    assertThatThrownBy(() -> SchemaVerifier.verifyChecksum("abc", 2, "xyz", 2))
            .isInstanceOf(SchemaBootException.class).hasMessageContaining("drift");
}
@Test public void olderStoredVersionPasses() {
    // rolling upgrade: this node carries version 3, the row still says version 2 — allowed;
    // the caller re-runs the additive DDL and advances the row.
    SchemaVerifier.verifyChecksum("abc", 3, "old-checksum", 2); // no throw
}
@Test public void newerStoredVersionThrows() {
    assertThatThrownBy(() -> SchemaVerifier.verifyChecksum("abc", 2, "newer", 3))
            .isInstanceOf(SchemaBootException.class).hasMessageContaining("downgrade");
}
```

- [ ] **Step 2: Run to verify compile failure** (new signature).

- [ ] **Step 3: Implement**

```java
    /**
     * Version-aware checksum verification.
     * <ul>
     *   <li>No stored row: first boot — pass.</li>
     *   <li>Stored version equals the current code version: checksums must match exactly (drift guard).</li>
     *   <li>Stored version is OLDER: pass — an additive schema upgrade is in flight (the additive-only DDL
     *       policy means the CREATE-IF-NOT-EXISTS bootstrap has already applied the delta); the caller then
     *       advances the row to the current version+checksum. During a rolling upgrade, not-yet-upgraded
     *       nodes keep booting because their older version compares as OLDER only on the upgraded row —
     *       see the runbook note below for the mixed-version window.</li>
     *   <li>Stored version is NEWER: refuse — a downgrade against an already-advanced schema.</li>
     * </ul>
     * NOTE on the mixed-version window: once one upgraded node advances the row, an OLD-version node that
     * restarts sees storedVersion &gt; its currentVersion and refuses to boot — this is intentional
     * (old code must not run against a newer schema); the runbook documents completing the roll promptly.
     */
    public static void verifyChecksum(final String expectedChecksum, final int currentVersion,
            @Nullable final String storedChecksum, @Nullable final Integer storedVersion) {
        if (storedChecksum == null || storedVersion == null) {
            return;
        }
        if (storedVersion > currentVersion) {
            throw new SchemaBootException("Refusing to boot: stored schema version " + storedVersion
                    + " is NEWER than this code's version " + currentVersion + " for component '"
                    + PostgresSchema.COMPONENT + "' — schema downgrade detected. Upgrade this service "
                    + "or restore the matching database.");
        }
        if (storedVersion == currentVersion && !storedChecksum.equals(expectedChecksum)) {
            throw new SchemaBootException(
                    "Refusing to boot: schema_version checksum mismatch for component '" + PostgresSchema.COMPONENT
                            + "' at version " + currentVersion + ". Stored=" + storedChecksum
                            + " expected=" + expectedChecksum
                            + ". This indicates schema drift (manual DDL edits or a code/DB mismatch at the "
                            + "same schema version). See deployment/postgres/README.md#schema-upgrades.");
        }
        // storedVersion < currentVersion: additive upgrade in flight — allowed.
    }
```

In `PostgresSchemaManager.upsertAndVerifyChecksum` (line 191): change the SELECT to read both columns and pass both through:

```java
        return Flux.from(connection.createStatement(
                        "SELECT version, checksum FROM schema_version WHERE component = $1")
                        .bind(0, PostgresSchema.COMPONENT)
                        .execute())
                .flatMap(result -> result.map((Row row, io.r2dbc.spi.RowMetadata meta) ->
                        new StoredSchemaVersion(row.get("version", Integer.class),
                                row.get("checksum", String.class))))
                .next()
                .defaultIfEmpty(StoredSchemaVersion.ABSENT)
                .doOnNext(stored -> SchemaVerifier.verifyChecksum(expected, PostgresSchema.VERSION,
                        stored.checksum(), stored.version()))
                .then(executeUpdate(connection,
                        "INSERT INTO schema_version (component, version, checksum) VALUES ($1, $2, $3) "
                                + "ON CONFLICT (component) DO UPDATE SET version = excluded.version, "
                                + "checksum = excluded.checksum",
                        PostgresSchema.COMPONENT, PostgresSchema.VERSION, expected));
```

with a small nested record:

```java
    private record StoredSchemaVersion(@Nullable Integer version, @Nullable String checksum) {
        static final StoredSchemaVersion ABSENT = new StoredSchemaVersion(null, null);
    }
```

- [ ] **Step 4: Write the runbook**

Append to `deployment/postgres/README.md`:

```markdown
## Schema upgrades (`schema_version`)

The bootstrap guards the `schema_version` row (component `ditto-postgres-persistence`): same-version
checksum drift refuses boot; an OLDER stored version is upgraded in place (DDL is additive-only,
`CREATE … IF NOT EXISTS`); a NEWER stored version refuses boot (downgrade guard).

Rolling upgrades: things/policies/connectivity share ONE row. As soon as the first upgraded pod
advances it, a NOT-yet-upgraded pod that RESTARTS will refuse to boot (downgrade guard) — running
pods are unaffected. Complete the roll across all three services promptly; do not pin one service to
an older image across a schema-version bump.

Recovery from a genuine same-version drift (manual DDL edits): reconcile the DDL, then
`UPDATE schema_version SET checksum = '<expected-from-the-boot-error>' WHERE component = 'ditto-postgres-persistence';`
```

- [ ] **Step 5: Run unit tests + module, commit**

```bash
command git add internal/utils/persistence-r2dbc deployment/postgres/README.md
command git commit -m "fix(persistence-r2dbc): version-aware schema checksum guard + upgrade runbook

The strict stored==current checksum equality (version column write-only)
made ANY future DDL change refuse-boot existing databases with no
documented recovery, and the shared schema_version row deadlocked
rolling upgrades. Older stored version now upgrades in place (additive
DDL policy), newer refuses (downgrade guard), same-version drift still
refuses; runbook added.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: Fail fast on the renamed `snapshot-adapter` extension key

The `ditto.extensions.snapshot-adapter` key was renamed to `snapshot-serializer` (`SnapshotSerializer.java:194`) with no migration shim: a deployment overriding the OLD key silently falls back to the default serializer (snapshot-format mismatch risk).

**Files:**
- Modify: `internal/utils/persistence-api/src/main/java/org/eclipse/ditto/internal/utils/persistence/api/serializer/SnapshotSerializer.java` (the static `get(...)` accessor next to `CONFIG_KEY`, line ~194)
- Test: the SnapshotSerializer/extension test in the same module (find with `command git ls-files | grep -i SnapshotSerializer | grep test`).

- [ ] **Step 1: Write the failing test**

```java
@Test
public void legacySnapshotAdapterKeyFailsFastWithMigrationMessage() {
    final Config dittoExtensionsConfig = ConfigFactory.parseString(
            "snapshot-adapter = \"com.example.LegacyAdapter\"\n"
            + "snapshot-serializer = { extension-class = \"" + /* the default/test serializer used by the
            existing tests in this file — reuse their fixture */ "\" }");
    assertThatThrownBy(() -> SnapshotSerializer.get(actorSystem, dittoExtensionsConfig))
            .isInstanceOf(DittoConfigError.class)
            .hasMessageContaining("snapshot-adapter")
            .hasMessageContaining("snapshot-serializer");
}
```

(Reuse the actor-system + config fixture style of the existing tests in that file; read it first.)

- [ ] **Step 2: Run to verify it fails** (currently the legacy key is silently ignored).

- [ ] **Step 3: Implement the guard**

At the top of `SnapshotSerializer.get(...)` (before resolving `CONFIG_KEY`):

```java
        if (dittoExtensionsConfig.hasPath("snapshot-adapter")) {
            throw new DittoConfigError("The extension key ditto.extensions.snapshot-adapter was RENAMED to "
                    + "ditto.extensions.snapshot-serializer (the snapshot storage envelope moved into the "
                    + "persistence-backend-provider's snapshot codec; the serializer is backend-neutral). "
                    + "A configured snapshot-adapter would be silently ignored and snapshots would be "
                    + "written/read with the default serializer — migrate the override to "
                    + "snapshot-serializer (and port the class to the SnapshotSerializer SPI).");
        }
```

(Adjust the parameter name to the method's real signature. If `get` receives the ScopedConfig of `ditto.extensions`, `hasPath("snapshot-adapter")` is correct; verify by reading the method.)

- [ ] **Step 4: Run module, commit**

```bash
command git add internal/utils/persistence-api
command git commit -m "fix(persistence-api): fail fast on the renamed snapshot-adapter extension key

A deployment still overriding ditto.extensions.snapshot-adapter silently
fell back to the default serializer (snapshot-format mismatch risk).
Boot now fails with a precise migration message.

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: Latent-bug batch (deleteSnapshot ts=0, unnest LIMIT, OPTIONS merge, streaming entity guard)

Four verified-but-latent defects, each a small isolated fix. One commit; each sub-fix carries its own test.

**Files:**
- Modify: `internal/utils/persistence-r2dbc/.../postgres/snapshot/PostgresSnapshotStoreOps.java:86-91`
- Modify: `internal/utils/persistence-r2dbc/.../postgres/ops/PostgresPersistenceOperations.java:418-423` (unnest) and add `deleteSnapshotsBySn`
- Modify: `internal/utils/persistence-r2dbc/.../postgres/ConnectionPoolFactory.java:182-185`
- Modify: `internal/utils/persistence-r2dbc/.../postgres/PostgresPersistenceBackendProvider.java:230-234`
- Tests: extend `PostgresReadJournalPaginationIT` (sub-fixes 1-2), the ConnectionPoolFactory unit test (sub-fix 3), the provider unit test (sub-fix 4).

- [ ] **Step 1: deleteSnapshot with timestamp 0 = wildcard (Pekko SPI parity)**

Failing IT:

```java
@Test
public void deleteSnapshotWithZeroTimestampDeletesBySn() throws Exception {
    await(snapshots.saveAsync(new SnapshotMetadata("thing:zdel:z1", 4L, 1234L), "{}"));
    await(snapshots.deleteAsync(new SnapshotMetadata("thing:zdel:z1", 4L, 0L))); // Pekko deleteSnapshot(seqNr)
    final List<String> pids = readJournal.getNewestSnapshotsAbove("", 100, true, Duration.ZERO, mat)
            .filter(e -> e.getPid().filter(p -> p.equals("thing:zdel:z1")).isPresent())
            .map(e -> e.getPid().orElseThrow())
            .runWith(Sink.seq(), mat).toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(pids).as("timestamp-0 delete must fall back to (pid, sn)").isEmpty();
}
```

Fix — `PostgresSnapshotStoreOps.deleteAsync(SnapshotMetadata)`:

```java
    /**
     * {@code deleteAsync(SnapshotMetadata)}: exact {@code (pid, sn, written_at)} when a timestamp is present;
     * Pekko's {@code Snapshotter.deleteSnapshot(seqNr)} sends timestamp 0 ("unknown") — reference plugins
     * treat that as a WILDCARD, so fall back to {@code (pid, sn)}.
     */
    public CompletionStage<Void> deleteAsync(final SnapshotMetadata metadata) {
        final Mono<Long> delete = metadata.timestamp() == 0L
                ? operations.deleteSnapshotsBySn(metadata.persistenceId(), metadata.sequenceNr())
                : operations.deleteSnapshotExact(metadata.persistenceId(), metadata.sequenceNr(),
                        Instant.ofEpochMilli(metadata.timestamp()));
        return delete.<Void>then(reactor.core.publisher.Mono.empty()).toFuture();
    }
```

New ops method (next to `deleteSnapshotExact`, line 320):

```java
    /** All snapshot rows of {@code (pid, sn)} regardless of {@code written_at} — the timestamp-0 wildcard delete. */
    public Mono<Long> deleteSnapshotsBySn(final String pid, final long sequenceNr) {
        return Mono.from(client.executeUpdatePublisher(
                "DELETE FROM " + tables.snapsTable() + " WHERE pid = $1 AND sn = $2",
                stmt -> {
                    stmt.bind(0, pid);
                    stmt.bind(1, sequenceNr);
                }));
    }
```

- [ ] **Step 2: `getMostRecentTagsForPid` — LIMIT the event, then unnest**

Failing IT:

```java
@Test
public void mostRecentTagsReturnsAllTagsOfTheNewestEvent() throws Exception {
    await(operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(
            "thing:mtags:m1", 1L, "m", List.of("old-tag"), "{}"))).toFuture());
    await(operations.insertEvents(List.of(new PostgresPersistenceOperations.JournalInsert(
            "thing:mtags:m1", 2L, "m", List.of("always-alive", "priority-7"), "{}"))).toFuture());
    final List<String> tags = readJournal.getMostRecentJournalTagsForPid("thing:mtags:m1")
            .runWith(Sink.seq(), mat).toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertThat(tags).containsExactlyInAnyOrder("always-alive", "priority-7");
}
```

Fix (`PostgresPersistenceOperations.getMostRecentTagsForPid`, line 418):

```java
    public Source<String, NotUsed> getMostRecentTagsForPid(final String pid) {
        // LIMIT selects the newest EVENT first; unnest expands ITS tags — LIMIT after unnest kept one
        // arbitrary tag and, on an empty newest-tags array, leaked a tag from an OLDER event.
        return querySourceMany("SELECT unnest(tags) AS tag FROM ("
                        + "SELECT tags FROM " + tables.journalTable()
                        + " WHERE pid = $1 ORDER BY sn DESC LIMIT 1) newest",
                stmt -> stmt.bind(0, pid),
                row -> getString(row, "tag"));
    }
```

- [ ] **Step 3: Merge, don't replace, the r2dbc OPTIONS map**

Failing unit test (in the ConnectionPoolFactory test class; find it via `command git ls-files | grep ConnectionPoolFactory`): build a config whose URI is `r2dbc:postgresql://host:5432/db?options=search_path=ditto` plus `statement-timeout = 60s`, call the options-building method (make `baseOptions` package-private for the test if it is private), and assert the resulting `PostgresqlConnectionFactoryProvider.OPTIONS` value contains BOTH `search_path=ditto` and `statement_timeout=60000`.

Fix (`ConnectionPoolFactory.baseOptions`, lines 182-185):

```java
        final Duration statementTimeout = config.getStatementTimeout();
        if (statementTimeout != null && !statementTimeout.isZero() && !statementTimeout.isNegative()) {
            // MERGE with any startup GUCs the operator supplied via the URI's options= query parameter
            // (e.g. search_path) — a bare Map.of(...) would REPLACE the parsed OPTIONS value wholesale.
            final Map<String, String> options = new java.util.LinkedHashMap<>();
            final Object parsedOptions = ConnectionFactoryOptions.parse(config.getUri())
                    .getValue(PostgresqlConnectionFactoryProvider.OPTIONS);
            if (parsedOptions instanceof Map<?, ?> parsedMap) {
                parsedMap.forEach((k, v) -> options.put(String.valueOf(k), String.valueOf(v)));
            } else if (parsedOptions instanceof String parsedString) {
                // URL form arrives as "a=b;c=d" before the provider converts it.
                for (final String pair : parsedString.split(";")) {
                    final int eq = pair.indexOf('=');
                    if (eq > 0) {
                        options.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
            }
            options.put("statement_timeout", Long.toString(statementTimeout.toMillis()));
            builder.option(PostgresqlConnectionFactoryProvider.OPTIONS, options);
        }
```

- [ ] **Step 4: `streaming(entityType)` honesty guard**

Failing unit test (provider test class): configure the provider with `read-journal.entity = "things"` and assert `provider.streaming("wot-validation-config", f1, f2)` throws `DittoConfigError` mentioning both entities, while `streaming("thing", f1, f2)` returns Props.

Fix (`PostgresPersistenceBackendProvider.streaming`, line 230):

```java
    @Override
    public Props streaming(final String entityType,
            final Function<String, EntityId> pid2EntityId,
            final Function<EntityId, String> entityId2Pid) {
        // The shared read journal binds to ONE table set (read-journal.entity). Streaming a DIFFERENT
        // entity type would silently read the wrong tables — fail loudly instead (interface honesty:
        // the entityType parameter is otherwise unused).
        final String boundEntity = resolveReadJournalEntity();
        final String requestedEntity = actorSystem.settings().config()
                .getString(getJournalPluginId(entityType) + ".entity");
        if (!boundEntity.equals(requestedEntity)) {
            throw new DittoConfigError("streaming(" + entityType + ") requested entity table set <"
                    + requestedEntity + "> but this service's read journal is bound to <" + boundEntity
                    + "> (extension-config read-journal.entity). A per-entity streaming read journal is "
                    + "not supported yet — bind the service's read journal to the requested entity.");
        }
        return SnapshotStreamingActor.props(pid2EntityId, entityId2Pid, getReadJournal(),
                NoOpCloseable.getInstance());
    }
```

(`resolveReadJournalEntity()` already exists — it is the private method reading `READ_JOURNAL_ENTITY_PATH`; check its exact name/visibility at `PostgresPersistenceBackendProvider.java:317-332` and reuse it.)

- [ ] **Step 5: Run the full r2dbc module + provider tests, commit**

Run: `$MVN -pl internal/utils/persistence-r2dbc verify -DskipITs=false -q` → BUILD SUCCESS.

```bash
command git add internal/utils/persistence-r2dbc
command git commit -m "fix(persistence-r2dbc): latent-defect batch from the max-effort review

- deleteAsync(SnapshotMetadata) with Pekko's timestamp-0 sentinel now
  deletes by (pid, sn) instead of matching nothing (SPI parity)
- getMostRecentTagsForPid selects the newest EVENT before unnesting its
  tags (LIMIT-after-unnest kept one arbitrary tag)
- statement_timeout startup GUC now MERGES with URI options= GUCs
  instead of replacing them (search_path survived)
- streaming(entityType) fails loudly when asked for an entity the shared
  read journal is not bound to, instead of silently streaming the wrong
  tables

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 14: Conventions + guard-rail batch (headers, stale doc, enforcer driver ban)

**Files:**
- Modify: `deployment/postgres-local/docker-compose.postgres.yml:1` (prepend EPL header)
- Modify: `internal/utils/persistence-r2dbc/src/main/resources/ditto-postgres-persistence.conf:1` (prepend EPL header)
- Modify: `documentation/src/main/resources/pages/ditto/installation-extending.md:191` (delete stale include)
- Modify: `things/service/pom.xml:319-340`, `policies/service/pom.xml:255-276`, `connectivity/service/pom.xml:295-316` (enforcer excludes)

- [ ] **Step 1: Prepend the EPL header to both files**

Use the exact comment-style header of `deployment/docker/docker-compose.yml:1-11` (starts `# Copyright (c) 2026 Contributors to the Eclipse Foundation`) for BOTH files — new files get year 2026. Place it ABOVE the existing first-line comments.

- [ ] **Step 2: Delete the stale doc include**

In `installation-extending.md`, delete line 191 (`include classpath("ditto-postgresql")`) — `ditto-postgresql.conf` was folded into `ditto-postgres-persistence.conf`; the include silently resolves to nothing. Check the surrounding paragraph for prose that still mentions "two includes"/the separate client-config file and fix it (also re-check the same page's auto-start guidance against Task 6: the overlay no longer needs to repoint `pekko.persistence.*` manually — simplify that step to the single include, keeping the classpath/packaging step).

- [ ] **Step 3: Ban the r2dbc driver coordinates in the service enforcers**

In each of the three `enforce-no-r2dbc-in-service` executions, extend the `<excludes>`:

```xml
                  <excludes>
                    <exclude>org.eclipse.ditto:ditto-internal-utils-persistence-r2dbc</exclude>
                    <exclude>org.eclipse.ditto:ditto-internal-utils-persistence-r2dbc-extension</exclude>
                    <!-- The driver coordinates themselves: a TRANSITIVE r2dbc/postgres driver must also
                         fail the build, not only the Ditto backend artifacts (the ArchUnit rule checks
                         code references, not shaded-jar contents). -->
                    <exclude>io.r2dbc:*</exclude>
                    <exclude>org.postgresql:r2dbc-postgresql</exclude>
                  </excludes>
```

- [ ] **Step 4: Verify the three services still build (proves no transitive driver exists today)**

Run: `$MVN -pl things/service,policies/service,connectivity/service verify -DskipTests -q`
Expected: BUILD SUCCESS (enforcer passes).

- [ ] **Step 5: Commit**

```bash
command git add deployment/postgres-local/docker-compose.postgres.yml \
  internal/utils/persistence-r2dbc/src/main/resources/ditto-postgres-persistence.conf \
  documentation/src/main/resources/pages/ditto/installation-extending.md \
  things/service/pom.xml policies/service/pom.xml connectivity/service/pom.xml
command git commit -m "chore: EPL headers, stale ditto-postgresql doc include, enforcer driver ban

Signed-off-by: Aleksandar Stanchev <aleksandar.stanchev@bosch.com>
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 15: Full verification sweep

- [ ] **Step 1: Full multi-module build over every touched module and its dependents**

Run: `$MVN -pl internal/utils/persistence-api,internal/utils/persistence,internal/utils/persistence-r2dbc,things/service,policies/service,connectivity/service -amd verify -DskipITs=false -q`
Expected: BUILD SUCCESS with the r2dbc ITs actually EXECUTED (Docker up — check the failsafe summary lines; skipped ITs are not a pass).

- [ ] **Step 2: End-to-end smoke on Postgres**

Run the IntelliJ compound `Ditto (Postgres)` (or `deployment/postgres-local/` scripts): create a thing via HTTP PUT, restart the Things service, GET the thing (recovery), and — new coverage — create >readBatchSize things and observe one cleanup round log processing MORE than the first batch (the Task-1/2 fix visible live).

- [ ] **Step 3: Update the status memory + review docs**

Record in the project status memory that the 15 review findings are fixed (list the commit shas), and that the deferred cleanup items (adapter dedup, dead-surface pruning, efficiency batch) remain open.

---

## Self-Review (performed at plan-writing time)

- **Spec coverage:** All 15 reported findings map to tasks — truncation (T1/T2), empty-tag ping (T3), DELETED resurrection (T2), pidFilter (T2), minAge-zero (T2), rejection mapping + U+0000 (T4), EmptyEvent binding (T5), auto-start dead path (T6+T7), remember-entities (T6), Mongo closed-client (T8), pluralize/plugin-ids wipe (T9), schema qualification (T10), checksum evolution (T11), snapshot-adapter rename (T12). Verified-but-cut latents (deleteSnapshot ts-0, unnest LIMIT, OPTIONS clobber, streaming entityType) are T13; conventions + enforcer gap are T14. Explicit non-goals listed in Global Constraints.
- **Placeholder scan:** Steps that depend on reading an existing fixture first say exactly which file to read and what to reuse; no TBDs.
- **Type consistency:** `unfoldBatchedSource` (T1) is reused in T2 with the same signature; the new ops signature `getNewestSnapshotsAbove(String, int, Duration, String)` is used consistently in T2 steps 3-5; `StreamingResources` (T8) matches between actor and provider; `verifyChecksum(String, int, String, Integer)` matches between T11 verifier and manager.
