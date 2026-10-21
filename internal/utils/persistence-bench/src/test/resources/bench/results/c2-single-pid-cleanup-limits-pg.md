# C2 — single-pid cleanup (batched deletes, batch size 100)

- generated: 2026-10-21T20:28:39.832225Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| P99 total per-pid | 32.67 \| 44.03 \| 46.24 | — |
| P99 per delete batch (100 rows) | 0.67 \| 3.34 \| 8.39 | — |
| OUTLIER total per-pid | 987.45 \| 1820.02 \| 1951.56 | — |
| OUTLIER per delete batch (100 rows) | 0.72 \| 3.10 \| 269.06 | — |

- P99: 23 pids found (3 warmup + 20 timed)
- OUTLIER: 23 pids found (3 warmup + 20 timed)

Sequence per pid (Cleanup.java 1:1): min event sn -> event ranges tiling to snapshotSn-1 -> min snapshot sn -> snapshot ranges keeping the latest. No journal_seq.deleted_to update — the real cleanup path does not touch it (plan deviation 2). C2 consumes these pids' cleanup backlog; rerun 'load' for a pristine corpus.

### PG plan (P99, first warm-up pid, before its cleanup; deletes rolled back)

pid thing:bench.ns01:pid-00126124: min event sn 1, min snapshot sn 500, snapshot sn 2000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.62..0.63 rows=1 width=8) (actual time=0.026..0.026 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.62 rows=1 width=8) (actual time=0.025..0.025 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..118.78 rows=2311 width=8) (actual time=0.025..0.025 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00126124'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.062 ms
Execution Time: 0.033 ms
```
```
Delete on things_journal  (cost=0.56..2473.12 rows=0 width=0) (actual time=3.771..3.772 rows=0 loops=1)
  Buffers: shared hit=120 read=15 dirtied=15
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2473.12 rows=612 width=6) (actual time=2.265..3.712 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00126124'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=7 read=15
Planning:
  Buffers: shared hit=6
Planning Time: 0.048 ms
Execution Time: 3.812 ms
```
```
Result  (cost=2.04..2.04 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
  Buffers: shared hit=4
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.41..2.04 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Buffers: shared hit=4
          ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.52 rows=5 width=8) (actual time=0.008..0.008 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns01:pid-00126124'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=4
Planning Time: 0.042 ms
Execution Time: 0.015 ms
```
```
Delete on things_snaps  (cost=0.41..8.44 rows=0 width=0) (actual time=0.448..0.448 rows=0 loops=1)
  Buffers: shared hit=35 read=2 dirtied=4
  ->  Index Scan using things_snaps_pkey on things_snaps  (cost=0.41..8.44 rows=1 width=6) (actual time=0.008..0.008 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns01:pid-00126124'::text) AND (sn >= 500) AND (sn <= 599))
        Buffers: shared hit=4
Planning Time: 0.020 ms
Execution Time: 0.458 ms
```

### PG plan (OUTLIER, first warm-up pid, before its cleanup; deletes rolled back)

pid thing:bench.ns07:pid-00137316: min event sn 1, min snapshot sn 500, snapshot sn 43000; first event batch [0, 99], first snapshot batch [500, 599]

```
Result  (cost=0.62..0.63 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
  Buffers: shared hit=5
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.56..0.62 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=5
          ->  Index Only Scan using things_journal_pkey on things_journal  (cost=0.56..118.78 rows=2311 width=8) (actual time=0.007..0.007 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns07:pid-00137316'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=5
Planning Time: 0.020 ms
Execution Time: 0.010 ms
```
```
Delete on things_journal  (cost=0.56..2473.12 rows=0 width=0) (actual time=0.103..0.103 rows=0 loops=1)
  Buffers: shared hit=133 dirtied=13
  ->  Index Scan using things_journal_pkey on things_journal  (cost=0.56..2473.12 rows=612 width=6) (actual time=0.010..0.045 rows=99 loops=1)
        Index Cond: ((pid = 'thing:bench.ns07:pid-00137316'::text) AND (sn >= 0) AND (sn <= 99))
        Buffers: shared hit=21
Planning:
  Buffers: shared hit=6
Planning Time: 0.031 ms
Execution Time: 0.124 ms
```
```
Result  (cost=0.72..0.73 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
  Buffers: shared hit=4
  InitPlan 1 (returns $0)
    ->  Limit  (cost=0.41..0.72 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=4
          ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..21.74 rows=69 width=8) (actual time=0.005..0.005 rows=1 loops=1)
                Index Cond: ((pid = 'thing:bench.ns07:pid-00137316'::text) AND (sn IS NOT NULL))
                Heap Fetches: 0
                Buffers: shared hit=4
Planning Time: 0.024 ms
Execution Time: 0.009 ms
```
```
Delete on things_snaps  (cost=4.58..50.95 rows=0 width=0) (actual time=0.358..0.358 rows=0 loops=1)
  Buffers: shared hit=37 read=1 dirtied=2
  ->  Bitmap Heap Scan on things_snaps  (cost=4.58..50.95 rows=13 width=6) (actual time=0.009..0.009 rows=1 loops=1)
        Recheck Cond: ((pid = 'thing:bench.ns07:pid-00137316'::text) AND (sn >= 500) AND (sn <= 599))
        Heap Blocks: exact=1
        Buffers: shared hit=4
        ->  Bitmap Index Scan on things_snaps_pkey  (cost=0.00..4.58 rows=13 width=0) (actual time=0.003..0.003 rows=1 loops=1)
              Index Cond: ((pid = 'thing:bench.ns07:pid-00137316'::text) AND (sn >= 500) AND (sn <= 599))
              Buffers: shared hit=3
Planning Time: 0.018 ms
Execution Time: 0.367 ms
```

