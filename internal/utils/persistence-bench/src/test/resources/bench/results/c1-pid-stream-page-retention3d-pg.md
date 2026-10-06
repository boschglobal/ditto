# C1 — cleanup pid-stream page with the production min age (retention 3d vs 0, batch 100)

- generated: 2026-10-02T15:41:39.963423Z
- corpus: count=250000 seed=42 freshShare=0.3 freshBase=2026-10-01T00:00:00Z
- backend(s): pg
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

- fresh-only pids (share 0.3, rows at 2026-10-01T00:00:00Z + sn seconds; all others at 2026-01-01T00:00:00Z + sn seconds): 75194 of 250000; with snapshots: 2568 of 8581

## PostgreSQL

| variant (one page, varying lower bound) | p50 \| p95 \| max (ms) |
|---|---|
| minAge 0 (age filter off — the committed C1 shape) | 2.87 \| 3.99 \| 4.13 |
| retention 3d (production min age) | 3.03 \| 3.39 \| 3.54 |

- result check, minAge PT0S: 23 of 23 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- result check, minAge PT72H: 23 of 23 pages equal the generator's expected page; pids per page min/mean/max 100 / 100.0 / 100
- age mix at timing start: 8581 pids with snapshots (expected 8581), 2568 of them without a snapshot older than 3d (expected 2568)
- things_snaps vacuum state before timing: last_vacuum null, last_autovacuum 2026-10-02 18:06:18.728102+03; relallvisible 547 of relpages 576
- vacuums in progress at timing start: none; at timing end: none

| table | heap | indexes | total |
|---|---|---|---|
| things_journal | 36272.7 MB | 2015.3 MB | 38288.0 MB |
| things_journal_seq | 18.7 MB | 11.9 MB | 30.5 MB |
| things_snaps | 291.4 MB | 2.9 MB | 294.3 MB |

| index | size |
|---|---|
| things_journal_pkey | 1975.0 MB |
| things_journal_seq_pkey | 11.9 MB |
| things_journal_tags_idx | 40.3 MB |
| things_snaps_lifecycle_idx | 0.0 MB |
| things_snaps_pkey | 2.9 MB |

### PG plan — minAge 0 (mid-corpus lower bound)

```
Nested Loop  (cost=0.83..539.41 rows=100 width=110) (actual time=0.027..1.207 rows=100 loops=1)
  Buffers: shared hit=853
  ->  Limit  (cost=0.41..29.67 rows=100 width=30) (actual time=0.008..0.048 rows=100 loops=1)
        Buffers: shared hit=8
        ->  Group  (cost=0.41..2244.76 rows=7671 width=30) (actual time=0.008..0.044 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=8
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.41..2145.16 rows=39839 width=30) (actual time=0.007..0.027 rows=478 loops=1)
                    Index Cond: (pid > 'thing:bench.ns01:pid-00119559'::text)
                    Heap Fetches: 0
                    Buffers: shared hit=8
  ->  Limit  (cost=0.41..5.09 rows=1 width=80) (actual time=0.011..0.011 rows=1 loops=100)
        Buffers: shared hit=845
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.41..28.45 rows=6 width=80) (actual time=0.011..0.011 rows=1 loops=100)
              Index Cond: (pid = things_snaps.pid)
              Buffers: shared hit=845
Planning:
  Buffers: shared hit=2
Planning Time: 0.057 ms
Execution Time: 1.216 ms
```

### PG plan — retention 3d (mid-corpus lower bound)

```
Nested Loop  (cost=0.84..573.58 rows=100 width=110) (actual time=0.018..1.192 rows=100 loops=1)
  Buffers: shared hit=857
  ->  Limit  (cost=0.42..29.09 rows=100 width=30) (actual time=0.005..0.050 rows=100 loops=1)
        Buffers: shared hit=9
        ->  Group  (cost=0.42..2192.57 rows=7645 width=30) (actual time=0.005..0.047 rows=100 loops=1)
              Group Key: things_snaps.pid
              Buffers: shared hit=9
              ->  Index Only Scan using things_snaps_pkey on things_snaps  (cost=0.42..2123.33 rows=27699 width=30) (actual time=0.005..0.030 rows=386 loops=1)
                    Index Cond: ((pid > 'thing:bench.ns01:pid-00119559'::text) AND (written_at < (now() - '72:00:00'::interval)))
                    Heap Fetches: 0
                    Buffers: shared hit=9
  ->  Limit  (cost=0.42..5.43 rows=1 width=80) (actual time=0.011..0.011 rows=1 loops=100)
        Buffers: shared hit=848
        ->  Index Scan Backward using things_snaps_pkey on things_snaps things_snaps_1  (cost=0.42..20.48 rows=4 width=80) (actual time=0.011..0.011 rows=1 loops=100)
              Index Cond: ((pid = things_snaps.pid) AND (written_at < (now() - '72:00:00'::interval)))
              Buffers: shared hit=848
Planning:
  Buffers: shared hit=2
Planning Time: 0.060 ms
Execution Time: 1.202 ms
```

What is compared: the binds Cleanup.java passes (includeDeleted=true, pidFilter='', minAgeFromNow = history-retention-duration). PG (PostgresPersistenceOperations.getNewestSnapshotsAbove): a non-zero min age sets the no-age-filter flag to false, so 'written_at < now() - interval' filters rows in BOTH steps (step 1: a pid with no old-enough row takes no page slot; step 2: the newest old-enough row wins); the page is 100 distinct pids. Mongo (MongoReadJournal.toMongoFilter): a non-zero min age ANDs '_id < ObjectId.getSmallestWithDate(now - minAge)' to the pid bound in $match, before $sort/$limit/$group; $limit counts snapshot ROWS, so a Mongo page spans fewer pids. written_at and the _id timestamp are both the snapshot's write time (corpus snapshot _ids embed it). Timing: the two variants interleaved per lower bound, alternating which runs first.
