# W3 — sustained churn

- generated: 2026-10-06T09:59:03.114792Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-one-statement; PG append shape one-statement; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89880 (+0 snapshots), achieved 499.3/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14984 | 5.12 | 8.11 | 37.18 | 0.0 MB | 8264.9 MB | 58.8 MB | 0 |
| 1 | 15024 | 5.22 | 8.67 | 23.16 | 20.0 MB | 8278.1 MB | 58.8 MB | 0 |
| 2 | 15016 | 5.30 | 8.75 | 41.09 | 19.6 MB | 8290.9 MB | 58.8 MB | 0 |
| 3 | 15011 | 5.35 | 8.38 | 33.47 | 24.0 MB | 8305.0 MB | 58.8 MB | 0 |
| 4 | 15021 | 5.20 | 8.73 | 35.29 | 19.1 MB | 8316.8 MB | 58.8 MB | 0 |
| 5 | 14824 | 5.15 | 8.77 | 36.19 | 19.5 MB | 8329.6 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.00, 0.00, 0.04, 0.10, 0.10, 0.10, 0.11 ms
- journal heap placement of the run's 89880 rows: 8992 distinct pages; 9 rows (0.0 %) on 1 pages that existed before the run (blocks < 993911, i.e. free space reused via the free space map), 89871 rows appended on 8991 new pages

Per-step client timings over the whole run (shape one-statement):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| statement | 89880 | 5.198 | 8.528 | 5.406 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | statement p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|
| 0 | 5.085 | 3659 | 4.10 | 2.15 | 0.00 | 2349 / 0 / 1202 |
| 1 | 5.195 | 3786 | 3.97 | 2.18 | 0.00 | 2340 / 0 / 1166 |
| 2 | 5.282 | 3786 | 3.96 | 2.28 | 0.00 | 1064 / 0 / 559 |
| 3 | 5.329 | 3786 | 3.97 | 2.21 | 0.05 | 980 / 0 / 2002 |
| 4 | 5.180 | 3659 | 4.10 | 2.16 | 0.00 | 1798 / 0 / 1088 |
| 5 | 5.121 | 3823 | 3.88 | 2.21 | 0.00 | 1784 / 0 / 1160 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9984 | 0.111 |
| bgwriter.buffers_backend | 7177 | 0.080 |
| bgwriter.buffers_checkpoint | 10439 | 0.116 |
| bgwriter.checkpoint_sync_time | 17 | 0.000 |
| bgwriter.checkpoint_write_time | 269556 | 2.999 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_write_time | 446 | 0.005 |
| db.blks_hit | 1600652 | 17.809 |
| db.xact_commit | 103898 | 1.156 |
| things_journal.heap_blks_hit | 133090 | 1.481 |
| things_journal.idx_blks_hit | 545460 | 6.069 |
| things_journal_seq.heap_blks_hit | 270948 | 3.015 |
| things_journal_seq.idx_blks_hit | 272207 | 3.029 |
| wal.wal_bytes | 126762523 | 1410.353 |
| wal.wal_fpi | 801 | 0.009 |
| wal.wal_records | 621766 | 6.918 |
| wal.wal_sync | 22499 | 0.250 |
| wal.wal_sync_time | 49475 | 0.550 |
| wal.wal_write | 22499 | 0.250 |
| wal.wal_write_time | 1083 | 0.012 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 13902 sampler passes, 111071 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 74570 | 67.1 % |
| active / LWLock:WALWrite | 28437 | 25.6 % |
| active / IO:WALSync | 4625 | 4.2 % |
| active / CPU | 2017 | 1.8 % |
| active / LWLock:BufferContent | 567 | 0.5 % |
| idle / CPU | 332 | 0.3 % |
| active / Client:ClientRead | 162 | 0.1 % |
| active / LWLock:XidGen | 92 | 0.1 % |
| active / Lock:extend | 76 | 0.1 % |
| active / IO:WALWrite | 61 | 0.1 % |
| idle / LWLock:WALWrite | 37 | 0.0 % |
| active / IO:DataFileExtend | 29 | 0.0 % |
| idle / LWLock:BufferContent | 23 | 0.0 % |
| active / LWLock:WALInsert | 13 | 0.0 % |
| idle / IO:WALSync | 11 | 0.0 % |
| active / LWLock:LockManager | 9 | 0.0 % |
| active / Timeout:SpinDelay | 4 | 0.0 % |
| idle / LWLock:XidGen | 2 | 0.0 % |
| active / IPC:ProcArrayGroupUpdate | 1 | 0.0 % |
| active / LWLock:ProcArray | 1 | 0.0 % |
| idle / IO:WALWrite | 1 | 0.0 % |
| idle / LWLock:WALInsert | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
