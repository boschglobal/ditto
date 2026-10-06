# W3 latency profile: server-side outputs

Verbatim outputs the `README.md` of this folder cites, captured on 2026-10-06 against the bench
containers (since torn down). Times are UTC.

## 1. fsync baseline of the data volumes

`pg_test_fsync -f /var/lib/postgresql/data/pg_test_fsync.tmp -s 3` in the PG container:

```text
3 seconds per test
O_DIRECT supported on this platform for open_datasync and open_sync.

Compare file sync methods using one 8kB write:
(in wal_sync_method preference order, except fdatasync is Linux's default)
        open_datasync                      1080.325 ops/sec     926 usecs/op
        fdatasync                          2217.252 ops/sec     451 usecs/op
        fsync                              2002.313 ops/sec     499 usecs/op
        fsync_writethrough                              n/a
        open_sync                          1118.281 ops/sec     894 usecs/op

Compare file sync methods using two 8kB writes:
(in wal_sync_method preference order, except fdatasync is Linux's default)
        open_datasync                       502.414 ops/sec    1990 usecs/op
        fdatasync                          1692.215 ops/sec     591 usecs/op
        fsync                              1922.810 ops/sec     520 usecs/op
        fsync_writethrough                              n/a
        open_sync                           518.942 ops/sec    1927 usecs/op

Compare open_sync with different write sizes:
(This is designed to compare the cost of writing 16kB in different write
open_sync sizes.)
         1 * 16kB open_sync write           958.614 ops/sec    1043 usecs/op
         2 *  8kB open_sync writes          509.503 ops/sec    1963 usecs/op
         4 *  4kB open_sync writes          259.285 ops/sec    3857 usecs/op
         8 *  2kB open_sync writes          242.484 ops/sec    4124 usecs/op
        16 *  1kB open_sync writes          121.167 ops/sec    8253 usecs/op

Test if fsync on non-write file descriptor is honored:
(If the times are similar, fsync() can sync data written on a different
descriptor.)
        write, fsync, close                 563.718 ops/sec    1774 usecs/op
        write, close, fsync                1814.146 ops/sec     551 usecs/op

Non-sync'ed 8kB writes:
        write                           1028322.220 ops/sec       1 usecs/op
```

`dd if=/dev/zero of=<data dir>/ddsync.tmp bs=8k count=2000 oflag=dsync` in each container:

```text
ditto-persistence-bench-pg /var/lib/postgresql/data
16384000 bytes (16 MB, 16 MiB) copied, 1.03633 s, 15.8 MB/s
ditto-persistence-bench-mongo /data/db
16384000 bytes (16 MB, 16 MiB) copied, 1.00439 s, 16.3 MB/s
```

## 2. Free space map of `things_journal` after the 50k load

Query (extension `pg_freespacemap`; a churn event row needs about 800 B including its line
pointer, so the ≥ 704 B count is an upper bound of the reusable pages):

```sql
SELECT pg_relation_size('things_journal') / 8192 AS heap_blocks,
       count(*) FILTER (WHERE avail >= 704) AS fsm_pages_ge_704b,
       count(*) FILTER (WHERE avail >= 1024) AS fsm_pages_ge_1kb,
       count(*) FILTER (WHERE avail > 0) AS fsm_pages_gt_0,
       round(avg(avail)) AS avg_avail_bytes
FROM pg_freespace('things_journal');
```

The values right after the load (autovacuum at block 19,021 of 920,400) and after the post-load
autovacuum are identical: the load itself leaves the free space in the map.

After the load, 08:59:19 (autovacuum running, block 19,021 of 920,400):

```text
 heap_blocks | fsm_pages_ge_704b | fsm_pages_ge_1kb | fsm_pages_gt_0 | avg_avail_bytes 
-------------+-------------------+------------------+----------------+-----------------
      920400 |            208796 |            27725 |         864574 |             431
(1 row)

```

After the post-load autovacuum, 09:05:15:

```text
 heap_blocks | fsm_pages_ge_704b | fsm_pages_ge_1kb | fsm_pages_gt_0 | avg_avail_bytes 
-------------+-------------------+------------------+----------------+-----------------
      920400 |            208796 |            27725 |         864574 |             431
(1 row)
```

After `canonical-600s-both` (09:29):

```text
 heap_blocks | fsm_pages_ge_704b | fsm_pages_ge_1kb | fsm_pages_gt_0 | avg_avail_bytes 
-------------+-------------------+------------------+----------------+-----------------
      939209 |             72797 |                5 |         869201 |             314
(1 row)

```

