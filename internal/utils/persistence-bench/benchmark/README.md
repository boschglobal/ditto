# Ditto core persistence on PostgreSQL vs MongoDB — benchmark

Driver-level comparison of the two persistence backends for the event-sourced services
(things/policies/connectivity): recovery, write path, and the cleanup flow, on one corpus loaded
identically into both stores. Companion to the things-search Phase-0 bench (same methodology,
naming, and evidence rules). Design: `docs/superpowers/specs/2026-07-10-postgres-persistence-bench-design.md`.

## 1. Where everything lives

| Artifact | Path |
|---|---|
| Bench harness (Java, JUnit-based) | `internal/utils/persistence-bench/src/test/java/org/eclipse/ditto/internal/utils/persistence/bench/` |
| Committed evidence (results, plans) | `internal/utils/persistence-bench/src/test/resources/bench/results/` |
| Superseded schema-v1 evidence (C1, W2) | `internal/utils/persistence-bench/src/test/resources/bench/results/history/2026-07-13-schema-v1/` |
| Container-limited full run (2026-10-21), one store at a time | `internal/utils/persistence-bench/src/test/resources/bench/results/*-limits-pg.md`, `*-limits-mongo.md` |
| W3 per-op latency profile (50k corpus, 2026-10-06) | `internal/utils/persistence-bench/src/test/resources/bench/results/w3-latency/` (start with its `README.md`) |
| Persistence ping: 1M synthetic pids and the partly tagged 250k corpus (walks, recovery and the actor; see §5 and §6) | `internal/utils/persistence-bench/src/test/resources/bench/results/ping-keyset-paging-1m.md`, `ping-partly-tagged-walks-250k.md`, `ping-partly-tagged-walks-250k-repeat.md`, `ping-partly-tagged-recovery-250k.md`, `ping-partly-tagged-recovery-250k-repeat.md`, `ping-partly-tagged-actor-250k.md`, `ping-partly-tagged-actor-250k-repeat.md` |
| Reproduction script | `internal/utils/persistence-bench/benchmark/run-benchmark.sh` |
| Design the bench validates | `docs/superpowers/specs/2026-07-10-postgres-persistence-bench-design.md` |
| Plan deviations cited by the evidence ("plan deviation N") | `internal/utils/persistence-bench/benchmark/PLAN-DEVIATIONS.md` (extracted from the implementation plan, which is not committed) |

An unlabelled run overwrites its evidence file (`git diff` shows run-to-run movement); `BENCH_EVIDENCE_LABEL=<label>` writes `<name>-<label>.md` next to it.

## 2. Environment and fairness pins

- **Container limits, the same for both backends.** `run-benchmark.sh --cpus N --memory G`, default
  `--cpus 4 --memory 8`, i.e. `docker run --cpus 4 --memory 8g --memory-swap 8g`; `0` = no limit. The memory limit is
  the container's cgroup limit: it covers the server's buffer pool or cache, its processes and the page cache its file
  reads leave, so each store works within its own 8 GiB instead of sharing the VM's page cache; with `--memory-swap`
  equal to it the container cannot swap. Disk I/O is not limited (both use the same VM disk). Every evidence header has
  a `- containers:` line with what `docker inspect` reported for the running bench containers. A stage refuses a
  container that was created with other limits or server arguments; `relimit` recreates it with the requested ones on
  its data volumes, so a loaded corpus survives. `recreate` does the same unconditionally, with this run's limits
  (pass the same `--cpus`/`--memory`): a server with empty caches on the loaded corpus, the C1 what-if's cold state.
- `postgres:16`: `shared_buffers=4GB`, `effective_cache_size` = ¾ of the memory limit (`6144MB` at 8 GiB: shared buffers
  plus the file cache the limit leaves; `12GB` without a limit), `work_mem=64MB` (host port 55433). All other settings
  are the image defaults (vacuum: `maintenance_work_mem` 64 MB, `autovacuum_vacuum_cost_limit` -1, 3 workers).
