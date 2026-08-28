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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Pins the temperature write contract: heating level order
 * and clamping, side power states, pillow resource targeting.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepServiceTemperatureTest {

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

    // ==================== heating level ====================

    // ==================== heating ====================

    /** Upstream order matters: power on first, then currentLevel, then timeBased. */
    @Test
    public void setHeatingLevelSendsTurnOnCurrentLevelThenTimeBased() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(3);
        join(new EightSleepService(client(t)).setHeatingLevel("u1", -50, 120));

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
        join(new EightSleepService(client(high)).setHeatingLevel("u1", 500, 0));
        assertTrue(high.requests.get(1).contains("\"currentLevel\":100"));
        assertTrue(high.requests.get(2).contains("\"level\":100"));

        ScriptedTransport low = new ScriptedTransport();
        low.enqueueSuccesses(3);
        join(new EightSleepService(client(low)).setHeatingLevel("u1", -999, 0));
        assertTrue(low.requests.get(1).contains("\"currentLevel\":-100"));

        ScriptedTransport off = new ScriptedTransport();
        off.enqueueSuccesses(3);
        join(new EightSleepService(client(off)).setHeatingLevel("u1", 0, 0));
        assertTrue(off.requests.get(1).contains("\"currentLevel\":0"));
    }

    @Test
    public void smartScheduleUpdatePreservesIntegerLevels() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.script.add(CompletableFuture.completedFuture(
                "{\"smart\":{\"bedTimeLevel\":-30,\"initialSleepLevel\":-22,\"finalSleepLevel\":-11}}"));
        transport.enqueueSuccesses(1);

        join(new EightSleepService(client(transport)).setSmartHeatingLevel("u1", -25, "initialSleepLevel"));

        String update = transport.requests.get(1);
        assertTrue(update.contains("\"bedTimeLevel\":-30"));
        assertTrue(update.contains("\"initialSleepLevel\":-25"));
        assertTrue(update.contains("\"finalSleepLevel\":-11"));
        assertTrue(!update.contains("-30.0") && !update.contains("-25.0") && !update.contains("-11.0"));
    }

    // ==================== side on/off ====================

    // ==================== side on/off ====================

    @Test
    public void turnOnAndOffSideUseSmartAndOffStates() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepService c = new EightSleepService(client(t));
        join(c.turnOnSide("u1"));
        join(c.turnOffSide("u1"));

        assertEquals(2, t.requests.size());
        assertTrue(t.requests.get(0).endsWith("body={\"currentState\":{\"type\":\"smart\"}}"));
        assertTrue(t.requests.get(1).endsWith("body={\"currentState\":{\"type\":\"off\"}}"));
    }

    // ==================== pillow ====================

    // ==================== pillow ====================

    @Test
    public void pillowOperationsTargetPillowResourceWithClamp() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(3);
        EightSleepService c = new EightSleepService(client(t));
        join(c.turnOnPillow("u1"));
        join(c.turnOffPillow("u1"));
        join(c.setPillowLevel("u1", 150));

        assertEquals(3, t.requests.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(t.requests.get(i).startsWith("PUT " + APP + "v1/users/u1/temperature/pillow "));
        }
        assertTrue(t.requests.get(0).endsWith("body={\"currentState\":{\"type\":\"smart\"}}"));
        assertTrue(t.requests.get(1).endsWith("body={\"currentState\":{\"type\":\"off\"}}"));
        assertTrue(t.requests.get(2).contains("\"currentLevel\":100"));
    }
}
