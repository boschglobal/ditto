# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-21T08:30:37.621967Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl2000-pg

## PostgreSQL

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]

### Autovacuum settle

- settle started at 2026-10-21T08:30:37.809Z, timeout 3600 s
- t+0s: live=13371812 dead=11 autovacuum_count=28 | vacuum: none
- settled at t+0s (dead < max(100k, 1% of live))

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 13363125 | 13363178 | 0 | 14 | 1 | 2026-10-21 11:27:05.988499+03 | 2026-10-21 11:13:11.746953+03 |
| things_journal_seq | 250000 | 250001 | 52 | 1 | 1 | 2026-10-21 11:05:04.956295+03 | 2026-10-21 11:13:15.474384+03 |
| things_snaps | 8581 | 8634 | 11 | 14 | 1 | 2026-10-21 11:26:27.278972+03 | 2026-10-21 11:13:15.519516+03 |

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | 0.90 \| 2.13 \| 5.15 | — |
| R2a tail P99 (n=50) | 4.74 \| 7.23 \| 8.28 | — |
| R2a tail OUTLIER (n=23) | 4.46 \| 7.54 \| 10.10 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) (n=23) | 1.23 \| 1.94 \| 1.94 | — |
| R2b full-scan P99 (post-sweep: only the tail remains) (n=50) | 1.22 \| 1.60 \| 1.62 | — |

### Natural sizes (files do NOT shrink after deletes — see note)

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36272.5 MB | 2015.8 MB | 38288.3 MB |
| things_journal_seq | 18.3 MB | 11.9 MB | 30.2 MB |
| things_snaps | 291.2 MB | 2.9 MB | 294.1 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 40.3 MB |
| things_snaps_pkey | 2.9 MB |

### OFFLINE reclamation

- skipped (bench.c4.offlineReclaim=false)

Deletes free space inside data files (reusable) but do not shrink them — the honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the live/dead tuple counts above.
