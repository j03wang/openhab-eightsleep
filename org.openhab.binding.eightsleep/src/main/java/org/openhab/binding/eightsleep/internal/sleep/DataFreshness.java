/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.eightsleep.internal.sleep;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Freshness of cached polled data relative to its own poll cadence.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class DataFreshness {

    private DataFreshness() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Cached data is stale when older than four poll intervals (but never less than
     * 60 s): polls keep failing and the cached values can no longer be trusted as
     * current. A null timestamp (no successful poll ever) counts as stale.
     */
    public static boolean isStale(@Nullable Instant lastUpdated, Instant now, long userIntervalSeconds) {
        long thresholdSeconds = Math.max(60L, 4 * userIntervalSeconds);
        return lastUpdated == null || lastUpdated.plusSeconds(thresholdSeconds).isBefore(now);
    }
}
