# C3 — full cleanup sweep

- generated: 2026-10-21T09:58:18.665817Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cldefault-pg

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 97.8 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 733.1 s (30230 rows/s), finished at 2026-10-21T10:12:09.864408Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 290 | 709720 | 9.7 | 23657 | 700211 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 130873/4641616, index passes 0) |
| 1 | 661 | 1644761 | 12.4 | 31168 | 1636846 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 688903/4641616, index passes 0) |
| 2 | 1079 | 2648407 | 13.9 | 33455 | 2633928 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1171954/4641616, index passes 0) |
| 3 | 1470 | 3613272 | 13.0 | 32162 | 3604892 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1963404/4641616, index passes 0) |
| 4 | 1827 | 4526784 | 11.9 | 30450 | 4516032 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 2571680/4641616, index passes 0) |
| 5 | 2170 | 5604853 | 11.4 | 35936 | 5566062 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 3184615/4641616, index passes 0) |
| 6 | 2607 | 6601572 | 14.6 | 33224 | 6566450 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 3750830/4641616, index passes 0) |
| 7 | 2985 | 7457019 | 12.6 | 28515 | 7440653 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 4175555/4641616, index passes 0) |
| 8 | 3351 | 8448470 | 12.2 | 33048 | 8418063 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 4583282/4641616, index passes 0) |
| 9 | 3674 | 9387382 | 10.8 | 31297 | 9360210 | 38288.0 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641616/4641616, index passes 0) |
| 10 | 4007 | 10260668 | 11.1 | 29110 | 10234664 | 38288.0 MB | 294.1 MB | things_journal vacuuming heap (heap 4641616/4641616, index passes 1) |
| 11 | 4335 | 11145392 | 10.9 | 29491 | 11122395 | 38288.0 MB | 294.1 MB | things_journal vacuuming heap (heap 4641616/4641616, index passes 1) |
| 12 | 4691 | 12076740 | 11.9 | 31045 | 4787944 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 42755/4641616, index passes 0) |
| 13 | 5055 | 12977181 | 12.1 | 30015 | 5657670 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 429967/4641616, index passes 0) |
| 14 | 5409 | 13852151 | 11.8 | 29166 | 6542356 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 623616/4641616, index passes 0) |
| 15 | 5753 | 14769596 | 11.5 | 30582 | 7447209 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 776541/4641616, index passes 0) |
| 16 | 6030 | 15653807 | 9.2 | 29474 | 8353167 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 910976/4641616, index passes 0) |
| 17 | 6303 | 16383616 | 9.1 | 24327 | 9070206 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1015042/4641616, index passes 0) |
| 18 | 6572 | 17087535 | 9.0 | 23464 | 9778341 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1133505/4641616, index passes 0) |
| 19 | 6897 | 17979817 | 10.8 | 29743 | 10670697 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1288599/4641616, index passes 0) |
| 20 | 7215 | 18910140 | 10.6 | 31011 | 11588341 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1435581/4641616, index passes 0) |
| 21 | 7634 | 19890958 | 14.0 | 32694 | 12537303 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1570370/4641616, index passes 0) |
| 22 | 7974 | 20732164 | 11.3 | 28040 | 13408474 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1667004/4641616, index passes 0) |
| 23 | 8466 | 21873745 | 16.4 | 38053 | 14564470 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 1794448/4641616, index passes 0) |


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
