# C3 — full cleanup sweep

- generated: 2026-10-21T12:23:48.407784Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cl800-pg

- pace: unthrottled (sequential, one batch in flight — measures backend capability)

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_cost_limit=800]
- corpus settled before the sweep: manual VACUUM (ANALYZE) of things_journal, things_journal_seq, things_snaps, 74.7 s (bench protocol step; in production insert-triggered autovacuum keeps the tables vacuumed)

## PostgreSQL

- pids processed: 8581
- event rows deleted: 22126919
- snapshot rows deleted: 35690
- delete batches: 399805
- duration: 966.8 s (22924 rows/s), finished at 2026-10-21T12:41:10.110435Z
- at default credit pace this would take ~111.1 hours

| window | pids done | rows deleted | pids/s | rows/s | dead tuples | journal size | snaps size | autovacuum in progress |
|---|---|---|---|---|---|---|---|---|
| 0 | 262 | 635437 | 8.7 | 21181 | 627684 | 38288.0 MB | 294.1 MB | none |
| 1 | 551 | 1437668 | 9.6 | 26741 | 1436271 | 38288.0 MB | 294.1 MB | things_journal vacuuming heap (heap 4641616/4641616, index passes 1) |
| 2 | 884 | 2150518 | 11.1 | 23762 | 835933 | 38287.8 MB | 294.1 MB | none |
| 3 | 1218 | 2969889 | 11.1 | 27312 | 1664322 | 38287.8 MB | 294.1 MB | things_journal scanning heap (heap 2353147/4641592, index passes 0) |
| 4 | 1495 | 3689376 | 9.2 | 23983 | 837231 | 38287.9 MB | 294.1 MB | none |
| 5 | 1757 | 4381624 | 8.7 | 23075 | 1518171 | 38287.9 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 6 | 2000 | 5049980 | 8.1 | 22279 | 650836 | 38287.9 MB | 294.1 MB | none |
| 7 | 2264 | 5763984 | 8.8 | 23800 | 1728191 | 38287.9 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 8 | 2569 | 6448741 | 10.2 | 22825 | 468562 | 38287.9 MB | 294.1 MB | none |
| 9 | 2894 | 7169232 | 10.8 | 24016 | 1182067 | 38287.9 MB | 294.1 MB | things_journal scanning heap (heap 3516497/4641592, index passes 0) |
| 10 | 3148 | 7874832 | 8.5 | 23520 | 694272 | 38288.0 MB | 294.1 MB | none |
| 11 | 3435 | 8620449 | 9.6 | 24854 | 1426826 | 38288.0 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 12 | 3671 | 9375684 | 7.9 | 25175 | 502447 | 38288.0 MB | 294.1 MB | none |
| 13 | 3944 | 10107785 | 9.1 | 24403 | 1906474 | 38288.0 MB | 294.1 MB | none |
| 14 | 4232 | 10922352 | 9.6 | 27152 | 2736373 | 38288.0 MB | 294.1 MB | none |
| 15 | 4564 | 11713960 | 11.1 | 26387 | 3538425 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 2391890/4641592, index passes 0) |
| 16 | 4783 | 12332265 | 7.3 | 20610 | 4130864 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 3526179/4641592, index passes 0) |
| 17 | 5028 | 12905017 | 8.2 | 19092 | 4720861 | 38288.0 MB | 294.1 MB | things_journal scanning heap (heap 4444762/4641592, index passes 0) |
| 18 | 5270 | 13507040 | 8.1 | 20067 | 5310270 | 38288.1 MB | 294.1 MB | things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) |
| 19 | 5470 | 14081279 | 6.7 | 19141 | 5893078 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 20 | 5708 | 14669986 | 7.9 | 19624 | 6487992 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 21 | 6006 | 15563003 | 9.9 | 29767 | 7382114 | 38288.1 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 22 | 6226 | 16192492 | 7.3 | 20983 | 1022353 | 38288.1 MB | 294.1 MB | none |
| 23 | 6479 | 16766732 | 8.4 | 19141 | 1611690 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 690888/4641592, index passes 0) |
| 24 | 6674 | 17363808 | 6.5 | 19903 | 2196322 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 1489016/4641592, index passes 0) |
| 25 | 6877 | 17923630 | 6.8 | 18661 | 2762633 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 2170931/4641592, index passes 0) |
| 26 | 7064 | 18498307 | 6.2 | 19156 | 3343510 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 2834815/4641592, index passes 0) |
| 27 | 7303 | 19081608 | 8.0 | 19443 | 3886295 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 3473886/4641592, index passes 0) |
| 28 | 7626 | 19869836 | 10.8 | 26274 | 4704526 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 4053008/4641592, index passes 0) |
| 29 | 7866 | 20521754 | 8.0 | 21731 | 5343758 | 38288.1 MB | 294.1 MB | things_journal scanning heap (heap 4451731/4641592, index passes 0) |
| 30 | 8269 | 21490783 | 13.4 | 32301 | 6331012 | 38288.2 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |
| 31 | 8539 | 22026312 | 9.0 | 17851 | 6850179 | 38288.2 MB | 294.1 MB | things_journal vacuuming heap (heap 4641592/4641592, index passes 1) |


At Ditto's default credit pace (3 batches x 100 rows / 3 s = 100 rows/s max) the same sweep would need [total batches / 1 per second] — quoted per backend above. Structure is 1:1 with PersistenceCleanupActor/Cleanup; only the pace differs (plan deviation 3).
