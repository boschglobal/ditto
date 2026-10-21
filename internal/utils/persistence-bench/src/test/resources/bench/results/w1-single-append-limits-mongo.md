# W1 — single append txn

- generated: 2026-10-21T20:50:58.835290Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| append (journal + high-water-mark) | — | 1.35 \| 2.30 \| 3.51 |

PG: event INSERT + journal_seq upsert, one transaction (synchronous_commit=on). Mongo: ordered 1-doc bulkWrite, WriteConcern JOURNALED; the plugin's async realtime-collection insert runs fire-and-forget outside the timed path exactly as in ScalaDriverPersistenceJournaller.batchAppend (plan deviation 4).


### Mongo plan (append)

```
not explainable: journal bulkWrite(InsertOne) and the realtime insertOne (MongoDB's explain command has no insert or compact form) — timed only
```
