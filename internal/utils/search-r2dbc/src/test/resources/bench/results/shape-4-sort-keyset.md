# Shape 4 — sort-by-arbitrary-path + keyset paging (gr only, no auth recheck per design)

Sort key: `/features/env/properties/temperature` (numeric). Page size 25.

## Page 1 (`ORDER BY ... LIMIT 25`, no OFFSET)

```sql
SELECT st.thing_id, k.type_rank, k.val_num, k.val_text, k.val_bool
FROM search_things st
LEFT JOIN LATERAL (
    SELECT type_rank, val_num, val_text, val_bool FROM search_flat s
    WHERE s.thing_id = st.thing_id AND s.wpath = ?
    ORDER BY type_rank, val_num, val_text, val_bool LIMIT 1
) k ON true
WHERE st.global_read && ?
ORDER BY COALESCE(k.type_rank, 1), k.val_num, k.val_text, k.val_bool, st.thing_id
LIMIT 25
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| page 1                                     |         20 |      555 |      558 |      567 |      570 |

```
Limit  (cost=8514279.99..8514280.05 rows=25 width=65) (actual time=567.038..567.040 rows=25 loops=1)
  Buffers: shared hit=96351 read=153214
  ->  Sort  (cost=8514279.99..8514351.80 rows=28726 width=65) (actual time=527.441..527.442 rows=25 loops=1)
        Sort Key: (COALESCE((s.type_rank)::integer, 1)), s.val_num, s.val_text COLLATE "C", s.val_bool, st.thing_id COLLATE "C"
        Sort Method: top-N heapsort  Memory: 28kB
        Buffers: shared hit=96351 read=153214
        ->  Nested Loop Left Join  (cost=502.94..8513469.36 rows=28726 width=65) (actual time=4.752..524.740 rows=29411 loops=1)
              Buffers: shared hit=96351 read=153214
              ->  Bitmap Heap Scan on search_things st  (cost=209.14..73508.54 rows=28726 width=41) (actual time=4.716..85.079 rows=29411 loops=1)
                    Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Heap Blocks: exact=27130
                    Buffers: shared hit=2 read=27143
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=2.330..2.330 rows=29411 loops=1)
                          Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Limit  (cost=293.79..293.80 rows=1 width=20) (actual time=0.015..0.015 rows=1 loops=29411)
                    Buffers: shared hit=96349 read=126071
                    ->  Sort  (cost=293.79..293.85 rows=22 width=20) (actual time=0.015..0.015 rows=1 loops=29411)
                          Sort Key: s.type_rank, s.val_num, s.val_text COLLATE "C", s.val_bool
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=96349 read=126071
                          ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..293.68 rows=22 width=20) (actual time=0.011..0.014 rows=1 loops=29411)
                                Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                                Buffers: shared hit=96349 read=126071
Planning:
  Buffers: shared read=1
Planning Time: 0.113 ms
JIT:
  Functions: 12
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.278 ms, Inlining 4.753 ms, Optimization 19.569 ms, Emission 15.278 ms, Total 39.878 ms
Execution Time: 567.371 ms
```

## Page 100 via OFFSET 2475 LIMIT 25

```sql
SELECT st.thing_id, k.type_rank, k.val_num, k.val_text, k.val_bool
FROM search_things st
LEFT JOIN LATERAL (
    SELECT type_rank, val_num, val_text, val_bool FROM search_flat s
    WHERE s.thing_id = st.thing_id AND s.wpath = ?
    ORDER BY type_rank, val_num, val_text, val_bool LIMIT 1
) k ON true
WHERE st.global_read && ?
ORDER BY COALESCE(k.type_rank, 1), k.val_num, k.val_text, k.val_bool, st.thing_id
OFFSET 2475 LIMIT 25
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| page 100 via OFFSET                        |         20 |      563 |      569 |      575 |      587 |

