# EXISTS-chain vs semi-join — headline comparison

Both strategies include the global_read array-overlap filter and the brief's per-field auth recheck; the
semi-join is the brief's single-pass-over-flat formulation (`thing_id IN (SELECT ... GROUP BY thing_id HAVING
count(*) FILTER (...) > 0 AND ...)`). Full SQL, EXPLAIN(ANALYZE,BUFFERS) plans and latency tables per mix are
in the sibling `comparison-*.md` files. All eight plans are index-backed (no `Seq Scan` on `search_flat`).

| mix | EXISTS p50 / p95 (ms) | semi-join p50 / p95 (ms) | winner |
|-----|---:|---:|---|
| 2-pred selective+selective (temp bucket AND weightKg>t) | 853 / 927 | 2521 / 3187 | EXISTS by ~3x |
| 2-pred selective+unselective (city AND temp bucket) | 5674 / 6564 | 6502 / 6783 | EXISTS, narrowly |
| 3-pred all-selective (+ certified=true) | 820 / 893 | 2947 / 4151 | EXISTS by ~3.5-4.5x |
| 3-pred selective+unselective (city AND temp AND weightKg) | 5715 / 6728 | 8212 / 8720 | EXISTS by ~1.3-1.4x |

Observations (Task 0.3 owns the recommendation; these are the raw facts):

- EXISTS-chain wins every mix at this corpus. Its advantage is largest when all predicates are selective
  (the chain drives from the most selective probe and never touches the unselective rows); the semi-join's
  single pass must always scan the union of all predicate row sets (e.g. the ~90k city rows), which it pays
  even when another predicate would have narrowed to hundreds of things.
- Neither strategy rescues the selective+unselective mixes from the >5s p50: the dominant cost there is the
  per-candidate recheck work (nested-loop probes into `search_things_pkey` + heap fetches for
  global_read/auth), which both strategies share.
- The semi-join's `GROUP BY thing_id HAVING count(*) FILTER` aggregation over the OR'd row sets adds a
  1.5-2.5s fixed overhead at these row volumes on top of that.
