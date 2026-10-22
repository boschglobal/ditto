# Production-config search benchmark: final re-run on the shipped code (2026-10-22)

**Result.** 8 of 20 variants pass the 200 ms read gate (all-rounds warm p95): `3-low`, `ce-a`, `ce-a-sudo`, `ce-b`,
`lone-wide`, `lone-wide-sudo`, `lone-wide-count`, `lone-mid-sudo`. The misses:

- **Shapes 1 and 5** (267.1 / 205.4 ms): a slow first round sets the all-rounds p95; rounds 2–3 are 133.6 · 129.3 ms
  and 159.9 · 166.6 ms. Both are 5–8× below the baseline (1,364.6 / 1,641.0 ms): selective range legs with the grouped
  pick.
- **Shape 2, `ce-c`, `lone-mid`** (422.8 / 314.0 / 491.0 ms): the first round only (436.7 / 357.8 / 544.1 ms p95);
  rounds 2–3 are 110–142 ms.
- **3-high** (356.7 ms, settled): the table-wide trigram index (a per-path index brings it to ~120 ms, below).
- **The five shape-4 sort pages** (374.7–720.0 ms).
- **`lone-wide-sudo-count`** (6,076.2 ms): a sudo count over 622,807 things.

**These are the read numbers that describe the shipped system; they supersede `../summary.md` (2026-09-30).** Every
run whose numbers are used here was reliable on the host-memory rule (`host-memory.md`). Two first tries in
`lone-range/` failed on the statement timeout and were repeated (see there).

## What was run

- Code: `f543fa47df` (search schema v3: production DDL + the system index `sf_multi`; selective range legs as shipped:
  an equality + two-sided range pick with a grouped leg, a lone two-sided range never materialised). Harness:
  `ProductionConfigReadBench` as committed, no change.
- Corpus: the 1M corpus (`CorpusGenerator`, seed 42, 68,513,542 flat rows, 29 GB) loaded on 2026-10-22 11:05–11:16
  UTC by `run-benchmark.sh pc-up pc-load` (`load.md`, a verbatim copy of `../range-legs-quiet-host/after/load/load.md`;
  same container, not reloaded). `corpus-record.md`: no writes since the load, `sf_multi` valid, no extended
  statistics.
- Server: container `ditto-search-bench-pc`, PostgreSQL 16.15, postgres:16 image defaults (`shared_buffers` 128MB,
  `effective_cache_size` 4GB, `work_mem` 4MB, `jit` on): `environment.md`.
- Stages on that corpus (UTC, 2026-10-22), each `run-benchmark.sh <stage> --results-dir
  src/test/resources/bench/results/production-config/final-2026-10-22[/<sub-folder>] …`:
  - `pc-read --rounds 3 --range-legs auto` 13:46:38–14:00:47 (Maven `Total time: 14:07 min`);
  - `pc-cold --cold-samples 3 --range-legs auto` 14:03:03–14:08:44 (`05:38 min`);
  - `pc-bias` 14:09:01–14:09:25 (`20.895 s`);
  - then `lone-range/` 14:11:41–15:06:32, `path-index/` 15:08:52–15:19:16, `invariance/` 15:19:26–15:37:38.

  Selective range legs `auto` ("on (sf_multi valid)") unless named.
- Protocol (unchanged from the baseline): 3 contiguous rounds per variant, each a warmup over the whole filter rotation
  then 20 timed executions, nearest rank; "settled" = per-round p95 within 10 % or 10 ms. **Cold** = container stop,
  Docker-VM page cache dropped (`drop_caches` from a privileged container), start, fresh pool, first execution; 3
  samples. The macOS host file cache is not dropped: "PostgreSQL- and VM-cold, host-cache state unknown" (`cold.md`).
- Host: OrbStack VM with 12 CPUs and 25.2 GB (`environment.md`); memory rule and per-run samples in `host-memory.md`.

## Headline (`summary.md`)

