# Load evidence — MongoDB

- generated: 2026-10-21T22:11:45.965355Z
- corpus: count=250000 seed=42
- backend(s): mongo
- containers: ditto-persistence-bench-mongo: cpus=4, memory=8 GiB, mongod --storageEngine wiredTiger --noscripting --wiredTigerCacheSizeGB 4
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-mongo

- journal docs: 35070304
- snapshot docs: 44271
- metadata docs: 0 (only written on the Pekko deleteFrom path — see plan deviation 6)
- insertMany: 490.3 s; index build: 461.0 s; total: 1049.2 s

## Sizes (collStats)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 35070304 | 36913.9 MB | 26453.6 MB | 1693.3 MB |
| things_snaps | 44271 | 263.0 MB | 272.9 MB | 3.2 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
