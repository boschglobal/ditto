# W3 — sustained churn

- generated: 2026-10-06T11:03:18.215146Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i1-r2dbc; PG append shape r2dbc; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 30008 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14598 | 1.71 | 2.39 | 654.43 | 0.0 MB | 7745.1 MB | 58.8 MB | 0 |
| 1 | 15001 | 1.66 | 2.69 | 153.00 | 19.2 MB | 7758.3 MB | 58.8 MB | 0 |

- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the end of the run: acquired=0 allocated=8 idle=8 pendingAcquire=0 maxAllocated=100
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 30008 rows: 3004 distinct pages; 9 rows (0.0 %) on 1 pages that existed before the run (blocks < 934479, i.e. free space reused via the free space map), 29999 rows appended on 3003 new pages

- per-step timings: n/a for shape r2dbc (total only)

Per-window step p50s, WAL flushes and buffer write-back:

| window | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|
| 0 | 13482 | 1.08 | 0.97 | 0.00 | 4022 / 0 / 1632 |
| 1 | 15174 | 0.99 | 1.01 | 0.00 | 4010 / 0 / 1678 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 3365 | 0.112 |
| bgwriter.buffers_backend | 3355 | 0.112 |
| bgwriter.buffers_checkpoint | 8408 | 0.280 |
| db.blk_read_time | 0 | 0.000 |
| db.blk_write_time | 42 | 0.001 |
| db.blks_hit | 532517 | 17.746 |
| db.blks_read | 1 | 0.000 |
| db.xact_commit | 35112 | 1.170 |
| things_journal.heap_blks_hit | 36106 | 1.203 |
| things_journal.idx_blks_hit | 181249 | 6.040 |
| things_journal_seq.heap_blks_hit | 89994 | 2.999 |
| things_journal_seq.idx_blks_hit | 92594 | 3.086 |
| things_journal_seq.idx_blks_read | 1 | 0.000 |
| wal.wal_bytes | 39858393 | 1328.259 |
| wal.wal_fpi | 7 | 0.000 |
| wal.wal_records | 199517 | 6.649 |
| wal.wal_sync | 29439 | 0.981 |
| wal.wal_sync_time | 29122 | 0.970 |
| wal.wal_write | 29439 | 0.981 |
| wal.wal_write_time | 142 | 0.005 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 5038 sampler passes, 39624 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 35456 | 89.5 % |
| active / IO:WALSync | 2605 | 6.6 % |
| idle in transaction / Client:ClientRead | 1159 | 2.9 % |
| active / LWLock:WALWrite | 301 | 0.8 % |
| active / CPU | 38 | 0.1 % |
| idle in transaction / CPU | 26 | 0.1 % |
| idle in transaction / IO:WALSync | 25 | 0.1 % |
| active / Client:ClientRead | 8 | 0.0 % |
| idle / CPU | 4 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
