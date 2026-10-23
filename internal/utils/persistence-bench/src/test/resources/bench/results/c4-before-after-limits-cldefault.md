# C4 — before/after proof (compare against the committed pre-sweep r2-replay.md / load-*.md)

- generated: 2026-10-23T00:05:49.079018Z
- corpus: count=250000 seed=42
- backend(s): pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- protocol: 3 warmup + 50 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)
- evidence label: limits-cldefault

## PostgreSQL

- server: autovacuum_max_workers=3, autovacuum_naptime=1min, autovacuum_vacuum_cost_delay=2ms, autovacuum_vacuum_cost_limit=-1, autovacuum_work_mem=-1, maintenance_work_mem=64MB, server_version=16.15 (Debian 16.15-1.pgdg13+2), shared_buffers=4GB, vacuum_cost_limit=200, vacuum_cost_page_dirty=20, vacuum_cost_page_miss=2
- storage parameters: things_journal [autovacuum_vacuum_scale_factor=0.01]; things_journal_seq [autovacuum_vacuum_scale_factor=0.01]; things_snaps [autovacuum_vacuum_scale_factor=0.01]

### Autovacuum settle

- settle started at 2026-10-23T00:05:49.361Z, timeout 7200 s
- t+0s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1798840/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 1, DataFileWrite 1
- t+15s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1853409/4641592, index passes 0) | waits: VacuumDelay 12, DataFileRead 2, WALSync 1
- t+30s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1904994/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+45s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1949327/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+60s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 1998465/4641592, index passes 0) | waits: VacuumDelay 10, CPU 2, DataFileRead 1, WALSync 1, WALWrite 1
- t+75s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2045548/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+91s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2095401/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, CPU 1
- t+106s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2146121/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 1, DataFileWrite 1
- t+121s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2187938/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 4, DataFileRead 1, DataFileWrite 1
- t+136s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2236153/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 2
- t+151s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2287511/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, CPU 1
- t+166s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2331320/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+182s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2382292/4641592, index passes 0) | waits: VacuumDelay 14, WALSync 1
- t+197s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2432157/4641592, index passes 0) | waits: VacuumDelay 13, WALSync 2
- t+212s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2484483/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileRead 1
- t+227s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2532644/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileWrite 1
- t+242s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2579609/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+257s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2630167/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+273s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2676943/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 5, CPU 1
- t+288s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2718987/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+303s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2768837/4641592, index passes 0) | waits: VacuumDelay 13, CPU 1, WALSync 1
- t+318s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2817665/4641592, index passes 0) | waits: VacuumDelay 10, DataFileRead 2, WALSync 2, CPU 1
- t+333s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2861981/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 6
- t+348s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2913630/4641592, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+364s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 2964594/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+379s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3006477/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+394s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3051700/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+409s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3096552/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 1, DataFileWrite 1
- t+424s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3139798/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+439s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3187198/4641592, index passes 0) | waits: VacuumDelay 9, DataFileRead 3, WALSync 3
- t+455s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3235680/4641592, index passes 0) | waits: VacuumDelay 13, DataFileRead 2
- t+470s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3283416/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+485s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3334899/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, DataFileRead 1, DataFileWrite 1
- t+500s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3385052/4641592, index passes 0) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+515s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3432502/4641592, index passes 0) | waits: VacuumDelay 15
- t+530s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3482111/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+545s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3529922/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, CPU 1
- t+561s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3576808/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 5, DataFileWrite 1
- t+576s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3626932/4641592, index passes 0) | waits: VacuumDelay 8, WALSync 5, DataFileRead 1, DataFileWrite 1
- t+591s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3673586/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, CPU 1
- t+606s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3723543/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 6
- t+621s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3771583/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileWrite 1
- t+636s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 3779045/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 1, WALWrite 1
- t+652s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 3779045/4641592, index passes 0) | waits: VacuumDelay 11, DataFileRead 3, WALSync 1
- t+667s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 3779045/4641592, index passes 0) | waits: VacuumDelay 14, WALSync 1
- t+682s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 3779045/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 3, DataFileRead 2, DataFileWrite 1
- t+697s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 3779045/4641592, index passes 0) | waits: VacuumDelay 8, CPU 3, WALSync 3, DataFileRead 1
- t+712s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 3, CPU 1, DataFileWrite 1
- t+727s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 6, WALSync 5, DataFileRead 3, DataFileWrite 1
- t+743s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+758s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 3, DataFileRead 2, DataFileWrite 1
- t+773s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 7, DataFileRead 5, WALSync 3
- t+788s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 6, DataFileRead 1
- t+803s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+818s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 3, CPU 1, DataFileRead 1
- t+833s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+849s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+864s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+879s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 7, WALSync 7, DataFileRead 1
- t+894s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+909s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+924s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+939s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+955s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 4, DataFileRead 1, DataFileWrite 1, WALWrite 1
- t+970s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, DataFileRead 2, WALSync 1
- t+985s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1000s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1015s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+1030s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+1045s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+1061s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+1076s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+1091s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 4, CPU 2
- t+1106s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1121s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+1136s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 5
- t+1152s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 7
- t+1167s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 5
- t+1182s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 4, DataFileRead 1, DataFileWrite 1
- t+1197s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, DataFileRead 2, WALSync 1
- t+1212s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 4, DataFileRead 2
- t+1227s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+1242s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, CPU 1
- t+1258s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1273s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 5, CPU 1, DataFileRead 1
- t+1288s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, CPU 1, DataFileRead 1, WALSync 1
- t+1303s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 4, DataFileRead 2, DataFileWrite 1
- t+1318s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+1333s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 14, WALSync 1
- t+1349s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+1364s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+1379s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 4, DataFileRead 2, WALWrite 1
- t+1394s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+1409s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 6, DataFileWrite 1
- t+1424s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+1440s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 8, DataFileRead 3, WALSync 3, CPU 1
- t+1455s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+1470s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+1485s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+1500s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 5
- t+1515s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 10, DataFileRead 3, WALSync 2
- t+1531s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+1546s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+1561s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 3779045/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+1576s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3816737/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 6
- t+1591s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3860795/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+1606s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3906468/4641592, index passes 1) | waits: VacuumDelay 10, CPU 3, WALSync 2
- t+1621s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 3955115/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 6, DataFileRead 1
- t+1637s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4003584/4641592, index passes 1) | waits: VacuumDelay 11, CPU 1, DataFileRead 1, DataFileWrite 1, WALSync 1
- t+1652s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4050243/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+1667s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4095876/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+1682s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4139012/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+1697s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4184356/4641592, index passes 1) | waits: VacuumDelay 11, DataFileRead 2, WALSync 2
- t+1712s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4228931/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+1728s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4266840/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 3, CPU 1, DataFileRead 1
- t+1743s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4308705/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, CPU 1
- t+1758s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4349285/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+1773s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4389024/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+1788s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4431362/4641592, index passes 1) | waits: VacuumDelay 10, DataFileRead 3, WALSync 2
- t+1803s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4476282/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileWrite 1
- t+1818s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4522519/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+1834s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4566180/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, WALWrite 1
- t+1849s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal scanning heap (heap 4608762/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 4, CPU 1, DataFileRead 1
- t+1864s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, CPU 2, DataFileRead 2
- t+1879s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 10, CPU 2, DataFileRead 2, WALSync 1
- t+1894s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+1909s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 11, WALSync 4
- t+1925s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+1940s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 10, WALSync 3, CPU 1, DataFileRead 1
- t+1955s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 12, WALSync 3
- t+1970s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 13, WALSync 2
- t+1985s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 13, DataFileRead 1, WALSync 1
- t+2000s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 9, WALSync 4, DataFileRead 1, DataFileWrite 1
- t+2015s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 10, WALSync 4, WALWrite 1
- t+2031s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+2046s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 10, WALSync 5
- t+2061s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 9, WALSync 5, CPU 1
- t+2076s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+2091s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+2106s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2122s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+2137s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 11, WALSync 3, DataFileWrite 1
- t+2152s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+2167s: live=20835583 dead=13032594 autovacuum_count=15 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 2) | waits: VacuumDelay 5, DataFilePrefetch 3, WALSync 2
- t+2182s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: none | waits: VacuumDelay 9, WALSync 3, CPU 1, DataFileRead 1
- t+2197s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 151990/4641592, index passes 0) | waits: VacuumDelay 7, WALSync 4, DataFileRead 3, CPU 1
- t+2212s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 313725/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 2
- t+2228s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 405442/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 2
- t+2243s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 572160/4641592, index passes 0) | waits: VacuumDelay 8, WALSync 4, DataFileRead 3
- t+2258s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 746329/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+2273s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 903829/4641592, index passes 0) | waits: VacuumDelay 11, DataFileWrite 2, DataFileRead 1, WALSync 1
- t+2288s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1094366/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 5
- t+2303s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1222112/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, DataFileRead 1
- t+2318s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1328282/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 5
- t+2334s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1432367/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 3, DataFileWrite 1
- t+2349s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1559962/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileRead 1
- t+2364s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1675152/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 3, CPU 1, DataFileRead 1, DataFileWrite 1
- t+2379s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1773120/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 4, DataFileRead 1, DataFileWrite 1
- t+2394s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 1879601/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+2409s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2021805/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+2425s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2141240/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+2440s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2257432/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 3
- t+2455s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2415450/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileWrite 1
- t+2470s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2568713/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 5
- t+2485s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2731905/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 2
- t+2500s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2823076/4641592, index passes 0) | waits: VacuumDelay 8, WALSync 4, CPU 2, WALWrite 1
- t+2515s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 2945817/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 2, CPU 1, DataFileRead 1
- t+2531s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 3103022/4641592, index passes 0) | waits: VacuumDelay 9, WALSync 6
- t+2546s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 3240927/4641592, index passes 0) | waits: VacuumDelay 10, WALSync 3, DataFileRead 2
- t+2561s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 3384901/4641592, index passes 0) | waits: VacuumDelay 10, DataFileRead 4, WALSync 1
- t+2576s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 3513599/4641592, index passes 0) | waits: VacuumDelay 11, WALSync 4
- t+2591s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal scanning heap (heap 3637680/4641592, index passes 0) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+2606s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) | waits: VacuumDelay 10, DataFileRead 4, CPU 1
- t+2622s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) | waits: VacuumDelay 15
- t+2637s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming indexes (heap 4641592/4641592, index passes 0) | waits: VacuumDelay 12, DataFileRead 2, CPU 1
- t+2652s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2667s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileRead 1
- t+2682s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: WALSync 8, VacuumDelay 6, DataFileRead 1
- t+2697s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 8, WALSync 4, CPU 1, DataFileRead 1, DataFileWrite 1
- t+2712s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, DataFileRead 3, WALSync 1
- t+2728s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 5, DataFileRead 1
- t+2743s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, WALWrite 1
- t+2758s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2773s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 3, CPU 2, DataFileWrite 1
- t+2788s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, CPU 1
- t+2803s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2819s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2834s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 4
- t+2849s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 3, DataFileRead 2, CPU 1
- t+2864s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, DataFileWrite 1
- t+2879s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+2894s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 13, WALSync 2
- t+2909s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 2, CPU 1
- t+2925s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 9, WALSync 3, CPU 2, DataFileRead 1
- t+2940s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, CPU 1, DataFileRead 1, DataFileWrite 1, WALSync 1
- t+2955s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 10, DataFileRead 3, WALSync 2
- t+2970s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, CPU 1
- t+2985s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 10, WALSync 4, DataFileWrite 1
- t+3000s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 12, WALSync 3
- t+3016s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 11, WALSync 3, DataFileRead 1
- t+3031s: live=12931136 dead=5667539 autovacuum_count=16 | vacuum: things_journal vacuuming heap (heap 4641592/4641592, index passes 1) | waits: VacuumDelay 6, WALSync 2
- t+3046s: live=11136802 dead=0 autovacuum_count=17 | vacuum: none
- settled at t+3046s (dead < max(100k, 1% of live))

