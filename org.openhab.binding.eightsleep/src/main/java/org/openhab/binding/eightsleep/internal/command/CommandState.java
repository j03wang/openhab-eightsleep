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
package org.openhab.binding.eightsleep.internal.command;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.sync.LastWriteWins.CommandedValue;

/**
 * Pending bed-side commands and locally retained target state used during reconciliation.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class CommandState {

    private final ConcurrentHashMap<String, CommandedValue> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CommandedValue> alarms = new ConcurrentHashMap<>();
    private volatile @Nullable Double lastKnownTargetLevel;

    /**
     * Records a boolean channel command at the current instant.
     *
     * @param channelId the channel identifier
     * @param value the commanded value
     */
    public void recordChannel(String channelId, boolean value) {
        channels.put(channelId, new CommandedValue(Instant.now(), value));
    }

    /**
     * Returns the pending command for a channel.
     *
     * @param channelId the channel identifier
     * @return the command, or {@code null} if none is pending
     */
    public @Nullable CommandedValue channel(String channelId) {
        return channels.get(channelId);
    }

    /**
     * Retires the pending command for a channel.
     *
     * @param channelId the channel identifier
     */
    public void retireChannel(String channelId) {
        channels.remove(channelId);
    }

    /**
     * Records an alarm-enabled command at the current instant.
     *
     * @param alarmId the alarm identifier
     * @param enabled the commanded state
     */
    public void recordAlarm(String alarmId, boolean enabled) {
        alarms.put(alarmId, new CommandedValue(Instant.now(), enabled));
    }

    /**
     * Returns the pending enabled command for an alarm.
     *
     * @param alarmId the alarm identifier
     * @return the command, or {@code null} if none is pending
     */
    public @Nullable CommandedValue alarm(String alarmId) {
        return alarms.get(alarmId);
    }

    /**
     * Retires the pending enabled command for an alarm.
     *
     * @param alarmId the alarm identifier
     */
    public void retireAlarm(String alarmId) {
        alarms.remove(alarmId);
    }

    /**
     * Returns the last meaningful target heating level shown by the handler.
     *
     * @return the retained level, or {@code null} before one is known
     */
    public @Nullable Double lastKnownTargetLevel() {
        return lastKnownTargetLevel;
    }

    /**
     * Retains the last meaningful target heating level.
     *
     * @param level the level to retain
     */
    public void setLastKnownTargetLevel(double level) {
        lastKnownTargetLevel = level;
    }
}
