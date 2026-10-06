# Load evidence — PostgreSQL

- generated: 2026-10-02T15:13:45.019684Z
- corpus: count=250000 seed=42 freshShare=0.3 freshBase=2026-10-01T00:00:00Z
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- journal rows: 35070304
- journal_seq rows: 250000
- snapshot rows: 44271
- COPY: 254.0 s; constraints+indexes+ANALYZE: 363.9 s; total: 721.5 s

- fresh-only pids (share 0.3, rows at 2026-10-01T00:00:00Z + sn seconds; all others at 2026-01-01T00:00:00Z + sn seconds): 75194 of 250000; with snapshots: 2568 of 8581

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
