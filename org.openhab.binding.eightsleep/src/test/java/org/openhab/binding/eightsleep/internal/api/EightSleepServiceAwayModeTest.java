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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Pins the bed-side assignment and away-mode contract:
 * current-device writes, away start/end and the awaySides filter read.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepServiceAwayModeTest {

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

    // ==================== bed side assignment / away mode ====================

    // ==================== bed side assignment / away mode ====================

    @Test
    public void setBedSideRejectsInvalidSideWithoutTransportCall() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        try {
            join(new EightSleepService(client(t)).setBedSide("u1", "dev1", "north"));
            fail("expected failure");
        } catch (Exception e) {
            assertApiFailure(e, "Invalid side parameter");
        }
        assertEquals(0, t.requests.size());

        t.enqueueSuccesses(1);
        join(new EightSleepService(client(t)).setBedSide("u1", "dev1", "SOLO"));
        assertTrue(t.requests.get(0).startsWith("PUT " + CLIENT + "/users/u1/current-device "));
        assertTrue(t.requests.get(0).contains("\"side\":\"solo\"") && t.requests.get(0).contains("\"id\":\"dev1\""));
    }

    /**
     * Away start follows the 7.52 app contract: DELETE the user's current
     * bed-side assignment; optional return date and partner flags travel as
     * {@code X-8S-*} headers.
     */
    @Test
    public void setAwayModeDeletesCurrentSetWithHeaders() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(new EightSleepService(client(t)).setAwayMode("u1", "2026-08-30T07:00:00Z", true));

        assertEquals(1, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("DELETE " + APP + "v1/household/users/u1/current-set "));
        // header assertions are transport-level; here we pin method + URL only
        assertFalse(t.requests.get(0).contains("awayPeriod"));
    }

    @Test
    public void setAwayReturnDatePostsSchedule() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(new EightSleepService(client(t)).setAwayReturnDate("u1", "set1", Instant.parse("2026-08-30T07:00:00Z"),
                false));

        assertEquals(1, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("POST " + APP + "v1/household/users/u1/schedule "));
        assertTrue(t.requests.get(0).contains("\"setId\":\"set1\""));
        assertTrue(t.requests.get(0).contains("2026-08-30T07:00:00Z"));
        assertTrue(t.requests.get(0).contains("\"includePartner\":false"));
    }

    @Test
    public void cancelAwayReturnDeletesSchedule() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(1);
        join(client(t).cancelAwayReturn("u1", "set1"));

        assertEquals(1, t.requests.size());
        assertTrue(t.requests.get(0).startsWith("DELETE " + APP + "v1/household/users/u1/schedule/set1"));
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
}
