# W2 — snapshot write (~4 KB)

- generated: 2026-10-21T10:14:37.494638Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cldefault-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 2.26 \| 2.53 \| 2.62 | — |
| overwrite (same pid/sn/ts key) | 1.87 \| 3.03 \| 7.45 | — |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).

- PG autovacuum in progress at start: things_journal scanning heap (heap 2389064/4641616, index passes 0); at end: things_journal scanning heap (heap 2390239/4641616, index passes 0)
