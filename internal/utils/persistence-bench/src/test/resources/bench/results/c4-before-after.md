# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-07-13T20:42:38.836684Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

## PostgreSQL

### Autovacuum settle

- t+0s: live=22949490 dead=11324954 autovacuum_count=18
- t+15s: live=22949490 dead=11324954 autovacuum_count=18
- t+30s: live=22949490 dead=11324954 autovacuum_count=18
- t+45s: live=22949490 dead=11324954 autovacuum_count=18
- t+60s: live=22949490 dead=11324954 autovacuum_count=18
- t+75s: live=22949490 dead=11324954 autovacuum_count=18
- t+90s: live=22949490 dead=11324954 autovacuum_count=18
- t+105s: live=22949490 dead=11324954 autovacuum_count=18
- t+120s: live=22949490 dead=11324954 autovacuum_count=18
- t+135s: live=22949490 dead=11324954 autovacuum_count=18
- t+150s: live=22949490 dead=11324954 autovacuum_count=18
- t+165s: live=22949490 dead=11324954 autovacuum_count=18
- t+180s: live=22949490 dead=11324954 autovacuum_count=18
- t+195s: live=22949490 dead=11324954 autovacuum_count=18
- t+210s: live=22949490 dead=11324954 autovacuum_count=18
- t+225s: live=22949490 dead=11324954 autovacuum_count=18
- t+240s: live=22949490 dead=11324954 autovacuum_count=18
- t+255s: live=22949490 dead=11324954 autovacuum_count=18
- t+270s: live=22949490 dead=11324954 autovacuum_count=18
- t+285s: live=22949490 dead=11324954 autovacuum_count=18
- t+300s: live=22949490 dead=11324954 autovacuum_count=18
- t+315s: live=22949490 dead=11324954 autovacuum_count=18
- t+330s: live=22949490 dead=11324954 autovacuum_count=18
- t+345s: live=22949490 dead=11324954 autovacuum_count=18
- t+360s: live=22949490 dead=11324954 autovacuum_count=18
- t+375s: live=22949490 dead=11324954 autovacuum_count=18
- t+390s: live=22949490 dead=11324954 autovacuum_count=18
- t+405s: live=22949490 dead=11324954 autovacuum_count=18
- t+420s: live=22949490 dead=11324954 autovacuum_count=18
- t+435s: live=22949490 dead=11324954 autovacuum_count=18
- t+450s: live=22949490 dead=11324954 autovacuum_count=18
- t+465s: live=22949490 dead=11324954 autovacuum_count=18
- t+480s: live=22949490 dead=11324954 autovacuum_count=18
- t+495s: live=22949490 dead=11324954 autovacuum_count=18
- t+510s: live=22949490 dead=11324954 autovacuum_count=18
- t+525s: live=22949490 dead=11324954 autovacuum_count=18
- t+540s: live=22949490 dead=11324954 autovacuum_count=18
- t+555s: live=22949490 dead=11324954 autovacuum_count=18
- t+570s: live=22949490 dead=11324954 autovacuum_count=18
- t+585s: live=22949490 dead=11324954 autovacuum_count=18
- t+600s: live=22949490 dead=11324954 autovacuum_count=18
- t+615s: live=22949490 dead=11324954 autovacuum_count=18
- t+630s: live=22949490 dead=11324954 autovacuum_count=18
- t+645s: live=22949490 dead=11324954 autovacuum_count=18
- t+660s: live=22949490 dead=11324954 autovacuum_count=18
- t+675s: live=22949490 dead=11324954 autovacuum_count=18
- t+690s: live=22949490 dead=11324954 autovacuum_count=18
- t+705s: live=22949490 dead=11324954 autovacuum_count=18
- t+720s: live=22949490 dead=11324954 autovacuum_count=18
- t+735s: live=22949490 dead=11324954 autovacuum_count=18
- t+750s: live=22949490 dead=11324954 autovacuum_count=18
- t+765s: live=22949490 dead=11324954 autovacuum_count=18
- t+780s: live=22949490 dead=11324954 autovacuum_count=18
- t+795s: live=22949490 dead=11324954 autovacuum_count=18
- t+810s: live=22949490 dead=11324954 autovacuum_count=18
- t+825s: live=22949490 dead=11324954 autovacuum_count=18
- t+840s: live=22949490 dead=11324954 autovacuum_count=18
- t+855s: live=22949490 dead=11324954 autovacuum_count=18
- t+870s: live=22949490 dead=11324954 autovacuum_count=18
- t+885s: live=22949490 dead=11324954 autovacuum_count=18
- t+900s: live=22949490 dead=11324954 autovacuum_count=18
- TIMEOUT waiting for autovacuum — dead tuples still high, note in README

### Post-sweep replay (same pid picks as r2-replay.md)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN | 1.70 \| 2.94 \| 3.72 | — |
| R2a tail P99 | 10.85 \| 16.92 \| 17.26 | — |
| R2a tail OUTLIER | 7.18 \| 12.14 \| 12.27 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) | 5.19 \| 98.45 \| 366.72 | — |

### Natural sizes (files do NOT shrink after deletes — see note)

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36272.7 MB | 2046.5 MB | 38319.2 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.6 MB |
| things_snaps | 291.2 MB | 2.9 MB | 294.1 MB |

| index | size |
|---|---|
| things_journal_pkey | 2001.2 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 44.8 MB |
| things_snaps_lifecycle_idx | 0.0 MB |
| things_snaps_pkey | 2.9 MB |

### OFFLINE reclamation (VACUUM FULL — not part of normal operation)

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 13600.8 MB | 760.8 MB | 14361.6 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.6 MB |
| things_snaps | 58.8 MB | 0.6 MB | 59.3 MB |

| index | size |
|---|---|
| things_journal_pkey | 745.6 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 15.2 MB |
| things_snaps_lifecycle_idx | 0.0 MB |
| things_snaps_pkey | 0.6 MB |

## MongoDB

### Post-sweep replay (same pid picks)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN | — | 3.65 \| 6.56 \| 8.25 |
| R2a tail P99 | — | 6.98 \| 13.18 \| 14.32 |
| R2a tail OUTLIER | — | 6.02 \| 9.65 \| 10.97 |
| R2b full-scan OUTLIER (post-sweep) | — | 3.33 \| 6.74 \| 7.06 |

### Natural sizes

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 13243360 | 13856.3 MB | 26492.1 MB | 1729.1 MB |
| things_snaps | 8582 | 50.2 MB | 275.0 MB | 4.1 MB |
| things_realtime | 125774 | 100.0 MB | 14.1 MB | 2.8 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |

### OFFLINE reclamation (compact — blocks, standalone-only convenience)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 13243360 | 13856.3 MB | 10985.9 MB | 874.4 MB |
| things_snaps | 8582 | 50.2 MB | 71.4 MB | 3.2 MB |
| things_realtime | 125774 | 100.0 MB | 14.1 MB | 2.8 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |

Deletes free space inside data files (reusable) but do not shrink them — the honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the live/dead tuple counts above.
