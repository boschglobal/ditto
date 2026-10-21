# C3 — full cleanup sweep

- generated: 2026-10-21T11:22:28.777985Z
- corpus: count=250000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: mongo-ref

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

## MongoDB

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 292.2 s (75839 rows/s), finished at 2026-10-21T11:27:21.164304Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 904 | 2189363 | 30.1 | 72979 | — | 28245.3 MB | 291.9 MB | — |
| 1 | 1907 | 4751841 | 33.4 | 85416 | — | 28245.3 MB | 291.9 MB | — |
| 2 | 2820 | 7021785 | 30.4 | 75665 | — | 28250.7 MB | 301.3 MB | — |
| 3 | 3646 | 9304382 | 27.5 | 76087 | — | 28250.7 MB | 301.3 MB | — |
| 4 | 4519 | 11578589 | 29.1 | 75807 | — | 28250.2 MB | 301.3 MB | — |
| 5 | 5410 | 13896204 | 29.7 | 77254 | — | 28250.2 MB | 301.3 MB | — |
| 6 | 6213 | 16118164 | 26.8 | 74065 | — | 28249.9 MB | 301.3 MB | — |
| 7 | 7017 | 18365807 | 26.8 | 74921 | — | 28249.9 MB | 301.3 MB | — |
| 8 | 7912 | 20617755 | 29.8 | 75065 | — | 28249.9 MB | 301.3 MB | — |


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
