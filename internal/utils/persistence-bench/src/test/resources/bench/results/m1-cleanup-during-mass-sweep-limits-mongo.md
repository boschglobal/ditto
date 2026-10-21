# M1 — live traffic during the mass cleanup sweep (loaded backlog, no VACUUM FULL before it)

- generated: 2026-10-21T21:10:41.617158Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

- churn: 500/s target, 8 workers x 100 pid(s)/worker, 600 s, 30 s windows; sweep: one unthrottled pass over the whole cleanup backlog (C3's sweep), started with the churn and run to its end
- churn pid prefix thing:bench.churn-m1m; the churn has W3's shape, so the same backend's w3-sustained-churn evidence is the no-sweep control

## MongoDB

- backlog before the run: 42225 snapshot rows (the corpus generates 44271)

- churn: 300000 ops (+0 snapshots), achieved 500.0/s over 600 s
- mass sweep: 8582 pids, 21054465 event + 33643 snapshot rows deleted, 378586 delete batches, 404.1 s (52188 rows/s); started 2026-10-21T21:10:41.900014Z, finished 2026-10-21T21:17:25.983014Z = 404 s after the churn started (the churn ran 600 s)
- churn pids swept by the mass sweep: 0 (expected 0)
- churn windows that ended while the mass sweep was still deleting: windows 0–12 (13 × 30 s): achieved 500.3/s; window p50 1.92–3.30 ms, p95 5.01–20.89 ms
- churn windows after the mass sweep had finished: windows 13–19 (7 × 30 s): achieved 499.5/s; window p50 1.54–2.34 ms, p95 3.23–10.94 ms

Churn windows (on the churn's clock):

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 15005 | 1.92 | 5.01 | 60.10 | — | 28208.6 MB | 280.6 MB | — |
| 1 | 15003 | 2.07 | 6.65 | 164.51 | — | 28221.2 MB | 289.7 MB | — |
| 2 | 15016 | 2.82 | 13.84 | 223.97 | — | 28221.2 MB | 289.7 MB | — |
| 3 | 14992 | 3.30 | 20.89 | 212.04 | — | 28221.1 MB | 289.7 MB | — |
| 4 | 15024 | 3.23 | 17.06 | 249.65 | — | 28221.1 MB | 289.7 MB | — |
| 5 | 15008 | 2.92 | 14.55 | 212.83 | — | 28220.5 MB | 289.7 MB | — |
| 6 | 15008 | 2.50 | 14.20 | 573.54 | — | 28220.5 MB | 289.7 MB | — |
| 7 | 15008 | 2.92 | 13.39 | 204.42 | — | 28220.5 MB | 289.7 MB | — |
| 8 | 15008 | 2.50 | 10.91 | 168.12 | — | 28220.5 MB | 289.7 MB | — |
| 9 | 15016 | 2.83 | 11.66 | 201.77 | — | 28220.5 MB | 289.6 MB | — |
| 10 | 15008 | 2.35 | 9.58 | 180.98 | — | 28220.5 MB | 289.6 MB | — |
| 11 | 15008 | 1.94 | 9.00 | 58.08 | — | 28220.5 MB | 288.9 MB | — |
| 12 | 15000 | 2.42 | 10.31 | 311.19 | — | 28220.5 MB | 288.9 MB | — |
| 13 | 15008 | 2.34 | 10.94 | 241.80 | — | 28220.5 MB | 288.8 MB | — |
| 14 | 15000 | 1.54 | 5.41 | 333.22 | — | 28220.5 MB | 288.8 MB | — |
| 15 | 15008 | 1.99 | 7.71 | 157.22 | — | 28220.5 MB | 288.8 MB | — |
| 16 | 15000 | 1.92 | 3.81 | 189.93 | — | 28220.5 MB | 288.8 MB | — |
| 17 | 15008 | 1.75 | 3.23 | 110.65 | — | 28220.5 MB | 288.8 MB | — |
| 18 | 15000 | 1.91 | 6.13 | 180.09 | — | 28220.5 MB | 288.8 MB | — |
| 19 | 14872 | 1.90 | 5.11 | 258.50 | — | 28220.5 MB | 288.8 MB | — |

Mass sweep windows (on the sweeper's clock, which starts just before the churn's):

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 876 | 2015649 | 29.2 | 67188 | — | 28209.7 MB | 280.6 MB | — |
| 1 | 1630 | 3851791 | 25.1 | 61205 | — | 28221.2 MB | 289.7 MB | — |
| 2 | 2156 | 5298289 | 17.5 | 48217 | — | 28221.2 MB | 289.7 MB | — |
| 3 | 2756 | 6586057 | 20.0 | 42926 | — | 28221.1 MB | 289.7 MB | — |
| 4 | 3299 | 7952916 | 18.1 | 45562 | — | 28221.1 MB | 289.7 MB | — |
| 5 | 3817 | 9398369 | 17.3 | 48182 | — | 28220.5 MB | 289.7 MB | — |
| 6 | 4406 | 10909009 | 19.6 | 50355 | — | 28220.5 MB | 289.7 MB | — |
| 7 | 5061 | 12356908 | 21.8 | 48263 | — | 28220.5 MB | 289.7 MB | — |
| 8 | 5715 | 13975139 | 21.8 | 53941 | — | 28220.5 MB | 289.7 MB | — |
| 9 | 6269 | 15458008 | 18.5 | 49429 | — | 28220.5 MB | 289.6 MB | — |
| 10 | 6912 | 17082050 | 21.4 | 54135 | — | 28220.5 MB | 289.6 MB | — |
| 11 | 7618 | 18811614 | 23.5 | 57652 | — | 28220.5 MB | 288.9 MB | — |
| 12 | 8270 | 20430550 | 21.7 | 53965 | — | 28220.5 MB | 288.9 MB | — |

### Mongo plan (C1-shaped page at the start of the pid stream, before the sweep and the churn started)

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
  "executionTimeMillis": 1,
  "totalKeysExamined": 100,
  "totalDocsExamined": 100
}
```

### Mongo plan (cleanup of the first snapshotted corpus pid, before the sweep and the churn started; explain deletes nothing)

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
  "executionTimeMillis": 0,
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

### Mongo plan (churn append)

```
not explainable: journal bulkWrite(InsertOne) and the realtime insertOne (MongoDB's explain command has no insert or compact form) — timed only
```

