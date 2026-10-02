# Persistence bench: plan deviations and implementation pins

Provenance: extracted verbatim (items 1, 2, 3, 4, 6, 8) from the section "Design deviations and
implementation pins" of the bench implementation plan
`docs/superpowers/plans/2026-07-10-postgres-persistence-bench.md` (an untracked working document in
the `ditto_feat__postgres-persistance` worktree, dated 2026-07-10; the plan itself is not committed,
by convention). Only the items that `README.md` and the committed evidence files cite are kept here,
using the plan's numbering ("plan deviation N"); the plan's items 5, 7 and 9-12 are not reproduced.
Source-file line references inside the items are as of the plan (July 2026) and may have shifted.

Cited by: item 1 by `README.md` §3 (corpus size); item 2 by `c2-single-pid-cleanup.md`; item 3 by
`README.md` §7.5 and `c3-full-sweep.md`; item 4 by `w1-single-append.md`; item 6 by `load-mongo.md`;
item 8 by `load-pg.md`.

1. **Corpus totals vs design estimate.** The design pins quantiles (median ~20, p99 ~2 000, cap 50 000). The piecewise log-uniform distribution implementing those pins (Task 2) has analytic mean ≈ **139 events/pid**, so 1M pids ⇒ **~139M journal rows ≈ 60–75 GB per backend (~130–150 GB total)** — the design's "~60–80M rows / ~40 GB per backend" corresponds to `--count 500000`. Default stays `--count 1000000` (design-faithful); the script's confirmation prompt and README must state the real disk estimate and recommend `--count 500000` for the design's disk budget. This is a §9 checkpoint item to confirm with the user before the first full run.

   Note (not part of the plan text): the published runs used `--count 250000` (35,070,304 journal rows per backend), not the 1M default; see `src/test/resources/bench/results/load-pg.md:4,9` and `load-mongo.md:9`.

2. **C2 has no `deleted_to` update.** The real cleanup path is `readJournal.deleteEvents` → `DELETE FROM things_journal WHERE pid=$1 AND sn>=$2 AND sn<=$3` (`PostgresPersistenceOperations.java:560-568`), which does **not** touch `things_journal_seq.deleted_to` (only the Pekko `deleteMessagesTo` API path does). The design's C2 row mentions a `deleted_to` update — the 1:1 transcription wins; note the correction in `c2-single-pid-cleanup.md`.

3. **C3 pacing.** On this branch `Credits` always issues `credits-per-batch=3` batches per 3 s interval (its timer accumulator is never fed — `Credits.java:36-42`), i.e. max ~100 deleted rows/s; a full sweep at that pace would take days. Default `bench.sweep.pace=unthrottled`: sequential batches, exactly one in flight (matching the actor's `Attributes.inputBuffer(1, 1)`), no timer gate. `bench.sweep.pace=credits` implements the 3-per-3s release for fidelity demos (`--quick`). Evidence states the pace used and quotes the computed default-pace sweep duration.

4. **Mongo append twin includes the realtime insert.** Ditto leaves the plugin default `realtime-enable-persistence = true`, so every append bulk-writes the journal AND fire-and-forget-inserts the same docs into the capped `things_realtime` collection (`ScalaDriverPersistenceJournaller.scala:115-128`). The twin replicates this: timed portion = journal `bulkWrite` (JOURNALED write concern); realtime insert dispatched async on a single-thread executor (reusing the same `Document` instance, `_id` included, as the plugin does), excluded from the timed path exactly as in the plugin.

6. **`things_metadata` stays empty.** The plugin only writes metadata in `deleteFrom` (Pekko API), which no bench scenario exercises. Loader creates the collection + unique sparse `{pid:1}` index for fidelity, loads nothing, and notes it.

8. **PG loader builds constraints/indexes after `COPY`.** Tables are created without PK, then `ALTER TABLE ... ADD PRIMARY KEY` + secondary indexes + autovacuum storage params + `ANALYZE`. End state is identical to `PostgresSchema.ddlStatements()` (verified by comparing `\d` output once, recorded in load evidence).

## Citations without a numbered item

`src/test/resources/bench/results/w3-sustained-churn.md:66` cites a generic "plan deviation list" (no oplog on standalone mongod; Mongo growth tracked via collStats). The plan has no numbered entry for it; the point is stated in `README.md` §2 (no replica set, no oplog).
