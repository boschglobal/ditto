# W3 — sustained churn

- generated: 2026-10-06T10:05:15.020497Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-r2dbc-phased; PG append shape r2dbc-phased; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89904 (+0 snapshots), achieved 499.5/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 15000 | 5.17 | 10.00 | 605.36 | 0.0 MB | 8419.6 MB | 58.8 MB | 0 |
| 1 | 15024 | 6.02 | 9.89 | 78.22 | 20.9 MB | 8432.8 MB | 58.8 MB | 0 |
| 2 | 15016 | 6.36 | 10.77 | 66.14 | 19.4 MB | 8445.4 MB | 58.8 MB | 0 |
| 3 | 15024 | 4.80 | 9.13 | 39.13 | 20.1 MB | 8459.1 MB | 58.8 MB | 0 |
| 4 | 15008 | 4.48 | 6.95 | 40.89 | 19.3 MB | 8471.7 MB | 58.8 MB | 0 |
| 5 | 14832 | 4.55 | 6.93 | 29.96 | 18.8 MB | 8483.6 MB | 58.8 MB | 0 |

- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the end of the run: acquired=0 allocated=8 idle=8 pendingAcquire=0 maxAllocated=100
- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.01, 0.03, 0.03, 0.03, 0.04, 0.04, 0.14 ms
- journal heap placement of the run's 89904 rows: 8994 distinct pages; 11 rows (0.0 %) on 2 pages that existed before the run (blocks < 1011894, i.e. free space reused via the free space map), 89893 rows appended on 8992 new pages

Per-step client timings over the whole run (shape r2dbc-phased):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| acquire | 89904 | 0.069 | 0.194 | 0.140 |
| begin | 89904 | 0.546 | 1.407 | 0.661 |
| insert | 89904 | 0.699 | 1.787 | 0.848 |
| upsert | 89904 | 0.485 | 1.323 | 0.605 |
| commit | 89904 | 3.184 | 5.335 | 3.304 |
| release | 89904 | 0.007 | 0.035 | 0.011 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | acquire p50 ms | begin p50 ms | insert p50 ms | upsert p50 ms | commit p50 ms | release p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.081 | 0.542 | 0.719 | 0.510 | 3.166 | 0.009 | 3803 | 3.95 | 1.65 | 0.00 | 95 / 0 / 77 |
| 1 | 0.088 | 0.734 | 0.902 | 0.613 | 3.284 | 0.009 | 3808 | 3.94 | 1.63 | 0.02 | 1719 / 0 / 2106 |
| 2 | 0.088 | 0.688 | 0.884 | 0.601 | 3.871 | 0.008 | 3795 | 3.96 | 1.90 | 0.00 | 1809 / 0 / 1080 |
| 3 | 0.062 | 0.530 | 0.663 | 0.459 | 2.830 | 0.006 | 3689 | 4.07 | 1.48 | 0.00 | 1810 / 0 / 1202 |
| 4 | 0.052 | 0.445 | 0.586 | 0.425 | 2.756 | 0.005 | 3797 | 3.95 | 1.47 | 0.00 | 1804 / 0 / 1092 |
| 5 | 0.053 | 0.448 | 0.601 | 0.428 | 2.804 | 0.006 | 3796 | 3.91 | 1.44 | 0.00 | 1803 / 0 / 1131 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9869 | 0.110 |
| bgwriter.buffers_backend | 6688 | 0.074 |
| bgwriter.buffers_checkpoint | 9158 | 0.102 |
| bgwriter.checkpoint_sync_time | 15 | 0.000 |
| bgwriter.checkpoint_write_time | 269905 | 3.002 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_write_time | 432 | 0.005 |
| db.blks_hit | 1604113 | 17.843 |
| db.xact_commit | 104686 | 1.164 |
| things_journal.heap_blks_hit | 134664 | 1.498 |
| things_journal.idx_blks_hit | 545183 | 6.064 |
| things_journal_seq.heap_blks_hit | 271122 | 3.016 |
| things_journal_seq.idx_blks_hit | 272453 | 3.030 |
| wal.wal_bytes | 122343904 | 1360.828 |
| wal.wal_fpi | 284 | 0.003 |
| wal.wal_records | 624172 | 6.943 |
| wal.wal_sync | 22699 | 0.252 |
| wal.wal_sync_time | 36225 | 0.403 |
| wal.wal_write | 22699 | 0.252 |
| wal.wal_write_time | 807 | 0.009 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14658 sampler passes, 117120 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 79132 | 67.6 % |
| active / LWLock:WALWrite | 17351 | 14.8 % |
| idle in transaction / Client:ClientRead | 13829 | 11.8 % |
| active / IO:WALSync | 2951 | 2.5 % |
| active / CPU | 1726 | 1.5 % |
| idle in transaction / CPU | 1129 | 1.0 % |
| active / Client:ClientRead | 371 | 0.3 % |
| idle in transaction / LWLock:WALWrite | 199 | 0.2 % |
| active / LWLock:BufferContent | 138 | 0.1 % |
| idle / CPU | 76 | 0.1 % |
| active / LWLock:XidGen | 47 | 0.0 % |
| active / Lock:extend | 44 | 0.0 % |
| active / IO:WALWrite | 35 | 0.0 % |
| active / IO:DataFileExtend | 25 | 0.0 % |
| idle in transaction / IO:WALWrite | 20 | 0.0 % |
| active / LWLock:WALInsert | 13 | 0.0 % |
| idle in transaction / IO:WALSync | 12 | 0.0 % |
| active / LWLock:LockManager | 8 | 0.0 % |
| active / Timeout:SpinDelay | 6 | 0.0 % |
| idle in transaction / LWLock:BufferContent | 6 | 0.0 % |
| active / LWLock:BufferMapping | 1 | 0.0 % |
| idle in transaction / LWLock:XidGen | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
