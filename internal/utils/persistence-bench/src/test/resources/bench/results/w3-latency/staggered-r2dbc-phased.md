# W3 — sustained churn

- generated: 2026-10-06T10:36:15.225077Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-r2dbc-phased; PG append shape r2dbc-phased; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90007 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14625 | 1.76 | 4.99 | 797.16 | 0.0 MB | 9036.7 MB | 58.8 MB | 0 |
| 1 | 15021 | 1.71 | 3.13 | 29.06 | 19.3 MB | 9049.8 MB | 58.8 MB | 0 |
| 2 | 15013 | 1.70 | 3.28 | 54.53 | 19.2 MB | 9062.6 MB | 58.8 MB | 0 |
| 3 | 15011 | 1.70 | 5.39 | 150.22 | 19.4 MB | 9075.8 MB | 58.8 MB | 0 |
| 4 | 15012 | 1.70 | 4.25 | 110.03 | 19.2 MB | 9088.7 MB | 58.8 MB | 0 |
| 5 | 14943 | 1.74 | 5.23 | 154.70 | 18.7 MB | 9100.7 MB | 58.8 MB | 0 |

- r2dbc pool (production ConnectionPoolFactory, shipped pool defaults) at the end of the run: acquired=0 allocated=8 idle=8 pendingAcquire=0 maxAllocated=100
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 90007 rows: 9004 distinct pages; 9 rows (0.0 %) on 1 pages that existed before the run (blocks < 1083857, i.e. free space reused via the free space map), 89998 rows appended on 9003 new pages

Per-step client timings over the whole run (shape r2dbc-phased):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| acquire | 90007 | 0.019 | 0.055 | 0.080 |
| begin | 90007 | 0.165 | 0.393 | 0.214 |
| insert | 90007 | 0.226 | 0.437 | 0.268 |
| upsert | 90007 | 0.202 | 0.324 | 0.228 |
| commit | 90007 | 1.074 | 2.852 | 1.409 |
| release | 90007 | 0.005 | 0.018 | 0.008 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | acquire p50 ms | begin p50 ms | insert p50 ms | upsert p50 ms | commit p50 ms | release p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.024 | 0.166 | 0.241 | 0.206 | 1.090 | 0.007 | 13559 | 1.08 | 0.92 | 0.00 | 33246 / 0 / 1618 |
| 1 | 0.021 | 0.167 | 0.237 | 0.205 | 1.050 | 0.006 | 14835 | 1.01 | 0.88 | 0.00 | 33262 / 0 / 1677 |
| 2 | 0.018 | 0.161 | 0.221 | 0.197 | 1.074 | 0.005 | 15043 | 1.00 | 0.95 | 0.00 | 33161 / 0 / 1640 |
| 3 | 0.020 | 0.166 | 0.227 | 0.202 | 1.053 | 0.005 | 14142 | 1.06 | 1.00 | 0.00 | 33263 / 0 / 1693 |
| 4 | 0.018 | 0.163 | 0.218 | 0.201 | 1.069 | 0.004 | 14859 | 1.01 | 0.97 | 0.00 | 33210 / 0 / 1644 |
| 5 | 0.017 | 0.166 | 0.224 | 0.204 | 1.099 | 0.004 | 14290 | 1.05 | 1.06 | 0.00 | 33034 / 0 / 1533 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9886 | 0.110 |
| bgwriter.buffers_backend | 9870 | 0.110 |
| bgwriter.buffers_checkpoint | 202273 | 2.247 |
| db.blk_write_time | 106 | 0.001 |
| db.blks_hit | 1576954 | 17.520 |
| db.xact_commit | 105089 | 1.168 |
| things_journal.heap_blks_hit | 108195 | 1.202 |
| things_journal.idx_blks_hit | 543345 | 6.037 |
| things_journal_seq.heap_blks_hit | 271583 | 3.017 |
| things_journal_seq.idx_blks_hit | 272700 | 3.030 |
| wal.wal_bytes | 119502411 | 1327.701 |
| wal.wal_fpi | 45 | 0.000 |
| wal.wal_records | 595012 | 6.611 |
| wal.wal_sync | 87453 | 0.972 |
| wal.wal_sync_time | 84232 | 0.936 |
| wal.wal_write | 87453 | 0.972 |
| wal.wal_write_time | 658 | 0.007 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 14958 sampler passes, 119016 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 104943 | 88.2 % |
| active / IO:WALSync | 7267 | 6.1 % |
| idle in transaction / Client:ClientRead | 4319 | 3.6 % |
| active / LWLock:WALWrite | 1997 | 1.7 % |
| active / CPU | 197 | 0.2 % |
| idle in transaction / CPU | 138 | 0.1 % |
| idle in transaction / IO:WALSync | 69 | 0.1 % |
| active / Client:ClientRead | 40 | 0.0 % |
| idle / CPU | 24 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 7 | 0.0 % |
| active / IO:WALWrite | 6 | 0.0 % |
| active / IO:DataFileExtend | 4 | 0.0 % |
| idle in transaction / IO:WALWrite | 2 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |
| active / LWLock:XidGen | 1 | 0.0 % |
| active / Lock:extend | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
