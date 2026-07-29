# R3 — highest sequence number

- generated: 2026-07-13T19:59:45.641249Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| mixed classes | 0.55 \| 0.84 \| 0.89 | 0.70 \| 1.34 \| 1.75 |

### PG plan

```
Result  (cost=9.06..9.08 rows=1 width=8) (actual time=0.023..0.023 rows=1 loops=1)
  Buffers: shared hit=9
  InitPlan 2 (returns $1)
    ->  Result  (cost=0.62..0.63 rows=1 width=8) (actual time=0.014..0.014 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 1 (returns $0)
            ->  Limit  (cost=0.56..0.62 rows=1 width=8) (actual time=0.014..0.014 rows=1 loops=1)
                  Buffers: shared hit=5
                  ->  Index Only Scan Backward using things_journal_pkey on things_journal  (cost=0.56..119.06 rows=2325 width=8) (actual time=0.013..0.013 rows=1 loops=1)
                        Index Cond: ((pid = 'thing:bench.ns15:pid-00002838'::text) AND (sn IS NOT NULL))
                        Heap Fetches: 0
                        Buffers: shared hit=5
  InitPlan 3 (returns $2)
    ->  Index Scan using things_journal_seq_pkey on things_journal_seq  (cost=0.42..8.44 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Index Cond: (pid = 'thing:bench.ns15:pid-00002838'::text)
          Buffers: shared hit=4
Planning Time: 0.039 ms
Execution Time: 0.031 ms
```

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
