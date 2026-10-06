# W3 — sustained churn

- generated: 2026-10-06T11:06:37.560149Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i2-r2dbc; PG append shape r2dbc; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 30008 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14599 | 1.71 | 2.27 | 642.83 | 0.0 MB | 7824.1 MB | 58.8 MB | 0 |
| 1 | 15000 | 1.70 | 2.06 | 19.79 | 19.1 MB | 7837.3 MB | 58.8 MB | 0 |

- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the end of the run: acquired=0 allocated=8 idle=8 pendingAcquire=0 maxAllocated=100
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 30008 rows: 3005 distinct pages; 9 rows (0.0 %) on 1 pages that existed before the run (blocks < 943491, i.e. free space reused via the free space map), 29999 rows appended on 3004 new pages

- per-step timings: n/a for shape r2dbc (total only)

Per-window step p50s, WAL flushes and buffer write-back:

| window | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|
| 0 | 13874 | 1.05 | 0.94 | 0.00 | 1306 / 0 / 1628 |
| 1 | 14836 | 1.01 | 1.02 | 0.00 | 1310 / 0 / 1693 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 3377 | 0.113 |
| bgwriter.buffers_backend | 3363 | 0.112 |
| bgwriter.buffers_checkpoint | 2738 | 0.091 |
| db.blk_write_time | 35 | 0.001 |
| db.blks_hit | 531397 | 17.709 |
| db.xact_commit | 35115 | 1.170 |
| things_journal.heap_blks_hit | 36120 | 1.204 |
| things_journal.idx_blks_hit | 181279 | 6.041 |
| things_journal_seq.heap_blks_hit | 89958 | 2.998 |
| things_journal_seq.idx_blks_hit | 92552 | 3.084 |
| wal.wal_bytes | 39652880 | 1321.410 |
| wal.wal_fpi | 2 | 0.000 |
| wal.wal_records | 195297 | 6.508 |
| wal.wal_sync | 29705 | 0.990 |
| wal.wal_sync_time | 29079 | 0.969 |
| wal.wal_write | 29705 | 0.990 |
| wal.wal_write_time | 127 | 0.004 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 5043 sampler passes, 39680 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 35725 | 90.0 % |
| active / IO:WALSync | 2642 | 6.7 % |
| idle in transaction / Client:ClientRead | 1134 | 2.9 % |
| active / LWLock:WALWrite | 90 | 0.2 % |
| idle in transaction / IO:WALSync | 36 | 0.1 % |
| idle in transaction / CPU | 20 | 0.1 % |
| active / CPU | 19 | 0.0 % |
| active / Client:ClientRead | 6 | 0.0 % |
| idle / CPU | 5 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 3 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
