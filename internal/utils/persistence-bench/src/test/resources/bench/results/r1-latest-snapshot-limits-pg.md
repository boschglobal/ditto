# R1 — latest-snapshot fetch

- generated: 2026-10-21T20:08:52.662326Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| MEDIAN (no snapshot) | 0.31 \| 0.53 \| 0.56 | — |
| P99 | 0.63 \| 1.01 \| 1.06 | — |
| OUTLIER | 0.69 \| 0.80 \| 0.84 | — |

### PG plan (P99 pid)

```
Limit  (cost=0.41..5.23 rows=1 width=110) (actual time=0.013..0.014 rows=1 loops=1)
  Buffers: shared hit=8
  ->  Index Scan Backward using things_snaps_pkey on things_snaps  (cost=0.41..24.51 rows=5 width=110) (actual time=0.013..0.013 rows=1 loops=1)
        Index Cond: ((pid = 'thing:bench.ns15:pid-00002838'::text) AND (sn <= '9223372036854775807'::bigint) AND (sn >= 0) AND (written_at <= '10000-01-01 01:59:59+02'::timestamp with time zone) AND (written_at >= '1970-01-01 02:00:00+02'::timestamp with time zone))
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=3
Planning Time: 0.028 ms
Execution Time: 0.017 ms
```
