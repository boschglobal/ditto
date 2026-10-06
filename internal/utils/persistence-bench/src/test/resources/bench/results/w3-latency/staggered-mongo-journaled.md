# W3 — sustained churn

- generated: 2026-10-06T10:14:32.455593Z
- corpus: count=50000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-mongo-j; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off; worker schedules staggered

## MongoDB

- total ops 90008 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14566 | 0.90 | 1.75 | 70.20 | — | 5644.7 MB | 54.9 MB | — |
| 1 | 15023 | 1.01 | 6.23 | 182.83 | — | 5644.9 MB | 54.9 MB | — |
| 2 | 15003 | 1.01 | 13.70 | 420.88 | — | 5647.8 MB | 54.9 MB | — |
| 3 | 15007 | 1.05 | 7.53 | 232.08 | — | 5647.8 MB | 54.9 MB | — |
| 4 | 15003 | 1.00 | 5.75 | 200.21 | — | 5650.5 MB | 54.9 MB | — |
| 5 | 14972 | 0.95 | 5.92 | 188.62 | — | 5650.5 MB | 54.9 MB | — |

- journal write concern: {"j": true} (realtime + snapshot writes stay JOURNALED)
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 180016 | 2.000 |
| opcounters.update | 10 | 0.000 |
| wiredTiger.cache.pages read into cache | 377 | 0.004 |
| wiredTiger.cache.pages written from cache | 3631 | 0.040 |
| wiredTiger.log.log bytes written | 92180992 | 1024.142 |
| wiredTiger.log.log flush operations | 165158 | 1.835 |
| wiredTiger.log.log sync operations | 165061 | 1.834 |
| wiredTiger.log.log sync time duration (usecs) | 132421529 | 1471.220 |
| wiredTiger.log.log sync_dir operations | 1 | 0.000 |
| wiredTiger.log.log write operations | 180044 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 398 | 0.004 |
| wiredTiger.transaction.transaction checkpoints | 3 | 0.000 |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
