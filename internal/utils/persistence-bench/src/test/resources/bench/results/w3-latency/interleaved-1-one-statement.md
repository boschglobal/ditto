# W3 — sustained churn

- generated: 2026-10-06T11:04:25.087901Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i1-one-statement; PG append shape one-statement; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 30007 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14610 | 1.60 | 2.26 | 45.02 | 0.0 MB | 7771.5 MB | 58.8 MB | 0 |
| 1 | 15000 | 1.67 | 2.53 | 113.81 | 19.8 MB | 7784.7 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 30007 rows: 3005 distinct pages; 1 rows (0.0 %) on 1 pages that existed before the run (blocks < 937482, i.e. free space reused via the free space map), 30006 rows appended on 3004 new pages

Per-step client timings over the whole run (shape one-statement):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| statement | 30007 | 1.635 | 2.350 | 1.751 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | statement p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|
| 0 | 1.596 | 14181 | 1.03 | 1.31 | 0.00 | 4018 / 0 / 1664 |
| 1 | 1.667 | 14912 | 1.01 | 1.43 | 0.00 | 4005 / 0 / 1683 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 3433 | 0.114 |
| bgwriter.buffers_backend | 3393 | 0.113 |
| bgwriter.buffers_checkpoint | 8259 | 0.275 |
| bgwriter.checkpoint_sync_time | 23 | 0.001 |
| bgwriter.checkpoint_write_time | 269358 | 8.977 |
| db.blk_read_time | 3 | 0.000 |
| db.blk_write_time | 33 | 0.001 |
| db.blks_hit | 530300 | 17.673 |
| db.blks_read | 237 | 0.008 |
| db.xact_commit | 35103 | 1.170 |
| things_journal.heap_blks_hit | 36071 | 1.202 |
| things_journal.idx_blks_hit | 181311 | 6.042 |
| things_journal_seq.heap_blks_hit | 90305 | 3.009 |
| things_journal_seq.idx_blks_hit | 92756 | 3.091 |
| things_journal_seq.idx_blks_read | 237 | 0.008 |
| wal.wal_bytes | 40939989 | 1364.348 |
| wal.wal_fpi | 21 | 0.001 |
| wal.wal_records | 200797 | 6.692 |
| wal.wal_sync | 29734 | 0.991 |
| wal.wal_sync_time | 40842 | 1.361 |
| wal.wal_write | 29734 | 0.991 |
| wal.wal_write_time | 139 | 0.005 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 5039 sampler passes, 40305 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 36444 | 90.4 % |
| active / IO:WALSync | 3539 | 8.8 % |
| active / LWLock:WALWrite | 266 | 0.7 % |
| active / CPU | 42 | 0.1 % |
| idle / CPU | 8 | 0.0 % |
| active / LWLock:BufferContent | 3 | 0.0 % |
| active / Client:ClientRead | 2 | 0.0 % |
| active / IO:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
