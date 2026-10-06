# W3 — sustained churn

- generated: 2026-10-06T09:52:51.545556Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-txn-sync-off; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules unstaggered

## PostgreSQL

- total ops 89832 (+0 snapshots), achieved 499.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14944 | 1.23 | 1.93 | 21.16 | 0.0 MB | 8110.6 MB | 58.8 MB | 0 |
| 1 | 15016 | 1.25 | 1.99 | 14.12 | 19.6 MB | 8123.7 MB | 58.8 MB | 0 |
| 2 | 15008 | 1.23 | 1.98 | 6.13 | 19.4 MB | 8136.4 MB | 58.8 MB | 0 |
| 3 | 15008 | 1.26 | 2.18 | 14.77 | 19.8 MB | 8149.9 MB | 58.8 MB | 0 |
| 4 | 15008 | 1.23 | 2.08 | 15.65 | 19.4 MB | 8162.6 MB | 58.8 MB | 0 |
| 5 | 14848 | 1.26 | 2.27 | 105.40 | 24.3 MB | 8174.5 MB | 58.8 MB | 0 |

- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.01, 0.01, 0.01, 0.06, 0.13, 0.16, 0.18 ms
- journal heap placement of the run's 89832 rows: 8987 distinct pages; 3 rows (0.0 %) on 1 pages that existed before the run (blocks < 975936, i.e. free space reused via the free space map), 89829 rows appended on 8986 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 89832 | 0.586 | 1.236 | 0.664 |
| upsert | 89832 | 0.345 | 0.698 | 0.386 |
| commit | 89832 | 0.237 | 0.451 | 0.258 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.567 | 0.335 | 0.221 | 145 | 103.16 | 3.32 | 0.00 | 0 / 0 / 0 |
| 1 | 0.587 | 0.344 | 0.230 | 146 | 102.81 | 3.25 | 0.00 | 0 / 0 / 0 |
| 2 | 0.575 | 0.346 | 0.236 | 144 | 104.22 | 3.11 | 0.00 | 0 / 0 / 0 |
| 3 | 0.602 | 0.353 | 0.247 | 145 | 103.50 | 3.09 | 0.00 | 0 / 0 / 0 |
| 4 | 0.590 | 0.343 | 0.244 | 145 | 103.50 | 3.14 | 0.00 | 0 / 0 / 0 |
| 5 | 0.591 | 0.345 | 0.244 | 145 | 102.34 | 3.33 | 0.06 | 373 / 0 / 7504 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 9895 | 0.110 |
| bgwriter.buffers_backend | 7504 | 0.084 |
| bgwriter.buffers_checkpoint | 527 | 0.006 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 4 | 0.000 |
| db.blk_write_time | 293 | 0.003 |
| db.blks_hit | 1595773 | 17.764 |
| db.blks_read | 352 | 0.004 |
| db.xact_commit | 104962 | 1.168 |
| things_journal.heap_blks_hit | 130034 | 1.448 |
| things_journal.idx_blks_hit | 543125 | 6.046 |
| things_journal_seq.heap_blks_hit | 271286 | 3.020 |
| things_journal_seq.heap_blks_read | 10 | 0.000 |
| things_journal_seq.idx_blks_hit | 272272 | 3.031 |
| things_journal_seq.idx_blks_read | 342 | 0.004 |
| wal.wal_bytes | 126465071 | 1407.795 |
| wal.wal_fpi | 865 | 0.010 |
| wal.wal_records | 627782 | 6.988 |
| wal.wal_sync | 871 | 0.010 |
| wal.wal_sync_time | 2795 | 0.031 |
| wal.wal_write | 13573 | 0.151 |
| wal.wal_write_time | 280 | 0.003 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 15013 sampler passes, 119928 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 108691 | 90.6 % |
| idle in transaction / Client:ClientRead | 8108 | 6.8 % |
| active / CPU | 1500 | 1.3 % |
| idle in transaction / CPU | 826 | 0.7 % |
| active / Client:ClientRead | 467 | 0.4 % |
| idle / CPU | 147 | 0.1 % |
| active / Lock:extend | 79 | 0.1 % |
| active / LWLock:BufferContent | 66 | 0.1 % |
| active / IO:DataFileExtend | 18 | 0.0 % |
| active / Timeout:SpinDelay | 12 | 0.0 % |
| active / LWLock:XidGen | 8 | 0.0 % |
| active / LWLock:ProcArray | 2 | 0.0 % |
| active / LWLock:LockManager | 1 | 0.0 % |
| idle / LWLock:BufferContent | 1 | 0.0 % |
| idle / LWLock:LockManager | 1 | 0.0 % |
| idle in transaction / LWLock:ProcArray | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
