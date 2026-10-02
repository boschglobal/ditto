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
package org.eclipse.ditto.internal.utils.persistence.postgres.client.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.utility.DockerImageName;

/**
 * Unit tests for the image selection of {@link PostgresDbResource} (no container is started): the
 * {@value PostgresDbResource#IMAGE_PROPERTY} system property chooses the image of every resource created without an
 * explicit one.
 */
public final class PostgresDbResourceTest {

    @Nullable private String originalProperty;

    @Before
    public void rememberProperty() {
        originalProperty = System.getProperty(PostgresDbResource.IMAGE_PROPERTY);
    }

    @After
    public void restoreProperty() {
        if (originalProperty == null) {
            System.clearProperty(PostgresDbResource.IMAGE_PROPERTY);
        } else {
            System.setProperty(PostgresDbResource.IMAGE_PROPERTY, originalProperty);
        }
    }

    @Test
    public void defaultsToPostgresSixteen() {
        System.clearProperty(PostgresDbResource.IMAGE_PROPERTY);

        assertThat(PostgresDbResource.IMAGE_PROPERTY).isEqualTo("ditto.test.postgres.image");
        assertThat(PostgresDbResource.DEFAULT_IMAGE).isEqualTo("postgres:16");
        assertThat(PostgresDbResource.configuredImage()).isEqualTo("postgres:16");
        assertThat(new PostgresDbResource().getImage()).isEqualTo("postgres:16");
    }

    @Test
    public void systemPropertyChoosesTheImage() {
        System.setProperty(PostgresDbResource.IMAGE_PROPERTY, "postgres:18");

        assertThat(PostgresDbResource.configuredImage()).isEqualTo("postgres:18");
        assertThat(new PostgresDbResource().getImage()).isEqualTo("postgres:18");
        assertThat(PostgresDbResource.configuredImageName().asCanonicalNameString())
                .isEqualTo("postgres:18");
    }

    @Test
    public void blankPropertyFallsBackToTheDefault() {
        System.setProperty(PostgresDbResource.IMAGE_PROPERTY, "  ");

        assertThat(PostgresDbResource.configuredImage()).isEqualTo("postgres:16");
    }

    @Test
    public void explicitImageWinsOverTheProperty() {
        System.setProperty(PostgresDbResource.IMAGE_PROPERTY, "postgres:18");

        assertThat(new PostgresDbResource("postgres:17").getImage()).isEqualTo("postgres:17");
    }

    @Test
    public void acceptsAMirroredPostgresImage() {
        System.setProperty(PostgresDbResource.IMAGE_PROPERTY, "registry.example.com/mirror/postgres:17");

        // Testcontainers' PostgreSQLContainer only accepts images declared compatible with "postgres".
        assertThat(PostgresDbResource.configuredImageName().isCompatibleWith(DockerImageName.parse("postgres")))
                .isTrue();
    }

}