## 3. Heap placement of `canonical-600s-both` by window

The run's rows grouped by sequence-number bucket (`(sn - 1) / 19`; each pid gets one append every
1.6 s, so a bucket of 19 sequence numbers is about one 30 s window), share of rows on pages that
existed before the run (blocks < 920,400). Captured at 09:30, before the server restart:

```text
 win | rows  | pct_old_pages 
-----+-------+---------------
   0 | 15200 |         100.0
   1 | 15200 |         100.0
   2 | 15200 |         100.0
   3 | 15200 |         100.0
   4 | 15200 |         100.0
   5 | 15200 |         100.0
   6 | 15200 |         100.0
   7 | 15200 |          35.0
   8 | 15200 |           0.0
   9 | 15200 |           0.0
  10 | 15200 |           0.0
  11 | 15200 |           0.0
  12 | 15200 |           0.0
  13 | 15200 |           0.0
  14 | 15200 |           0.0
  15 | 15200 |           0.0
  16 | 15200 |           0.0
  17 | 15200 |           0.0
  18 | 15200 |           0.0
  19 | 10984 |           0.0
(20 rows)
```

## 4. `things_journal` autovacuum periods during the session

From a 15 s poll of `pg_stat_progress_vacuum` (start/end at that granularity):

| period | what | runs it overlapped |
|---|---|---|
| 08:59:19–09:04:47 | post-load autovacuum (50k load) | none (before the first measured run) |
| 10:32:53–10:35:19 | insert-triggered autovacuum (1,074,359 blocks) | the last ~10 s of `staggered-one-statement`, about 2 of the 3 minutes of `staggered-r2dbc` |
| ~10:48:00–10:53:04 | post-load autovacuum after the reload | windows 1–3 of `reload-during-autovacuum-aligned-txn` (it began ~30 s into the run) |

## 5. `pg_stat_statements` per run

Reset right before each PG run (from `restart-aligned-txn` on; the canonical run had no
`pg_stat_statements`), dumped right after it: statements with ≥ 1,000 calls, by total time. The
`SELECT state || …` row is the wait-event sampler. `BEGIN`/`COMMIT` show ≈ 0 ms because the WAL flush
wait of a commit is not executor time.

### `restart-aligned-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89816 |   0.050 |     4478 |               0.01 |                  0.11 |            0.000 |         0.00 |               1126 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 89816 |   0.018 |     1623 |               0.00 |                  0.00 |            0.000 |         0.00 |                146 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 15046 |   0.040 |      602 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89816 |   0.000 |       31 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 89816 |   0.000 |       28 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `aligned-300s-txn`

```text
 calls  | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
--------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 149808 |   0.113 |    16942 |               0.16 |                  0.26 |            0.048 |         0.16 |               2300 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 149808 |   0.021 |     3158 |               0.00 |                  0.00 |            0.000 |         0.00 |                140 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  24991 |   0.046 |     1145 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 149808 |   0.000 |       62 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 149808 |   0.000 |       55 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `staggered-300s-txn`

```text
 calls  | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
--------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 150008 |   0.018 |     2749 |               0.00 |                  0.11 |            0.000 |         0.00 |               1159 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 150008 |   0.011 |     1625 |               0.00 |                  0.00 |            0.000 |         0.00 |                191 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  25078 |   0.029 |      727 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 150008 |   0.000 |       35 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 150008 |   0.000 |       23 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `aligned-txn-sync-commit-off`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89832 |   0.051 |     4598 |               0.00 |                  0.11 |            0.000 |         0.01 |               1186 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 89832 |   0.023 |     2050 |               0.00 |                  0.00 |            0.000 |         0.00 |                187 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 15013 |   0.040 |      603 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89832 |   0.000 |       41 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 89832 |   0.000 |       26 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `aligned-no-hwm`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89880 |   0.074 |     6635 |               0.00 |                  0.11 |            0.000 |         0.00 |               1122 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 14539 |   0.065 |      951 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89880 |   0.001 |       51 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 89880 |   0.000 |       43 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(4 rows)

```

### `aligned-one-statement`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89880 |   0.183 |    16489 |               0.00 |                  0.11 |            0.000 |         0.01 |               1376 | WITH ins AS (INSERT INTO things_journal (pid, sn, manifest, tags, even
 13902 |   0.101 |     1411 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
(2 rows)

```

