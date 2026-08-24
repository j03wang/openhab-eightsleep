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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Response of {@code GET /temperature/all}: lists the pod plus any pillow with their
 * per-device state. Used for Pod 5 pillow support.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class PillowData {
    public @Nullable List<PillowEntry> devices;

    /**
     * Finds the pillow entry of a given side ("left"/"right"), falling back to the
     * single side-less entry (solo bed), mirroring the upstream client.
     */
    public @Nullable PillowEntry findPillow(String side) {
        List<PillowEntry> pillows = new ArrayList<>();
        if (devices == null) {
            return null;
        }
        for (PillowEntry entry : devices) {
            if (entry.isPillow()) {
                pillows.add(entry);
            }
        }
        for (PillowEntry entry : pillows) {
            if (side.equals(entry.getSide())) {
                return entry;
            }
        }
        if (pillows.size() == 1 && pillows.get(0).getSide() == null) {
            return pillows.get(0);
        }
        return null;
    }

    /** Whether any pod in this payload matches the given device id (bed membership check). */
    public boolean containsPod(String deviceId) {
        if (devices == null || deviceId == null) {
            return false;
        }
        for (PillowEntry entry : devices) {
            if (entry.isPod() && deviceId.equals(entry.getDeviceId())) {
                return true;
            }
        }
        return false;
    }
}