```
Limit  (cost=8515240.43..8515240.50 rows=25 width=65) (actual time=619.206..619.209 rows=25 loops=1)
  Buffers: shared hit=96351 read=153214
  ->  Sort  (cost=8515234.25..8515306.06 rows=28726 width=65) (actual time=578.425..578.483 rows=2500 loops=1)
        Sort Key: (COALESCE((s.type_rank)::integer, 1)), s.val_num, s.val_text COLLATE "C", s.val_bool, st.thing_id COLLATE "C"
        Sort Method: top-N heapsort  Memory: 595kB
        Buffers: shared hit=96351 read=153214
        ->  Nested Loop Left Join  (cost=502.94..8513469.36 rows=28726 width=65) (actual time=4.846..565.940 rows=29411 loops=1)
              Buffers: shared hit=96351 read=153214
              ->  Bitmap Heap Scan on search_things st  (cost=209.14..73508.54 rows=28726 width=41) (actual time=4.810..93.101 rows=29411 loops=1)
                    Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Heap Blocks: exact=27130
                    Buffers: shared hit=2 read=27143
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=2.337..2.338 rows=29411 loops=1)
                          Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Limit  (cost=293.79..293.80 rows=1 width=20) (actual time=0.016..0.016 rows=1 loops=29411)
                    Buffers: shared hit=96349 read=126071
                    ->  Sort  (cost=293.79..293.85 rows=22 width=20) (actual time=0.016..0.016 rows=1 loops=29411)
                          Sort Key: s.type_rank, s.val_num, s.val_text COLLATE "C", s.val_bool
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=96349 read=126071
                          ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..293.68 rows=22 width=20) (actual time=0.012..0.015 rows=1 loops=29411)
                                Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                                Buffers: shared hit=96349 read=126071
Planning:
  Buffers: shared read=1
Planning Time: 0.125 ms
JIT:
  Functions: 13
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.292 ms, Inlining 4.984 ms, Optimization 20.223 ms, Emission 15.470 ms, Total 40.968 ms
Execution Time: 619.590 ms
```

## Page-99 boundary (anchor row for the keyset resume)

Anchor: rank=1, val_num=null, val_text=null, val_bool=null, thing_id=org.eclipse.ditto.bench.ns4:thing-767370
Populated sort column at this boundary: none (NULL tie-broken block) — falls back to thing_id only — see the Finding below.

Keyset page 100 returns exactly the OFFSET page 100 rows (verified): true

## Page 100 via keyset resume from the page-99 anchor

```sql
SELECT st.thing_id, k.type_rank, k.val_num, k.val_text, k.val_bool
FROM search_things st
LEFT JOIN LATERAL (
    SELECT type_rank, val_num, val_text, val_bool FROM search_flat s
    WHERE s.thing_id = st.thing_id AND s.wpath = ?
    ORDER BY type_rank, val_num, val_text, val_bool LIMIT 1
) k ON true
WHERE st.global_read && ?
  AND ((COALESCE(k.type_rank, 1) > ?) OR (COALESCE(k.type_rank, 1) = ? AND st.thing_id > ?))
ORDER BY COALESCE(k.type_rank, 1), k.val_num, k.val_text, k.val_bool, st.thing_id
LIMIT 25
```

| variant                                   | iterations |   min ms |   p50 ms |   p95 ms |   max ms |
|--------------------------------------------|-----------:|---------:|---------:|---------:|---------:|
| page 100 via keyset                        |         20 |      567 |      574 |      601 |      652 |

