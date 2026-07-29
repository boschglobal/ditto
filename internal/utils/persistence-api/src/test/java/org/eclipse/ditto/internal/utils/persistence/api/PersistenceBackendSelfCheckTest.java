/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.internal.utils.persistence.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.eclipse.ditto.internal.utils.config.DittoConfigError;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for the boot-time active-backend self-check {@link PersistenceBackendSelfCheck} — layer 2 of the
 * pluggable-persistence switchability proof. It catches the classic misconfiguration "provider = Postgres but HOCON
 * still wires the Mongo plugins" (and vice versa) by asserting that the resolved journal/snapshot plugin {@code class}
 * entries and the read-journal class belong to the selected provider's declared {@link BackendFamily}.
 * <p>
 * The check is exercised entirely with synthetic configs and stub providers — no actor system and no database — so the
 * family-match logic is pinned independently of any backend module.
 * <p>
 * The {@code class} strings used here are the real FQCNs as they appear in the shipped HOCON {@code class =} entries, so
 * the test doubles as a regression guard that the family prefixes the check compares against match production.
 */
public final class PersistenceBackendSelfCheckTest {

    // Real plugin-class FQCNs as they appear in the shipped HOCON `class =` entries.
    private static final String MONGO_JOURNAL_CLASS = "pekko.contrib.persistence.mongodb.MongoJournal";
    private static final String MONGO_SNAPSHOT_CLASS = "pekko.contrib.persistence.mongodb.MongoSnapshots";
    private static final String MONGO_READ_JOURNAL_CLASS =
            "org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal";

    private static final String POSTGRES_JOURNAL_CLASS =
            "org.eclipse.ditto.internal.utils.persistence.postgres.journal.PostgresJournal";
    private static final String POSTGRES_SNAPSHOT_CLASS =
            "org.eclipse.ditto.internal.utils.persistence.postgres.snapshot.PostgresSnapshotStore";
    private static final String POSTGRES_READ_JOURNAL_CLASS =
            "org.eclipse.ditto.internal.utils.persistence.postgres.readjournal.PostgresReadJournal";

    private static final String MONGO_JOURNAL_ID = "pekko-contrib-mongodb-persistence-things-journal";
    private static final String MONGO_SNAPSHOT_ID = "pekko-contrib-mongodb-persistence-things-snapshots";
    private static final String POSTGRES_JOURNAL_ID = "ditto-postgres-things-journal";
    private static final String POSTGRES_SNAPSHOT_ID = "ditto-postgres-things-snapshots";

