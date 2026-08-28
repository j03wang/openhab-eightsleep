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
package org.openhab.binding.eightsleep.internal.polling;

import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BaseState;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.PillowState;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.openhab.binding.eightsleep.internal.model.TemperatureState;
import org.openhab.binding.eightsleep.internal.model.TrendData;

/**
 * Immutable view of all polled state for one user.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record UserDataSnapshot(List<Alarm> alarms, @Nullable Instant alarmsPolledAt, @Nullable BaseState baseState,
        @Nullable PlayerState playerState, PillowState pillowState, @Nullable TemperatureState temperature,
        @Nullable Instant temperatureAt, TrendData trends, boolean awayObserved, Instant awayPolledAt,
        Instant lastUpdated) {

    public UserDataSnapshot {
        alarms = List.copyOf(alarms);
    }

    /**
     * Returns adjustable-base state for a side.
     *
     * @param side the logical bed side
     * @return the side state, or {@code null} when unavailable
     */
    public BaseState.@Nullable SideState baseSide(BedSide side) {
        return baseState != null ? baseState.side(side) : null;
    }
}
