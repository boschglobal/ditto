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
package org.eclipse.ditto.internal.utils.persistence.postgres.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.assertj.core.api.JUnitSoftAssertions;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link DefaultPostgresConfig} and its nested config sections.
 */
public final class DefaultPostgresConfigTest {

    private static Config dittoConfig;

    @Rule
    public final JUnitSoftAssertions softly = new JUnitSoftAssertions();

    @BeforeClass
    public static void initTestFixture() {
        dittoConfig = ConfigFactory.load("postgres-test").getConfig("ditto");
    }

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(DefaultPostgresConfig.class).usingGetClass().verify();
        EqualsVerifier.forClass(DefaultPoolConfig.class).usingGetClass().verify();
        EqualsVerifier.forClass(DefaultSslConfig.class).usingGetClass().verify();
        EqualsVerifier.forClass(DefaultDdlCredentialsConfig.class).usingGetClass().verify();
    }

    @Test
    public void returnsDefaultValuesWhenBaseConfigIsEmpty() {
        final DefaultPostgresConfig underTest = DefaultPostgresConfig.of(ConfigFactory.empty());

        softly.assertThat(underTest.getUri())
                .as("uri")
                .isEqualTo(PostgresConfig.PostgresConfigValue.URI.getDefaultValue());
        softly.assertThat(underTest.getUsername()).as("username").isEmpty();
        softly.assertThat(underTest.getPassword()).as("password").isEmpty();
        softly.assertThat(underTest.getPoolerMode()).as("pooler-mode").isEqualTo(PoolerMode.DIRECT);
        softly.assertThat(underTest.getConnectTimeout()).as("connect-timeout").isEqualTo(Duration.ofSeconds(5));
        softly.assertThat(underTest.getStatementTimeout()).as("statement-timeout").isEqualTo(Duration.ofSeconds(60));

        final PostgresConfig.PoolConfig pool = underTest.getPoolConfig();
        softly.assertThat(pool.getInitialSize()).as("pool.initial-size").isEqualTo(0);
        softly.assertThat(pool.getMaxSize()).as("pool.max-size").isEqualTo(100);
        softly.assertThat(pool.getMaxAcquireTime()).as("pool.max-acquire-time").isEqualTo(Duration.ofSeconds(30));
        softly.assertThat(pool.getFetchSize()).as("pool.fetch-size").isEqualTo(0);
        softly.assertThat(pool.getPreparedStatementCacheQueries())
                .as("pool.prepared-statement-cache-queries").isEqualTo(0);
        softly.assertThat(pool.getDdlCredentials().isConfigured()).as("ddl configured").isFalse();

        final PostgresConfig.SslConfig ssl = underTest.getSslConfig();
        softly.assertThat(ssl.getMode()).as("ssl.mode").isEqualTo("verify-full");
        softly.assertThat(ssl.getRootCert()).as("ssl.root-cert").isEmpty();
        softly.assertThat(ssl.isAllowSystemTruststore()).as("ssl.allow-system-truststore").isFalse();
    }

    @Test
    public void returnsConfiguredValues() {
        final DefaultPostgresConfig underTest = DefaultPostgresConfig.of(dittoConfig);

        softly.assertThat(underTest.getUri()).as("uri").isEqualTo("r2dbc:postgresql://db.example:6432/ditto_test");
        softly.assertThat(underTest.getUsername()).as("username").contains("ditto_runtime");
        softly.assertThat(underTest.getPassword()).as("password").contains("runtime_secret");
        softly.assertThat(underTest.getPoolerMode()).as("pooler-mode").isEqualTo(PoolerMode.TRANSACTION);
        softly.assertThat(underTest.getConnectTimeout()).as("connect-timeout").isEqualTo(Duration.ofSeconds(7));
        softly.assertThat(underTest.getStatementTimeout()).as("statement-timeout").isEqualTo(Duration.ofSeconds(90));

        final PostgresConfig.PoolConfig pool = underTest.getPoolConfig();
        softly.assertThat(pool.getInitialSize()).as("pool.initial-size").isEqualTo(5);
        softly.assertThat(pool.getMaxSize()).as("pool.max-size").isEqualTo(200);
        softly.assertThat(pool.getMaxAcquireTime()).as("pool.max-acquire-time").isEqualTo(Duration.ofSeconds(42));
        softly.assertThat(pool.getMaxIdleTime()).as("pool.max-idle-time").isEqualTo(Duration.ofMinutes(5));
        softly.assertThat(pool.getMaxLifeTime()).as("pool.max-life-time").isEqualTo(Duration.ofMinutes(30));
        softly.assertThat(pool.getFetchSize()).as("pool.fetch-size").isEqualTo(50);
        softly.assertThat(pool.getPreparedStatementCacheQueries())
                .as("pool.prepared-statement-cache-queries").isEqualTo(256);

        final PostgresConfig.DdlCredentialsConfig ddl = pool.getDdlCredentials();
        softly.assertThat(ddl.isConfigured()).as("ddl configured").isTrue();
        softly.assertThat(ddl.getUsername()).as("ddl.username").contains("ditto_ddl");
        softly.assertThat(ddl.getPassword()).as("ddl.password").contains("ddl_secret");

        final PostgresConfig.SslConfig ssl = underTest.getSslConfig();
        softly.assertThat(ssl.getMode()).as("ssl.mode").isEqualTo("verify-ca");
        softly.assertThat(ssl.getRootCert()).as("ssl.root-cert").contains("/etc/ditto/pg-tls/ca.crt");
        softly.assertThat(ssl.getCert()).as("ssl.cert").contains("/etc/ditto/pg-tls/client.crt");
        softly.assertThat(ssl.getKey()).as("ssl.key").contains("/etc/ditto/pg-tls/client.key");
        softly.assertThat(ssl.getKeyPassword()).as("ssl.key-password").contains("key_secret");
        softly.assertThat(ssl.isAllowSystemTruststore()).as("ssl.allow-system-truststore").isFalse();
    }

    @Test
    public void poolerModeFromConfigValueIsCaseInsensitive() {
        assertThat(PoolerMode.fromConfigValue("TRANSACTION")).contains(PoolerMode.TRANSACTION);
        assertThat(PoolerMode.fromConfigValue(" Session ")).contains(PoolerMode.SESSION);
        assertThat(PoolerMode.fromConfigValue("nonsense")).isEmpty();
        assertThat(PoolerMode.fromConfigValue(null)).isEmpty();
    }

    @Test
    public void toStringDoesNotLeakSecrets() {
        final DefaultPostgresConfig underTest = DefaultPostgresConfig.of(dittoConfig);
        final String rendered = underTest.toString();
        softly.assertThat(rendered).as("no runtime password").doesNotContain("runtime_secret");
        softly.assertThat(rendered).as("no ddl password").doesNotContain("ddl_secret");
        softly.assertThat(rendered).as("no key password").doesNotContain("key_secret");
    }

}
