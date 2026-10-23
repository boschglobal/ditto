# Autovacuum log of the post-sweep settle run (server log excerpt)

- generated: 2026-10-23T01:00:01Z
- evidence label: limits-cl2000
- source: `docker logs ditto-persistence-bench-pg`, with `log_autovacuum_min_duration = 0` set for this run by `ALTER SYSTEM` (logging only); every `automatic vacuum` entry of `things_journal` and `things_snaps` from the C3 start (2026-10-22T22:49:17Z) to the end of the run; assembled from the server log by the session's helper, not written by the harness. Times are UTC; an entry is written when the vacuum ends, its start is the end minus `elapsed`.
- C3 sweep finished at 2026-10-22T23:05:38.790336Z (`c3-full-sweep-limits-cl2000.md`)

```text
2026-10-22 22:51:32.194 UTC [1268] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 400 scanned (73.13% of total)
	tuples: 3344 removed, 38726 remain, 0 are dead but not yet removable
	removable cutoff: 40951, which was 8 XIDs old when operation ended
	frozen: 276 pages from table (50.46% of total) had 17551 tuples frozen
	index scan needed: 348 pages from table (63.62% of total) had 5535 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 20.001 MB/s, avg write rate: 56.003 MB/s
	buffer usage: 1449 hits, 135 misses, 378 dirtied
	WAL usage: 1337 records, 375 full page images, 2319170 bytes
	system usage: CPU: user: 0.01 s, system: 0.02 s, elapsed: 0.05 s
2026-10-22 22:51:51.076 UTC [1091] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 503847 scanned (10.86% of total)
	tuples: 2095841 removed, 32805320 remain, 691140 are dead but not yet removable
	tuples missed: 471 dead from 63 pages not removed due to cleanup lock contention
	removable cutoff: 26558, which was 18494 XIDs old when operation ended
	frozen: 27497 pages from table (0.59% of total) had 105420 tuples frozen
	index scan needed: 316762 pages from table (6.82% of total) had 2271380 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 14836 newly deleted, 14836 currently deleted, 14836 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 7 newly deleted, 7 currently deleted, 0 reusable
	avg read rate: 49.751 MB/s, avg write rate: 66.349 MB/s
	buffer usage: 1241004 hits, 502789 misses, 670541 dirtied
	WAL usage: 1013319 records, 648396 full page images, 485123489 bytes
	system usage: CPU: user: 5.52 s, system: 26.70 s, elapsed: 78.95 s
2026-10-22 22:51:51.078 UTC [1091] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 41 scanned (7.50% of total)
	tuples: 352 removed, 38563 remain, 0 are dead but not yet removable
	removable cutoff: 45051, which was 1 XIDs old when operation ended
	frozen: 5 pages from table (0.91% of total) had 370 tuples frozen
	index scan needed: 31 pages from table (5.67% of total) had 554 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 829.366 MB/s, avg write rate: 37.986 MB/s
	buffer usage: 396 hits, 131 misses, 6 dirtied
	WAL usage: 105 records, 13 full page images, 68777 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 22:53:32.269 UTC [1612] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 457 scanned (83.55% of total)
	tuples: 3201 removed, 34453 remain, 0 are dead but not yet removable
	removable cutoff: 75008, which was 12 XIDs old when operation ended
	frozen: 127 pages from table (23.22% of total) had 8008 tuples frozen
	index scan needed: 333 pages from table (60.88% of total) had 4146 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 25.979 MB/s, avg write rate: 24.161 MB/s
	buffer usage: 1340 hits, 343 misses, 319 dirtied
	WAL usage: 1151 records, 316 full page images, 1797914 bytes
	system usage: CPU: user: 0.01 s, system: 0.07 s, elapsed: 0.10 s
2026-10-22 22:53:48.272 UTC [1440] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 496816 scanned (10.70% of total)
	tuples: 2723009 removed, 29520807 remain, 41316 are dead but not yet removable
	tuples missed: 14 dead from 2 pages not removed due to cleanup lock contention
	removable cutoff: 60261, which was 18666 XIDs old when operation ended
	frozen: 29741 pages from table (0.64% of total) had 119091 tuples frozen
	index scan needed: 401228 pages from table (8.64% of total) had 2859229 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 19499 newly deleted, 34335 currently deleted, 34335 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 35 newly deleted, 35 currently deleted, 7 reusable
	avg read rate: 50.392 MB/s, avg write rate: 81.304 MB/s
	buffer usage: 1385900 hits, 490909 misses, 792040 dirtied
	WAL usage: 1294502 records, 791491 full page images, 527815355 bytes
	system usage: CPU: user: 10.35 s, system: 24.12 s, elapsed: 76.10 s
2026-10-22 22:53:57.023 UTC [1440] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 120 scanned (21.94% of total)
	tuples: 767 removed, 33528 remain, 0 are dead but not yet removable
	removable cutoff: 81131, which was 1 XIDs old when operation ended
	frozen: 34 pages from table (6.22% of total) had 1981 tuples frozen
	index scan needed: 76 pages from table (13.89% of total) had 848 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 976.130 MB/s, avg write rate: 44.999 MB/s
	buffer usage: 448 hits, 282 misses, 13 dirtied
	WAL usage: 270 records, 61 full page images, 341616 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 22:55:32.232 UTC [1955] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 3008 removed, 29850 remain, 0 are dead but not yet removable
	removable cutoff: 105242, which was 17 XIDs old when operation ended
	frozen: 56 pages from table (10.24% of total) had 3144 tuples frozen
	index scan needed: 320 pages from table (58.50% of total) had 3338 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 50.677 MB/s, avg write rate: 53.594 MB/s
	buffer usage: 1572 hits, 278 misses, 294 dirtied
	WAL usage: 1045 records, 344 full page images, 1785581 bytes
	system usage: CPU: user: 0.00 s, system: 0.02 s, elapsed: 0.04 s
2026-10-22 22:56:16.225 UTC [1783] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 616030 scanned (13.27% of total)
	tuples: 2584641 removed, 26029366 remain, 848424 are dead but not yet removable
	tuples missed: 7 dead from 1 pages not removed due to cleanup lock contention
	removable cutoff: 92050, which was 22491 XIDs old when operation ended
	frozen: 28251 pages from table (0.61% of total) had 110637 tuples frozen
	index scan needed: 390264 pages from table (8.41% of total) had 2789803 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 18348 newly deleted, 52683 currently deleted, 52683 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 41 newly deleted, 41 currently deleted, 42 reusable
	avg read rate: 50.018 MB/s, avg write rate: 58.913 MB/s
	buffer usage: 1447206 hits, 666191 misses, 784673 dirtied
	WAL usage: 1241136 records, 798194 full page images, 560912284 bytes
	system usage: CPU: user: 10.55 s, system: 39.68 s, elapsed: 104.05 s
2026-10-22 22:56:16.233 UTC [1783] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 305 scanned (55.76% of total)
	tuples: 1246 removed, 29205 remain, 0 are dead but not yet removable
	removable cutoff: 114541, which was 3 XIDs old when operation ended
	frozen: 11 pages from table (2.01% of total) had 702 tuples frozen
	index scan needed: 134 pages from table (24.50% of total) had 1340 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 263.768 MB/s, avg write rate: 97.291 MB/s
	buffer usage: 914 hits, 244 misses, 90 dirtied
	WAL usage: 427 records, 129 full page images, 644661 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 22:57:30.808 UTC [2126] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 417819 scanned (9.00% of total)
	tuples: 2218543 removed, 23593004 remain, 111802 are dead but not yet removable
	tuples missed: 118 dead from 16 pages not removed due to cleanup lock contention
	removable cutoff: 119518, which was 14171 XIDs old when operation ended
	frozen: 22821 pages from table (0.49% of total) had 88661 tuples frozen
	index scan needed: 327682 pages from table (7.06% of total) had 2311615 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 15875 newly deleted, 68558 currently deleted, 68558 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 29 newly deleted, 29 currently deleted, 83 reusable
	avg read rate: 51.176 MB/s, avg write rate: 88.892 MB/s
	buffer usage: 1264992 hits, 383841 misses, 666724 dirtied
	WAL usage: 1054527 records, 665957 full page images, 414615294 bytes
	system usage: CPU: user: 9.03 s, system: 18.33 s, elapsed: 58.59 s
2026-10-22 22:57:32.293 UTC [2297] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2588 removed, 25837 remain, 47 are dead but not yet removable
	removable cutoff: 133688, which was 375 XIDs old when operation ended
	frozen: 20 pages from table (3.66% of total) had 917 tuples frozen
	index scan needed: 292 pages from table (53.38% of total) had 2673 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 36.167 MB/s, avg write rate: 24.229 MB/s
	buffer usage: 1516 hits, 306 misses, 205 dirtied
	WAL usage: 920 records, 279 full page images, 1295404 bytes
	system usage: CPU: user: 0.00 s, system: 0.04 s, elapsed: 0.06 s
2026-10-22 22:57:39.728 UTC [2126] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 154 scanned (28.15% of total)
	tuples: 289 removed, 25783 remain, 0 are dead but not yet removable
	removable cutoff: 135851, which was 1 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 53 pages from table (9.69% of total) had 289 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 1267.529 MB/s, avg write rate: 14.136 MB/s
	buffer usage: 506 hits, 269 misses, 3 dirtied
	WAL usage: 165 records, 3 full page images, 17430 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 22:59:32.392 UTC [2639] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 4111 removed, 21352 remain, 4 are dead but not yet removable
	tuples missed: 18 dead from 1 pages not removed due to cleanup lock contention
	removable cutoff: 166362, which was 18 XIDs old when operation ended
	frozen: 12 pages from table (2.19% of total) had 593 tuples frozen
	index scan needed: 385 pages from table (70.38% of total) had 4196 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 17.823 MB/s, avg write rate: 20.257 MB/s
	buffer usage: 1644 hits, 271 misses, 308 dirtied
	WAL usage: 1211 records, 378 full page images, 1517919 bytes
	system usage: CPU: user: 0.00 s, system: 0.09 s, elapsed: 0.11 s
2026-10-22 23:00:12.436 UTC [2474] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 552133 scanned (11.90% of total)
	tuples: 2712894 removed, 21899852 remain, 333608 are dead but not yet removable
	removable cutoff: 152865, which was 21422 XIDs old when operation ended
	frozen: 25920 pages from table (0.56% of total) had 101804 tuples frozen
	index scan needed: 402140 pages from table (8.66% of total) had 2884757 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 19279 newly deleted, 87837 currently deleted, 87837 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 41 newly deleted, 41 currently deleted, 112 reusable
	avg read rate: 46.139 MB/s, avg write rate: 62.882 MB/s
	buffer usage: 1451181 hits, 591703 misses, 806423 dirtied
	WAL usage: 1287736 records, 815072 full page images, 511983324 bytes
	system usage: CPU: user: 10.73 s, system: 38.63 s, elapsed: 100.18 s
2026-10-22 23:00:12.444 UTC [2474] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1117 removed, 20211 remain, 0 are dead but not yet removable
	removable cutoff: 174286, which was 3 XIDs old when operation ended
	frozen: 1 pages from table (0.18% of total) had 69 tuples frozen
	index scan needed: 148 pages from table (27.06% of total) had 1141 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 251.623 MB/s, avg write rate: 66.964 MB/s
	buffer usage: 1408 hits, 248 misses, 66 dirtied
	WAL usage: 459 records, 111 full page images, 422044 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 23:01:32.460 UTC [2975] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2714 removed, 17472 remain, 0 are dead but not yet removable
	removable cutoff: 193665, which was 9 XIDs old when operation ended
	frozen: 2 pages from table (0.37% of total) had 10 tuples frozen
	index scan needed: 291 pages from table (53.20% of total) had 2739 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 12.566 MB/s, avg write rate: 10.811 MB/s
	buffer usage: 1599 hits, 222 misses, 191 dirtied
	WAL usage: 905 records, 268 full page images, 915414 bytes
	system usage: CPU: user: 0.01 s, system: 0.11 s, elapsed: 0.13 s
2026-10-22 23:01:56.716 UTC [2810] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 461674 scanned (9.95% of total)
	tuples: 2246098 removed, 20290617 remain, 320615 are dead but not yet removable
	removable cutoff: 180630, which was 17708 XIDs old when operation ended
	frozen: 18562 pages from table (0.40% of total) had 76381 tuples frozen
	index scan needed: 334095 pages from table (7.20% of total) had 2384936 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 16059 newly deleted, 103896 currently deleted, 103896 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 33 newly deleted, 33 currently deleted, 153 reusable
	avg read rate: 46.820 MB/s, avg write rate: 61.579 MB/s
	buffer usage: 1275926 hits, 505870 misses, 665337 dirtied
	WAL usage: 1066148 records, 671413 full page images, 409156003 bytes
	system usage: CPU: user: 10.04 s, system: 33.69 s, elapsed: 84.41 s
2026-10-22 23:02:06.122 UTC [2810] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 972 removed, 16490 remain, 0 are dead but not yet removable
	removable cutoff: 200680, which was 3 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 138 pages from table (25.23% of total) had 982 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 255.427 MB/s, avg write rate: 104.547 MB/s
	buffer usage: 1431 hits, 215 misses, 88 dirtied
	WAL usage: 426 records, 61 full page images, 223529 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 23:03:32.399 UTC [3317] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 3511 removed, 12961 remain, 1 are dead but not yet removable
	removable cutoff: 225813, which was 8 XIDs old when operation ended
	new relfrozenxid: 225813, which is 225065 XIDs ahead of previous value
	frozen: 1 pages from table (0.18% of total) had 2 tuples frozen
	index scan needed: 346 pages from table (63.25% of total) had 3529 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 68.135 MB/s, avg write rate: 76.516 MB/s
	buffer usage: 1624 hits, 252 misses, 283 dirtied
	WAL usage: 1076 records, 373 full page images, 1041560 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.02 s
2026-10-22 23:03:38.833 UTC [3146] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 481954 scanned (10.38% of total)
	tuples: 2414876 removed, 16783904 remain, 293484 are dead but not yet removable
	removable cutoff: 210383, which was 16543 XIDs old when operation ended
	frozen: 18179 pages from table (0.39% of total) had 73319 tuples frozen
	index scan needed: 356850 pages from table (7.69% of total) had 2553321 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 17240 newly deleted, 121136 currently deleted, 121136 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 32 newly deleted, 32 currently deleted, 186 reusable
	avg read rate: 48.979 MB/s, avg write rate: 82.937 MB/s
	buffer usage: 1456949 hits, 416790 misses, 705761 dirtied
	WAL usage: 1138585 records, 723037 full page images, 417242597 bytes
	system usage: CPU: user: 8.58 s, system: 21.62 s, elapsed: 66.48 s
2026-10-22 23:05:32.466 UTC [3659] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 4160 removed, 8800 remain, 0 are dead but not yet removable
	removable cutoff: 256380, which was 11 XIDs old when operation ended
	new relfrozenxid: 256380, which is 30567 XIDs ahead of previous value
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 390 pages from table (71.30% of total) had 4161 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 42.872 MB/s, avg write rate: 44.421 MB/s
	buffer usage: 1671 hits, 249 misses, 258 dirtied
	WAL usage: 1216 records, 328 full page images, 699289 bytes
	system usage: CPU: user: 0.00 s, system: 0.02 s, elapsed: 0.04 s
2026-10-22 23:06:08.659 UTC [3494] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 560685 scanned (12.08% of total)
	tuples: 2687739 removed, 15463898 remain, 416598 are dead but not yet removable
	removable cutoff: 243494, which was 14493 XIDs old when operation ended
	frozen: 19853 pages from table (0.43% of total) had 77699 tuples frozen
	index scan needed: 400183 pages from table (8.62% of total) had 2860074 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 19094 newly deleted, 140230 currently deleted, 140230 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 40 newly deleted, 40 currently deleted, 217 reusable
	avg read rate: 44.609 MB/s, avg write rate: 65.393 MB/s
	buffer usage: 1561903 hits, 549632 misses, 805710 dirtied
	WAL usage: 1274205 records, 814617 full page images, 461626039 bytes
	system usage: CPU: user: 11.70 s, system: 36.64 s, elapsed: 96.25 s
2026-10-22 23:06:13.128 UTC [3494] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 244 scanned (44.61% of total)
	tuples: 225 removed, 8969 remain, 0 are dead but not yet removable
	removable cutoff: 257988, which was 0 XIDs old when operation ended
	new relfrozenxid: 257880, which is 1500 XIDs ahead of previous value
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 27 pages from table (4.94% of total) had 221 dead item identifiers removed
	index "things_snaps_pkey": pages: 370 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 751.953 MB/s, avg write rate: 3.255 MB/s
	buffer usage: 699 hits, 231 misses, 1 dirtied
	WAL usage: 87 records, 3 full page images, 18034 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.00 s
2026-10-22 23:06:48.912 UTC [3840] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 211926 scanned (4.57% of total)
	tuples: 1165966 removed, 12657267 remain, 0 are dead but not yet removable
	removable cutoff: 257988, which was 0 XIDs old when operation ended
	frozen: 8140 pages from table (0.18% of total) had 32729 tuples frozen
	index scan needed: 171676 pages from table (3.70% of total) had 1211805 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 8261 newly deleted, 148491 currently deleted, 140230 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 29 newly deleted, 30 currently deleted, 258 reusable
	avg read rate: 118.684 MB/s, avg write rate: 76.172 MB/s
	buffer usage: 838316 hits, 250027 misses, 160469 dirtied
	WAL usage: 550412 records, 159859 full page images, 125244684 bytes
	system usage: CPU: user: 3.13 s, system: 3.33 s, elapsed: 16.45 s
```
