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

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Device level data as returned by {@code GET /v1/devices/{deviceId}}.
 * <p>
 * Mirrors the fields of the Python client's device json handling: heating levels,
 * presence, water/prime state and detected features.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DeviceData {

    // heating / cooling levels per side, range -100..100
    public @Nullable Double leftHeatingLevel;
    public @Nullable Double rightHeatingLevel;
    public @Nullable Double leftTargetHeatingLevel;
    public @Nullable Double rightTargetHeatingLevel;
    public @Nullable Boolean leftNowHeating;
    public @Nullable Boolean rightNowHeating;
    public @Nullable Integer leftHeatingDuration;
    public @Nullable Integer rightHeatingDuration;

    // device state
    public @Nullable Boolean hasWater;
    public @Nullable Boolean needsPriming;
    public @Nullable Boolean priming;
    public @Nullable String lastPrime;
    public @Nullable String modelString;
    public @Nullable String firmwareVersion;
    public @Nullable Double ledBrightnessLevel;

    public @Nullable SensorInfo sensorInfo;

    public @Nullable Map<String, Object> presenceInformation;

    public @Nullable List<String> features;

    /** Raw top-level field names of the last response, for diagnostics. */
    public volatile List<String> rawFieldNames = List.of();

    public boolean isPod() {
        return features != null && features.contains("cooling");
    }

    public boolean hasBase() {
        return features != null && features.contains("elevation");
    }

    public boolean hasSpeaker() {
        return features != null && features.contains("audio");
    }

    public static class SensorInfo {
        public @Nullable String hwRevision;
    }

    /**
     * Returns the current heating level of a side ({@code "left"} or {@code "right"}).
     */
    public @Nullable Double getHeatingLevel(String side) {
        return "right".equalsIgnoreCase(side) ? rightHeatingLevel : leftHeatingLevel;
    }

    /**
     * Returns the target heating level of a side ({@code "left"} or {@code "right"}).
     */
    public @Nullable Double getTargetHeatingLevel(String side) {
        return "right".equalsIgnoreCase(side) ? rightTargetHeatingLevel : leftTargetHeatingLevel;
    }
}
