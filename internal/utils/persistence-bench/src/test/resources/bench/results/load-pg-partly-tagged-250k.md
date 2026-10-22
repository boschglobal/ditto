# Load evidence — PostgreSQL

- generated: 2026-10-22T04:04:00.306080Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: 250k

- journal rows: 35070304
- journal_seq rows: 250000
- snapshot rows: 44271
- COPY: 210.8 s; constraints+indexes+ANALYZE: 218.2 s; total: 571.9 s

## Ping tag mix

Tags `always-alive` + `priority-<n>` (n = 0–9 per pid) on the tagged events; group draw = generator stream 6, priority = stream 7; cluster = the last count/100 pids in pid order, from `thing:bench.ns48:pid-00185848`. Checksum = SHA-256 over the sorted pids, each followed by `\n`.

| group | pids | share | tagged events | journal rows of these pids | sha256 |
|---|---|---|---|---|---|
| CLUSTER | 2,500 | 1.0000 % | every event | 319,529 | `6fc077c773951297702d9167b5c666221f7387b665bbc94f256d333b44b49f44` |
| SPREAD | 24,727 | 9.8908 % | every event | 3,388,468 | `df54d607290651b8261856767224ad1cfff8a4c2cd4775012543ca42bf01ef32` |
| OLDER_ONLY | 2,317 | 0.9268 % | every event but the newest | 372,615 | `a1173e3105d6049affb678e2734366dae57b123abe1cc6dce7af92fcc4b4ebae` |
| NEWEST_ONLY | 2,494 | 0.9976 % | the newest event only | 355,693 | `c3021f5a3348155ed579101f0c84a97288b10c9774ae54ad7ef0bd6301a8ac54` |
| UNTAGGED | 217,962 | 87.1848 % | none | 30,633,999 | `2e6a10eccd545f3862b9f04467c9ac5f5608a4dc608e0fb54a127b62fad73eb8` |

| expected set | pids | sha256 |
|---|---|---|
| ID order, any tagged event; priority order; the policies ping | 32,038 | `5761a81d53cd3e99ab4fdfec2843cc9fc434e9cad37ea74fb3324f6f03617988` |
| ID order, newest event tagged | 29,721 | `39ef64837a2833e3d9346405e6ec9a3b986b28efd6e45f6d71600a856790f757` |

- tagged journal rows (expected): 4,080,789

- tagged journal rows loaded: 4,080,789 (`tags @> ARRAY['always-alive']`)

## Sizes

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36444.5 MB | 2020.4 MB | 38464.9 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.5 MB |
| things_snaps | 291.4 MB | 2.9 MB | 294.3 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 45.3 MB |
| things_snaps_pkey | 2.9 MB |

- schema: persistence v3; storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]

The index and storage-parameter statements are PostgresSchema.ddlStatements()'s own things_* statements; the tables were created without PKs for the COPY and the PKs added afterwards (design plan, deviation 8).
