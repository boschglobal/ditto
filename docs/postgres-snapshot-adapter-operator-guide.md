# Eclipse Ditto — PostgreSQL persistence backend (operator guide)

## Overview

The PostgreSQL persistence backend is delivered as a single drop-in extension JAR
(`ditto-internal-utils-persistence-r2dbc-extension`, shaded) plus a HOCON overlay file.
That combination is all that is needed to switch a Ditto service from MongoDB to PostgreSQL:

* The extension JAR provides the Pekko persistence plugin (journal + snapshot-store), the R2DBC
  connection pool, the JSONB snapshot codec, and the schema bootstrap manager.
* The HOCON overlay activates the backend and supplies runtime connection parameters.
* Snapshot serialization is **automatic** — `AbstractPersistenceActor` composes the service's own
  snapshot serializer with the JSONB codec resolved from the `persistence-backend-provider`.
  No per-service snapshot-adapter override class is needed or supported.

A single extension selection key controls which backend is active at runtime:

```
ditto.extensions.persistence-backend-provider
```

This key is set by the `ditto-postgres-persistence.conf` profile included in the overlay (see below).

---

## Prerequisites

1. **Extension JAR on the classpath** — place `ditto-internal-utils-persistence-r2dbc-extension-<version>-shaded.jar`
   into `/opt/ditto/extensions/`. The Ditto process scans that directory at boot and adds all JARs to
   the classpath automatically.
2. **Reachable PostgreSQL instance** — version 14 or later is supported. The `POSTGRES_URI` env var
   must point at it (R2DBC URI format: `r2dbc:postgresql://host:5432/dbname`).
3. **DDL privileges** — on first boot `PostgresSchemaManager` creates the journal, snapshot, and
   sequence tables. It uses `POSTGRES_DDL_USER` / `POSTGRES_DDL_PASSWORD` if set, otherwise the
   runtime role. The DDL role needs `CREATE TABLE` on the target schema.

---

## Config activation

### How the overlay is injected

Ditto does **not** honour `-Dconfig.file` / `-Dconfig.resource`. It builds its effective config
through `RawConfigSupplier`, which layers environment-specific config at the top. The non-invasive
way to add a PostgreSQL overlay on top of the shipped `things.conf` / `policies.conf` without
editing them is the `filebased` hosting environment:

```sh
HOSTING_ENVIRONMENT=filebased
HOSTING_ENVIRONMENT_FILE_LOCATION=/abs/path/to/things-postgres.conf
```

The overlay file is parsed with `ConfigFactory.parseFileAnySyntax(...)` and used as the
highest-priority layer, so the service's stock config is preserved and the overlay only adds the
Postgres wiring.

### Overlay file structure

```hocon
# things-postgres.conf — injected via HOSTING_ENVIRONMENT_FILE_LOCATION

# Re-include the dev config that filebased normally replaces
include classpath("things-dev")

# TOP-LEVEL includes (NOT inside ditto {})
include classpath("ditto-postgres-persistence")
include classpath("ditto-postgresql")

# Repoint Pekko's auto-start to the Postgres plugin (must be exactly ONE entry)
pekko.persistence.journal.auto-start-journals = [ "ditto-postgres-things-journal" ]
pekko.persistence.snapshot-store.auto-start-snapshot-stores = [ "ditto-postgres-things-snapshots" ]

# Placeholder collection names (Mongo-era ops/streaming actors read these)
ditto-postgres-things-journal.overrides   { journal-collection = "things_journal"; metadata-collection = "things_metadata" }
ditto-postgres-things-snapshots.overrides { snaps-collection = "things_snaps" }
```

Two `include` rules are load-bearing:

* **Includes must be TOP-LEVEL, not inside `ditto { }`.** `ditto-postgres-persistence.conf` ships
  its own top-level `ditto { extensions {…} persistence {…} }` wrapper **and** top-level Pekko
  plugin blocks (`ditto-postgres-things-journal { }`, `ditto-postgres-things-snapshots { }`, …)
  that live outside any `ditto { }` block. Nesting the include under `ditto { }` relocates those
  plugin blocks to `ditto.ditto-postgres-things-journal` and Pekko never finds them.
* **Always use `include classpath("…")`, not the bare `include "…"` form.** Because the overlay is
  parsed with `parseFileAnySyntax`, a bare string include is a file-relative heuristic that silently
  resolves to nothing (the profile resources live on the classpath inside the extension JAR, not next
  to the overlay file). `classpath(...)` forces classpath resolution.

### Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `HOSTING_ENVIRONMENT` | yes | — | Set to `filebased` to activate the overlay |
| `HOSTING_ENVIRONMENT_FILE_LOCATION` | yes | — | Absolute path to the overlay conf file |
| `POSTGRES_URI` | yes | — | R2DBC connection URI (`r2dbc:postgresql://…`) |
| `POSTGRES_USER` | yes | — | Runtime database role |
| `POSTGRES_PASSWORD` | yes | — | Runtime database role password |
| `POSTGRES_DDL_USER` | no | *(reuse runtime)* | Role used for DDL during schema bootstrap |
| `POSTGRES_DDL_PASSWORD` | no | *(reuse runtime)* | DDL role password |
| `POSTGRES_SSL_MODE` | no | `verify-full` | SSL verification mode; use `disable` for local plaintext |
| `POSTGRES_POOL_MAX_SIZE` | no | `100` | Maximum connections in the R2DBC pool |
| `POSTGRES_READ_JOURNAL_ENTITY` | no | *(service default)* | Override the read-journal entity binding |

---

## Boot self-check

On startup the extension module (G2 validation) verifies its own wiring before any persistence actor
starts:

* The `persistence-backend-provider` resolves to the Postgres implementation.
* The JSONB snapshot codec is reachable from the provider.
* The R2DBC pool configuration is consistent (URI, credentials, SSL mode).

If any check fails the process exits with a diagnostic message rather than running silently broken.
A successful self-check produces a log line at `INFO` level:

```
PostgresPersistenceBackendProvider — self-check OK; journal/snapshot plugins wired
```

---

## Schema bootstrap

`PostgresSchemaManager.bootstrap()` runs automatically on first boot, before any Pekko actor
starts. It creates the required tables and sequences inside a single DDL transaction:

* `<entity>_journal` — event log (JSONB `event` column)
* `<entity>_snaps` — snapshot store (JSONB `snapshot` column)
* supporting sequences and indexes

