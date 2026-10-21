# R2 — event replay (a: from latest snapshot, b: from sn 1)

- generated: 2026-10-21T20:08:59.047370Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (~20 ev, no snapshot) | 1.26 \| 2.00 \| 2.11 | — |
| R2a tail P99 (200-400 ev past sn-2000 snapshot) | 4.98 \| 5.81 \| 5.93 | — |
| R2a tail OUTLIER (0-499 ev past latest snapshot) | 4.74 \| 6.48 \| 7.44 | — |
| R2b full P99 (~2200-2400 ev from sn 1) | 17.97 \| 21.11 \| 22.31 | — |
| R2b full OUTLIER (40k-50k ev from sn 1) | 238.57 \| 258.61 \| 259.71 | — |

### PG plan (OUTLIER full replay)

```
Sort  (cost=170157.10..170268.14 rows=44416 width=157) (actual time=84.078..88.465 rows=47037 loops=1)
  Sort Key: sn
  Sort Method: quicksort  Memory: 47447kB
  Buffers: shared hit=6674
  ->  Bitmap Heap Scan on things_journal  (cost=1850.87..166728.46 rows=44416 width=157) (actual time=4.260..73.946 rows=47037 loops=1)
        Recheck Cond: ((pid = 'thing:bench.ns02:pid-00000477'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
        Heap Blocks: exact=6331
        Buffers: shared hit=6674
        ->  Bitmap Index Scan on things_journal_pkey  (cost=0.00..1839.77 rows=44416 width=0) (actual time=2.181..2.181 rows=47037 loops=1)
              Index Cond: ((pid = 'thing:bench.ns02:pid-00000477'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
              Buffers: shared hit=343
Planning Time: 0.060 ms
JIT:
  Functions: 4
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.114 ms, Inlining 0.000 ms, Optimization 0.116 ms, Emission 1.495 ms, Total 1.726 ms
Execution Time: 89.645 ms
```

Bar mapping (design section 7): the 'outlier 50k events < 2 s' bar applies to R2b full OUTLIER; real recovery is R2a (snapshots every 500 cap the tail at 499).