    @Test
    public void mongoProviderWithMongoPluginClassesAndMongoReadJournalPasses() {
        final Config config = configWith(
                MONGO_JOURNAL_ID, MONGO_JOURNAL_CLASS,
                MONGO_SNAPSHOT_ID, MONGO_SNAPSHOT_CLASS);
        final PersistenceBackendProvider provider = mongoProvider();

        assertThatCode(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", MONGO_READ_JOURNAL_CLASS))
                .doesNotThrowAnyException();
    }

    @Test
    public void postgresProviderWithPostgresPluginClassesAndPostgresReadJournalPasses() {
        final Config config = configWith(
                POSTGRES_JOURNAL_ID, POSTGRES_JOURNAL_CLASS,
                POSTGRES_SNAPSHOT_ID, POSTGRES_SNAPSHOT_CLASS);
        final PersistenceBackendProvider provider = postgresProvider();

        assertThatCode(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", POSTGRES_READ_JOURNAL_CLASS))
                .doesNotThrowAnyException();
    }

    @Test
    public void mongoProviderWithPostgresJournalPluginClassThrowsNamingPluginIdAndActualVsExpected() {
        // Provider selected = MONGODB, but the journal plugin's `class =` is a Postgres class (mis-wired HOCON).
        final Config config = configWith(
                MONGO_JOURNAL_ID, POSTGRES_JOURNAL_CLASS,
                MONGO_SNAPSHOT_ID, MONGO_SNAPSHOT_CLASS);
        final PersistenceBackendProvider provider = mongoProvider();

        assertThatThrownBy(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", MONGO_READ_JOURNAL_CLASS))
                .isInstanceOf(DittoConfigError.class)
                .hasMessageContaining("MONGODB")
                .hasMessageContaining(MONGO_JOURNAL_ID)
                .hasMessageContaining(POSTGRES_JOURNAL_CLASS);
    }

    @Test
    public void postgresProviderWithMongoJournalPluginClassThrowsNamingPluginIdAndActualVsExpected() {
        // Provider selected = POSTGRESQL, but the journal plugin's `class =` is a Mongo class (mis-wired HOCON).
        final Config config = configWith(
                POSTGRES_JOURNAL_ID, MONGO_JOURNAL_CLASS,
                POSTGRES_SNAPSHOT_ID, POSTGRES_SNAPSHOT_CLASS);
        final PersistenceBackendProvider provider = postgresProvider();

        assertThatThrownBy(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", POSTGRES_READ_JOURNAL_CLASS))
                .isInstanceOf(DittoConfigError.class)
                .hasMessageContaining("POSTGRESQL")
                .hasMessageContaining(POSTGRES_JOURNAL_ID)
                .hasMessageContaining(MONGO_JOURNAL_CLASS);
    }

    @Test
    public void mongoProviderWithPostgresReadJournalThrowsNamingReadJournalAndActualVsExpected() {
        // Plugin classes are correct, but the resolved read-journal class is the wrong family.
        final Config config = configWith(
                MONGO_JOURNAL_ID, MONGO_JOURNAL_CLASS,
                MONGO_SNAPSHOT_ID, MONGO_SNAPSHOT_CLASS);
        final PersistenceBackendProvider provider = mongoProvider();

        assertThatThrownBy(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", POSTGRES_READ_JOURNAL_CLASS))
                .isInstanceOf(DittoConfigError.class)
                .hasMessageContaining("MONGODB")
                .hasMessageContaining("read journal")
                .hasMessageContaining(POSTGRES_READ_JOURNAL_CLASS)
                .hasMessageContaining(MONGO_READ_JOURNAL_CLASS);
    }

    @Test
    public void missingPluginClassEntryThrowsNamingPluginId() {
        // The auto-start journal plugin ID has no `<id>.class` in the effective config — a genuinely broken wiring.
        final Config config = ConfigFactory.parseString(
                "\"" + MONGO_SNAPSHOT_ID + "\" { class = \"" + MONGO_SNAPSHOT_CLASS + "\" }");
        final PersistenceBackendProvider provider = mongoProvider();

        assertThatThrownBy(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", MONGO_READ_JOURNAL_CLASS))
                .isInstanceOf(DittoConfigError.class)
                .hasMessageContaining(MONGO_JOURNAL_ID);
    }

    @Test
    public void verifyFailsWhenEffectiveAutoStartListsWireTheOtherBackend() {
        // Provider = Postgres family, and the provider-declared plugin ids + read journal are correctly
        // wired to Postgres classes (layers 1-2 pass) — but the EFFECTIVE pekko.persistence.* auto-start
        // list still auto-starts a Mongo-classed journal plugin (the exact single-include misconfiguration
        // the Task-6 profile fix addresses). Layer 3 must catch what layers 1-2 cannot see.
        final Config config = ConfigFactory.parseString(
                "pekko.persistence.journal.auto-start-journals = [ \"" + MONGO_JOURNAL_ID + "\" ]\n"
                        + "pekko.persistence.snapshot-store.auto-start-snapshot-stores = []\n"
                        + "\"" + MONGO_JOURNAL_ID + "\" { class = \"" + MONGO_JOURNAL_CLASS + "\" }\n"
                        + "\"" + POSTGRES_JOURNAL_ID + "\" { class = \"" + POSTGRES_JOURNAL_CLASS + "\" }\n"
                        + "\"" + POSTGRES_SNAPSHOT_ID + "\" { class = \"" + POSTGRES_SNAPSHOT_CLASS + "\" }");
        final PersistenceBackendProvider provider = postgresProvider();

        assertThatThrownBy(() ->
                PersistenceBackendSelfCheck.verify(config, provider, "things", POSTGRES_READ_JOURNAL_CLASS))
                .isInstanceOf(DittoConfigError.class)
                .hasMessageContaining("auto-start")
                .hasMessageContaining(MONGO_JOURNAL_ID);
    }

    // ── test helpers ─────────────────────────────────────────────────────────

    private static Config configWith(final String journalId, final String journalClass,
            final String snapshotId, final String snapshotClass) {
        return ConfigFactory.parseString(
                "\"" + journalId + "\" { class = \"" + journalClass + "\" }\n" +
                        "\"" + snapshotId + "\" { class = \"" + snapshotClass + "\" }");
    }

    private static PersistenceBackendProvider mongoProvider() {
        return new StubProvider(BackendFamily.MONGODB,
                Set.of(MONGO_JOURNAL_CLASS, MONGO_SNAPSHOT_CLASS), MONGO_READ_JOURNAL_CLASS,
                List.of(MONGO_JOURNAL_ID, MONGO_SNAPSHOT_ID));
    }

    private static PersistenceBackendProvider postgresProvider() {
        return new StubProvider(BackendFamily.POSTGRESQL,
                Set.of(POSTGRES_JOURNAL_CLASS, POSTGRES_SNAPSHOT_CLASS), POSTGRES_READ_JOURNAL_CLASS,
                List.of(POSTGRES_JOURNAL_ID, POSTGRES_SNAPSHOT_ID));
    }

    /**
     * A provider that declares its family + expected class-name STRINGS (never class literals), matching how the real
     * Mongo / Postgres providers feed the neutral self-check. {@code getReadJournal()} is never invoked by the check
     * (the resolved read-journal class name is passed in directly), so it is left unimplemented.
     */
    private static final class StubProvider implements PersistenceBackendProvider {

        private final BackendFamily family;
        private final Set<String> pluginClassNames;
        private final String readJournalClassName;
        private final List<String> autoStartIds;

        private StubProvider(final BackendFamily family, final Set<String> pluginClassNames,
                final String readJournalClassName, final List<String> autoStartIds) {
            this.family = family;
            this.pluginClassNames = pluginClassNames;
            this.readJournalClassName = readJournalClassName;
            this.autoStartIds = autoStartIds;
        }

        @Override
        public String getJournalPluginId(final String entityType) {
            return autoStartIds.get(0);
        }

        @Override
        public String getSnapshotPluginId(final String entityType) {
            return autoStartIds.get(1);
        }

        @Override
        public DittoReadJournal getReadJournal() {
            throw new UnsupportedOperationException("the self-check receives the read-journal class name directly");
        }

        @Override
        public BackendFamily backendFamily() {
            return family;
        }

        @Override
        public Set<String> expectedPluginClassNames() {
            return pluginClassNames;
        }

        @Override
        public String expectedReadJournalClassName() {
            return readJournalClassName;
        }

        @Override
        public PersistencePluginConfig pluginConfig() {
            return new PersistencePluginConfig() {
                @Override
                public String getJournalPluginId(final String entityType) {
                    return autoStartIds.get(0);
                }

                @Override
                public String getSnapshotPluginId(final String entityType) {
                    return autoStartIds.get(1);
                }

                @Override
                public List<String> getAutoStartPluginIds(final String serviceName) {
                    return autoStartIds;
                }

                @Override
                public RememberStorePluginIds getRememberStorePluginIds(final String entityType) {
                    return RememberStorePluginIds.of(autoStartIds.get(0), autoStartIds.get(1));
                }
            };
        }
    }
}
