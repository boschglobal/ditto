# W2 — snapshot write (~4 KB)

- generated: 2026-10-23T00:03:44.741640Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cldefault-vac

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 1.73 \| 2.80 \| 10.86 | — |
| overwrite (same pid/sn/ts key) | 2.01 \| 2.42 \| 3.18 | — |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).

- PG autovacuum in progress at start: things_journal scanning heap (heap 1388817/4641592, index passes 0); at end: things_journal scanning heap (heap 1390110/4641592, index passes 0)

### PG plan (fresh insert at sn 54; rolled back)

```
Insert on things_snaps  (cost=0.00..0.01 rows=0 width=0) (actual time=0.026..0.026 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_snaps_pkey
  Tuples Inserted: 1
  Conflicting Tuples: 0
  Buffers: shared hit=8
  ->  Result  (cost=0.00..0.01 rows=1 width=112) (actual time=0.000..0.000 rows=1 loops=1)
Planning Time: 0.008 ms
Execution Time: 0.029 ms
```

### PG plan (overwrite of the sn 1 key; rolled back)

```
Insert on things_snaps  (cost=0.00..0.01 rows=0 width=0) (actual time=0.024..0.024 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_snaps_pkey
  Tuples Inserted: 0
  Conflicting Tuples: 1
  Buffers: shared hit=6
  ->  Result  (cost=0.00..0.01 rows=1 width=112) (actual time=0.000..0.000 rows=1 loops=1)
Planning Time: 0.008 ms
Execution Time: 0.028 ms
```
