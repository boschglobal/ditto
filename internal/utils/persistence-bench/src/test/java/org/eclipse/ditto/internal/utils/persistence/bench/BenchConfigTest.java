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
package org.eclipse.ditto.internal.utils.persistence.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.Test;

public final class BenchConfigTest {

    @Test
    public void retentionUsesTheServiceConfigDurationUnits() {
        assertThat(BenchConfig.parseRetention("3d")).isEqualTo(Duration.ofDays(3));
        assertThat(BenchConfig.parseRetention("30d")).isEqualTo(Duration.ofDays(30));
        assertThat(BenchConfig.parseRetention("0d")).isEqualTo(Duration.ZERO);
        assertThat(BenchConfig.parseRetention("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(BenchConfig.parseRetention("90m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(BenchConfig.parseRetention("45s")).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    public void malformedRetentionIsRejected() {
        for (final String bad : new String[]{"", "3", "d", "-1d", "3 d", "3w", "1.5d"}) {
            assertThatThrownBy(() -> BenchConfig.parseRetention(bad))
                    .as("'%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void theDefaultFreshBaseIsThePreviousUtcMidnight() {
        assertThat(BenchConfig.defaultFreshBaseMillis(Instant.parse("2026-10-02T09:15:30Z")))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
        assertThat(BenchConfig.defaultFreshBaseMillis(Instant.parse("2026-10-02T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
    }

    @Test
    public void aFreshWindowThatReachesIntoTheFutureIsRejected() {
        final Instant now = Instant.parse("2026-10-02T09:15:30Z");
        final long dayAgo = now.minus(Duration.ofDays(1)).toEpochMilli();
        final long hourAgo = now.minus(Duration.ofHours(1)).toEpochMilli();

        BenchConfig.checkFreshWindow(new CorpusGenerator(42L, 10L, 0.3, dayAgo), now);
        // the deepest fresh pid would write rows up to DEPTH_CAP seconds (~13.9 h) after the base
        assertThatThrownBy(() -> BenchConfig.checkFreshWindow(new CorpusGenerator(42L, 10L, 0.3, hourAgo), now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("future");
        // without fresh pids the base is unused
        BenchConfig.checkFreshWindow(new CorpusGenerator(42L, 10L, 0.0, hourAgo), now);
    }

    @Test
    public void reloptionOverridesMustBeWrittenLikeTheSchemaDdl() {
        assertThat(BenchConfig.checkReloptions("")).isEmpty();
        assertThat(BenchConfig.checkReloptions(
                "autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_limit = 800"))
                .isEqualTo("autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_limit = 800");
        assertThatThrownBy(() -> BenchConfig.checkReloptions("autovacuum_vacuum_cost_limit=800"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchConfig.checkReloptions(
                "autovacuum_vacuum_cost_limit = 800); DROP TABLE things_journal; --"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void labelsAndProbeSuffixesAcceptLowerCaseLettersDigitsAndDashesOnly() {
        assertThat(BenchConfig.checkLabel("probe suffix", "-r2")).isEqualTo("-r2");
        assertThat(BenchConfig.checkLabel("probe suffix", "")).isEmpty();
        assertThatThrownBy(() -> BenchConfig.checkLabel("probe suffix", "R2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("probe suffix");
        assertThatThrownBy(() -> BenchConfig.checkLabel("evidence label", "a b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence label");
    }

    @Test
    public void pingActorSettingsMustBePositive() {
        assertThat(BenchConfig.checkPositive("ping actor rate", 1_000L)).isEqualTo(1_000L);
        assertThatThrownBy(() -> BenchConfig.checkPositive("ping actor interval", 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ping actor interval");
    }
}
