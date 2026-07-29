# Shape 3 — ilike mid-string (auth + gr)

## SQL

```sql
SELECT st.thing_id FROM search_things st
WHERE st.global_read && ?
  AND EXISTS (SELECT 1 FROM search_flat s WHERE s.thing_id = st.thing_id AND s.wpath = ? AND s.val_text COLLATE "C.utf8" ILIKE ? ESCAPE '\')
  AND (NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
      OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
           AND ( COALESCE( (st.policy_auth #> ?) ??| ?, false)
                 OR ( NOT COALESCE( (st.policy_auth #> ?) ??| ?, false)
                      AND COALESCE( (st.policy_auth #> ?) ??| ?, false) ) ) ) ))
```

Two variants were benchmarked deliberately — the planner's index choice depends on the
wpath's own cardinality, which is an important finding in itself (see below).

## Variant A — low-cardinality path `/attributes/vendor` (94,369 string rows for this wpath)

Substrings cycled: 234, 246, 456, 567, 789, 321, 135, 890 (each ~250-300 hits, verified pre-coding).

Both runs captured for this task are shown (review round 2, Finding 1) — the numbers
moved enough between runs that a single table would misrepresent stability; see the
disclosure in the Finding below.

Round-1 (original run, 2026-07-03, historical — superseded by the round-2 rerun):

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| vendor path, cycling substrings (round-1)  |         20 |       49 |       72 |      442 |      445 |

Round-2 rerun (current — this run's numbers are what the Finding below treats as
authoritative, and what the representative EXPLAIN below was captured from):

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| vendor path, cycling substrings (round-2)  |         20 |       46 |       71 |      126 |      467 |

Index-backed (no seq scan on search_flat): true
Uses `Bitmap Index Scan` on `sf_trgm`: true

```
Nested Loop  (cost=4429.32..4489.06 rows=1 width=41) (actual time=44.427..44.429 rows=0 loops=1)
  Buffers: shared hit=1573 read=6833
  ->  HashAggregate  (cost=4428.77..4428.84 rows=7 width=41) (actual time=42.400..42.421 rows=291 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 85kB
        Buffers: shared hit=688 read=6263
        ->  Bitmap Heap Scan on search_flat s  (cost=4400.70..4428.76 rows=7 width=41) (actual time=20.060..42.333 rows=291 loops=1)
              Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/attributes/vendor'::text))
              Rows Removed by Index Recheck: 7657
              Heap Blocks: exact=6667
              Buffers: shared hit=688 read=6263
              ->  BitmapAnd  (cost=4400.70..4400.70 rows=7 width=0) (actual time=19.301..19.301 rows=0 loops=1)
                    Buffers: shared hit=270 read=14
                    ->  Bitmap Index Scan on sf_trgm  (cost=0.00..139.65 rows=1469 width=0) (actual time=3.283..3.283 rows=44298 loops=1)
                          Index Cond: ((val_text)::text ~~* '%234%'::text)
                          Buffers: shared hit=3 read=14
                    ->  Bitmap Index Scan on sf_exists  (cost=0.00..4260.80 rows=324297 width=0) (actual time=15.188..15.188 rows=305613 loops=1)
                          Index Cond: (wpath = '/attributes/vendor'::text)
                          Buffers: shared hit=267
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=41) (actual time=0.007..0.007 rows=0 loops=291)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=885 read=570
Planning:
  Buffers: shared hit=9 read=15
Planning Time: 0.252 ms
Execution Time: 45.223 ms
```

## Variant B — high-cardinality path `/features/*/properties/prop0` (~1,400,918 string rows for this wpath)

Round-1 (original run, 2026-07-03, historical — superseded by the round-2 rerun):

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| common feature path, cycling substrings (round-1) |         20 |     1752 |     1952 |     2178 |     2267 |

Round-2 rerun (current — authoritative, see Finding below):

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| common feature path, cycling substrings (round-2) |         20 |      590 |     2298 |     2405 |     2574 |

Index-backed (no seq scan on search_flat): true
Uses `Bitmap Index Scan` on `sf_trgm`: true

