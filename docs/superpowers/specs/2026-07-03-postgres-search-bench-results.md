# Ditto things-search on PostgreSQL — Phase-0 bench results and provisional gate verdict

Status: Phase-0 CLOSED at reduced scale (1M corpus / 10-minute sustained write window), per the user's explicit
scale decision recorded in the Task 0.3 brief. **Every gate number in this document is measured at 1M things /
warm-cache / ≤10-minute sustained write windows — NOT the plan's original 10M-row / ≥1-hour bloat-plateau
scale.** Wherever a number below reads as a pass, that is a reduced-scale, provisional pass; the full 10M/≥1h
run is an explicitly scheduled follow-up, not yet executed, and is called out again in the gate-verdict section
below so this caveat cannot be missed by skimming.

This document synthesizes Tasks 0.1 (corpus/load), 0.2 (read-path raw), 0.2b (read-path mitigations), and 0.3
(write-path + plan amendments). Every number below traces to a committed evidence file; inferences are labeled
as such and kept separate from measured facts.

## 1. Corpus and load (Task 0.1)

Exact figures, re-quoted from `.superpowers/sdd/briefs/task-0.1-report.md`:

- **1,000,000 things / 68,513,542 flat rows** loaded via `CorpusLoadBench` (`internal/utils/search-r2dbc/src/test/java/.../bench/CorpusLoadBench.java`).
- Timings: schema apply 25 ms; COPY things 17,284 ms; COPY flat 402,335 ms; index build 141,070 ms; ANALYZE
  1,486 ms. **Total wall-clock: 562,201 ms (~9.4 minutes)** — well inside the brief's 45-minute gate.
- Sizes at load time: `search_things` total (table+indexes+toast) 1,552 MB; `search_flat` total **27 GB**, of
  which `search_flat_pkey` alone is 14 GB; other `search_flat` indexes: `sf_exists` 754 MB, `sf_num` 1,050 MB,
  `sf_text` 633 MB, `sf_trgm` 304 MB, `sf_bool` 87 MB.
- Disk-headroom near-miss: the first full-scale attempt was aborted mid-run when free disk dropped to ~24 GB
  against a ~27-30 GB projected need; the schema was dropped and the corpus generator tuned down slightly
  (feature/property-count skew) before the successful retry. Environment-specific, not a design finding.

## 2. Read-path raw results (Task 0.2)

Five canonical shapes, 1M corpus, 3 warmup + 20 timed executions each, EXPLAIN(ANALYZE, BUFFERS) captured per
shape (`internal/utils/search-r2dbc/src/test/resources/bench/results/shapes-1-to-5-summary.md` +
per-shape files):

| shape | index-backed | p50 ms | p95 ms | gate verdict (raw, unmitigated) |
|---|---|---:|---:|---|
| 1: eq two-predicate AND (auth+gr) | YES | 6,462 | 6,905 | **RED FLAG** |
| 2: gt range (auth+gr) | YES | 168 | 178 | PASS |
| 3: ilike, low-card path (`/attributes/vendor`) | YES (`sf_trgm`+`sf_exists` BitmapAnd) | 71 | 126 | **BORDERLINE** (straddles 200ms across runs — round-1 442ms, round-2 rerun 126ms; treated as unresolved risk, not a clean pass) |
| 3: ilike, high-card path (`/features/*/properties/prop0`) | YES (`sf_trgm` Bitmap Index Scan) | 2,298 | 2,405 | **RED FLAG** |
| 4: sort+keyset (page 1 / OFFSET-100 / keyset-100) | YES (lateral pkey probes, ~29k) | 558/569/574 | 567/575/601 | recorded, no formal gate |
| 5: count with auth filter | YES | 5,802 | 6,603 | **RED FLAG** |

EXISTS-chain vs. semi-join (`IN (...) GROUP BY ... HAVING count=n`), auth+gr included in both, 4 mixes:

