# W3 — sustained churn

- generated: 2026-10-06T10:53:17.494471Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 90 s per backend
- churn pid prefix thing:bench.churn-s-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 44808 (+0 snapshots), achieved 497.9/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14952 | 5.16 | 8.66 | 62.78 | 0.0 MB | 7602.4 MB | 58.8 MB | 0 |
| 1 | 15032 | 5.55 | 9.20 | 41.23 | 124.1 MB | 7603.5 MB | 58.8 MB | 0 |
| 2 | 14824 | 5.67 | 8.66 | 39.64 | 122.1 MB | 7604.2 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.18, 0.20, 0.22, 0.28, 0.39, 1.41, 1.46 ms
- journal heap placement of the run's 44808 rows: 44808 distinct pages; 44808 rows (100.0 %) on 44808 pages that existed before the run (blocks < 920400, i.e. free space reused via the free space map), 0 rows appended on 0 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 44808 | 0.974 | 2.268 | 1.112 |
| upsert | 44808 | 0.449 | 1.222 | 0.562 |
| commit | 44808 | 3.879 | 6.171 | 4.070 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 1.017 | 0.431 | 3.581 | 3740 | 4.00 | 1.98 | 0.99 | 1429 / 0 / 165 |
| 1 | 0.926 | 0.439 | 3.993 | 3850 | 3.90 | 2.17 | 1.01 | 9290 / 0 / 136 |
| 2 | 0.989 | 0.482 | 4.038 | 3810 | 3.89 | 2.11 | 1.02 | 9162 / 0 / 91 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 45250 | 1.010 |
| bgwriter.buffers_backend | 392 | 0.009 |
| bgwriter.buffers_checkpoint | 19901 | 0.444 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 5626 | 0.126 |
| db.blk_write_time | 12 | 0.000 |
| db.blks_hit | 873974 | 19.505 |
| db.blks_read | 44808 | 1.000 |
| db.xact_commit | 52031 | 1.161 |
| things_journal.heap_blks_hit | 141104 | 3.149 |
| things_journal.heap_blks_read | 44808 | 1.000 |
| things_journal.idx_blks_hit | 270603 | 6.039 |
| things_journal_seq.heap_blks_hit | 134238 | 2.996 |
| things_journal_seq.idx_blks_hit | 136909 | 3.055 |
| wal.wal_bytes | 388089772 | 8661.171 |
| wal.wal_fpi | 44960 | 1.003 |
| wal.wal_records | 307685 | 6.867 |
| wal.wal_sync | 11400 | 0.254 |
| wal.wal_sync_time | 23818 | 0.532 |
| wal.wal_write | 11400 | 0.254 |
| wal.wal_write_time | 680 | 0.015 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 7154 sampler passes, 56977 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 37250 | 65.4 % |
| active / LWLock:WALWrite | 10882 | 19.1 % |
| idle in transaction / Client:ClientRead | 4194 | 7.4 % |
| active / IO:WALSync | 2021 | 3.5 % |
| active / CPU | 1079 | 1.9 % |
| idle in transaction / CPU | 383 | 0.7 % |
| active / IO:DataFileRead | 374 | 0.7 % |
| active / Client:ClientRead | 283 | 0.5 % |
| idle / CPU | 182 | 0.3 % |
| active / LWLock:BufferContent | 111 | 0.2 % |
| idle in transaction / LWLock:WALWrite | 87 | 0.2 % |
| active / IO:WALWrite | 47 | 0.1 % |
| active / LWLock:XidGen | 30 | 0.1 % |
| active / Timeout:SpinDelay | 17 | 0.0 % |
| idle in transaction / IO:WALWrite | 10 | 0.0 % |
| idle / IO:DataFileRead | 9 | 0.0 % |
| idle / LWLock:BufferContent | 6 | 0.0 % |
| active / LWLock:LockManager | 4 | 0.0 % |
| active / IPC:BufferIO | 2 | 0.0 % |
| active / LWLock:BufferMapping | 1 | 0.0 % |
| active / LWLock:WALInsert | 1 | 0.0 % |
| active / Lock:extend | 1 | 0.0 % |
| idle / LWLock:XidGen | 1 | 0.0 % |
| idle in transaction / IO:DataFileRead | 1 | 0.0 % |
| idle in transaction / IO:WALSync | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