- `mongo:7.0` (the tag Ditto's `deployment/docker/docker-compose.yml` pins), `--storageEngine wiredTiger --noscripting
  --wiredTigerCacheSizeGB 4`: the same 4 GB as PG's `shared_buffers` (host port 57017). No replica set — Ditto's
  compose runs standalone; consequently no oplog (WAL column is PG-only).
- Evidence written before the limits — the unlabelled 2026-07-13/14 files (both containers side by side), the
  2026-10-02 C1 retention files, the 2026-10-20 C1 what-if and the 2026-10-21 C3/C4/C5 files (one container at a
  time) — pinned only the buffer/cache sizes: no `--cpus`/`--memory`, `effective_cache_size=12GB`, and the containers
  shared the VM's page cache.
- pgjdbc with `prepareThreshold=0` (every execution custom-planned — the search bench's
  generic-plan-flip finding, adopted as protocol).
- Mongo writes use WriteConcern JOURNALED (the plugin's `journal-write-concern = Journaled`);
  PG commits with default `synchronous_commit=on`. Both fsync on the write path.
- The Mongo append twin includes the plugin's fire-and-forget insert into the capped
  `things_realtime` collection (Ditto leaves `realtime-enable-persistence = true`).
- All operation shapes are 1:1 transcriptions — sources listed in the implementation plan's
  "Ground-truth shape reference".

## 3. Corpus

Deterministic from `(seed, pidIndex)` (default seed 42): pids `thing:bench.nsNN:pid-XXXXXXXX`,
namespaces Zipf over 50; journal depth piecewise log-uniform with median 20, p99 2 000, cap 50 000
(analytic mean ≈ 139 events/pid — at the default 1M pids that is ~139M journal rows, ~60-75 GB per
backend; `--count 500000` matches the design's ~40 GB-per-backend budget); things-shaped event JSON
300 B–1 KB; snapshots every 500 events (things default `snapshot.threshold`), 2–10 KB, carried by
the ~3.4% of pids with depth ≥ 500; PG additionally gets one `things_journal_seq` row per pid.
No journal tags unless the load runs with `BENCH_PING_TAG_MIX=true` (PostgreSQL only; evidence
`results/ping-partly-tagged-*-250k*.md`, see §5, runs in §6), and no DELETED-lifecycle snapshots (all corpus entities
live) — the PG loader still builds exactly the persistence schema's `things_*` indexes and storage parameters
(taken from `PostgresSchema.ddlStatements()`; schema v3 has no lifecycle index).

Row timestamps: every row is written at 2026-01-01 + sn seconds, months older than any cleanup
retention. `--fresh-share S` (`-Dbench.freshShare`) instead writes ALL rows of a share S of the pids
(chosen per pid from the seed, independent of depth) at `--fresh-base` + sn seconds (default: the
previous UTC midnight), so those pids have only snapshots younger than a 3-day retention while the
rest stay old; pids, depths, sequence numbers and payload sizes are unchanged, and at share 0 the
generator and the PG load are the corpus above byte for byte. Every Mongo load (share 0 included)
now gives snapshots an `_id` that embeds the snapshot's write time, because the cleanup min-age
filter on Mongo reads `_id`, not `ts`; in production that `_id` is generated by mongod on the
snapshot upsert (`replaceOne(…, upsert)`), so its timestamp is the write time too. A share-0 Mongo
load therefore differs from the 2026-07-13 documents in the snapshot `_id`s only (`things_snaps`
index size 3.0 -> 3.2 MB); no scenario except the C1 retention variant reads them. Load evidence
of a fresh-share corpus goes to `load-*-fresh-share-S.md`.

## 4. Method

Per scenario: 3 warmup + N timed executions with varying-but-deterministic binds; p50/p95/max (nearest-rank);
N = 20 by default (`BENCH_ITERATIONS`). The container-limited run times N = 50 except R1–R3 (N = 20: at
`--count 250000` only 26 OUTLIER-class pids exist and R1–R3 cycle their picks, so 50 would read most OUTLIER pids twice).
Sustained scenarios (W3/C3/M1) sample 30 s windows. Cleanup
scenarios pin `DefaultCleanupConfig`: reads-per-query 100, writes-per-credit 100,
history-retention 0d, delete-final-deleted-snapshot false. The C3 sweep runs unthrottled by default
— Ditto's credit gate (3×100-row batches per 3 s ⇒ ≤100 rows/s) is structurally replicated but
would need days for this corpus; evidence quotes the computed default-pace duration
(`BENCH_SWEEP_PACE=credits` enables the real pace; the `window` stage (scenario C5) always runs at the credit pace,
for `BENCH_SWEEP_MAX_SECONDS`).
The 2026-10-21 C3/C4/C5 runs time 50 executions (`BENCH_ITERATIONS=50`) on distinct pids: C4 never re-reads a pid, so a
row's n (in its label) can be below 50.

Plans: one representative plan per statement shape, captured outside the timed loops.

- PostgreSQL: `EXPLAIN (ANALYZE, BUFFERS)` for every scenario. ANALYZE executes the statement, so writes run inside a
  transaction that is rolled back: the rows are discarded; what an aborted transaction leaves (a dead tuple per
  inserted row, a consumed `seq` value) stays. A rolled-back plan shows the executor's work, not the commit's WAL
  flush. `COPY` (load), `VACUUM` and `ANALYZE` have no plan.
- MongoDB: `explain` with `executionStats` for finds and aggregates, and — through the `explain` command — for the
  cleanup `deleteMany` (as a `delete` command) and the snapshot `replaceOne` upsert (as an `update` command); explain
  of a write applies nothing and reports `nWouldDelete` / `nWouldModify` / `nWouldUpsert`. The command has no `insert`
  or `compact` form: the appends (journal `bulkWrite`, the realtime insert) and `compact` are timed only.

| scenario | PostgreSQL plan(s) | MongoDB plan(s) |
|---|---|---|
| R1 / R2 / R3 | latest snapshot (P99 pid) / OUTLIER full replay / highest sn | find |
| W1 | append transaction (event INSERT + `journal_seq` upsert) at the W1 pid's next sn, rolled back | none (insert) |
| W2 | snapshot upsert, fresh and overwrite, rolled back | `update` explain, fresh and overwrite |
| W3 | append transaction on a churn pid after the run, rolled back | none (insert) |
| C1 | page at the mid-corpus lower bound | aggregate |
| C2 | per class, on the first warm-up pid before its cleanup: both min-sn reads and the first delete batch per table (rolled back) | min finds, `delete` explains |
| C3 | first page of the pid stream and the first pid's cleanup, before the sweep (deletes rolled back) | aggregate, finds, `delete` explains |
| C4 | post-sweep full replay of an untimed P99 pid | find |
| M1, M1b | page at the stream start, cleanup of the first snapshotted pid (rolled back), churn append (rolled back), before the run | aggregate, finds, `delete` explains; none for the append |

M1 (`mixed-sweep`) runs where C3 ran in the 2026-07 pipeline: on a load that has had `recovery`, `write` and `cleanup`
(C2 consumed its 46 pids' backlog), after C3's settling `VACUUM (ANALYZE)`, one unthrottled sweep pass over the whole
remaining backlog (exactly C3's sweep, 21,054,465 events at `--count 250000`) starts, W3-shaped churn (8 workers × 100
pids, 500/s, 600 s) starts right after it, and the pass runs to its end. No `VACUUM FULL` or `compact` runs before it.
C3 and C4 get a fresh load of their own: C2 cleans 23 of the 26 OUTLIER-class pids that C4 replays, so on a shared load
C4's OUTLIER rows would time pids cleaned long before the sweep. M1b is the 2026-07 M1 (`mixed`): concentrated churn
after C4's offline reclamation, while a sweeper cycles over the churn's own debris.

## 5. Headline results

From the committed full-scale evidence of 2026-10-21 at `--count 250000` (35,070,304 journal rows loaded per
backend), schema v3 with its shipped storage parameters, with the §2 container limits (4 CPUs, 8 GiB per container),
one store at a time, evidence files `<scenario>-limits-pg.md` / `<scenario>-limits-mongo.md`. Two loads per backend:
R1–R3, W1–W3, C1, C2 and then M1 ran on a corpus loaded before the limits were applied (the same deterministic data;
the containers were recreated with the limits on their volumes), in the 2026-07 order with M1 in C3's place; C3 and C4
ran on a fresh load under the limits, so C4 replays pids the sweep cleaned (C2 cleans 23 of the 26 OUTLIER pids).
Verdict rule (design §7): flag any scenario where PG p95 > 2× Mongo p95. Values are p50 / p95 ms (n)
unless noted; full tables and plans live in the evidence files. Host-memory reliability: every measured stage of both
backends (recovery, wcheck, w3, c1, c2, mixed-sweep on load 1; c3, c4 on load 2) passed the host-memory gate on its
first call and had 0 pages swapped out between its before and after samples, with ≥ 72 % of memory free in both; no
stage was repeated and every number below is reliable. These numbers come from a different
environment than every earlier run (limits; one store at a time), so movements against the History values are not
attributable to either change alone. C1's former 21× flag was closed by the loose-index-scan rewrite; under the limits
the PG page is flagged again, 15× Mongo's (row below)
(see `docs/superpowers/specs/2026-07-13-cleanup-loose-index-scan-design.md`).

| scenario | PG p50 / p95 | Mongo p50 / p95 | verdict |
|---|---|---|---|
| R1 latest snapshot (worst class) | P99 0.63 / 1.01 (n=20) | P99 0.73 / 0.80 (n=20) | Mongo faster (1.3×) |
| R2a tail replay (worst class) | OUTLIER 4.74 / 6.48 (n=20) | OUTLIER 5.22 / 7.65 (n=20) | parity |
| R2b full replay, outlier 40–50k events | 238.57 / 258.61 (n=20) | 227.84 / 272.50 (n=20) | parity; absolute < 2 s bar MET |
| R3 highest sn | 0.53 / 0.82 (n=20) | 0.33 / 0.64 (n=20) | Mongo faster (1.3×) |
| W1 single append | 2.37 / 2.90 (n=50) | 1.35 / 2.30 (n=50) | Mongo faster (1.3×) |
| W2 snapshot write (fresh / overwrite) | 1.72 / 2.33; 1.34 / 1.89 (n=50) | 1.78 / 3.00; 1.35 / 1.54 (n=50) | PG faster (1.3×) / parity |
| W3 sustained churn (achieved of 500/s) | 499.8/s; per-op window p50 4.11–4.93, p95 6.63–9.02 | 499.8/s; per-op window p50 1.85–2.80, p95 3.30–8.32 | both hold; per-op latency: see the W3 note below |
| C1 cleanup pid-stream page, minAge 0 | 16.95 / 25.47 (n=50) | 1.06 / 1.66 (n=50) | FLAG: PG 15.3× slower (July: PG faster; the 2026-10-02 single-backend re-run: PG 2.5–2.8× slower at p50). The page's EXPLAIN ran 1.37 ms in the server on shared-buffer hits; the rest of PG's time was not isolated (the timed page also returns the 100 snapshot payloads, see the what-if below) |
| ↳ C1 what-if under the limits, **NOT PRODUCTION** (the same corpus shape and limits, PG only, 2026-10-22, 3 runs, run 1 quoted) | cold (server recreated right before): production 4.07 / 4.48, payload-free 0.48 / 0.78; warm (after one pass over the pid stream): production 2.60 / 2.84, payload-free 0.33 / 0.39 (n=50, minAge 0) | 1.06 / 1.66 (the row above; not re-run) | payload share cold 88 % / 83 %, warm 87 % / 86 %; of the cold production page: 2.27 ms payload, 1.47 ms cold cache, 0.33 ms rest (p50); payload-free vs Mongo: at or below cold, at or below warm (§7.12) |
| C2 single-pid cleanup (OUTLIER total per pid) | 987.45 / 1820.02 (n=20) | 502.13 / 632.84 (n=20) | FLAG: PG 2.9× slower (p50 2.0×; July: parity) |
| C3 full sweep (22.13M rows, clean load) | 837.5 s (26,461 rows/s) | 297.6 s (74,460 rows/s) | Mongo 2.8× faster (durations); identical deletions both sides; the 2026-10-21 run without limits: 778 s / 292 s |
| C4 post-sweep replay (tail P99 / full-scan OUTLIER / full-scan P99) | 4.33 / 8.56 (n=50); 1.25 / 1.92 (n=23); 1.27 / 1.62 (n=50); the post-sweep autovacuum of `things_journal` ended 36 s after the sweep | 2.87 / 4.62 (n=50); 1.65 / 2.00 (n=23); 1.62 / 1.99 (n=50) | parity on full-scan OUTLIER (1.0×) and full-scan P99 (0.8×) |
| **M1 churn during the mass sweep** (21.05M rows, after recovery/write/cleanup on its load; achieved of 500/s over the windows that ended while the sweep was deleting) | 500.0/s over 20 of 20 windows; window p50 2.57–4.66, p95 12.30–36.72; mass sweep 21,054,465 event + 33,643 snapshot rows in 890.2 s (23,690 rows/s) | 500.3/s over 13 of 20 windows; window p50 1.92–3.30, p95 5.01–20.89; mass sweep the same rows in 404.1 s (52,188 rows/s) | both hold; window p95 vs the same backend's W3: PG 12.30–36.72 vs 6.63–9.02, Mongo 5.01–20.89 vs 3.30–8.32 |
| M1b churn under the light post-reclamation sweep (2026-07-13, before the limits; `m1-cleanup-under-traffic.md`) | 499.8/s | 499.9/s | both hold; 296,576 rows swept live on each (the churn's own debris) |

History (schema v1, before the rewrite and the v2 index drop): C1 was 887.14 ms PG vs 42.47 ms
Mongo p95 (242 MB temp spill per page; Seq Scan + Sort over ~40k rows) and W2 was 4.31 / 3.30 ms
PG vs 6.25 / 3.35 ms Mongo. Those runs are kept verbatim, with provenance headers, in
`../src/test/resources/bench/results/history/2026-07-13-schema-v1/` (`c1-pid-stream-page.md`,
`w2-snapshot-write.md`); they are superseded by the current files above.
The 2026-07-13/14 values of the rows the container-limited run replaced (co-resident, no limits, the unlabelled
files; p95 ms PG / Mongo): R1 1.14 / 1.33, R2a 10.90 / 10.86, R2b 522 / 531, R3 0.84 / 1.34, W1 2.71 / 3.03, W2
2.96 / 2.33 vs 4.79 / 4.25 (fresh / overwrite), W3 499.8/s / 499.9/s, C1 minAge 0 8.50 / 10.71 (and the 2026-10-02
single-backend re-run 2.87 p50 / 3.99 p95 vs 1.02 / 1.27), C2 993 / 1163.
The July C3/C4 runs (schema v2, co-resident, 20 iterations; `c3-full-sweep.md`, `c4-before-after.md`): C3 797 s PG /
488 s Mongo; C4 16.9 (tail) / 98.5 (full-scan; the 2nd-highest of 20 samples, max 366.7) ms PG vs 13.2 / 6.7 ms Mongo,
the settle loop timing out at 900 s with dead tuples flat at 11.3M. The 2026-10-21 rows (runs without limits, below
the table) supersede them.

W2 noise caveat: the v1 -> v2 W2 gain (4.31 -> 2.96 and 3.30 -> 2.33 ms, about 30 %) compares two
separate runs, and the unchanged Mongo numbers moved between them as well (fresh 6.25 -> 4.79 ms,
overwrite 3.35 -> 4.25 ms), by about the size of the claimed gain. Treat "PG faster since v2" as
directional, not as a measured 30 % improvement.

W3 note (per-op latency): in the 2026-07-13 W3 run PG's per-op p50 is 6.53–7.40 ms (p95 9.99–12.43)
against MongoDB's 1.79–3.20 ms (p95 2.85–14.13), although both hold the rate. In the container-limited run
(`w3-sustained-churn-limits-pg.md`, `w3-sustained-churn-limits-mongo.md`) PG's per-op window p50 is 4.11–4.93 ms
(p95 6.63–9.02) against MongoDB's 1.85–2.80 ms (p95 3.30–8.32), with the same lock-step pacing. A profiling re-run on a
50k corpus (`../src/test/resources/bench/results/w3-latency/README.md`) found the gap to be mostly a
bench artefact, not the append path:
- W3's eight workers start their 16 ms pacing schedules when their connections are ready, which
  happens nearly at once (0.06–2.0 ms apart in the re-run's recorded schedules), so every 16 ms eight
  appends arrive together. PostgreSQL flushes WAL under one lock, so such a burst waits for two WAL
  fsyncs back to back (3.9–4.0 commits per flush in the durable aligned 8-worker runs, 3.1 in one
  whose schedules spread over 2 ms, 1.0 with even arrivals) and the commit step takes 1.6–2.1 fsyncs.
  MongoDB's journaled writes absorb the same bursts better.
- With the schedules spread evenly over the period (`-Dbench.w3.stagger=true`), PG's p50 is
  1.0–1.7 ms vs MongoDB's 1.0–1.1 ms; in same-period interleaved runs PG stays ≈ 0.5 ms above
  MongoDB (1.56 / 1.60 vs 1.10 / 1.02 ms). PG's p95 is 2.2–2.7 ms on W3's pgjdbc shape (2.2–4.9 ms
  on the production append path; the 4.9 ms run overlapped an autovacuum) vs MongoDB's 3.3–6.4 ms.
  Even arrivals are the best case and lock-step the worst; production appends arrive at random
  times and were not measured, so production latency lies in between.
- Run-to-run variance in the re-run: aligned runs of the same shape ranged from 2.9 to 6.5 ms p50,
  even ones from 1.0 to 1.6 ms. Differences between single runs smaller than that are not effects.
- The committed run's 124–131 MB WAL per window come from the bulk-loaded corpus: COPY leaves a few
  hundred bytes free on most pages and the load leaves them in the free space map, so each churn
  insert lands on a different old page and writes a full-page image (≈ 8.7 kB WAL per op instead of
  ≈ 1.3 kB). Whether this state, or the write-back after a bulk load, also costs latency was not
  isolated: a re-run during the post-load autovacuum (100 % page reuse, heavy write-back) ran at
  3.8 ms p50, inside the aligned spread.
- Neither the journal_seq upsert, nor the number of connections, nor a one-statement append changes
  the result. W3 times a pgjdbc transcription, not the production path; the production
  `PostgresPersistenceOperations` append (BEGIN as an extra round trip) was ≈ 0.1 ms slower in
  interleaved runs with even arrivals. Under the lock-step bursts it was slower in single runs, which
  does not isolate the effect.
Quote W3 latency with this note. A representative W3 protocol would need randomized arrivals, with
lock-step and even arrivals as bounds, on a settled corpus (autovacuum finished).

Cross-backend determinism: C3 deleted identical 21,054,465 event + 33,613 snapshot rows on both
backends; M1 swept identical 296,576. In the container-limited run C3 (clean load) deleted identical 8,581 pids /
22,126,919 event + 35,690 snapshot rows on both backends (the same counts as the 2026-10-21 runs without limits), and
M1's mass sweep identical 8,582 pids / 21,054,465 event + 33,643 snapshot rows: July's C3 event count; the 30 more
snapshot rows come from W2's probe pid, which wrote 53 snapshots at N = 50 against 23 at N = 20.
Credit-pace projection for C3: ~105 h (the real Ditto pace —
"days", as designed). Disk note for full runs: during the sweep the corpus bloats by roughly the
deleted-row volume until C4's offline reclamation (observed peak ~80 GB total at 250k vs ~30 GB
steady) — budget peak, not steady state; 500k needs ≳130–150 GB free.

| scenario | evidence file |
|---|---|
| R1 latest snapshot | `../src/test/resources/bench/results/r1-latest-snapshot.md` |
| R2 replay (tail + full) | `../src/test/resources/bench/results/r2-replay.md` |
| R3 highest sn | `../src/test/resources/bench/results/r3-highest-sn.md` |
| W1 append / W2 snapshot / W3 churn | `w1-single-append.md`, `w2-snapshot-write.md`, `w3-sustained-churn.md` |
| C1 pid stream / C2 single-pid / C3 sweep / C4 proof | `c1-*.md`, `c2-*.md`, `c3-*.md`, `c4-*.md` |
| C1 with the production age filter | `../src/test/resources/bench/results/c1-pid-stream-page-retention3d-pg.md`, `../src/test/resources/bench/results/c1-pid-stream-page-retention3d-mongo.md`, `../src/test/resources/bench/results/load-pg-fresh-share-0.3.md`, `../src/test/resources/bench/results/load-mongo-fresh-share-0.3.md` |
| C1 payload what-if (NOT PRODUCTION) | ../src/test/resources/bench/results/c1-payload-free-what-if-retention3d-pg-run1.md, …-run2.md, …-run3.md, ../src/test/resources/bench/results/load-pg-fresh-share-0.3-payload-whatif.md |
| C1 payload what-if under the container limits (NOT PRODUCTION) | `c1-payload-free-what-if-retention3d-pg-cold-limits-whatif-run{1,2,3}.md`, `…-pg-warm-limits-whatif-run{1,2,3}.md`, `c1-pid-stream-page-limits-whatif-run{1,2,3}.md`, `load-pg-limits-whatif.md` |
| C3 / W1 / W2 / C4 on schema v3 (2026-10-21; PG runs unreliable, §5) | `c3-full-sweep-<label>.md`, `w1-single-append-<label>.md`, `w2-snapshot-write-<label>.md`, `c4-before-after-<label>.md`, `load-pg-<label>.md` for `cl2000-pg` (cost limit 2000), `cldefault-pg` (v2 storage parameters), `cl800-pg` (800); `load-mongo-mongo-ref.md`, `c3-full-sweep-mongo-ref.md`, `c4-before-after-mongo-ref.md` |
| C5 credit-paced window | `c5-credit-paced-window-credits-pg.md`, `load-pg-credits-pg.md` |
| Container-limited run (all scenarios) | `<scenario>-limits-pg.md`, `<scenario>-limits-mongo.md`, `load-pg-limits-pg.md`, `load-mongo-limits-mongo.md` |
| M1 during the mass sweep | `m1-cleanup-during-mass-sweep-limits-pg.md`, `m1-cleanup-during-mass-sweep-limits-mongo.md` |
| M1b mixed (2026-07) | `m1-cleanup-under-traffic.md` |

### Runs without container limits that this run does not repeat

The C1 retention row of 2026-10-02 comes from a single-backend run, one container at a time (disk), on a fresh-share
corpus, without container limits. It is not directly comparable with the container-limited table above. The per-pid
view on that corpus (Mongo pages of ~22 pids on a never-cleaned backlog) is §7 caveat 11.

The C3, C4 and C5 rows of 2026-10-21 come from single-backend runs (one container at a time) on schema v3: 50 timed
executions on distinct pids, a manual `VACUUM (ANALYZE)` before the sweep, offline reclamation skipped, and the
`postgres:16` image's vacuum defaults (`autovacuum_vacuum_cost_limit` -1, i.e. 200 per 2 ms; `maintenance_work_mem`
64 MB; 3 workers). Their July values are in the History paragraph. Host-memory reliability: on every PostgreSQL run
macOS swapped out 12–19k pages (16 KiB) during the sweep's first minutes while ≥ 81 % of memory stayed free, so each
PG C3 stage, and with it each PG configuration run, is marked unreliable (a repeat showed the same); the 2 h C5 window
lost 36,995 pages in two bursts, about 3 and 57 minutes in, and is marked unreliable as well; the C4 stage of
the shipped configuration and the whole MongoDB run had no swap-outs. Treat the PG C3/C4 numbers as indicative. Across
the two loads of each configuration the post-sweep autovacuum finished 17 / 52 s (cost limit 2000), 722 / 403 s (800)
and 2,936 / 2,952 s (v2 storage parameters) after the sweep; the evidence files are the second loads'. C4's
cost-limit decision rests on these runs.

| scenario | PG p95 | Mongo p95 | verdict |
|---|---|---|---|
| C1 cleanup page, production 3-day age filter (fresh share 0.3, single-backend run 2026-10-02) | 3.03 p50 / 3.39 p95 | 1.15 p50 / 1.63 p95 | FLAG: PG 2.6× (p50) / 2.1× (p95) slower per page (3 runs: 2.3–2.7× / 1.9–2.2×); the age filter itself costs ≈ 0 on both and keeps the plan (PK index-only + backward top-1, no Sort/spill). PG is a lower bound (§7.10); follow-up: payload-free cleanup page — sized by the what-if note below; no production change |
| C3 full sweep (22.13M rows; schema v3, single-backend 2026-10-21) | 778 s | 292 s | Mongo 2.7× faster (delete-batch bound); identical deletions both sides; PG run unreliable (host memory, above) |
| **C4 post-sweep replay, schema v3** (distinct pids; the autovacuum after the sweep finished 52 s after it with the shipped storage parameters — 52 s with cost limit 2000, 2,952 s with v2's, 403 s with 800; July: not within 900 s) | tail P99 4.74 p50 / 7.23 p95 (n=50); full-scan OUTLIER 1.23 / 1.94 (n=23); full-scan P99 1.22 / 1.60 (n=50) | tail P99 3.16 p50 / 5.09 p95 (n=50); full-scan OUTLIER 1.56 / 2.00 (n=23); full-scan P99 1.47 / 1.98 (n=50) | FLAG closed: PG ≤ 2× (1.0× / 0.8×); PG run unreliable (host memory, above) |
| C5 credit-paced cleanup window (2 h at ≤ 100 rows/s, production order, shipped storage parameters) | 405,515 rows deleted (56 rows/s); dead tuples peak 348,401 (60 s samples) vs the 0.01 trigger 350,737; one autovacuum, 1 h 45 min into the window, cleared them; replay of swept pids as left 1.64 / 3.50 vs after VACUUM 1.57 / 3.18 (n=50), but both halves are pids that autovacuum had already cleaned (sweep positions 6–105 of 184; caveat 5) | — (MongoDB removes index keys in the delete itself) | replay slowdown of pids swept since the last autovacuum not measured (the 1.1× ratio is not a test of it); at credit pace ≈ 1 % of the journal turned dead before one autovacuum cleared it; run unreliable (host memory, above) |

C1 payload what-if, **NOT PRODUCTION** (single-backend PG run 2026-10-20, schema v3, the 2026-10-02
fresh-share corpus mode; Ditto does not issue this statement): the C1 page SQL without
`snapshot::text` takes 0.54 p50 / 0.80 p95 ms at the 3-day filter against 2.91 / 3.56 ms for the
production page in the same run, interleaved, so the payload is 82 % (p50) / 78 % (p95) of the page
(3 runs: 80–82 % / 77–79 %; at minAge 0: 84 % / 83 %). A page carries 588 kB of payload text on
average. Against Mongo's 1.15 / 1.63 ms from the 2026-10-02 run (not re-run): below. Decoding and
JSON-parsing the payloads as production does adds 2.71 ms p50 / 4.28 ms p95 on top (pgjdbc +
ditto-json estimate; production reads through r2dbc). See caveat 12.

C1 payload what-if under the container limits, **NOT PRODUCTION** (PG only, 2026-10-22, the plain 250k corpus of the
C1 row above, 4 CPUs / 8 GiB, N = 50, 3 runs, run 1 quoted; the `c1` stage re-run in the same state: 4.11 / 4.80 ms
against the row's 16.95 / 25.47). Right after the server was recreated (cold shared buffers; the VM's page cache is not
dropped) the production page takes 4.07 / 4.48 and the payload-free page 0.48 / 0.78 ms at minAge 0, and `things_snaps`
read 6,697 blocks into shared buffers during the timed pairs in 80.5 ms (0.012 ms per block: the VM's page cache served
them; the C1 row's run followed a fresh load that had pushed these blocks out of the page cache — a trial right after
this link's load, n = 5, not committed, read the same kind of blocks at 0.095 ms each and took 22.63 / 27.58 ms per
production page); after one untimed pass over the whole pid stream (warm) 2.60 / 2.84 and 0.33 / 0.39 ms with 0 blocks
read. So of the cold production page's 4.07 ms, 2.27 ms is the payload (warm production − warm payload-free), 1.47 ms
the cold cache (cold − warm production page) and 0.33 ms the rest (the warm payload-free page); p95: 2.45 / 1.64 /
0.39 ms of 4.48. Payload share under the limits: cold 88 / 83 %, warm 87 / 86 % (p50 / p95; 3 runs: cold 88–89 /
83–86 %, warm 87 / 86 %). Against Mongo's 1.06 / 1.66 ms from the limits run (not re-run): the payload-free page is at
or below cold and at or below warm (every run). At the 3-day filter the pairs read 2.66 / 2.88 vs 0.37 / 0.44 ms (the
cold run's second pair, warm by then) and 2.66 / 2.91 vs 0.38 / 0.43 ms (warm). A production change is decided on
these numbers; none is made here. See caveat 12.

### Persistence ping on a partly tagged corpus

The persistence ping was first measured on 1,000,000 synthetic pids with 2 events each
(`results/ping-keyset-paging-1m.md`). Here it walks the 250k corpus above (≈ 140 events per thing, 35,070,304 journal
rows), loaded with `BENCH_PING_TAG_MIX=true`: a deterministic mix of 32,038 tagged things (10 % spread over the key
space and a contiguous 1 % block at its end, tagged on every event; 1 % tagged on older events only; 1 % on the newest
event only; 87 % untagged; `priority-<n>` 0–9 on the tagged events). The expected pids of every walk come from the
generator (counts and SHA-256 in `results/load-pg-partly-tagged-250k.md`). The bench corpus is `things_*`; the things
service runs no ping, so the walks stand for the policies ping (ID order) and the connectivity ping (priority order):
same SQL, other table name. 8 GiB / 4-CPU container, no throttle, 500 pids per window. Numbers from
`results/ping-partly-tagged-walks-250k-repeat.md` (every walk RELIABLE):

| walk | statements | delivered = expected | walk | p50 | p95 | p99 | slowest (of the 60 s `statement_timeout`) |
|---|---|---|---|---|---|---|---|
| ID order, any tagged event (policies) | 501 | 32,038, missing 0, extra 0 | 289.3 s | 571.69 ms | 813.37 ms | 965.77 ms | 1,037.39 ms (1.73 %) |
| ID order, newest event tagged | 501 | 29,721 | 26.1 s | 49.00 ms | 80.93 ms | 95.65 ms | 181.32 ms (0.30 %) |
| priority order (connectivity): windows, sorted by priority | 501 | 32,038, 0 out of priority order | 183.1 s | 347.48 ms | 597.02 ms | 715.47 ms | 799.29 ms (1.33 %) |
| same-run control, 1M × 2 events, ID order / priority order | 2,001 / 2,001 | 10,000 / 10,000 | 3.4 s / 3.3 s | 1.47 / 1.47 ms | 1.76 / 1.76 ms | 2.25 / 2.06 ms | 3.46 / 4.32 ms |

- What the long histories cost: per window ≈ 390× the 2-event window of the same container (571.69 ms vs 1.47 ms
  p50), because a window reads every row of its untagged things; median per tenth flat (491–607 ms in the any-event
  ID walk, 307–386 ms in the priority walk). The first run on the same load (`results/ping-partly-tagged-walks-250k.md`)
  measured walk times and medians within 5 % of these (priority walk 189.6 s, p50 351.19 ms) and a slowest priority
  statement of 941.40 ms; its priority walk ended at 49 % free host memory and is marked unreliable, hence the repeat.
- The priority order reads the same windows as the ID order; before, it was one statement that PostgreSQL ran as a
  sequential scan of the whole 36 GB journal (69.9 s, cancelled by `statement_timeout`; the previous version of
  `results/ping-partly-tagged-walks-250k.md`).
- Recovery (`results/ping-partly-tagged-recovery-250k.md`, `…-250k-repeat.md`): the ID walk was broken by
  `pg_terminate_backend` and by a container restart; each break cost 3.6–4.2 s from the trigger to the first pid after
  the failed window (two failed attempts each, then the retry from the window's cursor), and the walk delivered 32,038
  with 0 missing and 0 duplicates, in both runs.
- The actor (`results/ping-partly-tagged-actor-250k.md`, `…-250k-repeat.md`): the production `PersistencePingActor`
  with the policies settings (interval 10m, rate raised to 1,000 pings/s) pinged every expected pid once per walk and
  no other in four walks; walk 2 failed by injection after 16,019 pings and walk 3 continued after the last one; no
  other WARN or ERROR, no skipped tick; 285–299 s per full walk (walks 1 and 4). Both runs gave the same result.
- Host memory: the walks repeat and the actor repeat had 0 swap-outs and ≥ 50 % free around every measured part. The
  first walks run (its priority walk ended at 49 % free), the first actor run and both recovery runs (48–49 % free at
  their start; the recovery repeat also swapped out 16,020 pages) are marked unreliable; the recovery results are
  about delivered pids and resume points, not timings.

## 6. Reproducing

```
benchmark/run-benchmark.sh config                       # the docker run lines the options resolve to; starts nothing
benchmark/run-benchmark.sh up build load settle         # containers + corpus; settle waits for PG's post-load autovacuum
benchmark/run-benchmark.sh recovery write cleanup       # R1-R3, W1-W3, C1, C2 (C2 consumes its own 46 pids)
benchmark/run-benchmark.sh mixed-sweep                  # M1 during the mass sweep (destroys the cleanup backlog)
benchmark/run-benchmark.sh load settle                  # a fresh backlog for C3/C4
benchmark/run-benchmark.sh sweep mixed                  # C3, C4 (ends with VACUUM FULL / compact), then M1b
benchmark/run-benchmark.sh status                       # corpus / container / limits / results state
benchmark/run-benchmark.sh all --quick --yes            # demo pipeline (numbers not comparable)
benchmark/run-benchmark.sh down --purge                 # remove containers + volumes (--backend pg|mongo: one store)
```

Re-run `load` after `sweep` for a pristine corpus — and before re-running `write` or `mixed`:
W1/W3/M1 write fixed, deterministic probe pids with plain inserts, so a second run against the
first run's leftover rows fails on duplicate keys.

`--backend pg|mongo` restricts any stage, `down` included. Pass the same `--count` (and `--cpus`/`--memory`, if not the
defaults) to every stage. `relimit` recreates loaded containers with new limits or server arguments on their data
volumes. `recreate` recreates them unconditionally with this run's limits (cold server caches). `mixed-sweep` refuses
a corpus whose backlog is gone (fewer than 90 % of the generated snapshot rows).
`BENCH_PROBE_SUFFIX=<s>` lets `wcheck` run again on one load, `BENCH_W3_PID_PREFIX` the same for `w3`;
`BENCH_RESULTS_DIR=<absolute path>` sends evidence elsewhere (trial runs).

W3 profiling knobs (opt-in Maven `-D` properties for `AppendBench#w3SustainedChurn`; the defaults
reproduce the committed W3 run): `bench.w3.stagger=true` (spread the worker schedules evenly),
`bench.w3.pgShape=txn|no-hwm|one-statement|r2dbc|r2dbc-phased` (`r2dbc` drives the production
`PostgresPersistenceOperations` on a pool from the production `ConnectionPoolFactory`),
`bench.w3.mongoWriteConcern=journaled|w1`, `bench.w3.waitSampleMillis=10` (pg_stat_activity
sampler), `bench.w3.pidPrefix=<unique per run>` (repeat W3 without a reload) and
`bench.w3.evidenceFile=<path under results/>`. Every PG churn run (W3 and M1, knobs or not) also
records per-step timings, per-window WAL flush counts, the heap placement of its rows and server
counter deltas, all outside the timed path: per 30 s window the sampler connection reads a few
`pg_stat_*` views (the `pg_stat_checkpointer` read fails on PostgreSQL 16, is skipped and shows up
in the server log), and after the run it pauses 2 s and runs one placement query. M1 and M1b share this
churn engine, so its concurrent sweep keeps running for those extra ≥ 2 s before it is stopped
(slightly more rows deleted than before), its churn connections are tagged
`application_name=w3-churn`, and a dead churn worker now aborts the run instead of being ignored.
With `bench.w3.stagger=true` the schedules start 1 s after the run's clock, so window 0 holds about
29 s of ops and the last second is counted in the totals only. The r2dbc shapes append
`?applicationName=…` to `bench.pg.url` and need a URL without a query string.