| variant | warm p50 / p95 (all rounds) | p95 per round | settled? | cold 1st exec (3 samples) | 200 ms gate |
|---|---:|---|---|---|:---:|
| `1` | 123.5 / 267.1 | 295.9 · 133.6 · 129.3 | no (128.9% / 166.6 ms) | 2107.0 · 249.5 · 231.9 | **MISS** |
| `2` | 109.7 / 422.8 | 436.7 · 116.2 · 109.9 | no (297.5% / 326.8 ms) | 190.6 · 251.9 · 209.3 | **MISS** |
| `3-low` | 41.9 / 53.4 | 54.7 · 43.6 · 44.2 | no (25.6% / 11.1 ms) | 799.8 · 73.5 · 71.9 | PASS |
| `3-high` | 207.8 / 356.7 | 358.4 · 338.3 · 333.3 | yes | 3130.9 · 488.5 · 458.0 | **MISS** |
| `4-page1` | 314.0 / 557.7 | 352.9 · 574.9 · 337.8 | no (70.2% / 237.2 ms) | 649.8 · 606.5 · 743.5 | **MISS** |
| `4-offset` | 324.8 / 446.8 | 384.6 · 468.6 · 331.5 | no (41.3% / 137.1 ms) | 608.8 · 604.9 · 607.7 | **MISS** |
| `4-keyset` | 657.7 / 720.0 | 678.1 · 706.9 · 763.2 | no (12.5% / 85.1 ms) | 4725.4 · 996.9 · 993.7 | **MISS** |
| `4-offset-5000` | 347.1 / 374.7 | 360.7 · 362.7 · 375.7 | yes | 616.6 · 621.5 · 614.8 | **MISS** |
| `4-keyset-5000` | 257.9 / 461.9 | 535.9 · 257.6 · 461.9 | no (108.0% / 278.3 ms) | 450.5 · 386.3 · 366.8 | **MISS** |
| `5` | 138.3 / 205.4 | 215.1 · 159.9 · 166.6 | no (34.5% / 55.2 ms) | 224.4 · 233.5 · 231.0 | **MISS** |
| `ce-a` | 1.6 / 1.7 | 1.7 · 1.7 · 1.7 | yes | 6.0 · 5.4 · 5.5 | PASS |
| `ce-a-sudo` | 1.3 / 1.5 | 1.5 · 1.6 · 1.5 | yes | 8.9 · 4.9 · 7.2 | PASS |
| `ce-b` | 16.6 / 41.5 | 27.6 · 16.7 · 48.2 | no (187.6% / 31.4 ms) | 35.7 · 40.4 · 36.8 | PASS |
| `ce-c` | 125.9 / 314.0 | 357.8 · 136.3 · 126.8 | no (182.2% / 231.0 ms) | 247.2 · 206.5 · 208.5 | **MISS** |
| `lone-wide` | 114.4 / 129.6 | 121.6 · 131.2 · 117.7 | no (11.5% / 13.5 ms) | 182.0 · 212.1 · 184.7 | PASS |
| `lone-wide-sudo` | 1.1 / 1.2 | 1.2 · 1.2 · 1.1 | yes | 10.9 · 4.4 · 4.5 | PASS |
| `lone-wide-count` | 72.4 / 107.7 | 115.1 · 78.8 · 66.2 | no (73.8% / 48.9 ms) | 171.4 · 211.7 · 201.6 | PASS |
| `lone-wide-sudo-count` | 5403.8 / 6076.2 | 6076.2 · 5688.4 · 6001.1 | yes | 56502.2 · 13553.8 · 9899.6 | **MISS** |
| `lone-mid` | 130.3 / 491.0 | 544.1 · 142.1 · 131.9 | no (312.5% / 412.2 ms) | 2463.6 · 226.1 · 231.1 | **MISS** |
| `lone-mid-sudo` | 4.4 / 6.0 | 6.0 · 6.1 · 6.0 | yes | 113.4 · 26.6 · 17.0 | PASS |