| mix | EXISTS p50/p95 | semi-join p50/p95 | winner |
|---|---:|---:|---|
| 2-pred sel+sel | 853 / 927 | 2,521 / 3,187 | EXISTS ~3x |
| 2-pred sel+unsel | 5,674 / 6,564 | 6,502 / 6,783 | EXISTS narrowly |
| 3-pred sel+sel | 820 / 893 | 2,947 / 4,151 | EXISTS ~3.5-4.5x |
| 3-pred sel+unsel | 5,715 / 6,728 | 8,212 / 8,720 | EXISTS ~1.3-1.4x |

**Root causes** (raw facts, all traced to committed EXPLAINs): (1) pooled column statistics mis-estimate flat-row
cardinality 38-80x because `val_num`/`val_text` histograms are shared across ALL wpaths — a `sf_num` probe for
one temperature bucket estimated 362 rows vs. an actual 13,869. (2) The resulting nested-loop plans do
14k-90k random `search_things_pkey` probes for the per-candidate auth recheck — 35k-83k buffer reads per query.
(3) `sf_trgm` is NOT wpath-scoped — trigram matching is table-wide regardless of which wpath variant is queried;
the planner's choice between `BitmapAnd(sf_trgm, sf_exists)` (cheap, low-card wpath) and
`sf_trgm`-then-post-filter (expensive, high-card wpath) is incidental to that wpath's own row count, not
evidence that low-cardinality-path ilike is inherently safe.

## 3. Read-path mitigation stack, E0→E5 (Task 0.2b)

Cumulative p50/p95 (ms), same four shapes, applied IN ORDER (each stage assumes the earlier stages' schema/
settings changes are still in place) — full detail in `results/mitigations/*.md`:

| experiment | shape 1 | shape 2 (control) | shape 3-high (ilike) | shape 5 |
|---|---:|---:|---:|---:|
| E0 baseline re-measure | 7,222 / 8,424 | 169 / 176 | 2,092 / 2,294 | 7,095 / 8,367 |
| E1 stats (authoritative custom-plan run) | 135 / 536 | 185 / 197 | 247 / 1,018 | 135 / 530 |
| E2 + realistic memory (4GB/12GB/64MB) | 53 / 818 | 199 / 215 | 248 / 1,753 | 44 / **49** |
| E3 + covering `INCLUDE(thing_id)` | 52 / 1,549 | 61 / 238 | 245 / 1,903 | 38 / **43** |
| E4 + wpath-scoped partial trigram | 48 / **87** | 57 / **71** | 22 / **49** | 45 / **50** |
| E5 rescue CTE (shapes 1/5 only) | 40 / **48** | — | — | 39 / **41** |

Bold = under the 200ms bar. No experiment ever produced a `Seq Scan on search_flat`.

**Which lever mattered, and which did NOT (committed negative results):**
- **E1 (statistics target on `wpath`, escalated to `val_text`/`val_num`) is a NEGATIVE result on its own terms**:
  the misestimate barely moves (34.0x wrong wpath-alone → 33.9x wrong escalated) — PostgreSQL's per-column
  statistics cannot represent "value distribution conditional on wpath" without extended/multivariate statistics
  (not attempted). E1 **discovered a separate, serious hazard**: better statistics made a generic plan look
  artificially cheap, and PostgreSQL's plancache flipped a repeated (pgjdbc-promoted) prepared statement to that
  generic plan at execution #10 — 55 seconds per execution vs. 0.4s custom-planned (`e1-generic-plan-flip-evidence.md`).
- **E3 (covering `INCLUDE(thing_id)` indexes) is a NEGATIVE result for the auth-heavy shapes 1/5** — their cost
  lives in the per-candidate `search_things` doc-row probe (~69k of 73.5k buffers), not the flat-index lookup;
  the covering index cannot remove that cost. (It DID help the control shape 2 substantially — index-only scan,
  p50 199→61ms.) **This is why Task 0.3 Part 0 dropped these indexes before pricing write cost** — see §5.
- **E4 (wpath-scoped partial trigram) is the decisive ilike lever**: 51 MB scoped index vs. 304 MB table-wide,
  2,294ms→49ms p95 (~47x). It is a mechanism proof (one hand-picked hot path), not yet a general solution — see
  the residual tension in §7.