C1 with the production age filter (`retention` stage, default `--retention 3d`) needs a corpus with
fresh-only pids; loading one backend at a time halves the peak disk (~42 GB PG, ~30 GB Mongo at
`--count 250000`):

```
benchmark/run-benchmark.sh up load retention --backend pg --count 250000 --fresh-share 0.3 \
    --fresh-base 2026-10-01T00:00:00Z --yes
benchmark/run-benchmark.sh down --purge
benchmark/run-benchmark.sh up load retention --backend mongo --count 250000 --fresh-share 0.3 \
    --fresh-base 2026-10-01T00:00:00Z --yes
```

Pin `--fresh-base` to the same instant for both loads: at least 14 h (the depth cap in seconds)
before the load (the loaders refuse future rows) and less than the retention before the
`retention` run — the stage checks the age mix against the generator and fails otherwise. It writes
`c1-pid-stream-page-retention3d[-pg|-mongo].md`. On PG, both C1 stages wait (bounded by
`BENCH_C1_VACUUM_TIMEOUT`, default 3600 s) until `things_journal` and `things_snaps` have each been
vacuumed since the load and no vacuum is in progress (after a 250k load the insert-triggered
`things_journal` autovacuum runs for minutes up to half an hour), and record the vacuum state at
timing start and end.

