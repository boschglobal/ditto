# C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)

- generated: 2026-10-21T20:26:05.588974Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| one page, varying lower bound | 16.95 \| 25.47 \| 28.31 | — |

### PG plan (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..534.37 rows=100 width=110) (actual time=0.057..1.356 rows=100 loops=1)
  Buffers: shared hit=863
  ->  Limit  (cost=0.41..24.41 rows=100 width=30) (actual time=0.007..0.051 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1828.63 rows=7618 width=30) (actual time=0.007..0.048 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1748.56 rows=32031 width=30) (actual time=0.007..0.030 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.013..0.013 rows=1 loops=100)
        Buffers: shared hit=855
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.46 rows=6 width=80) (actual time=0.013..0.013 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=855
Planning Time: 0.057 ms
Execution Time: 1.365 ms
```

Cleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), minAge=0 (the bench's history-retention pin: age predicates disabled; production things cleanup passes history-retention-duration, default 3d — see the C1 retention variant), pidFilter='' — what Cleanup.java passes with that pin.
