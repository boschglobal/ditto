# W3 — sustained churn

- generated: 2026-10-06T10:26:54.411887Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-no-hwm; PG append shape no-hwm; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90007 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14633 | 0.81 | 1.67 | 32.95 | 0.0 MB | 8804.2 MB | 58.8 MB | 0 |
| 1 | 15013 | 0.74 | 1.71 | 22.15 | 16.8 MB | 8817.3 MB | 58.8 MB | 0 |
| 2 | 15009 | 0.81 | 1.71 | 21.73 | 16.8 MB | 8830.4 MB | 58.8 MB | 0 |
| 3 | 15007 | 1.23 | 2.10 | 20.53 | 16.7 MB | 8843.3 MB | 58.8 MB | 0 |
| 4 | 15011 | 1.65 | 2.29 | 18.03 | 16.8 MB | 8856.4 MB | 58.8 MB | 0 |
| 5 | 14959 | 1.66 | 2.29 | 25.34 | 16.3 MB | 8868.5 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 90007 rows: 9004 distinct pages; 4 rows (0.0 %) on 1 pages that existed before the run (blocks < 1056874, i.e. free space reused via the free space map), 90003 rows appended on 9003 new pages

Per-step client timings over the whole run (shape no-hwm):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 90007 | 0.211 | 0.478 | 0.255 |
| commit | 90007 | 0.912 | 1.816 | 1.015 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|
| 0 | 0.200 | 0.591 | 14249 | 1.03 | 0.54 | 0.00 | 1850 / 0 / 1607 |
| 1 | 0.182 | 0.553 | 15100 | 0.99 | 0.54 | 0.00 | 1844 / 0 / 1671 |
| 2 | 0.204 | 0.597 | 15107 | 0.99 | 0.56 | 0.00 | 1840 / 0 / 1682 |
| 3 | 0.212 | 0.987 | 14998 | 1.00 | 0.83 | 0.00 | 1844 / 0 / 1633 |
| 4 | 0.226 | 1.409 | 14558 | 1.03 | 1.27 | 0.00 | 1848 / 0 / 1683 |
| 5 | 0.219 | 1.422 | 15000 | 1.00 | 1.28 | 0.00 | 1838 / 0 / 1550 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9919 | 0.110 |
| bgwriter.buffers_backend | 9917 | 0.110 |
| bgwriter.buffers_checkpoint | 11236 | 0.125 |
| db.blk_write_time | 87 | 0.001 |
| db.blks_hit | 1029221 | 11.435 |
| db.xact_commit | 105117 | 1.168 |
| things_journal.heap_blks_hit | 108053 | 1.200 |
| things_journal.idx_blks_hit | 543423 | 6.038 |
| wal.wal_bytes | 103982846 | 1155.275 |
| wal.wal_fpi | 1 | 0.000 |
| wal.wal_records | 363994 | 4.044 |
| wal.wal_sync | 89625 | 0.996 |
| wal.wal_sync_time | 75408 | 0.838 |
| wal.wal_write | 89625 | 0.996 |
| wal.wal_write_time | 524 | 0.006 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 15000 sampler passes, 119987 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 111226 | 92.7 % |
| active / IO:WALSync | 6768 | 5.6 % |
| idle in transaction / Client:ClientRead | 1562 | 1.3 % |
| active / LWLock:WALWrite | 217 | 0.2 % |
| active / CPU | 112 | 0.1 % |
| idle / CPU | 43 | 0.0 % |
| active / Client:ClientRead | 29 | 0.0 % |
| idle in transaction / CPU | 19 | 0.0 % |
| active / IO:WALWrite | 4 | 0.0 % |
| idle in transaction / IO:WALSync | 4 | 0.0 % |
| active / IO:DataFileExtend | 1 | 0.0 % |
| idle in transaction / IO:WALWrite | 1 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
