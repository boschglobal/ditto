# R3 — highest sequence number

- generated: 2026-10-21T20:08:59.067106Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| mixed classes | 0.53 \| 0.82 \| 2.26 | — |

### PG plan

```
Result  (cost=9.06..9.08 rows=1 width=8) (actual time=0.008..0.009 rows=1 loops=1)
  Buffers: shared hit=9
  InitPlan 2 (returns $1)
    ->  Result  (cost=0.62..0.63 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 1 (returns $0)
            ->  Limit  (cost=0.56..0.62 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
                  Buffers: shared hit=5
                  ->  Index Only Scan Backward using things_journal_pkey on things_journal  (cost=0.56..118.78 rows=2311 width=8) (actual time=0.005..0.005 rows=1 loops=1)
                        Index Cond: ((pid = 'thing:bench.ns15:pid-00002838'::text) AND (sn IS NOT NULL))
                        Heap Fetches: 0
                        Buffers: shared hit=5
  InitPlan 3 (returns $2)
    ->  Index Scan using things_journal_seq_pkey on things_journal_seq  (cost=0.42..8.44 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Index Cond: (pid = 'thing:bench.ns15:pid-00002838'::text)
          Buffers: shared hit=4
Planning Time: 0.030 ms
Execution Time: 0.013 ms
```