### `aligned-r2dbc`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89912 |   0.099 |     8929 |               0.00 |                  0.11 |            0.000 |         0.00 |               1119 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 89912 |   0.039 |     3511 |               0.00 |                  0.00 |            0.000 |         0.00 |                165 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14270 |   0.078 |     1112 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89912 |   0.000 |       41 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
 89912 |   0.000 |       41 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
(5 rows)

```

### `aligned-r2dbc-phased`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89904 |   0.073 |     6518 |               0.00 |                  0.11 |            0.000 |         0.00 |               1141 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 89904 |   0.031 |     2778 |               0.00 |                  0.00 |            0.000 |         0.00 |                186 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14658 |   0.059 |      858 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89904 |   0.000 |       35 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
 89904 |   0.000 |       34 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
(5 rows)

```

### `aligned-txn-16-workers`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89872 |   0.034 |     3071 |               0.00 |                  0.11 |            0.000 |         0.01 |               1175 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 89872 |   0.016 |     1418 |               0.00 |                  0.00 |            0.000 |         0.00 |                139 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14626 |   0.040 |      585 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89872 |   0.000 |       34 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 89872 |   0.000 |       18 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `aligned-txn-4-workers`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 89840 |   0.035 |     3122 |               0.00 |                  0.11 |            0.000 |         0.00 |               1119 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 89840 |   0.017 |     1517 |               0.00 |                  0.00 |            0.000 |         0.00 |                154 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 15027 |   0.038 |      578 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 89840 |   0.000 |       35 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 89840 |   0.000 |       29 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `staggered-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90008 |   0.024 |     2185 |               0.00 |                  0.11 |            0.000 |         0.00 |               1106 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 90008 |   0.012 |     1073 |               0.00 |                  0.00 |            0.000 |         0.00 |                145 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 15083 |   0.037 |      565 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90008 |   0.000 |       26 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 90008 |   0.000 |       20 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `staggered-txn-sync-commit-off`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90008 |   0.030 |     2728 |               0.00 |                  0.11 |            0.000 |         0.01 |               1168 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 90008 |   0.018 |     1580 |               0.00 |                  0.00 |            0.000 |         0.00 |                175 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14756 |   0.047 |      694 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90008 |   0.000 |       37 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
 90008 |   0.000 |       36 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
(5 rows)

```

### `staggered-no-hwm`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90007 |   0.019 |     1741 |               0.00 |                  0.11 |            0.000 |         0.00 |               1121 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 15000 |   0.032 |      479 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90007 |   0.000 |       23 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 90007 |   0.000 |       17 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(4 rows)

```

### `staggered-one-statement`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90008 |   0.036 |     3205 |               0.00 |                  0.12 |            0.000 |         0.00 |               1346 | WITH ins AS (INSERT INTO things_journal (pid, sn, manifest, tags, even
 14848 |   0.037 |      556 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
(2 rows)

```

### `staggered-r2dbc`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90005 |   0.020 |     1758 |               0.00 |                  0.11 |            0.000 |         0.01 |               1174 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 90005 |   0.013 |     1140 |               0.00 |                  0.00 |            0.000 |         0.00 |                174 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14870 |   0.037 |      555 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90005 |   0.000 |       20 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 90005 |   0.000 |       17 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `staggered-r2dbc-phased`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90007 |   0.018 |     1658 |               0.00 |                  0.11 |            0.000 |         0.00 |               1128 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 90007 |   0.012 |     1079 |               0.00 |                  0.00 |            0.000 |         0.00 |                166 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14958 |   0.036 |      533 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90007 |   0.000 |       20 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 90007 |   0.000 |       16 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `staggered-txn-16-workers`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90016 |   0.018 |     1652 |               0.00 |                  0.12 |            0.000 |         0.01 |               1155 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 90016 |   0.008 |      746 |               0.00 |                  0.00 |            0.000 |         0.00 |                146 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 14651 |   0.036 |      532 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90016 |   0.000 |       22 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 90016 |   0.000 |       14 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `staggered-txn-4-workers`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 90004 |   0.015 |     1378 |               0.00 |                  0.11 |            0.000 |         0.00 |               1118 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 90004 |   0.009 |      845 |               0.00 |                  0.00 |            0.000 |         0.00 |                181 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
 15327 |   0.025 |      389 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 90004 |   0.000 |       20 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 90004 |   0.000 |       16 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `reload-during-autovacuum-aligned-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 59840 |   0.273 |    16320 |               1.00 |                  1.02 |            0.200 |         1.01 |               8492 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 59840 |   0.024 |     1436 |               0.00 |                  0.00 |            0.000 |         0.00 |                182 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  9888 |   0.049 |      483 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 59840 |   0.000 |       24 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 59840 |   0.000 |       23 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `reload-free-space-reuse-aligned-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 44808 |   0.234 |    10465 |               1.00 |                  1.02 |            0.126 |         1.00 |               8445 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 44808 |   0.037 |     1648 |               0.00 |                  0.00 |            0.000 |         0.00 |                182 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  7154 |   0.076 |      545 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 44808 |   0.001 |       30 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
 44808 |   0.001 |       29 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
(5 rows)

```

