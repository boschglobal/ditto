# C3 — full cleanup sweep

- generated: 2026-10-21T22:14:55.792286Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

## MongoDB

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 297.6 s (74460 rows/s), finished at 2026-10-21T22:19:53.647452Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 873 | 2084307 | 29.1 | 69477 | — | 28210.5 MB | 292.4 MB | — |
| 1 | 1834 | 4560626 | 32.0 | 82544 | — | 28211.7 MB | 292.4 MB | — |
| 2 | 2741 | 6879056 | 30.2 | 77281 | — | 28230.5 MB | 303.1 MB | — |
| 3 | 3623 | 9159048 | 29.4 | 76000 | — | 28230.5 MB | 303.1 MB | — |
| 4 | 4450 | 11450470 | 27.6 | 76381 | — | 28229.9 MB | 303.1 MB | — |
| 5 | 5363 | 13726792 | 30.4 | 75877 | — | 28229.9 MB | 303.1 MB | — |
| 6 | 6105 | 15828205 | 24.7 | 70047 | — | 28229.4 MB | 302.9 MB | — |
| 7 | 6919 | 18059535 | 27.1 | 74378 | — | 28229.4 MB | 302.9 MB | — |
| 8 | 7760 | 20237403 | 28.0 | 72596 | — | 28229.4 MB | 302.9 MB | — |

### Mongo plan (first page of the pid stream, before the sweep)

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
            "[MinKey, MaxKey]"
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
  "executionTimeMillis": 3,
  "totalKeysExamined": 100,
  "totalDocsExamined": 100
}
```

### Mongo plan (cleanup of the first snapshotted corpus pid, before the sweep; explain deletes nothing)

pid thing:bench.ns01:pid-00000055: min event sn 1, min snapshot sn 500, snapshot sn 3000; first event batch [0, 99], first snapshot batch [500, 599]

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
        "direction": "backward",
        "indexBounds": {
          "pid": [
            "[\"thing:bench.ns01:pid-00000055\", \"thing:bench.ns01:pid-00000055\"]"
          ],
          "to": [
            "[MinKey, MaxKey]"
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
```json
// winningPlan
{
  "stage": "BATCHED_DELETE",
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
          "[\"thing:bench.ns01:pid-00000055\", \"thing:bench.ns01:pid-00000055\"]"
        ],
        "to": [
          "[99, 0]"
        ]
      }
    }
  }
}
// executionStats (summary)
{
  "nReturned": 0,
  "executionTimeMillis": 1,
  "totalKeysExamined": 99,
  "totalDocsExamined": 99,
  "nWouldDelete": 99
}
```
```json
// winningPlan
{
  "stage": "LIMIT",
  "limitAmount": 1,
  "inputStage": {
    "stage": "PROJECTION_COVERED",
    "transformBy": {
      "sn": 1
    },
    "inputStage": {
      "stage": "IXSCAN",
      "keyPattern": {
        "pid": 1,
        "sn": -1,
        "_id": 1
      },
      "indexName": "snaps_pid_sn_id_index",
      "isMultiKey": false,
      "multiKeyPaths": {
        "pid": [],
        "sn": [],
        "_id": []
      },
      "isUnique": false,
      "isSparse": false,
      "isPartial": false,
      "indexVersion": 2,
      "direction": "backward",
      "indexBounds": {
        "pid": [
          "[\"thing:bench.ns01:pid-00000055\", \"thing:bench.ns01:pid-00000055\"]"
        ],
        "sn": [
          "[MinKey, MaxKey]"
        ],
        "_id": [
          "[MaxKey, MinKey]"
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
  "totalDocsExamined": 0
}
```
```json
// winningPlan
{
  "stage": "BATCHED_DELETE",
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
          "[\"thing:bench.ns01:pid-00000055\", \"thing:bench.ns01:pid-00000055\"]"
        ],
        "sn": [
          "[599, 500]"
        ],
        "ts": [
          "[MaxKey, MinKey]"
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
  "nWouldDelete": 1
}
```


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