| table | reltuples | live | dead | autovacuum_count | vacuum_count | last_autovacuum | last_vacuum |
|---|---|---|---|---|---|---|---|
| things_journal | 11128168 | 11128168 | 0 | 4 | 1 | 2026-10-23 03:56:28.220943+03 | 2026-10-23 02:50:37.343332+03 |
| things_journal_seq | 250000 | 250001 | 53 | 1 | 1 | 2026-10-23 02:14:51.242776+03 | 2026-10-23 02:50:40.181402+03 |
| things_snaps | 8634 | 8634 | 0 | 13 | 1 | 2026-10-23 03:04:11.530855+03 | 2026-10-23 02:50:40.210255+03 |

### Post-sweep replay (distinct class pids from corpus index 0; n = timed reads)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| R2a tail MEDIAN (n=50) | 1.34 \| 3.49 \| 7.15 | — |
| R2a tail P99 (n=50) | 5.63 \| 12.86 \| 21.56 | — |
| R2a tail OUTLIER (n=23) | 4.35 \| 12.30 \| 16.19 | — |
| R2b full-scan OUTLIER (post-sweep: only the tail remains) (n=23) | 1.39 \| 2.30 \| 2.33 | — |
| R2b full-scan P99 (post-sweep: only the tail remains) (n=50) | 1.39 \| 2.17 \| 4.15 | — |

### PG plan (R2b full-scan replay of an untimed P99 pid, its first read after the sweep)

```
Index Scan using things_journal_pkey on things_journal  (cost=0.56..3786.21 rows=922 width=157) (actual time=0.456..3.842 rows=234 loops=1)
  Index Cond: ((pid = 'thing:bench.ns01:pid-00106368'::text) AND (sn >= 1) AND (sn <= '9223372036854775807'::bigint))
  Buffers: shared hit=8 read=34
Planning:
  Buffers: shared hit=3
Planning Time: 0.030 ms
Execution Time: 3.852 ms
```

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
