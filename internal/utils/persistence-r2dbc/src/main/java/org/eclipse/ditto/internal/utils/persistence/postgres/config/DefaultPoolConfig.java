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

import java.time.Duration;
import java.util.Objects;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.internal.utils.config.ConfigWithFallback;
import org.eclipse.ditto.internal.utils.config.ScopedConfig;

import com.typesafe.config.Config;

/**
 * Default implementation of {@link PostgresConfig.PoolConfig}.
 */
@Immutable
public final class DefaultPoolConfig implements PostgresConfig.PoolConfig {

    static final String CONFIG_PATH = "pool";

    private final int initialSize;
    private final int maxSize;
    private final Duration maxAcquireTime;
    private final Duration maxIdleTime;
    private final Duration maxLifeTime;
    private final int fetchSize;
    private final int preparedStatementCacheQueries;
    private final DefaultDdlCredentialsConfig ddlCredentials;

    private DefaultPoolConfig(final ScopedConfig config) {
        initialSize = config.getNonNegativeIntOrThrow(PoolConfigValue.INITIAL_SIZE);
        maxSize = config.getPositiveIntOrThrow(PoolConfigValue.MAX_SIZE);
        maxAcquireTime = config.getNonNegativeDurationOrThrow(PoolConfigValue.MAX_ACQUIRE_TIME);
        maxIdleTime = config.getNonNegativeDurationOrThrow(PoolConfigValue.MAX_IDLE_TIME);
        maxLifeTime = config.getNonNegativeDurationOrThrow(PoolConfigValue.MAX_LIFE_TIME);
        fetchSize = config.getNonNegativeIntOrThrow(PoolConfigValue.FETCH_SIZE);
        preparedStatementCacheQueries =
                config.getNonNegativeIntOrThrow(PoolConfigValue.PREPARED_STATEMENT_CACHE_QUERIES);
        ddlCredentials = DefaultDdlCredentialsConfig.of(config);
    }

    /**
     * Returns an instance of {@code DefaultPoolConfig} based on the settings of the given Config.
     *
     * @param config is supposed to provide the settings at {@value #CONFIG_PATH}.
     * @return the instance.
     * @throws org.eclipse.ditto.internal.utils.config.DittoConfigError if {@code config} is invalid.
     */
    public static DefaultPoolConfig of(final Config config) {
        return new DefaultPoolConfig(
                ConfigWithFallback.newInstance(config, CONFIG_PATH, PoolConfigValue.values()));
    }

    @Override
    public int getInitialSize() {
        return initialSize;
    }

    @Override
    public int getMaxSize() {
        return maxSize;
    }

    @Override
    public Duration getMaxAcquireTime() {
        return maxAcquireTime;
    }

    @Override
    public Duration getMaxIdleTime() {
        return maxIdleTime;
    }

    @Override
    public Duration getMaxLifeTime() {
        return maxLifeTime;
    }

    @Override
    public int getFetchSize() {
        return fetchSize;
    }

    @Override
    public int getPreparedStatementCacheQueries() {
        return preparedStatementCacheQueries;
    }

    @Override
    public DefaultDdlCredentialsConfig getDdlCredentials() {
        return ddlCredentials;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DefaultPoolConfig that = (DefaultPoolConfig) o;
        return initialSize == that.initialSize &&
                maxSize == that.maxSize &&
                fetchSize == that.fetchSize &&
                preparedStatementCacheQueries == that.preparedStatementCacheQueries &&
                Objects.equals(maxAcquireTime, that.maxAcquireTime) &&
                Objects.equals(maxIdleTime, that.maxIdleTime) &&
                Objects.equals(maxLifeTime, that.maxLifeTime) &&
                Objects.equals(ddlCredentials, that.ddlCredentials);
    }

    @Override
    public int hashCode() {
        return Objects.hash(initialSize, maxSize, maxAcquireTime, maxIdleTime, maxLifeTime, fetchSize,
                preparedStatementCacheQueries, ddlCredentials);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "initialSize=" + initialSize +
                ", maxSize=" + maxSize +
                ", maxAcquireTime=" + maxAcquireTime +
                ", maxIdleTime=" + maxIdleTime +
                ", maxLifeTime=" + maxLifeTime +
                ", fetchSize=" + fetchSize +
                ", preparedStatementCacheQueries=" + preparedStatementCacheQueries +
                ", ddlCredentials=" + ddlCredentials +
                "]";
    }

}
