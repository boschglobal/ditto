# W3 — sustained churn

- generated: 2026-10-06T10:17:37.246518Z
- corpus: count=50000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-g-mongo-w1; PG append shape txn; Mongo journal write concern w1; PG wait-event sampler off; worker schedules staggered

## MongoDB

- total ops 90008 (+0 snapshots), achieved 500.0/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14587 | 0.22 | 0.37 | 76.44 | — | 5653.8 MB | 54.9 MB | — |
| 1 | 15010 | 0.21 | 0.30 | 18.70 | — | 5655.1 MB | 54.9 MB | — |
| 2 | 15008 | 0.21 | 0.28 | 22.34 | — | 5657.4 MB | 54.9 MB | — |
| 3 | 15006 | 0.21 | 0.33 | 21.64 | — | 5657.5 MB | 54.9 MB | — |
| 4 | 15004 | 0.20 | 0.34 | 198.01 | — | 5660.4 MB | 54.9 MB | — |
| 5 | 14970 | 0.17 | 0.24 | 18.51 | — | 5662.0 MB | 54.9 MB | — |

- journal write concern: {"w": 1} (realtime + snapshot writes stay JOURNALED)
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 180016 | 2.000 |
| opcounters.update | 0 | 0.000 |
| wiredTiger.cache.pages read into cache | 141 | 0.002 |
| wiredTiger.cache.pages written from cache | 3311 | 0.037 |
| wiredTiger.log.log bytes written | 92178048 | 1024.110 |
| wiredTiger.log.log flush operations | 90017 | 1.000 |
| wiredTiger.log.log sync operations | 90014 | 1.000 |
| wiredTiger.log.log sync time duration (usecs) | 80227201 | 891.334 |
| wiredTiger.log.log sync_dir operations | 1 | 0.000 |
| wiredTiger.log.log write operations | 180034 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 329 | 0.004 |
| wiredTiger.transaction.transaction checkpoints | 3 | 0.000 |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