- **E5 (materialized selective-leg CTE)** is not strictly required to clear the 200ms bar once E1-E4 are in
  place, but it is the only lever that reduces I/O footprint rather than warming existing footprint: 11,659
  buffers vs. 73,519 for the plain EXISTS-chain (6.3x less), and a **43.2x** faster cold start (3,440.78ms
  EXISTS-form vs. 79.58ms CTE-form, measured via symmetric `docker restart` probes,
  `e5-cold-start-probe.md`) — this is the chosen translator strategy (§5 amendment).

At 1M, with the full E1+E2+E4(+E5) stack, **no measured shape misses the 200ms warm p95 bar** (87/71/49/50ms).

## 4. Write-path results (Task 0.3, Parts 0-2)

**Reduced-scale statement (repeated deliberately):** all numbers in this section are 1M-corpus / ≤10-minute
runs. The plan's original ≥1-hour bloat-plateau gate is explicitly **NOT YET RUN** (scheduled full-scale
follow-up) — see the trend discussion below for why 10 minutes cannot answer that question.

### 4.1 Part 0 — DB state normalization

Before any write measurement, the Task 0.2b E3 covering indexes (`sf_num_covering`, `sf_text_covering`) were
DROPPED — a committed negative result (§3) that would otherwise bill the write path for an index the read gate
itself rejected. KEPT: E1 statistics targets, E2 memory settings, the E4 scoped trigram, and the original
table-wide `sf_trgm`. Full DDL/settings/index list:
`internal/utils/search-r2dbc/src/test/resources/bench/results/write-bench/part0-db-normalization.md`.

### 4.2 Part 1 — sustained write fan-out (v1: unconditional upsert + blanket DELETE/unnest-INSERT)

Full raw evidence (20 30-second samples + summary):
`internal/utils/search-r2dbc/src/test/resources/bench/results/write-bench/part1-sustained-upsert.md`.

