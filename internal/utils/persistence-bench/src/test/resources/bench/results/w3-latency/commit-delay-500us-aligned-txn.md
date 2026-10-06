# W3 — sustained churn

- generated: 2026-10-06T10:58:46.805657Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 120 s per backend
- churn pid prefix thing:bench.churn-c-txn-commit-delay-500us; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 59728 (+0 snapshots), achieved 497.7/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14848 | 6.53 | 9.68 | 40.21 | 0.0 MB | 7667.1 MB | 58.8 MB | 0 |
| 1 | 15032 | 6.68 | 10.27 | 32.89 | 20.4 MB | 7680.8 MB | 58.8 MB | 0 |
| 2 | 15016 | 6.31 | 10.47 | 169.83 | 20.0 MB | 7693.5 MB | 58.8 MB | 0 |
| 3 | 14832 | 3.67 | 8.61 | 21.91 | 20.0 MB | 7706.7 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.03, 0.04, 0.04, 0.05, 0.08, 0.09, 0.10 ms
- journal heap placement of the run's 59728 rows: 5978 distinct pages; 3 rows (0.0 %) on 1 pages that existed before the run (blocks < 925495, i.e. free space reused via the free space map), 59725 rows appended on 5977 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 59728 | 0.971 | 2.155 | 1.120 |
| upsert | 59728 | 0.412 | 1.141 | 0.518 |
| commit | 59728 | 4.141 | 7.461 | 4.458 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 1.002 | 0.437 | 4.657 | 2316 | 6.42 | 2.10 | 0.00 | 8172 / 0 / 1369 |
| 1 | 1.125 | 0.479 | 4.782 | 2384 | 6.30 | 2.37 | 0.00 | 8180 / 0 / 1353 |
| 2 | 1.069 | 0.450 | 4.406 | 2332 | 6.44 | 2.32 | 0.00 | 8133 / 0 / 1199 |
| 3 | 0.793 | 0.316 | 2.476 | 2238 | 6.62 | 1.38 | 0.00 | 2691 / 0 / 564 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 6842 | 0.115 |
| bgwriter.buffers_backend | 4485 | 0.075 |
| bgwriter.buffers_checkpoint | 27176 | 0.455 |
| bgwriter.checkpoint_sync_time | 25 | 0.000 |
| bgwriter.checkpoint_write_time | 269107 | 4.506 |
| db.blk_read_time | 8 | 0.000 |
| db.blk_write_time | 283 | 0.005 |
| db.blks_hit | 1063899 | 17.812 |
| db.blks_read | 270 | 0.005 |
| db.xact_commit | 69366 | 1.161 |
| things_journal.heap_blks_hit | 86658 | 1.451 |
| things_journal.idx_blks_hit | 361668 | 6.055 |
| things_journal_seq.heap_blks_hit | 179961 | 3.013 |
| things_journal_seq.idx_blks_hit | 181947 | 3.046 |
| things_journal_seq.idx_blks_read | 270 | 0.005 |
| wal.wal_bytes | 83545775 | 1398.771 |
| wal.wal_fpi | 27 | 0.000 |
| wal.wal_records | 409760 | 6.860 |
| wal.wal_sync | 9270 | 0.155 |
| wal.wal_sync_time | 19015 | 0.318 |
| wal.wal_write | 9271 | 0.155 |
| wal.wal_write_time | 368 | 0.006 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 9551 sampler passes, 76208 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 48638 | 63.8 % |
| active / LWLock:WALWrite | 16890 | 22.2 % |
| idle in transaction / Client:ClientRead | 5060 | 6.6 % |
| active / CPU | 2309 | 3.0 % |
| active / IO:WALSync | 1788 | 2.3 % |
| idle in transaction / CPU | 577 | 0.8 % |
| active / Client:ClientRead | 367 | 0.5 % |
| idle in transaction / LWLock:WALWrite | 164 | 0.2 % |
| active / LWLock:BufferContent | 144 | 0.2 % |
| idle / CPU | 130 | 0.2 % |
| active / Lock:extend | 40 | 0.1 % |
| active / LWLock:XidGen | 35 | 0.0 % |
| active / IO:WALWrite | 29 | 0.0 % |
| active / Timeout:SpinDelay | 11 | 0.0 % |
| active / IO:DataFileExtend | 10 | 0.0 % |
| active / LWLock:WALInsert | 7 | 0.0 % |
| active / LWLock:LockManager | 3 | 0.0 % |
| idle / LWLock:BufferContent | 2 | 0.0 % |
| active / LWLock:BufferMapping | 1 | 0.0 % |
| idle in transaction / IO:DataFileExtend | 1 | 0.0 % |
| idle in transaction / IO:WALSync | 1 | 0.0 % |
| idle in transaction / IO:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
