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
package org.openhab.binding.eightsleep.internal.model;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A controllable side of an Eight Sleep bed.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public enum BedSide {
    LEFT("left"),
    RIGHT("right"),
    SOLO("solo");

    private final String apiValue;

    BedSide(String apiValue) {
        this.apiValue = apiValue;
    }

    /**
     * Returns the side value expected by the Eight Sleep API.
     *
     * @return the lowercase API value
     */
    public String apiValue() {
        return apiValue;
    }

    /**
     * Parses an API or configuration side value.
     *
     * @param value the value to parse
     * @return the matching side, or {@code null} for an absent or unsupported value
     */
    public static @Nullable BedSide fromString(@Nullable String value) {
        if (value != null) {
            for (BedSide side : values()) {
                if (side.apiValue.equalsIgnoreCase(value)) {
                    return side;
                }
            }
        }
        return null;
    }
}
