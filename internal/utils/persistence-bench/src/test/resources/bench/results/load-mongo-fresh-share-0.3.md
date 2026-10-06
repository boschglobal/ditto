# Load evidence — MongoDB

- generated: 2026-10-02T16:02:22.908171Z
- corpus: count=250000 seed=42 freshShare=0.3 freshBase=2026-10-01T00:00:00Z
- backend(s): mongo
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- journal docs: 35070304
- snapshot docs: 44271
- metadata docs: 0 (only written on the Pekko deleteFrom path — see plan deviation 6)
- insertMany: 508.6 s; index build: 515.5 s; total: 1138.1 s

- fresh-only pids (share 0.3, rows at 2026-10-01T00:00:00Z + sn seconds; all others at 2026-01-01T00:00:00Z + sn seconds): 75194 of 250000; with snapshots: 2568 of 8581

## Sizes (collStats)

| collection | docs | dataSize | storageSize | totalIndexSize |
|---|---|---|---|---|
| things_journal | 35070304 | 36913.9 MB | 26459.6 MB | 1693.0 MB |
| things_snaps | 44271 | 263.0 MB | 273.8 MB | 3.2 MB |
| things_realtime | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
| things_metadata | 0 | 0.0 MB | 0.0 MB | 0.0 MB |
