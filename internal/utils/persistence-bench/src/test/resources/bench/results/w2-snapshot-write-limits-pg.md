# W2 — snapshot write (~4 KB)

- generated: 2026-10-21T20:11:34.799626Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 1.72 \| 2.33 \| 2.62 | — |
| overwrite (same pid/sn/ts key) | 1.34 \| 1.89 \| 2.07 | — |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).

- PG autovacuum in progress at start: none; at end: none

### PG plan (fresh insert at sn 54; rolled back)

```
Insert on things_snaps  (cost=0.00..0.01 rows=0 width=0) (actual time=0.038..0.038 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_snaps_pkey
  Tuples Inserted: 1
  Conflicting Tuples: 0
  Buffers: shared hit=8
  ->  Result  (cost=0.00..0.01 rows=1 width=112) (actual time=0.000..0.000 rows=1 loops=1)
Planning Time: 0.014 ms
Execution Time: 0.042 ms
```

### PG plan (overwrite of the sn 1 key; rolled back)

```
Insert on things_snaps  (cost=0.00..0.01 rows=0 width=0) (actual time=0.031..0.031 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_snaps_pkey
  Tuples Inserted: 0
  Conflicting Tuples: 1
  Buffers: shared hit=6
  ->  Result  (cost=0.00..0.01 rows=1 width=112) (actual time=0.000..0.000 rows=1 loops=1)
Planning Time: 0.015 ms
Execution Time: 0.036 ms
```
