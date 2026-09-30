# Attempt 2 — contiguous rounds, 3 warmups (superseded, kept for the warmup comparison)

These files are the second production-config baseline (review round 1, 2026-09-30, 18:10–18:33 UTC), taken with the
one command `run-benchmark.sh production-config`: reload, then warm, then cold. The baseline one level up (`../`)
supersedes them. They are kept only because the T15a hand-offs cite them to show how the warmup changed the numbers.

How attempt 2 differs from the baseline in `../`:

- **Warmup.**
  - Each round warmed up with only iterations 0–2, i.e. 3 of the 10 cities, 3 of the 7 thresholds and 3 of the 8
    patterns.
  - Round 1's timed iterations 3–9 therefore read those filters' pages for the first time. The all-rounds p95 of
    shapes 1, 2, 3-high and 5 is such a first read.
  - The baseline's warmup covers the whole filter rotation.
- **What stays the same.**
  - Run order: shape by shape, 3 contiguous rounds.
  - Leader-only find plans, the catalog check and the corpus. The baseline was measured on this same load, without a
    reload, so the table statistics and plans are the same.
- **Cold.**
  - `cold.md` / `cold.json` here were taken right after attempt 2's warm run and before the corpus-bias counts, i.e.
    in the right order.
  - The baseline re-measures cold after its own warm run, with the same cold protocol.
- **Load.** `load.md` of this reload is the baseline's `../load.md`.
