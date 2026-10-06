# W3 — sustained churn

- generated: 2026-10-06T10:02:08.785888Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-r2dbc; PG append shape r2dbc; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89912 (+0 snapshots), achieved 499.5/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 15000 | 5.76 | 10.10 | 724.85 | 0.0 MB | 8342.5 MB | 58.8 MB | 0 |
| 1 | 15017 | 6.26 | 10.40 | 44.96 | 19.2 MB | 8355.6 MB | 58.8 MB | 0 |
| 2 | 15007 | 6.41 | 10.43 | 111.07 | 19.3 MB | 8368.7 MB | 58.8 MB | 0 |
| 3 | 15008 | 6.19 | 10.15 | 57.21 | 19.2 MB | 8381.7 MB | 58.8 MB | 0 |
| 4 | 15008 | 6.31 | 10.33 | 22.71 | 19.1 MB | 8394.7 MB | 58.8 MB | 0 |
| 5 | 14872 | 6.10 | 10.65 | 26.84 | 18.5 MB | 8406.6 MB | 58.8 MB | 0 |

- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the end of the run: acquired=0 allocated=8 idle=8 pendingAcquire=0 maxAllocated=100
- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.00, 0.01, 0.03, 0.03, 0.04, 0.06, 0.06 ms
- journal heap placement of the run's 89912 rows: 8993 distinct pages; 3 rows (0.0 %) on 1 pages that existed before the run (blocks < 1002902, i.e. free space reused via the free space map), 89909 rows appended on 8992 new pages

- per-step timings: n/a for shape r2dbc (total only)

Per-window step p50s, WAL flushes and buffer write-back:

| window | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|
| 0 | 3785 | 3.96 | 1.89 | 0.01 | 1789 / 0 / 1130 |
| 1 | 3787 | 3.97 | 1.98 | 0.00 | 1800 / 0 / 1101 |
| 2 | 3798 | 3.95 | 1.99 | 0.00 | 1791 / 0 / 1090 |
| 3 | 3668 | 4.09 | 1.80 | 0.00 | 1798 / 0 / 1090 |
| 4 | 3789 | 3.96 | 1.81 | 0.00 | 1792 / 0 / 1126 |
| 5 | 3791 | 3.92 | 1.88 | 0.00 | 1787 / 0 / 1006 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9904 | 0.110 |
| bgwriter.buffers_backend | 6543 | 0.073 |
| bgwriter.buffers_checkpoint | 10881 | 0.121 |
| db.blk_read_time | 9 | 0.000 |
| db.blk_write_time | 454 | 0.005 |
| db.blks_hit | 1606936 | 17.872 |
| db.blks_read | 312 | 0.003 |
| db.xact_commit | 104307 | 1.160 |
| things_journal.heap_blks_hit | 135646 | 1.509 |
| things_journal.idx_blks_hit | 545238 | 6.064 |
| things_journal_seq.heap_blks_hit | 271927 | 3.024 |
| things_journal_seq.idx_blks_hit | 272835 | 3.034 |
| things_journal_seq.idx_blks_read | 310 | 0.003 |
| wal.wal_bytes | 119034310 | 1323.898 |
| wal.wal_fpi | 107 | 0.001 |
| wal.wal_records | 596836 | 6.638 |
| wal.wal_sync | 22624 | 0.252 |
| wal.wal_sync_time | 42807 | 0.476 |
| wal.wal_write | 22624 | 0.252 |
| wal.wal_write_time | 872 | 0.010 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14270 sampler passes, 113984 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 69533 | 61.0 % |
| active / LWLock:WALWrite | 21571 | 18.9 % |
| idle in transaction / Client:ClientRead | 14251 | 12.5 % |
| active / IO:WALSync | 3688 | 3.2 % |
| active / CPU | 2179 | 1.9 % |
| idle in transaction / CPU | 1343 | 1.2 % |
| active / Client:ClientRead | 504 | 0.4 % |
| idle in transaction / LWLock:WALWrite | 336 | 0.3 % |
| active / LWLock:BufferContent | 181 | 0.2 % |
| idle / CPU | 123 | 0.1 % |
| active / Lock:extend | 57 | 0.1 % |
| active / LWLock:XidGen | 55 | 0.0 % |
| active / IO:WALWrite | 43 | 0.0 % |
| active / IO:DataFileExtend | 30 | 0.0 % |
| idle in transaction / IO:WALWrite | 25 | 0.0 % |
| idle in transaction / IO:WALSync | 17 | 0.0 % |
| active / Timeout:SpinDelay | 14 | 0.0 % |
| active / LWLock:WALInsert | 11 | 0.0 % |
| idle in transaction / LWLock:BufferContent | 10 | 0.0 % |
| active / LWLock:LockManager | 5 | 0.0 % |
| idle in transaction / IO:DataFileExtend | 3 | 0.0 % |
| idle in transaction / LWLock:XidGen | 2 | 0.0 % |
| active / IPC:XactGroupUpdate | 1 | 0.0 % |
| active / LWLock:XactSLRU | 1 | 0.0 % |
| idle in transaction / Lock:extend | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
