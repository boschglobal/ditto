# W1 — single append txn

- generated: 2026-10-23T00:59:11.436069Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cldefault-quiet

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| append (journal + high-water-mark) | 1.54 \| 2.78 \| 2.89 | — |

PG: event INSERT + journal_seq upsert, one transaction (synchronous_commit=on). Mongo: ordered 1-doc bulkWrite, WriteConcern JOURNALED; the plugin's async realtime-collection insert runs fire-and-forget outside the timed path exactly as in ScalaDriverPersistenceJournaller.batchAppend (plan deviation 4).

- PG autovacuum in progress at start: none; at end: none

### PG plan (append transaction of thing:bench.w1:pg-quiet at its next sn 54, after the timed loop; rolled back)

```
Insert on things_journal  (cost=0.00..0.01 rows=0 width=0) (actual time=0.036..0.036 rows=0 loops=1)
  Buffers: shared hit=7 read=1 dirtied=1
  ->  Result  (cost=0.00..0.01 rows=1 width=152) (actual time=0.005..0.005 rows=1 loops=1)
        Buffers: shared hit=1
Planning Time: 0.005 ms
Execution Time: 0.039 ms
```
```
Insert on things_journal_seq  (cost=0.00..0.01 rows=0 width=0) (actual time=0.010..0.011 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_journal_seq_pkey
  Tuples Inserted: 0
  Conflicting Tuples: 1
  Buffers: shared hit=6
  ->  Result  (cost=0.00..0.01 rows=1 width=48) (actual time=0.000..0.000 rows=1 loops=1)
Planning Time: 0.005 ms
Execution Time: 0.014 ms
```
