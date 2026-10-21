# R2 — event replay (a: from latest snapshot, b: from sn 1)

- generated: 2026-10-21T20:48:49.515969Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (~20 ev, no snapshot) | — | 1.78 \| 2.57 \| 2.61 |
| R2a tail P99 (200-400 ev past sn-2000 snapshot) | — | 5.14 \| 8.88 \| 10.80 |
| R2a tail OUTLIER (0-499 ev past latest snapshot) | — | 5.22 \| 7.65 \| 7.95 |
| R2b full P99 (~2200-2400 ev from sn 1) | — | 13.81 \| 19.43 \| 22.03 |
| R2b full OUTLIER (40k-50k ev from sn 1) | — | 227.84 \| 272.50 \| 273.70 |


### Mongo plan (OUTLIER full replay)

```json
// winningPlan
{
  "stage": "PROJECTION_SIMPLE",
  "transformBy": {
    "events": 1
  },
  "inputStage": {
    "stage": "FETCH",
    "filter": {
      "from": {
        "$gte": 1
      }
    },
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
          "[\"thing:bench.ns02:pid-00000477\", \"thing:bench.ns02:pid-00000477\"]"
        ],
        "to": [
          "[-inf.0, 9223372036854775807]"
        ]
      }
    }
  }
}
// executionStats (summary)
{
  "nReturned": 47037,
  "executionTimeMillis": 26,
  "totalKeysExamined": 47037,
  "totalDocsExamined": 47037
}
```

Bar mapping (design section 7): the 'outlier 50k events < 2 s' bar applies to R2b full OUTLIER; real recovery is R2a (snapshots every 500 cap the tail at 499).
