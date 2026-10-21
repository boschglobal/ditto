# Load evidence — PostgreSQL

- generated: 2026-10-21T12:21:17.689947Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl800-pg

- journal rows: 35070304
- journal_seq rows: 250000
- snapshot rows: 44271
- COPY: 201.8 s; constraints+indexes+ANALYZE: 219.6 s; total: 488.5 s

## Sizes

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36271.6 MB | 2015.3 MB | 38286.9 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.5 MB |
| things_snaps | 291.4 MB | 2.9 MB | 294.3 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 40.3 MB |
| things_snaps_pkey | 2.9 MB |

- schema: persistence v3; storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800] (things_journal/things_snaps OVERRIDDEN by bench.pg.reloptions: not production values)

The index and storage-parameter statements are PostgresSchema.ddlStatements()'s own things_* statements; the tables were created without PKs for the COPY and the PKs added afterwards (design plan, deviation 8).
