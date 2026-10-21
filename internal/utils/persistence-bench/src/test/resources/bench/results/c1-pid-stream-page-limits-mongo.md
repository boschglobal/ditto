# C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)

- generated: 2026-10-21T21:05:51.768035Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| one page, varying lower bound | — | 1.06 \| 1.66 \| 1.89 |


### Mongo plan (mid-corpus lower bound)

```json
// winningPlan
{
  "stage": "LIMIT",
  "limitAmount": 100,
  "inputStage": {
    "stage": "PROJECTION_DEFAULT",
    "transformBy": {
      "pid": 1,
      "s2.__lifecycle": 1,
      "sn": 1,
      "_id": 0
    },
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
            "(\"thing:bench.ns02:pid-00122616\", {})"
          ],
          "sn": [
            "[MaxKey, MinKey]"
          ],
          "ts": [
            "[MaxKey, MinKey]"
          ]
        }
      }
    }
  }
}
// executionStats (summary)
{
  "nReturned": 100,
  "executionTimeMillis": 0,
  "totalKeysExamined": 100,
  "totalDocsExamined": 100
}
```

Cleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), minAge=0 (the bench's history-retention pin: age predicates disabled; production things cleanup passes history-retention-duration, default 3d — see the C1 retention variant), pidFilter='' — what Cleanup.java passes with that pin.
