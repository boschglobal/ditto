> **Provenance (historical evidence, do not edit).** Copied verbatim from commit `89a7cf51df`
> ("full-scale (count=250000) evidence + curated headline results", reachable via the local tag
> `pre-squash-postgres-persistance`; the squashed branch history no longer contains it), path
> `internal/utils/persistence-bench/src/test/resources/bench/results/c1-pid-stream-page.md`.
> Run date: 2026-07-13T09:06Z (UTC), `--count 250000`, seed 42.
> Schema: **v1** — snapshots carried the extra `(pid, sn DESC)` index, and the cleanup pid-stream
> page used the `DISTINCT ON` query (before the loose-index-scan rewrite).
> **Superseded by the files one level up** (schema v2 + loose-index-scan query); kept as the "before"
> side of the comparisons in `benchmark/README.md` §5.

# C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)

- generated: 2026-07-13T09:06:23.156620Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| one page, varying lower bound | 526.03 \| 887.14 \| 913.94 | 19.59 \| 42.47 \| 48.47 |

### PG plan (mid-corpus lower bound)

```
Limit  (cost=4377.09..4379.72 rows=100 width=110) (actual time=785.451..785.653 rows=100 loops=1)
  Buffers: shared hit=181397, temp read=7960 written=30267
  ->  Unique  (cost=4377.09..4576.54 rows=7592 width=110) (actual time=785.449..785.647 rows=100 loops=1)
        Buffers: shared hit=181397, temp read=7960 written=30267
        ->  Sort  (cost=4377.09..4476.81 rows=39889 width=110) (actual time=785.445..785.596 rows=478 loops=1)
              Sort Key: things_snaps.pid COLLATE "C", things_snaps.sn DESC, things_snaps.written_at DESC
              Sort Method: external merge  Disk: 242112kB
              Buffers: shared hit=181397, temp read=7960 written=30267
              ->  Seq Scan on things_snaps  (cost=0.00..1328.83 rows=39889 width=110) (actual time=0.042..544.969 rows=39722 loops=1)
                    Filter: (pid > 'thing:bench.ns01:pid-00119559'::text)
                    Rows Removed by Filter: 4572
                    Buffers: shared hit=181397
Planning:
  Buffers: shared hit=1
Planning Time: 0.139 ms
Execution Time: 812.775 ms
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
  "executionTimeMillis": 2,
  "totalKeysExamined": 100,
  "totalDocsExamined": 100
}
```

Cleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), minAge=0 (age predicates disabled), pidFilter='' — exactly what Cleanup.java passes.
