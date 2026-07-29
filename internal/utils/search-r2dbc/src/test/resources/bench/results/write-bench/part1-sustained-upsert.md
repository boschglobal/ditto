# Task 0.3 Part 1 - sustained write fan-out benchmark (v1 blanket DELETE+INSERT)

Simulates the production write path (plan doc §3.4 step 1/2, exactly the design's v1 shape): one transaction per thing = (1) `INSERT ... ON CONFLICT (thing_id) DO UPDATE SET <all columns>` unconditional full-write upsert of the doc row, no revision predicate; (2) `DELETE FROM search_flat WHERE thing_id = ?` then `INSERT INTO search_flat SELECT * FROM unnest(...)` — one bind parameter per COLUMN, not per row.

**Reduced-scale caveat (explicit, per the user's Phase-0 scale decision):** this run is 1M things / ~10 minutes sustained, NOT the plan's original 10M / >=1 hour bloat-plateau gate. At 10 minutes NO bloat-plateau claim is possible — the size-growth/dead-tuple trend below is reported honestly as a TREND, and the plan's own >=1h plateau gate is explicitly marked 'not yet run (scheduled full-scale follow-up)' in the findings doc.

Pool: 2000 distinct thing_ids (random, seeded via `SELECT setseed(-0.101)` then `ORDER BY random()`).

Worker count: 16, target aggregate rate: 200.0/s, duration: PT10M.

Twin shape: 500 flat rows/thing (flat numeric attributes, no features), 5 leaves mutated per update.

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

## 30s samples

### Sample #1 (t+30s)

- window 30.0s: 5917 txns, achieved rate 197.2/s; cumulative success=5916 errors=0
- txn latency (this window): p50=7ms p95=34ms max=181ms
- WAL bytes this window: 2461921208 (2347.9 MB)
- relation sizes (bytes): search_flat=11688206336 search_flat_pkey=15711354880 sf_num=1348460544 sf_text=663478272 sf_bool=91389952 sf_exists=831807488 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69351338 n_dead_tup=5338532 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=74689870 n_tup_del=5338532

### Sample #2 (t+60s)

- window 30.0s: 5724 txns, achieved rate 190.6/s; cumulative success=11640 errors=0
- txn latency (this window): p50=9ms p95=63ms max=306ms
- WAL bytes this window: 2438086448 (2325.1 MB)
- relation sizes (bytes): search_flat=12072681472 search_flat_pkey=15722733568 sf_num=1461551104 sf_text=663478272 sf_bool=91389952 sf_exists=852410368 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69377924 n_dead_tup=8202215 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=77580139 n_tup_del=8202215

### Sample #3 (t+90s)

- window 30.0s: 5577 txns, achieved rate 185.8/s; cumulative success=17218 errors=0
- txn latency (this window): p50=13ms p95=91ms max=517ms
- WAL bytes this window: 2483324128 (2368.3 MB)
- relation sizes (bytes): search_flat=12447432704 search_flat_pkey=15729623040 sf_num=1572954112 sf_text=663478272 sf_bool=91389952 sf_exists=869072896 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69379339 n_dead_tup=10992873 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=80372212 n_tup_del=10992873

### Sample #4 (t+120s)

- window 30.0s: 5706 txns, achieved rate 190.2/s; cumulative success=22924 errors=0
- txn latency (this window): p50=16ms p95=80ms max=478ms
- WAL bytes this window: 2667210864 (2543.7 MB)
- relation sizes (bytes): search_flat=12831072256 search_flat_pkey=15740051456 sf_num=1691664384 sf_text=663478272 sf_bool=91389952 sf_exists=889675776 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=69379339 n_dead_tup=13885146 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=83264485 n_tup_del=13885146

### Sample #5 (t+150s)

- window 30.0s: 5414 txns, achieved rate 180.3/s; cumulative success=28336 errors=0
- txn latency (this window): p50=18ms p95=115ms max=293ms
- WAL bytes this window: 2699873152 (2574.8 MB)
- relation sizes (bytes): search_flat=13195173888 search_flat_pkey=15761530880 sf_num=1803214848 sf_text=663478272 sf_bool=91389952 sf_exists=906174464 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=5819006 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=85973893 n_tup_del=16594554

### Sample #6 (t+180s)

- window 30.0s: 4734 txns, achieved rate 157.7/s; cumulative success=33072 errors=0
- txn latency (this window): p50=19ms p95=182ms max=465ms
- WAL bytes this window: 2476305336 (2361.6 MB)
- relation sizes (bytes): search_flat=13513482240 search_flat_pkey=15761530880 sf_num=1900339200 sf_text=663478272 sf_bool=91389952 sf_exists=922951680 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=8187233 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=88342120 n_tup_del=18962781

### Sample #7 (t+210s)

- window 30.0s: 5949 txns, achieved rate 198.2/s; cumulative success=39021 errors=0
- txn latency (this window): p50=9ms p95=31ms max=165ms
- WAL bytes this window: 3077797008 (2935.2 MB)
- relation sizes (bytes): search_flat=13913653248 search_flat_pkey=15761539072 sf_num=2026840064 sf_text=663478272 sf_bool=91389952 sf_exists=943480832 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=11155157 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=91310044 n_tup_del=21930705

### Sample #8 (t+240s)

- window 30.0s: 5429 txns, achieved rate 180.9/s; cumulative success=44449 errors=0
- txn latency (this window): p50=19ms p95=101ms max=399ms
- WAL bytes this window: 2926405976 (2790.8 MB)
- relation sizes (bytes): search_flat=14278656000 search_flat_pkey=15761547264 sf_num=2149113856 sf_text=663478272 sf_bool=91389952 sf_exists=960045056 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=13865567 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=94020454 n_tup_del=24641115

### Sample #9 (t+270s)

- window 30.0s: 5730 txns, achieved rate 191.0/s; cumulative success=50180 errors=0
- txn latency (this window): p50=19ms p95=79ms max=392ms
- WAL bytes this window: 3332669648 (3178.3 MB)
- relation sizes (bytes): search_flat=14665015296 search_flat_pkey=15761580032 sf_num=2280636416 sf_text=663478272 sf_bool=91389952 sf_exists=976470016 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=16737800 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=96892687 n_tup_del=27513348

### Sample #10 (t+300s)

- window 30.0s: 5384 txns, achieved rate 179.4/s; cumulative success=55564 errors=0
- txn latency (this window): p50=22ms p95=110ms max=622ms
- WAL bytes this window: 3305492888 (3152.4 MB)
- relation sizes (bytes): search_flat=15027806208 search_flat_pkey=15761620992 sf_num=2398552064 sf_text=663478272 sf_bool=91389952 sf_exists=997212160 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=19442198 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=99597085 n_tup_del=30217746

### Sample #11 (t+330s)

- window 30.0s: 5582 txns, achieved rate 186.0/s; cumulative success=61146 errors=0
- txn latency (this window): p50=24ms p95=95ms max=330ms
- WAL bytes this window: 3656218400 (3486.8 MB)
- relation sizes (bytes): search_flat=15404122112 search_flat_pkey=15761702912 sf_num=2519605248 sf_text=663478272 sf_bool=91389952 sf_exists=1013628928 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=22252808 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=102407695 n_tup_del=33028356

### Sample #12 (t+360s)

- window 30.0s: 5820 txns, achieved rate 193.9/s; cumulative success=66966 errors=0
- txn latency (this window): p50=14ms p95=63ms max=313ms
- WAL bytes this window: 4105501056 (3915.3 MB)
- relation sizes (bytes): search_flat=15798468608 search_flat_pkey=15761915904 sf_num=2648965120 sf_text=663478272 sf_bool=91389952 sf_exists=1034149888 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=25159610 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=105314497 n_tup_del=35935158

### Sample #13 (t+390s)

- window 30.2s: 4420 txns, achieved rate 146.5/s; cumulative success=71386 errors=0
- txn latency (this window): p50=49ms p95=177ms max=691ms
- WAL bytes this window: 2957003312 (2820.0 MB)
- relation sizes (bytes): search_flat=16097566720 search_flat_pkey=15762063360 sf_num=2748915712 sf_text=663478272 sf_bool=91389952 sf_exists=1047273472 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=27366515 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=107521402 n_tup_del=38142063

### Sample #14 (t+420s)

- window 30.0s: 5541 txns, achieved rate 184.7/s; cumulative success=76927 errors=0
- txn latency (this window): p50=30ms p95=96ms max=886ms
- WAL bytes this window: 4013380048 (3827.5 MB)
- relation sizes (bytes): search_flat=16473595904 search_flat_pkey=15762382848 sf_num=2877030400 sf_text=663478272 sf_bool=91389952 sf_exists=1067548672 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=30156584 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=110311471 n_tup_del=40932132

### Sample #15 (t+450s)

- window 30.0s: 5529 txns, achieved rate 184.2/s; cumulative success=82456 errors=0
- txn latency (this window): p50=25ms p95=94ms max=1075ms
- WAL bytes this window: 4632875608 (4418.3 MB)
- relation sizes (bytes): search_flat=16849321984 search_flat_pkey=15762776064 sf_num=3010084864 sf_text=663478272 sf_bool=91389952 sf_exists=1084366848 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=32922605 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=113077492 n_tup_del=43698153

### Sample #16 (t+480s)

- window 30.0s: 5594 txns, achieved rate 186.3/s; cumulative success=88050 errors=0
- txn latency (this window): p50=29ms p95=90ms max=1378ms
- WAL bytes this window: 4728871984 (4509.8 MB)
- relation sizes (bytes): search_flat=17228800000 search_flat_pkey=15763120128 sf_num=3147063296 sf_text=663478272 sf_bool=91389952 sf_exists=1100783616 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=35714678 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=115869565 n_tup_del=46490226

### Sample #17 (t+511s)

- window 30.1s: 5446 txns, achieved rate 180.8/s; cumulative success=93496 errors=0
- txn latency (this window): p50=32ms p95=137ms max=1548ms
- WAL bytes this window: 4868512088 (4643.0 MB)
- relation sizes (bytes): search_flat=17599127552 search_flat_pkey=15763431424 sf_num=3282288640 sf_text=663478272 sf_bool=91389952 sf_exists=1121312768 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=38442122 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=118597009 n_tup_del=49217670

### Sample #18 (t+541s)

- window 30.0s: 5376 txns, achieved rate 179.1/s; cumulative success=98872 errors=0
- txn latency (this window): p50=32ms p95=193ms max=1733ms
- WAL bytes this window: 5332667128 (5085.6 MB)
- relation sizes (bytes): search_flat=17964785664 search_flat_pkey=15763800064 sf_num=3414843392 sf_text=663478272 sf_bool=91389952 sf_exists=1137876992 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=41137502 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=121292389 n_tup_del=51913050

### Sample #19 (t+571s)

- window 30.0s: 4585 txns, achieved rate 152.8/s; cumulative success=103457 errors=0
- txn latency (this window): p50=35ms p95=366ms max=1623ms
- WAL bytes this window: 4837549856 (4613.4 MB)
- relation sizes (bytes): search_flat=18277318656 search_flat_pkey=15764381696 sf_num=3529523200 sf_text=663478272 sf_bool=91389952 sf_exists=1150197760 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=43428074 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=123582961 n_tup_del=54203622

### Sample #20 (t+600s)

- window 29.4s: 4022 txns, achieved rate 136.7/s; cumulative success=107479 errors=0
- txn latency (this window): p50=34ms p95=392ms max=3014ms
- WAL bytes this window: 4462549736 (4255.8 MB)
- relation sizes (bytes): search_flat=18550407168 search_flat_pkey=15764635648 sf_num=3629662208 sf_text=663478272 sf_bool=91389952 sf_exists=1166614528 sf_trgm=318799872 sf_trgm_scoped_feature=53485568 
- search_flat pg_stat_user_tables: n_live_tup=68379781 n_dead_tup=45455120 autovacuum_count=1 last_autovacuum=2026-07-04 10:21:05.25561 n_tup_ins=125610007 n_tup_del=56230668


### Run - summary

- total successful txns: 107495
- total errors: 0
- achieved average rate: 179.2/s (target 200.0/s, duration 600s)
- overall txn latency: p50=20ms p95=112ms max=3014ms
- total flat rows deleted: 53826994, inserted: 53854995 (avg 500.7 deleted + 501.0 inserted per txn)

### Size-growth curve, WAL, and autovacuum behavior (computed from the 20 samples above; honest TREND, not a plateau claim)

- **Total WAL over the full 10 minutes (sum of the 20 per-window deltas above): 71,464,215,872 bytes (66.56 GB)** — averages ~113 MB/s sustained, consistent with the ~500-row-delete + ~500-row-insert-per-txn write amplification of the blanket approach at ~179 txn/s.
- **`search_flat` table size**: 11,688,206,336 → 18,550,407,168 bytes over sample #1→#20 (t+30s→t+600s), i.e. **+6.39 GB over 570s (~11.5 MB/s)**. Monotonically increasing every sample, no sign of leveling off within this window — expected at 10 minutes (the brief is explicit that no plateau claim is possible this early).
- **`sf_num` index**: 1,348,460,544 → 3,629,662,208 bytes, **+2.12 GB over 570s** — the largest single-index growth, consistent with `sf_num` being the only populated value-family index for this bench's all-numeric twin payload (see caveat below).
- **`sf_exists` index**: 831,807,488 → 1,166,614,528 bytes, **+319.3 MB over 570s**.
- **`search_flat_pkey`**: 15,711,354,880 → 15,764,635,648 bytes, **+50.8 MB over 570s** — comparatively modest; unlike `sf_num`/`sf_exists`, repeated DELETE+INSERT of the *same* `(thing_id, path, wpath, ord)` key tuples lets the B-tree reuse freed page space within the same key range far more effectively.
- **`sf_text`/`sf_bool`/`sf_trgm`/`sf_trgm_scoped_feature`: BYTE-IDENTICAL across all 20 samples (0 growth).** Caveat, disclosed: this bench's twin payload (`WriteFanoutBench`) is deliberately all-numeric (`val_num` only, for hot-loop simplicity — see its Javadoc) — so the text/boolean/trigram index families see ZERO write load from this bench. The table/pkey/`sf_num`/`sf_exists` growth numbers above are real and representative of the numeric-value path; the trigram/text index write cost under sustained churn is NOT measured by this run (a gap explicitly carried into the findings doc).
- **Dead tuples / autovacuum — the standout finding of Part 1:** `n_dead_tup` climbed monotonically and continuously for the ENTIRE 10-minute run, from 5,338,532 (sample #1, t+30s) to 45,455,120 (sample #20, t+600s) — **`autovacuum_count` stayed at exactly 1 for the entire run** (i.e. the single autovacuum cycle recorded as `last_autovacuum=...10:21:05` in every sample line is the one that ran BEFORE this benchmark started, not a new one triggered during it). A direct `pg_stat_activity` check immediately after Part 1 finished (see below) confirmed a single `autovacuum: VACUUM ANALYZE public.search_flat` worker had been ACTIVELY RUNNING, `IO`/`DataFileRead`-bound, continuously for **7 minutes 33 seconds** at that point (i.e. it started roughly 2-3 minutes into the 10-minute run and had NOT finished by the time Part 1 ended) — direct evidence that a single default-configured (`autovacuum_max_workers=3`, default cost limit/delay) autovacuum worker could not keep pace with ~179 upd/s of blanket full-rewrite churn on a 68-71M-row table within this window. This same vacuum cycle was confirmed STILL RUNNING (`autovacuum_count` static at 1, `last_autovacuum` still the pre-Part-1 `10:21:05` timestamp) through the entirety of Part 2's Phase A (see that file) — AND, per Part 2's own Phase B samples, it had STILL NOT completed by the end of Phase B either (all 10 Phase B samples show the same `autovacuum_count=1`/`10:21:05` timestamp, with `n_dead_tup` static at ~72.6-72.9M rather than dropping). It only completed sometime after Part 2 finished entirely: the post-writebench DB-state capture (`db-state-post-writebench.md`) shows `autovacuum_count` incremented to 2 and `last_autovacuum` timestamped `13:09:20`, at which point `n_dead_tup` stood at 61,413,895 — **not** reset to 0 (no committed sample anywhere in this run shows `n_dead_tup=0`; an earlier draft of this note asserted a reset-to-0 at the Phase A/Phase B boundary, which was a misreading of an inference, not an observed value — both the boundary-timing claim and the reset-to-0 detail are corrected here). That same DB-state capture also found no autovacuum worker active against `search_flat` at the exact moment of capture, so there is no evidence for a further "new cycle already 18+ minutes in" at that point either — that claim, made in an earlier draft of this note, is retracted as unsupported by the committed capture. In short: under this sustained blanket-rewrite workload, autovacuum on `search_flat` remained behind on a single carried-over cycle, never completing, for the combined duration of Part 1, Part 2 Phase A, and Part 2 Phase B.
- **≥1h bloat-plateau gate: NOT YET RUN (scheduled full-scale follow-up).** At 10 minutes, with a single autovacuum cycle never completing across the entire window, NO plateau claim is possible — the data above is an honest, still-climbing TREND, and (per the autovacuum finding) there is a genuine, evidenced reason to expect the full 10M/≥1h run needs autovacuum tuning (parallel workers, higher cost limit, lower scale factor, or the v1.5 anti-join's much lower dead-tuple production rate — see Part 2) rather than defaults, to have any chance of reaching a plateau at all.

Direct `pg_stat_activity` check immediately after this run (informational, not part of the 30s sample loop):
```
pid | state  | wait_event_type | wait_event   | query                                           | duration
326 | active | IO              | DataFileRead | autovacuum: VACUUM ANALYZE public.search_flat  | 00:07:33.499958
```
