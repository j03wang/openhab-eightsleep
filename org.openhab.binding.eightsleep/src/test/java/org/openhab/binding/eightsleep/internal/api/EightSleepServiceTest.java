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
 * Pins cross-cutting control contracts that do not belong to a single domain:
 * device-level actions (LED brightness, priming), read URL shapes, speaker
 * playback and the 401 retry plumbing across verbs. Domain-specific contracts
 * live in the other {@code EightSleepService*Test} classes.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepServiceTest {

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
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, (cid, cs, u, p) -> CompletableFuture
                .completedFuture("{\"access_token\":\"tok\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
        return new EightSleepApiClient(manager, transport);
    }

    private static <T> T join(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    // ==================== LED / priming ====================

    // ==================== LED / priming ====================

    @Test
    public void setLedBrightnessClampsAndTargetsDeviceResource() throws Exception {
        ScriptedTransport high = new ScriptedTransport();
        high.enqueueSuccesses(1);
        join(new EightSleepService(client(high)).setLedBrightness("dev1", 150));
        assertTrue(high.requests.get(0).startsWith("PUT " + CLIENT + "/devices/dev1 "));
        assertTrue(high.requests.get(0).contains("\"ledBrightnessLevel\":100"));

        ScriptedTransport low = new ScriptedTransport();
        low.enqueueSuccesses(1);
        join(new EightSleepService(client(low)).setLedBrightness("dev1", -5));
        assertTrue(low.requests.get(0).contains("\"ledBrightnessLevel\":0"));
    }

    @Test
    public void primePodNotifiesRequestingUser() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(new EightSleepService(client(t)).primePod("dev1", "u1"));

        String req = t.requests.get(0);
        assertTrue(req.startsWith("POST " + APP + "v1/devices/dev1/priming/tasks "));
        assertTrue(req.contains("\"users\":[\"u1\"]"));
        assertTrue(req.contains("\"meta\":\"fill_pod\""));
    }

    // ==================== read URL contracts ====================

    // ==================== data-fetch URL contracts ====================

    /** The v2 trends request must carry the full documented query string. */
    @Test
    public void getTrendsUrlContract() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.script.add(CompletableFuture.completedFuture("{\"days\":[]}"));
        var start = java.time.ZonedDateTime.parse("2026-08-20T00:00:00Z[UTC]");
        var end = java.time.ZonedDateTime.parse("2026-08-23T00:00:00Z[UTC]");
        join(client(t).getTrends("u1", start, end, "Europe/Berlin"));

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
        Map<String, String> devices = join(new EightSleepService(client(t)).getHouseholdDevices());

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
            join(new EightSleepService(client(t)).getCurrentUserId());
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

    // ==================== speaker ====================

    @Test
    public void playerVolumeClampsToPercentRange() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepService c = new EightSleepService(client(t));
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
        EightSleepService c = new EightSleepService(client(t));
        join(c.setPlayerState("u1", false));
        join(c.setPlayerTrack("u1", "track9"));

        assertTrue(t.requests.get(0).endsWith("body={\"state\":\"Paused\"}"));
        assertTrue(t.requests.get(1).contains("\"id\":\"track9\""));
        assertTrue(t.requests.get(1).contains("\"stopCriteria\":\"ManualStop\""));
    }

    // ==================== 401 retry across verbs ====================

    /** POST operations get the same single-retry-on-401 treatment as GET/PUT. */
    @Test
    public void postRetriesOnceAfterUnauthorized() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueFailure(new ApiException("HTTP 401", true, false));
        t.enqueueSuccesses(1);
        join(new EightSleepService(client(t)).setBasePreset("u1", "dev1", "sleep"));

        assertEquals(2, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("POST "));
        assertTrue(t.requests.get(1).startsWith("POST "));
    }
}
