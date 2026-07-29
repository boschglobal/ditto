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
default-privileges setting.

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

## Schema upgrades (`schema_version`)

The bootstrap guards the `schema_version` row (component `ditto-postgres-persistence`): same-version
checksum drift refuses boot; an OLDER stored version is upgraded in place (DDL is additive-only,
`CREATE … IF NOT EXISTS`); a NEWER stored version refuses boot (downgrade guard).

Because the policy is additive-only, `CREATE TABLE IF NOT EXISTS` alone cannot upgrade an *existing*
table's shape: a future schema version that adds columns to an already-created table must ship
`ALTER TABLE … ADD COLUMN IF NOT EXISTS …` statements for those columns, not just a `CREATE TABLE`
block that a same-named existing table would cause Postgres to silently skip.

Rolling upgrades: things/policies/connectivity share ONE row. As soon as the first upgraded pod
advances it, a NOT-yet-upgraded pod that RESTARTS will refuse to boot (downgrade guard) — running
pods are unaffected. Complete the roll across all three services promptly; do not pin one service to
an older image across a schema-version bump.

Recovery from a genuine same-version drift (manual DDL edits): reconcile the DDL, then
`UPDATE schema_version SET checksum = '<expected-from-the-boot-error>' WHERE component = 'ditto-postgres-persistence';`
