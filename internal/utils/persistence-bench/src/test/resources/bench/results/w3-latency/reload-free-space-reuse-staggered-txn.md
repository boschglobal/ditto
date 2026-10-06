# W3 — sustained churn

- generated: 2026-10-06T10:54:53.579882Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 90 s per backend
- churn pid prefix thing:bench.churn-s-stagger-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 45007 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14614 | 1.73 | 2.86 | 52.19 | 0.0 MB | 7605.6 MB | 58.8 MB | 0 |
| 1 | 15014 | 1.75 | 4.46 | 100.07 | 124.0 MB | 7607.1 MB | 58.8 MB | 0 |
| 2 | 14987 | 1.71 | 3.08 | 56.10 | 125.3 MB | 7607.7 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 45007 rows: 45007 distinct pages; 45007 rows (100.0 %) on 45007 pages that existed before the run (blocks < 920400, i.e. free space reused via the free space map), 0 rows appended on 0 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 45007 | 0.228 | 0.389 | 0.288 |
| upsert | 45007 | 0.136 | 0.186 | 0.149 |
| commit | 45007 | 1.349 | 2.667 | 1.585 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.226 | 0.133 | 1.351 | 14156 | 1.03 | 1.31 | 0.98 | 8963 / 0 / 195 |
| 1 | 0.222 | 0.136 | 1.383 | 14723 | 1.02 | 1.36 | 1.01 | 6667 / 0 / 72 |
| 2 | 0.236 | 0.140 | 1.302 | 14932 | 1.00 | 1.29 | 1.04 | 16762 / 0 / 199 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 45474 | 1.010 |
| bgwriter.buffers_backend | 467 | 0.010 |
| bgwriter.buffers_checkpoint | 32914 | 0.731 |
| bgwriter.checkpoint_sync_time | 19 | 0.000 |
| bgwriter.checkpoint_write_time | 122098 | 2.713 |
| bgwriter.checkpoints_req | 1 | 0.000 |
| db.blk_read_time | 1115 | 0.025 |
| db.blk_write_time | 3 | 0.000 |
| db.blks_hit | 874284 | 19.426 |
| db.blks_read | 45007 | 1.000 |
| db.xact_commit | 52614 | 1.169 |
| things_journal.heap_blks_hit | 138149 | 3.070 |
| things_journal.heap_blks_read | 45007 | 1.000 |
| things_journal.idx_blks_hit | 271648 | 6.036 |
| things_journal_seq.heap_blks_hit | 134957 | 2.999 |
| things_journal_seq.idx_blks_hit | 137588 | 3.057 |
| wal.wal_bytes | 389710941 | 8658.896 |
| wal.wal_fpi | 45410 | 1.009 |
| wal.wal_records | 304029 | 6.755 |
| wal.wal_sync | 44261 | 0.983 |
| wal.wal_sync_time | 58374 | 1.297 |
| wal.wal_write | 44262 | 0.983 |
| wal.wal_write_time | 346 | 0.008 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 7538 sampler passes, 60285 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 53380 | 88.5 % |
| active / IO:WALSync | 5097 | 8.5 % |
| idle in transaction / Client:ClientRead | 1117 | 1.9 % |
| active / LWLock:WALWrite | 527 | 0.9 % |
| active / CPU | 84 | 0.1 % |
| active / IO:DataFileRead | 17 | 0.0 % |
| idle in transaction / CPU | 16 | 0.0 % |
| idle in transaction / IO:WALSync | 16 | 0.0 % |
| idle / CPU | 11 | 0.0 % |
| active / Client:ClientRead | 9 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 4 | 0.0 % |
| active / IO:WALWrite | 3 | 0.0 % |
| active / LWLock:XidGen | 2 | 0.0 % |
| active / LWLock:BufferContent | 1 | 0.0 % |
| idle in transaction / IO:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
