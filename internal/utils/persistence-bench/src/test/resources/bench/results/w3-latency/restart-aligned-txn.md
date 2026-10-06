# W3 — sustained churn

- generated: 2026-10-06T09:30:59.352838Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-i2-post-r1-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms

## PostgreSQL

- total ops 89816 (+0 snapshots), achieved 499.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14944 | 2.53 | 4.30 | 22.01 | 0.0 MB | 7790.5 MB | 58.8 MB | 8 |
| 1 | 15008 | 2.71 | 5.44 | 20.79 | 19.1 MB | 7803.7 MB | 58.8 MB | 8 |
| 2 | 15008 | 2.77 | 5.91 | 21.11 | 18.6 MB | 7815.9 MB | 58.8 MB | 8 |
| 3 | 15000 | 2.97 | 6.13 | 25.77 | 19.5 MB | 7830.0 MB | 58.8 MB | 8 |
| 4 | 15000 | 3.40 | 6.26 | 20.49 | 18.6 MB | 7842.2 MB | 58.8 MB | 8 |
| 5 | 14856 | 3.84 | 6.63 | 27.56 | 18.2 MB | 7853.9 MB | 58.8 MB | 8 |

- journal heap placement of the run's 89816 rows: 8986 distinct pages; 31 rows (0.0 %) on 5 pages that existed before the run (blocks < 939209, i.e. free space reused via the free space map), 89785 rows appended on 8981 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 89816 | 0.514 | 1.038 | 0.570 |
| upsert | 89816 | 0.283 | 0.609 | 0.322 |
| commit | 89816 | 2.002 | 4.904 | 2.487 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 10691 | 0.119 |
| db.blk_read_time | 42 | 0.000 |
| db.blk_write_time | 232 | 0.003 |
| db.blks_hit | 1587220 | 17.672 |
| db.blks_read | 1974 | 0.022 |
| db.xact_commit | 104941 | 1.168 |
| things_journal.heap_blks_hit | 125745 | 1.400 |
| things_journal.heap_blks_read | 8 | 0.000 |
| things_journal.idx_blks_hit | 542355 | 6.039 |
| things_journal.idx_blks_read | 450 | 0.005 |
| things_journal_seq.heap_blks_hit | 269025 | 2.995 |
| things_journal_seq.heap_blks_read | 1410 | 0.016 |
| things_journal_seq.idx_blks_hit | 271938 | 3.028 |
| things_journal_seq.idx_blks_read | 6 | 0.000 |
| wal.wal_bytes | 117273567 | 1305.709 |
| wal.wal_fpi | 41 | 0.000 |
| wal.wal_records | 563085 | 6.269 |
| wal.wal_sync | 22574 | 0.251 |
| wal.wal_sync_time | 28555 | 0.318 |
| wal.wal_write | 22574 | 0.251 |
| wal.wal_write_time | 375 | 0.004 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 15046 sampler passes, 120200 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 92369 | 76.8 % |
| active / LWLock:WALWrite | 13976 | 11.6 % |
| idle in transaction / Client:ClientRead | 7843 | 6.5 % |
| active / IO:WALSync | 2911 | 2.4 % |
| active / CPU | 1460 | 1.2 % |
| idle in transaction / CPU | 680 | 0.6 % |
| active / Client:ClientRead | 349 | 0.3 % |
| idle in transaction / LWLock:WALWrite | 196 | 0.2 % |
| active / Lock:extend | 100 | 0.1 % |
| idle / CPU | 99 | 0.1 % |
| active / LWLock:BufferContent | 82 | 0.1 % |
| active / IO:WALWrite | 32 | 0.0 % |
| active / LWLock:XidGen | 27 | 0.0 % |
| idle in transaction / IO:WALWrite | 24 | 0.0 % |
| active / IO:DataFileExtend | 13 | 0.0 % |
| active / Timeout:SpinDelay | 12 | 0.0 % |
| idle in transaction / IO:WALSync | 8 | 0.0 % |
| active / IPC:BufferIO | 7 | 0.0 % |
| active / IO:DataFileRead | 4 | 0.0 % |
| active / LWLock:LockManager | 3 | 0.0 % |
| active / LWLock:WALInsert | 3 | 0.0 % |
| active / LWLock:XactSLRU | 1 | 0.0 % |
| idle / IO:DataFileExtend | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
