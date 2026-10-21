# Load evidence — MongoDB

- generated: 2026-10-21T11:19:53.575619Z
- corpus: count=250000 seed=42
- backend(s): mongo
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: mongo-ref

- journal docs: 35070304
- snapshot docs: 44271
- metadata docs: 0 (only written on the Pekko deleteFrom path — see plan deviation 6)
- insertMany: 483.5 s; index build: 446.0 s; total: 1021.8 s

## Sizes (collStats)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 35070304 | 36913.9 MB | 26470.0 MB | 1693.3 MB |
| things_snaps | 44271 | 263.0 MB | 273.2 MB | 3.2 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
