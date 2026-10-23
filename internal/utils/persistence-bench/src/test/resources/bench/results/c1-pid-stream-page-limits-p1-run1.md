# C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)

- generated: 2026-10-23T12:59:31.957591Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-p1-run1

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| one page, varying lower bound | 1.29 \| 2.41 \| 2.81 | — |

### PG plan (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..533.47 rows=100 width=78) (actual time=0.064..0.430 rows=100 loops=1)
  Buffers: shared hit=408
  ->  Limit  (cost=0.41..24.35 rows=100 width=30) (actual time=0.045..0.186 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1824.16 rows=7618 width=30) (actual time=0.044..0.181 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1744.13 rows=32012 width=30) (actual time=0.043..0.097 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.08 rows=1 width=48) (actual time=0.002..0.002 rows=1 loops=100)
        Buffers: shared hit=400
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.41 rows=6 width=48) (actual time=0.002..0.002 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=400
Planning Time: 0.184 ms
Execution Time: 0.452 ms
```

Cleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), minAge=0 (the bench's history-retention pin: age predicates disabled; production things cleanup passes history-retention-duration, default 3d — see the C1 retention variant), pidFilter='' — what Cleanup.java passes with that pin. PG statement: PersistenceShapes.PG_NEWEST_SNAPSHOTS_ABOVE, the payload-free page production issues for Cleanup since 2026-10-23; c1 evidence written before then timed the page with the payload.
