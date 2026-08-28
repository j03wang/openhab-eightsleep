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
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Mutable per-user cache of polled account data, written by the poll loop and
 * read by the bed-side channel sync. Fields are volatile: writers run on the
 * HTTP completion threads, readers on the channel-sync scheduler.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class UserDataCache {

    public final List<Alarm> alarms = new CopyOnWriteArrayList<>();
    /** When {@code alarms} was last fetched - used for last-write-wins merging. */
    public volatile java.time.@Nullable Instant alarmsPolledAt;
    public volatile @Nullable BaseState baseState;
    public volatile @Nullable PlayerState playerState;
    public volatile PillowState pillowState = PillowState.EMPTY;
    public volatile @Nullable TemperatureState temperature;
    /** When {@code temperature} was fetched - used for last-write-wins merging. */
    public volatile java.time.@Nullable Instant temperatureAt;
    public volatile TrendData trends = TrendData.EMPTY;
    /**
     * Last OBSERVED away state (raw poll result, unmerged). The channel sync
     * resolves this against the pending command stamp held by the account.
     */
    public volatile boolean awayObserved = false;
    /** When {@code awayObserved} was seen - the away poll's START time. */
    public volatile Instant awayPolledAt = Instant.EPOCH;
    /**
     * When cached data was last (re)freshed - the construction moment counts as
     * fresh so a just-created entry is not immediately flagged stale; every
     * completed poll overwrites it.
     */
    public volatile Instant lastUpdated = Instant.now();

    /**
     * Captures the current cache values for deterministic downstream processing.
     *
     * @return an immutable cache snapshot
     */
    public UserDataSnapshot snapshot() {
        return new UserDataSnapshot(alarms, alarmsPolledAt, baseState, playerState, pillowState, temperature,
                temperatureAt, trends, awayObserved, awayPolledAt, lastUpdated);
    }

    /**
     * Returns cached adjustable-base state for a side.
     *
     * @param side the logical bed side
     * @return the cached side state, or {@code null} when unavailable
     */
    public BaseState.@Nullable SideState baseSide(BedSide side) {
        BaseState base = baseState;
        return base != null ? base.side(side) : null;
    }
}
