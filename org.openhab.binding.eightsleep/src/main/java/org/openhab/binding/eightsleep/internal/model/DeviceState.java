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

import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Current state and capabilities of an Eight Sleep device.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record DeviceState(@Nullable Double leftHeatingLevel, @Nullable Double rightHeatingLevel,
        @Nullable Double leftTargetHeatingLevel, @Nullable Double rightTargetHeatingLevel,
        @Nullable Boolean leftNowHeating, @Nullable Boolean rightNowHeating, @Nullable Integer leftHeatingDuration,
        @Nullable Integer rightHeatingDuration, @Nullable Boolean hasWater, @Nullable Boolean needsPriming,
        @Nullable Boolean priming, @Nullable Instant lastPrime, @Nullable Double ledBrightnessLevel,
        List<String> features) {

    public static final DeviceState EMPTY = new DeviceState(null, null, null, null, null, null, null, null, null, null,
            null, null, null, List.of());

    public DeviceState {
        features = List.copyOf(features);
    }

    /**
     * Returns whether the device supports active cooling.
     *
     * @return {@code true} for a Pod
     */
    public boolean isPod() {
        return features.contains("cooling");
    }

    /**
     * Returns whether the device includes an adjustable base.
     *
     * @return {@code true} when elevation is supported
     */
    public boolean hasBase() {
        return features.contains("elevation");
    }

    /**
     * Returns whether the device includes a speaker.
     *
     * @return {@code true} when audio is supported
     */
    public boolean hasSpeaker() {
        return features.contains("audio");
    }

    /**
     * Returns the current heating level for a side.
     *
     * @param side the logical bed side
     * @return the current level, or {@code null} when unavailable
     */
    public @Nullable Double heatingLevel(BedSide side) {
        return side == BedSide.RIGHT ? rightHeatingLevel : leftHeatingLevel;
    }

    /**
     * Returns the target heating level for a side.
     *
     * @param side the logical bed side
     * @return the target level, or {@code null} when unavailable
     */
    public @Nullable Double targetHeatingLevel(BedSide side) {
        return side == BedSide.RIGHT ? rightTargetHeatingLevel : leftTargetHeatingLevel;
    }

    /**
     * Returns whether a side is actively heating or cooling.
     *
     * @param side the logical bed side
     * @return the active state, or {@code null} when unavailable
     */
    public @Nullable Boolean nowHeating(BedSide side) {
        return side == BedSide.RIGHT ? rightNowHeating : leftNowHeating;
    }

    /**
     * Returns the remaining heating duration for a side.
     *
     * @param side the logical bed side
     * @return the duration in seconds, or {@code null} when unavailable
     */
    public @Nullable Integer heatingDuration(BedSide side) {
        return side == BedSide.RIGHT ? rightHeatingDuration : leftHeatingDuration;
    }
}
