# Read-path benchmark headline summary (shapes 1-5)

Aggregated from per-shape `ReadPathBench#shapeN...` runs against the 1,000,000-thing / 68,513,542-row bench
corpus (2026-07-04; shapes were run one method at a time — see the Task 0.2 report for exact commands).
Per-shape detail, full SQL and EXPLAIN(ANALYZE,BUFFERS) plans are in the sibling `shape-*.md` files.

Shape 3's two rows below are the **round-2 rerun** (current, matching the committed `shape-3-ilike.md` per-shape
file) — see the "Correction (review round 2)" note below the table for why both a round-1 and a round-2 number
are cited, and `shape-3-ilike.md`'s own "Run-to-run instability disclosure" for the full story.

| shape | index-backed | p50 ms | p95 ms | max ms | red flag (p95>200ms) |
|-------|:---:|---:|---:|---:|:---:|
| 1 (eq AND) | true | 6462 | 6905 | 7506 | true |
| 2 (gt range) | true | 168 | 178 | 189 | false |
| 3 (ilike, low-card path `/attributes/vendor`, round-2 rerun) | true | 71 | 126 | 467 | borderline (see note) |
| 3 (ilike, high-card path `/features/*/properties/prop0`, round-2 rerun) | true | 2298 | 2405 | 2574 | true |
| 4 (sort+keyset, page 1) | true | 558 | 567 | 570 | true |
| 4 (sort+keyset, page 100 via OFFSET) | true | 569 | 575 | 587 | true |
| 4 (sort+keyset, page 100 via keyset, result-verified vs OFFSET) | true | 574 | 601 | 652 | true |
| 5 (count with auth filter) | true | 5802 | 6603 | 6611 | true |

**Gate shapes (1-3, 5) verdict:** index-backed yes for all (no `Seq Scan` on `search_flat` in any captured
plan), but shapes 1, 3 (high-card variant) and 5 are **red flags** — warm p95 far over the 200ms bar (6.9s /
2.4s / 6.6s). Only shape 2 passes cleanly. Shape 3's low-card variant straddles the 200ms bar across the two
runs captured for this task (round-1 p95 442ms, round-2 rerun p95 126ms, max 467ms both runs solidly over) —
**treat it as borderline, not a clean PASS**; see the disclosure below and in `shape-3-ilike.md`.

**Correction (review round 1):** an earlier version of this note claimed the low-card variant "uses `sf_text`,
NOT `sf_trgm`" — that was wrong (`sf_text` never appears in either plan; see the corrected mechanism in
`shape-3-ilike.md`'s Finding). Both `/attributes/vendor` variants actually use `sf_trgm`'s Bitmap Index Scan;
what differs is whether a second `BitmapAnd`ed leg on `sf_exists (wpath)` can cheaply narrow the trigram
candidates before the heap is touched. On the low-card `vendor` path (~305k rows for this wpath out of
68.5M) it can, so the query is fast whenever the relevant pages are cache-warm. On the high-card feature path
(~1.4M rows for this wpath) it can't, so the trigram candidates are heap-fetched and filtered by wpath
post-fetch instead, at multiple seconds. This is an **incidental planner choice driven by this wpath's own row
count, not evidence that low-cardinality-path ilike is naturally safe** — the underlying
`sf_trgm`-is-not-wpath-scoped gap applies to both variants.

**Correction (review round 2, Finding 1):** this note and the table above previously still cited the
round-1 numbers (low-card p95 442ms, high-card p95 2178ms) even after `shape-3-ilike.md`'s own latency tables
had been regenerated with fresh round-2 figures — a reconciliation gap, not a re-measurement. The table above
now carries the current round-2 rerun's numbers, matching `shape-3-ilike.md` exactly. The two runs' numbers:
low-card p95 442ms (round-1) → 126ms (round-2 rerun), max 445ms → 467ms; high-card p95 2178ms (round-1) →
2405ms (round-2 rerun). **Low-card p95 straddles the 200ms bar across runs — its own max stays over the bar in
both runs (445ms / 467ms), so the classification is cache-state-dependent at 1M scale; do not read the
round-2 p95 alone as a clean PASS.** High-card is unambiguously red-flagged in both runs — no run has come
close to 200ms. See `shape-3-ilike.md`'s "Run-to-run instability disclosure" for the full reasoning (cold vs.
OS-page-cache-warm variance against this container's `shared_buffers=128MB` default, per Concern #2).

Key mechanics behind the red flags (full analysis in the Task 0.2 report; recommendation is Task 0.3's):

- The planner underestimates `search_flat` predicate cardinality ~38-80x (e.g. temp-bucket: est 362 rows,
  actual 13,869; city eq: est 1,125, actual 89,901) because `val_num`/`val_text` statistics are pooled across
  ALL wpaths — a structural issue of the shared-column flat table, not a stale-ANALYZE artifact.
- The chosen nested-loop plans then do 14k-90k random probes into `search_things_pkey` + heap fetches for the
  global_read/auth recheck; on a cold cache that is ~35k-83k page reads ≈ 5-7s. Warm single-bind reruns of the
  same statement land at 0.7-0.9s (EXPLAIN ANALYZE), and forced alternatives (hash semi-join, materialized-CTE
  candidate set) measured 0.7-6.2s — i.e. no available 1M plan meets 200ms for shape 1/5's mix when the
  unselective predicate has ~90k rows.
- At 1M these numbers mean the 10M `<200ms` gate is dead for shapes 1, 3(high-card) and 5 as-designed —
  stated plainly per the brief. Shape 2 (single selective numeric range) is the only shape with headroom.