The C1 payload what-if (`payload-whatif` stage, NOT PRODUCTION, PG only) runs on the same kind of PG
load. It times the production page against the same SQL without `snapshot::text`, interleaved, at
`--retention` and at 0, then the production page decoded and JSON-parsed as production's client
does. `BENCH_EVIDENCE_LABEL` numbers the runs (`c1-payload-free-what-if-retention3d-pg-<label>.md`);
a labelled load keeps the committed load evidence:

```
BENCH_EVIDENCE_LABEL=payload-whatif benchmark/run-benchmark.sh up load --backend pg --count 250000 \
    --fresh-share 0.3 --fresh-base <T>
for r in 1 2 3; do BENCH_EVIDENCE_LABEL=run$r benchmark/run-benchmark.sh payload-whatif --backend pg \
    --count 250000 --fresh-share 0.3 --fresh-base <T> --retention 3d; done
```

The what-if under the container limits (2026-10-22): the plain 250k corpus of the limits run, PG only; `recreate`
gives the cold state (the server is recreated on its volume even when the limits already match) and
`BENCH_C1_CACHE_STATE=warm` the warm one (one untimed pass over the whole pid stream first); a cold run that read no
block fails its result check. `c1` ran in the same cold state to reproduce the C1 row.

```
export BENCH_ITERATIONS=50
BENCH_EVIDENCE_LABEL=limits-whatif benchmark/run-benchmark.sh up load settle --backend pg --count 250000
for r in 1 2 3; do
    benchmark/run-benchmark.sh recreate --backend pg --count 250000
    BENCH_EVIDENCE_LABEL=limits-whatif-run$r benchmark/run-benchmark.sh c1 --backend pg --count 250000
    benchmark/run-benchmark.sh recreate --backend pg --count 250000
    BENCH_C1_CACHE_STATE=cold BENCH_EVIDENCE_LABEL=limits-whatif-run$r \
        benchmark/run-benchmark.sh payload-whatif --backend pg --count 250000
    BENCH_C1_CACHE_STATE=warm BENCH_EVIDENCE_LABEL=limits-whatif-run$r \
        benchmark/run-benchmark.sh payload-whatif --backend pg --count 250000
done
```

