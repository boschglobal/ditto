# W3 — sustained churn

- generated: 2026-10-06T09:09:37.418161Z
- corpus: count=50000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- target rate 500/s, 8 workers x 100 pids, 600 s per backend
- churn pid prefix thing:bench.churn-r1-canon-600-b; PG append shape txn; Mongo journal write concern journaled; PG wait-event sampler off

## PostgreSQL

- total ops 299784 (+0 snapshots), achieved 499.6/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14920 | 5.19 | 8.99 | 47.81 | 0.0 MB | 7597.4 MB | 58.8 MB | 8 |
| 1 | 15016 | 5.76 | 9.63 | 37.86 | 124.1 MB | 7599.6 MB | 58.8 MB | 8 |
| 2 | 15008 | 6.29 | 10.09 | 56.23 | 123.7 MB | 7601.3 MB | 58.8 MB | 8 |
| 3 | 15000 | 6.43 | 11.01 | 53.49 | 124.1 MB | 7603.8 MB | 58.8 MB | 8 |
| 4 | 15009 | 6.47 | 11.20 | 72.07 | 132.9 MB | 7604.9 MB | 58.8 MB | 8 |
| 5 | 15015 | 6.47 | 10.18 | 52.47 | 123.5 MB | 7605.5 MB | 58.8 MB | 8 |
| 6 | 15000 | 6.21 | 9.93 | 81.09 | 123.5 MB | 7606.2 MB | 58.8 MB | 8 |
| 7 | 15008 | 6.42 | 10.26 | 63.92 | 67.2 MB | 7617.1 MB | 58.8 MB | 8 |
| 8 | 15008 | 6.21 | 9.88 | 32.81 | 19.0 MB | 7629.5 MB | 58.8 MB | 8 |
| 9 | 15008 | 6.57 | 9.89 | 35.07 | 19.2 MB | 7642.2 MB | 58.8 MB | 8 |
| 10 | 15008 | 6.38 | 9.88 | 100.34 | 20.3 MB | 7657.1 MB | 58.8 MB | 8 |
| 11 | 15008 | 6.66 | 10.45 | 45.47 | 22.8 MB | 7671.0 MB | 58.8 MB | 8 |
| 12 | 15008 | 6.82 | 10.35 | 31.93 | 18.8 MB | 7682.9 MB | 58.8 MB | 8 |
| 13 | 15009 | 6.57 | 10.02 | 35.55 | 25.2 MB | 7695.7 MB | 58.8 MB | 8 |
| 14 | 15007 | 6.37 | 9.67 | 35.07 | 22.9 MB | 7711.5 MB | 58.8 MB | 8 |
| 15 | 15008 | 6.72 | 10.17 | 31.94 | 19.7 MB | 7724.2 MB | 58.8 MB | 8 |
| 16 | 15008 | 6.60 | 10.27 | 156.02 | 19.3 MB | 7736.8 MB | 58.8 MB | 8 |
| 17 | 15008 | 6.60 | 10.10 | 40.17 | 18.9 MB | 7748.9 MB | 58.8 MB | 8 |
| 18 | 15008 | 6.60 | 10.26 | 27.14 | 21.5 MB | 7765.8 MB | 58.8 MB | 8 |
| 19 | 14720 | 6.51 | 9.95 | 41.71 | 18.4 MB | 7777.3 MB | 58.8 MB | 8 |

- journal heap placement of the run's 299784 rows: 130436 distinct pages; 111725 rows (37.3 %) on 111627 pages that existed before the run (blocks < 920400, i.e. free space reused via the free space map), 188059 rows appended on 18809 new pages

Per-step client timings over the whole run (shape txn):

| step | n | p50 ms | p95 ms | mean ms |
|---|---|---|---|---|
| insert | 299784 | 1.445 | 2.883 | 1.572 |
| upsert | 299784 | 0.676 | 1.499 | 0.746 |
| commit | 299784 | 4.160 | 6.558 | 4.314 |

Server-side counters over the run:

