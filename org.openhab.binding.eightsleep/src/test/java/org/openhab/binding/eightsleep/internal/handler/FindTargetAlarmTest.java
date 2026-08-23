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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneId;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;

import com.google.gson.Gson;

/**
 * Selection edge cases for the alarm channel targeting: null handling and the
 * deterministic id tie-break. All cases inject a fixed clock AND a fixed zone,
 * so no assertion depends on the timezone of the machine running the tests.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class FindTargetAlarmTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    /** Fixed zone for every selection call - CI may run in any timezone. */
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static EightSleepApiClient.Alarm alarm(String id, String time, String repeatJson) {
        var alarm = new EightSleepApiClient.Alarm();
        alarm.id = id;
        alarm.time = time;
        if (repeatJson != null) {
            alarm.repeat = new Gson().fromJson(repeatJson, EightSleepApiClient.Alarm.AlarmRepeat.class);
        }
        return alarm;
    }

    @Test
    public void noAlarmsYieldsNull() {
        assertNull(FindTargetAlarmBridge.find(new AccountHandler.UserData(), NOW));
    }

    /** Alarms whose schedule cannot be computed are excluded from selection. */
    @Test
    public void uncomputableAlarmsExcluded() {
        AccountHandler.UserData userData = new AccountHandler.UserData();
        userData.alarms.add(alarm("a1", null, "{\"enabled\":true,\"weekDays\":{}}")); // no time -> null run
        userData.alarms.add(alarm("a2", "bogus", "{\"enabled\":true,\"weekDays\":{}}")); // bad time -> null run
        assertNull(FindTargetAlarmBridge.find(userData, NOW));
    }

    /** Equal computed runs break the tie on the smaller id, regardless of list order. */
    @Test
    public void equalRunsTieBreakOnSmallestId() {
        AccountHandler.UserData userData = new AccountHandler.UserData();
        userData.alarms.add(alarm("bbb", "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        userData.alarms.add(alarm("aaa", "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        var target = FindTargetAlarmBridge.find(userData, NOW);
        assertNotNull(target);
        assertEquals("aaa", target.id);
    }

    /** An alarm with a null id must not win a tie (cannot be toggled anyway). */
    @Test
    public void nullIdLosesTie() {
        AccountHandler.UserData userData = new AccountHandler.UserData();
        userData.alarms.add(alarm(null, "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        userData.alarms.add(alarm("zzz", "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        var target = FindTargetAlarmBridge.find(userData, NOW);
        assertNotNull(target);
        assertEquals("zzz", target.id);
    }

    /**
     * Sooner run wins even when listed last. NOW is Saturday 12:00 UTC; daily
     * alarms fire tomorrow at their times, so "sooner" (05:15) is 3h45m closer
     * than "later" (09:00) regardless of the runner's timezone.
     */
    @Test
    public void soonerRunWinsRegardlessOfOrder() {
        AccountHandler.UserData userData = new AccountHandler.UserData();
        userData.alarms.add(alarm("later", "09:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        userData.alarms.add(alarm("sooner", "05:15:00", "{\"enabled\":true,\"weekDays\":{}}"));
        var target = FindTargetAlarmBridge.find(userData, NOW);
        assertNotNull(target);
        assertEquals("sooner", target.id);

        // relative comparison in the SAME injected zone: strictly sooner, and
        // exactly 3h45m earlier (both roll to tomorrow in UTC)
        Instant soonerRun = target.computeNextRun(ZONE, NOW);
        Instant laterRun = userData.alarms.get(0).computeNextRun(ZONE, NOW);
        assertTrue(soonerRun.isBefore(laterRun));
        assertEquals(java.time.Duration.ofHours(3).plusMinutes(45),
                java.time.Duration.between(soonerRun, laterRun));
    }

    /**
     * Compile-time bridge: {@code findTargetAlarm} is package-private static in
     * BedSideHandler; same package so tests call it directly through this alias.
     * Always passes the FIXED zone - never the system default.
     */
    private static final class FindTargetAlarmBridge {
        static EightSleepApiClient.@org.eclipse.jdt.annotation.Nullable Alarm find(AccountHandler.UserData userData,
                Instant now) {
            EightSleepApiClient.Alarm result = BedSideHandler.findTargetAlarm(userData, now, ZONE);
            assertFalse("selection must be stable across repeated calls",
                    !resultEquals(result, BedSideHandler.findTargetAlarm(userData, now, ZONE)));
            return result;
        }

        static boolean resultEquals(EightSleepApiClient.@org.eclipse.jdt.annotation.Nullable Alarm a,
                EightSleepApiClient.@org.eclipse.jdt.annotation.Nullable Alarm b) {
            if (a == b) {
                return true;
            }
            return a != null && b != null && java.util.Objects.equals(a.id, b.id);
        }
    }
}
