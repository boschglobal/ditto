# W3 — sustained churn

- generated: 2026-10-06T11:01:05.819081Z
- corpus: count=50000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 60 s per backend
- churn pid prefix thing:bench.churn-i1-mongo-j; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off; worker schedules staggered

## MongoDB

- total ops 29903 (+0 snapshots), achieved 498.4/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14585 | 1.14 | 5.96 | 180.67 | — | 5665.9 MB | 54.9 MB | — |
| 1 | 14987 | 1.07 | 6.92 | 93.53 | — | 5668.0 MB | 54.9 MB | — |

- journal write concern: {"j": true} (realtime + snapshot writes stay JOURNALED)
- worker schedules (staggered evenly): offsets within the 16.0 ms period: 0.00, 2.00, 4.00, 6.00, 8.00, 10.00, 12.00, 14.00 ms

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 59806 | 2.000 |
| opcounters.update | 0 | 0.000 |
| wiredTiger.cache.pages read into cache | 10 | 0.000 |
| wiredTiger.cache.pages written from cache | 1071 | 0.036 |
| wiredTiger.log.log bytes written | 30623872 | 1024.107 |
| wiredTiger.log.log flush operations | 54724 | 1.830 |
| wiredTiger.log.log sync operations | 54635 | 1.827 |
| wiredTiger.log.log sync time duration (usecs) | 44382718 | 1484.223 |
| wiredTiger.log.log sync_dir operations | 0 | 0.000 |
| wiredTiger.log.log write operations | 59812 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 203 | 0.007 |
| wiredTiger.transaction.transaction checkpoints | 1 | 0.000 |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
