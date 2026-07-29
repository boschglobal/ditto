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
package org.eclipse.ditto.internal.utils.persistence.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Props;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.config.ScopedConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit test for the B3 task: {@link MongoPersistenceBackendProvider#healthCheck()} overrides the A1 default
 * and returns {@link MongoHealthChecker#props()} (a non-null {@link Props} whose actor class is
 * {@link MongoHealthChecker}).
 * <p>
 * The A1 default-UOE contract (interface-level) is covered in
 * {@code PersistenceBackendProviderApiSurfaceTest#healthCheckDefaultThrowsUnsupported()} and is intentionally
 * NOT duplicated here — the two tests form a complementary pair.
 */
public final class MongoPersistenceBackendProviderHealthCheckTest {

    private static ActorSystem system;
    private static MongoPersistenceBackendProvider provider;

    @BeforeClass
    public static void setUp() {
        // Plugin-ID resolution is a pure config read; the (lazy) MongoReadJournal is never built here.
        system = ActorSystem.create("MongoPersistenceBackendProviderHealthCheckTest",
                ConfigFactory.load("reference"));
        final Config referenceExtensionConfig =
                ScopedConfig.dittoExtension(ConfigFactory.load("reference"))
                        .getConfig("persistence-backend-provider.extension-config");
        provider = new MongoPersistenceBackendProvider(system, referenceExtensionConfig);
    }

    @AfterClass
    public static void tearDown() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
            system = null;
        }
    }

    /**
     * B3: {@code healthCheck()} must return a non-null {@link Props} wrapping {@link MongoHealthChecker}.
     * Prior to B3 the inherited A1 default threw {@link UnsupportedOperationException}; after B3 it must
     * return the Props that every Ditto service's RootActor already uses for Mongo health monitoring.
     */
    @Test
    public void healthCheckReturnsNonNullMongoHealthCheckerProps() {
        final Props props = provider.healthCheck();

        assertThat(props)
                .as("healthCheck() must not return null (B3)")
                .isNotNull();
        assertThat(props.actorClass())
                .as("healthCheck() must wire MongoHealthChecker, not some other actor (B3)")
                .isEqualTo(MongoHealthChecker.class);
    }
}
