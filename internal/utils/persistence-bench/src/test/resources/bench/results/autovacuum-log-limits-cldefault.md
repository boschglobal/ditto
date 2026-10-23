# Autovacuum log of the post-sweep settle run (server log excerpt)

- generated: 2026-10-23T01:00:01Z
- evidence label: limits-cldefault
- source: `docker logs ditto-persistence-bench-pg`, with `log_autovacuum_min_duration = 0` set for this run by `ALTER SYSTEM` (logging only); every `automatic vacuum` entry of `things_journal` and `things_snaps` from the C3 start (2026-10-22T23:50:37Z) to the end of the run; assembled from the server log by the session's helper, not written by the harness. Times are UTC; an entry is written when the vacuum ends, its start is the end minus `elapsed`.
- C3 sweep finished at 2026-10-23T00:03:36.760146Z (`c3-full-sweep-limits-cldefault.md`)

```text
2026-10-22 23:52:11.344 UTC [1219] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 312 scanned (57.04% of total)
	tuples: 2585 removed, 39972 remain, 0 are dead but not yet removable
	removable cutoff: 32281, which was 91 XIDs old when operation ended
	frozen: 200 pages from table (36.56% of total) had 12614 tuples frozen
	index scan needed: 270 pages from table (49.36% of total) had 4284 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 11.938 MB/s, avg write rate: 8.775 MB/s
	buffer usage: 1013 hits, 317 misses, 233 dirtied
	WAL usage: 1023 records, 277 full page images, 1697514 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.20 s
2026-10-22 23:53:11.279 UTC [1392] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 237 scanned (43.33% of total)
	tuples: 1800 removed, 38848 remain, 1 are dead but not yet removable
	removable cutoff: 53365, which was 29 XIDs old when operation ended
	frozen: 134 pages from table (24.50% of total) had 8446 tuples frozen
	index scan needed: 182 pages from table (33.27% of total) had 2958 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 16.457 MB/s, avg write rate: 9.000 MB/s
	buffer usage: 836 hits, 256 misses, 140 dirtied
	WAL usage: 693 records, 182 full page images, 1100539 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.12 s
2026-10-22 23:54:11.336 UTC [1564] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 348 scanned (63.62% of total)
	tuples: 2372 removed, 34405 remain, 11 are dead but not yet removable
	removable cutoff: 73723, which was 55 XIDs old when operation ended
	frozen: 83 pages from table (15.17% of total) had 5151 tuples frozen
	index scan needed: 241 pages from table (44.06% of total) had 2822 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 9.732 MB/s, avg write rate: 8.679 MB/s
	buffer usage: 1179 hits, 194 misses, 173 dirtied
	WAL usage: 827 records, 256 full page images, 1429239 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.15 s
2026-10-22 23:55:11.364 UTC [1741] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 515 scanned (94.15% of total)
	tuples: 2422 removed, 31529 remain, 0 are dead but not yet removable
	removable cutoff: 94055, which was 60 XIDs old when operation ended
	frozen: 46 pages from table (8.41% of total) had 2872 tuples frozen
	index scan needed: 287 pages from table (52.47% of total) had 2803 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 7.971 MB/s, avg write rate: 8.211 MB/s
	buffer usage: 1587 hits, 166 misses, 171 dirtied
	WAL usage: 922 records, 249 full page images, 1354380 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.16 s
2026-10-22 23:56:11.473 UTC [1913] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2756 removed, 28421 remain, 4 are dead but not yet removable
	removable cutoff: 115182, which was 80 XIDs old when operation ended
	frozen: 35 pages from table (6.40% of total) had 1841 tuples frozen
	index scan needed: 286 pages from table (52.29% of total) had 2983 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 4.677 MB/s, avg write rate: 8.081 MB/s
	buffer usage: 1669 hits, 147 misses, 254 dirtied
	WAL usage: 917 records, 252 full page images, 1251925 bytes
	system usage: CPU: user: 0.00 s, system: 0.01 s, elapsed: 0.24 s
2026-10-22 23:57:11.413 UTC [2084] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2665 removed, 25681 remain, 0 are dead but not yet removable
	removable cutoff: 134873, which was 50 XIDs old when operation ended
	frozen: 21 pages from table (3.84% of total) had 1114 tuples frozen
	index scan needed: 298 pages from table (54.48% of total) had 2740 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 5.150 MB/s, avg write rate: 7.134 MB/s
	buffer usage: 1719 hits, 109 misses, 151 dirtied
	WAL usage: 945 records, 234 full page images, 1086905 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.16 s
2026-10-22 23:58:11.475 UTC [2255] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2515 removed, 23111 remain, 8 are dead but not yet removable
	removable cutoff: 153473, which was 42 XIDs old when operation ended
	frozen: 8 pages from table (1.46% of total) had 392 tuples frozen
	index scan needed: 294 pages from table (53.75% of total) had 2570 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 3.146 MB/s, avg write rate: 7.807 MB/s
	buffer usage: 1743 hits, 81 misses, 201 dirtied
	WAL usage: 916 records, 283 full page images, 1224869 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.20 s
2026-10-22 23:58:17.176 UTC [1041] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 915396 scanned (19.72% of total)
	tuples: 891213 removed, 33859489 remain, 4196886 are dead but not yet removable
	removable cutoff: 11756, which was 143572 XIDs old when operation ended
	frozen: 12249 pages from table (0.26% of total) had 46077 tuples frozen
	index scan needed: 185010 pages from table (3.99% of total) had 1217236 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 6340 newly deleted, 6340 currently deleted, 6340 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 6 newly deleted, 6 currently deleted, 0 reusable
	avg read rate: 16.031 MB/s, avg write rate: 17.110 MB/s
	buffer usage: 1475564 hits, 874273 misses, 933127 dirtied
	WAL usage: 507459 records, 353589 full page images, 545398663 bytes
	system usage: CPU: user: 15.11 s, system: 56.46 s, elapsed: 426.06 s
2026-10-23 00:00:11.626 UTC [2597] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 5260 removed, 17768 remain, 0 are dead but not yet removable
	removable cutoff: 191382, which was 85 XIDs old when operation ended
	frozen: 14 pages from table (2.56% of total) had 420 tuples frozen
	index scan needed: 415 pages from table (75.87% of total) had 5343 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 5.646 MB/s, avg write rate: 9.367 MB/s
	buffer usage: 1722 hits, 223 misses, 370 dirtied
	WAL usage: 1311 records, 461 full page images, 1554994 bytes
	system usage: CPU: user: 0.01 s, system: 0.01 s, elapsed: 0.30 s
2026-10-23 00:01:11.507 UTC [2773] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2859 removed, 14876 remain, 0 are dead but not yet removable
	removable cutoff: 212056, which was 24 XIDs old when operation ended
	frozen: 2 pages from table (0.37% of total) had 9 tuples frozen
	index scan needed: 315 pages from table (57.59% of total) had 2892 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 9.338 MB/s, avg write rate: 7.866 MB/s
	buffer usage: 1642 hits, 203 misses, 171 dirtied
	WAL usage: 979 records, 259 full page images, 803348 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.16 s
2026-10-23 00:02:11.557 UTC [2944] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2622 removed, 12251 remain, 0 are dead but not yet removable
	removable cutoff: 231036, which was 65 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 296 pages from table (54.11% of total) had 2625 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 6.838 MB/s, avg write rate: 8.206 MB/s
	buffer usage: 1656 hits, 170 misses, 204 dirtied
	WAL usage: 917 records, 288 full page images, 774083 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.19 s
2026-10-23 00:03:11.526 UTC [3115] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2649 removed, 9601 remain, 0 are dead but not yet removable
	removable cutoff: 250154, which was 16 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 282 pages from table (51.55% of total) had 2650 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 8.135 MB/s, avg write rate: 8.355 MB/s
	buffer usage: 1664 hits, 148 misses, 152 dirtied
	WAL usage: 876 records, 229 full page images, 531040 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.14 s
2026-10-23 00:04:11.530 UTC [3298] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1029 removed, 8634 remain, 0 are dead but not yet removable
	removable cutoff: 257984, which was 0 XIDs old when operation ended
	frozen: 1 pages from table (0.18% of total) had 14 tuples frozen
	index scan needed: 165 pages from table (30.16% of total) had 1022 dead item identifiers removed
	index "things_snaps_pkey": pages: 370 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 8.884 MB/s, avg write rate: 5.369 MB/s
	buffer usage: 1557 hits, 139 misses, 84 dirtied
	WAL usage: 510 records, 170 full page images, 408757 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.12 s
2026-10-23 00:42:00.831 UTC [2426] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 2
	pages: 0 removed, 4641592 remain, 3176137 scanned (68.43% of total)
	tuples: 14103405 removed, 17792132 remain, 4699768 are dead but not yet removable
	removable cutoff: 173246, which was 84739 XIDs old when operation ended
	frozen: 106156 pages from table (2.29% of total) had 419359 tuples frozen
	index scan needed: 2135682 pages from table (46.01% of total) had 15209629 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 101650 newly deleted, 107990 currently deleted, 6340 reusable
	index "things_journal_tags_idx": pages: 5154 in total, 188 newly deleted, 189 currently deleted, 7 reusable
	avg read rate: 15.673 MB/s, avg write rate: 15.252 MB/s
	buffer usage: 4804612 hits, 5154719 misses, 5016266 dirtied
	WAL usage: 6700927 records, 4339555 full page images, 2699896118 bytes
	system usage: CPU: user: 112.48 s, system: 275.84 s, elapsed: 2569.53 s
2026-10-23 00:56:28.221 UTC [9847] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 1004708 scanned (21.65% of total)
	tuples: 5642261 removed, 11128168 remain, 0 are dead but not yet removable
	removable cutoff: 257986, which was 0 XIDs old when operation ended
	frozen: 40241 pages from table (0.87% of total) had 159966 tuples frozen
	index scan needed: 828208 pages from table (17.84% of total) had 5700055 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 40501 newly deleted, 148491 currently deleted, 107990 reusable
	index "things_journal_tags_idx": pages: 5154 in total, 93 newly deleted, 93 currently deleted, 195 reusable
	avg read rate: 12.164 MB/s, avg write rate: 15.576 MB/s
	buffer usage: 2255651 hits, 1332665 misses, 1706496 dirtied
	WAL usage: 2654424 records, 1705814 full page images, 864663608 bytes
	system usage: CPU: user: 58.07 s, system: 80.60 s, elapsed: 855.93 s
```
