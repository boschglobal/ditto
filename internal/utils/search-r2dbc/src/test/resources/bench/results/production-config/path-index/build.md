# Scoped index build (production PathIndexMaintenance)

- database: `jdbc:postgresql://localhost:55433/scratch`
- schema bootstrap before the maintenance: 416 ms
- started: 2026-10-01T03:36:11.588217Z, took 44128 ms
- outcome: `Outcome[status=APPLIED, built=[sf_path_trgm_ca5f5cd692cb58e9], repaired=[], dropped=[], failed=[]]`

## Log

```
2026-10-01T03:36:11.625Z INFO Applying search path index changes: drop INVALID [], drop unconfigured [], build [sf_path_trgm_ca5f5cd692cb58e9 (trigram on <features/*/properties/prop0>)]
2026-10-01T03:36:11.628Z INFO Building search path index sf_path_trgm_ca5f5cd692cb58e9 (1/1): trigram on <features/*/properties/prop0> (wpath </features/*/properties/prop0>) with CREATE INDEX CONCURRENTLY. search_flat stays readable and writable; until the index is valid, queries on this path use the table-wide index.
2026-10-01T03:36:41.658Z INFO Building search path index sf_path_trgm_ca5f5cd692cb58e9: phase 'building index', blocks 1271375/1327064 (95%), 30 s elapsed.
2026-10-01T03:36:55.705Z INFO Built search path index sf_path_trgm_ca5f5cd692cb58e9 in 44 s (51 MB).
2026-10-01T03:36:55.709Z INFO Search path index changes applied: Outcome[status=APPLIED, built=[sf_path_trgm_ca5f5cd692cb58e9], repaired=[], dropped=[], failed=[]]
```

## Sizes

```
search_flat_pkey 14 GB (15521562624 bytes), valid=true
sf_bool 87 MB (91389952 bytes), valid=true
sf_exists 754 MB (790413312 bytes), valid=true
sf_num 1050 MB (1101299712 bytes), valid=true
sf_path_trgm_ca5f5cd692cb58e9 51 MB (53485568 bytes), valid=true
sf_text 633 MB (663478272 bytes), valid=true
sf_trgm 304 MB (318799872 bytes), valid=true
```
