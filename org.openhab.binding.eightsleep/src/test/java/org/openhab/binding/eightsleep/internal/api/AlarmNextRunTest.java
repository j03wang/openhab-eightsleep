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
package org.openhab.binding.eightsleep.internal.api;


import org.openhab.binding.eightsleep.internal.api.model.Alarm;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests for the weekly roll-forward of stale alarm timestamps and DST handling
 * of the repeating-alarm schedule computation. All cases inject a fixed clock -
 * no test may depend on the wall clock.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AlarmNextRunTest {

    private static Alarm alarmWithRepeat(String repeatJson) {
        var alarm = new Alarm();
        alarm.id = "a1";
        alarm.time = "07:30:00";
        alarm.repeat = GsonHelper.fromJson(repeatJson, Alarm.AlarmRepeat.class);
        return alarm;
    }

    // ==================== rollToNextWeek ====================

    @Test
    public void nullTimestampStaysNull() {
        assertNull(Alarm.rollToNextWeek(null, Instant.now()));
    }

    @Test
    public void futureTimestampUnchanged() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        Instant future = Instant.parse("2026-08-25T09:00:00Z");
        assertEquals(future, Alarm.rollToNextWeek(future, now));
    }

    /** A stale timestamp rolls forward in whole weeks until it lands after now. */
    @Test
    public void pastTimestampRollsWholeWeeks() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        Instant stale = Instant.parse("2026-08-01T09:00:00Z"); // 3 weeks back
        Instant rolled = Alarm.rollToNextWeek(stale, now);
        assertNotNull(rolled);
        assertEquals(Instant.parse("2026-08-29T09:00:00Z"), rolled);
        assertFalse(rolled.isBefore(now));
    }

    /**
     * Rolling across a DST boundary keeps UTC arithmetic (07:00 UTC stays
     * 07:00 UTC even though the local offset changes). Documented behaviour.
     */
    @Test
    public void rollAcrossDstBoundaryKeepsUtcArithmetic() {
        ZoneId la = ZoneId.of("America/Los_Angeles");
        Instant beforeDst = ZonedDateTime.of(2026, 10, 25, 7, 0, 0, 0, la).toInstant(); // PDT (UTC-7)
        Instant now = beforeDst.plusSeconds(60);
        Instant rolled = Alarm.rollToNextWeek(beforeDst, now); // crosses Nov 1 DST end
        assertNotNull(rolled);
        assertEquals("same UTC time-of-day preserved", beforeDst.toEpochMilli() + 7L * 24 * 3600 * 1000,
                rolled.toEpochMilli());
    }

    // ==================== computeNextRun extras ====================

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z"); // Saturday

    /** Repeat flag enabled with an all-false weekDays map is treated as daily. */
    @Test
    public void allFalseWeekDaysMeansDaily() {
        var alarm = alarmWithRepeat("{\"enabled\":true,\"weekDays\":{\"monday\":false,\"tuesday\":false}}");
        Instant run = alarm.computeNextRun(ZoneId.of("UTC"), NOW);
        // daily -> next occurrence is today 07:30 UTC? No: 12:00Z already past it -> tomorrow 07:30Z
        assertNotNull(run);
        ZonedDateTime zdt = run.atZone(ZoneId.of("UTC"));
        assertEquals(ZonedDateTime.ofInstant(NOW, ZoneId.of("UTC")).plusDays(1).getDayOfMonth(), zdt.getDayOfMonth());
        assertEquals(7, zdt.getHour());
        assertEquals(30, zdt.getMinute());
    }

    /** Time strings carrying fractional seconds still parse (first 8 chars used). */
    @Test
    public void fractionalSecondTimeParses() {
        var alarm = new Alarm();
        alarm.time = "06:45:00.000";
        alarm.nextTimestamp = "2026-08-30T16:00:00Z"; // one-shot path uses server ts anyway
        Instant run = alarm.computeNextRun(ZoneId.of("UTC"), NOW);
        assertEquals(Instant.parse("2026-08-30T16:00:00Z"), run);
    }

    /** One-shot enabled alarm with a FUTURE server timestamp uses it as-is. */
    @Test
    public void oneShotFutureServerTimestampWins() {
        var alarm = GsonHelper.fromJson(
                "{\"repeat\":{\"enabled\":false}}", Alarm.class);
        alarm.time = "09:00:00";
        alarm.nextTimestamp = "2026-09-05T09:00:00Z";
        assertEquals(Instant.parse("2026-09-05T09:00:00Z"), alarm.computeNextRun(ZoneId.of("UTC"), NOW));
    }

    /** One-shot DISABLED alarm with a STALE timestamp is kept in the ordering one week out. */
    @Test
    public void oneShotStaleTimestampRollsAWeek() {
        var alarm = new Alarm();
        alarm.time = "09:00:00";
        alarm.nextTimestamp = "2026-08-20T09:00:00Z"; // already fired, disabled since
        Instant run = alarm.computeNextRun(ZoneId.of("UTC"), NOW);
        assertEquals(Instant.parse("2026-08-27T09:00:00Z"), run);
    }

    /** One-shot with no timestamp at all yields null (excluded from selection). */
    @Test
    public void oneShotWithoutTimestampIsNull() {
        var alarm = new Alarm();
        alarm.time = "09:00:00";
        assertNull(alarm.computeNextRun(ZoneId.of("UTC"), NOW));
    }

    /** Repeating alarm firing exactly at `now` is returned (not skipped to next week). */
    @Test
    public void slotExactlyNowIsReturned() {
        var alarm = alarmWithRetryAt("12:00:00", "{\"enabled\":true,\"weekDays\":{}}");
        assertEquals(NOW, alarm.computeNextRun(ZoneId.of("UTC"), NOW));
    }

    /** Spring-forward gap: 02:30 does not exist on 2027-03-14 in Los Angeles; the zone shifts it. */
    @Test
    public void dstGapDoesNotThrowAndStaysSameDay() {
        var alarm = alarmWithRetryAt("02:30:00",
                "{\"enabled\":true,\"weekDays\":{\"sunday\":true}}");
        Instant now = Instant.parse("2027-03-13T00:00:00Z"); // Saturday
        Instant run = alarm.computeNextRun(ZoneId.of("America/Los_Angeles"), now);
        assertNotNull(run);
        ZonedDateTime zdt = run.atZone(ZoneId.of("America/Los_Angeles"));
        assertEquals(java.time.DayOfWeek.SUNDAY, zdt.getDayOfWeek());
        assertEquals(3, zdt.getHour()); // shifted out of the gap by the zone rules
    }

    private static Alarm alarmWithRetryAt(String time, String repeatJson) {
        var alarm = alarmWithRepeat(repeatJson);
        alarm.time = time;
        return alarm;
    }
}
