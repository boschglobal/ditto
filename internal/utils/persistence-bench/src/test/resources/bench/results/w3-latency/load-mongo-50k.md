# Load evidence — MongoDB

- generated: 2026-10-06T09:01:35.485738Z
- corpus: count=50000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- journal docs: 6953631
- snapshot docs: 8749
- metadata docs: 0 (only written on the Pekko deleteFrom path — see plan deviation 6)
- insertMany: 105.1 s; index build: 53.9 s; total: 163.0 s

## Sizes (collStats)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 6953631 | 7319.6 MB | 5245.2 MB | 335.2 MB |
| things_snaps | 8749 | 52.3 MB | 54.2 MB | 0.7 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
