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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.GsonHelper;

/**
 * Regression tests for the alarm schedule computation that replaced reliance on
 * the server's nextTimestamp field (which goes stale or null when an alarm is
 * disabled).
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AlarmScheduleTest {

    private Alarm alarm(String time, boolean enabled, String weekDaysJson) {
        Alarm.AlarmRepeat repeat;
        if (weekDaysJson != null) {
            repeat = GsonHelper.fromJson("{\"enabled\":true,\"weekDays\":" + weekDaysJson + "}",
                    Alarm.AlarmRepeat.class);
        } else {
            repeat = new Alarm.AlarmRepeat(false, Map.of());
        }
        return alarm(time, enabled, repeat, null);
    }

    /**
     * Bug: selection skipped disabled alarms entirely, so toggling one off made the
     * switch jump to another alarm ("flips back to ON"). Disabled alarms must still
     * produce a computed next run so they can be selected and re-enabled.
     */
    @Test
    public void disabledWeekdayAlarmStillComputesNextRun() {
        // Saturday 2026-08-22 noon PT; weekday alarm disabled but schedule intact
        var alarm = alarm("05:15:00", false,
                "{\"monday\":true,\"tuesday\":true,\"wednesday\":true,\"thursday\":true,\"friday\":true}");
        Instant now = ZonedDateTime.of(2026, 8, 22, 12, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant();
        Instant next = alarm.computeNextRun(ZoneId.of("America/Los_Angeles"), now);
        assertTrue(next != null);
        // next weekday occurrence after Sat 2026-08-22 is Monday 2026-08-24 05:15 PT
        ZonedDateTime zdt = next.atZone(ZoneId.of("America/Los_Angeles"));
        assertEquals("MONDAY", zdt.getDayOfWeek().toString());
        assertEquals(5, zdt.getHour());
        assertEquals(15, zdt.getMinute());
    }

    /** Weekend-only mask must land on Saturday or Sunday, whatever today is. */
    @Test
    public void weekendAlarmLandsOnAWeekendDay() {
        var alarm = alarm("07:00:00", true, "{\"saturday\":true,\"sunday\":true}");
        // Wednesday: next weekend day must be found deterministically
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant();
        Instant next = alarm.computeNextRun(ZoneId.of("America/Los_Angeles"), now);
        assertNotNull(next);
        ZonedDateTime zdt = next.atZone(ZoneId.of("America/Los_Angeles"));
        String dow = zdt.getDayOfWeek().toString();
        assertTrue("must be SATURDAY or SUNDAY, was " + dow, "SATURDAY".equals(dow) || "SUNDAY".equals(dow));
    }

    /** Repeat flag with no active weekdays is treated as daily (upstream behaviour). */
    @Test
    public void repeatWithoutDaysIsDaily() {
        var alarm = alarm("23:59:00", true, "{}");
        Instant now = ZonedDateTime.of(2026, 8, 22, 8, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        Instant next = alarm.computeNextRun(ZoneId.of("UTC"), now);
        assertNotNull(next);
        assertEquals(23, next.atZone(ZoneId.of("UTC")).getHour());
    }

    /** One-shot alarms carry no date in HH:mm:ss - fall back to server nextTimestamp. */
    @Test
    public void oneShotFallsBackToServerTimestamp() {
        var alarm = alarm("09:00:00", true, new Alarm.AlarmRepeat(false, Map.of()),
                Instant.parse("2026-08-30T16:00:00Z"));
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        Instant next = alarm.computeNextRun(ZoneId.of("UTC"), now);
        assertEquals(Instant.parse("2026-08-30T16:00:00Z"), next);
    }

    /**
     * Takeover timing: with the weekend alarm disabled and Sunday 07:00 passed, the
     * enabled weekday alarm's computed run (Mon 05:15) is sooner than the weekend
     * alarm's next computed slot (next Sat 07:00), so selection moves to it.
     */
    @Test
    public void weekdayTakesOverAfterWeekendSlotPasses() {
        var weekend = alarm("07:00:00", false, "{\"saturday\":true,\"sunday\":true}");
        var weekday = alarm("05:15:00", true,
                "{\"monday\":true,\"tuesday\":true,\"wednesday\":true,\"thursday\":true,\"friday\":true}");

        // Sunday Aug 23, 08:00 PT - one hour after the weekend slot fired
        Instant now = ZonedDateTime.of(2026, 8, 23, 8, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant();
        ZoneId zone = ZoneId.of("America/Los_Angeles");

        // The disabled weekend alarm still computes its schedule, rolling to NEXT
        // Saturday 07:00 since this week's Sunday 07:00 slot already fired. The
        // enabled weekday alarm fires Monday 05:15 PT - sooner, so IT takes over.
        Instant weekendNext = weekend.computeNextRun(zone, now);
        assertNotNull(weekendNext);
        ZonedDateTime weekendZdt = weekendNext.atZone(zone);
        assertEquals(DayOfWeek.SATURDAY, weekendZdt.getDayOfWeek());

        Instant weekdayNext = weekday.computeNextRun(zone, now);
        assertNotNull(weekdayNext);
        assertEquals(DayOfWeek.MONDAY, weekdayNext.atZone(zone).getDayOfWeek());
        assertTrue("weekday Mon must be sooner than weekend's next Sat", weekdayNext.isBefore(weekendNext));
    }

    /** Missing domain time yields null instead of throwing. */
    @Test
    public void missingTimeYieldsNull() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        assertNull(alarm(null, true, null).computeNextRun(ZoneId.of("UTC"), now));
    }

    private Alarm alarm(String time, boolean enabled, Alarm.AlarmRepeat repeat, Instant nextRun) {
        return new Alarm("a1", time != null ? LocalTime.parse(time) : null, enabled, repeat, Map.of(), Map.of(),
                Map.of(), Map.of(), List.of(), null, null, nextRun);
    }
}
