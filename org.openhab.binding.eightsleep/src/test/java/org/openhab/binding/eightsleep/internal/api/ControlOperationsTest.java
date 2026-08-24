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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Pins the request contract of every control operation: URL, method, body and
 * clamping. These encode live-verified upstream behaviour - a silent change in
 * any of them breaks real hardware, so each is asserted against a scripted
 * transport.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ControlOperationsTest {

    private static final String CLIENT = "https://client-api.8slp.net/v1";
    private static final String APP = "https://app-api.8slp.net/";

    /** Recorded request + scripted response for one transport call. */
    private static class ScriptedTransport implements EightSleepApiClient.Transport {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<String>> script = new CopyOnWriteArrayList<>();

        void enqueueSuccesses(int count) {
            for (int i = 0; i < count; i++) {
                script.add(CompletableFuture.completedFuture(""));
            }
        }

        void enqueueFailure(ApiException e) {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            script.add(f);
        }

        @Override
        public CompletableFuture<String> send(String method, String url, String jsonBody, String accessToken) {
            requests.add(method + " " + url + " body=" + jsonBody);
            return script.remove(0);
        }
    }

    private static EightSleepApiClient client(ScriptedTransport transport) {
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null,
                (cid, cs, u, p) -> CompletableFuture
                        .completedFuture("{\"access_token\":\"tok\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
        return new EightSleepApiClient(manager, transport);
    }

    private static <T> T join(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void assertApiFailure(Exception e, String fragment) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        while (cause != null && !(cause instanceof ApiException)) {
            cause = cause.getCause();
        }
        assertTrue("expected ApiException cause, got " + e.getCause(), cause instanceof ApiException);
        assertTrue("message should contain '" + fragment + "': " + ((ApiException) cause).getMessage(),
                ((ApiException) cause).getMessage().contains(fragment));
    }

    // ==================== heating ====================

    /** Upstream order matters: power on first, then currentLevel, then timeBased. */
    @Test
    public void setHeatingLevelSendsTurnOnCurrentLevelThenTimeBased() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(3);
        join(client(t).setHeatingLevel("u1", -50, 120));

        assertEquals(3, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("PUT " + APP + "v1/users/u1/temperature ")
                && t.requests.get(0).contains("body={\"currentState\":{\"type\":\"smart\"}}"));
        assertTrue(t.requests.get(1).startsWith("PUT ") && t.requests.get(1).contains("\"currentLevel\":-50")
                && !t.requests.get(1).contains("timeBased"));
        // Map.of has no iteration order - assert members, not their sequence
        String timeBasedPut = t.requests.get(2);
        assertTrue(timeBasedPut.contains("\"timeBased\":{"));
        assertTrue(timeBasedPut.contains("\"level\":-50"));
        assertTrue(timeBasedPut.contains("\"durationSeconds\":120"));
    }

    /** Levels outside -100..100 clamp at both ends. */
    @Test
    public void setHeatingLevelClampsToApiRange() throws Exception {
        ScriptedTransport high = new ScriptedTransport();
        high.enqueueSuccesses(3);
        join(client(high).setHeatingLevel("u1", 500, 0));
        assertTrue(high.requests.get(1).contains("\"currentLevel\":100"));
        assertTrue(high.requests.get(2).contains("\"level\":100"));

        ScriptedTransport low = new ScriptedTransport();
        low.enqueueSuccesses(3);
        join(client(low).setHeatingLevel("u1", -999, 0));
        assertTrue(low.requests.get(1).contains("\"currentLevel\":-100"));

        ScriptedTransport off = new ScriptedTransport();
        off.enqueueSuccesses(3);
        join(client(off).setHeatingLevel("u1", 0, 0));
        assertTrue(off.requests.get(1).contains("\"currentLevel\":0"));
    }

    // ==================== side on/off ====================

    @Test
    public void turnOnAndOffSideUseSmartAndOffStates() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepApiClient c = client(t);
        join(c.turnOnSide("u1"));
        join(c.turnOffSide("u1"));

        assertEquals(2, t.requests.size());
        assertTrue(t.requests.get(0).endsWith("body={\"currentState\":{\"type\":\"smart\"}}"));
        assertTrue(t.requests.get(1).endsWith("body={\"currentState\":{\"type\":\"off\"}}"));
    }

    // ==================== adjustable base ====================

    @Test
    public void setBaseAnglePostsDeviceScopedPayload() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(client(t).setBaseAngle("u1", "dev1", 20, 45));

        assertEquals(1, t.requests.size());
        String req = t.requests.get(0);
        assertTrue(req.startsWith("POST " + APP + "v1/users/u1/base/angle?ignoreDeviceErrors=false "));
        assertTrue(req.contains("\"deviceId\":\"dev1\""));
        assertTrue(req.contains("\"legAngle\":20"));
        assertTrue(req.contains("\"torsoAngle\":45"));
        assertTrue(req.contains("\"deviceOnline\":true"));
        assertTrue(req.contains("\"enableOfflineMode\":false"));
    }

    @Test
    public void setBasePresetPostsPresetNameVerbatim() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(client(t).setBasePreset("u1", "dev1", "reading"));

        String req = t.requests.get(0);
        assertTrue(req.startsWith("POST " + APP + "v1/users/u1/base/angle?ignoreDeviceErrors=false "));
        assertTrue(req.contains("\"preset\":\"reading\""));
    }

    // ==================== LED / priming ====================

    @Test
    public void setLedBrightnessClampsAndTargetsDeviceResource() throws Exception {
        ScriptedTransport high = new ScriptedTransport();
        high.enqueueSuccesses(1);
        join(client(high).setLedBrightness("dev1", 150));
        assertTrue(high.requests.get(0).startsWith("PUT " + CLIENT + "/devices/dev1 "));
        assertTrue(high.requests.get(0).contains("\"ledBrightnessLevel\":100"));

        ScriptedTransport low = new ScriptedTransport();
        low.enqueueSuccesses(1);
        join(client(low).setLedBrightness("dev1", -5));
        assertTrue(low.requests.get(0).contains("\"ledBrightnessLevel\":0"));
    }

    @Test
    public void primePodNotifiesRequestingUser() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(client(t).primePod("dev1", "u1"));

        String req = t.requests.get(0);
        assertTrue(req.startsWith("POST " + APP + "v1/devices/dev1/priming/tasks "));
        assertTrue(req.contains("\"users\":[\"u1\"]"));
        assertTrue(req.contains("\"meta\":\"rePriming\""));
    }

    // ==================== pillow ====================

    @Test
    public void pillowOperationsTargetPillowResourceWithClamp() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(3);
        EightSleepApiClient c = client(t);
        join(c.turnOnPillow("u1"));
        join(c.turnOffPillow("u1"));
        join(c.setPillowLevel("u1", 150));

        assertEquals(3, t.requests.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(t.requests.get(i).startsWith(
                    "PUT " + APP + "v1/users/u1/temperature/pillow "));
        }
        assertTrue(t.requests.get(0).endsWith("body={\"currentState\":{\"type\":\"smart\"}}"));
        assertTrue(t.requests.get(1).endsWith("body={\"currentState\":{\"type\":\"off\"}}"));
        assertTrue(t.requests.get(2).contains("\"currentLevel\":100"));
    }

    // ==================== bed side assignment / away mode ====================

    @Test
    public void setBedSideRejectsInvalidSideWithoutTransportCall() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        try {
            join(client(t).setBedSide("u1", "dev1", "north"));
            fail("expected failure");
        } catch (Exception e) {
            assertApiFailure(e, "Invalid side parameter");
        }
        assertEquals(0, t.requests.size());

        t.enqueueSuccesses(1);
        join(client(t).setBedSide("u1", "dev1", "SOLO"));
        assertTrue(t.requests.get(0)
                .startsWith("PUT " + CLIENT + "/users/u1/current-device "));
        assertTrue(t.requests.get(0).contains("\"side\":\"solo\"") && t.requests.get(0).contains("\"id\":\"dev1\""));
    }

    /**
     * A genuine side re-asserts the user's current-device BEFORE the away-mode PUT
     * (multi-pod targeting); solo skips the rewrite entirely.
     */
    @Test
    public void setAwayModeReassertsSideOnlyForGenuineSides() throws Exception {
        ScriptedTransport left = new ScriptedTransport();
        left.enqueueSuccesses(2);
        join(client(left).setAwayMode("u1", "dev1", "left", "start"));

        assertEquals(2, left.requests.size());
        assertTrue(left.requests.get(0).startsWith("PUT " + CLIENT + "/users/u1/current-device "));
        assertTrue(left.requests.get(0).contains("\"side\":\"left\""));
        assertTrue(left.requests.get(1).startsWith("PUT " + APP + "v1/users/u1/away-mode "));
        assertTrue(left.requests.get(1).contains("\"start\":\""));
        assertTrue(left.requests.get(1).matches(".*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\".*"));

        ScriptedTransport solo = new ScriptedTransport();
        solo.enqueueSuccesses(1);
        join(client(solo).setAwayMode("u1", "dev1", "solo", "end"));

        assertEquals("solo must NOT rewrite current-device", 1, solo.requests.size());
        assertTrue(solo.requests.get(0).contains("away-mode"));
        assertTrue(solo.requests.get(0).contains("\"end\":\""));
    }

    @Test
    public void setAwayModeRejectsInvalidActionImmediately() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        try {
            join(client(t).setAwayMode("u1", "dev1", "left", "toggle"));
            fail("expected failure");
        } catch (Exception e) {
            assertApiFailure(e, "Invalid away mode action");
        }
        assertEquals(0, t.requests.size());
    }

    // ==================== alarms ====================

    @Test
    public void snoozeAndDismissUseAlarmScopedUrls() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepApiClient c = client(t);
        join(c.snoozeAlarm("u1", "a1", 9));
        join(c.dismissAlarm("u1", "a1"));

        assertTrue(t.requests.get(0).startsWith("PUT " + APP + "v1/users/u1/alarms/a1/snooze "));
        assertTrue(t.requests.get(0).contains("\"snoozeMinutes\":9"));
        assertTrue(t.requests.get(0).contains("\"ignoreDeviceErrors\":false"));
        assertTrue(t.requests.get(1).startsWith("PUT " + APP + "v1/users/u1/alarms/a1/dismiss "));
        assertTrue(t.requests.get(1).contains("\"ignoreDeviceErrors\":false"));
    }

    @Test
    public void alarmUpdatesWithoutIdFailFast() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        EightSleepApiClient c = client(t);
        Alarm idless = new Alarm();

        try {
            join(c.setAlarmEnabled("u1", idless, true));
            fail("expected failure");
        } catch (Exception e) {
            assertApiFailure(e, "without an id");
        }
        try {
            join(c.setAlarmTime("u1", idless, "07:00:00"));
            fail("expected failure");
        } catch (Exception e) {
            assertApiFailure(e, "without an id");
        }
        assertEquals("no transport traffic without an id", 0, t.requests.size());
    }

    // ==================== data-fetch URL contracts ====================

    /** The v2 trends request must carry the full documented query string. */
    @Test
    public void getTrendsUrlContract() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.script.add(CompletableFuture.completedFuture("{\"days\":[]}"));
        var start = java.time.ZonedDateTime.parse("2026-08-20T00:00:00Z[UTC]");
        var end = java.time.ZonedDateTime.parse("2026-08-23T00:00:00Z[UTC]");
        join(client(t).getUserTrends("u1", start, end, "Europe/Berlin"));

        assertEquals(1, t.requests.size());
        String req = t.requests.get(0);
        assertTrue(req.startsWith("GET " + CLIENT + "/users/u1/trends?"));
        assertTrue(req.contains("tz=Europe%2FBerlin"));
        assertTrue(req.contains("from=2026-08-20"));
        assertTrue(req.contains("to=2026-08-23"));
        assertTrue(req.contains("include-main=false"));
        assertTrue(req.contains("include-all-sessions=true"));
        assertTrue(req.contains("model-version=v2"));
    }

    /** getHouseholdDevices chains /users/me into the household summary for the same user. */
    @Test
    public void householdDevicesChainUsersMe() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.script.add(CompletableFuture.completedFuture("{\"user\":{\"userId\":\"u abc 1\"}}"));
        t.script.add(CompletableFuture.completedFuture(
                "{\"households\":[{\"sets\":[{\"devices\":[{\"deviceId\":\"d1\",\"deviceName\":\"Pod\"}]}]}]}"));
        Map<String, String> devices = join(client(t).getHouseholdDevices());

        assertEquals(2, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("GET " + CLIENT + "/users/me "));
        // the userId is url-encoded on its way into the summary URL
        assertTrue(t.requests.get(1).startsWith("GET " + APP + "v1/household/users/u+abc+1/summary "));
        assertEquals(Map.of("d1", "Pod"), devices);
    }

    /** A /users/me response without a userId fails the whole chain. */
    @Test
    public void currentUserIdMissingFails() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        try {
            join(client(t).getCurrentUserId());
            fail("expected failure");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            while (cause != null && !(cause instanceof IllegalStateException)) {
                cause = cause.getCause();
            }
            assertTrue("expected IllegalStateException cause, got " + e.getCause(),
                    cause instanceof IllegalStateException);
        }
    }

    /** Alarm enable/time updates PUT the exact alarm-scoped URL with the built body. */
    @Test
    public void alarmToggleAndRescheduleTargetAlarmScopedUrl() throws Exception {
        Alarm alarm = EightSleepApiClient.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":true,"time":"07:00:00",
                 "repeat":{"enabled":true,"weekDays":{"monday":true}}}]}""").get(0);

        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepApiClient c = client(t);
        join(c.setAlarmEnabled("u1", alarm, false));
        join(c.setAlarmTime("u1", alarm, "06:30:00"));

        assertEquals(2, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("PUT " + APP + "v1/users/u1/alarms/a1 "));
        assertTrue(t.requests.get(0).contains("\"enabled\":false"));
        assertTrue(t.requests.get(1).startsWith("PUT " + APP + "v1/users/u1/alarms/a1 "));
        assertTrue(t.requests.get(1).contains("\"time\":\"06:30:00\""));
        // neither update may wrap the body or leak server-computed fields
        assertFalse(t.requests.get(0).contains("alarmSettings"));
        assertFalse(t.requests.get(0).contains("nextTimestamp"));
    }

    // ==================== speaker ====================

    @Test
    public void playerVolumeClampsToPercentRange() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepApiClient c = client(t);
        join(c.setPlayerVolume("u1", 150));
        join(c.setPlayerVolume("u1", -10));

        assertTrue(t.requests.get(0).startsWith("PUT " + APP + "v1/users/u1/audio/player/volume "));
        assertTrue(t.requests.get(0).contains("\"volume\":100"));
        assertTrue(t.requests.get(1).contains("\"volume\":0"));
    }

    @Test
    public void playerStateAndTrackUseDocumentedBodies() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepApiClient c = client(t);
        join(c.setPlayerState("u1", false));
        join(c.setPlayerTrack("u1", "track9"));

        assertTrue(t.requests.get(0).endsWith("body={\"state\":\"Paused\"}"));
        assertTrue(t.requests.get(1).contains("\"id\":\"track9\""));
        assertTrue(t.requests.get(1).contains("\"stopCriteria\":\"ManualStop\""));
    }

    // ==================== retry plumbing across verbs ====================

    /** POST operations get the same single-retry-on-401 treatment as GET/PUT. */
    @Test
    public void postRetriesOnceAfterUnauthorized() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueFailure(new ApiException("HTTP 401", true, false));
        t.enqueueSuccesses(1);
        join(client(t).setBasePreset("u1", "dev1", "sleep"));

        assertEquals(2, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("POST "));
        assertTrue(t.requests.get(1).startsWith("POST "));
    }
}
