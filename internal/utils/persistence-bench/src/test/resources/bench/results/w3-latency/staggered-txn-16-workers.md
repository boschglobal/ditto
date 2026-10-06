# W3 — sustained churn

- generated: 2026-10-06T10:39:22.304293Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 16 workers x 50 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-txn-w16; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90016 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14624 | 1.59 | 2.31 | 68.87 | 0.0 MB | 9113.8 MB | 58.8 MB | 0 |
| 1 | 15016 | 1.59 | 2.35 | 31.00 | 18.8 MB | 9126.8 MB | 58.8 MB | 0 |
| 2 | 15015 | 1.57 | 3.76 | 156.42 | 22.1 MB | 9140.2 MB | 58.8 MB | 0 |
| 3 | 15011 | 1.61 | 2.75 | 25.93 | 19.1 MB | 9153.2 MB | 58.8 MB | 0 |
| 4 | 15009 | 1.59 | 2.68 | 82.61 | 18.9 MB | 9166.1 MB | 58.8 MB | 0 |
| 5 | 14948 | 1.55 | 2.96 | 158.74 | 18.2 MB | 9178.1 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 32.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00, 16.00, 18.00, 20.00, 22.00, 24.00, 26.00, 28.00, 30.00 ms
- journal heap placement of the run's 90016 rows: 9010 distinct pages; 0 rows (0.0 %) on 0 pages that existed before the run (blocks < 1092860, i.e. free space reused via the free space map), 90016 rows appended on 9010 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 90016 | 0.215 | 0.338 | 0.260 |
| upsert | 90016 | 0.151 | 0.190 | 0.162 |
| commit | 90016 | 1.210 | 2.001 | 1.459 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.227 | 0.153 | 1.193 | 14379 | 1.02 | 1.09 | 0.01 | 33288 / 0 / 1631 |
| 1 | 0.221 | 0.152 | 1.208 | 14701 | 1.02 | 1.09 | 0.00 | 27021 / 0 / 1377 |
| 2 | 0.213 | 0.149 | 1.194 | 14435 | 1.04 | 1.11 | 0.04 | 339 / 0 / 2009 |
| 3 | 0.209 | 0.154 | 1.236 | 15156 | 0.99 | 1.16 | 0.00 | 1818 / 0 / 1653 |
| 4 | 0.208 | 0.150 | 1.226 | 14574 | 1.03 | 1.17 | 0.00 | 1818 / 0 / 1663 |
| 5 | 0.209 | 0.148 | 1.195 | 14549 | 1.03 | 1.10 | 0.00 | 1811 / 0 / 1530 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 10000 | 0.111 |
| bgwriter.buffers_backend | 9954 | 0.111 |
| bgwriter.buffers_checkpoint | 66262 | 0.736 |
| bgwriter.checkpoint_sync_time | 24 | 0.000 |
| bgwriter.checkpoint_write_time | 269985 | 2.999 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 4 | 0.000 |
| db.blk_write_time | 85 | 0.001 |
| db.blks_hit | 1582485 | 17.580 |
| db.blks_read | 86 | 0.001 |
| db.xact_commit | 104800 | 1.164 |
| things_journal.heap_blks_hit | 108211 | 1.202 |
| things_journal.idx_blks_hit | 545065 | 6.055 |
| things_journal_seq.heap_blks_hit | 272686 | 3.029 |
| things_journal_seq.idx_blks_hit | 273293 | 3.036 |
| things_journal_seq.idx_blks_read | 86 | 0.001 |
| wal.wal_bytes | 120985676 | 1344.046 |
| wal.wal_fpi | 772 | 0.009 |
| wal.wal_records | 552932 | 6.143 |
| wal.wal_sync | 88449 | 0.983 |
| wal.wal_sync_time | 99046 | 1.100 |
| wal.wal_write | 88449 | 0.983 |
| wal.wal_write_time | 512 | 0.006 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14651 sampler passes, 234340 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 221715 | 94.6 % |
| active / IO:WALSync | 8975 | 3.8 % |
| idle in transaction / Client:ClientRead | 1945 | 0.8 % |
| active / LWLock:WALWrite | 1427 | 0.6 % |
| active / CPU | 107 | 0.0 % |
| idle in transaction / IO:WALSync | 81 | 0.0 % |
| idle / CPU | 27 | 0.0 % |
| idle in transaction / CPU | 22 | 0.0 % |
| active / Client:ClientRead | 15 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 12 | 0.0 % |
| active / LWLock:BufferContent | 4 | 0.0 % |
| active / IO:DataFileExtend | 3 | 0.0 % |
| active / Lock:extend | 2 | 0.0 % |
| active / IO:WALWrite | 1 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |
| active / LWLock:WALInsert | 1 | 0.0 % |
| active / LWLock:XidGen | 1 | 0.0 % |
| idle in transaction / IO:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
