# Persistence ping on a partly tagged corpus: PersistencePingActor, four walks

- generated: 2026-10-22T18:25:05.744191Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged (load evidence: `load-pg-partly-tagged-250k.md`), schema v3
- backend: pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- evidence label: 250k

## Settings

- the production PersistencePingActor with the policies ping settings: journal-tag `always-alive`, streaming-order ID, read-journal-batch-size 500, initial-delay 0s, interval 600 s; only the rate is raised, to 1000 pings/s (policies.conf ships 1/s)
- client: exactly the production pool (`ConnectionPoolFactory.createConnectionPool`), `PostgresReadJournal` over `things_*`; a TestKit is the shard region
- injection point: the source of the 2nd walk fails at `thing:bench.ns06:pid-00172392`, index 16,019 of the 32,038 expected pids

## Walks

| walk | started (UTC) | ended | duration | completed | continued after | pinged | expected | missing | extra | dup | end log (verbatim) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2026-10-22T18:25:05.841657Z | 2026-10-22T18:30:04.674738Z | 298.8 s | yes | — | 32,038 | 32,038 | 0 | 0 | 0 | `Ping iteration completed: pinged <32038> PersistenceActors in <PT4M58.788703875S>.` |
| 2 | 2026-10-22T18:35:05.850326Z | 2026-10-22T18:37:36.026613Z | 150.2 s | no | — | 16,019 | 16,019 | 0 | 0 | 0 | `Ping iteration failed after pinging <16019> PersistenceActors in <PT2M30.171821708S>; the next iteration starts as scheduled, every <PT10M>, and continues after <thing:bench.ns06:pid-00172260>. Cause: org.eclipse.ditto.internal.utils.persistence.bench.PingActorRun$InjectedWalkFailure: injected walk failure at <thing:bench.ns06:pid-00172392>` |
| 3 | 2026-10-22T18:45:05.865107Z | 2026-10-22T18:49:51.514426Z | 285.6 s | yes | `thing:bench.ns06:pid-00172260` | 16,019 | 16,019 | 0 | 0 | 0 | `Ping iteration completed: pinged <16019> PersistenceActors in <PT4M45.645585917S>.` |
| 4 | 2026-10-22T18:55:05.867153Z | 2026-10-22T18:59:51.897844Z | 286.0 s | yes | — | 32,038 | 32,038 | 0 | 0 | 0 | `Ping iteration completed: pinged <32038> PersistenceActors in <PT4M46.028015959S>.` |

Walk 2 pinged 16,019 of the 16,019 pids before the injection point. A failing source may drop the element buffered in front of the throttle; walk 3 continued after the last pid walk 2 reached.

- skipped ticks ("Another ping iteration is currently in progress"): 0
- other warnings: none
- host memory before: 2026-10-22T18:25:05.748648Z swap_used_MB=1857.44 swapins=4551316 swapouts=6153558 free=49
- host memory after: 2026-10-22T18:59:51.919128Z swap_used_MB=1857.44 swapins=4551429 swapouts=6153558 free=55
- host memory verdict: UNRELIABLE

**Result: every walk delivered exactly the expected pids.**
