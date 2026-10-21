# R3 — highest sequence number

- generated: 2026-10-21T20:48:49.528510Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| mixed classes | — | 0.33 \| 0.64 \| 0.67 |


### Mongo plan

```json
// winningPlan
{
  "stage": "LIMIT",
  "limitAmount": 1,
  "inputStage": {
    "stage": "PROJECTION_SIMPLE",
    "transformBy": {
      "to": 1
    },
    "inputStage": {
      "stage": "FETCH",
      "inputStage": {
        "stage": "IXSCAN",
        "keyPattern": {
          "pid": 1,
          "to": -1
        },
        "indexName": "max_sequence_sort",
        "isMultiKey": false,
        "multiKeyPaths": {
          "pid": [],
          "to": []
        },
        "isUnique": false,
        "isSparse": false,
        "isPartial": false,
        "indexVersion": 2,
        "direction": "forward",
        "indexBounds": {
          "pid": [
            "[\"thing:bench.ns15:pid-00002838\", \"thing:bench.ns15:pid-00002838\"]"
          ],
          "to": [
            "[MaxKey, MinKey]"
          ]
        }
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
