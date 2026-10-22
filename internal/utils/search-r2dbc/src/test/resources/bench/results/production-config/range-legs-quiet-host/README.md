# Range legs on a quiet host: a lone range is never materialised, the pick's leg is grouped (ABBA, 2026-10-22)

**Result.**

- **(a) A two-sided range alone keeps the statement it had before range legs** (no selective-leg CTE, byte for byte
  the `off` render; the byte-identity checks below hold for all seven lone variants). Measured against `off` on the same
  load, every lone variant is within the noise band, or differs only by run-to-run drift of an identical statement
  (`ce-c`, `lone-wide-count`; see the tables). The code it replaces materialised the lone range: for temperature 0..100
  (~62 % of the things) that was 52× (auth find), 82× (auth count) and ~2,300× (sudo find) slower than `off`, for a
  ~40,000-thing range 2.4× (auth find) and 31× (sudo find) slower (`before/`).
- **(b) The pick's `sel_leg` is grouped by thing id** (`SELECT sli.thing_id FROM (…) sli GROUP BY sli.thing_id`).
  Shapes 1 and 5 are faster beyond the band: 1,250 → 132 ms and 523 → 126 ms, start code `auto` vs final code `auto`
  on one load (the start code's shape 1 had slow first rounds there, 1,517 / 1,303 ms; its rounds 2–3 were 520–530 ms
  but one), and 582 → 162 ms / 515 → 134 ms across the two series; `ce-a`, `ce-a-sudo` are within the band and `ce-b`
  is faster (428 → 19 ms). The probe for a pick whose
  point leg wins with ~90,000 ids (`probe.md`) is equal or faster. Results are unchanged (see "Invariance").
- **Gate: shapes 1 and 5 pass 200 ms** (all-rounds warm p95 of the final code's B runs: shape 1 159.8 / 165.2 ms,
  shape 5 135.4 / 132.2 ms; in the extra ABBA 136.7 / 127.8 and 128.7 / 123.9 ms). Shape 5 settled in every B run;
  shape 1 settled in one of four (its first round is 25–33 % slower than its third).

## What is compared

