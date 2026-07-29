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
| Reproduction script | `internal/utils/persistence-bench/benchmark/run-benchmark.sh` |
| Design the bench validates | `docs/superpowers/specs/2026-07-10-postgres-persistence-bench-design.md` |
| Implementation plan (incl. deviation list) | `docs/superpowers/plans/2026-07-10-postgres-persistence-bench.md` |

Each run overwrites its evidence file — `git diff` shows run-to-run movement.

## 2. Environment and fairness pins

- `postgres:16`, laptop-class settings from the start: `shared_buffers=4GB, effective_cache_size=12GB,
  work_mem=64MB` (host port 55433).
- `mongo:7.0` (the tag Ditto's `deployment/docker/docker-compose.yml` pins), `--storageEngine
  wiredTiger --noscripting --wiredTigerCacheSizeGB 4` for memory symmetry (host port 57017). No
  replica set — Ditto's compose runs standalone; consequently no oplog (WAL column is PG-only).
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
No journal tags and no DELETED-lifecycle snapshots (all corpus entities live) — tag indexes and the
lifecycle partial index are still created for schema fidelity.

## 4. Method

Per scenario: 3 warmup + 20 timed executions with varying-but-deterministic binds; p50/p95/max
(nearest-rank); one representative plan — `EXPLAIN (ANALYZE, BUFFERS)` on PG,
`explain(executionStats)` on Mongo. Mongo `deleteMany` has no executionStats — delete scenarios are
timed only (disclosed per file). Sustained scenarios (W3/C3/M1) sample 30 s windows. Cleanup
scenarios pin `DefaultCleanupConfig`: reads-per-query 100, writes-per-credit 100,
history-retention 0d, delete-final-deleted-snapshot false. The C3 sweep runs unthrottled by default
— Ditto's credit gate (3×100-row batches per 3 s ⇒ ≤100 rows/s) is structurally replicated but
would need days for this corpus; evidence quotes the computed default-pace duration
(`-Dbench.sweep.pace=credits` enables the real pace).

## 5. Headline results

From the committed full-scale evidence, re-run 2026-07-13/14 at `--count 250000` (~34.75M
journal rows, ~15 GB PG / ~14 GB Mongo steady per backend; 250k chosen for this machine's disk —
see the sweep-bloat note below) on the loose-index-scan query rewrite + schema v2 (snaps DESC
index dropped). Verdict rule (design §7): flag any scenario where PG p95 > 2× Mongo p95. All
values are p95 ms unless noted; full tables + plans live in the evidence files. C1's former 21×
flag was closed by the loose-index-scan rewrite
(see `docs/superpowers/specs/2026-07-13-cleanup-loose-index-scan-design.md`).

| scenario | PG p95 | Mongo p95 | verdict |
|---|---|---|---|
| R1 latest snapshot (worst class) | 1.14 | 1.33 | parity |
| R2a tail replay (worst class) | 10.90 | 10.86 | parity |
| R2b full replay, outlier 40-50k events | 522 | 531 | parity; absolute < 2 s bar MET |
| R3 highest sn | 0.84 | 1.34 | PG faster |
| W1 single append | 2.71 | 3.03 | parity |
| W2 snapshot write (fresh / overwrite) | 2.96 / 2.33 | 4.79 / 4.25 | PG faster (one snaps index fewer since v2) |
| W3 sustained churn (achieved of 500/s) | 499.8/s | 499.9/s | both hold target |
| C1 cleanup pid-stream page | 8.50 | 10.71 | PG faster — former 21× FLAG closed (loose index scan; plan: index-only pid page + backward PK top-1, no sort/temp) |
| C2 single-pid cleanup (outlier total) | 993 | 1163 | parity |
| C3 full sweep (21.05M rows) | 797 s | 488 s | Mongo 1.6× faster (delete-batch bound); identical deletions both sides |
| **C4 post-sweep replay before reclamation** | **16.9 (tail) / 98.5 (full-scan)** | **13.2 / 6.7** | **FLAG: PG replays walk dead space until vacuum (worst-case: unthrottled sweep, §7.5)** |
| M1 churn under live sweep (achieved of 500/s) | 499.8/s | 499.9/s | both hold; 296,576 rows swept live on each |

Cross-backend determinism: C3 deleted identical 21,054,465 event + 33,613 snapshot rows on both
backends; M1 swept identical 296,576. Credit-pace projection for C3: ~105 h (the real Ditto pace —
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
| M1 mixed | `m1-cleanup-under-traffic.md` |

## 6. Reproducing

```
benchmark/run-benchmark.sh up build load          # containers + corpus (hours at full scale)
benchmark/run-benchmark.sh recovery write cleanup # non-destructive scenario groups
benchmark/run-benchmark.sh sweep mixed            # C3 destroys the cleanup backlog by design
benchmark/run-benchmark.sh status                 # corpus / container / results state
benchmark/run-benchmark.sh all --quick --yes      # ~20-min demo pipeline (numbers not comparable)
benchmark/run-benchmark.sh down --purge           # remove containers + volumes
```

Re-run `load` after `sweep` for a pristine corpus — and before re-running `write` or `mixed`:
W1/W3/M1 write fixed, deterministic probe pids with plain inserts, so a second run against the
first run's leftover rows fails on duplicate keys.

`--backend pg|mongo` restricts any stage.

## 7. Caveats — read before quoting numbers

1. Laptop-scale Docker; 10-minute windows — trends, not plateau claims.
2. Driver-level, not actor-level: no Pekko/serialization overhead on either side. The comparison
   is symmetric and fair; absolute service-level latency is higher.
3. Mongo delete timings have no plan-level evidence (`deleteMany` lacks executionStats).
4. Synthetic corpus; payload knobs approximate no specific customer data set.
5. C3 pace is unthrottled by default (deviation 3); Ditto's production pace is far slower and
   gentler — the dead-tuple/autovacuum observations under unthrottled load are worst-case.
6. C2/C3 do not update `journal_seq.deleted_to` — neither does Ditto's real cleanup path.
7. Corpus rows carry historic timestamps (2026-01-01 base) — with `history-retention-duration=0d`
   the age filter is disabled, matching both the bench and the config default.
8. M1 concentrates its churn (the `mixed` stage passes `pidsPerWorker=1`): a churn pid only
   becomes sweepable after crossing the 500-event snapshot threshold, which requires
   `ops/(workers×pidsPerWorker) ≥ 500` — unreachable at the default spread even at full scale.
   Consequently M1's per-pid contention differs from W3's; compare their latency windows
   directionally, not 1:1.
9. At `--quick` scale the corpus has only ~6 OUTLIER-class pids, so class-scan disjointness
   between scenarios is not guaranteed (C2 cleans the same outlier pids R2 measured — reload
   before re-measuring recovery). Full-scale corpora have enough pids per class.

## 8. Presenting this

Storyline: (1) recovery is index-friendly on both backends (R1/R2/R3); (2) the write path
compares append txn vs journaled bulkWrite honestly, including PG's extra journal_seq upsert and
Mongo's realtime side-write (W1-W3); (3) cleanup — the explicit ask — shows the pid-stream shape,
per-pid batched deletes, a full sweep with autovacuum keeping up (or not), and quantified
recovery/size wins after cleanup (C1-C4, M1). Quote p95s with the environment pins from §2 and
the caveats from §7 attached.