Every timed stage had the host-memory gate (above) before it and a sample right before and right after it.

The C3/C4/C5 rows of 2026-10-21 (schema v3): one backend at a time, `down --purge` between loads, and
for every stage

```
export BENCH_ITERATIONS=50 BENCH_C4_SETTLE_TIMEOUT=3600 BENCH_C3_VACUUM_FIRST=true BENCH_C4_OFFLINE_RECLAIM=false
export BENCH_EVIDENCE_LABEL=cl2000-pg    # cldefault-pg: also BENCH_PG_RELOPTIONS='autovacuum_vacuum_scale_factor = 0.01'
                                         # cl800-pg: also BENCH_PG_RELOPTIONS='autovacuum_vacuum_scale_factor = 0.01,
                                         #   autovacuum_vacuum_cost_limit = 800'
benchmark/run-benchmark.sh build up load --backend pg --count 250000
benchmark/run-benchmark.sh c3 --backend pg --count 250000
benchmark/run-benchmark.sh wcheck --backend pg --count 250000
benchmark/run-benchmark.sh c4 --backend pg --count 250000
benchmark/run-benchmark.sh down --purge
BENCH_EVIDENCE_LABEL=mongo-ref benchmark/run-benchmark.sh build up load --backend mongo --count 250000
BENCH_EVIDENCE_LABEL=mongo-ref benchmark/run-benchmark.sh c3 --backend mongo --count 250000
BENCH_EVIDENCE_LABEL=mongo-ref benchmark/run-benchmark.sh c4 --backend mongo --count 250000
benchmark/run-benchmark.sh down --purge
BENCH_EVIDENCE_LABEL=credits-pg benchmark/run-benchmark.sh build up load --backend pg --count 250000
BENCH_EVIDENCE_LABEL=credits-pg BENCH_SWEEP_MAX_SECONDS=7200 BENCH_SAMPLE_INTERVAL=60 \
    benchmark/run-benchmark.sh window --backend pg --count 250000
```

