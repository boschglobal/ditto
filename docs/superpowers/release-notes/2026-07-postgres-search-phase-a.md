# Release-note stub — thing-search pluggable-persistence, Phase A (SPI extraction)

Status: **MIGRATED (Phase H, Task H1)** into the documentation release-notes area as
`documentation/src/main/resources/pages/ditto/release_notes_postgres_search.md` (a `published: false` pending
snippet — the next Ditto version number is still undecided, latest cut is `release_notes_390.md` / Ditto 3.9.0,
so the content is staged next to the versioned release-notes pages rather than in a versioned file). The migrated
page ALSO folds in the Phase B–H operational topics (three-JAR deployment matrix, `pg_trgm` prerequisite,
re-index cutover story, `like`/`ilike` performance limitation, Mongo-only knobs ignored-with-WARN). When the
release version is cut, fold that page's sections into the versioned `release_notes_<version>.md` and drop or
publish the snippet. This file is retained as the original Phase-A record.

This stub lists exactly the API-surface / behavior changes introduced by Phase A of the
things-search-on-PostgreSQL effort (branch `feat/postgres-persistance-search`), for a reviewer or Phase-H
author to fold into the next `release_notes_<version>.md`.

## Breaking change — `SearchUpdateMapper` re-typed to the neutral write model

`SearchUpdateMapper` (the `search-update-mapper` extension point) no longer works on the MongoDB-specific
`MongoWriteModel`. It is now typed against the **backend-neutral**
`org.eclipse.ditto.thingsearch.persistence.api.model.AbstractWriteModel`. This change is **binary- and
source-breaking** for custom implementations, but it is **japicmp-invisible**: `ditto-thingsearch-service` is
not a japicmp-gated module (services are not binary-compatibility-checked), so no build-breaking japicmp
diff will surface it — this note is the only record of the break for downstream custom-mapper authors.

New signature (verbatim, from `thingsearch/service/.../persistence/write/streaming/SearchUpdateMapper.java`):

```java
public abstract class SearchUpdateMapper implements DittoExtensionPoint {
    protected final ActorSystem actorSystem;
    protected SearchUpdateMapper(final ActorSystem actorSystem, final Config config);
    public abstract Source<org.eclipse.ditto.thingsearch.persistence.api.model.AbstractWriteModel, NotUsed>
        processWriteModel(org.eclipse.ditto.thingsearch.persistence.api.model.AbstractWriteModel writeModel,
                          org.eclipse.ditto.thingsearch.persistence.api.model.AbstractWriteModel lastWriteModel);
    public static SearchUpdateMapper get(final ActorSystem actorSystem, final Config config);
}
```

Removed from the old (Mongo-typed) API: the `protected final int maxWireVersion` field, the
`SearchUpdateMapper(ActorSystem, Integer)` constructor, the private `getMaxWireVersion(ActorSystem)` helper
(which probed `MongoClientExtension.get(...).getUpdaterClient().getMaxWireVersion()`), and the
`protected Source<MongoWriteModel, NotUsed> toIncrementalMongo(...)` helper. The abstract method's element
type changed from `MongoWriteModel` to the neutral `AbstractWriteModel`. A custom mapper is now simply a
neutral write-model → write-model transform; the bundled default
(`DefaultSearchUpdateMapper.processWriteModel(...)`) is the identity function
(`return Source.single(writeModel);`).

**Migration for custom `search-update-mapper` implementations:** re-type the override to the neutral
`AbstractWriteModel`; any Mongo-specific manipulation (BSON diffing, wire-version probing) must move behind
the (currently Mongo-only) `SearchUpdaterFlow` seam — it is no longer available at the mapper extension
point.

## Documented (non-breaking in practice) — `SearchUpdateObserver`

`SearchUpdateObserver` (the `search-update-observer` extension point) was flagged in the pluggable-persistence
plan as a breaking change, but its public surface was **already backend-neutral** before this refactor and
required **no code change**:

```java
public interface SearchUpdateObserver extends DittoExtensionPoint {
    void process(final Metadata metadata, @Nullable final JsonObject thingJson);
    static SearchUpdateObserver get(final ActorSystem actorSystem, final Config config);
}
```

(`Metadata` here is `org.eclipse.ditto.thingsearch.persistence.api.model.Metadata`.) No migration needed for
custom observers.

## New extension point — `ditto.extensions.search-persistence-provider`

Thing-search's persistence backend is now pluggable via a new `DittoExtensionPoint`,
`SearchPersistenceProvider` (`org.eclipse.ditto.thingsearch.persistence.api`), configured at:

```hocon
ditto.extensions.search-persistence-provider = org.eclipse.ditto.thingsearch.service.persistence.MongoSearchPersistenceProvider
```

in `thingsearch/service/src/main/resources/search.conf`. The bundled default
(`MongoSearchPersistenceProvider`) is unchanged behavior — MongoDB remains the out-of-the-box thing-search
backend. A future PostgreSQL search extension JAR swaps this FQCN to its own provider without any other
change to `search.conf` (the existing `include "search-extension.conf"` /
`include file("/opt/ditto/search-extension.conf")` drop-in seam is the intended override point). No action
required for existing deployments.

## Non-breaking addition — `PredicateVisitor.visitLikeWithWildcards`

`org.eclipse.ditto.rql.query.criteria.visitors.PredicateVisitor<T>` gained new **default** methods giving raw
wildcard access to `like`/`ilike` predicates, for backends (e.g. a future SQL/trigram-index backend) that need
the un-translated wildcard pattern rather than the Mongo-regex-translated form. Because the additions are
`default` methods on an interface, this is japicmp-safe under the repo's `METHOD_NEW_DEFAULT` /
`METHOD_ADDED_TO_INTERFACE` compatibility overrides (root `pom.xml` japicmp config) — source-compatible,
binary-compatible, no build gate tripped. No migration needed for existing `PredicateVisitor` implementers;
they inherit the default behavior automatically.
