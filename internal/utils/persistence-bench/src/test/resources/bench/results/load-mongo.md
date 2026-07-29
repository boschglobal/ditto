# Load evidence — MongoDB

- generated: 2026-07-13T19:59:17.688317Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- journal docs: 35070304
- snapshot docs: 44271
- metadata docs: 0 (only written on the Pekko deleteFrom path — see plan deviation 6)
- insertMany: 739.3 s; index build: 667.1 s; total: 1539.4 s

## Sizes (collStats)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 35070304 | 36913.9 MB | 26468.7 MB | 1693.9 MB |
| things_snaps | 44271 | 263.0 MB | 273.2 MB | 3.0 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