Between stages the runs waited until two host-memory samples 2 minutes apart showed no swapping (≤ 1,000 pages
swapped out, ≥ 50 % free), and every measured stage had such a sample right before and after it.

The persistence-ping runs of the partly tagged corpus (PostgreSQL only; label 250k):

    export BENCH_PING_TAG_MIX=true BENCH_EVIDENCE_LABEL=250k
    benchmark/run-benchmark.sh build up load settle --backend pg --count 250000   # load-pg-partly-tagged-250k.md
    benchmark/run-benchmark.sh ping --backend pg --count 250000                   # ping-partly-tagged-walks-250k.md
    benchmark/run-benchmark.sh ping-recovery --backend pg --count 250000          # restarts the container mid-walk
    benchmark/run-benchmark.sh ping-actor --backend pg --count 250000             # ≈ 31 min: four walks at interval 10m
    benchmark/run-benchmark.sh down --purge --backend pg

`BENCH_PING_ACTOR_RATE` (default 1000 pings/s) and `BENCH_PING_ACTOR_INTERVAL_SECONDS` (default 600) set the actor
stage; each evidence file has a host-memory sample right before and after every measured stage.

At 250k all stages have evidence (§5). The previous `ping-partly-tagged-walks-250k.md`, with the priority order as one
statement that exceeded `statement_timeout` on all 11 attempts, is in git history. A stage whose host-memory verdict
was UNRELIABLE was run once more with `BENCH_EVIDENCE_LABEL=250k-repeat` on the same load; both files are kept.

