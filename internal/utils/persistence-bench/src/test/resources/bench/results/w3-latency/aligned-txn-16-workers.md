# W3 — sustained churn

- generated: 2026-10-06T10:08:21.342627Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 16 workers x 50 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-txn-w16; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89872 (+0 snapshots), achieved 499.3/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14960 | 2.16 | 3.97 | 36.65 | 0.0 MB | 8496.5 MB | 58.8 MB | 0 |
| 1 | 15024 | 2.49 | 4.28 | 41.84 | 18.8 MB | 8509.6 MB | 58.8 MB | 0 |
| 2 | 15008 | 2.38 | 4.52 | 27.71 | 18.8 MB | 8522.6 MB | 58.8 MB | 0 |
| 3 | 15008 | 2.13 | 3.94 | 20.28 | 18.7 MB | 8535.6 MB | 58.8 MB | 0 |
| 4 | 15008 | 2.68 | 4.69 | 48.02 | 23.3 MB | 8548.5 MB | 58.8 MB | 0 |
| 5 | 14864 | 2.89 | 4.89 | 28.60 | 18.6 MB | 8560.9 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 32.0 ms period: 0.00, 0.20, 1.03, 1.08, 1.63, 1.63, 2.38, 3.03, 3.15, 3.43, 3.63, 3.93, 4.16, 4.23, 4.59, 5.72 ms
- journal heap placement of the run's 89872 rows: 8991 distinct pages; 9 rows (0.0 %) on 1 pages that existed before the run (blocks < 1020886, i.e. free space reused via the free space map), 89863 rows appended on 8990 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 89872 | 0.297 | 0.618 | 0.368 |
| upsert | 89872 | 0.215 | 0.520 | 0.259 |
| commit | 89872 | 1.852 | 3.646 | 2.029 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.270 | 0.196 | 1.600 | 6231 | 2.40 | 0.97 | 0.01 | 1805 / 0 / 1646 |
| 1 | 0.304 | 0.220 | 1.869 | 5570 | 2.69 | 1.11 | 0.00 | 1809 / 0 / 1647 |
| 2 | 0.290 | 0.211 | 1.783 | 5868 | 2.56 | 1.04 | 0.00 | 1809 / 0 / 1626 |
| 3 | 0.276 | 0.202 | 1.567 | 6469 | 2.32 | 0.93 | 0.00 | 1533 / 0 / 1396 |
| 4 | 0.318 | 0.227 | 2.030 | 5301 | 2.83 | 1.19 | 0.06 | 282 / 0 / 1871 |
| 5 | 0.326 | 0.235 | 2.236 | 4976 | 2.98 | 1.35 | 0.00 | 1818 / 0 / 1539 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9982 | 0.111 |
| bgwriter.buffers_backend | 9725 | 0.108 |
| bgwriter.buffers_checkpoint | 9177 | 0.102 |
| bgwriter.checkpoint_sync_time | 14 | 0.000 |
| bgwriter.checkpoint_write_time | 269939 | 3.004 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 7 | 0.000 |
| db.blk_write_time | 217 | 0.002 |
| db.blks_hit | 1583236 | 17.617 |
| db.blks_read | 524 | 0.006 |
| db.xact_commit | 104632 | 1.164 |
| things_journal.heap_blks_hit | 111503 | 1.241 |
| things_journal.idx_blks_hit | 544244 | 6.056 |
| things_journal_seq.heap_blks_hit | 271925 | 3.026 |
| things_journal_seq.idx_blks_hit | 272618 | 3.033 |
| things_journal_seq.idx_blks_read | 524 | 0.006 |
| wal.wal_bytes | 121733985 | 1354.526 |
| wal.wal_fpi | 974 | 0.011 |
| wal.wal_records | 552860 | 6.152 |
| wal.wal_sync | 34415 | 0.383 |
| wal.wal_sync_time | 37341 | 0.415 |
| wal.wal_write | 34416 | 0.383 |
| wal.wal_write_time | 476 | 0.005 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14626 sampler passes, 233738 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 214810 | 91.9 % |
| active / LWLock:WALWrite | 10145 | 4.3 % |
| idle in transaction / Client:ClientRead | 4534 | 1.9 % |
| active / IO:WALSync | 3137 | 1.3 % |
| active / CPU | 627 | 0.3 % |
| idle in transaction / CPU | 148 | 0.1 % |
| active / Client:ClientRead | 138 | 0.1 % |
| idle / CPU | 90 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 51 | 0.0 % |
| active / LWLock:BufferContent | 16 | 0.0 % |
| active / IO:WALWrite | 14 | 0.0 % |
| active / IO:DataFileExtend | 8 | 0.0 % |
| idle in transaction / IO:WALSync | 8 | 0.0 % |
| active / Lock:extend | 4 | 0.0 % |
| idle in transaction / IO:WALWrite | 4 | 0.0 % |
| active / LWLock:WALInsert | 1 | 0.0 % |
| active / Timeout:SpinDelay | 1 | 0.0 % |
| idle / IO:WALSync | 1 | 0.0 % |
| idle / LWLock:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
