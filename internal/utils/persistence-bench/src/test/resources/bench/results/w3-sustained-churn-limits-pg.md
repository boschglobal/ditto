# W3 — sustained churn

- generated: 2026-10-21T20:13:50.318140Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-pg

- target rate 500/s, 8 workers x 100 pids, 600 s per backend
- churn pid prefix thing:bench.churn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off; worker schedules unstaggered

## PostgreSQL

- total ops 299864 (+0 snapshots), achieved 499.8/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14976 | 4.11 | 6.63 | 28.76 | 0.0 MB | 38289.8 MB | 294.3 MB | 8 |
| 1 | 15024 | 4.76 | 6.97 | 26.64 | 123.9 MB | 38291.3 MB | 294.3 MB | 8 |
| 2 | 15008 | 4.25 | 6.92 | 34.48 | 123.8 MB | 38293.0 MB | 294.3 MB | 8 |
| 3 | 15016 | 4.67 | 7.36 | 32.27 | 123.9 MB | 38294.7 MB | 294.3 MB | 8 |
| 4 | 15009 | 4.79 | 7.93 | 38.33 | 127.1 MB | 38296.8 MB | 294.3 MB | 8 |
| 5 | 15015 | 4.93 | 8.24 | 39.45 | 123.6 MB | 38298.0 MB | 294.3 MB | 8 |
| 6 | 15008 | 4.88 | 9.02 | 65.76 | 128.4 MB | 38298.8 MB | 294.3 MB | 8 |
| 7 | 15008 | 4.79 | 7.86 | 110.46 | 123.7 MB | 38299.9 MB | 294.3 MB | 8 |
| 8 | 15016 | 4.53 | 7.34 | 37.13 | 130.0 MB | 38303.1 MB | 294.3 MB | 8 |
| 9 | 15008 | 4.64 | 8.14 | 38.71 | 124.1 MB | 38304.6 MB | 294.3 MB | 8 |
| 10 | 15008 | 4.52 | 7.89 | 41.76 | 123.3 MB | 38304.9 MB | 294.3 MB | 8 |
| 11 | 15008 | 4.46 | 7.43 | 41.18 | 124.0 MB | 38306.1 MB | 294.3 MB | 8 |
| 12 | 15009 | 4.46 | 7.47 | 43.78 | 129.7 MB | 38308.8 MB | 294.3 MB | 8 |
| 13 | 15015 | 4.53 | 7.56 | 109.62 | 125.0 MB | 38310.6 MB | 294.3 MB | 8 |
| 14 | 15008 | 4.51 | 7.57 | 70.98 | 129.9 MB | 38311.1 MB | 294.3 MB | 8 |
| 15 | 15008 | 4.52 | 7.48 | 37.27 | 124.6 MB | 38312.6 MB | 294.3 MB | 8 |
| 16 | 15016 | 4.56 | 7.47 | 124.81 | 129.9 MB | 38314.9 MB | 294.3 MB | 8 |
| 17 | 15008 | 4.60 | 7.91 | 43.21 | 125.3 MB | 38317.0 MB | 294.3 MB | 8 |
| 18 | 15008 | 4.34 | 7.21 | 23.32 | 123.2 MB | 38317.1 MB | 294.3 MB | 8 |
| 19 | 14688 | 4.26 | 7.59 | 62.73 | 121.7 MB | 38318.5 MB | 294.3 MB | 8 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.15, 0.15, 0.22, 0.22, 0.25, 0.41, 0.45 ms
- journal heap placement of the run's 299864 rows: 299864 distinct pages; 299864 rows (100.0 %) on 299864 pages that existed before the run (blocks < 4641616, i.e. free space reused via the free space map), 0 rows appended on 0 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 299864 | 0.721 | 1.922 | 0.889 |
| upsert | 299864 | 0.349 | 0.790 | 0.419 |
| commit | 299864 | 3.242 | 5.702 | 3.201 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.744 | 0.352 | 2.861 | 3693 | 4.06 | — | 0.98 | 0 / 0 / 0 |
| 1 | 0.745 | 0.375 | 3.381 | 3807 | 3.94 | — | 1.01 | 0 / 0 / 0 |
| 2 | 0.726 | 0.358 | 2.968 | 3811 | 3.94 | — | 1.01 | 0 / 0 / 0 |
| 3 | 0.782 | 0.375 | 3.303 | 3817 | 3.93 | — | 1.01 | 0 / 0 / 0 |
| 4 | 0.698 | 0.342 | 3.602 | 3682 | 4.08 | — | 1.01 | 14494 / 0 / 1141 |
| 5 | 0.705 | 0.349 | 3.619 | 3822 | 3.93 | — | 1.01 | 16507 / 0 / 158 |
| 6 | 0.740 | 0.355 | 3.531 | 3817 | 3.93 | — | 1.05 | 17132 / 0 / 96 |
| 7 | 0.726 | 0.354 | 3.433 | 3820 | 3.93 | — | 1.01 | 15238 / 0 / 133 |
| 8 | 0.743 | 0.351 | 3.216 | 3825 | 3.93 | — | 1.07 | 12740 / 0 / 412 |
| 9 | 0.729 | 0.351 | 3.283 | 3703 | 4.05 | — | 0.98 | 16728 / 0 / 194 |
| 10 | 0.726 | 0.337 | 3.147 | 3849 | 3.90 | — | 1.01 | 16613 / 0 / 38 |
| 11 | 0.659 | 0.333 | 3.267 | 3834 | 3.92 | — | 1.01 | 16716 / 0 / 159 |
| 12 | 0.696 | 0.339 | 3.114 | 3875 | 3.87 | — | 1.07 | 10506 / 0 / 349 |
| 13 | 0.739 | 0.352 | 3.224 | 3707 | 4.05 | — | 0.99 | 17092 / 0 / 226 |
| 14 | 0.726 | 0.352 | 3.156 | 3857 | 3.89 | — | 1.07 | 17732 / 0 / 68 |
| 15 | 0.716 | 0.339 | 3.196 | 3838 | 3.91 | — | 1.02 | 17059 / 0 / 190 |
| 16 | 0.740 | 0.342 | 3.218 | 3877 | 3.87 | — | 1.08 | 10584 / 0 / 293 |
| 17 | 0.754 | 0.353 | 3.189 | 3864 | 3.88 | — | 1.03 | 17218 / 0 / 274 |
| 18 | 0.656 | 0.344 | 3.135 | 3700 | 4.06 | — | 0.97 | 16929 / 0 / 13 |
| 19 | 0.682 | 0.329 | 2.991 | 3853 | 3.81 | — | 1.04 | 16765 / 0 / 174 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 294759 | 0.983 |
| bgwriter.buffers_backend | 3918 | 0.013 |
| bgwriter.buffers_checkpoint | 250073 | 0.834 |
| bgwriter.checkpoint_sync_time | 58 | 0.000 |
| bgwriter.checkpoint_write_time | 339447 | 1.132 |
| bgwriter.checkpoints_req | 3 | 0.000 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blks_hit | 5757182 | 19.199 |
| db.blks_read | 310494 | 1.035 |
| db.xact_commit | 300106 | 1.001 |
| things_journal.heap_blks_hit | 929771 | 3.101 |
| things_journal.heap_blks_read | 288960 | 0.964 |
| things_journal.idx_blks_hit | 1816310 | 6.057 |
| things_journal.idx_blks_read | 1466 | 0.005 |
| things_journal_seq.heap_blks_hit | 900472 | 3.003 |
| things_journal_seq.heap_blks_read | 20040 | 0.067 |
| things_journal_seq.idx_blks_hit | 902235 | 3.009 |
| things_journal_seq.idx_blks_read | 1 | 0.000 |
| wal.wal_bytes | 2618374659 | 8731.874 |
| wal.wal_fpi | 306098 | 1.021 |
| wal.wal_records | 2036087 | 6.790 |
| wal.wal_sync | 76051 | 0.254 |
| wal.wal_write | 76051 | 0.254 |

### PG plan (append transaction of churn pid thing:bench.churn:w0-p0 at its next sn 376, after the run; rolled back)

```
Insert on things_journal  (cost=0.00..0.01 rows=0 width=0) (actual time=0.152..0.152 rows=0 loops=1)
  Buffers: shared hit=43 read=1 dirtied=1
  ->  Result  (cost=0.00..0.01 rows=1 width=152) (actual time=0.020..0.021 rows=1 loops=1)
        Buffers: shared hit=10
Planning Time: 0.021 ms
Execution Time: 0.159 ms
```
```
Insert on things_journal_seq  (cost=0.00..0.01 rows=0 width=0) (actual time=0.031..0.031 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: things_journal_seq_pkey
  Tuples Inserted: 0
  Conflicting Tuples: 1
  Buffers: shared hit=7
  ->  Result  (cost=0.00..0.01 rows=1 width=48) (actual time=0.000..0.001 rows=1 loops=1)
Planning:
  Buffers: shared hit=13
Planning Time: 0.018 ms
Execution Time: 0.082 ms
```


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
