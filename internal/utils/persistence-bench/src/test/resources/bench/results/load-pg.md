# Load evidence — PostgreSQL

- generated: 2026-07-13T19:33:34.597145Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- journal rows: 35070304
- journal_seq rows: 250000
- snapshot rows: 44271
- COPY: 271.6 s; constraints+indexes+ANALYZE: 258.3 s; total: 620.9 s

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
| things_snaps_lifecycle_idx | 0.0 MB |
| things_snaps_pkey | 2.9 MB |

End-state DDL is identical to PostgresSchema.ddlStatements(): tables were created without PKs for the COPY, then PK/indexes/autovacuum params added (design plan, deviation 8).
