# W3 — sustained churn

- generated: 2026-10-06T10:11:26.905184Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 4 workers x 200 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-txn-w4; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89840 (+0 snapshots), achieved 499.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14948 | 3.55 | 5.64 | 32.11 | 0.0 MB | 8573.9 MB | 58.8 MB | 0 |
| 1 | 15024 | 3.27 | 5.51 | 38.89 | 19.0 MB | 8587.0 MB | 58.8 MB | 0 |
| 2 | 15013 | 2.75 | 5.36 | 30.66 | 18.7 MB | 8599.6 MB | 58.8 MB | 0 |
| 3 | 15011 | 2.97 | 5.39 | 33.61 | 19.2 MB | 8613.2 MB | 58.8 MB | 0 |
| 4 | 15012 | 2.91 | 5.45 | 43.33 | 18.7 MB | 8625.8 MB | 58.8 MB | 0 |
| 5 | 14832 | 3.06 | 5.45 | 23.27 | 18.2 MB | 8637.8 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 8.0 ms period: 0.00, 0.00, 0.00, 0.01 ms
- journal heap placement of the run's 89840 rows: 8986 distinct pages; 7 rows (0.0 %) on 1 pages that existed before the run (blocks < 1029876, i.e. free space reused via the free space map), 89833 rows appended on 8985 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 89840 | 0.462 | 0.955 | 0.518 |
| upsert | 89840 | 0.299 | 0.649 | 0.338 |
| commit | 89840 | 2.211 | 4.390 | 2.455 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.485 | 0.301 | 2.542 | 7289 | 2.05 | 1.40 | 0.01 | 1829 / 0 / 1559 |
| 1 | 0.517 | 0.317 | 2.291 | 7551 | 1.99 | 1.31 | 0.00 | 1823 / 0 / 1643 |
| 2 | 0.445 | 0.295 | 1.884 | 7546 | 1.99 | 1.21 | 0.00 | 1827 / 0 / 1574 |
| 3 | 0.441 | 0.298 | 2.124 | 7548 | 1.99 | 1.31 | 0.00 | 1822 / 0 / 1693 |
| 4 | 0.446 | 0.291 | 2.074 | 7551 | 1.99 | 1.32 | 0.00 | 1822 / 0 / 1563 |
| 5 | 0.443 | 0.296 | 2.244 | 7515 | 1.97 | 1.35 | 0.00 | 1820 / 0 / 1489 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9853 | 0.110 |
| bgwriter.buffers_backend | 9521 | 0.106 |
| bgwriter.buffers_checkpoint | 11062 | 0.123 |
| db.blk_write_time | 274 | 0.003 |
| db.blks_hit | 1578868 | 17.574 |
| db.xact_commit | 104975 | 1.168 |
| things_journal.heap_blks_hit | 111932 | 1.246 |
| things_journal.idx_blks_hit | 542470 | 6.038 |
| things_journal_seq.heap_blks_hit | 275074 | 3.062 |
| things_journal_seq.idx_blks_hit | 272253 | 3.030 |
| wal.wal_bytes | 117422925 | 1307.023 |
| wal.wal_fpi | 103 | 0.001 |
| wal.wal_records | 565140 | 6.291 |
| wal.wal_sync | 45000 | 0.501 |
| wal.wal_sync_time | 59213 | 0.659 |
| wal.wal_write | 45000 | 0.501 |
| wal.wal_write_time | 600 | 0.007 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 15027 sampler passes, 60032 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 34326 | 57.2 % |
| active / LWLock:WALWrite | 9731 | 16.2 % |
| idle in transaction / Client:ClientRead | 9378 | 15.6 % |
| active / IO:WALSync | 4869 | 8.1 % |
| active / CPU | 801 | 1.3 % |
| idle in transaction / CPU | 351 | 0.6 % |
| active / Client:ClientRead | 185 | 0.3 % |
| idle in transaction / LWLock:WALWrite | 106 | 0.2 % |
| idle / CPU | 78 | 0.1 % |
| active / LWLock:BufferContent | 46 | 0.1 % |
| active / IO:WALWrite | 37 | 0.1 % |
| idle in transaction / IO:WALWrite | 29 | 0.0 % |
| active / Lock:extend | 27 | 0.0 % |
| active / LWLock:XidGen | 22 | 0.0 % |
| idle in transaction / IO:WALSync | 16 | 0.0 % |
| active / IO:DataFileExtend | 15 | 0.0 % |
| active / LWLock:LockManager | 5 | 0.0 % |
| active / Timeout:SpinDelay | 5 | 0.0 % |
| active / LWLock:WALInsert | 3 | 0.0 % |
| active / LWLock:ProcArray | 1 | 0.0 % |
| idle / LWLock:BufferContent | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
