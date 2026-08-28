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
package org.openhab.binding.eightsleep.internal.alarm;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.polling.DataFreshness;

/**
 * Selection of the alarm the alarm channels represent: the soonest locally-computed
 * run across ALL alarms, enabled or not (a disabled alarm's schedule is derived from
 * time+weekDays since its server nextTimestamp goes null). Selection is therefore
 * stable: toggling one alarm off doesn't move selection to another. Ties break on
 * id; an alarm without an id loses the tie (it cannot be toggled).
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class AlarmSelector {

    private AlarmSelector() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Selects the target alarm using the system zone (production path).
     */
    public static @Nullable Alarm findTargetAlarm(List<Alarm> alarms, Instant now) {
        return findTargetAlarm(alarms, now, ZoneId.systemDefault());
    }

    /**
     * As above with an explicit zone - production uses the system zone, tests inject
     * a fixed zone so they cannot depend on where they run.
     */
    public static @Nullable Alarm findTargetAlarm(List<Alarm> alarms, Instant now, ZoneId zone) {
        Alarm target = null;
        Instant targetRun = null;
        for (Alarm alarm : alarms) {
            Instant run = alarm.computeNextRun(zone, now);
            if (run == null) {
                continue;
            }
            boolean closer = targetRun == null || run.isBefore(targetRun) || (run.equals(targetRun) && target != null
                    && alarm.id() != null && (target.id() == null || alarm.id().compareTo(target.id()) < 0));
            if (closer) {
                target = alarm;
                targetRun = run;
            }
        }
        return target;
    }

    /**
     * Whether the alarm channels should be reset to UNDEF: there is no selectable
     * alarm AND either stale alarm entries are still published or the last alarms
     * poll was recent enough to trust an empty list (e.g. subscription lapse).
     */
    public static boolean shouldClearAlarmChannels(boolean nextAlarmPresent, int alarmCount,
            @Nullable Instant alarmsPolledAt, Instant now, long userIntervalSeconds) {
        if (nextAlarmPresent) {
            return false;
        }
        return alarmCount > 0 || !DataFreshness.isStale(alarmsPolledAt, now, userIntervalSeconds);
    }
}
