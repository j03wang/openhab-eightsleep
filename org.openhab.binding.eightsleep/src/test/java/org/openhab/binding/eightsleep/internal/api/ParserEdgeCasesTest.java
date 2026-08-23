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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Edge-case tests for response parsers that the contract tests cover only on
 * their happy path: user profiles, household flattening, filter responses and
 * the alarm update payload builder.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ParserEdgeCasesTest {

    // ==================== parseUserProfile ====================

    @Test
    public void userProfileExtractsCurrentDevice() {
        var result = EightSleepApiClient.parseUserProfile("u1",
                "{\"user\":{\"currentDevice\":{\"side\":\"right\",\"deviceId\":\"dev9\"}}}");
        assertEquals("u1", result.userId());
        assertNotNull(result.currentDevice());
        assertEquals("right", result.currentDevice().side);
        assertEquals("dev9", result.currentDevice().deviceId);
    }

    @Test
    public void userProfileWithoutUserYieldsNullDevice() {
        var result = EightSleepApiClient.parseUserProfile("u1", "{}");
        assertEquals("u1", result.userId());
        assertNull(result.currentDevice());
    }

    @Test
    public void userProfileGarbageBodyDoesNotThrow() {
        var result = EightSleepApiClient.parseUserProfile("u1", "not json at all");
        assertEquals("u1", result.userId());
        assertNull(result.currentDevice());
    }

    // ==================== parseHouseholdDevices ====================

    @Test
    public void householdMissingSectionsAreSkipped() {
        Map<String, String> devices = EightSleepApiClient.parseHouseholdDevices(
                "{\"households\":[{\"name\":\"H\"},{\"sets\":[]},"
                        + "{\"sets\":[{\"devices\":[]},{\"devices\":[{\"deviceId\":\"d1\"}]}]}]}");
        assertEquals(Map.of("d1", "d1"), devices);
    }

    @Test
    public void householdNullHouseholdsYieldsEmpty() {
        assertTrue(EightSleepApiClient.parseHouseholdDevices("{}").isEmpty());
        assertTrue(EightSleepApiClient.parseHouseholdDevices("{\"households\":null}").isEmpty());
    }

    @Test
    public void deviceWithoutNameFallsBackToId() {
        Map<String, String> devices = EightSleepApiClient.parseHouseholdDevices(
                "{\"households\":[{\"sets\":[{\"devices\":[{\"deviceId\":\"d2\",\"deviceName\":\"Bed\"}]}]}]}");
        assertEquals("Bed", devices.get("d2"));
    }

    /** Devices must be flattened in encounter order for deterministic fallback choice. */
    @Test
    public void householdPreservesEncounterOrder() {
        Map<String, String> devices = EightSleepApiClient.parseHouseholdDevices(
                "{\"households\":[{\"sets\":[{\"devices\":[{\"deviceId\":\"z\"},{\"deviceId\":\"a\"},{\"deviceId\":\"m\"}]}]}]}");
        List<String> order = new java.util.ArrayList<>(devices.keySet());
        assertEquals(List.of("z", "a", "m"), order);
    }

    // ==================== parseUserIdsForDevice / getDeviceUsers ====================

    @Test
    public void filterResponseWithNullAwaySidesDefaultsToEmpty() {
        EightSleepApiClient.DeviceUsers users = EightSleepApiClient.parseUserIdsForDevice(
                "{\"result\":{\"leftUserId\":\"l\",\"rightUserId\":\"r\",\"awaySides\":null}}");
        assertEquals("l", users.leftUserId);
        assertEquals("r", users.rightUserId);
        assertTrue(users.awaySides.isEmpty());
    }

    @Test
    public void emptyResultYieldsEmptyUsers() {
        EightSleepApiClient.DeviceUsers users = EightSleepApiClient.parseUserIdsForDevice("{\"result\":null}");
        assertNull(users.leftUserId);
        assertNull(users.rightUserId);
        assertTrue(users.awaySides.isEmpty());
    }

    // ==================== buildAlarmUpdateBody extras ====================

    private static EightSleepApiClient.Alarm fullAlarm() {
        return EightSleepApiClient.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":true,"time":"07:15:30","snoozing":false,
                 "repeat":{"enabled":true,"weekDays":{"monday":true}},
                 "thermal":{"enabled":true,"level":-10.0},
                 "vibration":{"enabled":false,"level":50.5,"duration":300.0},
                 "audio":{"enabled":true,"level":20,"trackId":"waves"},
                 "smart":{"lightSleepEnabled":true},
                 "tags":["routine-x"],"skipNext":false}]}""").get(0);
    }

    @Test
    public void timeOverrideReplacesTime() {
        String json = EightSleepApiClient.buildAlarmUpdateBody(fullAlarm(), null, "06:30:00");
        assertTrue(json.contains("\"time\":\"06:30:00\""));
        assertFalse(json.contains("07:15:30"));
    }

    @Test
    public void enabledOverrideApplied() {
        assertTrue(EightSleepApiClient.buildAlarmUpdateBody(fullAlarm(), true, null).contains("\"enabled\":true"));
        assertTrue(EightSleepApiClient.buildAlarmUpdateBody(fullAlarm(), false, null).contains("\"enabled\":false"));
    }

    @Test
    public void nonWholeDoublesSurviveAsFloats() {
        String json = EightSleepApiClient.buildAlarmUpdateBody(fullAlarm(), null, null);
        assertTrue("50.5 is not a whole number - keep the decimal", json.contains("50.5"));
        assertFalse("-10.0 must be integerized", json.contains("-10.0"));
        assertTrue(json.contains("\"level\":-10"));
        assertTrue("300.0 inside a list-bearing section must also be integerized", json.contains("\"duration\":300"));
    }

    @Test
    public void nullSectionsGetUpstreamDefaults() {
        var bare = new EightSleepApiClient.Alarm();
        bare.id = "bare";
        bare.time = null;
        String json = EightSleepApiClient.buildAlarmUpdateBody(bare, true, null);
        assertTrue(json.contains("\"time\":\"07:00:00\""));
        assertTrue(json.contains("\"repeat\":{}"));
        assertTrue(json.contains("\"thermal\":{}"));
        assertTrue(json.contains("\"vibration\":{}"));
        assertTrue(json.contains("\"audio\":{}"));
        assertTrue(json.contains("\"smart\":{}"));
        assertTrue(json.contains("\"skipNext\":false"));
        assertTrue(json.contains("\"snoozing\":false"));
        assertFalse("tags omitted when absent upstream", json.contains("\"tags\""));
    }

    /**
     * The normalizeNumbers helper must recurse into nested maps AND lists
     * (audio sections carry lists like weekDays values).
     */
    @Test
    public void normalizationRecursesIntoLists() {
        var alarm = EightSleepApiClient.parseAlarms("""
                {"alarms":[{"id":"a1","time":"07:00:00","snoozing":false,
                 "thermal":{"enabled":true,"level":-7.0},
                 "vibration":{"pattern":"RISE","duration":600.0}}]}""").get(0);
        String json = EightSleepApiClient.buildAlarmUpdateBody(alarm, null, null);
        assertTrue(json.contains("\"level\":-7"));
        assertTrue(json.contains("\"duration\":600"));
        assertFalse(json.contains("-7.0") || json.contains("600.0"));
    }
}
