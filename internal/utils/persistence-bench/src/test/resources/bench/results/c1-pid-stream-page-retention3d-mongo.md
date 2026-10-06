# C1 — cleanup pid-stream page with the production min age (retention 3d vs 0, batch 100)

- generated: 2026-10-02T16:02:50.349521Z
- corpus: count=250000 seed=42 freshShare=0.3 freshBase=2026-10-01T00:00:00Z
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- fresh-only pids (share 0.3, rows at 2026-10-01T00:00:00Z + sn seconds; all others at 2026-01-01T00:00:00Z + sn seconds): 75194 of 250000; with snapshots: 2568 of 8581

## MongoDB

| variant (one page, varying lower bound) | p50 \| p95 \| max (ms) |
|---|---|
| minAge 0 (age filter off — the committed C1 shape) | 1.02 \| 1.27 \| 1.49 |
| retention 3d (production min age) | 1.15 \| 1.63 \| 1.72 |

- result check, minAge PT0S: 23 of 23 pages equal the generator's expected page; pids per page min/mean/max 11 / 22.6 / 35
- result check, minAge PT72H: 23 of 23 pages equal the generator's expected page; pids per page min/mean/max 9 / 22.7 / 33
- age mix at timing start: 8581 pids with snapshots (expected 8581), 2568 of them without a snapshot older than 3d (expected 2568)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 35070304 | 36913.9 MB | 26459.6 MB | 1693.0 MB |
| things_snaps | 44271 | 263.0 MB | 273.8 MB | 3.2 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |

### Mongo plan — minAge 0 (mid-corpus lower bound)

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
            "(\"thing:bench.ns01:pid-00119559\", {})"
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

### Mongo plan — retention 3d (mid-corpus lower bound)

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
        "direction": "forward",
        "indexBounds": {
          "pid": [
            "(\"thing:bench.ns01:pid-00119559\", {})"
          ],
          "sn": [
            "[MaxKey, MinKey]"
          ],
          "_id": [
            "[ObjectId('000000000000000000000000'), ObjectId('6abbe12a0000000000000000'))"
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
  "totalKeysExamined": 188,
  "totalDocsExamined": 100
}
```

What is compared: the binds Cleanup.java passes (includeDeleted=true, pidFilter='', minAgeFromNow = history-retention-duration). PG (PostgresPersistenceOperations.getNewestSnapshotsAbove): a non-zero min age sets the no-age-filter flag to false, so 'written_at < now() - interval' filters rows in BOTH steps (step 1: a pid with no old-enough row takes no page slot; step 2: the newest old-enough row wins); the page is 100 distinct pids. Mongo (MongoReadJournal.toMongoFilter): a non-zero min age ANDs '_id < ObjectId.getSmallestWithDate(now - minAge)' to the pid bound in $match, before $sort/$limit/$group; $limit counts snapshot ROWS, so a Mongo page spans fewer pids. written_at and the _id timestamp are both the snapshot's write time (corpus snapshot _ids embed it). Timing: the two variants interleaved per lower bound, alternating which runs first.
