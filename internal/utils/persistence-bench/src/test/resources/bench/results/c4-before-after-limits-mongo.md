# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-21T22:22:09.748910Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo


## MongoDB

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | — | 1.21 \| 2.00 \| 2.55 |
| R2a tail P99 (n=50) | — | 2.87 \| 4.62 \| 5.47 |
| R2a tail OUTLIER (n=23) | — | 2.25 \| 3.57 \| 3.64 |
| R2b full-scan OUTLIER (post-sweep) (n=23) | — | 1.65 \| 2.00 \| 2.07 |
| R2b full-scan P99 (post-sweep) (n=50) | — | 1.62 \| 1.99 \| 3.97 |

### Mongo plan (R2b full-scan replay of an untimed P99 pid, its first read after the sweep)

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
          "[\"thing:bench.ns01:pid-00106368\", \"thing:bench.ns01:pid-00106368\"]"
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
  "nReturned": 234,
  "executionTimeMillis": 1,
  "totalKeysExamined": 234,
  "totalDocsExamined": 234
}
```

### Natural sizes

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 12943385 | 13617.8 MB | 26485.7 MB | 1743.8 MB |
| things_snaps | 8581 | 50.2 MB | 299.2 MB | 3.7 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |

### OFFLINE reclamation

- skipped (bench.c4.offlineReclaim=false)

Deletes free space inside data files (reusable) but do not shrink them — the honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the live/dead tuple counts above.
