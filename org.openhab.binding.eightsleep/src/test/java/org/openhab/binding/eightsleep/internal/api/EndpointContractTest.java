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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;

import com.google.gson.JsonArray;

/**
 * Contract tests: assert that our parsers handle REAL captured response shapes for
 * every Eight Sleep endpoint we consume.
 * <p>
 * Capture live payloads with the standalone script, then run:
 *
 * <pre>
 * python3 tools/capture_fixtures.py me@example.com mypassword -o fixtures
 * mvn test -Deightsleep.fixtures=fixtures
 * </pre>
 *
 * When a fixture file exists it takes precedence over the embedded sample below.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EndpointContractTest {

    private static final String FIXTURE_DIR = System.getProperty("eightsleep.fixtures", "target/test-data");

    /** True when a live capture exists for this endpoint (assert shape, not values). */
    private static boolean isLive(String name) {
        return java.nio.file.Files.exists(java.nio.file.Path.of(FIXTURE_DIR, name + ".json"));
    }

    /** Loads a fixture by name, preferring a live capture over the embedded sample. */
    private static String fixture(String name, @Nullable String embeddedSample) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(FIXTURE_DIR, name + ".json");
            if (java.nio.file.Files.exists(p)) {
                return java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // fall through to sample
        }
        assertNotNull("No fixture file or embedded sample for '" + name + "'", embeddedSample);
        return embeddedSample;
    }

    // ==================== /v1/tokens (auth) ====================
    // Shape per upstream: snake_case OAuth fields at top level.

    @Test
    public void authRequestSerializesSnakeCase() {
        TokenManager.AuthRequest request = TokenManager.AuthRequest.of("cid", "secret", "me@x.com", "pw");
        String json = GsonHelper.toJson(request);
        assertNotNull(json);
        assertTrue("must use grant_type", json.contains("\"grant_type\":\"password\""));
        assertTrue("must use client_id", json.contains("\"client_id\":\"cid\""));
        assertTrue("must not leak camelCase", !json.contains("grantType"));
    }

    // ==================== GET /v1/devices/{id} ====================

    @Test
    public void deviceDataUnwrapsResultEnvelope() {
        String body = """
                {"result":{"needsPriming":false,"leftHeatingLevel":10,"leftTargetHeatingLevel":-20,
                  "leftNowHeating":true,"leftHeatingDuration":3600,"hasWater":true,"priming":false,
                  "lastPrime":"2026-08-01T06:00:00.000Z","modelString":"Pod4","firmwareVersion":"fw1",
                  "ledBrightnessLevel":50,"sensorInfo":{"hwRevision":"hw2"},
                  "features":["cooling","elevation"]}}""";
        body = fixture("devices-id", body);

        boolean live = isLive("devices-id");
        org.openhab.binding.eightsleep.internal.model.DeviceData data = EightSleepApiClient.parseDeviceData(body);
        if (live) {
            // live capture: assert the fields we rely on are present
            assertNotNull("leftHeatingLevel must parse", data.leftHeatingLevel);
            assertNotNull("hasWater must parse", data.hasWater);
            assertTrue("rawFieldNames captured", !data.rawFieldNames.isEmpty());
            return;
        }
        assertEquals(Double.valueOf(10), data.leftHeatingLevel);
        assertEquals(Double.valueOf(-20), data.leftTargetHeatingLevel);
        assertEquals(Boolean.TRUE, data.hasWater);
        assertEquals(Boolean.TRUE, data.leftNowHeating);
        assertTrue(data.isPod());
        assertTrue(data.hasBase());
    }

    // ==================== GET /users/{id}/trends (v2) ====================
    // Real shape (per live capture): {"days":[{presenceStart, presenceEnd, score, tnt,
    // sleepQualityScore:{total, hrv:{current,...}, respiratoryRate:{current,...}},
    // sleepRoutineScore:{...}, sessions:[{sleepStart, sleepEnd, stages,
    // timeseries:{tempBedC:[[ts,val]],...}}]}]} - there is NO "processing" flag.

    @Test
    public void trendsDaysExtractedAndSessionsNavigated() {
        String body = fixture("trends-days", """
                {"days":[
                  {"score":72,"tnt":12,
                   "presenceStart":"2026-08-21T23:30:00Z","presenceEnd":"2026-08-22T07:00:00Z",
                   "lightDuration":9000,"deepDuration":6000,"remDuration":4000,
                   "sleepDuration":18000,"presenceDuration":27000,
                   "sleepQualityScore":{"total":88,"hrv":{"current":41.5},
                     "respiratoryRate":{"current":13.2}},
                   "sleepRoutineScore":{"total":91},
                   "sessions":[{"timeseries":{
                      "tempBedC":[["2026-08-21T23:30:00Z","None"],["2026-08-22T00:00:00Z",29.5]],
                      "tempRoomC":[["2026-08-21T23:30:00Z","None"],["2026-08-22T00:00:00Z",21.3]],
                      "heartRate":[["2026-08-21T23:30:00Z","None"],["2026-08-22T00:00:00Z",58]]}}]},
                  {"score":80,"tnt":9,
                   "presenceStart":"2026-08-22T23:00:00Z",
                   "sleepQualityScore":{"total":90,"hrv":{"current":44}},
                   "sessions":[{"timeseries":{
                      "heartRate":[["2026-08-22T23:05:00Z",62]]}}]}
                ]}""");
        boolean trendsLive = isLive("trends-days");
        JsonArray days = EightSleepApiClient.parseTrendDays(body);
        assertTrue("at least one day", days.size() >= 1);

        org.openhab.binding.eightsleep.internal.model.TrendParser parser =
                new org.openhab.binding.eightsleep.internal.model.TrendParser(days);

        // latest day = index 0 from the end
        var currentDay = parser.getDay(0);
        assertNotNull(currentDay);
        Double score = org.openhab.binding.eightsleep.internal.model.TrendParser.getDouble(currentDay, "score");
        Instant dayStart = org.openhab.binding.eightsleep.internal.model.TrendParser.parseTimestamp(
                org.openhab.binding.eightsleep.internal.model.TrendParser.getString(currentDay, "presenceStart"));
        if (!trendsLive) {
            assertEquals(Double.valueOf(80), score);
            assertNotNull(dayStart);
        } else {
            assertNotNull("live day must carry a score", score);
            assertNotNull("live day must carry presenceStart", dayStart);
        }

        // quality sub-scores are nested objects with "current"
        var quality = org.openhab.binding.eightsleep.internal.model.TrendParser.getObject(currentDay,
                "sleepQualityScore");
        assertNotNull("sleepQualityScore object required", quality);
        Double hrvCurrent = org.openhab.binding.eightsleep.internal.model.TrendParser
                .getDouble(org.openhab.binding.eightsleep.internal.model.TrendParser.getObject(quality, "hrv"),
                        "current");
        if (!trendsLive) {
            assertEquals(Double.valueOf(90),
                    org.openhab.binding.eightsleep.internal.model.TrendParser.getDouble(quality, "total"));
            assertEquals(Double.valueOf(44), hrvCurrent);
        }

        // timeseries live on the session: string timestamps + "None" values tolerated
        var session = parser.getCurrentSession();
        if (session != null && !trendsLive) {
            assertEquals(Double.valueOf(62),
                    org.openhab.binding.eightsleep.internal.model.TrendParser.latestSeriesValue(session, "heartRate"));
            assertNull(org.openhab.binding.eightsleep.internal.model.TrendParser.latestSeriesValue(session, "tempRoomC"));
        }

        // previous day carries the completed sleep summary + temps (when present)
        var previousDay = parser.getDay(1);
        if (!trendsLive) {
            assertNotNull(previousDay);
            assertEquals(Double.valueOf(72), org.openhab.binding.eightsleep.internal.model.TrendParser
                    .getDouble(previousDay, "score"));
            assertEquals(Double.valueOf(12), org.openhab.binding.eightsleep.internal.model.TrendParser
                    .getDouble(previousDay, "tnt"));
            var previousSession = parser.getCurrentSessionOf(previousDay);
            assertEquals(Double.valueOf(21.3),
                    org.openhab.binding.eightsleep.internal.model.TrendParser.latestSeriesValue(
                            previousSession, "tempRoomC"));
            assertEquals(Double.valueOf(29.5),
                    org.openhab.binding.eightsleep.internal.model.TrendParser.latestSeriesValue(
                            previousSession, "tempBedC"));
            assertEquals(Double.valueOf(9000), org.openhab.binding.eightsleep.internal.model.TrendParser
                    .getDouble(previousDay, "lightDuration"));
        }
    }

    // ==================== GET /v2/users/{id}/alarms ====================

    @Test
    public void alarmsParseWithNestedSettings() {
        String body = fixture("alarms-v2", """
                {"alarms":[{
                    "id":"6bd72e29-f6ee-432a-b180-721f281d2659",
                    "enabled":true,"time":"06:45:00","snoozing":false,
                    "repeat":{"enabled":true,"weekDays":{"monday":true,"tuesday":true}},
                    "thermal":{"enabled":true,"level":-10},
                    "vibration":{"enabled":true,"level":50,"pattern":"rise","duration":300},
                    "nextTimestamp":"2026-08-25T06:40:00Z"
                  }],
                 "recommendedAlarm":{}}""");

        boolean alarmsLive = isLive("alarms-v2");
        List<EightSleepApiClient.Alarm> alarms = EightSleepApiClient.parseAlarms(body);
        if (!alarmsLive) {
            assertEquals(1, alarms.size());
            EightSleepApiClient.Alarm alarm = alarms.get(0);
            assertEquals("6bd72e29-f6ee-432a-b180-721f281d2659", alarm.id);
            assertEquals(Boolean.TRUE, alarm.enabled);
            assertEquals("06:45:00", alarm.time);
        } else {
            assertTrue("live account has alarms", alarms.size() >= 1);
        }
        // both modes: every alarm must expose the fields the toggle payload needs
        for (EightSleepApiClient.Alarm alarm : alarms) {
            assertNotNull(alarm.id);
            assertNotNull(alarm.time);
            assertTrue(alarm.thermal != null || alarm.vibration != null || alarm.repeat != null);
        }
    }

    /**
     * Regression: the toggle payload must be the BARE alarm object (no
     * "alarmSettings" wrapper) and emit whole numbers as JSON integers (the
     * backend rejects -10.0 for Int32 fields).
     */
    @Test
    public void alarmTogglePayloadIsBareAndIntegerized() throws IOException {
        List<EightSleepApiClient.Alarm> alarms = EightSleepApiClient.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":true,"time":"07:00:00","snoozing":false,
                 "repeat":{"enabled":false},"thermal":{"enabled":true,"level":-10.0},
                 "vibration":{"enabled":true,"level":50.0}}]}""");

        // exercise the REAL production body builder (not a simulation)
        String json = EightSleepApiClient.buildAlarmUpdateBody(alarms.get(0), false, null);
        assertTrue("bare object shape (verified live)", !json.contains("alarmSettings"));
        assertTrue("thermal.level must be integer", json.contains("\"level\":-10") && !json.contains("-10.0"));
        assertTrue("vibration.level must be integer",
                json.contains("\"level\":50") && !json.contains("50.0"));

        // live-shape regression (only when fixtures are provided): every client-settable
        // section must survive the round-trip
        if (!isLive("alarms-v2")) {
            return;
        }
        String liveBody = java.nio.file.Files.readString(java.nio.file.Path.of(
                java.lang.System.getProperty("eightsleep.fixtures", "target/test-data"),
                "alarms-v2.json"), StandardCharsets.UTF_8);
        EightSleepApiClient.Alarm liveAlarm = EightSleepApiClient.parseAlarms(liveBody).get(0);
        String liveJson = EightSleepApiClient.buildAlarmUpdateBody(liveAlarm, true, null);
        for (String required : new String[] { "\"audio\"", "\"smart\"", "\"tags\"", "\"skipNext\"" }) {
            assertTrue("live alarm body must retain " + required, liveJson.contains(required));
        }
        assertNotNull("fixture alarm must carry an id", liveAlarm.id);
        assertTrue("body id must equal the path id that would be used",
                liveJson.contains("\"id\":\"" + liveAlarm.id + "\""));
    }

    // ==================== GET /users/me & household summary ====================

    @Test
    public void currentUserParsed() {
        String body = fixture("users-me", """
                {"user":{"userId":"u_abc123","devices":["dev1"],"email":"me@x.com"}}""");
        String userId = EightSleepApiClient.parseCurrentUserId(body);
        if (isLive("users-me")) {
            assertTrue("live userId should look like a hex id", userId.length() >= 16);
        } else {
            assertEquals("u_abc123", userId);
        }
    }

    @Test
    public void householdDevicesFlattened() {
        String body = fixture("household-summary", """
                {"households":[{"name":"Home","sets":[
                  {"devices":[{"deviceId":"dev1","deviceName":"Master Pod"},
                              {"deviceId":"dev2"}]}]}]}""");
        boolean houseLive = isLive("household-summary");
        Map<String, String> devices = EightSleepApiClient.parseHouseholdDevices(body);
        if (!houseLive) {
            assertEquals(2, devices.size());
            assertEquals("Master Pod", devices.get("dev1"));
            assertEquals("dev2", devices.get("dev2"));
        } else {
            assertTrue("live account has at least one device", devices.size() >= 1);
        }
    }

    // ==================== GET /devices/{id}?filter=... ====================

    @Test
    public void deviceUsersIncludingAwaySides() {
        String body = fixture("device-users", """
                {"result":{"leftUserId":"u_left","rightUserId":null,
                 "awaySides":{"left":"u_away"}}}""");
        EightSleepApiClient.DeviceUsers users = EightSleepApiClient.parseUserIdsForDevice(body);
        if (isLive("device-users")) {
            // live: away users are REMOVED from side slots, so leftUserId may be null.
            // Verify the away rule instead: awaySides-listed + no side slot = away.
            // (A capture where nobody is away has an empty map - only assert the rule
            // when there is actually an entry, and never assume the map is non-empty.)
            assertFalse("awaySides must default to a non-null map", users.awaySides == null);
            if (!users.awaySides.isEmpty()) {
                String awayId = users.awaySides.values().iterator().next();
                assertTrue("away user must resolve via isAway", users.isAway(awayId));
            }
            return;
        }
        assertEquals("u_left", users.leftUserId);
        assertTrue("away membership is by value", users.awaySides.containsValue("u_away"));
    }

    // ==================== GET /temperature/all (pillow) ====================

    @Test
    public void pillowDataSideMatching() {
        String body = fixture("temperature-all", """
                {"devices":[
                  {"device":{"specialization":"pod","side":"left","deviceId":"dev1"},"currentLevel":0},
                 {"device":{"specialization":"pillow","side":"left","deviceId":"pil1"},
                  "currentLevel":-15,"currentState":{"type":"smart"}}
                ]}""");
        EightSleepApiClient.PillowData data = EightSleepApiClient.parsePillowData(body);
        if (isLive("temperature-all")) {
            // live capture: shape check only (a pod without a pillow has no pillow entry)
            assertNotNull(data.devices);
            return;
        }
        EightSleepApiClient.PillowEntry pillow = data.findPillow("left");
        assertNotNull(pillow);
        assertTrue(pillow.isOn());
        assertEquals(-15, pillow.getLevel());
        assertTrue(data.containsPod("dev1"));
    }

    // ==================== GET /v1/users/{id}/temperature ====================

    @Test
    public void temperatureResourceParsed() {
        String body = fixture("temperature", """
                {"currentLevel":-20,"currentState":{"type":"smart"},
                 "smart":{"bedTimeLevel":-32,"initialSleepLevel":-24,"finalSleepLevel":-12}}""");
        com.google.gson.JsonObject temp = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        boolean live = isLive("temperature");
        assertNotNull(org.openhab.binding.eightsleep.internal.model.TrendParser.getObject(temp, "currentState"));
        if (!live) {
            assertEquals(Double.valueOf(-20), org.openhab.binding.eightsleep.internal.model.TrendParser
                    .getDouble(temp, "currentLevel"));
            assertEquals("smart", org.openhab.binding.eightsleep.internal.model.TrendParser.getString(
                    org.openhab.binding.eightsleep.internal.model.TrendParser.getObject(temp, "currentState"),
                    "type"));
            // Autopilot fallback source for the target-temperature channel
            assertEquals(Double.valueOf(-32),
                    org.openhab.binding.eightsleep.internal.model.TrendParser.getDouble(
                            org.openhab.binding.eightsleep.internal.model.TrendParser.getObject(temp, "smart"),
                            "bedTimeLevel"));
        } else {
            // live shape check only
            assertTrue(temp.size() >= 1);
        }
    }

    // ==================== GET /v1/users/{id}/base ====================

    @Test
    public void baseDataParsed() {
        String body = fixture("base-data", """
                {"left":{"preset":{"name":"sleep"},"torso":{"currentAngle":30},
                  "leg":{"currentAngle":8},"inSnoreMitigation":false},
                 "right":{"preset":{"name":"reading"},"torso":{"currentAngle":10}}}""");
        org.openhab.binding.eightsleep.internal.model.BaseData data = EightSleepApiClient.parseBaseData(body);
        if (isLive("base-data")) {
            // live capture can be an error body ("NoPairedPod"): parser must not throw
            // and must degrade to an empty-but-valid object.
            assertNotNull(data);
            return;
        }
        assertEquals("sleep", data.getSide("left").preset.name);
        assertEquals(Integer.valueOf(30), data.getSide("left").torso.currentAngle);
        assertEquals(Boolean.FALSE, data.getSide("left").inSnoreMitigation);
        assertEquals("reading", data.getSide("right").preset.name);
    }

    /** Error bodies for accounts without a paired pod/base parse to an empty object. */
    @Test
    public void baseDataErrorBodyYieldsEmptyObject() {
        org.openhab.binding.eightsleep.internal.model.BaseData data = EightSleepApiClient
                .parseBaseData("{\"message\":\"User not paired to device\",\"errorType\":\"NoPairedPod\"}");
        assertNull(data.getSide("left"));
        assertNull(data.getSide("right"));
    }

    // ==================== GET /v1/users/{id}/audio/player ====================

    @Test
    public void playerStateParsed() {
        String body = fixture("player-state", """
                {"state":"Playing","volume":30,
                 "currentTrack":{"id":"t1","name":"Rain","categoryId":"nature",
                   "currentPosition":12.5,"trackDuration":600.0},
                 "hardwareInfo":{"sku":"POD5","hardwareVersion":"hw1","softwareVersion":"sw1"}}""");
        org.openhab.binding.eightsleep.internal.model.PlayerState state = EightSleepApiClient.parsePlayerState(body);
        if (isLive("player-state")) {
            // live capture can be a 404 error body (no speaker): must degrade gracefully
            assertNotNull(state);
            return;
        }
        assertTrue(state.isPlaying());
        assertFalse(state.isPaused());
        assertEquals(30, state.getVolumePercent());
        assertTrue(state.hasSpeaker());
        assertEquals("Rain", state.currentTrack.name);
    }
}
