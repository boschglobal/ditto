# W3 — sustained churn

- generated: 2026-10-06T09:55:57.263864Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-no-hwm; PG append shape no-hwm; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89880 (+0 snapshots), achieved 499.3/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14984 | 2.86 | 5.28 | 15.86 | 0.0 MB | 8187.6 MB | 58.8 MB | 0 |
| 1 | 15008 | 2.58 | 4.91 | 16.26 | 16.8 MB | 8200.8 MB | 58.8 MB | 0 |
| 2 | 15008 | 3.93 | 7.42 | 31.71 | 16.6 MB | 8213.5 MB | 58.8 MB | 0 |
| 3 | 15016 | 4.79 | 8.08 | 33.52 | 17.0 MB | 8226.9 MB | 58.8 MB | 0 |
| 4 | 15016 | 4.62 | 7.93 | 38.91 | 16.6 MB | 8239.6 MB | 58.8 MB | 0 |
| 5 | 14848 | 4.89 | 8.06 | 24.31 | 16.2 MB | 8251.6 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.13, 0.16, 0.16, 0.27, 0.75, 1.86, 2.01 ms
- journal heap placement of the run's 89880 rows: 8993 distinct pages; 31 rows (0.0 %) on 4 pages that existed before the run (blocks < 984922, i.e. free space reused via the free space map), 89849 rows appended on 8989 new pages

Per-step client timings over the whole run (shape no-hwm):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 89880 | 0.574 | 1.857 | 0.780 |
| commit | 89880 | 3.222 | 5.764 | 3.311 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|
| 0 | 0.388 | 2.409 | 5364 | 2.79 | 1.20 | 0.00 | 2344 / 0 / 1544 |
| 1 | 0.380 | 2.147 | 5751 | 2.61 | 1.10 | 0.00 | 2342 / 0 / 1520 |
| 2 | 0.527 | 3.246 | 4871 | 3.08 | 1.56 | 0.00 | 2342 / 0 / 1503 |
| 3 | 0.811 | 3.849 | 4404 | 3.41 | 1.84 | 0.00 | 2341 / 0 / 1499 |
| 4 | 0.821 | 3.660 | 4111 | 3.65 | 1.88 | 0.00 | 2346 / 0 / 1438 |
| 5 | 0.914 | 3.874 | 4219 | 3.52 | 1.99 | 0.00 | 2330 / 0 / 1307 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9875 | 0.110 |
| bgwriter.buffers_backend | 8811 | 0.098 |
| bgwriter.buffers_checkpoint | 14199 | 0.158 |
| db.blk_write_time | 398 | 0.004 |
| db.blks_hit | 1043374 | 11.609 |
| db.xact_commit | 104531 | 1.163 |
| things_journal.heap_blks_hit | 120822 | 1.344 |
| things_journal.idx_blks_hit | 544479 | 6.058 |
| things_journal_seq.heap_blks_hit | 579 | 0.006 |
| wal.wal_bytes | 103908043 | 1156.075 |
| wal.wal_fpi | 37 | 0.000 |
| wal.wal_records | 363552 | 4.045 |
| wal.wal_sync | 28720 | 0.320 |
| wal.wal_sync_time | 44611 | 0.496 |
| wal.wal_write | 28720 | 0.320 |
| wal.wal_write_time | 774 | 0.009 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14539 sampler passes, 116171 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 86999 | 74.9 % |
| active / LWLock:WALWrite | 18505 | 15.9 % |
| idle in transaction / Client:ClientRead | 4326 | 3.7 % |
| active / IO:WALSync | 4204 | 3.6 % |
| active / CPU | 933 | 0.8 % |
| idle in transaction / CPU | 276 | 0.2 % |
| idle / CPU | 242 | 0.2 % |
| active / Client:ClientRead | 226 | 0.2 % |
| idle in transaction / LWLock:WALWrite | 187 | 0.2 % |
| active / LWLock:BufferContent | 81 | 0.1 % |
| active / IO:WALWrite | 40 | 0.0 % |
| active / IO:DataFileExtend | 35 | 0.0 % |
| active / Lock:extend | 31 | 0.0 % |
| active / LWLock:XidGen | 26 | 0.0 % |
| active / Timeout:SpinDelay | 18 | 0.0 % |
| idle in transaction / IO:WALWrite | 14 | 0.0 % |
| idle in transaction / IO:WALSync | 12 | 0.0 % |
| active / LWLock:LockManager | 5 | 0.0 % |
| idle / LWLock:BufferContent | 4 | 0.0 % |
| active / LWLock:WALInsert | 2 | 0.0 % |
| idle / LWLock:WALWrite | 2 | 0.0 % |
| idle / LWLock:XidGen | 2 | 0.0 % |
| idle / IO:WALSync | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
