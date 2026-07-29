# C2 — single-pid cleanup (batched deletes, batch size 100)

- generated: 2026-07-13T20:20:50.639518Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| P99 total per-pid | 59.33 \| 70.00 \| 130.08 | 48.28 \| 88.51 \| 104.07 |
| P99 per delete batch (100 rows) | 1.21 \| 6.72 \| 52.94 | 1.44 \| 2.82 \| 22.19 |
| OUTLIER total per-pid | 875.58 \| 993.31 \| 1126.50 | 881.56 \| 1162.56 \| 1187.11 |
| OUTLIER per delete batch (100 rows) | 1.13 \| 1.98 \| 43.28 | 1.04 \| 2.42 \| 51.39 |

- P99: 23 pids found (3 warmup + 20 timed)
- OUTLIER: 23 pids found (3 warmup + 20 timed)

Sequence per pid (Cleanup.java 1:1): min event sn -> event ranges tiling to snapshotSn-1 -> min snapshot sn -> snapshot ranges keeping the latest. No journal_seq.deleted_to update — the real cleanup path does not touch it (plan deviation 2). Mongo deleteMany has no executionStats — timings only. C2 consumes these pids' cleanup backlog; rerun 'load' for a pristine corpus.
