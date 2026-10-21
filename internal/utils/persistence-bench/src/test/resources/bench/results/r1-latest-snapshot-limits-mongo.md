# R1 — latest-snapshot fetch

- generated: 2026-10-21T20:48:43.539612Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| MEDIAN (no snapshot) | — | 0.94 \| 1.25 \| 1.29 |
| P99 | — | 0.73 \| 0.80 \| 0.81 |
| OUTLIER | — | 0.69 \| 0.76 \| 0.82 |


### Mongo plan (P99 pid)

```json
// winningPlan
{
  "stage": "LIMIT",
  "limitAmount": 1,
  "inputStage": {
    "stage": "FETCH",
    "inputStage": {
      "stage": "IXSCAN",
      "keyPattern": {
        "pid": 1,
        "sn": -1,
        "ts": -1
      },
      "indexName": "things_snaps_index",
      "isMultiKey": false,
      "multiKeyPaths": {
        "pid": [],
        "sn": [],
        "ts": []
      },
      "isUnique": true,
      "isSparse": false,
      "isPartial": false,
      "indexVersion": 2,
      "direction": "forward",
      "indexBounds": {
        "pid": [
          "[\"thing:bench.ns15:pid-00002838\", \"thing:bench.ns15:pid-00002838\"]"
        ],
        "sn": [
          "[9223372036854775807, -inf.0]"
        ],
        "ts": [
          "[9223372036854775807, -inf.0]"
        ]
      }
    }
  }
}
// executionStats (summary)
{
  "nReturned": 1,
  "executionTimeMillis": 0,
  "totalKeysExamined": 1,
  "totalDocsExamined": 1
}
```
