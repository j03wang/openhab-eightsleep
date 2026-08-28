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
 * Pins the adjustable base write contract: angle and preset
 * POST shapes.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepServiceBaseTest {

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

    // ==================== adjustable base ====================

    // ==================== adjustable base ====================

    @Test
    public void setBaseAnglePostsDeviceScopedPayload() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(new EightSleepService(client(t)).setBaseAngle("u1", "dev1", 20, 45));

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
        join(new EightSleepService(client(t)).setBasePreset("u1", "dev1", "reading"));

        String req = t.requests.get(0);
        assertTrue(req.startsWith("POST " + APP + "v1/users/u1/base/angle?ignoreDeviceErrors=false "));
        assertTrue(req.contains("\"preset\":\"reading\""));
    }
}
