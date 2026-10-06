# W3 — sustained churn

- generated: 2026-10-06T10:33:07.583940Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-r2dbc; PG append shape r2dbc; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90005 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14614 | 1.78 | 5.66 | 693.58 | 0.0 MB | 8960.0 MB | 58.8 MB | 0 |
| 1 | 15015 | 1.72 | 4.95 | 129.50 | 22.3 MB | 8973.2 MB | 58.8 MB | 0 |
| 2 | 15008 | 1.70 | 4.88 | 121.05 | 22.1 MB | 8985.7 MB | 58.8 MB | 0 |
| 3 | 15006 | 1.70 | 4.04 | 149.62 | 25.8 MB | 8999.4 MB | 58.8 MB | 0 |
| 4 | 15005 | 1.70 | 2.41 | 120.39 | 22.4 MB | 9011.7 MB | 58.8 MB | 0 |
| 5 | 14965 | 1.74 | 5.16 | 152.51 | 23.4 MB | 9023.6 MB | 58.8 MB | 0 |

- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the end of the run: acquired=0 allocated=8 idle=8 pendingAcquire=0 maxAllocated=100
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 90005 rows: 9063 distinct pages; 370 rows (0.4 %) on 85 pages that existed before the run (blocks < 1074879, i.e. free space reused via the free space map), 89635 rows appended on 8978 new pages

- per-step timings: n/a for shape r2dbc (total only)

Per-window step p50s, WAL flushes and buffer write-back:

| window | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|
| 0 | 13505 | 1.08 | 1.05 | 0.00 | 1800 / 0 / 5270 |
| 1 | 14514 | 1.03 | 1.05 | 0.00 | 1797 / 0 / 1680 |
| 2 | 14439 | 1.04 | 1.03 | 0.00 | 1801 / 0 / 1605 |
| 3 | 14392 | 1.04 | 0.97 | 0.00 | 1800 / 0 / 1759 |
| 4 | 15259 | 0.98 | 0.96 | 0.01 | 531 / 0 / 505 |
| 5 | 14704 | 1.02 | 1.04 | 0.06 | 22043 / 0 / 2530 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9864 | 0.110 |
| bgwriter.buffers_backend | 13458 | 0.150 |
| bgwriter.buffers_checkpoint | 34146 | 0.379 |
| bgwriter.checkpoint_sync_time | 17 | 0.000 |
| bgwriter.checkpoint_write_time | 269907 | 2.999 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 24145 | 0.268 |
| db.blk_write_time | 237 | 0.003 |
| db.blks_hit | 2869061 | 31.877 |
| db.blks_read | 707615 | 7.862 |
| db.xact_commit | 105008 | 1.167 |
| things_journal.heap_blks_hit | 1392321 | 15.469 |
| things_journal.heap_blks_read | 707473 | 7.860 |
| things_journal.idx_blks_hit | 546568 | 6.073 |
| things_journal.idx_blks_read | 24 | 0.000 |
| things_journal_seq.heap_blks_hit | 273390 | 3.037 |
| things_journal_seq.idx_blks_hit | 273470 | 3.038 |
| things_journal_seq.idx_blks_read | 118 | 0.001 |
| wal.wal_bytes | 143480142 | 1594.135 |
| wal.wal_fpi | 1015 | 0.011 |
| wal.wal_records | 921130 | 10.234 |
| wal.wal_sync | 87242 | 0.969 |
| wal.wal_sync_time | 88952 | 0.988 |
| wal.wal_write | 87243 | 0.969 |
| wal.wal_write_time | 713 | 0.008 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14870 sampler passes, 118288 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 104123 | 88.0 % |
| active / IO:WALSync | 7804 | 6.6 % |
| idle in transaction / Client:ClientRead | 4038 | 3.4 % |
| active / LWLock:WALWrite | 1712 | 1.4 % |
| active / CPU | 255 | 0.2 % |
| idle in transaction / CPU | 169 | 0.1 % |
| idle in transaction / IO:WALSync | 61 | 0.1 % |
| active / Client:ClientRead | 51 | 0.0 % |
| idle / CPU | 30 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 23 | 0.0 % |
| active / IO:WALWrite | 9 | 0.0 % |
| active / LWLock:BufferContent | 6 | 0.0 % |
| idle in transaction / IO:WALWrite | 3 | 0.0 % |
| active / LWLock:XidGen | 2 | 0.0 % |
| idle in transaction / LWLock:BufferContent | 1 | 0.0 % |
| idle in transaction / LWLock:WALInsert | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
