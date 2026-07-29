# Task 0.3 Part 2 - v1.5 anti-join churn-reduction comparison

Same workload as Part 1 (500-leaf twins, 5 leaves changed per update, same target rate/worker count), alternative flat-row maintenance for Phase B: instead of blanket DELETE-all+INSERT-all, delete-changed/insert-changed via anti-join against the freshly flattened row set, matching on FULL row identity (path, wpath, ord, type_rank, value) in a single statement with two writable CTEs sharing one `MATERIALIZED` unnest (plan doc §3.4 step 2 note). Two DISJOINT 2000-thing pools (poolA=Phase A blanket, poolB=Phase B anti-join), same corpus, same seed run, each measured for PT5M (a shorter comparison window than Part 1's 10 min, per the brief's explicit '2x5 min acceptable' allowance).

**First-touch caveat (disclosed, not hidden):** the very first write to any pool thing_id replaces that thing's PRE-EXISTING CorpusGenerator-shaped flat rows with the twin shape — zero row-identity overlap, so that specific update is full churn under BOTH v1 and v1.5. Only REPEAT touches to the same thing_id within this run let the anti-join's small-churn advantage show up; at the pool size/duration/rate used here, first-touch updates are a small minority of the total (see the per-phase average deleted/inserted-rows-per-txn figures below for the actual, not assumed, effect).

## Phase A - v1 blanket DELETE+INSERT (poolA, 2000 things)

GIN / autovacuum / memory knobs in effect (recorded per the brief; defaults are fine, they just must be recorded):

```
gin_pending_list_limit = 4MB
autovacuum_vacuum_scale_factor = 0.2
autovacuum_vacuum_cost_limit = -1
autovacuum_vacuum_cost_delay = 2ms
autovacuum_naptime = 1min
shared_buffers = 4GB
effective_cache_size = 12GB
work_mem = 64MB
sf_trgm reloptions (fastupdate; empty = default ON) = (none — all defaults)
sf_trgm_scoped_feature reloptions = (none — all defaults)
search_flat reloptions (autovacuum overrides; empty = using the global defaults above) = (none — all defaults)
statistics targets: val_num=1000 val_text=1000 wpath=10000
```

### 30s samples

### Sample #1 (t+30s)

- window 30.0s: 5976 txns, achieved rate 199.2/s; cumulative success=5976 errors=0
- txn latency (this window): p50=9ms p95=32ms max=165ms
- WAL bytes this window: 2697940240 (2573.0 MB)
- relation sizes (bytes): search_flat=18952814592 search_flat_pkey=15935234048 sf_num=3660898304 sf_text=663478272 sf_bool=91389952 sf_exists=1183498240 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69210859 n_dead_tup=47597978 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=128583943 n_tup_del=58373526

### Sample #2 (t+60s)

- window 30.0s: 5974 txns, achieved rate 199.0/s; cumulative success=11950 errors=0
- txn latency (this window): p50=9ms p95=31ms max=132ms
- WAL bytes this window: 3214104104 (3065.2 MB)
- relation sizes (bytes): search_flat=19354066944 search_flat_pkey=15944736768 sf_num=3660898304 sf_text=663478272 sf_bool=91389952 sf_exists=1204019200 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69247293 n_dead_tup=50566041 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=131588440 n_tup_del=61341589

### Sample #3 (t+90s)

- window 30.0s: 5938 txns, achieved rate 197.9/s; cumulative success=17888 errors=0
- txn latency (this window): p50=9ms p95=51ms max=215ms
- WAL bytes this window: 3453759808 (3293.8 MB)
- relation sizes (bytes): search_flat=19753648128 search_flat_pkey=15946457088 sf_num=3660898304 sf_text=663478272 sf_bool=91389952 sf_exists=1204019200 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=53556413 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=134580913 n_tup_del=64331961

### Sample #4 (t+120s)

- window 30.0s: 5737 txns, achieved rate 191.2/s; cumulative success=23625 errors=0
- txn latency (this window): p50=14ms p95=84ms max=237ms
- WAL bytes this window: 3398224200 (3240.8 MB)
- relation sizes (bytes): search_flat=20138876928 search_flat_pkey=15948079104 sf_num=3676471296 sf_text=663478272 sf_bool=91389952 sf_exists=1204019200 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=56438666 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=137463166 n_tup_del=67214214

### Sample #5 (t+150s)

- window 30.0s: 5759 txns, achieved rate 191.9/s; cumulative success=29384 errors=0
- txn latency (this window): p50=15ms p95=74ms max=500ms
- WAL bytes this window: 3770951848 (3596.3 MB)
- relation sizes (bytes): search_flat=20526841856 search_flat_pkey=15950012416 sf_num=3794960384 sf_text=663478272 sf_bool=91389952 sf_exists=1204019200 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=59293364 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=140317864 n_tup_del=70068912

