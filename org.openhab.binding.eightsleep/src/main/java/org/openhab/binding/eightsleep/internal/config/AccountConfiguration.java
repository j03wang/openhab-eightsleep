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
package org.openhab.binding.eightsleep.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Configuration for the {@code account} bridge thing.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountConfiguration {

    /** Eight Sleep account email address. */
    public String username = "";

    /** Eight Sleep account password. */
    public String password = "";

    /**
     * Optional OAuth client id override. When empty the client id of the official mobile app is used.
     */
    public String clientId = "";

    /**
     * Optional OAuth client secret override. When empty the client secret of the official mobile app is used.
     */
    public String clientSecret = "";

    /** Polling interval of the device data in seconds. */
    public int deviceRefreshInterval = 60;

    /** Polling interval of the user (sleep) data in seconds. */
    public int userRefreshInterval = 30;

    /** Polling interval of the adjustable base data in seconds. */
    public int baseRefreshInterval = 60;

    /** Temperature unit used when reporting temperatures: "C" or "F". Defaults to the openHAB locale unit. */
    public String temperatureUnit = "";

    /**
     * Eight Sleep device (pod) to bind. Empty means the first device reported by the account; accounts with
     * more than one pod should set this explicitly.
     */
    public String deviceId = "";

    /** Returns the optional client id, normalized to {@code null} when blank. */
    public @Nullable String clientIdOrNull() {
        return emptyToNull(clientId);
    }

    /** Returns the optional client secret, normalized to {@code null} when blank. */
    public @Nullable String clientSecretOrNull() {
        return emptyToNull(clientSecret);
    }

    /** Returns the device polling interval within supported bounds. */
    public long deviceRefreshIntervalSeconds() {
        return clamp(deviceRefreshInterval, 15, 600);
    }

    /** Returns the user polling interval within supported bounds. */
    public long userRefreshIntervalSeconds() {
        return clamp(userRefreshInterval, 15, 600);
    }

    /** Returns the base polling interval within supported bounds. */
    public long baseRefreshIntervalSeconds() {
        return clamp(baseRefreshInterval, 30, 900);
    }

    /** Returns the configured temperature unit, or the supplied fallback. */
    public char temperatureUnit(char fallback) {
        if (!temperatureUnit.isBlank()) {
            char first = Character.toLowerCase(temperatureUnit.trim().charAt(0));
            if (first == 'c' || first == 'f') {
                return first;
            }
        }
        return fallback;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static @Nullable String emptyToNull(@Nullable String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
