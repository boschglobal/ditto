# W3 — sustained churn

- generated: 2026-10-06T10:42:28.820645Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 4 workers x 200 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-txn-w4; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 90004 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14610 | 1.59 | 2.02 | 44.69 | 0.0 MB | 9191.6 MB | 58.8 MB | 0 |
| 1 | 15016 | 1.42 | 1.90 | 21.82 | 19.5 MB | 9204.7 MB | 58.8 MB | 0 |
| 2 | 15012 | 1.56 | 6.01 | 174.62 | 19.2 MB | 9217.3 MB | 58.8 MB | 0 |
| 3 | 15005 | 1.59 | 9.02 | 454.36 | 19.7 MB | 9230.9 MB | 58.8 MB | 0 |
| 4 | 15009 | 1.56 | 6.39 | 173.00 | 19.2 MB | 9243.5 MB | 58.8 MB | 0 |
| 5 | 14958 | 1.48 | 5.61 | 139.85 | 18.8 MB | 9255.3 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 8.0 ms period: 0.00, 2.00, 4.00, 6.00 ms
- journal heap placement of the run's 90004 rows: 9003 distinct pages; 8 rows (0.0 %) on 1 pages that existed before the run (blocks < 1101870, i.e. free space reused via the free space map), 89996 rows appended on 9002 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 90004 | 0.197 | 0.378 | 0.250 |
| upsert | 90004 | 0.146 | 0.212 | 0.149 |
| commit | 90004 | 1.145 | 4.896 | 1.836 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.225 | 0.163 | 1.172 | 14155 | 1.03 | 1.05 | 0.00 | 1820 / 0 / 1640 |
| 1 | 0.195 | 0.143 | 1.052 | 15054 | 1.00 | 0.87 | 0.00 | 1818 / 0 / 1681 |
| 2 | 0.188 | 0.141 | 1.192 | 13996 | 1.07 | 1.27 | 0.00 | 1818 / 0 / 1609 |
| 3 | 0.188 | 0.138 | 1.209 | 12845 | 1.17 | 1.52 | 0.00 | 1817 / 0 / 1725 |
| 4 | 0.189 | 0.138 | 1.175 | 13828 | 1.09 | 1.30 | 0.00 | 1818 / 0 / 1609 |
| 5 | 0.194 | 0.143 | 1.090 | 13952 | 1.07 | 1.18 | 0.00 | 1083 / 0 / 896 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9866 | 0.110 |
| bgwriter.buffers_backend | 9160 | 0.102 |
| bgwriter.buffers_checkpoint | 10174 | 0.113 |
| bgwriter.checkpoint_sync_time | 14 | 0.000 |
| bgwriter.checkpoint_write_time | 269923 | 2.999 |
| db.blk_write_time | 79 | 0.001 |
| db.blks_hit | 1573613 | 17.484 |
| db.xact_commit | 105439 | 1.171 |
| things_journal.heap_blks_hit | 108303 | 1.203 |
| things_journal.idx_blks_hit | 543278 | 6.036 |
| things_journal_seq.heap_blks_hit | 271544 | 3.017 |
| things_journal_seq.idx_blks_hit | 272663 | 3.029 |
| wal.wal_bytes | 120014842 | 1333.439 |
| wal.wal_fpi | 29 | 0.000 |
| wal.wal_records | 620471 | 6.894 |
| wal.wal_sync | 84396 | 0.938 |
| wal.wal_sync_time | 100184 | 1.113 |
| wal.wal_write | 84396 | 0.938 |
| wal.wal_write_time | 429 | 0.005 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 15327 sampler passes, 61302 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 44476 | 72.6 % |
| active / IO:WALSync | 9230 | 15.1 % |
| active / LWLock:WALWrite | 4354 | 7.1 % |
| idle in transaction / Client:ClientRead | 2828 | 4.6 % |
| active / CPU | 211 | 0.3 % |
| idle in transaction / CPU | 53 | 0.1 % |
| idle in transaction / IO:WALSync | 46 | 0.1 % |
| active / Client:ClientRead | 36 | 0.1 % |
| idle in transaction / LWLock:WALWrite | 31 | 0.1 % |
| idle / CPU | 27 | 0.0 % |
| active / IO:WALWrite | 3 | 0.0 % |
| active / IO:DataFileExtend | 2 | 0.0 % |
| active / LWLock:BufferContent | 2 | 0.0 % |
| idle in transaction / IO:WALWrite | 2 | 0.0 % |
| idle / IO:WALSync | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
