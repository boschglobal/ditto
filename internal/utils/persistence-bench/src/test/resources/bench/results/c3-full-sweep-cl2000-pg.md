# C3 — full cleanup sweep

- generated: 2026-10-21T08:12:30.138663Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl2000-pg

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=2000]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 45.1 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 778.3 s (28476 rows/s), finished at 2026-10-21T08:26:13.903426Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 356 | 899578 | 11.9 | 29986 | 898678 | 38288.0 MB | 294.1 MB | none |
| 1 | 746 | 1815733 | 13.0 | 30539 | 1797418 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1078443/4641616, index passes 0) |
| 2 | 1065 | 2590842 | 10.6 | 25837 | 480248 | 38287.8 MB | 294.1 MB | none |
| 3 | 1422 | 3510651 | 11.9 | 30660 | 1399476 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 2966832/4641592, index passes 0) |
| 4 | 1746 | 4322812 | 10.8 | 27072 | 787920 | 38287.9 MB | 294.1 MB | none |
| 5 | 2064 | 5286226 | 10.6 | 32114 | 1747118 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 4470384/4641592, index passes 0) |
| 6 | 2428 | 6082582 | 12.1 | 26545 | 1023749 | 38287.9 MB | 294.1 MB | none |
| 7 | 2833 | 7039093 | 13.5 | 31884 | 1983564 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 2605913/4641592, index passes 0) |
| 8 | 3114 | 7778205 | 9.4 | 24637 | 729380 | 38288.0 MB | 294.1 MB | none |
| 9 | 3441 | 8630047 | 10.9 | 28395 | 1563809 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 2607188/4641592, index passes 0) |
| 10 | 3598 | 9080542 | 5.2 | 15017 | 2032319 | 38288.0 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 11 | 3930 | 10036387 | 11.1 | 31862 | 925485 | 38288.0 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 12 | 4219 | 10893819 | 9.6 | 28581 | 990461 | 38288.0 MB | 294.1 MB | none |
| 13 | 4606 | 11783926 | 12.9 | 29670 | 1886260 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 3358820/4641592, index passes 0) |
| 14 | 4885 | 12563823 | 9.3 | 25997 | 478116 | 38288.1 MB | 294.1 MB | none |
| 15 | 5215 | 13426112 | 11.0 | 28743 | 1304604 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 4403906/4641592, index passes 0) |
| 16 | 5547 | 14251873 | 11.1 | 27525 | 1112740 | 38288.1 MB | 294.1 MB | none |
| 17 | 5932 | 15289376 | 12.8 | 34583 | 2139286 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 3476316/4641592, index passes 0) |
| 18 | 6187 | 16028540 | 8.5 | 24639 | 452177 | 38288.2 MB | 294.1 MB | none |
| 19 | 6519 | 16852922 | 11.1 | 27479 | 1272262 | 38288.2 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 20 | 6818 | 17780980 | 10.0 | 30935 | 1091325 | 38288.2 MB | 294.1 MB | none |
| 21 | 7168 | 18739592 | 11.7 | 31954 | 2053748 | 38288.2 MB | 294.1 MB | things_journal scanning heap (heap 3239724/4641592, index passes 0) |
| 22 | 7483 | 19522524 | 10.5 | 26098 | 318997 | 38288.2 MB | 294.1 MB | none |
| 23 | 7858 | 20507046 | 12.5 | 32817 | 1306915 | 38288.2 MB | 294.1 MB | things_journal scanning heap (heap 3200459/4641592, index passes 0) |
| 24 | 8213 | 21314042 | 11.8 | 26900 | 1050633 | 38288.3 MB | 294.1 MB | none |


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
