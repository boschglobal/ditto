# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-23T01:39:21.299716Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cl800

## PostgreSQL

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]

### Autovacuum settle

- settle started at 2026-10-23T01:39:21.635Z, timeout 7200 s
- t+0s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal scanning heap (heap 4070887/4641592, index passes 0) | waits: DataFileRead 6, VacuumDelay 4, WALSync 3, CPU 1, WALWrite 1
- t+15s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal scanning heap (heap 4242138/4641592, index passes 0) | waits: VacuumDelay 7, WALSync 7, DataFileRead 1
- t+30s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal scanning heap (heap 4409086/4641592, index passes 0) | waits: WALSync 9, VacuumDelay 4, DataFileRead 2
- t+45s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal scanning heap (heap 4615883/4641592, index passes 0) | waits: DataFileRead 6, VacuumDelay 6, WALSync 3
- t+60s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileRead 1
- t+75s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 6, DataFileRead 4, WALSync 4, DataFileWrite 1
- t+90s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 8, DataFileRead 4, VacuumDelay 3
- t+106s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 6, VacuumDelay 5, CPU 3, DataFileRead 1
- t+121s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 5, DataFileRead 2
- t+136s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, CPU 1
- t+151s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 6, VacuumDelay 5, CPU 1, DataFileRead 1, DataFileWrite 1, WALWrite 1
- t+166s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 6, WALSync 6, DataFileRead 3
- t+181s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 9, VacuumDelay 5, DataFileWrite 1
- t+196s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 7, VacuumDelay 5, DataFileRead 2, CPU 1
- t+212s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 7, VacuumDelay 5, DataFileRead 2, DataFileWrite 1
- t+227s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 10, VacuumDelay 3, DataFileRead 2
- t+242s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 7, VacuumDelay 5, DataFileRead 2, WALWrite 1
- t+257s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 5, CPU 2
- t+272s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 11, VacuumDelay 3, DataFileRead 1
- t+287s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 5, DataFileRead 2
- t+303s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+318s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 7, WALSync 7, DataFileRead 1
- t+333s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 5, DataFileWrite 1, WALWrite 1
- t+348s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 9, VacuumDelay 4, DataFileRead 1, DataFileWrite 1
- t+363s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 11, VacuumDelay 3, CPU 1
- t+378s: live=16371749 dead=5730640 autovacuum_count=20 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: DataFilePrefetch 3, VacuumDelay 2, CPU 1
- t+393s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: none | waits: no autovacuum worker
- t+409s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: none | waits: no autovacuum worker
- t+424s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: none | waits: no autovacuum worker
- t+439s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: none | waits: VacuumDelay 6, DataFileRead 4, WALSync 2, CPU 1
- t+454s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: things_journal scanning heap (heap 1114087/4641592, index passes 0) | waits: VacuumDelay 9, DataFileRead 4, CPU 1, WALSync 1
- t+469s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: things_journal scanning heap (heap 2122487/4641592, index passes 0) | waits: VacuumDelay 11, DataFileRead 3, CPU 1
- t+484s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: things_journal scanning heap (heap 3216603/4641592, index passes 0) | waits: VacuumDelay 11, CPU 2, DataFileRead 2
- t+500s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) | waits: DataFileRead 9, VacuumDelay 4, CPU 1, WALSync 1
- t+515s: live=13199265 dead=2560457 autovacuum_count=21 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 6, VacuumDelay 5, CPU 1, DataFileRead 1
- t+530s: live=12281633 dead=0 autovacuum_count=22 | vacuum: none
- settled at t+530s (dead < max(100k, 1% of live))

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 12272999 | 12272999 | 0 | 6 | 1 | 2026-10-23 04:48:09.81401+03 | 2026-10-23 04:20:48.740716+03 |
| things_journal_seq | 250000 | 250001 | 53 | 1 | 1 | 2026-10-23 04:03:28.905638+03 | 2026-10-23 04:20:51.707978+03 |
| things_snaps | 8634 | 8634 | 0 | 16 | 1 | 2026-10-23 04:37:42.857868+03 | 2026-10-23 04:20:51.734994+03 |

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | 1.27 \| 2.65 \| 5.16 | — |
| R2a tail P99 (n=50) | 5.26 \| 12.03 \| 14.38 | — |
| R2a tail OUTLIER (n=23) | 5.28 \| 12.12 \| 12.15 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) (n=23) | 1.22 \| 2.02 \| 2.09 | — |
| R2b full-scan P99 (post-sweep: only the tail remains) (n=50) | 1.31 \| 1.89 \| 2.02 | — |

### PG plan (R2b full-scan replay of an untimed P99 pid, its first read after the sweep)

```
Index Scan using things_journal_pkey on things_journal  (cost=0.56..4079.88 rows=995 width=157) (actual time=0.393..3.901 rows=234 loops=1)
  Index Cond: ((pid = 'thing:bench.ns01:pid-00106368'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
  Buffers: shared hit=5 read=37
Planning:
  Buffers: shared hit=3
Planning Time: 0.030 ms
Execution Time: 3.910 ms
```

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