Protocol: per-thing transaction = (1) `INSERT ... ON CONFLICT (thing_id) DO UPDATE SET <all columns>`
(unconditional, no revision predicate, exactly the plan's §3.4 step-1 shape); (2) `DELETE FROM search_flat
WHERE thing_id=?` then `INSERT INTO search_flat SELECT * FROM unnest(...)` (one bind per column). 2,000
randomly-selected existing thing_ids (seeded `setseed`), rewritten to a controlled ~500-flat-row numeric twin
shape (via the real `Flattener`), 5 of 500 leaves mutated per update, 16 concurrent workers, target 200 upd/s,
10 minutes (600s).

**Headline results:**
- **Achieved rate: 179.2/s average against a 200/s target** (min window 136.7/s, max window 199.2/s) — the
  system does NOT fully sustain 200/s at this corpus/hardware/worker configuration; reported honestly as the
  achieved rate, per the brief's explicit allowance for this outcome. 107,495 successful transactions, **0
  errors**, over the full 10 minutes.
- **Transaction latency**: overall p50=20ms, p95=112ms, max=3,014ms (one outlier; the vast majority of
  transactions land well under 200ms even under sustained load).
- **Size-growth curve (10 min, not a plateau — an honestly-reported still-rising trend)**: `search_flat` table
  +6.39 GB (11.69→18.55 GB, ~11.5 MB/s); `sf_num` index +2.12 GB (1.35→3.63 GB) — the largest single-index
  grower; `sf_exists` +319.3 MB; `search_flat_pkey` +50.8 MB only (repeated same-key churn reuses B-tree page
  space far better than the value-family indexes). **`sf_text`/`sf_bool`/`sf_trgm`/`sf_trgm_scoped_feature` saw
  ZERO growth** — disclosed caveat: this bench's twin payload is all-numeric for hot-loop simplicity, so the
  text/boolean/trigram index families are not exercised by this write bench at all.
- **Total WAL over the 10 minutes: 66.56 GB** (~113 MB/s sustained).
- **Dead tuples / autovacuum — the standout finding**: `n_dead_tup` climbed monotonically and continuously for
  the entire 10 minutes (5.3M→45.5M), while **`autovacuum_count` stayed at exactly 1 the whole run** — i.e. no
  new autovacuum cycle completed during the run. A direct `pg_stat_activity` check immediately afterward found
  a single `autovacuum: VACUUM ANALYZE public.search_flat` worker that had been continuously
  `IO`/`DataFileRead`-bound for **7 minutes 33 seconds already** — i.e. it started early in the run and had not
  finished by the time Part 1 ended. This same cycle was confirmed still active (`autovacuum_count` static at
  1) through the entirety of Part 2's Phase A (`n_dead_tup` continuing to climb, ~47.6M→72.5M) — and, per
  Phase B's own samples, **it still had not completed by the end of Phase B either** (`autovacuum_count`
  static at 1, `n_dead_tup` static at ~72.6-72.9M across all 10 Phase B samples). It only completed sometime
  after Part 2 finished entirely (`autovacuum_count` incremented to 2, timestamped 13:09:20, per the
  post-writebench DB-state capture — see that file). **Under this sustained blanket-rewrite workload, a
  single default-configured autovacuum worker on `search_flat` could not keep pace for the combined duration
  of Part 1 and both phases of Part 2** — a genuine, measured operational risk for the v1 blanket design at
  this churn rate, not an inference. (No committed sample shows `n_dead_tup` actually reset to 0 anywhere in
  this run — that detail, previously stated here, was an unverified inference; see the corrected note in the
  Part 1 result file.)
- GIN/autovacuum knobs in effect (all defaults, recorded per the brief): `gin_pending_list_limit=4MB`,
  `autovacuum_vacuum_scale_factor=0.2`, `autovacuum_vacuum_cost_limit=-1` (global default 200),
  `autovacuum_vacuum_cost_delay=2ms`, `autovacuum_naptime=1min`, `autovacuum_max_workers=3`; `sf_trgm`/
  `sf_trgm_scoped_feature`/`search_flat` reloptions all empty (no per-relation overrides).

**≥1h bloat-plateau gate: explicitly NOT YET RUN.** At 10 minutes, with a single autovacuum cycle never
completing across the entire window, no plateau claim is possible — the numbers above are an honest,
still-climbing trend. The autovacuum finding is itself evidence that the full 10M/≥1h run will likely need
autovacuum tuning (more workers, higher cost limit, lower scale factor) — or the v1.5 anti-join's much lower
dead-tuple production rate (§4.3) — to have any realistic chance of reaching a plateau at all.

### 4.3 Part 2 — v1.5 anti-join churn-reduction comparison

Full raw evidence (two 5-minute phases, 10 samples each + summary + comparison table):
`internal/utils/search-r2dbc/src/test/resources/bench/results/write-bench/part2-antijoin-vs-blanket-comparison.md`.

Same workload/rate/worker configuration as Part 1, two DISJOINT 2,000-thing pools (same corpus, same DB state,
run back-to-back): **Phase A** = v1 blanket DELETE+INSERT (poolA), **Phase B** = v1.5 anti-join
delete-changed/insert-changed via a single statement with two writable CTEs sharing one `MATERIALIZED` unnest,
matching on full row identity (path, wpath, ord, type_rank, value via `IS NOT DISTINCT FROM`). Each phase
measured for 5 minutes (the brief's explicit "shorter comparison window, e.g. 2×5 min, acceptable" allowance).

| metric | v1 blanket (Phase A) | v1.5 anti-join (Phase B) | ratio |
|---|---:|---:|---:|
| txn p50/p95/max ms | 14 / 85 / 1,585 | 3 / 17 / 625 | ~4.7x / ~5x / ~2.5x faster |
| avg flat rows deleted/txn | 485.3 | 7.2 | ~67x fewer |
| avg flat rows inserted/txn | 501.0 | 21.7 | ~23x fewer |
| achieved rate (target 200/s) | 185.2/s | 197.7/s | anti-join sustains closer to target |
| total WAL over the 300s phase | 34.05 GB | 4.79 GB | **~7.1x less WAL** |
| `search_flat` table growth (same window) | +3.10 GB | +12.4 MB | **~256x less table growth** |
| `sf_num` index growth (same window) | +681.9 MB | +15.2 MB | **~45x less index growth** |
| `sf_exists` index growth (same window) | +90.9 MB | +0 (no growth) | anti-join: unmeasurable |
| `n_dead_tup` growth (same window) | +24.86M | +278K | **~89x less dead-tuple growth** |

**First-touch caveat (disclosed):** the very first write to any pool thing_id replaces that thing's pre-existing
CorpusGenerator-shaped rows with the twin shape — zero row-identity overlap, so that one update is full churn
under BOTH forms. At this pool size (2,000) and rate (~180-200/s) over 5 minutes, each thing_id is touched
~27.8-29.7 times on average (Phase A: 55,548 txns / 2,000 things ≈ 27.8; Phase B: 59,321 txns / 2,000 things ≈
29.7), so first-touch updates are a small (~3.4-3.6%) minority of the total — the numbers above are the
*actual measured* steady-state-dominated average, not an idealized best case.

**Recommendation: adopt v1.5 (anti-join delete-changed/insert-changed) over the v1 blanket rewrite, once
repeat-touch steady state is reached.** Full rationale in the Part 2 result file's "Recommendation" section;
summary: anti-join is decisively better on the write-amplification/WAL/index-and-table-growth/dead-tuple axes
(rows deleted/inserted per txn, WAL bytes, relation-size deltas — direct mechanism measurements, unaffected by
table state) at steady state, at the cost of materially more complex SQL (two writable CTEs, full row-identity
matching) — a real but bounded implementation cost, and it does NOT require leaf-level diffing (the plan's
§3.7-scoped, separate v2 optimization). This decision is recorded in plan §3.4.

**Confound disclosed (not hidden):** the latency/throughput comparison (p50 3ms vs 14ms; 197.7/s vs 185.2/s)
is *not* clean evidence on its own — Phase A ran while the autovacuum cycle carried over from Part 1 was still
active, with `n_dead_tup` climbing ~47.6M→72.5M across the phase. That cycle did **not** complete at the
Phase A/Phase B boundary: Phase B's own samples show `autovacuum_count` still at 1 and `n_dead_tup` static at
~72.6-72.9M across all 10 Phase B samples, i.e. the same carried-over cycle was still running throughout
Phase B too, only completing sometime after Part 2 finished entirely (`autovacuum_count` incremented to 2,
timestamped 13:09:20, per the post-writebench DB-state capture). Phase A and Phase B therefore ran under
non-identical, not-fully-characterizable table/vacuum states — neither phase ran against a "clean" baseline,
and Phase B did **not** start against a freshly-vacuumed table. Notably, Phase B's standing dead-tuple level
(~72.7M, static) was *higher* than most of Phase A's (which climbed from 47.6M up to 72.5M) — if anything this
biases AGAINST v1.5's apparent latency/throughput advantage, which makes the mechanism-metric attribution
below safer, not weaker. Some of the observed latency/throughput gap may still be attributable to Phase A's
progressively rising dead-tuple/bloat state and concurrent autovacuum I/O contention rather than to the
anti-join mechanism alone (see the Part 2 result file's recommendation point 2 for the full disclosure). The
mechanism-attributable ratios above (67x/23x fewer rows, ~7x less WAL, 45x-256x less index/table growth, ~89x
less dead-tuple growth) are unaffected by any of this and remain the primary basis for the recommendation.

**Disclosed measurement gap:** like Part 1, this comparison's twin payload is all-numeric — the index-growth
numbers above are representative of the numeric-value index family only; text/trigram-indexed leaf growth
under sustained anti-join vs. blanket churn is not directly measured here (expected, not verified, to show the
same relative advantage).

## 5. Query translation strategy decided (amends plan §3.5)

Combining Task 0.2's EXISTS-chain-vs-semi-join result with Task 0.2b's E5 rescue-form result: the translator's
chosen strategy is **EXISTS-chain, with the selective leg forced into a `MATERIALIZED` CTE first** when a
shape's plan would otherwise nested-loop from an unselective leg. This closes both the plan-shape question
(§3.5's original open item) and gives the smallest I/O footprint and fastest cold start of any measured
alternative (§3). Recorded in the plan doc.

## 6. GIN / autovacuum observations (cross-referencing §3 and §4)

- `gin_pending_list_limit` (4MB) and `fastupdate` (default ON, no reloption override) were left at defaults for
  both `sf_trgm` and `sf_trgm_scoped_feature` throughout — Parts 1/2's all-numeric twin payload never exercised
  either trigram index, so this bench provides no data on GIN pending-list behavior under sustained ilike-path
  churn; this is an explicit gap, not a "no effect" finding.
- Autovacuum defaults (`scale_factor=0.2`, `cost_limit`=global default 200, `cost_delay=2ms`, 3 workers) were
  measurably insufficient to keep `search_flat` healthy under the v1 blanket workload's churn rate over a
  combined ~25-minute window (§4.2) — v1.5's dramatically lower dead-tuple production rate (§4.3) is the
  strongest evidenced mitigation measured in this phase; explicit autovacuum retuning (more workers, higher
  cost limit) is a candidate for Phase C/F but was not itself benchmarked here.

## 7. Provisional gate verdict (reduced scale — 1M / ≤10 min)

**What passed, and under which mitigations, at 1M/10-min:**
- All five read-path shapes close under the 200ms warm-p95 bar with the full E1(stats)+E2(memory)+E4(scoped
  trigram)[+E5(rescue CTE)] mitigation stack in place (§3). None pass unmitigated (§2).
- The write path sustains ~179-198/s (below the 200/s target but close, and honestly reported as such) with
  zero transaction errors across both a 10-minute blanket run and a 2×5-minute blanket-vs-anti-join comparison.
- The v1.5 anti-join churn-reduction candidate is measurably, substantially better than the v1 blanket rewrite
  on the write-amplification/WAL/index-and-table-growth/dead-tuple axes (§4.3); the latency/throughput axis
  points the same direction but that specific comparison is confounded — the carried-over autovacuum cycle had
  not completed by the end of either phase, so neither phase ran against a characterizable baseline (§4.3) —
  adopted as the write-path recommendation.
- EXISTS-chain + materialized selective-leg CTE is the decided translator strategy (§5).

**What remains for the full 10M / ≥1h run (scheduled follow-up, NOT yet executed):**
- The read-path 200ms gate at 10M scale is unproven — the pooled-statistics misestimate (~34x wrong) is not
  fixed by any lever tried, only survived by index-backed plans at 1M; whether the same plans survive at 10x
  the candidate-set cardinality is untested.
- The write-path ≥1h bloat-PLATEAU gate is unproven — 10 minutes showed a still-climbing dead-tuple trend with
  autovacuum unable to keep pace under the blanket workload; whether autovacuum (default or tuned) or the v1.5
  anti-join reaches a genuine plateau over ≥1h at 10M scale is untested.
- The trigram/text-index write cost under sustained churn is untested (this bench's twin payload is
  all-numeric).

**Decision-checkpoint statement (proceed / stop-and-evaluate-pg_documentdb), based on the reduced-scale
evidence:** **PROCEED** to Phase A (SPI extraction) under the stated mitigations, with the residual tensions in
§8 carried forward as explicit, unresolved design inputs to Phases C/D/F — not silently closed. Nothing measured
at 1M scale constitutes a stop condition; the open questions are about scale-headroom (10M/≥1h), not about a
fundamental architectural rejection of the flattened-side-table design. A full-scale (10M/≥1h) run remains
recommended before a production go-live decision, but is not required to unblock the next implementation phase.

## 8. Residual tensions (stated honestly — decision inputs, NOT solved problems)

These three items are deliberately NOT resolved by this document or by the plan amendments in this task. They
are flagged for the plan owner / Phase D implementer to decide with full context.

### 8.1 ilike / high-cardinality-path residual risk

The global, table-wide `sf_trgm` index alone leaves high-cardinality-path ilike at ~2.4s p95 at 1M (§2, §3) —
unambiguously red-flagged. The only lever that closed it (Task 0.2b's E4, ~47x improvement) is a **per-path
partial trigram index** — a mechanism the plan's §3.7 currently declares out of scope ("Postgres analog of
Mongo custom indexes ... explicitly out of scope"). Options on the table, NOT decided here:
- **(a) Accept slow generic ilike on unindexed paths, documented** like Mongo's own unanchored-regex-degrades-
  to-scan caveat (plan §5's "Accepted" risk row already covers a related case).
- **(b) Revisit §3.7 with an opt-in scoped-index knob** — an operator- or schema-lifecycle-managed mechanism to
  create per-path partial trigram indexes for configured hot paths.
- **(c) Translator-managed hot-path indexes** — automatically created/maintained based on observed query
  patterns, with attendant lifecycle/maintenance complexity.

This is genuinely open. The plan doc's §3.5 ilike row and §5 risk table now both point here (this section) and
state explicitly that the plan does not choose an option.

### 8.2 Plancache generic-plan-flip hazard (HARD requirement for Phase D)

r2dbc-postgresql, like pgjdbc, reuses named prepared statements. Task 0.2b measured PostgreSQL's plancache
flipping a wpath-parameterized query to a catastrophic generic plan once improved statistics made that generic
plan look artificially cheap: **55 seconds per execution vs. 0.4 seconds custom-planned** — a 25x-scale cliff
triggered purely by a statistics change that was otherwise a pure improvement (`e1-generic-plan-flip-evidence.md`).
This is recorded as a **HARD requirement for Phase D**: the translator must guard against this, via either (a)
inlining wpath literals through a strict, injection-safe whitelist (wpaths are a bounded, schema-derived set,
not arbitrary user input, making this tractable), or (b) forcing `plan_cache_mode=force_custom_plan` on search
connections. This is not optional and not deferred — it is a correctness-adjacent performance cliff that a
naive r2dbc translator would hit in production exactly as this bench hit it in testing.

### 8.3 Misestimate persistence (10M scale unproven)

Pooled `wpath`-cross-column statistics remain ~34x wrong regardless of how high the `wpath`/`val_text`/`val_num`
statistics targets are pushed (§3, E1) — this is architectural (PostgreSQL has no per-(wpath,value)-pair
statistics short of multivariate/extended statistics, not attempted here), not a resolvable tuning gap.
Index-backed plans survive this misestimate at 1M scale via the EXISTS-chain-with-materialized-CTE form (§3,
§5) — but this survival is empirically observed at 1M, not proven to generalize. At 10M, if candidate-set
cardinalities scale up ~10x as the corpus does, the same plans' warm floor could reach ~400-500ms (extrapolated
from the 1M candidate-probe counts in Task 0.2b's residual-risk paragraph) — over the 200ms gate — unless the
translator's chosen strategy (§5) caps candidate-leg cardinality independent of corpus scale. **This is stated
as an open risk, not resolved**: the full 10M run is the only way to actually answer it.

## 9. Files referenced

- `.superpowers/sdd/briefs/task-0.1-report.md`, `task-0.2-report.md`, `task-0.2b-report.md`, `task-0.3-report.md`
- `internal/utils/search-r2dbc/src/test/resources/bench/results/shapes-1-to-5-summary.md`,
  `shape-1-eq-and.md`, `shape-2-gt-range.md`, `shape-3-ilike.md`, `shape-4-sort-keyset.md`,
  `shape-5-count-auth.md`, `comparison-summary.md` + 4 comparison files
- `internal/utils/search-r2dbc/src/test/resources/bench/results/mitigations/*.md` (E0-E5 + review-round-1 fixes
  + `db-end-state.md`)
- `internal/utils/search-r2dbc/src/test/resources/bench/results/write-bench/part0-db-normalization.md`,
  `part1-sustained-upsert.md`, `part2-antijoin-vs-blanket-comparison.md`, `db-state-post-writebench.md`
- `docs/superpowers/specs/2026-07-03-postgres-search-plan.md` (amended alongside this document)
