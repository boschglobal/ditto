# Running Eclipse Ditto on PostgreSQL locally

This directory contains everything needed to boot Ditto microservices against the **PostgreSQL persistence
backend** locally and prove it works — without editing any shipped `*.conf`. Activation is done entirely with
NEW overlay config files (in this directory) injected via environment variables.

All files here are additive; no committed service config is modified.

---

## What's here

| File | Purpose |
|------|---------|
| `docker-compose.postgres.yml` | A standalone Postgres 16 (db `ditto`, user/pw `ditto`). Does NOT touch `deployment/docker/docker-compose.yml`. |
| `things-postgres.conf` | Overlay that activates Postgres for the Things service (joins the Policies-founded cluster at 2552). |
| `policies-postgres.conf` | Overlay that activates Postgres for the Policies service (cluster founder). |
| `run-compound-postgres.sh` | CLI launcher for Policies + Things + Gateway, all wired to Postgres / pre-auth. |

IntelliJ run configs live in `.run/` at the repo root:
`PoliciesService (Postgres)`, `ThingsService (Postgres)`, `GatewayService (Postgres)`, and the
compound `Ditto (Postgres)` (which launches all three together).

---

## How activation works (and the four gotchas that took debugging)

Ditto does **not** honor `-Dconfig.file`. It builds its config via `RawConfigSupplier`:
`FILE_BASED overlay` (highest priority) → `things.conf` → `ditto-service-base.conf` → `reference.conf`.
We inject our overlay as the `FILE_BASED` layer:

```
HOSTING_ENVIRONMENT=filebased
HOSTING_ENVIRONMENT_FILE_LOCATION=<abs path to the overlay>
```

The overlay is layered ON TOP of `things.conf`, so everything in `things.conf` is preserved; we only ADD the
Postgres wiring. The overlays encode four non-obvious fixes:

1. **`include classpath("...")`, not bare `include "..."`.** The FILE_BASED file is parsed with
   `parseFileAnySyntax`, where a bare include is file-relative and silently resolves to nothing (the profile
   lives on the classpath inside the persistence-r2dbc jar). `classpath(...)` forces classpath resolution.
2. **Top-level includes, not inside `ditto { }`.** `ditto-postgres-persistence.conf` ships its own `ditto { }`
   wrapper PLUS top-level Pekko plugin blocks (`ditto-postgres-things-journal { }`, ...). Nesting the include
   inside `ditto { }` would misplace those plugin blocks. (The operator guide's `ditto { include ... }` sketch
   is misleading on this point.)
