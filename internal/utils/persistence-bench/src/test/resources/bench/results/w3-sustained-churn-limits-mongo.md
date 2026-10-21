# W3 — sustained churn

- generated: 2026-10-21T20:53:37.800750Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

- target rate 500/s, 8 workers x 100 pids, 600 s per backend
- churn pid prefix thing:bench.churn; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off; worker schedules unstaggered

## MongoDB

- total ops 299888 (+0 snapshots), achieved 499.8/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 15000 | 1.85 | 3.30 | 78.04 | — | 28148.7 MB | 277.1 MB | — |
| 1 | 15016 | 1.96 | 4.17 | 171.49 | — | 28150.2 MB | 277.1 MB | — |
| 2 | 15016 | 2.25 | 7.33 | 156.69 | — | 28151.7 MB | 277.1 MB | — |
| 3 | 15000 | 2.80 | 8.32 | 172.01 | — | 28156.3 MB | 277.1 MB | — |
| 4 | 15008 | 2.38 | 7.86 | 186.13 | — | 28156.3 MB | 277.1 MB | — |
| 5 | 15008 | 2.25 | 8.05 | 307.19 | — | 28161.7 MB | 277.1 MB | — |
| 6 | 14928 | 2.60 | 8.02 | 190.02 | — | 28162.5 MB | 277.1 MB | — |
| 7 | 15008 | 2.57 | 8.15 | 188.75 | — | 28167.5 MB | 277.1 MB | — |
| 8 | 15081 | 2.06 | 6.82 | 201.18 | — | 28169.3 MB | 277.1 MB | — |
| 9 | 15007 | 2.14 | 8.15 | 159.48 | — | 28173.3 MB | 277.1 MB | — |
| 10 | 15008 | 2.18 | 6.40 | 186.42 | — | 28173.8 MB | 277.1 MB | — |
| 11 | 15000 | 1.99 | 5.99 | 174.62 | — | 28177.9 MB | 277.1 MB | — |
| 12 | 15008 | 2.03 | 6.50 | 187.88 | — | 28178.4 MB | 277.1 MB | — |
| 13 | 15008 | 2.42 | 7.81 | 198.90 | — | 28183.2 MB | 277.1 MB | — |
| 14 | 15000 | 2.16 | 7.29 | 181.11 | — | 28184.5 MB | 277.1 MB | — |
| 15 | 15008 | 2.23 | 7.40 | 184.33 | — | 28189.8 MB | 277.1 MB | — |
| 16 | 15000 | 2.15 | 6.83 | 171.25 | — | 28189.8 MB | 277.1 MB | — |
| 17 | 15008 | 2.31 | 7.31 | 188.04 | — | 28194.7 MB | 277.1 MB | — |
| 18 | 15008 | 2.43 | 7.49 | 180.25 | — | 28194.7 MB | 277.1 MB | — |
| 19 | 14768 | 2.36 | 8.26 | 176.37 | — | 28199.9 MB | 277.1 MB | — |

- journal write concern: {"j": true} (realtime + snapshot writes stay JOURNALED)
- worker schedules (each worker starts when its connection is ready): offsets within the 16.0 ms period: 0.00, 0.12, 0.12, 0.21, 0.32, 0.34, 0.36, 0.45 ms

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 599776 | 2.000 |
| opcounters.update | 20 | 0.000 |
| wiredTiger.cache.pages read into cache | 1022 | 0.003 |
| wiredTiger.cache.pages written from cache | 18831 | 0.063 |
| wiredTiger.log.log bytes written | 307123840 | 1024.128 |
| wiredTiger.log.log flush operations | 355530 | 1.186 |
| wiredTiger.log.log sync operations | 355521 | 1.186 |
| wiredTiger.log.log sync time duration (usecs) | 445494881 | 1485.538 |
| wiredTiger.log.log sync_dir operations | 2 | 0.000 |
| wiredTiger.log.log write operations | 599856 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 1258 | 0.004 |
| wiredTiger.transaction.transaction checkpoints | 10 | 0.000 |

### Mongo plan (append)

```
not explainable: journal bulkWrite(InsertOne) and the realtime insertOne (MongoDB's explain command has no insert or compact form) — timed only
```


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
