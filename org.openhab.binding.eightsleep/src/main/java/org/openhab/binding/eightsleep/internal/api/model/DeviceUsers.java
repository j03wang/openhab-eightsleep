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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Device-user assignment incl. away sides, from the
 * {@code GET /devices/{id}?filter=leftUserId,rightUserId,awaySides} response.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DeviceUsers {
    public @Nullable String leftUserId;
    public @Nullable String rightUserId;
    /**
     * Side -> userId map from the {@code awaySides} filter. NOTE: in live captures
     * the KEYS are "leftUserId"/"rightUserId" (not "left"/"right") - only the
     * VALUES (user ids) are meaningful, which is all {@link #isAway} uses.
     */
    public Map<String, String> awaySides = new HashMap<>();

    /**
     * Verified live semantics (captured present vs away):
     * an away user is listed in {@code awaySides} AND has been removed from their
     * side slot (leftUserId/rightUserId becomes null while away). A present user
     * is listed in awaySides too (stale record) but still occupies a side slot.
     */
    public boolean isAway(String userId) {
        if (userId == null || !awaySides.containsValue(userId)) {
            return false;
        }
        boolean occupiesSide = userId.equals(leftUserId) || userId.equals(rightUserId);
        return !occupiesSide;
    }
}
