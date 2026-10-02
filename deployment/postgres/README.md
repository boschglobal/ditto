<!--
  Copyright (c) 2026 Contributors to the Eclipse Foundation

  See the NOTICE file(s) distributed with this work for additional
  information regarding copyright ownership.

  This program and the accompanying materials are made available under the
  terms of the Eclipse Public License 2.0 which is available at
  http://www.eclipse.org/legal/epl-2.0

  SPDX-License-Identifier: EPL-2.0
-->
# Ditto PostgreSQL persistence backend — ops runbook

Operational artifacts for the phase-2 PostgreSQL persistence backend (custom
Pekko plugin on the r2dbc driver). For the design see
`docs/superpowers/specs/postgres-persistence-backend.md` — §7 (HOCON config),
§11 (role privileges + schema). `thingsearch` stays on MongoDB; only `policies`,
`things` and `connectivity` use this backend.

Contents:

- `provisioning/01-roles-and-grants.sql` — two-role provisioning (run once as
  superuser / db owner).
- Helm TLS wiring: `postgres.tls.*` in `deployment/helm/ditto/values.yaml`,
  rendered by `templates/postgres-tls-secret.yaml` and mounted via the
  `ditto.postgres.tls.{volume,volumeMount}` helpers.

## 1. Provisioning order (MUST be followed in this sequence)

The order is load-bearing because PostgreSQL `ALTER DEFAULT PRIVILEGES` applies
**only to objects created after it runs** — it never back-fills existing tables.

