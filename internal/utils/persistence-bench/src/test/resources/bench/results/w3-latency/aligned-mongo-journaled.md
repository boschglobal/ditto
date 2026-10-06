# W3 — sustained churn

- generated: 2026-10-06T09:49:47.970501Z
- corpus: count=50000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 180 s per backend
- churn pid prefix thing:bench.churn-u-mongo-j; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off; worker schedules unstaggered

## MongoDB

- total ops 89912 (+0 snapshots), achieved 499.5/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 15000 | 1.95 | 3.24 | 84.12 | — | 5634.9 MB | 54.9 MB | — |
| 1 | 15016 | 1.66 | 3.50 | 138.27 | — | 5634.9 MB | 54.9 MB | — |
| 2 | 15000 | 1.66 | 4.30 | 173.03 | — | 5637.7 MB | 54.9 MB | — |
| 3 | 15008 | 1.68 | 3.71 | 123.29 | — | 5637.7 MB | 54.9 MB | — |
| 4 | 15008 | 1.75 | 4.07 | 123.11 | — | 5640.3 MB | 54.9 MB | — |
| 5 | 14880 | 1.75 | 3.72 | 194.07 | — | 5640.7 MB | 54.9 MB | — |

- journal write concern: {"j": true} (realtime + snapshot writes stay JOURNALED)
- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.02, 0.05, 0.07, 0.13, 0.18, 0.27, 0.45 ms

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 179824 | 2.000 |
| opcounters.update | 9 | 0.000 |
| wiredTiger.cache.pages read into cache | 280 | 0.003 |
| wiredTiger.cache.pages written from cache | 2590 | 0.029 |
| wiredTiger.log.log bytes written | 92082304 | 1024.138 |
| wiredTiger.log.log flush operations | 110957 | 1.234 |
| wiredTiger.log.log sync operations | 110886 | 1.233 |
| wiredTiger.log.log sync time duration (usecs) | 106785331 | 1187.665 |
| wiredTiger.log.log sync_dir operations | 1 | 0.000 |
| wiredTiger.log.log write operations | 179851 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 456 | 0.005 |
| wiredTiger.transaction.transaction checkpoints | 3 | 0.000 |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
