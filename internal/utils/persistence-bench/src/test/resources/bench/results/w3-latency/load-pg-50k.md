# Load evidence — PostgreSQL

- generated: 2026-10-06T08:58:49.208683Z
- corpus: count=50000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- journal rows: 6953631
- journal_seq rows: 50000
- snapshot rows: 8749
- COPY: 46.0 s; constraints+indexes+ANALYZE: 24.4 s; total: 74.2 s

## Sizes

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 7192.4 MB | 399.6 MB | 7592.1 MB |
| things_journal_seq | 4.2 MB | 2.4 MB | 6.6 MB |
| things_snaps | 58.3 MB | 0.6 MB | 58.9 MB |

| index | size |
|---|---|
| things_journal_pkey | 391.6 MB |
| things_journal_seq_pkey | 2.4 MB |
| things_journal_tags_idx | 8.0 MB |
| things_snaps_lifecycle_idx | 0.0 MB |
| things_snaps_pkey | 0.6 MB |

End-state DDL is identical to PostgresSchema.ddlStatements(): tables were created without PKs for the COPY, then PK/indexes/autovacuum params added (design plan, deviation 8).
