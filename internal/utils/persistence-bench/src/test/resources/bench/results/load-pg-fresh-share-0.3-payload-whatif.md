# Load evidence — PostgreSQL

- generated: 2026-10-20T18:57:31.292391Z
- corpus: count=250000 seed=42 freshShare=0.3 freshBase=2026-10-19T00:00:00Z
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: payload-whatif

- journal rows: 35070304
- journal_seq rows: 250000
- snapshot rows: 44271
- COPY: 212.1 s; constraints+indexes+ANALYZE: 230.4 s; total: 536.4 s

- fresh-only pids (share 0.3, rows at 2026-10-19T00:00:00Z + sn seconds; all others at 2026-01-01T00:00:00Z + sn seconds): 75194 of 250000; with snapshots: 2568 of 8581

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
