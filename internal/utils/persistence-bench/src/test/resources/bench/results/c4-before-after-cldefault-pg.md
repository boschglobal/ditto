# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-21T10:16:50.489075Z
- corpus: count=250000 seed=42
- backend(s): pg
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: cldefault-pg

## PostgreSQL

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01]

### Autovacuum settle

- settle started at 2026-10-21T10:16:50.679Z, timeout 3600 s
- t+0s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 2904275/4641616, index passes 0) | waits: VacuumDelay 14, CPU 1
- t+15s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 2963335/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+30s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3016839/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+45s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3070206/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 5
- t+60s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3122984/4641616, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, WALWrite 1
- t+75s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3179480/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+90s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3231815/4641616, index passes 0) | waits: VacuumDelay 14, WALSync 1
- t+105s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3291582/4641616, index passes 0) | waits: VacuumDelay 15
- t+120s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3350309/4641616, index passes 0) | waits: VacuumDelay 12, DataFileRead 2, CPU 1
- t+135s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3408071/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 2, CPU 1, DataFileRead 1, DataFileWrite 1
- t+151s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3466729/4641616, index passes 0) | waits: VacuumDelay 8, WALSync 5, DataFileRead 1, DataFileWrite 1
- t+166s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3523570/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+181s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3580382/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 5
- t+196s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3640556/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+211s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3695412/4641616, index passes 0) | waits: VacuumDelay 13, DataFileRead 2
- t+226s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3755442/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+241s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3812084/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+256s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3870143/4641616, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+271s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3929144/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+286s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 3993513/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+301s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4053989/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+317s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4117013/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+332s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4173024/4641616, index passes 0) | waits: VacuumDelay 14, WALWrite 1
- t+347s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4228098/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+362s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4281233/4641616, index passes 0) | waits: VacuumDelay 14, WALSync 1
- t+377s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4335666/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+392s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4386261/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+407s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4438467/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+422s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4499426/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+437s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming indexes (heap 4524928/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+452s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming indexes (heap 4524928/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+468s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming indexes (heap 4524928/4641616, index passes 0) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+483s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming indexes (heap 4524928/4641616, index passes 0) | waits: VacuumDelay 12, CPU 2, WALSync 1
- t+498s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming indexes (heap 4524928/4641616, index passes 0) | waits: VacuumDelay 13, CPU 1, WALSync 1
- t+513s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, DataFileRead 2, CPU 1, WALSync 1
- t+528s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 15
- t+543s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+558s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+573s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+588s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 8, WALSync 4, DataFileWrite 2, DataFileRead 1
- t+603s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+618s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+634s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 8, WALSync 6, DataFileRead 1
- t+649s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+664s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+679s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+694s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+709s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, DataFileWrite 2, WALSync 2, DataFileRead 1
- t+724s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+739s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 3, DataFileRead 2
- t+754s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+769s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+785s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 5
- t+800s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+815s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+830s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+845s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, CPU 2, WALSync 1
- t+860s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+875s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+890s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+905s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 3, DataFileRead 2, CPU 1
- t+920s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+935s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+951s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, DataFileWrite 1
- t+966s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+981s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+996s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1011s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+1026s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+1041s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+1056s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, CPU 1
- t+1071s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: WALSync 8, VacuumDelay 7
- t+1086s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1101s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 3, CPU 2
- t+1117s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 8, WALSync 7
- t+1132s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1147s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 3, CPU 1, DataFileRead 1, DataFileWrite 1
- t+1162s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+1177s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1192s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+1207s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+1222s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, WALWrite 1
- t+1237s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1252s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileWrite 1
- t+1268s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4524928/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1283s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4553658/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+1298s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal scanning heap (heap 4601785/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1313s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming indexes (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, DataFileRead 3
- t+1328s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 2) | waits: VacuumDelay 10, WALSync 4, CPU 1
- t+1343s: live=19411123 dead=14858949 autovacuum_count=14 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 2) | waits: VacuumDelay 9, WALSync 4, DataFilePrefetch 1, DataFileRead 1
- t+1358s: live=13913271 dead=7355608 autovacuum_count=15 | vacuum: none | waits: DataFilePrefetch 2, VacuumDelay 1
- t+1373s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: none | waits: no autovacuum worker
- t+1388s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: none | waits: no autovacuum worker
- t+1403s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: none | waits: VacuumDelay 5, WALSync 2
- t+1419s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 70670/4641616, index passes 0) | waits: VacuumDelay 9, WALSync 5, DataFileWrite 1
- t+1434s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 179358/4641616, index passes 0) | waits: VacuumDelay 9, DataFileRead 3, WALSync 3
- t+1449s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 304340/4641616, index passes 0) | waits: VacuumDelay 10, DataFileRead 5
- t+1464s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 398457/4641616, index passes 0) | waits: VacuumDelay 10, DataFileRead 3, CPU 1, WALSync 1
- t+1479s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 515353/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+1494s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 662386/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+1509s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 788872/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1524s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 928775/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+1539s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1044988/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1554s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1180064/4641616, index passes 0) | waits: VacuumDelay 11, CPU 2, WALSync 2
- t+1569s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1267595/4641616, index passes 0) | waits: VacuumDelay 8, WALSync 3, DataFileWrite 2, CPU 1, WALWrite 1
- t+1585s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1357264/4641616, index passes 0) | waits: VacuumDelay 7, WALSync 5, DataFileRead 2, DataFileWrite 1
- t+1600s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1459641/4641616, index passes 0) | waits: VacuumDelay 9, WALSync 3, DataFileRead 2, CPU 1
- t+1615s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1563079/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+1630s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1667964/4641616, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+1645s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1764245/4641616, index passes 0) | waits: VacuumDelay 13, CPU 1, WALSync 1
- t+1660s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1855225/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 5
- t+1675s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1975336/4641616, index passes 0) | waits: VacuumDelay 9, WALSync 4, CPU 1, DataFileRead 1
- t+1690s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2096374/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, WALWrite 1
- t+1705s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2214856/4641616, index passes 0) | waits: VacuumDelay 8, WALSync 7
- t+1720s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2309185/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+1736s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2455806/4641616, index passes 0) | waits: VacuumDelay 13, CPU 2
- t+1751s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2569576/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1766s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2710910/4641616, index passes 0) | waits: VacuumDelay 14, WALSync 1
- t+1781s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2793765/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1796s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2909405/4641616, index passes 0) | waits: VacuumDelay 11, DataFileRead 2, CPU 1, WALSync 1
- t+1811s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3023649/4641616, index passes 0) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+1826s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3158317/4641616, index passes 0) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+1841s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3293158/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1856s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3407894/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+1871s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3534793/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+1886s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3631568/4641616, index passes 0) | waits: VacuumDelay 12, CPU 1, DataFileRead 1, WALSync 1
- t+1902s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3773477/4641616, index passes 0) | waits: VacuumDelay 14, DataFileRead 1
- t+1917s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3881228/4641616, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+1932s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3975071/4641616, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+1947s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4070745/4641616, index passes 0) | waits: VacuumDelay 9, WALSync 6
- t+1962s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4163373/4641616, index passes 0) | waits: VacuumDelay 14, DataFileRead 1
- t+1977s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4262521/4641616, index passes 0) | waits: VacuumDelay 11, WALSync 3, CPU 1
- t+1992s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4345275/4641616, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+2007s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4446521/4641616, index passes 0) | waits: VacuumDelay 12, CPU 1, DataFileRead 1, WALSync 1
- t+2022s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641616/4641616, index passes 0) | waits: VacuumDelay 9, WALSync 3, CPU 2, DataFileRead 1
- t+2037s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641616/4641616, index passes 0) | waits: VacuumDelay 12, CPU 1, DataFileRead 1, WALSync 1
- t+2052s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641616/4641616, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, DataFileWrite 1
- t+2068s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641616/4641616, index passes 0) | waits: VacuumDelay 7, WALSync 6, CPU 1, WALWrite 1
- t+2083s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2098s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2113s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2128s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2143s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+2158s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2173s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+2188s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2203s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, DataFileRead 3, DataFileWrite 1
- t+2218s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+2234s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+2249s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 10, DataFileRead 3, WALSync 2
- t+2264s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+2279s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+2294s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+2309s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+2324s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2339s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2354s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 5, CPU 1
- t+2369s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2384s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, CPU 1, DataFileWrite 1
- t+2400s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileRead 1
- t+2415s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2430s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, CPU 1, WALSync 1
- t+2445s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2460s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+2475s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 15
- t+2490s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+2505s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+2520s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 10, WALSync 3, CPU 2
- t+2535s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, CPU 1, DataFileRead 1, WALSync 1
- t+2551s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2566s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2581s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+2596s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2611s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2626s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+2641s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2656s: live=13105882 dead=9487165 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641616/4641616, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2671s: live=10136157 dead=11 autovacuum_count=16 | vacuum: none
- settled at t+2671s (dead < max(100k, 1% of live))

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 10127523 | 10127523 | 0 | 3 | 1 | 2026-10-21 14:01:21.548089+03 | 2026-10-21 12:59:53.322072+03 |
| things_journal_seq | 250000 | 250001 | 52 | 1 | 1 | 2026-10-21 12:50:19.188384+03 | 2026-10-21 12:59:56.595785+03 |
| things_snaps | 8581 | 8634 | 11 | 13 | 1 | 2026-10-21 13:12:21.232453+03 | 2026-10-21 12:59:56.641162+03 |

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | 1.11 \| 1.95 \| 2.12 | — |
| R2a tail P99 (n=50) | 4.12 \| 7.63 \| 8.34 | — |
| R2a tail OUTLIER (n=23) | 2.69 \| 7.07 \| 8.38 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) (n=23) | 1.38 \| 2.35 \| 2.51 | — |
| R2b full-scan P99 (post-sweep: only the tail remains) (n=50) | 1.37 \| 1.78 \| 1.84 | — |

### Natural sizes (files do NOT shrink after deletes — see note)

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36272.5 MB | 2015.8 MB | 38288.3 MB |
| things_journal_seq | 18.3 MB | 11.9 MB | 30.2 MB |
| things_snaps | 291.2 MB | 2.9 MB | 294.1 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 40.3 MB |
| things_snaps_pkey | 2.9 MB |

### OFFLINE reclamation

- skipped (bench.c4.offlineReclaim=false)

Deletes free space inside data files (reusable) but do not shrink them — the honest 'reclaimed space' figures are the OFFLINE reclamation deltas plus the live/dead tuple counts above.
