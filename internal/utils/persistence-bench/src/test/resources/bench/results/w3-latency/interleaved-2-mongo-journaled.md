# W3 — sustained churn

- generated: 2026-10-06T11:08:50.803907Z
- corpus: count=50000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i2-mongo-j; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off; worker schedules staggered

## MongoDB

- total ops 30007 (+0 snapshots), achieved 500.1/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14577 | 1.05 | 1.59 | 89.51 | — | 5669.1 MB | 54.9 MB | — |
| 1 | 15000 | 0.99 | 5.02 | 192.45 | — | 5669.1 MB | 54.9 MB | — |

- journal write concern: {"j": true} (realtime + snapshot writes stay JOURNALED)
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 60014 | 2.000 |
| opcounters.update | 0 | 0.000 |
| wiredTiger.cache.pages read into cache | 32 | 0.001 |
| wiredTiger.cache.pages written from cache | 425 | 0.014 |
| wiredTiger.log.log bytes written | 30730496 | 1024.111 |
| wiredTiger.log.log flush operations | 58064 | 1.935 |
| wiredTiger.log.log sync operations | 58040 | 1.934 |
| wiredTiger.log.log sync time duration (usecs) | 45044019 | 1501.117 |
| wiredTiger.log.log sync_dir operations | 1 | 0.000 |
| wiredTiger.log.log write operations | 60020 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 54 | 0.002 |
| wiredTiger.transaction.transaction checkpoints | 1 | 0.000 |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