### Sample #6 (t+180s)

- window 30.0s: 5514 txns, achieved rate 183.7/s; cumulative success=34898 errors=0
- txn latency (this window): p50=24ms p95=98ms max=653ms
- WAL bytes this window: 3804604368 (3628.4 MB)
- relation sizes (bytes): search_flat=20896890880 search_flat_pkey=15951601664 sf_num=3910049792 sf_text=663478272 sf_bool=91389952 sf_exists=1208934400 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=62067902 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=143092402 n_tup_del=72843450

### Sample #7 (t+210s)

- window 30.0s: 5261 txns, achieved rate 175.3/s; cumulative success=40159 errors=0
- txn latency (this window): p50=27ms p95=101ms max=916ms
- WAL bytes this window: 3791583064 (3615.9 MB)
- relation sizes (bytes): search_flat=21250768896 search_flat_pkey=15953068032 sf_num=4024729600 sf_text=663478272 sf_bool=91389952 sf_exists=1225351168 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=64715186 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=145739686 n_tup_del=75490734

### Sample #8 (t+240s)

- window 30.0s: 4683 txns, achieved rate 156.0/s; cumulative success=44842 errors=0
- txn latency (this window): p50=40ms p95=148ms max=1585ms
- WAL bytes this window: 3389760512 (3232.7 MB)
- relation sizes (bytes): search_flat=21566210048 search_flat_pkey=15954092032 sf_num=4129669120 sf_text=663478272 sf_bool=91389952 sf_exists=1241915392 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=67080908 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=148105408 n_tup_del=77856456

### Sample #9 (t+270s)

- window 30.0s: 5911 txns, achieved rate 196.9/s; cumulative success=50753 errors=0
- txn latency (this window): p50=18ms p95=54ms max=259ms
- WAL bytes this window: 5018159216 (4785.7 MB)
- relation sizes (bytes): search_flat=21964120064 search_flat_pkey=15955148800 sf_num=4265484288 sf_text=663478272 sf_bool=91389952 sf_exists=1262436352 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=70040315 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=151064815 n_tup_del=80815863

### Sample #10 (t+300s)

- window 29.7s: 4780 txns, achieved rate 160.9/s; cumulative success=55532 errors=0
- txn latency (this window): p50=32ms p95=137ms max=752ms
- WAL bytes this window: 4021318680 (3835.0 MB)
- relation sizes (bytes): search_flat=22286041088 search_flat_pkey=15955951616 sf_num=4375912448 sf_text=663478272 sf_bool=91389952 sf_exists=1278836736 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69249394 n_dead_tup=72458141 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=153482641 n_tup_del=83233689


### Phase A (v1 blanket) - summary

- total successful txns: 55548
- total errors: 0
- achieved average rate: 185.2/s (target 200.0/s, duration 300s)
- overall txn latency: p50=14ms p95=85ms max=1585ms
- total flat rows deleted: 26959935, inserted: 27829548 (avg 485.3 deleted + 501.0 inserted per txn)

## Phase B - v1.5 anti-join delete-changed/insert-changed (poolB, 2000 things)

### 30s samples

### Sample #1 (t+30s)

- window 30.0s: 5370 txns, achieved rate 178.8/s; cumulative success=5370 errors=0
- txn latency (this window): p50=5ms p95=139ms max=625ms
- WAL bytes this window: 887421768 (846.3 MB)
- relation sizes (bytes): search_flat=22415433728 search_flat_pkey=16130277376 sf_num=4413947904 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70050599 n_dead_tup=72617661 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154443366 n_tup_del=83393209

### Sample #2 (t+60s)

- window 30.0s: 6006 txns, achieved rate 200.0/s; cumulative success=11376 errors=0
- txn latency (this window): p50=3ms p95=5ms max=66ms
- WAL bytes this window: 412280304 (393.2 MB)
- relation sizes (bytes): search_flat=22427082752 search_flat_pkey=16142073856 sf_num=4417708032 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70104574 n_dead_tup=72655221 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154534901 n_tup_del=83430769

### Sample #3 (t+90s)