The container-limited run (`*-limits-pg.md`, `*-limits-mongo.md`): one store at a time, the other container stopped or
removed, and for every stage

```
export BENCH_ITERATIONS=50 BENCH_C4_SETTLE_TIMEOUT=3600 BENCH_C3_VACUUM_FIRST=true BENCH_C4_OFFLINE_RECLAIM=false
export BENCH_EVIDENCE_LABEL=limits-pg                     # limits-mongo for --backend mongo
benchmark/run-benchmark.sh relimit --backend pg --count 250000     # load 1: the loaded corpus, recreated with the limits
benchmark/run-benchmark.sh settle --backend pg --count 250000
BENCH_ITERATIONS=20 benchmark/run-benchmark.sh recovery --backend pg --count 250000
benchmark/run-benchmark.sh wcheck --backend pg --count 250000      # then w3, c1, c2 and mixed-sweep the same way
benchmark/run-benchmark.sh down --purge --backend pg
benchmark/run-benchmark.sh load --backend pg --count 250000        # load 2
benchmark/run-benchmark.sh settle --backend pg --count 250000
benchmark/run-benchmark.sh c3 --backend pg --count 250000          # then c4
benchmark/run-benchmark.sh down --purge --backend pg
```

Every measured stage was preceded by the host-memory gate of the 2026-10-21 runs (above) and had a sample right before
and after it; no stage was repeated: every gate passed on its first call and no measured stage swapped out a page.

