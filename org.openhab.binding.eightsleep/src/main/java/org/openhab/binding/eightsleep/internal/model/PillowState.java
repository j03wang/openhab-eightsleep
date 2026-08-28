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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Pod and pillow state returned for a user.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record PillowState(List<PillowEntry> devices) {

    public static final PillowState EMPTY = new PillowState(List.of());

    public PillowState {
        devices = List.copyOf(devices);
    }

    /**
     * Finds the pillow associated with a side.
     *
     * @param side the logical bed side
     * @return the matching pillow, or the sole sideless pillow, or {@code null}
     */
    public @Nullable PillowEntry findPillow(BedSide side) {
        List<PillowEntry> pillows = devices.stream().filter(PillowEntry::isPillow).toList();
        for (PillowEntry entry : pillows) {
            if (side == entry.side()) {
                return entry;
            }
        }
        return pillows.size() == 1 && pillows.get(0).side() == null ? pillows.get(0) : null;
    }

    /**
     * Determines whether this response includes a specific Pod.
     *
     * @param deviceId the Pod device identifier
     * @return {@code true} if the Pod is present
     */
    public boolean containsPod(String deviceId) {
        return devices.stream().anyMatch(entry -> entry.isPod() && deviceId.equals(entry.deviceId()));
    }
}