- window 30.0s: 5999 txns, achieved rate 199.9/s; cumulative success=17375 errors=0
- txn latency (this window): p50=3ms p95=5ms max=197ms
- WAL bytes this window: 186757424 (178.1 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142876672 sf_num=4419870720 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72685696 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154567956 n_tup_del=83461244

### Sample #4 (t+120s)

- window 30.0s: 6002 txns, achieved rate 200.0/s; cumulative success=23377 errors=0
- txn latency (this window): p50=3ms p95=5ms max=49ms
- WAL bytes this window: 373277768 (356.0 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142909440 sf_num=4421828608 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72715791 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154598051 n_tup_del=83491339

### Sample #5 (t+150s)

- window 30.0s: 6003 txns, achieved rate 200.0/s; cumulative success=29380 errors=0
- txn latency (this window): p50=3ms p95=8ms max=32ms
- WAL bytes this window: 204574272 (195.1 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142909440 sf_num=4423680000 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72745756 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154628016 n_tup_del=83521304

### Sample #6 (t+180s)

- window 30.0s: 6001 txns, achieved rate 199.9/s; cumulative success=35381 errors=0
- txn latency (this window): p50=4ms p95=27ms max=112ms
- WAL bytes this window: 733942936 (699.9 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142917632 sf_num=4425310208 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72775521 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154657781 n_tup_del=83551069

### Sample #7 (t+210s)

- window 30.0s: 6002 txns, achieved rate 200.0/s; cumulative success=41383 errors=0
- txn latency (this window): p50=3ms p95=15ms max=174ms
- WAL bytes this window: 840211224 (801.3 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142917632 sf_num=4426874880 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72805501 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154687761 n_tup_del=83581049

### Sample #8 (t+240s)

- window 30.0s: 5955 txns, achieved rate 198.4/s; cumulative success=47338 errors=0
- txn latency (this window): p50=3ms p95=13ms max=241ms
- WAL bytes this window: 629101248 (600.0 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142917632 sf_num=4428783616 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72835226 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154717486 n_tup_del=83610774

### Sample #9 (t+270s)

- window 30.0s: 6001 txns, achieved rate 200.0/s; cumulative success=53339 errors=0
- txn latency (this window): p50=3ms p95=9ms max=72ms
- WAL bytes this window: 521545416 (497.4 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142917632 sf_num=4429938688 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72865391 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154747651 n_tup_del=83640939

### Sample #10 (t+300s)

- window 29.8s: 5967 txns, achieved rate 200.0/s; cumulative success=59306 errors=0
- txn latency (this window): p50=3ms p95=5ms max=34ms
- WAL bytes this window: 349118256 (332.9 MB)
- relation sizes (bytes): search_flat=22428467200 search_flat_pkey=16142917632 sf_num=4429938688 sf_text=663478272 sf_bool=91389952 sf_exists=1282949120 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=70107154 n_dead_tup=72895511 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=154777771 n_tup_del=83671059


### Phase B (v1.5 anti-join) - summary

- total successful txns: 59321
- total errors: 0
- achieved average rate: 197.7/s (target 200.0/s, duration 300s)
- overall txn latency: p50=3ms p95=17ms max=625ms
- total flat rows deleted: 427620, inserted: 1285380 (avg 7.2 deleted + 21.7 inserted per txn)

## Comparison

| metric | v1 blanket (Phase A) | v1.5 anti-join (Phase B) | ratio |
|---|---:|---:|---:|
| txn p50/p95/max ms | 14 / 85 / 1585 | 3 / 17 / 625 | ~4.7x / ~5x / ~2.5x |
| avg flat rows deleted/txn | 485.3 (always ~500, full rewrite) | 7.2 | ~67x fewer |
| avg flat rows inserted/txn | 501.0 (always ~500, full rewrite) | 21.7 | ~23x fewer |
| achieved rate (target 200/s) | 185.2/s | 197.7/s | anti-join sustains closer to target |
| total WAL over the 300s phase (sum of the 10 per-window deltas above) | 34.05 GB | 4.79 GB | **~7.1x less WAL** |
| `search_flat` table growth, sample #1→#10 (bytes, from the relation-size lines above) | 18,952,814,592 → 22,286,041,088 (+3.10 GB) | 22,415,433,728 → 22,428,467,200 (+12.4 MB) | **~256x less table growth** |
| `sf_num` index growth, sample #1→#10 | 3,660,898,304 → 4,375,912,448 (+681.9 MB) | 4,413,947,904 → 4,429,938,688 (+15.2 MB) | **~45x less index growth** |
| `sf_exists` index growth, sample #1→#10 | 1,183,498,240 → 1,278,836,736 (+90.9 MB) | 1,282,949,120 → 1,282,949,120 (+0 — no growth observed) | anti-join: no measurable growth |
| `search_flat` n_dead_tup growth, sample #1→#10 | 47,597,978 → 72,458,141 (+24.86M) | 72,617,661 → 72,895,511 (+278K) | **~89x less dead-tuple growth** |

**Caveat (disclosed):** the twin payload used by this bench (§ this file's Javadoc pointer, `WriteFanoutBench`) is all-numeric (`val_num` only) for hot-loop simplicity, so `sf_text`/`sf_bool`/`sf_trgm`/`sf_trgm_scoped_feature` see ZERO write churn from either phase (both stayed byte-identical throughout, confirmed in the relation-size lines above) — this comparison's index-growth numbers are therefore only representative of the numeric-value index family (`sf_num`, `sf_exists`, `search_flat_pkey`), not the text/trigram indexes. A production workload with mixed value types would additionally churn `sf_text`/`sf_trgm`; the RELATIVE blanket-vs-anti-join advantage is expected to hold (anti-join only rewrites rows whose value actually changed, regardless of type) but this run does not directly measure it for text-valued leaves.

**Recommendation: adopt v1.5 (anti-join delete-changed/insert-changed) over the v1 blanket DELETE+INSERT, once repeat-touch steady state is reached.** Rationale, all from the numbers above (same 300s window, same rate/worker config, disjoint pools, same corpus/DB state):
1. **Write amplification is the dominant cost and anti-join eliminates nearly all of it once a thing has been touched once**: ~67x fewer flat-row deletes and ~23x fewer inserts per transaction (the insert-side ratio is smaller than the delete-side because the still-nonzero first-touch population, diluted at ~1/30 of touches at this pool-size/duration, always inserts the full ~500-row twin with zero prior-row overlap — see the first-touch caveat above; the delete side is less inflated by first-touch because the *replaced* corpus-shaped rows per thing average only ~68.5, not 500).
2. **Anti-join also shows lower latency and closer-to-target throughput per transaction** (p50 3ms vs 14ms, p95 17ms vs 85ms; 197.7/s vs 185.2/s against a 200/s target) under identical worker/rate configuration — **but this specific comparison is confounded, disclosed here rather than hidden**: per Part 1's autovacuum finding, the single autovacuum cycle carried over from Part 1 was still active for the entirety of Phase A, during which `search_flat`'s `n_dead_tup` climbed from ~47.6M to ~72.5M (see the Phase A samples above). That cycle did NOT complete at the Phase A/Phase B boundary: the Phase B samples above (Sample #1-#10) all show `autovacuum_count=1` and `n_dead_tup` static at ~72.6-72.9M throughout — i.e. the same carried-over cycle was still active (not yet counted as complete) for the entirety of Phase B as well, only completing sometime after Part 2 finished entirely (`autovacuum_count` incremented to 2, timestamped 13:09:20 — see `db-state-post-writebench.md`). Phase A and Phase B therefore ran under non-identical, not-fully-characterizable table/vacuum states, and this latency/throughput comparison stays flagged as unclean — it is NOT the case that Phase B started against a freshly-vacuumed table. Worth noting: Phase B's standing dead-tuple level (~72.7M, static) was actually HIGHER than most of Phase A's (which climbed from 47.6M up to 72.5M) — if anything this biases AGAINST v1.5's apparent latency/throughput advantage, which makes the mechanism-metric attribution in points 1 and 3-5 below safer, not weaker. This does NOT affect points 1 and 3-5 below: rows-deleted/inserted-per-txn, WAL bytes, and relation-size deltas are direct mechanism measurements (what each SQL form actually writes), independent of table bloat state.
3. **WAL volume drops ~7x** (4.79 GB vs 34.05 GB over the same 300s) — directly relevant to replication lag, WAL archiving cost, and crash-recovery replay time in production.
4. **Index and table bloat are dramatically smaller**: `search_flat`'s own table growth drops ~256x, `sf_num`'s index growth drops ~45x, and `sf_exists` shows no measurable growth at all in Phase B vs +90.9 MB in Phase A over the identical window — directly addressing the plan's "Flat-table write amplification at high twin-update rates" risk (§5) far more effectively than blanket rewrite plus tuning alone.
5. **Dead-tuple accumulation drops ~89x** (+278K vs +24.86M over 300s) — this is the single biggest factor in how much autovacuum work is needed to keep the table healthy; see Part 1's finding that a single autovacuum worker fell continuously behind under the v1 blanket workload for 15+ minutes straight (Part 1 result file, "autovacuum" note) — the anti-join's much lower dead-tuple production rate directly reduces that risk.
- **Tradeoff acknowledged, not hidden:** v1.5's SQL is materially more complex (two writable CTEs sharing one `MATERIALIZED` unnest, full-row-identity matching via `IS NOT DISTINCT FROM` on every value column) than v1's two-statement DELETE+unnest-INSERT — a real implementation/maintenance cost, and the plan doc's §3.7 already treats leaf-level diffing as a separate, more surgical v2 optimization NOT required by this recommendation (v1.5 stays row-identity-based, no leaf-diff machinery). The first-touch caveat above also means v1.5's advantage is a *steady-state* property, not an every-single-transaction guarantee — cold/rarely-updated things still pay full churn on their first touch under either design.