- **First rounds.** In round 1, the timed iterations from the end of the filter rotation on (10 for shape 1, `ce-c`,
  `lone-mid`; 7 for shape 2) meet their subject pair for the first time, as in the baseline. Shape 1's round-1 p95 comes
  from iterations 3 and 10–12 (267–330 ms); rounds 2–3 run 119–134 ms. Round 1 from that iteration on: shape 2
  213–480 ms (iterations 3–6: 112–122 ms), `ce-c` 166–369 ms (3–9: 119–126 ms), `lone-mid` 381–549 ms (3–9:
  126–149 ms). Shape 5 is slower in every round (round 1 up to 216 ms, rounds 2–3 118–181 ms).
- **Shape 4** reads the same pages in every iteration, yet shows single slow iterations (page 1 up to 683 ms in round 2,
  page-201 cursor up to 612 ms in round 1 and 499 ms in round 3, against a floor of ~310 / ~255 ms), so four of the
  five pages did not settle.

## Against the baseline (`vs-baseline.md`)

| variant | baseline p95 | final p95 | baseline gate | final gate | SQL / plan |
|---|---:|---:|---|---|---|
| 1 | 1364.6 | 267.1 | MISS (did not settle) | MISS (did not settle) | changed / changed |
| 2 | 398.8 | 422.8 | MISS (did not settle) | MISS (did not settle) | same / same |
| 3-low | 50.3 | 53.4 | PASS (settled) | PASS (did not settle) | same / same |
| 3-high | 391.5 | 356.7 | MISS (did not settle) | MISS (settled) | same / changed |
| 4-page1 | 599.5 | 557.7 | MISS (settled) | MISS (did not settle) | changed / changed |
| 4-offset | 653.4 | 446.8 | MISS (settled) | MISS (did not settle) | changed / changed |
| 4-keyset | 772.7 | 720.0 | MISS (settled) | MISS (did not settle) | changed / changed |
| 4-offset-5000 | 647.7 | 374.7 | MISS (settled) | MISS (settled) | changed / changed |
| 4-keyset-5000 | 299.9 | 461.9 | MISS (settled) | MISS (did not settle) | changed / changed |
| 5 | 1641.0 | 205.4 | MISS (did not settle) | MISS (did not settle) | changed / changed |

Reading rule: a change counts as a code effect only where the iteration-0 SQL **and** plan changed; the isolated
effect is in that change's A/B folder: shapes 1/5 — selective range legs and the grouped pick
(`../range-legs-quiet-host/`, `../selective-range-leg/`); 4-* — the path-bound sort lateral (`../sort-path-bound/`).
Shapes 2, 3-low and 3-high render the same SQL as in the baseline; their differences are drift between loads and
days. 3-high's iteration-0 plan changed with the new load's `ANALYZE` sample: its pattern `*234*` now gets
`BitmapAnd(sf_trgm, sf_exists)`, the plan the baseline showed only for `*890*` (diff in `vs-baseline.md`).

## Same code, same corpus: against the previous run (`../range-legs-quiet-host/after/`)

The previous run measured 13 of these variants with the same code on this load (`after/B1-auto`, `after/B2-auto`,
2026-10-22 11:43–12:05 UTC). `byte-identity.md` check 2: the same SQL and binds for all 13. All-rounds warm p95, ms:

