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
 * Configuration-parsing helpers shared by the account handler and its tests.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class AccountConfigParser {

    private AccountConfigParser() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Resolves the temperature unit from a configuration string ("C"/"F", any case,
     * tolerating whitespace). Returns {@code fallback} for blank/unknown values.
     */
    public static char parseTemperatureUnit(@Nullable String unit, char fallback) {
        if (unit != null && !unit.isBlank()) {
            char first = Character.toLowerCase(unit.trim().charAt(0));
            if (first == 'c' || first == 'f') {
                return first;
            }
        }
        return fallback;
    }

    /** Clamps a configured interval into its allowed bounds. */
    public static long clampInterval(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Nulls out blank configuration strings. */
    public static @Nullable String emptyToNull(@Nullable String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
