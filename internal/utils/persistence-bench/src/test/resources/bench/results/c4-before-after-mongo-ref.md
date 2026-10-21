# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-21T11:29:44.972679Z
- corpus: count=250000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: mongo-ref


## MongoDB

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | — | 1.38 \| 2.49 \| 3.12 |
| R2a tail P99 (n=50) | — | 3.16 \| 5.09 \| 6.13 |
| R2a tail OUTLIER (n=23) | — | 2.23 \| 3.99 \| 4.39 |
| R2b full-scan OUTLIER (post-sweep) (n=23) | — | 1.56 \| 2.00 \| 2.07 |
| R2b full-scan P99 (post-sweep) (n=50) | — | 1.47 \| 1.98 \| 3.57 |

### Natural sizes

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 12943385 | 13617.8 MB | 26509.7 MB | 1740.3 MB |
| things_snaps | 8581 | 50.2 MB | 297.3 MB | 3.9 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |

### OFFLINE reclamation

- skipped (bench.c4.offlineReclaim=false)

Deletes free space inside data files (reusable) but do not shrink them — the honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the live/dead tuple counts above.