| variant | previous `B1-auto` | previous `B2-auto` | this run | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---|
| 1 | 159.8 ¹ | 165.2 ¹ | 267.1 ¹ | 162 → 267 (+64 %) | 16 | slower |
| 2 | 177.2 ¹ | 163.9 ¹ | 422.8 ¹ | 171 → 423 (+148 %) | 17 | slower |
| 5 | 135.4 | 132.2 | 205.4 ¹ | 134 → 205 (+54 %) | 13 | slower |
| ce-a | 2.3 | 2.3 | 1.7 | 2 → 2 (-26 %) | 10 | within band |
| ce-a-sudo | 1.9 | 2.0 | 1.5 | 2 → 2 (-23 %) | 10 | within band |
| ce-b | 18.8 | 18.3 | 41.5 ¹ | 19 → 42 (+124 %) | 10 | slower |
| ce-c | 148.9 ¹ | 147.3 ¹ | 314.0 ¹ | 148 → 314 (+112 %) | 15 | slower |
| lone-wide | 114.8 | 114.0 | 129.6 ¹ | 114 → 130 (+13 %) | 11 | slower |
| lone-wide-sudo | 1.5 | 1.4 | 1.2 | 1 → 1 (-17 %) | 10 | within band |
| lone-wide-count | 130.1 ¹ | 83.5 ¹ | 107.7 ¹ | 107 → 108 (+1 %) | 11 | within band |
| lone-wide-sudo-count | 5517.3 | 5719.8 ¹ | 6076.2 | 5619 → 6076 (+8 %) | 562 | within band |
| lone-mid | 448.0 ¹ | 510.4 ¹ | 491.0 ¹ | 479 → 491 (+2 %) | 48 | within band |
| lone-mid-sudo | 5.6 | 5.8 | 6.0 | 6 → 6 (+5 %) | 10 | within band |

¹ did not settle (per-round p95 spread > 10 % and > 10 ms). Band = max(10 % of the previous runs' mean, 10 ms).

The six rows outside the band are **run-to-run spread on the same load and code**: identical statements, and the
difference sits in single rounds or spreads over a whole run (per-round p95, ms):

- round 1 of shapes 1 and 2 and `ce-c`: 295.9, 436.7, 357.8 against 169.8–178.7, 175.9–192.8, 151.7–156.3 in the
  previous runs, while their rounds 2–3 stay within 13 ms of the previous runs';
- rounds 1 and 3 of `ce-b`: 27.6 and 48.2 against 17.5–18.9;
- every round of shape 5 (159.9–215.1 against 129.0–137.8) and of `lone-wide` (117.7–131.2 against 113.1–114.9).

## Lone two-sided ranges, `off` vs `auto` (`lone-range/`, ABBA)

`A*` = `--range-legs off`, `B*` = `--range-legs auto`; order A1, B1, B2, A2; the six `lone-*` variants. All-rounds
warm p95, ms:

| variant | A1 | B1 | B2 | A2 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| lone-wide | 195.9 ¹ | 285.3 ¹ | 123.8 ¹ | 193.2 ¹ | 195 → 205 (+5 %) | 19 | within band |
| lone-wide-sudo | 2.9 | 3.3 | 2.8 | 2.5 | 3 → 3 (+13 %) | 10 | within band |
| lone-wide-count | 280.7 ¹ | 226.5 ¹ | 89.2 ¹ | 272.7 ¹ | 277 → 158 (-43 %) | 28 | faster |
| lone-wide-sudo-count | 5809.0 ¹ | 5717.7 ¹ | 10338.1 | 5013.8 | 5411 → 8028 (+48 %) | 541 | slower |
| lone-mid | 533.7 ¹ | 473.0 ¹ | 397.9 ¹ | 200.8 ¹ | 367 → 435 (+19 %) | 37 | slower |
| lone-mid-sudo | 6.5 | 6.4 | 11.6 | 6.1 | 6 → 9 (+43 %) | 10 | within band |

¹ did not settle (per-round p95 spread > 10 % and > 10 ms).

A lone range renders the same statement and binds with range legs `auto` as `off` in all four runs, without
`sel_leg` and without `GROUP BY` (`byte-identity.md` checks 3–5). The three verdicts outside the band are therefore
**identical statement; run-to-run drift**: `lone-wide-count` (round-1 p95 244–289 ms in A1, B1, A2; 95 ms in B2),
`lone-wide-sudo-count` (B2's p50 9,058 ms against 4,870–5,232 ms in the other three runs; the host's 1-minute load
average stood at ~25 right after B2, from other processes, and the memory rule does not judge CPU) and `lone-mid`
(round 1 sets each run's p95; A2's round 1 was the fastest, 214 ms against 437–554 ms). **With the shipped code a lone
range is never materialised.**

**Two first tries failed and were repeated.** `B1-auto` (14:22:49) and `A2-off` (14:54:59) each failed after ~1.5 min:
the harness's untimed total-match count for `lone-wide-sudo` (the sudo count over the range 0..100, the statement
`lone-wide-sudo-count` times) exceeded the shipped 60 s statement timeout. That count is I/O-bound when the VM page
cache does not hold its index pages; its first cold execution took 56.5 s (`cold.md`). The failed tries wrote nothing;
each was repeated once into the same folder (`host-memory.md`; `A2-off`'s first try was also unreliable, 49 % free at
its end). The timeout was not raised.

