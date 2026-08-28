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
import static org.openhab.binding.eightsleep.internal.sync.SyncChannels.add;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
import org.openhab.binding.eightsleep.internal.sync.LastWriteWins.CommandedValue;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.UnDefType;

@NonNullByDefault
final class AlarmChannelMapper {

    private AlarmChannelMapper() {
        throw new IllegalAccessError("Non-instantiable");
    }

    static void publish(UserDataSnapshot userData, Instant now, ZoneId zone, long userIntervalSeconds,
            @Nullable CommandedValue alarmEnabledCommand, SyncCollector collector) {
        Alarm alarm = AlarmSelector.findTargetAlarm(userData.alarms(), now, zone);
        if (AlarmSelector.shouldClearAlarmChannels(alarm != null, userData.alarms().size(), userData.alarmsPolledAt(),
                now, userIntervalSeconds)) {
            add(collector, GROUP_CURRENT, CHANNEL_NEXT_ALARM, UnDefType.UNDEF);
            add(collector, GROUP_CURRENT, CHANNEL_ALARM_ENABLED, UnDefType.UNDEF);
            add(collector, GROUP_CURRENT, CHANNEL_ALARM_TIME, UnDefType.UNDEF);
        }
        if (alarm == null || alarm.id() == null) {
            return;
        }
        Instant nextRun = alarm.computeNextRun(zone, now);
        if (nextRun != null) {
            add(collector, GROUP_CURRENT, CHANNEL_NEXT_ALARM, new DateTimeType(nextRun));
        }
        if (alarm.time() != null) {
            add(collector, GROUP_CURRENT, CHANNEL_ALARM_TIME,
                    new DateTimeType(alarm.time().atDate(LocalDate.ofInstant(now, zone)).atZone(zone).toInstant()));
        }
        Boolean resolved = LastWriteWins.resolveLatest(alarm.enabled(), userData.alarmsPolledAt(), alarmEnabledCommand);
        if (resolved != null) {
            add(collector, GROUP_CURRENT, CHANNEL_ALARM_ENABLED, OnOffType.from(resolved));
            if (LastWriteWins.shouldRetireCommand(alarm.enabled(), resolved)) {
                collector.retireAlarmId = alarm.id();
            }
        }
    }
}