| counter (pg_stat view.column) | delta over the run | per op |
|---|---|---|
| bgwriter.buffers_alloc | 134558 | 0.449 |
| bgwriter.buffers_backend | 16859 | 0.056 |
| bgwriter.buffers_checkpoint | 133273 | 0.445 |
| bgwriter.checkpoint_sync_time | 61 | 0.000 |
| bgwriter.checkpoint_write_time | 370110 | 1.235 |
| bgwriter.checkpoints_req | 2 | 0.000 |
| db.blks_hit | 5507062 | 18.370 |
| db.blks_read | 111186 | 0.371 |
| db.xact_commit | 299987 | 1.001 |
| things_journal.heap_blks_hit | 624958 | 2.085 |
| things_journal.heap_blks_read | 111186 | 0.371 |
| things_journal.idx_blks_hit | 1820506 | 6.073 |
| things_journal_seq.heap_blks_hit | 908332 | 3.030 |
| things_journal_seq.idx_blks_hit | 902079 | 3.009 |
| wal.wal_bytes | 1238256694 | 4130.496 |
| wal.wal_fpi | 115240 | 0.384 |
| wal.wal_records | 2020095 | 6.739 |
| wal.wal_sync | 75351 | 0.251 |
| wal.wal_write | 75351 | 0.251 |

## MongoDB

- total ops 299944 (+0 snapshots), achieved 499.9/s

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 15000 | 2.03 | 3.77 | 55.99 | — | 5581.7 MB | 54.9 MB | — |
| 1 | 15008 | 1.97 | 3.78 | 61.73 | — | 5582.3 MB | 54.9 MB | — |
| 2 | 15008 | 2.02 | 3.35 | 18.54 | — | 5584.8 MB | 54.9 MB | — |
| 3 | 15008 | 2.07 | 3.49 | 59.42 | — | 5586.3 MB | 54.9 MB | — |
| 4 | 15000 | 1.91 | 3.19 | 22.89 | — | 5590.2 MB | 54.9 MB | — |
| 5 | 15008 | 1.92 | 3.32 | 37.91 | — | 5590.6 MB | 54.9 MB | — |
| 6 | 15008 | 1.98 | 3.81 | 177.22 | — | 5595.4 MB | 54.9 MB | — |
| 7 | 15008 | 2.07 | 3.72 | 40.18 | — | 5596.8 MB | 54.9 MB | — |
| 8 | 15008 | 2.09 | 3.48 | 194.19 | — | 5602.5 MB | 54.9 MB | — |
| 9 | 15000 | 2.07 | 3.85 | 34.09 | — | 5604.3 MB | 54.9 MB | — |
| 10 | 15008 | 2.03 | 4.18 | 244.93 | — | 5607.7 MB | 54.9 MB | — |
| 11 | 15008 | 2.03 | 3.59 | 28.13 | — | 5607.7 MB | 54.9 MB | — |
| 12 | 15008 | 1.98 | 3.25 | 24.60 | — | 5613.3 MB | 54.9 MB | — |
| 13 | 15008 | 1.95 | 4.01 | 159.00 | — | 5615.1 MB | 54.9 MB | — |
| 14 | 15000 | 2.00 | 4.84 | 144.63 | — | 5619.3 MB | 54.9 MB | — |
| 15 | 15008 | 2.04 | 4.93 | 81.33 | — | 5622.2 MB | 54.9 MB | — |
| 16 | 15008 | 1.87 | 5.49 | 155.36 | — | 5624.1 MB | 54.9 MB | — |
| 17 | 15008 | 1.86 | 5.52 | 231.65 | — | 5624.1 MB | 54.9 MB | — |
| 18 | 15000 | 1.74 | 4.76 | 148.47 | — | 5629.6 MB | 54.9 MB | — |
| 19 | 14832 | 1.68 | 4.36 | 137.03 | — | 5630.4 MB | 54.9 MB | — |

- journal write concern: {"j": true} (realtime + snapshot writes stay JOURNALED)

Server-side counters over the run:

| counter (serverStatus) | delta over the run | per op |
|---|---|---|
| opcounters.insert | 599888 | 2.000 |
| opcounters.update | 19 | 0.000 |
| wiredTiger.cache.pages read into cache | 1133 | 0.004 |
| wiredTiger.cache.pages written from cache | 19794 | 0.066 |
| wiredTiger.log.log bytes written | 307181056 | 1024.128 |
| wiredTiger.log.log flush operations | 364386 | 1.215 |
| wiredTiger.log.log sync operations | 363252 | 1.211 |
| wiredTiger.log.log sync time duration (usecs) | 412914394 | 1376.638 |
| wiredTiger.log.log sync_dir operations | 3 | 0.000 |
| wiredTiger.log.log write operations | 599967 | 2.000 |
| wiredTiger.transaction.transaction checkpoint total time (msecs) | 1711 | 0.006 |
| wiredTiger.transaction.transaction checkpoints | 10 | 0.000 |


Note: standalone mongod has no oplog — WAL-delta column applies to PG only; Mongo growth is tracked via collStats storage+index size (plan deviation list).