## Shape 3 with a per-path trigram index (`path-index/`, ABBA)

`A*` = production DDL, `B*` = plus `sf_path_trgm_ca5f5cd692cb58e9` (`build.md`: built with `CREATE INDEX CONCURRENTLY`
in 43.4 s, 51 MB, `indisvalid` true, the definition `PathIndex.createStatement()` renders; dropped before A2,
`leftover` 0). Order A1, build, B1, B2, drop, A2; variants `3-low` (control) and `3-high`. All-rounds warm p95, ms:

| variant | A1 | B1 | B2 | A2 | A mean → B mean | band | verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| 3-low | 69.3 ¹ | 48.6 | 42.3 | 43.3 | 56 → 45 (-19 %) | 10 | faster |
| 3-high | 294.2 ¹ | 136.1 ¹ | 103.4 ¹ | 301.8 | 298 → 120 (-60 %) | 30 | faster |

¹ did not settle (per-round p95 spread > 10 % and > 10 ms).

Both EXPLAINs of 3-high in each B run's `shape-3-ilike.md` use `Bitmap Index Scan on sf_path_trgm_ca5f5cd692cb58e9`
and neither `sf_exists` nor `BitmapAnd`; the A runs' 3-high plans use `BitmapAnd(sf_trgm, sf_exists)`. 3-low never
uses the index: its "faster" is A1's unsettled first run after the lone-range series (A2 43.3 ms, B 42.3–48.6 ms).
The B runs' catalog check reads "production DDL + 1 per-path index (46 items compared)". The per-path index is an
operator option (off by default), so these numbers are not part of the headline.

## Invariance (`invariance/invariance.md`)

`run-benchmark.sh pc-invariance` (default set: every variant with a two-sided range over its whole rotation plus three
`attributes/region` filters), 15:19–15:37 UTC, Maven `Total time: 18:10 min`, on production DDL (the per-path index
dropped): **Differences: 0**. 51 requests executed in both modes with the same request result and the same full
ordered sudo match list (shapes 1/5, `ce-a`, `ce-a-sudo`, `ce-b` × 10 iterations and the region pair); 36 identical
statements not executed (`ce-c`, `lone-mid`, `lone-mid-sudo` × 10, the four `lone-wide*` × 1, two lone region filters).

## Caveats

- Laptop OrbStack VM; postgres:16 defaults; fresh load (COPY-clustered heap, `CREATE INDEX`-built B-trees, visibility
  map set by `VACUUM`, no system-field rows); synthetic corpus whose independent auth attributes flatter shapes 1, 2, 5
  and the cursor pages (`corpus-bias.md`, the same counts as the baseline's).
- Cold samples carry an unknown host-cache state; do not derive a cold-vs-warm ratio.
- The memory rule judges swap-outs and free memory, not CPU. Single slow rounds and iterations above (first rounds,
  shape 4's spikes, `lone-range/B2-auto`) make the all-rounds p95 of unsettled variants the conservative figure;
  per-round p95s are given next to it.
- A sudo count over ~62 % of the things takes ~5.4 s warm and reached the 60 s statement timeout on a cold page cache
  (`lone-wide-sudo-count` cold sample 1: 56.5 s; the two failed first tries above).
