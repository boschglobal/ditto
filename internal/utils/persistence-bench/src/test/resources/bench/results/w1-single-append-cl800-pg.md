# W1 — single append txn

- generated: 2026-10-21T12:43:42.125982Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl800-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| append (journal + high-water-mark) | 3.80 \| 12.62 \| 17.20 | — |

PG: event INSERT + journal_seq upsert, one transaction (synchronous_commit=on). Mongo: ordered 1-doc bulkWrite, WriteConcern JOURNALED; the plugin's async realtime-collection insert runs fire-and-forget outside the timed path exactly as in ScalaDriverPersistenceJournaller.batchAppend (plan deviation 4). Mongo deleteMany/insert plans are not explainable — timings only.

- PG autovacuum in progress at start: things_journal scanning heap (heap 224928/4641592, index passes 0); at end: things_journal scanning heap (heap 237247/4641592, index passes 0)
