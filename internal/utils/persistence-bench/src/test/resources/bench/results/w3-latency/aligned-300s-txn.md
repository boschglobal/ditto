# W3 — sustained churn

- generated: 2026-10-06T09:38:51.530211Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 300 s per backend
- churn pid prefix thing:bench.churn-u1-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 149808 (+0 snapshots), achieved 499.4/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14936 | 4.16 | 6.54 | 31.74 | 0.0 MB | 7855.2 MB | 58.8 MB | 8 |
| 1 | 15024 | 4.12 | 6.54 | 22.83 | 72.2 MB | 7862.2 MB | 58.8 MB | 8 |
| 2 | 15016 | 4.07 | 6.39 | 19.62 | 18.4 MB | 7874.9 MB | 58.8 MB | 8 |
| 3 | 15016 | 4.06 | 6.34 | 17.33 | 21.6 MB | 7887.8 MB | 58.8 MB | 8 |
| 4 | 15008 | 4.08 | 6.64 | 24.09 | 18.6 MB | 7900.9 MB | 58.8 MB | 8 |
| 5 | 15009 | 3.58 | 6.50 | 19.61 | 18.2 MB | 7913.2 MB | 58.8 MB | 8 |
| 6 | 15015 | 4.05 | 6.96 | 61.28 | 18.2 MB | 7925.5 MB | 58.8 MB | 8 |
| 7 | 15008 | 4.80 | 8.83 | 89.28 | 26.2 MB | 7939.1 MB | 58.8 MB | 8 |
| 8 | 15008 | 4.16 | 6.88 | 27.20 | 19.3 MB | 7953.7 MB | 58.8 MB | 8 |
| 9 | 14768 | 3.74 | 6.79 | 50.47 | 18.3 MB | 7966.6 MB | 58.8 MB | 8 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.00, 0.16, 0.16, 0.17, 0.19, 0.20, 0.24 ms
- journal heap placement of the run's 149808 rows: 35172 distinct pages; 22433 rows (15.0 %) on 22433 pages that existed before the run (blocks < 948190, i.e. free space reused via the free space map), 127375 rows appended on 12739 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 149808 | 0.632 | 1.555 | 0.753 |
| upsert | 149808 | 0.338 | 0.770 | 0.395 |
| commit | 149808 | 2.902 | 5.325 | 3.076 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 1.066 | 0.352 | 2.562 | 3782 | 3.95 | 1.41 | 0.99 | 1154 / 0 / 178 |
| 1 | 0.751 | 0.346 | 2.804 | 3861 | 3.89 | 1.49 | 0.53 | 1154 / 0 / 759 |
| 2 | 0.565 | 0.346 | 2.982 | 3803 | 3.95 | 1.54 | 0.00 | 965 / 0 / 1118 |
| 3 | 0.562 | 0.347 | 2.949 | 3802 | 3.95 | 1.54 | 0.04 | 489 / 0 / 1492 |
| 4 | 0.580 | 0.345 | 2.977 | 3681 | 4.08 | 1.55 | 0.00 | 3020 / 0 / 1367 |
| 5 | 0.550 | 0.302 | 2.648 | 3795 | 3.95 | 1.48 | 0.00 | 3007 / 0 / 1253 |
| 6 | 0.593 | 0.331 | 2.982 | 3799 | 3.95 | 1.62 | 0.00 | 3016 / 0 / 1248 |
| 7 | 0.721 | 0.375 | 3.465 | 3811 | 3.94 | 1.88 | 0.07 | 3018 / 0 / 1268 |
| 8 | 0.580 | 0.331 | 3.061 | 3668 | 4.09 | 1.58 | 0.00 | 3015 / 0 / 1351 |
| 9 | 0.599 | 0.313 | 2.667 | 3837 | 3.85 | 1.50 | 0.00 | 2983 / 0 / 1124 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 38447 | 0.257 |
| bgwriter.buffers_backend | 11158 | 0.074 |
| bgwriter.buffers_checkpoint | 22029 | 0.147 |
| bgwriter.checkpoint_sync_time | 24 | 0.000 |
| bgwriter.checkpoint_write_time | 269919 | 1.802 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 7256 | 0.048 |
| db.blk_write_time | 420 | 0.003 |
| db.blks_hit | 2702578 | 18.040 |
| db.blks_read | 25631 | 0.171 |
| db.xact_commit | 174976 | 1.168 |
| things_journal.heap_blks_hit | 260812 | 1.741 |
| things_journal.heap_blks_read | 22689 | 0.151 |
| things_journal.idx_blks_hit | 910794 | 6.080 |
| things_journal.idx_blks_read | 1108 | 0.007 |
| things_journal_seq.heap_blks_hit | 450299 | 3.006 |
| things_journal_seq.heap_blks_read | 1490 | 0.010 |
| things_journal_seq.idx_blks_hit | 452019 | 3.017 |
| things_journal_seq.idx_blks_read | 344 | 0.002 |
| wal.wal_bytes | 371200268 | 2477.840 |
| wal.wal_fpi | 24239 | 0.162 |
| wal.wal_records | 926498 | 6.185 |
| wal.wal_sync | 37839 | 0.253 |
| wal.wal_sync_time | 58960 | 0.394 |
| wal.wal_write | 37840 | 0.253 |
| wal.wal_write_time | 981 | 0.007 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 24991 sampler passes, 199751 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 145836 | 73.0 % |
| active / LWLock:WALWrite | 28572 | 14.3 % |
| idle in transaction / Client:ClientRead | 13748 | 6.9 % |
| active / IO:WALSync | 5628 | 2.8 % |
| active / CPU | 2595 | 1.3 % |
| idle in transaction / CPU | 1150 | 0.6 % |
| active / Client:ClientRead | 576 | 0.3 % |
| active / IO:DataFileRead | 511 | 0.3 % |
| idle in transaction / LWLock:WALWrite | 365 | 0.2 % |
| idle / CPU | 224 | 0.1 % |
| active / LWLock:BufferContent | 213 | 0.1 % |
| active / IO:WALWrite | 106 | 0.1 % |
| active / Lock:extend | 67 | 0.0 % |
| idle in transaction / IO:WALWrite | 35 | 0.0 % |
| active / LWLock:XidGen | 30 | 0.0 % |
| active / IO:DataFileExtend | 29 | 0.0 % |
| active / LWLock:LockManager | 15 | 0.0 % |
| active / Timeout:SpinDelay | 15 | 0.0 % |
| active / IPC:BufferIO | 14 | 0.0 % |
| idle in transaction / IO:WALSync | 14 | 0.0 % |
| active / LWLock:WALInsert | 3 | 0.0 % |
| idle / LWLock:BufferContent | 2 | 0.0 % |
| idle / LWLock:XidGen | 2 | 0.0 % |
| active / LWLock:XactSLRU | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