## 7. Caveats — read before quoting numbers

1. Laptop-scale Docker; 10-minute windows — trends, not plateau claims.
2. Driver-level, not actor-level: no Pekko/serialization overhead on either side. The comparison
   is symmetric and fair; absolute service-level latency is higher.
3. MongoDB has no explain for inserts (the W1/W3/M1 appends) or `compact`: those are timed only. Its deletes and the snapshot upsert are explained through the explain command, which applies no write.
4. Synthetic corpus; payload knobs approximate no specific customer data set.
5. C3 pace is unthrottled by default (§4); Ditto's production pace is far slower and
   gentler — the dead-tuple/autovacuum observations under unthrottled load are worst-case. C5 runs a 2 h
   credit-paced window: dead tuples grew to about 348k (≈ 1 % of the journal) until the 0.01 trigger after about
   1 h 45 min, and one autovacuum of `things_journal` cleared them (`n_dead_tup`). C5's replay does not show whether
   pids swept since the last autovacuum replay slower: it times the pids swept first (sweep positions 6–105 of 184),
   which that autovacuum had already cleaned, so both halves (p95 3.50 ms as left vs 3.18 ms after a manual VACUUM,
   n=50) are clean pids. That replay cost is not measured; it needs the as-left pids picked from those swept after the
   window's last autovacuum.
6. C2/C3 do not update `journal_seq.deleted_to` — neither does Ditto's real cleanup path.
7. Corpus rows carry historic timestamps (2026-01-01 base), and the scenarios pin
   `history-retention-duration=0d`, which disables the cleanup age filter. That is the code
   default (`CleanupConfig`), not the service default: things ships 3d, policies and connectivity
   30d, so production cleanup runs with the filter on. The C1 retention row in §5 measures that
   shape on a fresh-share corpus; on the backdated corpus every row would qualify and the filter
   would cost only its evaluation.
8. M1b concentrates its churn (the `mixed` stage passes `pidsPerWorker=1`): a churn pid only
   becomes sweepable after crossing the 500-event snapshot threshold, which requires
   `ops/(workers×pidsPerWorker) ≥ 500` — unreachable at the default spread even at full scale.
   Consequently M1b's per-pid contention differs from W3's; compare their latency windows
   directionally, not 1:1.
9. At `--quick` scale the corpus has only ~6 OUTLIER-class pids, so class-scan disjointness
   between scenarios is not guaranteed (C2 cleans the same outlier pids R2 measured — reload
   before re-measuring recovery). Full-scale corpora have enough pids per class.
10. C1's PG number is a lower bound on production's page cost. The bench reads only `pid` and
    `sn`: pgjdbc transfers the 100 `snapshot::text` payloads of a page but the bench never decodes
    them, while production decodes every payload to a string and JSON-parses it
    (`PostgresPersistenceOperations.mapSnapshotRow`, `PostgresReadJournal.toSnapshotEntry`), only
    for Cleanup to discard it. Mongo's cleanup pipeline projects the payload away on the server.
11. C1 pages compare as measured, per page. The C1 corpus was never cleaned: it holds ~5.2
    snapshots per pid that has any, so a Mongo page (`$limit` counts snapshot rows) advances only
    ~22 pids while a PG page advances 100. That is a property of a first pass over an uncleaned
    backlog. Once Cleanup has run, a pid keeps about one old-enough snapshot (younger ones are
    excluded before `$limit`), so a Mongo page is ~100 pids too and per page is the equal-work
    comparison.
12. The C1 payload what-if is not production. Ditto's `getNewestSnapshotsAbove` fetches the payload; the what-if
    sizes what a payload-free cleanup page would save in TOAST reads, `jsonb_out`, transfer and pgjdbc row
    handling. Production would also skip decoding and JSON-parsing the payloads; the evidence estimates that with
    pgjdbc and ditto-json, not through r2dbc. The comparison with Mongo uses the 2026-10-02 Mongo run (not
    re-run; one backend at a time; that PG anchor ran on schema v2, this run on v3). The 2026-10-22 what-if under
    the limits compares with the Mongo value of the limits run (one backend at a time, the same corpus shape). Its
    cold state is cold shared buffers only: the server is recreated on its data volume and the VM's page cache is
    not dropped, so a block read there may come from the page cache or from the disk, and only the evidence's read
    time per block tells which: on this host about 0.01 ms per block is the page cache and about 0.1 ms the disk
    (the committed cold runs read at 0.012 ms per block, the uncommitted post-load trial at 0.095 ms); its warm
    state is after one untimed pass over the whole pid stream. `track_io_timing` is on in the what-if's session (one
    clock read per block I/O).
13. The 2026-10-21 C4 never re-reads a pid: at `--count 250000` only 26 OUTLIER-class pids exist, so its OUTLIER rows
    have n = 23 (26 minus 3 warm-ups) and the full-scan P99 row carries the 50-sample view of the same dead-space walk.
    C3/C5 sample tables have an `autovacuum in progress` column; C4 records the settle timeline with vacuum progress
    and autovacuum wait events.
14. M1 is measured during a mass sweep: one unthrottled pass over the whole remaining backlog of its load (after
    recovery, write and cleanup, as July's C3), at the same time as W3-shaped churn. Its headline uses the churn windows that ended while that pass was still deleting;
    a window that straddles the end counts as "after", and if the pass outlasts the churn every window is "during".
    The same backend's W3 run (same churn shape, no sweep) is its control. The churn keeps W3's lock-step pacing (eight
    workers fire together every 16 ms), so its per-window latency carries the same artefact as W3's (see the W3 note
    in §5): quote M1 for throughput and its latency directionally. The sweep runs unthrottled (worst case; Ditto's
    credit pace is ≤ 100 rows/s). M1b is the 2026-07 M1, kept as a separately named row: it ran after C4's offline
    reclamation, so its sweep found only the churn's own debris (296,576 rows).

## 8. Presenting this

Storyline: (1) recovery is index-friendly on both backends (R1/R2/R3); (2) the write path
compares append txn vs journaled bulkWrite honestly, including PG's extra journal_seq upsert and
Mongo's realtime side-write (W1-W3); (3) cleanup — the explicit ask — shows the pid-stream shape,
per-pid batched deletes, a full sweep with autovacuum keeping up (or not), and quantified
recovery/size wins after cleanup (C1-C4, M1). Quote p95s with the environment pins from §2 and
the caveats from §7 attached.
