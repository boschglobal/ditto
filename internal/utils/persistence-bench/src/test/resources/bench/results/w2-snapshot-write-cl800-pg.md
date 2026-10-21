# W2 — snapshot write (~4 KB)

- generated: 2026-10-21T12:43:45.380332Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl800-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 0.82 \| 1.06 \| 9.39 | — |
| overwrite (same pid/sn/ts key) | 3.73 \| 32.77 \| 44.74 | — |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).

- PG autovacuum in progress at start: things_journal scanning heap (heap 313467/4641592, index passes 0); at end: things_journal scanning heap (heap 314540/4641592, index passes 0)
