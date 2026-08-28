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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.Alarm;

import com.google.gson.Gson;

/**
 * Selection edge cases for the alarm channel targeting: null handling and the
 * deterministic id tie-break. All cases inject a fixed clock AND a fixed zone,
 * so no assertion depends on the timezone of the machine running the tests.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AlarmSelectorTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    /** Fixed zone for every selection call - CI may run in any timezone. */
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static Alarm alarm(String id, String time, String repeatJson) {
        Alarm.AlarmRepeat repeat = repeatJson != null ? new Gson().fromJson(repeatJson, Alarm.AlarmRepeat.class) : null;
        return new Alarm(id, time != null && !"bogus".equals(time) ? LocalTime.parse(time) : null, null, repeat,
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), null, null, null);
    }

    @Test
    public void noAlarmsYieldsNull() {
        assertNull(AlarmSelector.findTargetAlarm(List.of(), NOW, ZONE));
    }

    /** Alarms whose schedule cannot be computed are excluded from selection. */
    @Test
    public void uncomputableAlarmsExcluded() {
        List<Alarm> alarms = new ArrayList<>();
        alarms.add(alarm("a1", null, "{\"enabled\":true,\"weekDays\":{}}")); // no time -> null run
        alarms.add(alarm("a2", "bogus", "{\"enabled\":true,\"weekDays\":{}}")); // bad time -> null run
        assertNull(AlarmSelector.findTargetAlarm(alarms, NOW, ZONE));
    }

    /** Equal computed runs break the tie on the smaller id, regardless of list order. */
    @Test
    public void equalRunsTieBreakOnSmallestId() {
        List<Alarm> alarms = List.of(alarm("bbb", "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"),
                alarm("aaa", "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        var target = AlarmSelector.findTargetAlarm(alarms, NOW, ZONE);
        assertNotNull(target);
        assertEquals("aaa", target.id());
    }

    /** An alarm with a null id must not win a tie (cannot be toggled anyway). */
    @Test
    public void nullIdLosesTie() {
        List<Alarm> alarms = List.of(alarm(null, "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"),
                alarm("zzz", "07:00:00", "{\"enabled\":true,\"weekDays\":{}}"));
        var target = AlarmSelector.findTargetAlarm(alarms, NOW, ZONE);
        assertNotNull(target);
        assertEquals("zzz", target.id());
    }

    /**
     * Sooner run wins even when listed last. NOW is Saturday 12:00 UTC; daily
     * alarms fire tomorrow at their times, so "sooner" (05:15) is 3h45m closer
     * than "later" (09:00) regardless of the runner's timezone.
     */
    @Test
    public void soonerRunWinsRegardlessOfOrder() {
        List<Alarm> alarms = List.of(alarm("later", "09:00:00", "{\"enabled\":true,\"weekDays\":{}}"),
                alarm("sooner", "05:15:00", "{\"enabled\":true,\"weekDays\":{}}"));
        var target = AlarmSelector.findTargetAlarm(alarms, NOW, ZONE);
        assertNotNull(target);
        assertEquals("sooner", target.id());

        // relative comparison in the SAME injected zone: strictly sooner, and
        // exactly 3h45m earlier (both roll to tomorrow in UTC)
        Instant soonerRun = target.computeNextRun(ZONE, NOW);
        Instant laterRun = alarms.get(0).computeNextRun(ZONE, NOW);
        assertTrue(soonerRun.isBefore(laterRun));
        assertEquals(java.time.Duration.ofHours(3).plusMinutes(45), java.time.Duration.between(soonerRun, laterRun));
    }

    @Test
    public void selectedAlarmChannelsAreRetained() {
        assertFalse(AlarmSelector.shouldClearAlarmChannels(true, 0, null, NOW, 30));
        assertFalse(AlarmSelector.shouldClearAlarmChannels(true, 5, NOW, NOW, 30));
    }

    @Test
    public void freshEmptyPollClearsAlarmChannels() {
        assertTrue(AlarmSelector.shouldClearAlarmChannels(false, 0, NOW, NOW, 30));
    }

    @Test
    public void stalePublishedAlarmChannelsAreCleared() {
        Instant old = NOW.minusSeconds(3600);
        assertTrue(AlarmSelector.shouldClearAlarmChannels(false, 3, old, NOW, 30));
        assertFalse(AlarmSelector.shouldClearAlarmChannels(false, 0, old, NOW, 30));
        assertFalse(AlarmSelector.shouldClearAlarmChannels(false, 0, null, NOW, 30));
    }
}
