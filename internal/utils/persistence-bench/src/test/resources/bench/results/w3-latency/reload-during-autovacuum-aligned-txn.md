# W3 — sustained churn

- generated: 2026-10-06T10:47:23.149330Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 120 s per backend
- churn pid prefix thing:bench.churn-v-during-vacuum-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 59840 (+0 snapshots), achieved 498.7/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14968 | 3.67 | 7.15 | 43.02 | 0.0 MB | 7594.1 MB | 58.8 MB | 0 |
| 1 | 15016 | 3.97 | 6.31 | 28.37 | 130.5 MB | 7596.3 MB | 58.8 MB | 0 |
| 2 | 15016 | 4.93 | 7.98 | 39.61 | 132.2 MB | 7598.1 MB | 58.8 MB | 0 |
| 3 | 14840 | 3.56 | 8.13 | 42.80 | 128.3 MB | 7601.0 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.00, 0.00, 0.00, 0.15, 0.18, 0.18, 0.18 ms
- journal heap placement of the run's 59840 rows: 59840 distinct pages; 59840 rows (100.0 %) on 59840 pages that existed before the run (blocks < 920400, i.e. free space reused via the free space map), 0 rows appended on 0 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 59840 | 0.869 | 1.967 | 1.017 |
| upsert | 59840 | 0.325 | 0.848 | 0.404 |
| commit | 59840 | 2.621 | 5.245 | 2.964 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.738 | 0.295 | 2.503 | 3714 | 4.03 | 1.53 | 0.98 | 2583 / 0 / 4865 |
| 1 | 0.815 | 0.303 | 2.737 | 3846 | 3.90 | 1.42 | 1.01 | 2776 / 0 / 85159 |
| 2 | 1.047 | 0.386 | 3.264 | 3851 | 3.90 | 1.73 | 1.04 | 4732 / 0 / 82961 |
| 3 | 0.939 | 0.340 | 2.149 | 3882 | 3.82 | 1.34 | 1.01 | 11356 / 0 / 81539 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 61114 | 1.021 |
| bgwriter.buffers_backend | 259834 | 4.342 |
| bgwriter.buffers_checkpoint | 21475 | 0.359 |
| bgwriter.checkpoint_sync_time | 50 | 0.001 |
| bgwriter.checkpoint_write_time | 94254 | 1.575 |
| bgwriter.checkpoints_req | 1 | 0.000 |
| db.blk_read_time | 11937 | 0.199 |
| db.blk_write_time | 24 | 0.000 |
| db.blks_hit | 1100498 | 18.391 |
| db.blks_read | 59850 | 1.000 |
| db.xact_commit | 69810 | 1.167 |
| things_journal.heap_blks_hit | 124467 | 2.080 |
| things_journal.heap_blks_read | 59840 | 1.000 |
| things_journal.idx_blks_hit | 360659 | 6.027 |
| things_journal.idx_blks_read | 6 | 0.000 |
| things_journal_seq.heap_blks_hit | 179633 | 3.002 |
| things_journal_seq.idx_blks_hit | 182229 | 3.045 |
| things_journal_seq.idx_blks_read | 4 | 0.000 |
| wal.wal_bytes | 521075957 | 8707.820 |
| wal.wal_fpi | 60357 | 1.009 |
| wal.wal_records | 412439 | 6.892 |
| wal.wal_sync | 15303 | 0.256 |
| wal.wal_sync_time | 23093 | 0.386 |
| wal.wal_write | 15303 | 0.256 |
| wal.wal_write_time | 638 | 0.011 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 9888 sampler passes, 78919 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 56944 | 72.2 % |
| active / LWLock:WALWrite | 11245 | 14.2 % |
| idle in transaction / Client:ClientRead | 5081 | 6.4 % |
| active / IO:WALSync | 2165 | 2.7 % |
| active / IO:DataFileRead | 1135 | 1.4 % |
| active / CPU | 1134 | 1.4 % |
| idle in transaction / CPU | 431 | 0.5 % |
| active / Client:ClientRead | 265 | 0.3 % |
| idle in transaction / LWLock:WALWrite | 131 | 0.2 % |
| active / LWLock:BufferContent | 130 | 0.2 % |
| idle / CPU | 97 | 0.1 % |
| active / IO:WALWrite | 62 | 0.1 % |
| active / LWLock:XidGen | 35 | 0.0 % |
| idle in transaction / IO:WALWrite | 18 | 0.0 % |
| idle / IO:DataFileRead | 14 | 0.0 % |
| active / Timeout:SpinDelay | 13 | 0.0 % |
| active / IPC:BufferIO | 6 | 0.0 % |
| active / Lock:extend | 4 | 0.0 % |
| idle in transaction / IO:WALSync | 4 | 0.0 % |
| active / IO:DataFileExtend | 1 | 0.0 % |
| active / IPC:XactGroupUpdate | 1 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |
| idle / IO:WALSync | 1 | 0.0 % |
| idle in transaction / LWLock:BufferContent | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
