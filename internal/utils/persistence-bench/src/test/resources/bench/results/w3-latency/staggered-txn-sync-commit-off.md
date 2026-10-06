# W3 — sustained churn

- generated: 2026-10-06T10:23:47.546088Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-txn-sync-off; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90008 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14625 | 0.64 | 1.33 | 25.79 | 0.0 MB | 8727.3 MB | 58.8 MB | 0 |
| 1 | 15019 | 0.65 | 1.47 | 15.72 | 20.7 MB | 8740.6 MB | 58.8 MB | 0 |
| 2 | 15017 | 0.58 | 1.42 | 17.23 | 19.3 MB | 8753.1 MB | 58.8 MB | 0 |
| 3 | 15011 | 0.61 | 1.54 | 21.16 | 22.2 MB | 8766.8 MB | 58.8 MB | 0 |
| 4 | 15019 | 0.57 | 1.48 | 33.20 | 21.8 MB | 8779.4 MB | 58.8 MB | 0 |
| 5 | 14934 | 0.79 | 1.47 | 16.72 | 18.2 MB | 8791.3 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 90008 rows: 9012 distinct pages; 4 rows (0.0 %) on 1 pages that existed before the run (blocks < 1047863, i.e. free space reused via the free space map), 90004 rows appended on 9011 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 90008 | 0.287 | 0.643 | 0.365 |
| upsert | 90008 | 0.179 | 0.440 | 0.239 |
| commit | 90008 | 0.146 | 0.342 | 0.190 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.280 | 0.184 | 0.150 | 147 | 99.58 | 2.50 | 0.00 | 1381 / 0 / 1653 |
| 1 | 0.302 | 0.186 | 0.153 | 152 | 98.80 | 2.11 | 0.01 | 1380 / 0 / 1689 |
| 2 | 0.272 | 0.164 | 0.135 | 154 | 97.49 | 1.91 | 0.00 | 1344 / 0 / 1567 |
| 3 | 0.289 | 0.170 | 0.137 | 150 | 100.13 | 2.15 | 0.00 | 87 / 0 / 1800 |
| 4 | 0.266 | 0.160 | 0.128 | 152 | 98.78 | 2.23 | 0.06 | 1812 / 0 / 1625 |
| 5 | 0.350 | 0.233 | 0.195 | 149 | 100.19 | 2.50 | 0.00 | 1833 / 0 / 1527 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9985 | 0.111 |
| bgwriter.buffers_backend | 9915 | 0.110 |
| bgwriter.buffers_checkpoint | 8009 | 0.089 |
| bgwriter.checkpoint_sync_time | 9 | 0.000 |
| bgwriter.checkpoint_write_time | 269856 | 2.998 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 64 | 0.001 |
| db.blk_write_time | 179 | 0.002 |
| db.blks_hit | 1603748 | 17.818 |
| db.blks_read | 22845 | 0.254 |
| db.xact_commit | 104886 | 1.165 |
| things_journal.heap_blks_hit | 115670 | 1.285 |
| things_journal.heap_blks_read | 22445 | 0.249 |
| things_journal.idx_blks_hit | 544512 | 6.050 |
| things_journal_seq.heap_blks_hit | 282039 | 3.133 |
| things_journal_seq.idx_blks_hit | 280258 | 3.114 |
| things_journal_seq.idx_blks_read | 396 | 0.004 |
| wal.wal_bytes | 126280193 | 1402.989 |
| wal.wal_fpi | 1078 | 0.012 |
| wal.wal_records | 604395 | 6.715 |
| wal.wal_sync | 910 | 0.010 |
| wal.wal_sync_time | 2032 | 0.023 |
| wal.wal_write | 15554 | 0.173 |
| wal.wal_write_time | 369 | 0.004 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14756 sampler passes, 118037 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 114658 | 97.1 % |
| idle in transaction / Client:ClientRead | 2815 | 2.4 % |
| active / CPU | 335 | 0.3 % |
| idle / CPU | 112 | 0.1 % |
| idle in transaction / CPU | 60 | 0.1 % |
| active / Client:ClientRead | 52 | 0.0 % |
| active / IO:DataFileExtend | 3 | 0.0 % |
| active / LWLock:BufferContent | 1 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
