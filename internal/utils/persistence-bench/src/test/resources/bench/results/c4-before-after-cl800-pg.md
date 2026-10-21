# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-21T12:45:57.438183Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl800-pg

## PostgreSQL

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]

### Autovacuum settle

- settle started at 2026-10-21T12:45:57.664Z, timeout 3600 s
- t+0s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) | waits: VacuumDelay 8, WALSync 5, DataFileRead 2
- t+15s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 7, VacuumDelay 6, DataFileRead 2
- t+30s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+45s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 10, VacuumDelay 4, CPU 1
- t+60s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 4, DataFileRead 1, DataFileWrite 1
- t+75s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+90s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+105s: live=13019688 dead=5847024 autovacuum_count=23 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 6, WALSync 3, DataFileRead 1
- t+120s: live=11213597 dead=11 autovacuum_count=24 | vacuum: none
- settled at t+120s (dead < max(100k, 1% of live))

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 11204963 | 11204963 | 0 | 9 | 1 | 2026-10-21 15:47:53.231398+03 | 2026-10-21 15:24:59.654714+03 |
| things_journal_seq | 250000 | 250001 | 52 | 1 | 1 | 2026-10-21 15:16:35.989344+03 | 2026-10-21 15:25:03.193799+03 |
| things_snaps | 8581 | 8634 | 11 | 15 | 1 | 2026-10-21 15:41:36.266129+03 | 2026-10-21 15:25:03.231964+03 |

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | 1.01 \| 1.72 \| 17.52 | — |
| R2a tail P99 (n=50) | 4.51 \| 8.16 \| 8.55 | — |
| R2a tail OUTLIER (n=23) | 3.92 \| 6.77 \| 8.58 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) (n=23) | 1.16 \| 1.69 \| 1.69 | — |
| R2b full-scan P99 (post-sweep: only the tail remains) (n=50) | 1.16 \| 1.59 \| 1.68 | — |

### Natural sizes (files do NOT shrink after deletes — see note)

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36272.5 MB | 2015.7 MB | 38288.2 MB |
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
