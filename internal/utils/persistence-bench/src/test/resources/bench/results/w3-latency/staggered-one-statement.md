# W3 — sustained churn

- generated: 2026-10-06T10:30:00.772699Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-one-statement; PG append shape one-statement; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90008 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14615 | 1.63 | 2.26 | 43.14 | 0.0 MB | 8882.1 MB | 58.8 MB | 0 |
| 1 | 15012 | 1.55 | 2.25 | 18.99 | 22.4 MB | 8895.3 MB | 58.8 MB | 0 |
| 2 | 15008 | 1.52 | 2.36 | 36.19 | 19.9 MB | 8908.1 MB | 58.8 MB | 0 |
| 3 | 15009 | 1.49 | 2.37 | 121.23 | 20.3 MB | 8922.4 MB | 58.8 MB | 0 |
| 4 | 15014 | 1.53 | 4.68 | 72.88 | 19.0 MB | 8934.1 MB | 58.8 MB | 0 |
| 5 | 14954 | 1.53 | 3.28 | 111.60 | 20.4 MB | 8947.0 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 90008 rows: 9003 distinct pages; 4 rows (0.0 %) on 1 pages that existed before the run (blocks < 1065877, i.e. free space reused via the free space map), 90004 rows appended on 9002 new pages

Per-step client timings over the whole run (shape one-statement):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| statement | 90008 | 1.537 | 2.484 | 1.767 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | statement p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|
| 0 | 1.620 | 14175 | 1.03 | 1.36 | 0.00 | 985 / 0 / 874 |
| 1 | 1.549 | 15091 | 0.99 | 1.26 | 0.03 | 841 / 0 / 2471 |
| 2 | 1.518 | 14963 | 1.00 | 1.25 | 0.00 | 1801 / 0 / 1653 |
| 3 | 1.480 | 14899 | 1.01 | 1.19 | 0.00 | 1798 / 0 / 1819 |
| 4 | 1.520 | 14004 | 1.07 | 1.29 | 0.00 | 1801 / 0 / 1503 |
| 5 | 1.526 | 14705 | 1.02 | 1.21 | 0.00 | 1794 / 0 / 13418 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 10066 | 0.112 |
| bgwriter.buffers_backend | 25083 | 0.279 |
| bgwriter.buffers_checkpoint | 9187 | 0.102 |
| bgwriter.checkpoint_sync_time | 15 | 0.000 |
| bgwriter.checkpoint_write_time | 269912 | 2.999 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 2 | 0.000 |
| db.blk_write_time | 121 | 0.001 |
| db.blks_hit | 1580327 | 17.558 |
| db.blks_read | 150 | 0.002 |
| db.xact_commit | 104969 | 1.166 |
| things_journal.heap_blks_hit | 108079 | 1.201 |
| things_journal.idx_blks_hit | 545098 | 6.056 |
| things_journal_seq.heap_blks_hit | 274217 | 3.047 |
| things_journal_seq.idx_blks_hit | 273592 | 3.040 |
| things_journal_seq.idx_blks_read | 150 | 0.002 |
| wal.wal_bytes | 125147466 | 1390.404 |
| wal.wal_fpi | 468 | 0.005 |
| wal.wal_records | 614968 | 6.832 |
| wal.wal_sync | 88503 | 0.983 |
| wal.wal_sync_time | 111480 | 1.239 |
| wal.wal_write | 88503 | 0.983 |
| wal.wal_write_time | 668 | 0.007 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14848 sampler passes, 118779 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 107250 | 90.3 % |
| active / IO:WALSync | 9909 | 8.3 % |
| active / LWLock:WALWrite | 1294 | 1.1 % |
| active / CPU | 234 | 0.2 % |
| idle / CPU | 49 | 0.0 % |
| active / Client:ClientRead | 20 | 0.0 % |
| active / IO:WALWrite | 13 | 0.0 % |
| idle / IO:WALSync | 5 | 0.0 % |
| active / LWLock:BufferContent | 4 | 0.0 % |
| active / Timeout:SpinDelay | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
