# W2 — snapshot write (~4 KB)

- generated: 2026-10-21T20:51:01.799611Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | — | 1.78 \| 3.00 \| 3.13 |
| overwrite (same pid/sn/ts key) | — | 1.35 \| 1.54 \| 1.62 |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).


### Mongo plan (fresh upsert at sn 54; explain writes nothing)

```json
// winningPlan
{
  "stage": "UPDATE",
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
          "[\"thing:bench.w2:mongo\", \"thing:bench.w2:mongo\"]"
        ],
        "sn": [
          "[54, 54]"
        ],
        "ts": [
          "[1767225653000, 1767225653000]"
        ]
      }
    }
  }
}
// executionStats (summary)
{
  "nReturned": 0,
  "executionTimeMillis": 0,
  "totalKeysExamined": 0,
  "totalDocsExamined": 0,
  "nWouldModify": 0,
  "nWouldUpsert": 1
}
```

### Mongo plan (overwrite of the sn 1 key; explain writes nothing)

```json
// winningPlan
{
  "stage": "UPDATE",
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
          "[\"thing:bench.w2:mongo\", \"thing:bench.w2:mongo\"]"
        ],
        "sn": [
          "[1, 1]"
        ],
        "ts": [
          "[1767225600000, 1767225600000]"
        ]
      }
    }
  }
}
// executionStats (summary)
{
  "nReturned": 0,
  "executionTimeMillis": 0,
  "totalKeysExamined": 1,
  "totalDocsExamined": 1,
  "nWouldModify": 1,
  "nWouldUpsert": 0
}
```