3. **The persistence-r2dbc jar + r2dbc driver must be on the runtime classpath, FIRST.** The service allinone
   jar does NOT bundle the persistence-r2dbc module (provider/journal classes + profile resources + driver),
   and it bundles an OLD `reactor-core` (3.4.x) lacking `Mono.singleOptional()` that the r2dbc driver calls.
   The CLI scripts prepend the r2dbc module jar and its runtime deps (reactor-core 3.5.x, r2dbc-postgresql,
   r2dbc-pool) BEFORE the allinone jar. In IntelliJ this is supplied by a dedicated local-dev module,
   `ide-postgres-launcher` (artifact `ditto-ide-postgres-launcher`): it compile-depends on the services **and**
   `ditto-internal-utils-persistence-r2dbc`, and the `(Postgres)` run configs set their "Use classpath of module"
   to it (keeping the service's own main class). This is needed because the service modules deliberately don't
   depend on r2dbc (the `enforce-no-r2dbc-in-service` rule keeps it out of their allinones) and an IntelliJ
   Application run uses only a module's *production* classpath — so a test-scope dep on the service would never
   reach the run classpath. The launcher is never shaded or published, so r2dbc stays out of every service image.
   Run *Reload All Maven Projects* after pulling for IntelliJ to pick up the new module. (In IntelliJ the module
   graph also resolves the project's `reactor-core` 3.5.20, so the allinone's old 3.4.x copy is not involved.)
4. **Singleton auto-start journal + Mongo `overrides` placeholders.** The Things/Policies root actors always
   start a Mongo-era operations actor and a snapshot-streaming actor that read `pekko.persistence.*.auto-start-*`
   (must be exactly one entry) and `<journal-plugin>.overrides.*-collection`. The overlays repoint the
   auto-start lists at the Postgres plugins and add harmless placeholder `overrides` collection names so those
   actors construct. (See "Known limitations" below.)

One more knob is passed via env (the service configs already expose it):
* `POSTGRES_SSL_MODE=disable` — the default SSL mode is `verify-full`; a local plaintext Postgres needs this or
  the pool refuses to connect.

The snapshot envelope no longer needs a per-service override: each service selects only its backend-neutral
`ditto.extensions.snapshot-serializer`, and the persistent actors compose it with the active backend's snapshot
codec (Mongo BSON / Postgres JSONB) resolved from the persistence-backend-provider. Including the Postgres
persistence profile therefore switches the snapshot envelope to JSONB automatically.

### Extension JAR placement

The Postgres backend is packaged as a separate JAR (`ditto-internal-utils-persistence-r2dbc-extension-<version>.jar`)
that is **not** bundled in the service allinone JARs.

* **Production / Kubernetes:** drop the extension JAR (plus its runtime deps — r2dbc-postgresql, r2dbc-pool,
  reactor-core 3.5.x) into `/opt/ditto/extensions/` of each service container. The Helm operator guide has an
  example of staging this via an `initContainer`.
* **Local CLI dev (this directory):** the launch scripts assemble the classpath manually — they prepend the
  extension JAR and its runtime deps before the allinone JAR, which is equivalent to the
  `/opt/ditto/extensions/` drop-in mechanism. No manual JAR copying is needed when using the scripts or the
  IntelliJ run configs.

---

## Step 1 — Start Postgres

```bash
docker compose -f deployment/postgres-local/docker-compose.postgres.yml up -d
# or: docker run -d --name ditto-postgres-local -e POSTGRES_DB=ditto -e POSTGRES_USER=ditto \
#       -e POSTGRES_PASSWORD=ditto -p 5432:5432 postgres:16
```

## Step 2 — Build (once)

```bash
MVN=/opt/homebrew/Cellar/sdkman-cli/5.18.2/libexec/candidates/maven/3.9.3/bin/mvn
$MVN -pl :ditto-things-service,:ditto-policies-service,:ditto-gateway-service,:ditto-internal-utils-persistence-r2dbc \
     -am -DskipTests -Dcheckstyle.skip -Dlicense.skip=true -Dpmd.skip=true install
```

## Step 3 — Run Policies + Things + Gateway: full HTTP write→recover demo

Quick schema sanity check once the services are up (the persistence schema is auto-created on first boot):
```bash
docker exec ditto-postgres-local psql -U ditto -d ditto -c '\dt'
#   -> things_journal, things_journal_seq, things_snaps, policies_*, connections_*, wot_*, schema_version
```

```bash
./deployment/postgres-local/run-compound-postgres.sh
# logs: /tmp/policies-pg.log /tmp/things-pg.log /tmp/gateway-pg.log
```
or in IntelliJ run the compound **Ditto (Postgres)**.

Wait until the gateway is up (`curl -s http://localhost:8080/health` is green), then:

```bash
AUTH='x-ditto-pre-authenticated: nginx:ditto'

# 1. Create a policy (Postgres-backed)
curl -s -X PUT -H "$AUTH" -H 'Content-Type: application/json' \
  http://localhost:8080/api/2/policies/org.eclipse.ditto:demo \
  -d '{"entries":{"DEFAULT":{"subjects":{"nginx:ditto":{"type":"x"}},
       "resources":{"thing:/":{"grant":["READ","WRITE"],"revoke":[]},
                    "policy:/":{"grant":["READ","WRITE"],"revoke":[]}}}}}'

# 2. Create a thing (Postgres-backed)
curl -s -X PUT -H "$AUTH" -H 'Content-Type: application/json' \
  http://localhost:8080/api/2/things/org.eclipse.ditto:demo-thing \
  -d '{"policyId":"org.eclipse.ditto:demo","attributes":{"colour":"blue"}}'

# 3. Confirm the row landed in Postgres
docker exec ditto-postgres-local psql -U ditto -d ditto \
  -c "SELECT pid, sn, manifest FROM things_journal ORDER BY sn;"

# 4. RESTART the Things service, then GET the thing back to prove recovery from Postgres
#    (kill just the things JVM and re-run its launcher, or restart the run config)
curl -s -H "$AUTH" http://localhost:8080/api/2/things/org.eclipse.ditto:demo-thing
#    -> returns the thing with attributes.colour=blue, recovered from things_journal / things_snaps
```

---

## Known limitations (local demo)

* The **snapshot-streaming actor** (`snapshotStreamingActor`) and the **persistence-cleanup / operations**
  actors are Mongo-specific: they expect a `<journal>-read` Pekko plugin and Mongo collections. On Postgres the
  streaming actor logs an `ActorInitializationException` for the missing `ditto-postgres-things-journal-read`
  plugin and the operations actor is a no-op. **These do not affect entity persistence or recovery** — Thing/
  Policy actors resolve their journal/snapshot plugins through the Postgres backend provider, write to
  `*_journal` / `*_snaps`, and recover from them on restart. They are background streaming/cleanup paths that
  are simply not yet ported to Postgres.
* The Mongo health-checker (`MongoHealthChecker`) still reports the persistence subsystem; with no Mongo running
  it may report UP based on its own logic. Trust the Postgres table contents + recovery log lines as the real
  evidence.
* IntelliJ compound runs start all three services at once; they converge via the seed-node retry loop. The CLI
  `run-compound-postgres.sh` sleeps between services to start the Policies founder first (more deterministic).
