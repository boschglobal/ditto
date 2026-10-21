# W2 — snapshot write (~4 KB)

- generated: 2026-10-21T08:28:29.207718Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl2000-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 2.14 \| 2.40 \| 2.47 | — |
| overwrite (same pid/sn/ts key) | 1.36 \| 1.49 \| 1.75 | — |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).

- PG autovacuum in progress at start: none; at end: none