1. **Create roles + set default privileges (as superuser / db owner).**

   ```bash
   psql -h <pg-host> -U postgres -d ditto \
        -v ddl_pw="$DITTO_DDL_PASSWORD" \
        -v app_pw="$DITTO_APP_PASSWORD" \
        -f provisioning/01-roles-and-grants.sql
   ```

   This creates `ditto_ddl` (schema owner, CREATE) and `ditto_app` (DML-only),
   `CREATE SCHEMA ditto AUTHORIZATION ditto_ddl`, revokes CREATE from `ditto_app`
   and `PUBLIC`, and sets `ALTER DEFAULT PRIVILEGES FOR ROLE ditto_ddl … GRANT
   SELECT, INSERT, UPDATE, DELETE ON TABLES TO ditto_app`. The default-privileges
   statement **must** be set here, before the app boots — see comment in the SQL.
   It also sets the role defaults `search_path = ditto` (both roles) and
   `statement_timeout = '60s'` (`ditto_app` only, see
   [Statement timeout](#statement-timeout-role-default-and-pgbouncer)).

2. **App boots → schema manager runs bootstrap DDL as `ditto_ddl`.**
   On first boot `PostgresSchemaManager` connects with the `ddl-credentials`
   (DDL role) and runs all `CREATE TABLE`/`CREATE INDEX` inside one
   `pg_advisory_xact_lock`'d transaction `[H2]`. Because step 1 already set the
   default privileges, every new table is born with the DML grant to `ditto_app`
   — no post-boot re-grant pass is needed.

3. **Runtime traffic uses `ditto_app`.** The hot-path `uri` (`${POSTGRES_URI}`)
   authenticates as `ditto_app`, which has DML only — it cannot run DDL.

Notes on the role model (all intentional, see SQL comments):

- **No sequence grant.** PKs use `GENERATED ALWAYS AS IDENTITY`, whose INSERT
  needs no `USAGE ON SEQUENCE`. Do **not** use `SERIAL`/`nextval()` (which would
  require a sequence grant).
- **No TRUNCATE grant.** Cleanup deletes rows with `DELETE`, never `TRUNCATE`.

Re-provisioning an existing schema: skip `CREATE ROLE`/`CREATE SCHEMA` (they
already exist) and run the GRANT/REVOKE/ALTER block; the
`GRANT … ON ALL TABLES IN SCHEMA ditto` line covers tables that predate the
default-privileges setting. That block also sets the `statement_timeout` default
of `ditto_app` back to `60s`.

### Statement timeout: role default and PgBouncer

The script gives the runtime role a server-side statement timeout, the same value
as the `ditto.postgresql.statement-timeout` default:

```sql
ALTER ROLE ditto_app SET statement_timeout = '60s';
```

Ditto also sends `ditto.postgresql.statement-timeout` (`POSTGRES_STATEMENT_TIMEOUT`)
as a connection startup parameter. Which of the two a statement runs under depends
on the path to PostgreSQL:

| Path | Timeout in effect |
|---|---|
| Direct connection | the startup parameter; it overrides the role default |
| PgBouncer with `track_extra_parameters = statement_timeout` and `ignore_startup_parameters = extra_float_digits` | the startup parameter: PgBouncer applies each client's value to the server connection it hands out |
| PgBouncer with `ignore_startup_parameters = extra_float_digits,statement_timeout` | the role default only: PgBouncer drops the startup parameter |
| PgBouncer with `statement_timeout` in neither | n/a: PgBouncer refuses the connection (`unsupported startup parameter: statement_timeout`) |

- The PgBouncer rows hold for session and transaction pooling alike. They were
  verified with PgBouncer 1.26.0; `PgBouncerStatementTimeoutIT` covers the
  `ignore_startup_parameters` case in transaction mode. PostgreSQL does not
  report `statement_timeout` to clients, and how PgBouncer tracks such a
  parameter may differ between versions: check the `track_extra_parameters` row
  on your version.
- The driver always sends `extra_float_digits` as well, which PgBouncer refuses
  in the same way unless it is listed; hence `extra_float_digits` in
  `ignore_startup_parameters` in both setups.
- **`statement-timeout = 0` does not mean "no timeout".** It sends no startup
  parameter, so the role default (60 s) applies on every path. To lift the cap:
  on a direct connection, or behind a PgBouncer with `track_extra_parameters`,
  set a large `statement-timeout`, which overrides the role default (Ditto
  cannot send an explicit `0`). Behind a PgBouncer with
  `ignore_startup_parameters` only the role default counts: run
  `ALTER ROLE ditto_app SET statement_timeout = 0` (or `… RESET
  statement_timeout`), then `RECONNECT` PgBouncer. With `statement-timeout = 0`
  that role change lifts the cap on every path.

Before this role default existed, a PgBouncer with `ignore_startup_parameters`
ran Ditto's statements without any timeout. With it, statements that run longer
than 60 s are cancelled there too (SQLSTATE `57014`), as on a direct connection.
That includes streaming reads that are consumed slowly, because the timeout
counts across the whole stream, not per fetch. The policies service's
`always-alive` persistence ping is such a read: it streams the tagged policy
ids in fetches of 256 and pings about one per second, so with more than 256
always-alive policies its stream is cancelled after about the first 256 ids,
and the ping then stops until the service restarts. On a direct connection this
already happens; a follow-up change pages the ping.

- **PgBouncer must log in to PostgreSQL as the client's role.** A role default
  applies only to server sessions opened *as that role*. A `[databases]` entry
  with a forced `user=` makes PgBouncer open every server connection as that
  user, and the default of `ditto_app` never applies. Use an entry without
  `user=`, e.g. `ditto = host=<pg-host> port=5432 dbname=ditto`, and let
  PgBouncer authenticate clients via `auth_file` or `auth_query`. An `auth_user=`
  in the entry (some images generate one) is fine: it only names the role that
  runs the auth query.
- **A changed value applies to new server sessions only.** PostgreSQL reads role
  defaults when a session starts. After `ALTER ROLE ditto_app SET
  statement_timeout = …`, open sessions keep the old value until they reconnect;
  behind PgBouncer, run `RECONNECT` on its admin console.
- **The DDL role deliberately gets no timeout default.** The schema bootstrap and
  `CREATE INDEX CONCURRENTLY` builds (per-path search indexes) run as the DDL role
  and must not be cut off by a role-level timeout. The bootstrap is still bounded
  by `ditto.postgresql.statement-timeout` wherever that startup parameter reaches
  the server; the per-path index builds set `statement_timeout = 0` for their own
  session.
- **Without a separate DDL role the default also applies to DDL.** When
  `ddl-credentials` are empty (`POSTGRES_DDL_USER` unset), Ditto runs the schema
  bootstrap as the runtime role, so the runtime role's timeout default applies to
  it as well, also behind a PgBouncer with `ignore_startup_parameters`, where the
  bootstrap would otherwise run without a timeout. A schema upgrade that builds an
  index on a large table can then be cancelled. Configure a separate DDL role
  (`ditto_ddl` from this script) via `POSTGRES_DDL_USER`/`POSTGRES_DDL_PASSWORD`.
  Otherwise raise the timeout for the boot that runs such DDL and restore it
  afterwards: the role default behind a PgBouncer with
  `ignore_startup_parameters`, `ditto.postgresql.statement-timeout` elsewhere.
  Per-path search index builds are not affected either way.

## 2. TLS / certificate mount

The r2dbc-postgresql client runs `sslMode=verify-full` (validates the server
cert chain **and** hostname). The Helm chart mounts the CA (and optional mTLS
material) read-only into the persistence pods. File names map 1:1 to the
persistence-backend `ssl.*` HOCON keys (spec §7) that `DittoPostgresClient`
(WU4) reads:

| Mounted file (`postgres.tls.mountPath`) | HOCON key       | r2dbc Option   |
|-----------------------------------------|-----------------|----------------|
| `/etc/ditto/pg-tls/ca.crt`              | `ssl.root-cert` | `sslRootCert`  |
| `/etc/ditto/pg-tls/tls.crt` (mTLS)      | `ssl.cert`      | `sslCert`      |
| `/etc/ditto/pg-tls/tls.key` (PKCS#8)    | `ssl.key`       | `sslKey`       |

Set `POSTGRES_SSL_ROOT_CERT=/etc/ditto/pg-tls/ca.crt` (and the cert/key env vars
for mTLS) so the HOCON `ssl.root-cert`/`ssl.cert`/`ssl.key` resolve to the mount.
Keep `ssl.allow-system-truststore = false` (default) so a `verify-full`/`verify-ca`
config with an unset `root-cert` fails fast at boot instead of silently falling
back to the JVM truststore and failing at first connect `[G5]`.

If PgBouncer terminates TLS, mount the PgBouncer CA here (the `verify-full`
checks are against whichever endpoint the r2dbc URI hits).

Enable via Helm values:

```yaml
postgres:
  tls:
    enabled: true
    mountPath: /etc/ditto/pg-tls
    caCert: |
      -----BEGIN CERTIFICATE-----
      ...PG / PgBouncer CA...
      -----END CERTIFICATE-----
    # or reference a pre-created Secret (keys: ca.crt[, tls.crt, tls.key]):
    # existingSecret: my-pg-ca
    mTLS:
      enabled: false        # set true + supply PKCS#8 clientKey for mutual TLS
      clientCert: ""
      clientKey: ""          # MUST be PKCS#8 (not PKCS#1)
```

The client private key **must be PKCS#8** — r2dbc-postgresql's `sslKey` does not
accept PKCS#1. Convert with:
`openssl pkcs8 -topk8 -nocrypt -in client-pkcs1.key -out tls.key`.

## 3. CA rotation procedure

r2dbc reads the certificate **once**, when the `ConnectionFactory` is built — so
a changed CA only takes effect after a **pod restart**. Rotate without downtime
using a staged multi-CA bundle:

1. **Stage:** build a PEM bundle containing **both** the current and the new CA
   (concatenate them into one file) and put it in `postgres.tls.caCert` (or the
   `existingSecret`'s `ca.crt`).
2. **Roll the persistence pods:** `kubectl rollout restart deployment/<release>-things
   <release>-policies <release>-connectivity`. After this, every pod trusts both
   CAs. (The managed Secret's `checksum` is not auto-wired into a pod annotation,
   so the restart must be explicit.)
3. **Cut over the server:** install the server certificate signed by the **new**
   CA on PostgreSQL / PgBouncer. Clients already trust it (bundle from step 1).
4. **Prune:** once all servers present new-CA certs, drop the old CA from the
   bundle and roll the persistence pods again so they no longer trust it.

Never remove the old CA from the client bundle before every server has cut over,
or in-flight pods that still present the old cert will fail `verify-full`.

## Schema upgrades

Every PostgreSQL-backed component keeps one row in `schema_version`: things, policies and
connectivity share the row `ditto-postgres-persistence`; the optional search backend has its own row
`ditto-postgres-search`. The row holds the schema `version`, the `checksum` of the DDL, and
`min_compatible_version`, the oldest code version that can still run against that schema.

The bootstrap runs in one transaction under a cluster-wide advisory lock. It reads the row **before**
it runs any DDL and decides:

| Stored row | What the booting pod does |
|---|---|
| none (first boot) | creates the schema, verifies the catalog, inserts the row |
| `version` older than the code | upgrade: runs its idempotent DDL, verifies the catalog, advances the row (including its own `min_compatible_version`) |
| `version` equal to the code | runs its idempotent DDL and verifies the catalog; a checksum mismatch refuses to boot (drift) |
| `version` newer, `min_compatible_version` ≤ code | **compatible newer** (rolling upgrade in progress): runs **no** DDL and does **not** write the row; only verifies the catalog facts it needs (primary keys, column types, `COLLATE "C"`; extra columns or extra/dropped indexes are fine); logs an INFO line naming both versions |
| `version` newer, `min_compatible_version` > code | refuses to boot; the message names `min_compatible_version` |

A row written before the `min_compatible_version` column existed has it `NULL`, which counts as
`min_compatible_version = version`. The first boot of an image with this check adds the column and
fills it in: the bootstrap checks the catalog first and issues
`ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS min_compatible_version INT` only while the
column is missing. That statement is not part of the checksummed DDL, so no stored checksum changes.

Privileges: this one-time `ALTER TABLE` needs **ownership** of `schema_version`. With the default
provisioning (`provisioning/01-roles-and-grants.sql`) the DDL role created the table and owns it.
If a component's DDL role does not own `schema_version`, boot a component whose role owns it first,
or add the column once as the owner. Once the column exists, boots and heals need the same
privileges on `schema_version` as before this change (`SELECT`, `INSERT`, `UPDATE`), no ownership.

The runtime self-heal (re-creating a missing table on SQLSTATE `42P01`) follows the same rule: if the
stored version is newer, it runs no DDL. With a compatible newer schema the heal is a no-op and the
failed statement is retried once. With an incompatible one the heal fails and the original error
surfaces.

### Rolling upgrades

As soon as the first upgraded pod advances the row, the not-yet-upgraded pods run against the newer
schema. Pods that keep running are unaffected either way. A not-yet-upgraded pod that **restarts**
mid-roll:

- keeps booting if the new version declares it compatible (`min_compatible_version` ≤ its version);
- refuses to boot if it does not. Then complete the roll across all services that share the row
  promptly, and don't pin one service to an older image across such a bump.

**One-time window for existing databases:** images built before `min_compatible_version` existed don't
read it. They treat any newer stored version as a downgrade, and their self-heal still runs their own
DDL. So during the first schema bump after this change, a pod still on such an older image refuses to
boot if it restarts mid-roll, the same window as before. Roll every pod to an image with this check
first (that roll needs no schema bump); later bumps then follow the table above.

### Rules for schema bumps (developers)

Any change to a component's DDL changes its checksum, so it must bump the component's `VERSION`, and
each bump declares its `MIN_COMPATIBLE_VERSION` (`PostgresSchemaDescriptor#minCompatibleVersion()`):

- **Backward-compatible changes** keep the previous `MIN_COMPATIBLE_VERSION`. Examples: an index
  drop, reloptions (e.g. autovacuum settings), a new index.
- **Changes older code can't run against** set it to the new version. Examples: a column rename or
  type change, a new `NOT NULL` column without a default, dropping a column or table older code
  still uses.
- Both versions run side by side during a roll, so a "compatible" change must also let the new code
  cope with rows the old code writes.

`CREATE TABLE IF NOT EXISTS` never changes an *existing* table's shape: a version that adds columns
to an already-created table must ship `ALTER TABLE … ADD COLUMN IF NOT EXISTS …` statements for
them, not just a changed `CREATE TABLE` block that Postgres silently skips for the existing table.

### Recovery from same-version drift

Recovery from a genuine same-version drift (manual DDL edits): reconcile the DDL, then
`UPDATE schema_version SET checksum = '<expected-from-the-boot-error>' WHERE component = 'ditto-postgres-persistence';`
(use `ditto-postgres-search` for the search row).
