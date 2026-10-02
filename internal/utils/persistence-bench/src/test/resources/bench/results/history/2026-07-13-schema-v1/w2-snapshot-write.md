> **Provenance (historical evidence, do not edit).** Copied verbatim from commit `89a7cf51df`
> ("full-scale (count=250000) evidence + curated headline results", reachable via the local tag
> `pre-squash-postgres-persistance`; the squashed branch history no longer contains it), path
> `internal/utils/persistence-bench/src/test/resources/bench/results/w2-snapshot-write.md`.
> Run date: 2026-07-13T08:46Z (UTC), `--count 250000`, seed 42.
> Schema: **v1** — snapshots carried the extra `(pid, sn DESC)` index, and the cleanup pid-stream
> page used the `DISTINCT ON` query (before the loose-index-scan rewrite).
> **Superseded by the files one level up** (schema v2 + loose-index-scan query); kept as the "before"
> side of the comparisons in `benchmark/README.md` §5.

# W2 — snapshot write (~4 KB)

- generated: 2026-07-13T08:46:01.185132Z
- corpus: count=250000 seed=42
- backend(s): both
- protocol: 3 warmup + 20 timed executions, nearest-rank percentiles, pgjdbc prepareThreshold=0
- cleanup defaults: reads-per-query=100, writes-per-credit=100 (DefaultCleanupConfig)

| scenario | PG p50 \| p95 \| max (ms) | Mongo p50 \| p95 \| max (ms) |
|---|---|---|
| fresh insert (new sn per iteration) | 2.67 \| 4.31 \| 4.68 | 4.48 \| 6.25 \| 6.63 |
| overwrite (same pid/sn/ts key) | 2.73 \| 3.30 \| 5.77 | 2.67 \| 3.35 \| 3.50 |

PG: INSERT ... ON CONFLICT (pid, sn, written_at) DO UPDATE. Mongo: replaceOne({pid,sn,ts}, upsert=true), WriteConcern JOURNALED (ScalaDriverPersistenceSnapshotter.saveSnapshot).
