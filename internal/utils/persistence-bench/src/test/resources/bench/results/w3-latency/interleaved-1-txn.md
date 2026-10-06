# W3 — sustained churn

- generated: 2026-10-06T11:02:11.954599Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i1-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 30008 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14618 | 1.58 | 2.51 | 68.08 | 0.0 MB | 7719.2 MB | 58.8 MB | 0 |
| 1 | 14999 | 1.54 | 2.05 | 117.51 | 19.3 MB | 7732.1 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 30008 rows: 3008 distinct pages; 8 rows (0.0 %) on 1 pages that existed before the run (blocks < 931472, i.e. free space reused via the free space map), 30000 rows appended on 3007 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 30008 | 0.236 | 0.377 | 0.271 |
| upsert | 30008 | 0.162 | 0.207 | 0.170 |
| commit | 30008 | 1.134 | 1.682 | 1.252 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.245 | 0.165 | 1.140 | 14121 | 1.04 | 1.05 | 0.00 | 4019 / 0 / 1607 |
| 1 | 0.228 | 0.159 | 1.124 | 15045 | 1.00 | 1.02 | 0.00 | 4014 / 0 / 1654 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 3308 | 0.110 |
| bgwriter.buffers_backend | 3301 | 0.110 |
| bgwriter.buffers_checkpoint | 8396 | 0.280 |
| db.blk_write_time | 47 | 0.002 |
| db.blks_hit | 531216 | 17.702 |
| db.xact_commit | 35114 | 1.170 |
| things_journal.heap_blks_hit | 36075 | 1.202 |
| things_journal.idx_blks_hit | 182762 | 6.090 |
| things_journal_seq.heap_blks_hit | 90028 | 3.000 |
| things_journal_seq.idx_blks_hit | 92645 | 3.087 |
| wal.wal_bytes | 39993789 | 1332.771 |
| wal.wal_fpi | 77 | 0.003 |
| wal.wal_records | 207329 | 6.909 |
| wal.wal_sync | 29781 | 0.992 |
| wal.wal_sync_time | 30786 | 1.026 |
| wal.wal_write | 29781 | 0.992 |
| wal.wal_write_time | 216 | 0.007 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 5050 sampler passes, 40395 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 36531 | 90.4 % |
| active / IO:WALSync | 2900 | 7.2 % |
| idle in transaction / Client:ClientRead | 691 | 1.7 % |
| active / LWLock:WALWrite | 192 | 0.5 % |
| active / CPU | 40 | 0.1 % |
| active / Client:ClientRead | 14 | 0.0 % |
| idle in transaction / IO:WALSync | 8 | 0.0 % |
| idle / CPU | 7 | 0.0 % |
| idle in transaction / CPU | 7 | 0.0 % |
| active / LWLock:BufferContent | 2 | 0.0 % |
| active / IO:DataFileExtend | 1 | 0.0 % |
| active / IO:WALWrite | 1 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