- **before/** — the code that materialised a lone two-sided range (`WITH sel_leg AS MATERIALIZED (<range rows> UNION
  ALL <multi-valued rows>)`) and an ungrouped pick, `off` (`--range-legs off`, point legs only) and `auto` (range legs,
  `sf_multi` valid). Copied as measured; `psql-checks.md` is that run's spike (below).
- **after/** — the final code, `off` and `auto`, on a second load of the same corpus (same generator, seed 42).
- **after-pairs/** — shapes 1 and 5 only: the start code's `auto` (`A*-start-auto`, from a second checkout of the
  start commit with the same harness) against the final code's `auto` (`B*-final-auto`), on the "after" load.
- `off` renders the same statements in both series and in both code versions (checked below), so `off` before vs
  `off` after measures the drift between the two loads.

## How the runs were taken

- **Loads:** `run-benchmark.sh pc-down --purge pc-up pc-load --results-dir <dir> --yes`; before: 2026-10-22
  00:05–00:16 UTC (`before/load/`); after: 11:05–11:16:47 UTC (`after/load/`, `Total time: 11:34 min`). Both:
  1,000,000 things, 68,513,542 `search_flat` rows, 29 GB, catalog check against the production DDL **true (46 items)**,
  temperature 0..100 = 622,807 things, Stuttgart 89,901 entries, `lone-mid` buckets 39,571–40,389, no extended
  statistics (`*/load/corpus-checks.md`). Container `ditto-search-bench-pc`, PostgreSQL 16.15, image defaults
  (`jit = on`).
- **Each run:** `run-benchmark.sh pc-read --rounds 3 --variants <ids> --range-legs off|auto --results-dir <dir>
  --yes`, with `1,5,2,ce-a,ce-a-sudo,ce-b,ce-c,lone-wide,lone-wide-sudo,lone-wide-count,lone-wide-sudo-count,lone-mid,
  lone-mid-sudo` for the main runs. `environment.md` of every A run says "off (forced: bench.pc.rangeLegs=off)", of
  every B run "on (sf_multi valid)".
- **Order (UTC, 2026-10-22):** before `A1-off` 00:21–00:35, `B1-auto` 00:37–01:10, `B2-auto` 01:12–01:45, `A2-off`
  01:48–02:00; after `A1-off` 11:27–11:41, `B1-auto` 11:44–11:53, `B2-auto` 11:56–12:05, `A2-off` 12:07–12:20, then
  `A3-off` 12:23 and `B3-auto` 12:26 (`ce-c`, `lone-wide-count` only), then `after-pairs/` `A1-start-auto`
  12:29–12:32, `B1-final-auto` 12:34–12:35, `B2-final-auto` 12:37–12:38, `A2-start-auto` 12:40–12:42 (`1,5` only).
- **Memory protocol:** before each run two host samples 120 s apart must show `Swapouts` grown by ≤ 1,000 pages and
  ≥ 50 % free (quiet check); a run is reliable iff the samples right before and after it show the same. Every run was
  reliable on its first try with **0 swap-outs** (`host-memory.md`).
- **Noise band:** max(10 % of the reference arm's mean, 10 ms) on the all-rounds warm p95; "settled" = per-round p95
  within 10 % or 10 ms (¹ in the tables: not settled).
- **Drift check and extra runs:** the cross-series comparison of `auto` before vs after is used only where `off`
  before vs `off` after stays within the band. It did not for shapes 1 and 5 (`off` 1,046 → 1,314 ms and
  856 → 1,116 ms), so their verdict comes from `after-pairs/` (ABBA on one load). `ce-c` and `lone-wide-count`
  render identical statements in A and B but fell outside the band in the after series, so one extra
  `A3-off`/`B3-auto` pair was run for them.

## Tables (all-rounds warm p95, ms)

**After series — final code, `off` (A) vs `auto` (B), with the extra pair; acceptance (a) and the gate:**

| variant | A1 | B1 | B2 | A2 | A3 | B3 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 1341.4 ¹ | 159.8 ¹ | 165.2 ¹ | 1286.3 ¹ | – | – | 1314 → 162 (-88 %) | 131 | faster |
| 2 | 415.2 ¹ | 177.2 ¹ | 163.9 ¹ | 162.6 ¹ | – | – | 289 → 171 (-41 %) | 29 | faster |
| 5 | 1258.6 ¹ | 135.4 | 132.2 | 974.1 ¹ | – | – | 1116 → 134 (-88 %) | 112 | faster |
| ce-a | 2.4 | 2.3 | 2.3 | 2.0 | – | – | 2 → 2 (+5 %) | 10 | within band |
| ce-a-sudo | 1.7 | 1.9 | 2.0 | 1.6 | – | – | 2 → 2 (+18 %) | 10 | within band |
| ce-b | 868.4 | 18.8 | 18.3 | 835.3 | – | – | 852 → 19 (-98 %) | 85 | faster |
| ce-c | 271.0 ¹ | 148.9 ¹ | 147.3 ¹ | 159.0 ¹ | 318.5 ¹ | 224.6 ¹ | 250 → 174 (-30 %) | 25 | faster |
| lone-wide | 114.7 | 114.8 | 114.0 | 112.0 ¹ | – | – | 113 → 114 (+1 %) | 11 | within band |
| lone-wide-sudo | 1.9 | 1.5 | 1.4 | 1.5 | – | – | 2 → 1 (-15 %) | 10 | within band |
| lone-wide-count | 75.2 ¹ | 130.1 ¹ | 83.5 ¹ | 79.3 ¹ | 71.5 | 66.0 | 75 → 93 (+24 %) | 10 | slower |
| lone-wide-sudo-count | 5732.5 | 5517.3 | 5719.8 ¹ | 5498.1 | – | – | 5615 → 5619 (+0 %) | 562 | within band |
| lone-mid | 495.0 ¹ | 448.0 ¹ | 510.4 ¹ | 475.3 ¹ | – | – | 485 → 479 (-1 %) | 49 | within band |
| lone-mid-sudo | 6.1 | 5.6 | 5.8 | 5.8 | – | – | 6 → 6 (-4 %) | 10 | within band |

The lone variants, shape 2 and `ce-c` render the same statement in A and B (byte identity below), so their verdicts
are run-to-run drift: `lone-wide-count` B1 had a slow first round (135.7 vs 72.8 / 71.4 ms), and the extra pair gave
71.5 (A3) vs 66.0 (B3) ms; `ce-c` A1 and A3 had slow first rounds.

**`auto` before (start code, earlier load) vs `auto` after (final code); used for `ce-a`, `ce-a-sudo`, `ce-b`:**

| variant | Abefore1 | Abefore2 | Bafter1 | Bafter2 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 557.0 ¹ | 607.1 ¹ | 159.8 ¹ | 165.2 ¹ | 582 → 162 (-72 %) | 58 | faster |
| 2 | 141.2 ¹ | 128.3 ¹ | 177.2 ¹ | 163.9 ¹ | 135 → 171 (+27 %) | 13 | slower |
| 5 | 512.2 | 518.4 | 135.4 | 132.2 | 515 → 134 (-74 %) | 52 | faster |
| ce-a | 2.5 | 3.0 | 2.3 | 2.3 | 3 → 2 (-16 %) | 10 | within band |
| ce-a-sudo | 1.8 | 1.8 | 1.9 | 2.0 | 2 → 2 (+8 %) | 10 | within band |
| ce-b | 439.2 | 417.7 | 18.8 | 18.3 | 428 → 19 (-96 %) | 43 | faster |
| ce-c | 128.3 | 127.7 | 148.9 ¹ | 147.3 ¹ | 128 → 148 (+16 %) | 13 | slower |
| lone-wide | 5781.9 | 5927.0 | 114.8 | 114.0 | 5854 → 114 (-98 %) | 585 | faster |
| lone-wide-sudo | 3309.0 | 3293.1 | 1.5 | 1.4 | 3301 → 1 (-100 %) | 330 | faster |
| lone-wide-count | 5801.1 | 5806.5 | 130.1 ¹ | 83.5 ¹ | 5804 → 107 (-98 %) | 580 | faster |
| lone-wide-sudo-count | 9348.1 | 9296.1 | 5517.3 | 5719.8 ¹ | 9322 → 5619 (-40 %) | 932 | faster |
| lone-mid | 367.4 | 365.3 | 448.0 ¹ | 510.4 ¹ | 366 → 479 (+31 %) | 37 | slower |
| lone-mid-sudo | 147.7 | 161.6 ¹ | 5.6 | 5.8 | 155 → 6 (-96 %) | 15 | faster |

**`auto` start code vs final code on the "after" load (`after-pairs/`); replaces the rows of shapes 1 and 5 above:**

| variant | A1 | B1 | B2 | A2 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 1379.3 ¹ | 136.7 ¹ | 127.8 | 1119.9 ¹ | 1250 → 132 (-89 %) | 125 | faster |
| 5 | 550.5 ¹ | 128.7 | 123.9 | 496.3 | 523 → 126 (-76 %) | 52 | faster |

**Drift: `off` before vs `off` after (identical statements, two loads):**

| variant | Abefore1 | Abefore2 | Bafter1 | Bafter2 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 1039.2 ¹ | 1052.8 ¹ | 1341.4 ¹ | 1286.3 ¹ | 1046 → 1314 (+26 %) | 105 | slower |
| 2 | 349.0 ¹ | 124.9 ¹ | 415.2 ¹ | 162.6 ¹ | 237 → 289 (+22 %) | 24 | slower |
| 5 | 847.4 | 864.8 | 1258.6 ¹ | 974.1 ¹ | 856 → 1116 (+30 %) | 86 | slower |
| ce-a | 2.0 | 2.3 | 2.4 | 2.0 | 2 → 2 (+2 %) | 10 | within band |
| ce-a-sudo | 1.7 | 1.6 | 1.7 | 1.6 | 2 → 2 (+0 %) | 10 | within band |
| ce-b | 847.0 | 841.9 | 868.4 | 835.3 | 844 → 852 (+1 %) | 84 | within band |
| ce-c | 218.5 ¹ | 199.3 ¹ | 271.0 ¹ | 159.0 ¹ | 209 → 215 (+3 %) | 21 | within band |
| lone-wide | 110.6 | 113.6 | 114.7 | 112.0 ¹ | 112 → 113 (+1 %) | 11 | within band |
| lone-wide-sudo | 1.4 | 1.5 | 1.9 | 1.5 | 1 → 2 (+17 %) | 10 | within band |
| lone-wide-count | 70.1 | 72.2 | 75.2 ¹ | 79.3 ¹ | 71 → 77 (+9 %) | 10 | within band |
| lone-wide-sudo-count | 6108.7 | 6186.8 | 5732.5 | 5498.1 | 6148 → 5615 (-9 %) | 615 | within band |
| lone-mid | 156.8 ¹ | 147.4 ¹ | 495.0 ¹ | 475.3 ¹ | 152 → 485 (+219 %) | 15 | slower |
| lone-mid-sudo | 4.9 | 5.0 | 6.1 | 5.8 | 5 → 6 (+20 %) | 10 | within band |

¹ did not settle (per-round p95 spread > 10 % and > 10 ms).

`lone-mid`'s `off` moved from 152 to 485 ms between the loads with the same statement: in every after run its first
round took ~450–550 ms and rounds 2–3 ~120–140 ms (before: 123–125 ms in rounds 2–3).

## Byte identity (iteration-0 SQL and binds of the shape files)

```
- 13 variants in every after run: yes
- every variant: A1 = A2 and B1 = B2 (SQL + binds) in the after series: yes
- `off` unchanged: after A-run SQL + binds = before A-run SQL + binds, every variant: yes
- (a) lone ranges: after B-run SQL + binds = after A-run SQL + binds, no `sel_leg`, no `GROUP BY`: yes
- (b) pairs carry the grouped leg: yes
- pairs: after B-run SQL, ungrouped, + binds = before B-run SQL + binds: yes
- shape 2: identical SQL + binds in all eight runs: yes
ALL-OK
```

"Ungrouped" removes the `SELECT sli.thing_id FROM (…) sli GROUP BY sli.thing_id` wrapper of the pick's leg; the
remaining statement and binds equal the start code's. In `after-pairs/`, the start code's SQL equals `before/B1-auto`'s
and the final code's equals `after/B1-auto`'s for shapes 1 and 5; `A3-off`/`B3-auto` equal `after/A1-off` for `ce-c` and
`lone-wide-count`.

## Probe

`probe.md`: warm EXPLAINs of the grouped pick against the ungrouped one where the **point** leg wins with ~90,000 ids
(city Stuttgart with temperature 0..100), the case the bench variants do not cover, plus the earlier spike's pair
statements re-measured on this load.

## Why the grouped pick is faster (from the B plans of shapes 1 and 5)

- Before: `CTE sel_leg` was an `Append` estimated at 31,524 rows (actual 13,869). The multi-valued branch
  (`wpath = <temperature> AND ord = 1`) is estimated from the column-wide share of `ord = 1` rows (31,844) and returns
  none. With that estimate the main statement read the city as a set: a bitmap heap scan of all 89,901 Stuttgart rows
  into a merge semi join (shape 1, 518 ms) or a hash semi join (shape 5, 488 ms).
- After: `CTE sel_leg` is a `HashAggregate` estimated at 200 rows (actual 13,869). The main statement drives from the
  13,869 candidates, checks `global_read`/auth on `search_things_pkey` (5 survive) and then the range's lower bound
  and the city per candidate through `search_flat_pkey` (5 probes each; none is in Stuttgart): 133 ms (shape 1),
  123 ms (shape 5).
- A lone range is never grouped: with the same 200-row estimate a sorted sudo find over tens of thousands of ids drove
  from every id instead of stopping early (an earlier spike: ~40,000 ids 144 → 1,075 ms). Since a lone range now has
  no CTE at all, it cannot meet the grouping.

## Invariance

`invariance/invariance.md`: a bounded run, 2026-10-22 12:51–13:09 UTC (Maven `Total time: 18:35 min`), `run-benchmark.sh
pc-invariance --variants 1,5,ce-a,ce-a-sudo,ce-b,ce-c,lone-mid,lone-mid-sudo,lone-wide,lone-wide-sudo,lone-wide-count,
lone-wide-sudo-count`, on the "after" load. **Differences: 0.**

- Executed in both modes, same request result and same full ordered sudo match list: the 50 requests of shapes 1/5,
  `ce-a`, `ce-a-sudo`, `ce-b` (10 iterations each) and the region pair
  `and(eq(attributes/location/city,"Stuttgart"),ge(attributes/region,91700),le(attributes/region,93000))`. Each passed
  the vacuity check first: its range-legs render carries `sel_pick` and the `ord = 1` branch.
- Recorded as identical and not executed: the 34 lone-range requests (`ce-c`, `lone-mid`, `lone-mid-sudo` × 10, the four
  `lone-wide*` × 1) and the two lone `attributes/region` filters. Their statements and binds are the same in both modes.
- In the region pair's plan the range leg won (point branch `(never executed)`; 910 in-range rows + 13,709
  multi-valued rows, grouped to 14,490 thing ids). The appended plans are single executions, `POINT` first, so the
  `POINT` plans pay the cold reads; they are not timings.
- Also unchanged results: `PostgresSearchReadIT` (including the grouped pick when the range wins on a straddling array,
  and a lone range with 40,002 matches) and the Mongo parity battery with range legs on, on PostgreSQL 16, 17 and 18.

## `before/psql-checks.md`

The spike of the "before" run: every in-statement form of capping a lone range (count it, materialise it only below
50,000 entries, else the old plan) was slower than `off` — the count alone costs ~9 ms, a second query block or a large
CTE estimate adds 10–16 ms of JIT, and a `UNION ALL` derived table loses the old plan. Hence (a): no CTE for a lone
range at all.

## Not covered

- `pc-cold` with the six `lone-*` variants (it runs every variant regardless of `--variants`).
- The full `pc-invariance` run over every variant and iteration of a full rotation is the next session's; this folder
  holds a bounded run (`invariance/`).
- String ranges, `features/*` ranges and ranges under `or`/`not` keep the plan without range legs (unchanged here).