### `reload-free-space-reuse-staggered-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 45007 |   0.046 |     2065 |               1.00 |                  1.01 |            0.025 |         1.01 |               8450 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 45007 |   0.010 |      453 |               0.00 |                  0.00 |            0.000 |         0.00 |                175 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  7538 |   0.029 |      220 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 45007 |   0.000 |       10 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 45007 |   0.000 |        7 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `commit-delay-0-aligned-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 59816 |   0.130 |     7752 |               0.14 |                  0.26 |            0.010 |         0.16 |               2330 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 59816 |   0.040 |     2375 |               0.00 |                  0.00 |            0.000 |         0.00 |                184 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  9501 |   0.079 |      750 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 59816 |   0.001 |       39 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
 59816 |   0.001 |       32 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
(5 rows)

```

### `commit-delay-500us-aligned-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 59728 |   0.112 |     6660 |               0.00 |                  0.11 |            0.000 |         0.00 |               1184 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 59728 |   0.034 |     2055 |               0.00 |                  0.00 |            0.000 |         0.00 |                177 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  9551 |   0.071 |      677 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 59728 |   0.001 |       31 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 59728 |   0.000 |       30 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `interleaved-1-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 30008 |   0.023 |      704 |               0.00 |                  0.12 |            0.000 |         0.00 |               1111 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 30008 |   0.013 |      379 |               0.00 |                  0.00 |            0.000 |         0.00 |                188 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  5050 |   0.034 |      173 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 30008 |   0.000 |        8 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 30008 |   0.000 |        6 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `interleaved-1-r2dbc`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 30008 |   0.015 |      445 |               0.00 |                  0.11 |            0.000 |         0.00 |               1126 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 30008 |   0.009 |      258 |               0.00 |                  0.00 |            0.000 |         0.00 |                168 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  5038 |   0.029 |      144 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 30008 |   0.000 |        5 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 30008 |   0.000 |        4 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `interleaved-1-one-statement`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 30007 |   0.026 |      770 |               0.00 |                  0.11 |            0.000 |         0.00 |               1324 | WITH ins AS (INSERT INTO things_journal (pid, sn, manifest, tags, even
  5039 |   0.030 |      153 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
(2 rows)

```

### `interleaved-2-one-statement`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 30008 |   0.030 |      906 |               0.00 |                  0.12 |            0.000 |         0.01 |               1378 | WITH ins AS (INSERT INTO things_journal (pid, sn, manifest, tags, even
  5012 |   0.035 |      173 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
(2 rows)

```

### `interleaved-2-r2dbc`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 30008 |   0.013 |      398 |               0.00 |                  0.12 |            0.000 |         0.00 |               1128 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 30008 |   0.008 |      234 |               0.00 |                  0.00 |            0.000 |         0.00 |                160 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  5043 |   0.026 |      129 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 30008 |   0.000 |        5 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 30008 |   0.000 |        4 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

### `interleaved-2-txn`

```text
 calls | mean_ms | total_ms | blks_read_per_call | blks_dirtied_per_call | read_ms_per_call | fpi_per_call | wal_bytes_per_call |                                 query                                  
-------+---------+----------+--------------------+-----------------------+------------------+--------------+--------------------+------------------------------------------------------------------------
 30008 |   0.016 |      484 |               0.00 |                  0.11 |            0.000 |         0.00 |               1113 | INSERT INTO things_journal (pid, sn, manifest, tags, event, written_at
 30008 |   0.009 |      269 |               0.00 |                  0.00 |            0.000 |         0.00 |                188 | INSERT INTO things_journal_seq (pid, highest_sn) VALUES ($1, $2) ON CO
  5077 |   0.025 |      127 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | SELECT state || $1 || coalesce(wait_event_type || $2 || wait_event, $3
 30008 |   0.000 |        6 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | COMMIT
 30008 |   0.000 |        4 |               0.00 |                  0.00 |            0.000 |         0.00 |                  0 | BEGIN
(5 rows)

```

