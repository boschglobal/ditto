# Autovacuum log of the post-sweep settle run (server log excerpt)

- generated: 2026-10-23T01:51:20Z
- evidence label: limits-cl800
- source: `docker logs ditto-persistence-bench-pg`, with `log_autovacuum_min_duration = 0` set for this run by `ALTER SYSTEM` (logging only); every `automatic vacuum` entry of `things_journal` and `things_snaps` from the C3 start (2026-10-23T01:20:48Z) to the end of the run; assembled from the server log by the session's helper, not written by the harness. Times are UTC; an entry is written when the vacuum ends, its start is the end minus `elapsed`.
- C3 sweep finished at 2026-10-23T01:37:08.731145Z (`c3-full-sweep-limits-cl800.md`)

```text
2026-10-23 01:22:42.548 UTC [987] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 377 scanned (68.92% of total)
	tuples: 3050 removed, 39149 remain, 0 are dead but not yet removable
	removable cutoff: 37983, which was 9 XIDs old when operation ended
	frozen: 276 pages from table (50.46% of total) had 17551 tuples frozen
	index scan needed: 328 pages from table (59.96% of total) had 5111 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 44.156 MB/s, avg write rate: 46.728 MB/s
	buffer usage: 1209 hits, 309 misses, 327 dirtied
	WAL usage: 1275 records, 371 full page images, 2310164 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.05 s
2026-10-23 01:22:47.305 UTC [816] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 413284 scanned (8.90% of total)
	tuples: 1436175 removed, 33505879 remain, 857472 are dead but not yet removable
	removable cutoff: 18510, which was 20509 XIDs old when operation ended
	frozen: 18174 pages from table (0.39% of total) had 69064 tuples frozen
	index scan needed: 221549 pages from table (4.77% of total) had 1580998 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 10249 newly deleted, 10249 currently deleted, 10249 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 9 newly deleted, 9 currently deleted, 0 reusable
	avg read rate: 37.358 MB/s, avg write rate: 60.257 MB/s
	buffer usage: 1111160 hits, 310044 misses, 500091 dirtied
	WAL usage: 700723 records, 451262 full page images, 358105507 bytes
	system usage: CPU: user: 7.26 s, system: 11.60 s, elapsed: 64.83 s
2026-10-23 01:24:42.587 UTC [1335] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 475 scanned (86.84% of total)
	tuples: 3591 removed, 34556 remain, 0 are dead but not yet removable
	removable cutoff: 72796, which was 18 XIDs old when operation ended
	frozen: 166 pages from table (30.35% of total) had 10263 tuples frozen
	index scan needed: 354 pages from table (64.72% of total) had 4820 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 53.181 MB/s, avg write rate: 55.001 MB/s
	buffer usage: 1477 hits, 263 misses, 272 dirtied
	WAL usage: 1259 records, 343 full page images, 1951407 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:25:42.613 UTC [1506] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 400 scanned (73.13% of total)
	tuples: 1957 removed, 31972 remain, 0 are dead but not yet removable
	removable cutoff: 89281, which was 12 XIDs old when operation ended
	frozen: 32 pages from table (5.85% of total) had 2008 tuples frozen
	index scan needed: 232 pages from table (42.41% of total) had 2272 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 36.111 MB/s, avg write rate: 30.151 MB/s
	buffer usage: 1262 hits, 206 misses, 172 dirtied
	WAL usage: 745 records, 234 full page images, 1290359 bytes
	system usage: CPU: user: 0.00 s, system: 0.01 s, elapsed: 0.04 s
2026-10-23 01:26:42.619 UTC [1683] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 476 scanned (87.02% of total)
	tuples: 1922 removed, 30336 remain, 0 are dead but not yet removable
	removable cutoff: 104459, which was 0 XIDs old when operation ended
	frozen: 33 pages from table (6.03% of total) had 2036 tuples frozen
	index scan needed: 234 pages from table (42.78% of total) had 2110 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 43.900 MB/s, avg write rate: 40.544 MB/s
	buffer usage: 1465 hits, 157 misses, 145 dirtied
	WAL usage: 753 records, 230 full page images, 1223420 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.02 s
2026-10-23 01:27:15.233 UTC [1164] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 592542 scanned (12.77% of total)
	tuples: 3248349 removed, 29500976 remain, 30010 are dead but not yet removable
	tuples missed: 331 dead from 45 pages not removed due to cleanup lock contention
	removable cutoff: 58629, which was 53760 XIDs old when operation ended
	frozen: 40007 pages from table (0.86% of total) had 158632 tuples frozen
	index scan needed: 478079 pages from table (10.30% of total) had 3404662 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 23085 newly deleted, 33334 currently deleted, 33334 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 31 newly deleted, 31 currently deleted, 9 reusable
	avg read rate: 40.634 MB/s, avg write rate: 35.808 MB/s
	buffer usage: 1070104 hits, 1106315 misses, 974913 dirtied
	WAL usage: 1545187 records, 982426 full page images, 650600989 bytes
	system usage: CPU: user: 11.50 s, system: 65.42 s, elapsed: 212.70 s
2026-10-23 01:27:26.397 UTC [1164] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 365 scanned (66.73% of total)
	tuples: 1449 removed, 28833 remain, 0 are dead but not yet removable
	removable cutoff: 115176, which was 5 XIDs old when operation ended
	frozen: 10 pages from table (1.83% of total) had 636 tuples frozen
	index scan needed: 157 pages from table (28.70% of total) had 1537 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 76.250 MB/s, avg write rate: 61.824 MB/s
	buffer usage: 1190 hits, 111 misses, 90 dirtied
	WAL usage: 498 records, 136 full page images, 670026 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.01 s
2026-10-23 01:28:42.673 UTC [2025] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2808 removed, 25538 remain, 1 are dead but not yet removable
	removable cutoff: 135929, which was 13 XIDs old when operation ended
	frozen: 14 pages from table (2.56% of total) had 716 tuples frozen
	index scan needed: 311 pages from table (56.86% of total) had 2883 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 18.487 MB/s, avg write rate: 45.021 MB/s
	buffer usage: 1756 hits, 85 misses, 207 dirtied
	WAL usage: 979 records, 315 full page images, 1442264 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:29:42.679 UTC [2196] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1961 removed, 23534 remain, 0 are dead but not yet removable
	removable cutoff: 150335, which was 12 XIDs old when operation ended
	frozen: 4 pages from table (0.73% of total) had 249 tuples frozen
	index scan needed: 234 pages from table (42.78% of total) had 2004 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 34.526 MB/s, avg write rate: 38.519 MB/s
	buffer usage: 1617 hits, 147 misses, 164 dirtied
	WAL usage: 730 records, 153 full page images, 653148 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:30:42.700 UTC [2373] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1997 removed, 21498 remain, 1 are dead but not yet removable
	removable cutoff: 164972, which was 4 XIDs old when operation ended
	frozen: 5 pages from table (0.91% of total) had 334 tuples frozen
	index scan needed: 246 pages from table (44.97% of total) had 2036 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 29.762 MB/s, avg write rate: 27.585 MB/s
	buffer usage: 1653 hits, 123 misses, 114 dirtied
	WAL usage: 763 records, 210 full page images, 878671 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:31:42.726 UTC [2545] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 2125 removed, 19342 remain, 0 are dead but not yet removable
	removable cutoff: 180272, which was 15 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 236 pages from table (43.14% of total) had 2156 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 26.398 MB/s, avg write rate: 30.798 MB/s
	buffer usage: 1634 hits, 132 misses, 154 dirtied
	WAL usage: 733 records, 247 full page images, 926502 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:32:42.739 UTC [2716] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1915 removed, 17410 remain, 0 are dead but not yet removable
	removable cutoff: 194158, which was 10 XIDs old when operation ended
	frozen: 1 pages from table (0.18% of total) had 9 tuples frozen
	index scan needed: 240 pages from table (43.88% of total) had 1932 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 27.779 MB/s, avg write rate: 39.188 MB/s
	buffer usage: 1658 hits, 112 misses, 158 dirtied
	WAL usage: 743 records, 144 full page images, 495950 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:33:42.754 UTC [2887] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1907 removed, 15473 remain, 0 are dead but not yet removable
	removable cutoff: 207865, which was 6 XIDs old when operation ended
	frozen: 1 pages from table (0.18% of total) had 2 tuples frozen
	index scan needed: 229 pages from table (41.86% of total) had 1937 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 34.943 MB/s, avg write rate: 33.611 MB/s
	buffer usage: 1654 hits, 105 misses, 101 dirtied
	WAL usage: 711 records, 181 full page images, 570087 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.02 s
2026-10-23 01:34:10.800 UTC [1855] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 1244772 scanned (26.82% of total)
	tuples: 5028682 removed, 22093701 remain, 1961195 are dead but not yet removable
	tuples missed: 7 dead from 1 pages not removed due to cleanup lock contention
	removable cutoff: 120805, which was 93529 XIDs old when operation ended
	frozen: 50413 pages from table (1.09% of total) had 198120 tuples frozen
	index scan needed: 764014 pages from table (16.46% of total) had 5473483 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 35987 newly deleted, 69321 currently deleted, 69321 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 72 newly deleted, 72 currently deleted, 40 reusable
	avg read rate: 36.604 MB/s, avg write rate: 36.035 MB/s
	buffer usage: 2102129 hits, 1818781 misses, 1790514 dirtied
	WAL usage: 2410069 records, 1557171 full page images, 1081679555 bytes
	system usage: CPU: user: 16.92 s, system: 113.07 s, elapsed: 388.19 s
2026-10-23 01:34:10.822 UTC [1855] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 929 removed, 14541 remain, 0 are dead but not yet removable
	removable cutoff: 214333, which was 3 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 111 pages from table (20.29% of total) had 932 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 35.898 MB/s, avg write rate: 5.026 MB/s
	buffer usage: 1519 hits, 100 misses, 14 dirtied
	WAL usage: 345 records, 41 full page images, 139008 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.02 s
2026-10-23 01:35:42.821 UTC [3236] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 3378 removed, 11163 remain, 0 are dead but not yet removable
	removable cutoff: 238735, which was 11 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 338 pages from table (61.79% of total) had 3378 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 15.767 MB/s, avg write rate: 40.432 MB/s
	buffer usage: 1767 hits, 101 misses, 259 dirtied
	WAL usage: 1050 records, 335 full page images, 832159 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.05 s
2026-10-23 01:36:42.824 UTC [3407] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 1841 removed, 9321 remain, 0 are dead but not yet removable
	removable cutoff: 252247, which was 6 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 233 pages from table (42.60% of total) had 1842 dead item identifiers removed
	index "things_snaps_pkey": pages: 369 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 18.016 MB/s, avg write rate: 24.244 MB/s
	buffer usage: 1682 hits, 81 misses, 109 dirtied
	WAL usage: 721 records, 159 full page images, 373184 bytes
	system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.03 s
2026-10-23 01:37:42.857 UTC [3589] LOG:  automatic vacuum of table "bench.public.things_snaps": index scans: 1
	pages: 0 removed, 547 remain, 547 scanned (100.00% of total)
	tuples: 746 removed, 8634 remain, 0 are dead but not yet removable
	removable cutoff: 257986, which was 0 XIDs old when operation ended
	frozen: 0 pages from table (0.00% of total) had 0 tuples frozen
	index scan needed: 129 pages from table (23.58% of total) had 742 dead item identifiers removed
	index "things_snaps_pkey": pages: 370 in total, 0 newly deleted, 0 currently deleted, 0 reusable
	avg read rate: 16.244 MB/s, avg write rate: 13.862 MB/s
	buffer usage: 1585 hits, 75 misses, 64 dirtied
	WAL usage: 399 records, 24 full page images, 81459 bytes
	system usage: CPU: user: 0.00 s, system: 0.01 s, elapsed: 0.03 s
2026-10-23 01:45:41.625 UTC [3059] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 1826723 scanned (39.36% of total)
	tuples: 8363509 removed, 16177342 remain, 2012063 are dead but not yet removable
	removable cutoff: 224643, which was 33343 XIDs old when operation ended
	frozen: 61507 pages from table (1.33% of total) had 245237 tuples frozen
	index scan needed: 1254351 pages from table (27.02% of total) had 8932761 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 60022 newly deleted, 129343 currently deleted, 69321 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 119 newly deleted, 120 currently deleted, 112 reusable
	avg read rate: 33.025 MB/s, avg write rate: 33.294 MB/s
	buffer usage: 3009528 hits, 2785163 misses, 2807867 dirtied
	WAL usage: 3966955 records, 2563504 full page images, 1503128310 bytes
	system usage: CPU: user: 34.45 s, system: 160.59 s, elapsed: 658.86 s
2026-10-23 01:48:09.814 UTC [5141] LOG:  automatic vacuum of table "bench.public.things_journal": index scans: 1
	pages: 0 removed, 4641592 remain, 487192 scanned (10.50% of total)
	tuples: 2694184 removed, 12272999 remain, 0 are dead but not yet removable
	removable cutoff: 257987, which was 0 XIDs old when operation ended
	frozen: 19794 pages from table (0.43% of total) had 78421 tuples frozen
	index scan needed: 396277 pages from table (8.54% of total) had 2735016 dead item identifiers removed
	index "things_journal_pkey": pages: 252804 in total, 19148 newly deleted, 148491 currently deleted, 129343 reusable
	index "things_journal_tags_idx": pages: 5153 in total, 56 newly deleted, 56 currently deleted, 231 reusable
	avg read rate: 29.878 MB/s, avg write rate: 39.629 MB/s
	buffer usage: 1618817 hits, 331798 misses, 440084 dirtied
	WAL usage: 1271002 records, 410694 full page images, 333213139 bytes
	system usage: CPU: user: 14.41 s, system: 13.28 s, elapsed: 86.75 s
```
