# R2 — event replay (a: from latest snapshot, b: from sn 1)

- generated: 2026-07-13T19:59:45.595483Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (~20 ev, no snapshot) | 1.80 \| 3.03 \| 3.18 | 2.02 \| 2.41 \| 2.56 |
| R2a tail P99 (200-400 ev past sn-2000 snapshot) | 7.65 \| 10.90 \| 12.74 | 6.87 \| 10.44 \| 11.79 |
| R2a tail OUTLIER (0-499 ev past latest snapshot) | 8.04 \| 10.09 \| 13.59 | 6.03 \| 10.86 \| 11.20 |
| R2b full P99 (~2200-2400 ev from sn 1) | 31.23 \| 38.80 \| 52.45 | 23.23 \| 29.91 \| 36.40 |
| R2b full OUTLIER (40k-50k ev from sn 1) | 468.92 \| 522.02 \| 589.89 | 457.41 \| 530.99 \| 567.22 |

### PG plan (OUTLIER full replay)

```
Sort  (cost=195799.22..195927.79 rows=51429 width=157) (actual time=156.178..167.887 rows=47037 loops=1)
  Sort Key: sn
  Sort Method: quicksort  Memory: 47447kB
  Buffers: shared hit=6674
  ->  Bitmap Heap Scan on things_journal  (cost=2140.28..191774.83 rows=51429 width=157) (actual time=7.399..129.865 rows=47037 loops=1)
        Recheck Cond: ((pid = 'thing:bench.ns02:pid-00000477'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
        Heap Blocks: exact=6331
        Buffers: shared hit=6674
        ->  Bitmap Index Scan on things_journal_pkey  (cost=0.00..2127.43 rows=51429 width=0) (actual time=4.309..4.309 rows=47037 loops=1)
              Index Cond: ((pid = 'thing:bench.ns02:pid-00000477'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
              Buffers: shared hit=343
Planning Time: 0.139 ms
JIT:
  Functions: 4
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.251 ms, Inlining 0.000 ms, Optimization 0.226 ms, Emission 2.236 ms, Total 2.714 ms
Execution Time: 170.184 ms
```

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
  "executionTimeMillis": 83,
  "totalKeysExamined": 47037,
  "totalDocsExamined": 47037
}
```

Bar mapping (design section 7): the 'outlier 50k events < 2 s' bar applies to R2b full OUTLIER; real recovery is R2a (snapshots every 500 cap the tail at 499).
