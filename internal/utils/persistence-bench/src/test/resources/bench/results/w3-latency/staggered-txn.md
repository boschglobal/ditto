# W3 — sustained churn

- generated: 2026-10-06T10:20:41.155120Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90008 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14618 | 0.88 | 2.60 | 155.27 | 0.0 MB | 8650.4 MB | 58.8 MB | 0 |
| 1 | 15017 | 1.00 | 3.19 | 70.82 | 18.6 MB | 8663.2 MB | 58.8 MB | 0 |
| 2 | 15025 | 1.07 | 1.95 | 58.65 | 18.5 MB | 8676.0 MB | 58.8 MB | 0 |
| 3 | 15009 | 1.39 | 1.87 | 109.87 | 18.9 MB | 8689.0 MB | 58.8 MB | 0 |
| 4 | 15008 | 1.13 | 4.16 | 159.35 | 18.7 MB | 8702.1 MB | 58.8 MB | 0 |
| 5 | 14942 | 0.97 | 2.07 | 68.12 | 18.2 MB | 8714.3 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 90008 rows: 9003 distinct pages; 5 rows (0.0 %) on 1 pages that existed before the run (blocks < 1038861, i.e. free space reused via the free space map), 90003 rows appended on 9002 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 90008 | 0.215 | 0.452 | 0.276 |
| upsert | 90008 | 0.134 | 0.265 | 0.154 |
| commit | 90008 | 0.668 | 1.686 | 1.049 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.185 | 0.116 | 0.566 | 13858 | 1.06 | 0.67 | 0.00 | 1123 / 0 / 1626 |
| 1 | 0.217 | 0.125 | 0.626 | 14754 | 1.02 | 0.67 | 0.00 | 1382 / 0 / 1642 |
| 2 | 0.228 | 0.139 | 0.669 | 14979 | 1.00 | 0.65 | 0.00 | 1380 / 0 / 1629 |
| 3 | 0.218 | 0.154 | 1.007 | 14791 | 1.01 | 0.88 | 0.00 | 1383 / 0 / 1665 |
| 4 | 0.236 | 0.139 | 0.697 | 14235 | 1.05 | 0.77 | 0.00 | 1380 / 0 / 1675 |
| 5 | 0.210 | 0.122 | 0.612 | 14907 | 1.00 | 0.65 | 0.00 | 1375 / 0 / 1559 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9849 | 0.109 |
| bgwriter.buffers_backend | 9836 | 0.109 |
| bgwriter.buffers_checkpoint | 8152 | 0.091 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_write_time | 170 | 0.002 |
| db.blks_hit | 1575958 | 17.509 |
| db.xact_commit | 105207 | 1.169 |
| things_journal.heap_blks_hit | 108178 | 1.202 |
| things_journal.idx_blks_hit | 544873 | 6.054 |
| things_journal_seq.heap_blks_hit | 271105 | 3.012 |
| things_journal_seq.idx_blks_hit | 272455 | 3.027 |
| wal.wal_bytes | 115721613 | 1285.681 |
| wal.wal_fpi | 101 | 0.001 |
| wal.wal_records | 564653 | 6.273 |
| wal.wal_sync | 88094 | 0.979 |
| wal.wal_sync_time | 62807 | 0.698 |
| wal.wal_write | 88094 | 0.979 |
| wal.wal_write_time | 644 | 0.007 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 15083 sampler passes, 120650 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 110272 | 91.4 % |
| active / IO:WALSync | 6050 | 5.0 % |
| idle in transaction / Client:ClientRead | 2319 | 1.9 % |
| active / LWLock:WALWrite | 1641 | 1.4 % |
| active / CPU | 208 | 0.2 % |
| idle in transaction / CPU | 48 | 0.0 % |
| active / Client:ClientRead | 41 | 0.0 % |
| idle / CPU | 34 | 0.0 % |
| idle in transaction / IO:WALSync | 15 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 6 | 0.0 % |
| active / IO:WALWrite | 4 | 0.0 % |
| active / IO:DataFileExtend | 3 | 0.0 % |
| active / Lock:extend | 3 | 0.0 % |
| idle in transaction / IO:WALWrite | 3 | 0.0 % |
| active / LWLock:BufferContent | 1 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |
| active / LWLock:XidGen | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
