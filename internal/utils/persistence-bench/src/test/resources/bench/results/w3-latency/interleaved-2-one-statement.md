# W3 — sustained churn

- generated: 2026-10-06T11:05:31.249864Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i2-one-statement; PG append shape one-statement; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 30008 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14614 | 1.53 | 2.24 | 44.42 | 0.0 MB | 7797.9 MB | 58.8 MB | 0 |
| 1 | 14998 | 1.70 | 2.33 | 22.13 | 19.9 MB | 7811.1 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 30008 rows: 3006 distinct pages; 6 rows (0.0 %) on 1 pages that existed before the run (blocks < 940486, i.e. free space reused via the free space map), 30002 rows appended on 3005 new pages

Per-step client timings over the whole run (shape one-statement):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| statement | 30008 | 1.625 | 2.284 | 1.668 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | statement p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|
| 0 | 1.523 | 14189 | 1.03 | 1.25 | 0.01 | 193 / 0 / 1654 |
| 1 | 1.698 | 15074 | 0.99 | 1.39 | 0.00 | 1311 / 0 / 1691 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 3389 | 0.113 |
| bgwriter.buffers_backend | 3385 | 0.113 |
| bgwriter.buffers_checkpoint | 1626 | 0.054 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 0 | 0.000 |
| db.blk_write_time | 40 | 0.001 |
| db.blks_hit | 529852 | 17.657 |
| db.blks_read | 1 | 0.000 |
| db.xact_commit | 35076 | 1.169 |
| things_journal.heap_blks_hit | 36068 | 1.202 |
| things_journal.idx_blks_hit | 181318 | 6.042 |
| things_journal.idx_blks_read | 1 | 0.000 |
| things_journal_seq.heap_blks_hit | 90111 | 3.003 |
| things_journal_seq.idx_blks_hit | 92651 | 3.088 |
| wal.wal_bytes | 42395349 | 1412.802 |
| wal.wal_fpi | 203 | 0.007 |
| wal.wal_records | 206550 | 6.883 |
| wal.wal_sync | 29899 | 0.996 |
| wal.wal_sync_time | 39630 | 1.321 |
| wal.wal_write | 29899 | 0.996 |
| wal.wal_write_time | 151 | 0.005 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 5012 sampler passes, 40090 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 36549 | 91.2 % |
| active / IO:WALSync | 3406 | 8.5 % |
| active / LWLock:WALWrite | 102 | 0.3 % |
| active / CPU | 17 | 0.0 % |
| active / Client:ClientRead | 6 | 0.0 % |
| idle / CPU | 4 | 0.0 % |
| idle / IO:WALSync | 3 | 0.0 % |
| idle / LWLock:WALWrite | 2 | 0.0 % |
| active / LWLock:BufferContent | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
