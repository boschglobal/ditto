# W2 — snapshot write (~4 KB)

- generated: 2026-07-13T19:59:52.594029Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 2.11 \| 2.96 \| 3.05 | 4.26 \| 4.79 \| 4.93 |
| overwrite (same pid/sn/ts key) | 2.03 \| 2.33 \| 2.44 | 3.46 \| 4.25 \| 4.79 |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).
