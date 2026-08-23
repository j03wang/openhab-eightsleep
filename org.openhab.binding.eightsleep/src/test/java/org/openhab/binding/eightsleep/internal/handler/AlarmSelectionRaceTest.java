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
package org.openhab.binding.eightsleep.internal.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;

/**
 * Reproduces the exact production timeline from 2026-08-22 21:43:
 * disabling alarm A (weekday, next run Mon 05:15Z) made the selection jump to
 * alarm B (weekend, enabled), flipping the switch back ON until B's own
 * disable landed.
 *
 * The contract: findTargetAlarm must be STABLE across a poll that only changes
 * an alarm's `enabled` flag - schedule data (time/weekDays) drives selection,
 * and a disabled alarm keeps its computed slot instead of being skipped.
 */
@NonNullByDefault
public class AlarmSelectionRaceTest {

    private static EightSleepApiClient.Alarm alarm(String id, String time, boolean enabled,
            String repeatJson, String nextTimestamp) {
        var alarm = new EightSleepApiClient.Alarm();
        alarm.id = id;
        alarm.time = time;
        alarm.enabled = enabled;
        alarm.repeat = repeatJson != null ? GsonBridge.repeat(repeatJson) : GsonBridge.repeatDisabled();
        alarm.nextTimestamp = nextTimestamp;
        return alarm;
    }

    /** Bridges test JSON into the typed AlarmRepeat without touching private DTOs. */
    private static final class GsonBridge {
        static EightSleepApiClient.Alarm.AlarmRepeat repeat(String weekDaysJson) {
            return parseRepeat("{\"repeat\":{\"enabled\":true,\"weekDays\":" + weekDaysJson + "}}");
        }

        static EightSleepApiClient.Alarm.AlarmRepeat repeatDisabled() {
            return parseRepeat("{\"repeat\":{\"enabled\":false}}");
        }

        private static EightSleepApiClient.Alarm.AlarmRepeat parseRepeat(String body) {
            var obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            return new com.google.gson.Gson().fromJson(obj.get("repeat"),
                    EightSleepApiClient.Alarm.AlarmRepeat.class);
        }
    }

    @Test
    public void productionTimeline_selectionStaysOnNewlyDisabledAlarm() {
        // state BEFORE: weekday alarm enabled (next run Mon 2026-08-24T12:15Z),
        // weekend alarm disabled by the earlier session (no nextTimestamp yet)
        UserDataCache userData = new UserDataCache();
        userData.alarms.add(alarm("b7fbf288", "05:15:00", true,
                "{\"monday\":true,\"tuesday\":true,\"wednesday\":true,\"thursday\":true,\"friday\":true}",
                "2026-08-24T12:15:00Z"));
        userData.alarms.add(alarm("6bd72e29", "07:00:00", false,
                "{\"saturday\":true,\"sunday\":true}", null));

        // Saturday 2026-08-22, 21:43 UTC - the exact production moment.
        Instant now = Instant.parse("2026-08-22T21:43:11Z");
        // FIXED zone: the original incident ran in America/Los_Angeles. Pinning the
        // zone keeps weekday-vs-weekend ordering identical wherever tests run -
        // with the system zone (e.g. UTC+3) Sunday would already have passed and
        // selection would legitimately differ.
        ZoneId incidentZone = ZoneId.of("America/Los_Angeles");

        // selection BEFORE disable: weekend fires tomorrow (Sun 07:00 PT) vs weekday
        // Monday 05:15 -> weekend selected
        assertEquals("6bd72e29",
                AlarmSelector.findTargetAlarm(userData, now, incidentZone).id);

        // user disables the WEEKEND alarm via OH; server clears its nextTimestamp,
        // but our computation derives Mon 05:15 from time+weekDays regardless
        userData.alarms.get(1).enabled = false;
        userData.alarms.get(1).nextTimestamp = null;

        // CONTRACT: selection stays on the weekend alarm. Its computed Sun 07:00 slot
        // is still sooner than the weekday Mon 05:15, and the switch shows OFF
        // because the alarm is disabled - exactly what the user expects when they
        // turn off their weekend alarm.
        var target = AlarmSelector.findTargetAlarm(userData, now, incidentZone);
        assertNotNull(target);
        assertEquals("selection stays on disabled alarm's computed slot",
                "6bd72e29", target.id);
        assertFalse("disabled alarm must show OFF",
                Boolean.TRUE.equals(target.enabled));

        // The disabled weekend alarm remains selectable by its own computed slot:
        // it still computes Sun 07:00 today even while disabled.
        var weekend = userData.alarms.get(1);
        assertNotNull(weekend.computeNextRun(incidentZone, now));
    }

    @Test
    public void twoAlarms_soonestComputedWins_stablyAcrossPolls() {
        UserDataCache userData = new UserDataCache();
        // weekend first in list, weekday second - list order must not matter
        userData.alarms.add(alarm("6bd72e29", "07:00:00", false, "{\"saturday\":true,\"sunday\":true}", null));
        userData.alarms.add(alarm("b7fbf288", "05:15:00", true,
                "{\"monday\":true,\"wednesday\":true,\"friday\":true}", "2026-08-26T12:15:00Z"));

        // Saturday 12:00 UTC; in UTC the weekend alarm's next Sun 07:00 slot is
        // tomorrow while the weekday alarm's next Mon/Wed/Fri 05:15 slot is Monday:
        // weekend sooner. (In US zones the same holds for Sat afternoon; a fixed
        // UTC keeps the expectation valid on any CI runner.)
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        ZoneId zone = ZoneId.of("UTC");
        var t1 = AlarmSelector.findTargetAlarm(userData, now, zone);
        var t2 = AlarmSelector.findTargetAlarm(userData, now, zone);
        assertEquals("selection stable across sync cycles", t1.id, t2.id);
        assertEquals("weekend slot is sooner than the next weekday slot",
                "6bd72e29", t1.id);
    }
}