The table prefix matches the `overrides.*-collection` values in the overlay (the names are
informational labels; the actual table names are determined by the plugin's own configuration).

**Data migration is the operator's responsibility.** No migration tooling from MongoDB to PostgreSQL
is provided in this release. A fresh PostgreSQL deployment starts with empty tables; any existing
MongoDB event history is not automatically transferred.

---

## Verification / smoke test

After starting the service on PostgreSQL, confirm end-to-end operation:

1. **Write an entity** via the Ditto HTTP API (e.g. `PUT /api/2/things/<namespace>:<name>`).
2. **Confirm journal row** — query `SELECT count(*) FROM things_journal` (or the equivalent table
   for the service under test); the count must be non-zero.
3. **Confirm snapshot row** — after the actor has emitted at least one snapshot,
   `SELECT count(*) FROM things_snaps` must be non-zero.
4. **Restart the service** and GET the entity back. A clean recovery (no `IllegalArgumentException`
   or `ClassCastException` in the log) confirms the JSONB codec round-trip is working.
5. **Check the recovery log line** — a successful actor recovery emits:

   ```
   AbstractPersistenceActor — recovered [ThingPersistenceActor] from snapshot at sequenceNr=…
   ```

The canonical reference run is `deployment/postgres-local/` — its overlay confs, launcher scripts,
and `README.md` encode the proven working configuration.

---

## Kubernetes / Helm deployment

The Ditto Helm chart (`deployment/helm/ditto/`) supports opt-in PostgreSQL activation via
`extraVolumes`, `extraVolumeMounts`, and `extraInitContainers` on each service.
**Default Mongo deployments are NOT affected** — these keys are null (off) by default.

### Step 1: stage the extension JAR via an initContainer

The `ditto-internal-utils-persistence-r2dbc-extension` shaded JAR must reach
`/opt/ditto/extensions/` inside the Things/Policies/Connectivity pods.
A portable approach is to have an initContainer download or copy the JAR into an `emptyDir` volume
that is then mounted at `/opt/ditto/extensions/`.

Add the following to your `values.yaml` override (shown for `things`; repeat the same pattern under
`policies` and `connectivity`, substituting the appropriate plugin IDs in the overlay conf):

```yaml
things:
  extraInitContainers:
    - name: stage-postgres-extension
      image: curlimages/curl:8.6.0
      command:
        - sh
        - -c
        - |
          curl -fL -o /extensions/ditto-internal-utils-persistence-r2dbc-extension.jar \
            "https://your-artifact-host/path/to/ditto-internal-utils-persistence-r2dbc-extension-<version>.jar"
      volumeMounts:
        - name: ditto-extensions
          mountPath: /extensions

  extraVolumes:
    - name: ditto-extensions
      emptyDir: {}

  extraVolumeMounts:
    - name: ditto-extensions
      mountPath: /opt/ditto/extensions

  extraEnv:
    - name: HOSTING_ENVIRONMENT
      value: filebased
    - name: HOSTING_ENVIRONMENT_FILE_LOCATION
      value: /opt/ditto/things-postgres.conf
    - name: POSTGRES_URI
      value: r2dbc:postgresql://postgres:5432/ditto
    - name: POSTGRES_USER
      valueFrom:
        secretKeyRef:
          name: postgres-credentials
          key: username
    - name: POSTGRES_PASSWORD
      valueFrom:
        secretKeyRef:
          name: postgres-credentials
          key: password
    - name: POSTGRES_SSL_MODE
      value: verify-full   # or disable for local/dev without TLS
```

### Step 2: mount the overlay conf

The activation overlay conf (`things-postgres.conf`) can be delivered via a `ConfigMap`:

```yaml
# Create the ConfigMap once (or manage it as a Helm template value):
kubectl create configmap things-postgres-conf \
  --from-file=things-postgres.conf=path/to/your/things-postgres.conf

# Then in values.yaml:
things:
  extraVolumes:
    - name: ditto-extensions
      emptyDir: {}
    - name: postgres-overlay
      configMap:
        name: things-postgres-conf

  extraVolumeMounts:
    - name: ditto-extensions
      mountPath: /opt/ditto/extensions
    - name: postgres-overlay
      mountPath: /opt/ditto/things-postgres.conf
      subPath: things-postgres.conf
```

See `deployment/helm/ditto/values.yaml` comments under `things.extraInitContainers` for the
full annotated example.

---

## Known limitations

* **`snapshotStreamingActor` is not functional on PostgreSQL.** At boot a non-fatal
  `IllegalArgumentException` may appear because the backend provides no Mongo-style read-journal
  plugin (`ditto-postgres-<entity>-journal-read`). Entity **write and recover** work correctly; the
  background snapshot-streaming / persistence-cleanup / search-sync paths are not yet ported to
  PostgreSQL.
* **`MongoHealthChecker` may report `UP` even without MongoDB.** The health indicator does not
  check PostgreSQL connectivity. Trust table contents and actor-recovery log lines as the real
  liveness signal.
* **No migration tooling.** Moving event history from MongoDB to PostgreSQL is an operator task;
  no automated migration path is included.

---

## JSONB canonicalisation of stored event/snapshot payloads

The journal `event` and snapshot `snapshot` columns are PostgreSQL `jsonb`. PostgreSQL re-serialises
any value into its canonical form on store, so the JSON text read back from these columns is **not**
byte-identical to the text written in. This is intentional: `jsonb` is kept (rather than `TEXT`)
because it gives indexability and query-side predicates; the round-trip is semantics-preserving, so
consumers that re-parse the payload (via `JsonFactory`) always recover the same key/value structure.

The four canonicalisation rules PG16 applies (probed 2026-06-15; pinned by
`org.eclipse.ditto.internal.utils.persistence.postgres.JsonbCanonicalizationIT`):

1. **Object keys are sorted alphabetically** within each nested object. (Top-level key order is *not*
   promised by `jsonb` — treat it as undefined; do not rely on it.)
2. **Scientific notation is flattened** to plain decimal: `1E10` → `10000000000`, `-2.5E-3` → `-0.0025`.
3. **Arbitrary-precision integers are preserved** beyond the IEEE-754 double range (no float-precision
   loss): `9007199254740993` survives intact.
4. **Whitespace inside string keys/values is preserved**; inter-token (structural) whitespace is stripped.

Operational consequence: do **not** compare raw `event::text` / `snapshot::text` bytes for equality
against the originally written JSON, and do not assume any field ordering when querying the text form.
Compare the *parsed* JSON (key/value) instead. Example — the probe input

```
{"intish": 21.0, "expish": 1E10, "bigint": 9007199254740993, "negexp": -2.5E-3, "keys":{"b":1,"a":2}, "  ws  ":"x"}
```

is stored and read back as the canonical text

```
{"keys": {"a": 2, "b": 1}, "  ws  ": "x", "bigint": 9007199254740993, "expish": 10000000000, "intish": 21.0, "negexp": -0.0025}
```

If a future PostgreSQL upgrade changes any of these rules, `JsonbCanonicalizationIT`'s canonical-form
snapshot assertion fails — that is the regression signal to re-verify and re-document the canonical form.
