# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-22T23:07:51.117692Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cl2000

## PostgreSQL

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]

### Autovacuum settle

- settle started at 2026-10-22T23:07:51.400Z, timeout 7200 s
- t+0s: live=12666236 dead=0 autovacuum_count=26 | vacuum: none
- settled at t+0s (dead < max(100k, 1% of live))

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 12657267 | 12657267 | 0 | 10 | 1 | 2026-10-23 02:06:48.911902+03 | 2026-10-23 01:49:17.876952+03 |
| things_journal_seq | 250000 | 250001 | 53 | 1 | 1 | 2026-10-23 00:01:34.984092+03 | 2026-10-23 01:49:22.346318+03 |
| things_snaps | 8969 | 8969 | 0 | 16 | 1 | 2026-10-23 02:06:13.12817+03 | 2026-10-23 01:49:22.371949+03 |

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | 1.12 \| 2.11 \| 3.06 | — |
| R2a tail P99 (n=50) | 5.02 \| 12.81 \| 14.85 | — |
| R2a tail OUTLIER (n=23) | 4.77 \| 8.27 \| 9.21 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) (n=23) | 1.45 \| 2.16 \| 2.44 | — |
| R2b full-scan P99 (post-sweep: only the tail remains) (n=50) | 1.38 \| 1.85 \| 1.92 | — |

### PG plan (R2b full-scan replay of an untimed P99 pid, its first read after the sweep)

```
Index Scan using things_journal_pkey on things_journal  (cost=0.56..4288.40 rows=1047 width=156) (actual time=0.501..4.360 rows=234 loops=1)
  Index Cond: ((pid = 'thing:bench.ns01:pid-00106368'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
  Buffers: shared hit=5 read=37
Planning:
  Buffers: shared hit=3
Planning Time: 0.030 ms
Execution Time: 4.368 ms
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
