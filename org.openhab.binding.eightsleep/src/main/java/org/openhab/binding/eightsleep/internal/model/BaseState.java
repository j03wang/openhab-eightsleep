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
 * Current adjustable-base state.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record BaseState(@Nullable SideState left, @Nullable SideState right) {

    public static final BaseState EMPTY = new BaseState(null, null);

    /**
     * Returns state for the requested side. Solo beds use the left physical zone.
     *
     * @param side the logical bed side
     * @return the side state, or {@code null} when unavailable
     */
    public @Nullable SideState side(BedSide side) {
        return side == BedSide.RIGHT ? right : left;
    }

    public record SideState(@Nullable String presetName, @Nullable Integer legAngle, @Nullable Integer torsoAngle,
            @Nullable Boolean inSnoreMitigation) {
    }
}
