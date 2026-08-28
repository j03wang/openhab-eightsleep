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
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceAssignments;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.model.PillowEntry;
import org.openhab.binding.eightsleep.internal.model.PillowState;

/**
 * Spec-first contract tests for every Eight Sleep endpoint the binding consumes.
 * <p>
 * Each test encodes the binding's <em>expectations</em> - the fields and shapes
 * derived from the upstream API that the parsers must satisfy. The same body of
 * assertions runs in two modes:
 * <ul>
 * <li><b>Embedded sample</b> (no fixtures): exact-value assertions against a
 * minimal payload exercising every parser branch.</li>
 * <li><b>Live capture</b> (fixture present, captured via
 * {@code tools/capture_fixtures.py}): the same expectations are evaluated
 * against the real response, plus fixture-derived invariants (e.g. "every
 * alarm round-trips through the update-body builder", "timeseries values parse
 * as numbers or are skipped").</li>
 * </ul>
 *
 * Run with live captures:
 *
 * <pre>
 * python3 tools/capture_fixtures.py me@example.com mypassword -o fixtures
 * mvn test -Deightsleep.fixtures=fixtures
 * </pre>
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
    // Spec: the OAuth token endpoint requires snake_case form fields at top level.

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
    // Spec: {"result":{...}} envelope with per-side heating level/target/now-heating,
    // hasWater, priming state, LED brightness and model/firmware identity.

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
        DeviceState data = ApiTestFixtures.parseDeviceData(body);
        if (!live) {
            assertEquals(Double.valueOf(10), data.leftHeatingLevel());
            assertEquals(Double.valueOf(-20), data.leftTargetHeatingLevel());
            assertEquals(Boolean.TRUE, data.hasWater());
            assertEquals(Boolean.TRUE, data.leftNowHeating());
            assertTrue(data.isPod());
            assertTrue(data.hasBase());
            return;
        }
        // Live invariants: whatever the account's pod reports, every field the
        // binding publishes channels from must be present and internally consistent.
        assertNotNull("heating levels drive bed-temperature fallback", data.leftHeatingLevel());
        assertNotNull(data.rightHeatingLevel());
        assertNotNull("water status drives the hasWater channel", data.hasWater());
        assertNotNull("LED level drives the brightness channel", data.ledBrightnessLevel());
        if (data.leftTargetHeatingLevel() != null && data.leftNowHeating() != null) {
            assertFalse("an actively heating side must report a target level",
                    data.leftNowHeating().booleanValue() && data.leftTargetHeatingLevel() == 0);
        }
    }

    // ==================== GET /users/{id}/trends (v2) ====================
    // Spec: {"days":[...]} newest-first; day carries score/tnt/durations and
    // presenceStart/End; sleepQualityScore nests hrv/respiratoryRate "current"
    // values; sessions[].timeseries maps series names to [isoTimestamp, value]
    // pairs where value may be the literal string "None" or a number.

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
        var trends = ApiTestFixtures.parseTrendDays(body);
        assertTrue("at least one day", !trends.days().isEmpty());

        var currentDay = trends.getDay(0);
        assertNotNull(currentDay);
        Double score = currentDay.score();
        Instant dayStart = currentDay.presenceStart();
        Instant dayEnd = currentDay.presenceEnd();

        assertNotNull("score drives the sleep-score channel", score);
        assertNotNull("presenceStart anchors session timing", dayStart);
        if (!trendsLive) {
            assertEquals(Double.valueOf(80), score);
        } else if (dayEnd != null) {
            assertFalse("presence window must be chronological", dayEnd.isBefore(dayStart));
        }

        var quality = currentDay.sleepQualityScore();
        assertNotNull("sleepQualityScore object required", quality);
        Double qualityTotal = quality.total();
        Double hrvCurrent = quality.hrv();
        assertNotNull("quality.total drives routine channels", qualityTotal);
        if (!trendsLive) {
            assertEquals(Double.valueOf(90), qualityTotal);
            assertEquals(Double.valueOf(44), hrvCurrent);
        }

        var session = trends.getCurrentSession();
        if (session != null) {
            Double hr = session.latestValue("heartRate");
            if (!trendsLive) {
                assertEquals(Double.valueOf(62), hr);
                assertNull(session.latestValue("tempRoomC"));
            } else if (hr != null) {
                assertTrue("heart rate readings stay physiological", hr >= 20 && hr <= 250);
            }
        }

        var previousDay = trends.getDay(1);
        if (!trendsLive) {
            assertNotNull(previousDay);
            assertEquals(Double.valueOf(72), previousDay.score());
            assertEquals(Double.valueOf(12), previousDay.tossAndTurns());
            var previousSession = org.openhab.binding.eightsleep.internal.model.TrendData.currentSessionOf(previousDay);
            assertEquals(Double.valueOf(21.3), previousSession.latestValue("tempRoomC"));
            assertEquals(Double.valueOf(29.5), previousSession.latestValue("tempBedC"));
            assertEquals(Double.valueOf(9000), previousDay.lightDuration());
        } else if (previousDay != null) {
            Double prevScore = previousDay.score();
            String curDay = currentDay.day();
            String prevDayName = previousDay.day();
            if (curDay != null && prevDayName != null) {
                assertTrue("days must be ordered newest-first", curDay.compareTo(prevDayName) >= 0);
            }
            assertNotNull("previous day should also carry a score", prevScore);
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

        List<Alarm> alarms = ApiTestFixtures.parseAlarms(body);
        if (!isLive("alarms-v2")) {
            assertEquals(1, alarms.size());
            Alarm alarm = alarms.get(0);
            assertEquals("6bd72e29-f6ee-432a-b180-721f281d2659", alarm.id());
            assertEquals(Boolean.TRUE, alarm.enabled());
            assertEquals(java.time.LocalTime.of(6, 45), alarm.time());
        }
        // Both modes: every alarm exposes exactly what the toggle builder and the
        // alarm scheduler need - id, HH:mm:ss time, at least one behaviour section.
        for (Alarm alarm : alarms) {
            assertNotNull(alarm.id());
            assertNotNull(alarm.time());
            assertTrue(!alarm.thermal().isEmpty() || !alarm.vibration().isEmpty() || alarm.repeat() != null);
        }
    }

    /**
     * Regression: the toggle payload must be the BARE alarm object (no
     * "alarmSettings" wrapper) and emit whole numbers as JSON integers (the
     * backend rejects -10.0 for Int32 fields).
     */
    @Test
    public void alarmTogglePayloadIsBareAndIntegerized() throws IOException {
        List<Alarm> alarms = ApiTestFixtures.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":true,"time":"07:00:00","snoozing":false,
                 "repeat":{"enabled":false},"thermal":{"enabled":true,"level":-10.0},
                 "vibration":{"enabled":true,"level":50.0}}]}""");

        String json = ApiTestFixtures.buildAlarmUpdateBody(alarms.get(0), false, null);
        assertTrue("bare object shape (verified live)", !json.contains("alarmSettings"));
        assertTrue("thermal.level must be integer", json.contains("\"level\":-10") && !json.contains("-10.0"));
        assertTrue("vibration.level must be integer", json.contains("\"level\":50") && !json.contains("50.0"));

        // Fixture-derived invariant: EVERY alarm of a live capture must survive a
        // full parse -> modify -> serialize round-trip with its client-settable
        // sections intact, because any of them can be the toggle target.
        if (!isLive("alarms-v2")) {
            return;
        }
        String liveBody = java.nio.file.Files.readString(java.nio.file.Path.of(FIXTURE_DIR, "alarms-v2.json"),
                StandardCharsets.UTF_8);
        List<Alarm> liveAlarms = ApiTestFixtures.parseAlarms(liveBody);
        assertTrue("live capture has alarms", !liveAlarms.isEmpty());
        for (Alarm liveAlarm : liveAlarms) {
            String liveJson = ApiTestFixtures.buildAlarmUpdateBody(liveAlarm, true, null);
            assertNotNull("fixture alarm must carry an id", liveAlarm.id());
            assertTrue("body id must equal the path id used for PUT",
                    liveJson.contains("\"id\":\"" + liveAlarm.id() + "\""));
            assertTrue("audio section survives round-trip", liveJson.contains("\"audio\""));
            assertTrue("smart section survives round-trip", liveJson.contains("\"smart\""));
            assertTrue("tags section survives round-trip", liveJson.contains("\"tags\""));
            assertFalse("server-computed fields stay stripped", liveJson.contains("nextTimestamp")
                    || liveJson.contains("dismissedUntil") || liveJson.contains("snoozedUntil"));
            assertFalse("no whole-number doubles leak into Int32 fields",
                    liveJson.matches("(?s).*\"level\":-?\\d+\\.0.*"));
        }
    }

    // ==================== GET /users/me & household summary ====================

    @Test
    public void currentUserParsed() {
        String body = fixture("users-me", """
                {"user":{"userId":"u_abc123","devices":["dev1"],"email":"me@x.com"}}""");
        String userId = ApiTestFixtures.parseCurrentUserId(body);
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
        Map<String, String> devices = ApiTestFixtures.parseHouseholdDevices(body);
        if (!isLive("household-summary")) {
            assertEquals(2, devices.size());
            assertEquals("Master Pod", devices.get("dev1"));
            assertEquals("dev2", devices.get("dev2"));
            return;
        }
        // Live invariant: every set device surfaces with a stable non-blank id -
        // chooseDeviceId picks the sorted-first entry, so ids must be usable.
        assertFalse("live account exposes at least one device", devices.isEmpty());
        for (Map.Entry<String, String> e : devices.entrySet()) {
            assertFalse("device ids must be non-blank", e.getKey().isBlank());
            assertFalse("device ids must not contain path separators", e.getKey().contains("/"));
        }
    }

    // ==================== GET /devices/{id}?filter=... ====================

    @Test
    public void deviceUsersIncludingAwaySides() {
        String body = fixture("device-users", """
                {"result":{"leftUserId":"u_left","rightUserId":null,
                 "awaySides":{"left":"u_away"}}}""");
        DeviceAssignments users = ApiTestFixtures.parseUserIdsForDevice(body);
        // Expectation (both modes): away membership is decided ONLY by side-slot
        // occupancy - an awaySides listing never makes an occupying user away,
        // and awaySides defaults to an empty map rather than null.
        assertNotNull(users.awaySides());
        if (!users.awaySides().isEmpty()) {
            String awayId = users.awaySides().values().iterator().next();
            boolean occupiesSlot = awayId.equals(users.leftUserId()) || awayId.equals(users.rightUserId());
            assertEquals("away == listed AND slot-free", !occupiesSlot, users.isAway(awayId));
        }
        if (isLive("device-users")) {
            return;
        }
        assertEquals("u_left", users.leftUserId());
        assertTrue("away membership is by value", users.awaySides().containsValue("u_away"));
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
        PillowState data = ApiTestFixtures.parsePillowData(body);
        // Both modes: parsing never throws; absence of pillows degrades to an
        // empty list, never null.
        assertNotNull(data.devices());
        if (isLive("temperature-all")) {
            for (PillowEntry entry : data.devices()) {
                assertNotNull("every entry identifies its specialization", entry.specialization());
                Integer level = entry.level();
                if (entry.isPillow() && level != null) {
                    assertTrue("pillow levels stay in percent range", level >= -100 && level <= 100);
                }
            }
            return;
        }
        PillowEntry pillow = data.findPillow(BedSide.LEFT);
        assertNotNull(pillow);
        assertTrue(pillow.isOn());
        assertEquals(-15, pillow.level().intValue());
        assertTrue(data.containsPod("dev1"));
    }

    // ==================== GET /v1/users/{id}/temperature ====================

    @Test
    public void temperatureResourceParsed() {
        String body = fixture("temperature", """
                {"currentLevel":-20,"currentState":{"type":"smart"},
                 "smart":{"bedTimeLevel":-32,"initialSleepLevel":-24,"finalSleepLevel":-12}}""");
        var temp = ApiTestFixtures.parseTemperature(body);
        assertNotNull(temp.stateType());
        boolean live = isLive("temperature");
        if (!live) {
            assertEquals(Double.valueOf(-20), temp.currentLevel());
            assertEquals("smart", temp.stateType());
        }
        // Expectation (both modes): the autopilot baseline triple must parse so
        // target-temperature commands have a fallback when no manual level is set.
        if (!temp.smart().isEmpty()) {
            Double bedtime = temp.smartLevel("bedTimeLevel");
            Double initial = temp.smartLevel("initialSleepLevel");
            Double finall = temp.smartLevel("finalSleepLevel");
            if (!live) {
                assertEquals(Double.valueOf(-32), bedtime);
            } else if (bedtime != null && initial != null && finall != null) {
                assertTrue("bedtime is the coldest phase", bedtime <= initial && bedtime <= finall);
            }
        }
    }

    // ==================== GET /v1/users/{id}/base ====================

    @Test
    public void baseDataParsed() {
        String body = fixture("base-data", """
                {"left":{"preset":{"name":"sleep"},"torso":{"currentAngle":30},
                  "leg":{"currentAngle":8},"inSnoreMitigation":false},
                 "right":{"preset":{"name":"reading"},"torso":{"currentAngle":10}}}""");
        org.openhab.binding.eightsleep.internal.model.BaseState data = ApiTestFixtures.parseBaseData(body);
        if (isLive("base-data")) {
            // Live captures include error bodies ("NoPairedPod"): the parser must
            // degrade to an empty-but-valid object instead of throwing.
            assertNull("error body yields no phantom sides", data.side(BedSide.LEFT));
            assertNull(data.side(BedSide.RIGHT));
            return;
        }
        assertEquals("sleep", data.side(BedSide.LEFT).presetName());
        assertEquals(Integer.valueOf(30), data.side(BedSide.LEFT).torsoAngle());
        assertEquals(Boolean.FALSE, data.side(BedSide.LEFT).inSnoreMitigation());
        assertEquals("reading", data.side(BedSide.RIGHT).presetName());
    }

    /** Error bodies for accounts without a paired pod/base parse to an empty object. */
    @Test
    public void baseDataErrorBodyYieldsEmptyObject() {
        org.openhab.binding.eightsleep.internal.model.BaseState data = ApiTestFixtures
                .parseBaseData("{\"message\":\"User not paired to device\",\"errorType\":\"NoPairedPod\"}");
        assertNull(data.side(BedSide.LEFT));
        assertNull(data.side(BedSide.RIGHT));
    }

    // ==================== GET /v1/users/{id}/audio/player ====================

    @Test
    public void playerStateParsed() {
        String body = fixture("player-state", """
                {"state":"Playing","volume":30,
                 "currentTrack":{"id":"t1","name":"Rain","categoryId":"nature",
                   "currentPosition":12.5,"trackDuration":600.0},
                 "hardwareInfo":{"sku":"POD5","hardwareVersion":"hw1","softwareVersion":"sw1"}}""");
        org.openhab.binding.eightsleep.internal.model.PlayerState state = ApiTestFixtures.parsePlayerState(body);
        if (isLive("player-state")) {
            // Live captures include 404 bodies (no speaker): absence must surface
            // as a speaker-less state, not fabricated defaults.
            assertFalse("404 body must not fabricate hardware", state.hasSpeaker());
            assertNull("absent volume stays unknown", state.volume());
            return;
        }
        assertTrue(state.isPlaying());
        assertFalse(state.isPaused());
        assertEquals(30, state.volume().intValue());
        assertTrue(state.hasSpeaker());
        assertEquals("Rain", state.currentTrack().name());
    }
}
