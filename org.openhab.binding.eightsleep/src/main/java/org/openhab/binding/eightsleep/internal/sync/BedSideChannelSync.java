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
package org.openhab.binding.eightsleep.internal.sync;

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import java.time.Instant;
import java.time.ZoneId;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.polling.DataFreshness;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
import org.openhab.binding.eightsleep.internal.sync.LastWriteWins.CommandedValue;
import org.openhab.binding.eightsleep.internal.sync.SyncResult.StatusAction;

/**
 * Pure resolver turning cached account data into the channel updates of one bed
 * side: input is the polled cache plus the pending command stamps, output is the
 * list of {@code group#channel -> State} updates, the thing-status decision and
 * which commands the server confirmed. No openHAB framework objects involved,
 * which makes the entire channel mapping testable against fixture payloads.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class BedSideChannelSync {

    /**
     * Computes all channel updates for one sync cycle.
     *
     * @param sidePowerCommand pending side-power command stamp (null = none)
     * @param alarmEnabledCommand pending command stamp for the selected alarm (null = none)
     * @param awayModeCommand pending away-mode command stamp (null = none)
     * @param lastKnownTargetLevel previously persisted shown target level (null = never set)
     */
    public SyncResult compute(@Nullable DeviceState deviceState, @Nullable UserDataSnapshot userData, BedSide side,
            boolean fahrenheit, long userIntervalSeconds, Instant now, ZoneId zone,
            @Nullable CommandedValue sidePowerCommand, @Nullable CommandedValue alarmEnabledCommand,
            @Nullable CommandedValue awayModeCommand, @Nullable Double lastKnownTargetLevel) {
        SyncCollector r = new SyncCollector();

        if (deviceState == null) {
            r.statusAction = StatusAction.BRIDGE_OFFLINE;
            return r.build();
        }
        if (userData == null) {
            r.statusAction = StatusAction.USER_NOT_FOUND;
            return r.build();
        }
        r.statusAction = DataFreshness.isStale(userData.lastUpdated(), now, userIntervalSeconds)
                ? StatusAction.STALE_DATA
                : StatusAction.ONLINE;

        DeviceChannelMapper.Projection device = DeviceChannelMapper.publish(deviceState, userData, side, fahrenheit,
                sidePowerCommand, awayModeCommand, lastKnownTargetLevel, r);
        SleepChannelMapper.publish(userData.trends(), device.heatingLevel(), fahrenheit, now, r);
        AccessoryChannelMapper.publish(userData, side, fahrenheit, r);
        AlarmChannelMapper.publish(userData, now, zone, userIntervalSeconds, alarmEnabledCommand, r);
        return r.build();
    }
}