```
Limit  (cost=8514140.89..8514140.95 rows=25 width=65) (actual time=574.272..574.274 rows=25 loops=1)
  Buffers: shared hit=96350 read=153215
  ->  Sort  (cost=8514140.89..8514172.16 rows=12509 width=65) (actual time=525.944..525.945 rows=25 loops=1)
        Sort Key: (COALESCE((s.type_rank)::integer, 1)), s.val_num, s.val_text COLLATE "C", s.val_bool, st.thing_id COLLATE "C"
        Sort Method: top-N heapsort  Memory: 28kB
        Buffers: shared hit=96350 read=153215
        ->  Nested Loop Left Join  (cost=502.94..8513787.90 rows=12509 width=65) (actual time=4.691..523.414 rows=26936 loops=1)
              Filter: ((COALESCE((s.type_rank)::integer, 1) > 1) OR ((COALESCE((s.type_rank)::integer, 1) = 1) AND (st.thing_id > 'org.eclipse.ditto.bench.ns4:thing-767370'::text)))
              Rows Removed by Filter: 2475
              Buffers: shared hit=96350 read=153215
              ->  Bitmap Heap Scan on search_things st  (cost=209.14..73508.54 rows=28726 width=41) (actual time=4.651..85.286 rows=29411 loops=1)
                    Recheck Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                    Heap Blocks: exact=27130
                    Buffers: shared hit=2 read=27143
                    ->  Bitmap Index Scan on st_global_read  (cost=0.00..201.96 rows=28726 width=0) (actual time=2.285..2.285 rows=29411 loops=1)
                          Index Cond: (global_read && '{user:sub-000,user:sub-041}'::text[])
                          Buffers: shared hit=2 read=13
              ->  Limit  (cost=293.79..293.80 rows=1 width=20) (actual time=0.015..0.015 rows=1 loops=29411)
                    Buffers: shared hit=96348 read=126072
                    ->  Sort  (cost=293.79..293.85 rows=22 width=20) (actual time=0.015..0.015 rows=1 loops=29411)
                          Sort Key: s.type_rank, s.val_num, s.val_text COLLATE "C", s.val_bool
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=96348 read=126072
                          ->  Index Scan using search_flat_pkey on search_flat s  (cost=0.69..293.68 rows=22 width=20) (actual time=0.011..0.014 rows=1 loops=29411)
                                Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
                                Buffers: shared hit=96348 read=126072
Planning:
  Buffers: shared read=1
Planning Time: 0.150 ms
JIT:
  Functions: 15
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.418 ms, Inlining 4.781 ms, Optimization 24.950 ms, Emission 18.601 ms, Total 48.750 ms
Execution Time: 574.763 ms
```

## Finding

~10% of things (100,212 of 1,000,000) have no `/features/env/properties/temperature`
value at all, so under `COALESCE(k.type_rank, 1)` they sort first as one large tied
group (rank=1, val_num/val_text/val_bool all NULL, tie-broken purely by thing_id). Page
100 (rows 2476-2500) falls entirely inside that tied NULL block, so the "populated
column" the brief's pragmatic keyset emulation refers to is, at this boundary, *none of
them* — the resume predicate degenerates to a plain `rank = ? AND thing_id > ?` branch.
This is an honest artifact of sorting by a sparsely-populated path plus a materially
skewed corpus, not a shortcut in the emulation: a general implementation must handle
the "nothing populated, fall back to thing_id" case, which this bench's
`buildKeysetPredicate` does. Record the plan shape above at face value — this is exactly
the shape the brief calls out as hardest.

## Extended verification (review round 1/2) — populated val_num / val_text / val_bool keyset branches

Finding 2 (review round 1): the original page-100 boundary (OFFSET 2475) landed inside
the sort path's NULL block, so only `buildKeysetPredicate`'s NULL-anchor fallback branch
(its final `else`) was ever exercised end-to-end. The two boundaries below sit well past
each sort path's own NULL block (2,951 NULL rows for `/features/env/properties/temperature`, 3,038 for
`/attributes/location/city`, out of 29,411 gr-visible things for the fixed subject
pair used here — verified by direct SQL before coding), so the anchor row is populated
and the `val_num` / `val_text` branches fire.

Finding 3 (review round 2): the round-1 extension left `buildKeysetPredicate`'s val_bool
branch untested end-to-end. The corpus generator has no dedicated boolean hot path, so
`/attributes/certified` was picked after querying the live corpus for a wpath
with enough boolean rows (1,040 of the 29,411 gr-visible things for the fixed subject
pair: 522 `false`, 518 `true` — verified by direct SQL before coding). The `OFFSET 28893`
boundary is the exact ordinal split between the `false` and `true` sub-blocks (last
`false` row), chosen deliberately so the anchor's val_bool is `false` and the resumed
page is entirely `true` rows — this exercises the cross-value branch of the tie-break
(`k.val_bool = true AND anchor = false`), not just a same-value tie.

