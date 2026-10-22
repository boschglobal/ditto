# C1 — cleanup pid-stream page (getNewestSnapshotsAbove, batch 100)

- generated: 2026-10-22T21:31:31.872722Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-whatif-run3

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| one page, varying lower bound | 4.18 \| 4.94 \| 6.27 | — |

### PG plan (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..534.35 rows=100 width=110) (actual time=0.057..1.258 rows=100 loops=1)
  Buffers: shared hit=863
  ->  Limit  (cost=0.41..24.36 rows=100 width=30) (actual time=0.006..0.047 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..1829.23 rows=7637 width=30) (actual time=0.006..0.043 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..1749.08 rows=32060 width=30) (actual time=0.005..0.026 rows=527 loops=1)
                    Index Cond: (pid > 'thing:bench.ns02:pid-00122616'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.012..0.012 rows=1 loops=100)
        Buffers: shared hit=855
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.46 rows=6 width=80) (actual time=0.012..0.012 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=855
Planning Time: 0.050 ms
Execution Time: 1.266 ms
```

Cleanup parameters: includeDeleted=true (no DELETED filter stage on Mongo), minAge=0 (the bench's history-retention pin: age predicates disabled; production things cleanup passes history-retention-duration, default 3d — see the C1 retention variant), pidFilter='' — what Cleanup.java passes with that pin.
