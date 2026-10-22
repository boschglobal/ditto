# Load evidence — PostgreSQL

- generated: 2026-10-22T21:05:47.328210Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-whatif

- journal rows: 35070304
- journal_seq rows: 250000
- snapshot rows: 44271
- COPY: 208.9 s; constraints+indexes+ANALYZE: 230.3 s; total: 517.6 s

## Sizes

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36271.8 MB | 2015.3 MB | 38287.1 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.5 MB |
| things_snaps | 291.4 MB | 2.9 MB | 294.3 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 40.3 MB |
| things_snaps_pkey | 2.9 MB |

- schema: persistence v3; storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]

The index and storage-parameter statements are PostgresSchema.ddlStatements()'s own things_* statements; the tables were created without PKs for the COPY and the PKs added afterwards (design plan, deviation 8).
