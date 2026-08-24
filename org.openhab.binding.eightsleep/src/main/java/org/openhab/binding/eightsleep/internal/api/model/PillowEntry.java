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
package org.openhab.binding.eightsleep.internal.api.model;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * One temperature-controlled device entry (pod or pillow) of a /temperature/all payload.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class PillowEntry {
    public @Nullable DeviceInfo device;
    public @Nullable Double currentLevel;
    public @Nullable CurrentState currentState;

    public boolean isPillow() {
        return device != null && "pillow".equals(device.specialization);
    }

    public boolean isPod() {
        return device != null && "pod".equals(device.specialization);
    }

    public @Nullable String getSide() {
        return device != null ? device.side : null;
    }

    public @Nullable String getDeviceId() {
        return device != null ? device.deviceId : null;
    }

    public boolean isOn() {
        return currentState != null && currentState.type != null && !"off".equalsIgnoreCase(currentState.type);
    }

    /**
     * The reported heating level rounded to an integer percent; {@code null} when the
     * payload carried no {@code currentLevel}, so absence is not read as level zero.
     */
    public @Nullable Integer getLevel() {
        return currentLevel != null ? Integer.valueOf((int) Math.round(currentLevel.doubleValue())) : null;
    }

    public static class DeviceInfo {
        public @Nullable String specialization;
        public @Nullable String side;
        public @Nullable String deviceId;
    }

    public static class CurrentState {
        public @Nullable String type;
    }
}