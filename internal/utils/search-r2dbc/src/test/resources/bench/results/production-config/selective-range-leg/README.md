# Selective range leg: shapes 1/5 and the leg-choice counter-examples (back-to-back ABBA, 2026-10-06)

**Result: the 200 ms read gate is missed.** With range legs the statement picks the smaller leg every time it has two
(the range for shapes 1/5 and `ce-b`, the point leg for `ce-a-sudo`; `ce-c` has only the range), but shapes 1 and 5
stay far above 200 ms: the main statement then evaluates the city equality as a set (a bitmap scan of all ~90k city
rows feeding a merge/hash semi join) instead of probing the ~13.9k range candidates one by one. On this run the host
was also swapping heavily, so the absolute numbers are inflated (see "Environment").

These files compare, on a fresh 1M corpus loaded with search schema v3 (`load/`), two versions of the selective-leg
CTE, with the same jar and corpus:

- **A, point legs only** (`--range-legs off`, `SelectiveLegs.POINT`): the statement as shipped before range legs. The
  iteration-0 SQL of shape 1 in `A1-point-legs/` and `A2-point-legs/` is byte-identical to the one in
  `../shape-1-eq-and-range.md`.
- **B, range legs** (`--range-legs auto`): as the service runs once its probe sees `sf_multi` valid;
  `environment.md` of every B run says "Selective range legs: on (sf_multi valid)".

## How the runs were taken

- **Corpus:** `run-benchmark.sh pc-down --purge pc-up pc-load --results-dir …/selective-range-leg/load --yes`,
  2026-10-06 22:16:00–22:35:04 UTC (1,130.6 s): `CorpusGenerator` seed 42, 1,000,000 things, 68,513,542 `search_flat`
  rows, database 29 GB, catalog check against the production schema (DDL + `sf_multi`) **true, 46 items**. Container
  `ditto-search-bench-pc` (postgres:16 = PostgreSQL 16.15, image defaults: 128 MB `shared_buffers`, `jit = on`),
  volume `ditto-search-bench-pc-data`. `load/load.md` was written before the loader's report text named `sf_multi`
  (it still says "`PostgresSearchSchema.ddlStatements()` only"); its catalog check and sizes include `sf_multi`.
- **`sf_multi` concurrent build:** `build.md` (71.8 s, 213 MB, 2,330,008 entries).
- **Order (UTC):** `A1-point-legs` 22:38:26–22:52:56, `B1-range-legs` 22:52:56–23:02:57, `B2-range-legs`
  23:02:57–23:13:59, `A2-point-legs` 23:13:59–23:32:54, then `B3-range-legs` 23:32:54–23:47:54 (shapes 1/5 did not
  settle in B1/B2).
- **Command:** each run is one `run-benchmark.sh pc-read --rounds 3 --variants 1,5,2,ce-a,ce-a-sudo,ce-b,ce-c
  --range-legs off|auto --results-dir <dir>`: the production-config warm protocol of `../` (3 contiguous rounds per
  shape, each a full-rotation warmup + 20 timed iterations through `PostgresThingsSearchPersistence`, nearest rank;
  plans right after a shape's rounds).
- **What is kept:** per run the harness's own output (`summary.md`, `environment.md`, `shape-1-eq-and-range.md`,
  `shape-2-gt-range.md`, `shape-5-count.md`, `ce-leg-choice.md`, `warm.json`).

## Result (all-rounds warm p95, ms)

"A mean → B mean" uses A1/A2 and B1/B2 (B3 is the extra run the protocol asks for when B does not settle). Band =
max(10 % of the A mean, 10 ms), the noise band of `../`.

| variant | A1 | B1 | B2 | A2 | B3 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 7033.5 ¹ | 3390.2 ¹ | 4107.0 ¹ | 6567.8 | 7636.8 ¹ | 6801 → 3749 (-45%) | 680 | gate: MISS |
| 2 | 564.3 ¹ | 697.6 ¹ | 627.0 ¹ | 604.2 ¹ | 720.7 ¹ | 584 → 662 (+13%) | 58 | above band; drift (same SQL in A and B) |
| 5 | 3099.9 ¹ | 5751.1 ¹ | 6116.7 ¹ | 7354.2 ¹ | 6164.2 ¹ | 5227 → 5934 (+14%) | 523 | gate: MISS; above band |
| ce-a | 2.8 | 4.4 | 3.4 | 4.0 | 3.4 | 3 → 4 (+15%) | 10 | within band |
| ce-a-sudo | 2.1 | 2.7 | 3.3 | 2.5 | 2.9 | 2 → 3 (+30%) | 10 | within band |
| ce-b | 2875.7 ¹ | 938.9 ¹ | 1167.0 ¹ | 6304.1 ¹ | 1662.4 ¹ | 4590 → 1053 (-77%) | 459 | faster |
| ce-c | 685.1 ¹ | 584.0 ¹ | 289.3 ¹ | 571.7 ¹ | 296.0 ¹ | 628 → 437 (-31%) | 63 | faster |

¹ did not settle (per-round p95 spread > 10 % and > 10 ms).

