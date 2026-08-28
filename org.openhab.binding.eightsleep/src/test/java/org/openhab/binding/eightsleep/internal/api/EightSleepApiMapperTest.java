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
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceAssignments;

/**
 * Tests API-contract to domain mapping and domain-to-request mapping edge cases.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepApiMapperTest {

    // ==================== parseUserProfile ====================

    @Test
    public void userProfileExtractsCurrentDevice() {
        var result = ApiTestFixtures.parseUserProfile("u1",
                "{\"user\":{\"currentDevice\":{\"side\":\"right\",\"id\":\"dev9\"}}}");
        assertEquals("u1", result.userId());
        assertNotNull(result.currentDevice());
        assertEquals(BedSide.RIGHT, result.currentDevice().side());
        assertEquals("dev9", result.currentDevice().deviceId());
    }

    @Test
    public void userProfileWithoutUserYieldsNullDevice() {
        var result = ApiTestFixtures.parseUserProfile("u1", "{}");
        assertEquals("u1", result.userId());
        assertNull(result.currentDevice());
    }

    @Test(expected = com.google.gson.JsonSyntaxException.class)
    public void userProfileGarbageBodyFailsParsing() {
        ApiTestFixtures.parseUserProfile("u1", "not json at all");
    }

    // ==================== parseHouseholdDevices ====================

    @Test
    public void householdMissingSectionsAreSkipped() {
        Map<String, String> devices = ApiTestFixtures
                .parseHouseholdDevices("{\"households\":[{\"name\":\"H\"},{\"sets\":[]},"
                        + "{\"sets\":[{\"devices\":[]},{\"devices\":[{\"deviceId\":\"d1\"}]}]}]}");
        assertEquals(Map.of("d1", "d1"), devices);
    }

    @Test
    public void householdNullHouseholdsYieldsEmpty() {
        assertTrue(ApiTestFixtures.parseHouseholdDevices("{}").isEmpty());
        assertTrue(ApiTestFixtures.parseHouseholdDevices("{\"households\":null}").isEmpty());
    }

    @Test
    public void deviceWithoutNameFallsBackToId() {
        Map<String, String> devices = ApiTestFixtures.parseHouseholdDevices(
                "{\"households\":[{\"sets\":[{\"devices\":[{\"deviceId\":\"d2\",\"deviceName\":\"Bed\"}]}]}]}");
        assertEquals("Bed", devices.get("d2"));
    }

    /** Devices must be flattened in encounter order for deterministic fallback choice. */
    @Test
    public void householdPreservesEncounterOrder() {
        Map<String, String> devices = ApiTestFixtures.parseHouseholdDevices(
                "{\"households\":[{\"sets\":[{\"devices\":[{\"deviceId\":\"z\"},{\"deviceId\":\"a\"},{\"deviceId\":\"m\"}]}]}]}");
        List<String> order = new java.util.ArrayList<>(devices.keySet());
        assertEquals(List.of("z", "a", "m"), order);
    }

    // ==================== parseUserIdsForDevice / getDeviceUsers ====================

    @Test
    public void filterResponseWithNullAwaySidesDefaultsToEmpty() {
        DeviceAssignments users = ApiTestFixtures
                .parseUserIdsForDevice("{\"result\":{\"leftUserId\":\"l\",\"rightUserId\":\"r\",\"awaySides\":null}}");
        assertEquals("l", users.leftUserId());
        assertEquals("r", users.rightUserId());
        assertTrue(users.awaySides().isEmpty());
    }

    @Test
    public void emptyResultYieldsEmptyUsers() {
        DeviceAssignments users = ApiTestFixtures.parseUserIdsForDevice("{\"result\":null}");
        assertNull(users.leftUserId());
        assertNull(users.rightUserId());
        assertTrue(users.awaySides().isEmpty());
    }

    // ==================== buildAlarmUpdateBody extras ====================

    private static Alarm fullAlarm() {
        return ApiTestFixtures.parseAlarms("""
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
        String json = ApiTestFixtures.buildAlarmUpdateBody(fullAlarm(), null, "06:30:00");
        assertTrue(json.contains("\"time\":\"06:30:00\""));
        assertFalse(json.contains("07:15:30"));
    }

    @Test
    public void enabledOverrideApplied() {
        assertTrue(ApiTestFixtures.buildAlarmUpdateBody(fullAlarm(), true, null).contains("\"enabled\":true"));
        assertTrue(ApiTestFixtures.buildAlarmUpdateBody(fullAlarm(), false, null).contains("\"enabled\":false"));
    }

    @Test
    public void nonWholeDoublesSurviveAsFloats() {
        String json = ApiTestFixtures.buildAlarmUpdateBody(fullAlarm(), null, null);
        assertTrue("50.5 is not a whole number - keep the decimal", json.contains("50.5"));
        assertFalse("-10.0 must be integerized", json.contains("-10.0"));
        assertTrue(json.contains("\"level\":-10"));
        assertTrue("300.0 inside a list-bearing section must also be integerized", json.contains("\"duration\":300"));
    }

    @Test
    public void nullSectionsGetUpstreamDefaults() {
        var bare = new Alarm("bare", null, null, null, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), null, null,
                null);
        String json = ApiTestFixtures.buildAlarmUpdateBody(bare, true, null);
        assertTrue(json.contains("\"time\":\"07:00:00\""));
        assertTrue(json.contains("\"repeat\":{}"));
        assertTrue(json.contains("\"thermal\":{}"));
        assertTrue(json.contains("\"vibration\":{}"));
        assertTrue(json.contains("\"audio\":{}"));
        assertTrue(json.contains("\"smart\":{}"));
        assertTrue(json.contains("\"skipNext\":false"));
        assertTrue(json.contains("\"snoozing\":false"));
        assertTrue("domain collections serialize consistently", json.contains("\"tags\":[]"));
    }

    /**
     * The normalizeNumbers helper must recurse into nested maps AND lists
     * (audio sections carry lists like weekDays values).
     */
    @Test
    public void normalizationRecursesIntoLists() {
        var alarm = ApiTestFixtures.parseAlarms("""
                {"alarms":[{"id":"a1","time":"07:00:00","snoozing":false,
                 "thermal":{"enabled":true,"level":-7.0},
                 "vibration":{"pattern":"RISE","duration":600.0}}]}""").get(0);
        String json = ApiTestFixtures.buildAlarmUpdateBody(alarm, null, null);
        assertTrue(json.contains("\"level\":-7"));
        assertTrue(json.contains("\"duration\":600"));
        assertFalse(json.contains("-7.0") || json.contains("600.0"));
    }
}
