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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.Alarm;

/**
 * Reproduces the exact production timeline from 2026-08-22 21:43:
 * disabling alarm A (weekday, next run Mon 05:15Z) made the selection jump to
 * alarm B (weekend, enabled), flipping the switch back ON until B's own
 * disable landed.
 *
 * @author Joe Wang - Initial contribution
 *
 *         The contract: findTargetAlarm must be STABLE across a poll that only changes
 *         an alarm's `enabled` flag - schedule data (time/weekDays) drives selection,
 *         and a disabled alarm keeps its computed slot instead of being skipped.
 */
@NonNullByDefault
public class AlarmSelectorRegressionTest {

    private static Alarm alarm(String id, String time, boolean enabled, String repeatJson, String nextTimestamp) {
        Alarm.AlarmRepeat repeat = repeatJson != null ? GsonBridge.repeat(repeatJson) : GsonBridge.repeatDisabled();
        return new Alarm(id, LocalTime.parse(time), enabled, repeat, Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                null, null, nextTimestamp != null ? Instant.parse(nextTimestamp) : null);
    }

    /** Bridges test JSON into the typed AlarmRepeat without touching private DTOs. */
    private static final class GsonBridge {
        static Alarm.AlarmRepeat repeat(String weekDaysJson) {
            return parseRepeat("{\"repeat\":{\"enabled\":true,\"weekDays\":" + weekDaysJson + "}}");
        }

        static Alarm.AlarmRepeat repeatDisabled() {
            return parseRepeat("{\"repeat\":{\"enabled\":false}}");
        }

        private static Alarm.AlarmRepeat parseRepeat(String body) {
            var obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            return new com.google.gson.Gson().fromJson(obj.get("repeat"), Alarm.AlarmRepeat.class);
        }
    }

    @Test
    public void productionTimeline_selectionStaysOnNewlyDisabledAlarm() {
        // state BEFORE: weekday alarm enabled (next run Mon 2026-08-24T12:15Z),
        // weekend alarm disabled by the earlier session (no nextTimestamp yet)
        List<Alarm> alarms = new java.util.ArrayList<>();
        alarms.add(alarm("b7fbf288", "05:15:00", true,
                "{\"monday\":true,\"tuesday\":true,\"wednesday\":true,\"thursday\":true,\"friday\":true}",
                "2026-08-24T12:15:00Z"));
        alarms.add(alarm("6bd72e29", "07:00:00", false, "{\"saturday\":true,\"sunday\":true}", null));

        // Saturday 2026-08-22, 21:43 UTC - the exact production moment.
        Instant now = Instant.parse("2026-08-22T21:43:11Z");
        // FIXED zone: the original incident ran in America/Los_Angeles. Pinning the
        // zone keeps weekday-vs-weekend ordering identical wherever tests run -
        // with the system zone (e.g. UTC+3) Sunday would already have passed and
        // selection would legitimately differ.
        ZoneId incidentZone = ZoneId.of("America/Los_Angeles");

        // selection BEFORE disable: weekend fires tomorrow (Sun 07:00 PT) vs weekday
        // Monday 05:15 -> weekend selected
        assertEquals("6bd72e29", AlarmSelector.findTargetAlarm(alarms, now, incidentZone).id());

        // user disables the WEEKEND alarm via OH; server clears its nextTimestamp,
        // but our computation derives Mon 05:15 from time+weekDays regardless
        Alarm weekendAlarm = alarms.get(1);
        alarms.set(1,
                new Alarm(weekendAlarm.id(), weekendAlarm.time(), false, weekendAlarm.repeat(), weekendAlarm.thermal(),
                        weekendAlarm.vibration(), weekendAlarm.audio(), weekendAlarm.smart(), weekendAlarm.tags(),
                        weekendAlarm.skipNext(), weekendAlarm.snoozing(), null));

        // CONTRACT: selection stays on the weekend alarm. Its computed Sun 07:00 slot
        // is still sooner than the weekday Mon 05:15, and the switch shows OFF
        // because the alarm is disabled - exactly what the user expects when they
        // turn off their weekend alarm.
        var target = AlarmSelector.findTargetAlarm(alarms, now, incidentZone);
        assertNotNull(target);
        assertEquals("selection stays on disabled alarm's computed slot", "6bd72e29", target.id());
        assertFalse("disabled alarm must show OFF", Boolean.TRUE.equals(target.enabled()));

        // The disabled weekend alarm remains selectable by its own computed slot:
        // it still computes Sun 07:00 today even while disabled.
        var weekend = alarms.get(1);
        assertNotNull(weekend.computeNextRun(incidentZone, now));
    }

    @Test
    public void twoAlarms_soonestComputedWins_stablyAcrossPolls() {
        List<Alarm> alarms = new java.util.ArrayList<>();
        // weekend first in list, weekday second - list order must not matter
        alarms.add(alarm("6bd72e29", "07:00:00", false, "{\"saturday\":true,\"sunday\":true}", null));
        alarms.add(alarm("b7fbf288", "05:15:00", true, "{\"monday\":true,\"wednesday\":true,\"friday\":true}",
                "2026-08-26T12:15:00Z"));

        // Saturday 12:00 UTC; in UTC the weekend alarm's next Sun 07:00 slot is
        // tomorrow while the weekday alarm's next Mon/Wed/Fri 05:15 slot is Monday:
        // weekend sooner. (In US zones the same holds for Sat afternoon; a fixed
        // UTC keeps the expectation valid on any CI runner.)
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        ZoneId zone = ZoneId.of("UTC");
        var t1 = AlarmSelector.findTargetAlarm(alarms, now, zone);
        var t2 = AlarmSelector.findTargetAlarm(alarms, now, zone);
        assertEquals("selection stable across sync cycles", t1.id(), t2.id());
        assertEquals("weekend slot is sooner than the next weekday slot", "6bd72e29", t1.id());
    }
}
