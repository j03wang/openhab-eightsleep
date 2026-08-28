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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * User and away-side assignments of a device.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record DeviceAssignments(@Nullable String leftUserId, @Nullable String rightUserId,
        Map<String, String> awaySides) {

    public static final DeviceAssignments EMPTY = new DeviceAssignments(null, null, Map.of());

    public DeviceAssignments {
        awaySides = Collections.unmodifiableMap(new HashMap<>(awaySides));
    }

    /**
     * Determines whether a user is assigned to an away slot and no longer occupies a bed side.
     *
     * @param userId the user identifier
     * @return {@code true} if the user is away
     */
    public boolean isAway(String userId) {
        return userId != null && awaySides.containsValue(userId) && !userId.equals(leftUserId)
                && !userId.equals(rightUserId);
    }
}
