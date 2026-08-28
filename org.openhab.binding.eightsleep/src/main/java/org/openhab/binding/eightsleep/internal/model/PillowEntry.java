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
 * One temperature-controlled device in a temperature-all response.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record PillowEntry(@Nullable String specialization, @Nullable BedSide side, @Nullable String deviceId,
        @Nullable Double currentLevel, @Nullable String stateType) {

    /**
     * Returns whether this entry represents a pillow.
     *
     * @return {@code true} for a pillow entry
     */
    public boolean isPillow() {
        return "pillow".equals(specialization);
    }

    /**
     * Returns whether this entry represents a Pod.
     *
     * @return {@code true} for a Pod entry
     */
    public boolean isPod() {
        return "pod".equals(specialization);
    }

    /**
     * Returns whether the accessory is in a state other than off.
     *
     * @return {@code true} when the accessory is on
     */
    public boolean isOn() {
        return stateType != null && !"off".equalsIgnoreCase(stateType);
    }

    /**
     * Returns the rounded heating level.
     *
     * @return the rounded level, or {@code null} when unavailable
     */
    public @Nullable Integer level() {
        return currentLevel != null ? (int) Math.round(currentLevel) : null;
    }
}
