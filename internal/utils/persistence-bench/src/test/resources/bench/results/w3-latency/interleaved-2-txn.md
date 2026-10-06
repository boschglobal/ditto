# W3 — sustained churn

- generated: 2026-10-06T11:07:44.464972Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i2-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 30008 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14608 | 1.59 | 2.22 | 40.16 | 0.0 MB | 7850.2 MB | 58.8 MB | 0 |
| 1 | 14999 | 1.62 | 2.16 | 20.11 | 19.5 MB | 7863.1 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 30008 rows: 3002 distinct pages; 2 rows (0.0 %) on 1 pages that existed before the run (blocks < 946495, i.e. free space reused via the free space map), 30006 rows appended on 3001 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 30008 | 0.216 | 0.304 | 0.237 |
| upsert | 30008 | 0.156 | 0.192 | 0.162 |
| commit | 30008 | 1.219 | 1.749 | 1.275 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.222 | 0.160 | 1.192 | 14209 | 1.03 | 1.09 | 0.00 | 1311 / 0 / 1627 |
| 1 | 0.210 | 0.153 | 1.238 | 15114 | 0.99 | 1.15 | 0.00 | 1310 / 0 / 1652 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 3319 | 0.111 |
| bgwriter.buffers_backend | 3319 | 0.111 |
| bgwriter.buffers_checkpoint | 2741 | 0.091 |
| db.blk_write_time | 30 | 0.001 |
| db.blks_hit | 530784 | 17.688 |
| db.xact_commit | 35141 | 1.171 |
| things_journal.heap_blks_hit | 36017 | 1.200 |
| things_journal.idx_blks_hit | 182782 | 6.091 |
| things_journal_seq.heap_blks_hit | 89820 | 2.993 |
| things_journal_seq.idx_blks_hit | 92455 | 3.081 |
| wal.wal_bytes | 40067622 | 1335.231 |
| wal.wal_fpi | 46 | 0.002 |
| wal.wal_records | 211414 | 7.045 |
| wal.wal_sync | 29977 | 0.999 |
| wal.wal_sync_time | 33535 | 1.118 |
| wal.wal_write | 29977 | 0.999 |
| wal.wal_write_time | 136 | 0.005 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 5077 sampler passes, 40601 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 36597 | 90.1 % |
| active / IO:WALSync | 3115 | 7.7 % |
| idle in transaction / Client:ClientRead | 774 | 1.9 % |
| active / LWLock:WALWrite | 33 | 0.1 % |
| active / CPU | 28 | 0.1 % |
| idle in transaction / IO:WALSync | 22 | 0.1 % |
| idle / CPU | 13 | 0.0 % |
| active / Client:ClientRead | 8 | 0.0 % |
| idle in transaction / CPU | 8 | 0.0 % |
| active / IO:DataFileExtend | 1 | 0.0 % |
| active / IO:WALWrite | 1 | 0.0 % |
| idle in transaction / IO:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
