# W3 — sustained churn

- generated: 2026-10-06T09:43:58.093176Z
- corpus: count=50000 seed=42
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 300 s per backend
- churn pid prefix thing:bench.churn-u2-stagger-txn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler every 10 ms; worker schedules staggered

## PostgreSQL

- total ops 150008 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14629 | 1.56 | 2.33 | 88.49 | 0.0 MB | 7979.3 MB | 58.8 MB | 8 |
| 1 | 15017 | 1.49 | 4.17 | 132.66 | 19.7 MB | 7992.5 MB | 58.8 MB | 8 |
| 2 | 15012 | 1.42 | 5.92 | 149.22 | 19.4 MB | 8005.2 MB | 58.8 MB | 8 |
| 3 | 15011 | 1.61 | 2.64 | 163.44 | 23.4 MB | 8018.7 MB | 58.8 MB | 8 |
| 4 | 15008 | 1.56 | 3.28 | 168.69 | 19.4 MB | 8031.3 MB | 58.8 MB | 8 |
| 5 | 15009 | 1.55 | 4.22 | 55.50 | 19.1 MB | 8043.4 MB | 58.8 MB | 8 |
| 6 | 15008 | 1.59 | 2.81 | 147.57 | 19.5 MB | 8056.2 MB | 58.8 MB | 8 |
| 7 | 15008 | 1.60 | 2.53 | 21.60 | 21.4 MB | 8072.4 MB | 58.8 MB | 8 |
| 8 | 15005 | 1.62 | 2.48 | 42.84 | 19.3 MB | 8084.7 MB | 58.8 MB | 8 |
| 9 | 14921 | 1.61 | 2.21 | 23.83 | 20.3 MB | 8097.3 MB | 58.8 MB | 0 |

- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms
- journal heap placement of the run's 150008 rows: 15007 distinct pages; 0 rows (0.0 %) on 0 pages that existed before the run (blocks < 960929, i.e. free space reused via the free space map), 150008 rows appended on 15007 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 150008 | 0.213 | 0.347 | 0.254 |
| upsert | 150008 | 0.151 | 0.201 | 0.159 |
| commit | 150008 | 1.204 | 2.608 | 1.490 |

Per-window step p50s, WAL flushes and buffer write-back:

| window | insert p50 ms | upsert p50 ms | commit p50 ms | WAL flushes | commits per flush | avg WAL fsync ms | FPI per op | bufs written: checkpointer / bgwriter / backends |
|---|---|---|---|---|---|---|---|---|
| 0 | 0.222 | 0.154 | 1.161 | 14110 | 1.04 | 1.05 | 0.00 | 3019 / 0 / 1628 |
| 1 | 0.193 | 0.140 | 1.120 | 14568 | 1.03 | 1.05 | 0.00 | 3015 / 0 / 1691 |
| 2 | 0.211 | 0.133 | 1.018 | 13940 | 1.08 | 1.15 | 0.00 | 1869 / 0 / 1006 |
| 3 | 0.202 | 0.149 | 1.247 | 14736 | 1.02 | 1.17 | 0.04 | 1539 / 0 / 2337 |
| 4 | 0.215 | 0.152 | 1.187 | 14596 | 1.03 | 1.11 | 0.00 | 4058 / 0 / 1616 |
| 5 | 0.235 | 0.164 | 1.110 | 14533 | 1.03 | 1.09 | 0.00 | 4053 / 0 / 1534 |
| 6 | 0.209 | 0.152 | 1.221 | 14736 | 1.02 | 1.15 | 0.00 | 4061 / 0 / 1649 |
| 7 | 0.202 | 0.149 | 1.241 | 14891 | 1.01 | 1.15 | 0.00 | 4054 / 0 / 2077 |
| 8 | 0.211 | 0.152 | 1.250 | 14654 | 1.02 | 1.14 | 0.00 | 4067 / 0 / 1581 |
| 9 | 0.221 | 0.153 | 1.223 | 15021 | 0.99 | 1.13 | 0.00 | 4021 / 0 / 1651 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 17007 | 0.113 |
| bgwriter.buffers_backend | 16817 | 0.112 |
| bgwriter.buffers_checkpoint | 34134 | 0.228 |
| bgwriter.checkpoint_sync_time | 29 | 0.000 |
| bgwriter.checkpoint_write_time | 269859 | 1.799 |
| bgwriter.checkpoints_timed | 1 | 0.000 |
| db.blk_read_time | 109 | 0.001 |
| db.blk_write_time | 211 | 0.001 |
| db.blks_hit | 2656179 | 17.707 |
| db.blks_read | 25249 | 0.168 |
| db.xact_commit | 175265 | 1.168 |
| things_journal.heap_blks_hit | 186088 | 1.241 |
| things_journal.heap_blks_read | 24200 | 0.161 |
| things_journal.idx_blks_hit | 908111 | 6.054 |
| things_journal_seq.heap_blks_hit | 468975 | 3.126 |
| things_journal_seq.heap_blks_read | 690 | 0.005 |
| things_journal_seq.idx_blks_hit | 462522 | 3.083 |
| things_journal_seq.idx_blks_read | 342 | 0.002 |
| wal.wal_bytes | 208220416 | 1388.062 |
| wal.wal_fpi | 820 | 0.005 |
| wal.wal_records | 1048236 | 6.988 |
| wal.wal_sync | 146332 | 0.975 |
| wal.wal_sync_time | 163771 | 1.092 |
| wal.wal_write | 146332 | 0.975 |
| wal.wal_write_time | 940 | 0.006 |

Wait events of the churn backends (pg_stat_activity, every 10 ms):

- wait-event samples: 25078 sampler passes, 200620 backend observations

| state / wait event | observations | share |
|---|---|---|
| idle / Client:ClientRead | 178025 | 88.7 % |
| active / IO:WALSync | 15181 | 7.6 % |
| idle in transaction / Client:ClientRead | 3686 | 1.8 % |
| active / LWLock:WALWrite | 3219 | 1.6 % |
| active / CPU | 217 | 0.1 % |
| idle in transaction / IO:WALSync | 108 | 0.1 % |
| active / Client:ClientRead | 56 | 0.0 % |
| idle / CPU | 42 | 0.0 % |
| idle in transaction / CPU | 42 | 0.0 % |
| idle in transaction / LWLock:WALWrite | 17 | 0.0 % |
| active / IO:WALWrite | 14 | 0.0 % |
| active / IO:DataFileExtend | 5 | 0.0 % |
| active / LWLock:BufferContent | 3 | 0.0 % |
| active / Lock:extend | 2 | 0.0 % |
| idle in transaction / IO:WALWrite | 2 | 0.0 % |
| idle / LWLock:WALWrite | 1 | 0.0 % |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
