/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.gateway.service.util.config.streaming;

import org.eclipse.ditto.base.service.config.ThrottlingConfig;
import org.eclipse.ditto.internal.utils.config.KnownConfigValue;

/**
 * Provides configuration settings of SSE.
 */
public interface SseConfig {

    /**
     * Config path relative to its parent.
     */
    String CONFIG_PATH = "sse";

    /**
     * Returns the throttling config for SSE.
     *
     * @return the throttling config.
     */
    ThrottlingConfig getThrottlingConfig();

    /**
     * Returns the max buffer size of how many outstanding events a single SSE client can have.
     * When the buffer is full, further events wait for buffer space, see {@link #getPublisherMaxPendingOffers()}.
     *
     * @return the buffer size.
     */
    int getPublisherBackpressureBufferSize();

    /**
     * Returns how many events may wait for space in the publisher buffer of a single SSE client.
     * Additional events are dropped if this number is reached.
     *
     * @return the maximum number of pending offers.
     */
    int getPublisherMaxPendingOffers();

    enum SseConfigValue implements KnownConfigValue {

        /**
         * The max buffer size of how many outstanding events a single SSE client can have.
         */
        PUBLISHER_BACKPRESSURE_BUFFER_SIZE("publisher.backpressure-buffer-size", 100),

        /**
         * How many events may wait for space in the publisher buffer of a single SSE client before further ones are
         * dropped.
         */
        PUBLISHER_MAX_PENDING_OFFERS("publisher.max-pending-offers", 1000);

        private final String path;
        private final Object defaultValue;

        SseConfigValue(final String thePath, final Object theDefaultValue) {
            path = thePath;
            defaultValue = theDefaultValue;
        }

        @Override
        public Object getDefaultValue() {
            return defaultValue;
        }

        @Override
        public String getConfigPath() {
            return path;
        }

    }


}