```
Nested Loop  (cost=5891.88..6735.28 rows=1 width=41) (actual time=541.325..552.417 rows=3 loops=1)
  Buffers: shared hit=13391 read=38761
  ->  HashAggregate  (cost=5891.33..5892.31 rows=98 width=41) (actual time=524.392..524.813 rows=4243 loops=1)
        Group Key: s.thing_id
        Batches: 1  Memory Usage: 737kB
        Buffers: shared hit=1 read=30936
        ->  Bitmap Heap Scan on search_flat s  (cost=139.68..5891.09 rows=98 width=41) (actual time=6.579..523.203 rows=4260 loops=1)
              Recheck Cond: ((val_text)::text ~~* '%234%'::text)
              Filter: (wpath = '/features/*/properties/prop0'::text)
              Rows Removed by Filter: 40038
              Heap Blocks: exact=30920
              Buffers: shared hit=1 read=30936
              ->  Bitmap Index Scan on sf_trgm  (cost=0.00..139.65 rows=1469 width=0) (actual time=3.623..3.623 rows=44298 loops=1)
                    Index Cond: ((val_text)::text ~~* '%234%'::text)
                    Buffers: shared hit=1 read=16
  ->  Index Scan using search_things_pkey on search_things st  (cost=0.55..8.60 rows=1 width=41) (actual time=0.006..0.006 rows=0 loops=4243)
        Index Cond: (thing_id = s.thing_id)
        Filter: ((global_read && '{user:sub-000,user:sub-041}'::text[]) AND (NOT COALESCE(((policy_auth #> '{attributes,location,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,location,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{attributes,·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND (COALESCE(((policy_auth #> '{attributes,·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false) OR ((NOT COALESCE(((policy_auth #> '{·r}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false)) AND COALESCE(((policy_auth #> '{·g}'::text[]) ?| '{user:sub-000,user:sub-041}'::text[]), false))))))
        Rows Removed by Filter: 1
        Buffers: shared hit=13390 read=7825
Planning:
  Buffers: shared hit=7 read=17
Planning Time: 0.359 ms
Execution Time: 552.449 ms
```

## Finding

**Correction (review round 1):** an earlier draft of this finding claimed the low-card
`vendor` path skips `sf_trgm` entirely and is instead served by the `sf_text (wpath,
val_text)` B-tree. That claim was wrong — `sf_text` never appears in either EXPLAIN above.
The corrected mechanism, read directly off the two plans:

- **Variant A (low-card `vendor` path):** `BitmapAnd(Bitmap Index Scan on sf_trgm, Bitmap
  Index Scan on sf_exists)`. `sf_trgm` (table-wide, not wpath-scoped) returns 44,298
  trigram-matching candidates; `sf_exists` (an index on `wpath` alone) returns the
  ~305,613 rows at this wpath. Because 305,613 is a small slice of the 68.5M-row table,
  ANDing the two bitmaps collapses to 291 candidates before the heap is even touched, so
  the query is fast whenever the relevant pages are cache-warm — this round-2 rerun
  measured min 46ms / p50 71ms (representative EXPLAIN above).
- **Variant B (high-card feature path):** a plain `Bitmap Heap Scan on search_flat`
  driven by `sf_trgm` alone (the *same* 44,298 table-wide candidates — the substring in
  the representative bind is identical), with `wpath = ...` applied as a post-fetch
  `Filter`, not a second bitmap leg: this wpath has ~1.4M rows, too many for `sf_exists`
  to narrow the AND usefully, so the planner heap-fetches all 44,298 trigram candidates
  and discards 40,038 of them by filter — this round-2 rerun measured p50/p95/max 2298/2405/2574ms.

Both variants therefore use `sf_trgm` and both prove the *same* underlying design gap:
`sf_trgm` is not scoped by `wpath`, so its candidate set is always the full 68.5M-row
table's trigram matches. Whether a query pays for that unscoped-ness cheaply (Variant A,
because this wpath's own row count happens to be low enough for `sf_exists` to shrink the
BitmapAnd) or expensively (Variant B, because this wpath is too large for that
intersection to help) is an **incidental planner choice driven by this wpath's row
count — it is NOT evidence that low-cardinality-path ilike queries are naturally safe**.
A wpath-scoped or partial trigram index (or a composite index including wpath) is very
likely needed for both variants before ilike is production-viable at scale — the
wpath-scoping concern stands regardless of a given path's cardinality.

**Run-to-run instability disclosure (review round 2, Finding 1):** Variant A's own warm
p95 straddles the 200ms bar across the two runs captured for this task (round-1 442ms → round-2 rerun 126ms; max 467ms). This container's default `shared_buffers` is small relative to the ~15GB dataset
(see the Task 0.2 report's Concern #2), so whether the pages a given iteration's
substring/city bind needs happen to be cache-resident is itself somewhat random at this
scale — the PASS/FAIL classification for Variant A is cache-state-dependent, not a
stable property of the query shape. **Treat Variant A as borderline, not a clean PASS,
even on a run where its p95 lands under 200ms.** Variant B carries no such ambiguity: it
is unambiguously red-flagged in both runs (round-1 p95 2178ms → round-2 rerun 2405ms) — no run has come close to the 200ms bar.