### numeric sort path (val_num branch) — sort path `/features/env/properties/temperature`, OFFSET 5000 boundary

Anchor: rank=2, val_num=-15.02, val_text=null, val_bool=null, thing_id=org.eclipse.ditto.bench.ns27:thing-479728
Populated sort column at this boundary (branch of `buildKeysetPredicate` exercised): val_num
Rows compared: 25
Keyset-resumed page equals OFFSET page, row-for-row (verified): true

```sql
SELECT st.thing_id, k.type_rank, k.val_num, k.val_text, k.val_bool
FROM search_things st
LEFT JOIN LATERAL (
    SELECT type_rank, val_num, val_text, val_bool FROM search_flat s
    WHERE s.thing_id = st.thing_id AND s.wpath = ?
    ORDER BY type_rank, val_num, val_text, val_bool LIMIT 1
) k ON true
WHERE st.global_read && ?
  AND ((COALESCE(k.type_rank, 1) > ?) OR (COALESCE(k.type_rank, 1) = ? AND (k.val_num > ? OR (k.val_num = ? AND st.thing_id > ?))))
ORDER BY COALESCE(k.type_rank, 1), k.val_num, k.val_text, k.val_bool, st.thing_id
LIMIT 25
```

### string sort path (val_text branch) — sort path `/attributes/location/city`, OFFSET 5000 boundary

Anchor: rank=3, val_num=null, val_text=Berlin, val_bool=null, thing_id=org.eclipse.ditto.bench.ns3:thing-931234
Populated sort column at this boundary (branch of `buildKeysetPredicate` exercised): val_text
Rows compared: 25
Keyset-resumed page equals OFFSET page, row-for-row (verified): true

```sql
SELECT st.thing_id, k.type_rank, k.val_num, k.val_text, k.val_bool
FROM search_things st
LEFT JOIN LATERAL (
    SELECT type_rank, val_num, val_text, val_bool FROM search_flat s
    WHERE s.thing_id = st.thing_id AND s.wpath = ?
    ORDER BY type_rank, val_num, val_text, val_bool LIMIT 1
) k ON true
WHERE st.global_read && ?
  AND ((COALESCE(k.type_rank, 1) > ?) OR (COALESCE(k.type_rank, 1) = ? AND (k.val_text > ? OR (k.val_text = ? AND st.thing_id > ?))))
ORDER BY COALESCE(k.type_rank, 1), k.val_num, k.val_text, k.val_bool, st.thing_id
LIMIT 25
```

### boolean sort path (val_bool branch) — sort path `/attributes/certified`, OFFSET 28893 boundary

Anchor: rank=6, val_num=null, val_text=null, val_bool=false, thing_id=org.eclipse.ditto.bench.ns9:thing-930338
Populated sort column at this boundary (branch of `buildKeysetPredicate` exercised): val_bool
Rows compared: 25
Keyset-resumed page equals OFFSET page, row-for-row (verified): true

```sql
SELECT st.thing_id, k.type_rank, k.val_num, k.val_text, k.val_bool
FROM search_things st
LEFT JOIN LATERAL (
    SELECT type_rank, val_num, val_text, val_bool FROM search_flat s
    WHERE s.thing_id = st.thing_id AND s.wpath = ?
    ORDER BY type_rank, val_num, val_text, val_bool LIMIT 1
) k ON true
WHERE st.global_read && ?
  AND ((COALESCE(k.type_rank, 1) > ?) OR (COALESCE(k.type_rank, 1) = ? AND ((k.val_bool = true AND ? = false) OR (k.val_bool IS NOT DISTINCT FROM ? AND st.thing_id > ?))))
ORDER BY COALESCE(k.type_rank, 1), k.val_num, k.val_text, k.val_bool, st.thing_id
LIMIT 25
```

