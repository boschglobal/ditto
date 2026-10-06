# W3 — sustained churn

- generated: 2026-10-06T10:56:40.791200Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 120 s per backend
- churn pid prefix thing:bench.churn-c-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 59816 (+0 snapshots), achieved 498.5/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14928 | 5.39 | 8.73 | 26.88 | 0.0 MB | 7614.3 MB | 58.8 MB | 0 |
| 1 | 15032 | 5.81 | 9.11 | 57.86 | 27.2 MB | 7628.2 MB | 58.8 MB | 0 |
| 2 | 15008 | 5.21 | 8.58 | 48.94 | 19.1 MB | 7640.9 MB | 58.8 MB | 0 |
| 3 | 14848 | 5.54 | 8.61 | 48.48 | 18.9 MB | 7653.6 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.00, 0.00, 0.03, 0.12, 0.16, 0.17, 0.20 ms
- journal heap placement of the run's 59816 rows: 13877 distinct pages; 8880 rows (14.8 %) on 8782 pages that existed before the run (blocks < 920400, i.e. free space reused via the free space map), 50936 rows appended on 5095 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 59816 | 0.998 | 2.278 | 1.180 |
| upsert | 59816 | 0.479 | 1.245 | 0.580 |
| commit | 59816 | 3.807 | 5.976 | 3.922 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.953 | 0.424 | 3.746 | 3675 | 4.07 | 1.89 | 0.59 | 12402 / 0 / 675 |
| 1 | 1.105 | 0.569 | 3.978 | 3790 | 3.96 | 2.01 | 0.07 | 4207 / 0 / 1365 |
| 2 | 1.000 | 0.486 | 3.572 | 3793 | 3.96 | 1.87 | 0.00 | 2951 / 0 / 1269 |
| 3 | 0.965 | 0.465 | 3.914 | 3753 | 3.95 | 2.00 | 0.00 | 7581 / 0 / 1243 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 14207 | 0.238 |
| bgwriter.buffers_backend | 4552 | 0.076 |
| bgwriter.buffers_checkpoint | 27696 | 0.463 |
| db.blk_read_time | 622 | 0.010 |
| db.blk_write_time | 255 | 0.004 |
| db.blks_hit | 1085026 | 18.139 |
| db.blks_read | 8285 | 0.139 |
| db.xact_commit | 69403 | 1.160 |
| things_journal.heap_blks_hit | 103885 | 1.737 |
| things_journal.heap_blks_read | 8285 | 0.139 |
| things_journal.idx_blks_hit | 365639 | 6.113 |
| things_journal_seq.heap_blks_hit | 179719 | 3.005 |
| things_journal_seq.idx_blks_hit | 181909 | 3.041 |
| wal.wal_bytes | 152450533 | 2548.658 |
| wal.wal_fpi | 9848 | 0.165 |
| wal.wal_records | 419109 | 7.007 |
| wal.wal_sync | 15011 | 0.251 |
| wal.wal_sync_time | 29146 | 0.487 |
| wal.wal_write | 15011 | 0.251 |
| wal.wal_write_time | 576 | 0.010 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 9501 sampler passes, 75856 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 49790 | 65.6 % |
| active / LWLock:WALWrite | 14434 | 19.0 % |
| idle in transaction / Client:ClientRead | 6123 | 8.1 % |
| active / IO:WALSync | 2623 | 3.5 % |
| active / CPU | 1167 | 1.5 % |
| idle in transaction / CPU | 582 | 0.8 % |
| active / Client:ClientRead | 404 | 0.5 % |
| idle in transaction / LWLock:WALWrite | 192 | 0.3 % |
| idle / CPU | 183 | 0.2 % |
| active / LWLock:BufferContent | 144 | 0.2 % |
| active / Lock:extend | 43 | 0.1 % |
| active / IO:WALWrite | 38 | 0.1 % |
| active / IO:DataFileRead | 32 | 0.0 % |
| active / LWLock:XidGen | 29 | 0.0 % |
| idle in transaction / IO:WALWrite | 20 | 0.0 % |
| active / IO:DataFileExtend | 12 | 0.0 % |
| idle / LWLock:BufferContent | 12 | 0.0 % |
| active / Timeout:SpinDelay | 11 | 0.0 % |
| idle in transaction / IO:WALSync | 4 | 0.0 % |
| idle in transaction / LWLock:BufferContent | 4 | 0.0 % |
| active / IPC:BufferIO | 2 | 0.0 % |
| active / LWLock:LockManager | 2 | 0.0 % |
| idle / LWLock:XidGen | 2 | 0.0 % |
| idle / Lock:extend | 2 | 0.0 % |
| idle / IO:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
