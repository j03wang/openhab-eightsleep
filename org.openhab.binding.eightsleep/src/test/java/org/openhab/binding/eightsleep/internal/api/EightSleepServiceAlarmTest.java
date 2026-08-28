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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.Alarm;

/**
 * Pins the alarm write contract: scoped URLs, bare-object updates,
 * fail-fast guards and reschedule.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepServiceAlarmTest {

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

    // ==================== alarms ====================

    // ==================== alarms ====================

    @Test
    public void snoozeAndDismissUseAlarmScopedUrls() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepService c = new EightSleepService(client(t));
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
        EightSleepService c = new EightSleepService(client(t));
        Alarm idless = new Alarm(null, null, null, null, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), null, null,
                null);

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

    // ==================== reschedule ====================

    @Test
    public void alarmToggleAndRescheduleTargetAlarmScopedUrl() throws Exception {
        Alarm alarm = ApiTestFixtures.parseAlarms("""
                {"alarms":[{"id":"a1","enabled":true,"time":"07:00:00",
                 "repeat":{"enabled":true,"weekDays":{"monday":true}}}]}""").get(0);

        ScriptedTransport t = new ScriptedTransport();
        t.enqueueSuccesses(2);
        EightSleepService c = new EightSleepService(client(t));
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
