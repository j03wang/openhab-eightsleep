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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Regression tests for every contract bug encountered while building the binding.
 * Each test names the symptom it guards against.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class RegressionTest {

    private static final String USER = "user_a1b2c3d4e5f6g7h8";

    // ==================== auth ====================

    /**
     * Bug: the token request serialized camelCase fields ("grantType"), producing
     * HTTP 400 unsupported_grant_type. The OAuth body must be snake_case.
     */
    @Test
    public void authBodyUsesOAuthSnakeCase() {
        TokenManager.AuthRequest request = TokenManager.AuthRequest.of("cid", "sec", "me@x.com", "pw");
        String json = GsonHelper.toJson(request);
        assertTrue(json.contains("\"grant_type\":\"password\""));
        assertTrue(json.contains("\"client_id\":\"cid\""));
        assertFalse("camelCase leak", json.contains("grantType") || json.contains("clientId"));
    }

    // ==================== device data envelope ====================

    /**
     * Bug: GET /devices/{id} returns {"result":{...}} but the parser mapped the whole
     * response onto DeviceData, so heatingLevel/targetLevel/hasWater were all null.
     */
    @Test
    public void deviceDataUnwrapsResult() {
        String body = "{\"result\":{\"leftHeatingLevel\":-16,\"leftTargetHeatingLevel\":-41,"
                + "\"hasWater\":true,\"ledBrightnessLevel\":50,\"currentState\":null,\"features\":[\"cooling\"]}}";
        var data = ApiTestFixtures.parseDeviceData(body);
        assertEquals(Double.valueOf(-16), data.leftHeatingLevel());
        assertEquals(Double.valueOf(-41), data.leftTargetHeatingLevel());
        assertEquals(Boolean.TRUE, data.hasWater());
    }

    // ==================== trends shape ====================

    private static final String TRENDS_BODY = """
            {"days":[
              {"score":74,"tnt":15,
               "presenceStart":"2026-08-22T04:31:00.000Z","presenceEnd":"2026-08-22T11:07:30.000Z",
               "lightDuration":9480,"deepDuration":4560,"remDuration":6030,
               "sleepDuration":20070,"presenceDuration":28110,
               "sleepQualityScore":{"total":84,"hrv":{"current":48.4},
                 "respiratoryRate":{"current":15.8}},
               "sleepRoutineScore":{"total":79},
               "sessions":[{"timeseries":{
                  "tempBedC":[["2026-08-22T04:31:00Z","None"],["2026-08-22T05:00:00Z",26.17]],
                  "tempRoomC":[["2026-08-22T04:31:00Z","None"],["2026-08-22T05:00:00Z",27.27]],
                  "heartRate":[["2026-08-22T04:31:00Z","None"],["2026-08-22T05:00:00Z",48]]}}]},
              {"score":80,"tnt":9,
               "sessions":[{"timeseries":{"heartRate":[["2026-08-22T23:05:00Z",62]]}}]}
            ]}""";

    /** Contract DTO values are mapped into a typed domain model. */
    @Test
    public void trendParsingSurvivesNestedObjectsAndNoneStrings() {
        var trends = ApiTestFixtures.parseTrendDays(TRENDS_BODY);
        assertEquals(2, trends.days().size());

        // day-level fields (score/tnt/durations live on the DAY, not the session)
        var day = trends.getDay(0);
        assertEquals(Double.valueOf(80), day.score());
        assertEquals(Double.valueOf(9), day.tossAndTurns());

        // session timeseries with string timestamps and literal "None" values
        var session = trends.getCurrentSession();
        assertNull("'None' entries must be skipped", session.latestValue("tempRoomC"));
        var previous = trends.getPreviousSession();
        assertEquals(Double.valueOf(27.27), previous.latestValue("tempRoomC"));
    }

    // ==================== alarm update contract ====================

    /**
     * Bug: the toggle payload was wrapped in {"alarmSettings":...}; every wrapper
     * variant is rejected with "alarm id mismatch between path and body". Verified
     * live: only the BARE alarm object PUTs successfully.
     */
    @Test
    public void alarmUpdateIsBareObjectWithIntegers() {
        var alarms = ApiTestFixtures.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":true,"time":"07:00:00","snoozing":false,
                 "repeat":{"enabled":true,"weekDays":{"monday":true}},
                 "thermal":{"enabled":true,"level":-10.0},
                 "vibration":{"enabled":true,"level":50.0,"pattern":"INTENSE"},
                 "audio":{"enabled":false,"level":30,"trackId":"futuristic"},
                 "smart":{"lightSleepEnabled":true},"tags":["routine-x"],"skipNext":false}]}""");
        String json = ApiTestFixtures.buildAlarmUpdateBody(alarms.get(0), false, null);

        assertFalse("wrapper must NOT be sent (verified: rejected)", json.contains("alarmSettings"));
        assertTrue("round-trips id for path/body match", json.contains("\"id\":\"a1\""));
        // .NET Int32 binding rejects whole-number doubles like -10.0 / 50.0
        assertTrue(json.contains("\"level\":-10"));
        assertTrue(json.contains("\"level\":50"));
        assertFalse(json.contains("-10.0") || json.contains("50.0"));
        // full round-trip: sections stripped by earlier regressions must survive
        assertTrue(json.contains("\"audio\""));
        assertTrue(json.contains("\"smart\""));
        assertTrue(json.contains("\"tags\""));
        assertTrue(json.contains("\"skipNext\""));
        // server-computed fields stay stripped
        assertFalse(
                json.contains("nextTimestamp") || json.contains("startTimestamp") || json.contains("dismissedUntil"));
    }

    /**
     * Bug: disabled alarms have nextTimestamp == null; String.valueOf(null) fed the
     * literal "null" into the parser, logging parse errors every sync.
     */
    @Test
    public void nullNextTimestampParsesToNull() {
        var alarms = ApiTestFixtures.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":false,"time":"07:00:00","nextTimestamp":null}]}""");
        assertNull(alarms.get(0).nextRun());
    }
}
