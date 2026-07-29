# M1 — cleanup under live traffic

- generated: 2026-07-13T21:06:26.949490Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- churn: 500/s target, 8 workers x 1 pid(s)/worker, 600 s; sweep pace: unthrottled, cycling continuously
- churn is CONCENTRATED (pidsPerWorker=1 via the mixed stage) so pids cross the 500-event snapshot threshold and the sweeper finds live debris; W3 spreads the same rate over workers*100 pids, so per-pid contention differs — compare latency windows directionally, not 1:1

## PostgreSQL

- churn: 299888 ops (+592 snapshots), achieved 499.8/s
- concurrent sweep: 222 full cycles, 296576 rows deleted

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14984 | 2.70 | 4.27 | 43.26 | 0.0 MB | 14378.8 MB | 59.9 MB | 12008 |
| 1 | 15016 | 2.62 | 3.86 | 40.84 | 19.4 MB | 14392.6 MB | 59.9 MB | 28040 |
| 2 | 15008 | 2.65 | 3.97 | 34.05 | 19.4 MB | 14406.6 MB | 59.9 MB | 44072 |
| 3 | 15000 | 2.64 | 3.90 | 75.94 | 19.2 MB | 14420.4 MB | 59.9 MB | 56096 |
| 4 | 15008 | 2.66 | 4.19 | 46.83 | 19.4 MB | 14434.3 MB | 59.9 MB | 72128 |
| 5 | 15000 | 2.66 | 4.65 | 45.86 | 20.3 MB | 14448.2 MB | 59.9 MB | 88000 |
| 6 | 15008 | 2.76 | 4.57 | 46.99 | 19.7 MB | 14461.8 MB | 59.9 MB | 104032 |
| 7 | 15000 | 2.69 | 4.86 | 32.97 | 19.3 MB | 14475.0 MB | 59.9 MB | 116056 |
| 8 | 15000 | 2.66 | 4.68 | 19.73 | 19.5 MB | 14488.3 MB | 59.9 MB | 132088 |
| 9 | 15008 | 2.66 | 4.82 | 39.56 | 21.0 MB | 14501.5 MB | 59.9 MB | 148120 |
| 10 | 15000 | 2.76 | 6.18 | 62.58 | 25.7 MB | 14514.9 MB | 59.9 MB | 160644 |
| 11 | 15008 | 2.70 | 6.51 | 249.93 | 25.3 MB | 14528.2 MB | 59.5 MB | 176000 |
| 12 | 15000 | 2.69 | 7.96 | 181.26 | 25.6 MB | 14541.6 MB | 59.5 MB | 192032 |
| 13 | 15000 | 2.69 | 6.02 | 120.15 | 25.6 MB | 14554.9 MB | 59.5 MB | 208064 |
| 14 | 15008 | 2.73 | 6.40 | 337.93 | 25.5 MB | 14568.3 MB | 59.5 MB | 224096 |
| 15 | 15000 | 2.72 | 7.81 | 111.41 | 27.9 MB | 14581.7 MB | 59.5 MB | 236120 |
| 16 | 15008 | 2.68 | 5.70 | 139.19 | 25.7 MB | 14595.1 MB | 59.5 MB | 252152 |
| 17 | 15000 | 2.73 | 7.01 | 208.59 | 25.5 MB | 14608.3 MB | 59.5 MB | 268000 |
| 18 | 15008 | 2.68 | 7.01 | 103.74 | 25.6 MB | 14621.7 MB | 59.5 MB | 284032 |
| 19 | 14824 | 2.65 | 5.64 | 143.58 | 25.1 MB | 14634.9 MB | 59.5 MB | 296056 |

## MongoDB

- churn: 299952 ops (+592 snapshots), achieved 499.9/s
- concurrent sweep: 189 full cycles, 296576 rows deleted

| window | ops | p50 ms | p95 ms | max ms | WAL delta | journal size | snaps size | dead tuples |
|---|---|---|---|---|---|---|---|---|
| 0 | 14936 | 2.26 | 17.83 | 185.90 | — | 11860.2 MB | 74.6 MB | — |
| 1 | 15072 | 2.17 | 16.72 | 280.02 | — | 11860.2 MB | 74.6 MB | — |
| 2 | 15009 | 2.11 | 16.52 | 461.53 | — | 11860.2 MB | 74.6 MB | — |
| 3 | 14983 | 2.13 | 15.69 | 258.02 | — | 11860.2 MB | 74.6 MB | — |
| 4 | 15032 | 2.01 | 17.56 | 373.11 | — | 11860.2 MB | 74.6 MB | — |
| 5 | 14960 | 1.99 | 16.39 | 437.56 | — | 11860.2 MB | 74.6 MB | — |
| 6 | 15016 | 2.10 | 19.15 | 199.59 | — | 11860.2 MB | 74.6 MB | — |
| 7 | 14976 | 2.13 | 19.08 | 1109.10 | — | 11860.2 MB | 74.6 MB | — |
| 8 | 15024 | 2.34 | 11.47 | 281.38 | — | 11860.2 MB | 74.6 MB | — |
| 9 | 15048 | 2.38 | 9.25 | 435.48 | — | 11860.2 MB | 74.6 MB | — |
| 10 | 15000 | 2.21 | 10.69 | 274.28 | — | 11860.2 MB | 74.6 MB | — |
| 11 | 14936 | 2.07 | 7.34 | 220.35 | — | 11860.2 MB | 74.6 MB | — |
| 12 | 15024 | 2.08 | 7.05 | 409.76 | — | 11860.2 MB | 74.6 MB | — |
| 13 | 15056 | 2.13 | 8.80 | 334.27 | — | 11860.2 MB | 74.6 MB | — |
| 14 | 15008 | 2.11 | 8.87 | 230.05 | — | 11860.2 MB | 74.6 MB | — |
| 15 | 15008 | 2.17 | 7.47 | 272.68 | — | 11860.2 MB | 74.6 MB | — |
| 16 | 14984 | 2.17 | 8.78 | 411.63 | — | 11860.2 MB | 74.6 MB | — |
| 17 | 15024 | 2.30 | 9.43 | 440.68 | — | 11860.2 MB | 74.6 MB | — |
| 18 | 15008 | 2.25 | 8.23 | 235.36 | — | 11860.2 MB | 74.6 MB | — |
| 19 | 14848 | 2.06 | 7.53 | 249.47 | — | 11860.2 MB | 74.6 MB | — |


Interference read-out: compare each backend's churn p50/p95/max windows against the same backend's windows in w3-sustained-churn.md (churn without sweep).
