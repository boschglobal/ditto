# C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)

- generated: 2026-07-13T20:20:04.389720Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| one page, varying lower bound | 4.56 \| 8.50 \| 8.92 | 5.87 \| 10.71 \| 14.19 |

### PG plan (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..539.47 rows=100 width=110) (actual time=0.048..1.611 rows=100 loops=1)
  Buffers: shared hit=853
  ->  Limit  (cost=0.41..29.62 rows=100 width=30) (actual time=0.009..0.060 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..2245.47 rows=7687 width=30) (actual time=0.008..0.055 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2145.79 rows=39873 width=30) (actual time=0.008..0.035 rows=478 loops=1)
                    Index Cond: (pid > 'thing:bench.ns01:pid-00119559'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.015..0.015 rows=1 loops=100)
        Buffers: shared hit=845
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.46 rows=6 width=80) (actual time=0.015..0.015 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=845
Planning:
  Buffers: shared hit=2
Planning Time: 0.070 ms
Execution Time: 1.622 ms
```

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
  "executionTimeMillis": 1,
  "totalKeysExamined": 100,
  "totalDocsExamined": 100
}
```

Cleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), minAge=0 (age predicates disabled), pidFilter='' — exactly what Cleanup.java passes.
