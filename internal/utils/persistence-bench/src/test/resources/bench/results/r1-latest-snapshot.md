# R1 — latest-snapshot fetch

- generated: 2026-07-13T19:59:21.905360Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| MEDIAN (no snapshot) | 0.50 \| 1.14 \| 1.69 | 1.11 \| 1.33 \| 1.41 |
| P99 | 0.56 \| 0.92 \| 1.06 | 0.91 \| 1.07 \| 1.08 |
| OUTLIER | 0.54 \| 0.79 \| 0.92 | 0.82 \| 1.17 \| 1.75 |

### PG plan (P99 pid)

```
Limit  (cost=0.41..5.23 rows=1 width=110) (actual time=0.029..0.030 rows=1 loops=1)
  Buffers: shared hit=8
  ->  Index Scan Backward using things_snaps_pkey on things_snaps  (cost=0.41..24.50 rows=5 width=110) (actual time=0.029..0.029 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns15:pid-00002838'::text) AND (sn <= '9223372036854775807'::bigint) AND (sn >= 0) AND (written_at <= '10000-01-01 01:59:59+02'::timestamp with time zone) AND (written_at >= '1970-01-01 02:00:00+02'::timestamp with time zone))
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=4
Planning Time: 0.089 ms
Execution Time: 0.038 ms
```

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
  "executionTimeMillis": 1,
  "totalKeysExamined": 1,
  "totalDocsExamined": 1
}
```