- **Gate (shapes 1 and 5 below 200 ms all-rounds p95 in every B run, settled): missed** in B1, B2 and B3.
- **Counter-examples:** `ce-a-sudo` (rare zip + wide range, no auth) keeps the point leg (its point branch runs in
  every B run, `ce-leg-choice.md`) and stays at 2–3 ms. `ce-a` (the same with the corpus's auth) does not show the
  pick: the statement returns no row before it reads `sel_leg`, so `sel_pick` and `sel_leg` are `(never executed)`
  ("picked an undetermined leg") and its 2–4 ms is that empty result. `ce-b` (city + 0.09-wide range) and `ce-c` (lone
  0.99-wide range, ~1.4 % of the things) are faster with range legs. Shape 2 has no selective-leg CTE in either mode
  (its SQL is identical in A and B), so its +13 % is drift, not an effect (see "Environment"). Shape 5 (the count) is
  above the band by the timed p95 (+707 ms against a band of 523 ms), while its warm EXPLAINs below are faster in every
  B run; none of its runs settled.
- **Not covered: a lone wide two-sided range.** `ce-c` is narrow, and `ce-a`/`ce-a-sudo` always have an equality,
  which the capped count of the pick protects. A two-sided range that is the only candidate is materialised with no
  cap, where the statement before range legs had no selective-leg CTE. An earlier measurement on a 1M corpus of the
  same generator materialised the temperature range 0..100 as the only leg: 622,807 thing ids in 2,851 ms. A lone
  filter of that width is therefore expected to be slower with range legs; it is not measured here.
- **Warm EXPLAINs** (iteration 0 / slowest, ms; a single execution each, less affected by the swapping than the timed
  p95): shape 1 A 2,110 / 1,852 · 1,793 / 3,695 → B 929 / 1,043 · 978 / 972 · 1,368 / 1,216; shape 5 A 1,582 / 1,608 ·
  1,762 / 4,036 → B 966 / 1,002 · 973 / 990 · 1,375 / 1,427.

## Why shapes 1 and 5 stay slow (from the B plans in `shape-1-eq-and-range.md` / `shape-5-count.md`)

- **The pick is right.** `sel_pick` counts the city up to 50,000 (Stuttgart has 89,901 entries) and the range
  (13,869 entries + 0 multi-valued) in ~10 ms; `sel_leg` materialises the range branches only (the point branch is
  `(never executed)`): 13,869 thing ids in ~94 ms instead of the city's 89,901 ids in ~375 ms (`../`).
- **The main statement then reads the whole city anyway.** The full predicate stays in `WHERE`, and the planner
  evaluates the city's `EXISTS` conjunct as a `Bitmap Heap Scan on search_flat sf` over all ~89.9k Stuttgart rows
  (lossy at `work_mem` 4 MB: 1.93 million rows removed by recheck, 640–1,000 ms), sorted or hashed into a merge/hash
  semi join with the ~5 candidates that survive the auth recheck, instead of 5 per-thing probes. It estimates the city
  at 1,223 rows (actual 89,901) and `sel_leg` at 33,452 rows (actual 13,869; the multi-valued branch alone is estimated
  at 31,795 rows and returns 0).
- The same plan shape is behind `ce-b`'s spread: at iteration 0 no candidate survived the auth recheck, so the merge
  ended before the city scan started (34 ms); in the slowest iteration one did, and the city scan ran (771 ms).
- **The multi-valued branch's estimate causes the switch** (`psql-checks.md`): the same statement without that
  branch is estimated at 1,657 `sel_leg` rows, probes per thing and takes 128–148 ms; with it (as shipped) 747 ms warm
  on the same corpus right after. Without the branch the leg would lose things whose array elements straddle the range
  (see the `SelectiveLegCte` Javadoc), so dropping it is not an option; the question is how to keep it without its
  estimate.

## Environment

- macOS host with 36 GB RAM, OrbStack VM with 23.5 GB and 12 CPUs. During the runs the host used 12–13 GB of its
  ~13–14 GB swap (the 29 GB database fills the VM's page cache), with other applications running. "Cache hits" of the
  VM could therefore be host swap-ins.
- Consequences: apart from `ce-a`/`ce-a-sudo`, only shape 1 in `A2-point-legs` settled; shape 1's A runs are ~5× the committed baseline of `../`
  (6.8 s vs 1.37 s p95); shape 2, whose SQL does not change, moved by 13 %.
- After the series the Docker VM stopped responding to `docker` commands for some hours; the check in `psql-checks.md`
  ran once it answered again.

## Invariance

The `pc-invariance` stage (`ProductionConfigReadBench#legInvariance`) was started at 23:47:54 UTC and was stopped
after 1 h 46 min while at shape 1, iteration 6 of 10 (each iteration runs the request and
the full sudo match list in both modes, about 1.7 minutes per iteration at first and slower later). It writes
`invariance.md` only at the end, so there is none here. That range legs do not change results is shown by
`PostgresSearchReadIT` (array-valued paths, find and count, sudo and auth; a mutation without the multi-valued branch
fails it) and by the Mongo parity battery with range legs on (`SearchBackendParityIT`, the `range-pair-*` cases).
