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
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;

/**
 * Mutable per-user cache of polled account data, written by the poll loop and
 * read by the bed-side channel sync. Fields are volatile: writers run on the
 * HTTP completion threads, readers on the channel-sync scheduler.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class UserDataCache {

    public final List<EightSleepApiClient.Alarm> alarms = new CopyOnWriteArrayList<>();
    /** When {@code alarms} was last fetched - used for last-write-wins merging. */
    public volatile java.time.@Nullable Instant alarmsPolledAt;
    public volatile org.openhab.binding.eightsleep.internal.model.@Nullable BaseData baseData;
    public volatile org.openhab.binding.eightsleep.internal.model.@Nullable PlayerState playerState;
    public volatile EightSleepApiClient.@Nullable PillowData pillowData;
    /** Raw /temperature payload (currentLevel, smart schedule, ...). */
    public volatile com.google.gson.@Nullable JsonObject temperature;
    /** When {@code temperature} was fetched - used for last-write-wins merging. */
    public volatile java.time.@Nullable Instant temperatureAt;
    /** Raw v1 trends "days" payload, parsed defensively on read. */
    public volatile com.google.gson.JsonArray trendDays = new com.google.gson.JsonArray();
    public volatile boolean awayMode;
    /** Instant of the last command that set awayMode (for last-write-wins). */
    public volatile java.time.@Nullable Instant awayCommandedAt;
    /** Instant of the last successful away-state poll; epoch means "never". */
    public volatile Instant awayPolledAt = Instant.EPOCH;
    /**
     * When cached data was last (re)freshed - the construction moment counts as
     * fresh so a just-created entry is not immediately flagged stale; every
     * completed poll overwrites it.
     */
    public volatile Instant lastUpdated = Instant.now();

    /**
     * Defensive parser over the raw trends payload. Day 0 from the end is the current one.
     */
    public TrendParser getTrends() {
        return new TrendParser(trendDays);
    }

    public BaseData.@Nullable SideData getBaseSide(String side) {
        BaseData base = baseData;
        return base != null ? base.getSide(side) : null;
    }
}
