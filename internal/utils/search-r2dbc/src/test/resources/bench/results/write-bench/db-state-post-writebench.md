# Bench-DB end state after Task 0.3 (Part 0 normalization + Part 1 + Part 2 write benchmarks)

Captured directly against `ditto-search-bench-pg` immediately after both `WriteFanoutBench` methods completed
(`part1SustainedUpsertBlanket` then `part2AntiJoinVsBlanketComparison`, run sequentially, no container restart
in between). This is the state the NEXT task (or a full-scale follow-up) inherits.

## Index list and sizes

```
search_flat_pkey       | 15 GB
search_things_pkey     | 110 MB
sf_bool                | 87 MB
sf_exists              | 1224 MB
sf_num                 | 4225 MB
sf_text                | 633 MB
sf_trgm                | 304 MB
sf_trgm_scoped_feature | 51 MB
st_delete_at           | 456 kB
st_global_read         | 18 MB
st_modified            | 26 MB
st_namespace           | 92 MB
st_policy              | 62 MB
st_referenced_pols     | 7112 kB
```

Compare to the Part 0 post-normalization baseline (`part0-db-normalization.md`): `search_flat_pkey` 14GB→15GB,
`sf_num` 1050MB→4225MB (largest grower — the only populated value-family index for this bench's all-numeric
twin payload), `sf_exists` 754MB→1224MB, `st_global_read` 9616kB→18MB (churned by every doc-row upsert's
`global_read` column write). `sf_text`/`sf_bool`/`sf_trgm`/`sf_trgm_scoped_feature` are UNCHANGED from Part 0
(304MB/51MB) — confirmed byte-identical throughout Parts 1/2's 30s samples (see those files) — this bench's
twin payload is all-numeric, so the text/boolean/trigram index families see zero write load from it (disclosed
caveat, see Part 1/Part 2 files).

## Row counts

```
search_things = 1000000
search_flat   = 71106712
```

Grew from the Part 0 baseline (68,513,542 flat rows) by +2,593,170 rows. Sanity check: this bench converted
~6,000 distinct thing_ids (Part 1's 2,000-thing pool + Part 2's two disjoint 2,000-thing pools) from their
original CorpusGenerator shape (~68.5 flat rows/thing average, per Task 0.1) to the fixed 500-leaf twin shape;
expected net growth ≈ 6,000 × (500 − 68.5) ≈ 2,589,000 rows — matches the observed +2,593,170 almost exactly.

## pg_stat_user_tables (at capture time)

```
relname        | n_live_tup | n_dead_tup | last_autovacuum              | autovacuum_count
search_things  |    999341  |     22802  | 2026-07-04 13:07:08.974982+00|  2
search_flat    |  71106712  |  61413895  | 2026-07-04 13:09:20.408824+00|  2
```

`search_flat`'s dead-tuple count (61.4M, ~86% of live rows) is what remained AFTER the single autovacuum
cycle carried over from before Part 1 finally completed at 13:09:20 — i.e. after Part 2 finished entirely
(this capture, `autovacuum_count`=2, is the first committed data point where that cycle counts as complete;
Part 2's Phase B samples still show `autovacuum_count`=1 and `n_dead_tup` static at ~72.6-72.9M through the
end of Phase B — see that file). The completed cycle did NOT reset `n_dead_tup` to 0: it went from ~72.9M
(Phase B's last sample) to the 61.4M seen here, a partial reclaim only. (An earlier draft of this note
claimed a boundary-timed completion with a reset to 0, citing Part 1's finding — both details were
inferences contradicted by the committed samples and are corrected here and in the Part 1/Part 2 files.)
No autovacuum worker was active against `search_flat` at the exact moment of this capture, but one had been
continuously active for the entire span of Parts 1+2 — see Part 1's `pg_stat_activity` evidence and the
Phase A/B sample lines.

## Settings (unchanged from Part 0 / Task 0.2b's E2)

```
shared_buffers = 4GB
effective_cache_size = 12GB
work_mem = 64MB
```

## Disk headroom

`df -h /`: 20 GB free (was 35 GB free before Part 1 started, 32 GB before Part 0's index drop) — Parts 1+2
consumed ~15 GB of headroom over their combined ~25 minutes of sustained churn. Healthy margin remains for
any immediate follow-up work on this host; a full-scale (10M-corpus, ≥1h) run would need this re-assessed
against that host's own disk budget (a 10M corpus's baseline footprint alone was ~270GB-scale by linear
extrapolation from Task 0.1's 1M/27GB figure, before any write-churn growth on top — well beyond this
laptop's disk and out of scope for the reduced-scale Phase-0 runs).
