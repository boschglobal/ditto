# Persistence ping on a partly tagged corpus: PersistencePingActor, four walks

- generated: 2026-10-22T19:03:26.133706Z
- corpus: count=250000 seed=42 pingTagMix=partly-tagged (load evidence: `load-pg-partly-tagged-250k-repeat.md`), schema v3
- backend: pg
- containers: ditto-persistence-bench-pg: cpus=4, memory=8 GiB, postgres -c shared_buffers=4GB -c effective_cache_size=6144MB -c work_mem=64MB
- evidence label: 250k-repeat

## Settings

- the production PersistencePingActor with the policies ping settings: journal-tag `always-alive`, streaming-order ID, read-journal-batch-size 500, initial-delay 0s, interval 600 s; only the rate is raised, to 1000 pings/s (policies.conf ships 1/s)
- client: exactly the production pool (`ConnectionPoolFactory.createConnectionPool`), `PostgresReadJournal` over `things_*`; a TestKit is the shard region
- injection point: the source of the 2nd walk fails at `thing:bench.ns06:pid-00172392`, index 16,019 of the 32,038 expected pids

## Walks

| walk | started (UTC) | ended | duration | completed | continued after | pinged | expected | missing | extra | dup | end log (verbatim) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2026-10-22T19:03:26.227654Z | 2026-10-22T19:08:14.146417Z | 287.9 s | yes | — | 32,038 | 32,038 | 0 | 0 | 0 | `Ping iteration completed: pinged <32038> PersistenceActors in <PT4M47.877291792S>.` |
| 2 | 2026-10-22T19:13:26.241856Z | 2026-10-22T19:15:54.397734Z | 148.2 s | no | — | 16,019 | 16,019 | 0 | 0 | 0 | `Ping iteration failed after pinging <16019> PersistenceActors in <PT2M28.148066542S>; the next iteration starts as scheduled, every <PT10M>, and continues after <thing:bench.ns06:pid-00172260>. Cause: org.eclipse.ditto.internal.utils.persistence.bench.PingActorRun$InjectedWalkFailure: injected walk failure at <thing:bench.ns06:pid-00172392>` |
| 3 | 2026-10-22T19:23:26.242527Z | 2026-10-22T19:28:10.220469Z | 284.0 s | yes | `thing:bench.ns06:pid-00172260` | 16,019 | 16,019 | 0 | 0 | 0 | `Ping iteration completed: pinged <16019> PersistenceActors in <PT4M43.974949542S>.` |
| 4 | 2026-10-22T19:33:26.243800Z | 2026-10-22T19:38:11.129887Z | 284.9 s | yes | — | 32,038 | 32,038 | 0 | 0 | 0 | `Ping iteration completed: pinged <32038> PersistenceActors in <PT4M44.883042417S>.` |

Walk 2 pinged 16,019 of the 16,019 pids before the injection point. A failing source may drop the element buffered in front of the throttle; walk 3 continued after the last pid walk 2 reached.

- skipped ticks ("Another ping iteration is currently in progress"): 0
- other warnings: none
- host memory before: 2026-10-22T19:03:26.137933Z swap_used_MB=1857.44 swapins=4551429 swapouts=6153558 free=50
- host memory after: 2026-10-22T19:38:11.144537Z swap_used_MB=1857.44 swapins=4551457 swapouts=6153558 free=56
- host memory verdict: RELIABLE

**Result: every walk delivered exactly the expected pids.**
