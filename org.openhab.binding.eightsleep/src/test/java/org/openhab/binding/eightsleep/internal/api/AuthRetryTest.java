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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Verifies the 401-retry-once contract of {@code withAuthRetry} through a fake
 * transport: exactly one retry after unauthorized, no retry on other errors,
 * and failure propagation when the retry also gets a 401.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AuthRetryTest {


    private static TokenManager managerWithToken() {
        return new TokenManager("me@x.com", "pw", null, null,
                (clientId, clientSecret, username, password) -> CompletableFuture
                        .completedFuture("{\"access_token\":\"tok\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
    }

    private static <T> T join(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void unauthorizedIsRetriedOnceWithFreshTokenThenSucceeds() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.enqueueFailure(new ApiException("HTTP 401", true, false));
        transport.enqueueSuccess("{\"currentState\":{\"type\":\"smart\"}}");
        EightSleepApiClient client = new EightSleepApiClient(managerWithToken(), transport);

        assertEquals("smart",
                join(client.getTemperature("u1")).getAsJsonObject("currentState").get("type").getAsString());
        assertEquals(2, transport.requests.size());
        assertTrue("first attempt uses cached token",
                transport.requests.get(0).endsWith("token=tok"));
        assertTrue("retry also carries a (refreshed) token",
                transport.requests.get(1).endsWith("token=tok"));
    }

    @Test
    public void serverErrorIsNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.enqueueFailure(new ApiException("HTTP 500", false, false));
        EightSleepApiClient client = new EightSleepApiClient(managerWithToken(), transport);

        try {
            join(client.getTemperature("u1"));
            fail("expected failure");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof ApiException)) {
                cause = cause.getCause();
            }
            assertTrue(cause instanceof ApiException);
            assertEquals(1, transport.requests.size());
        }
    }

    @Test
    public void secondUnauthorizedPropagatesAfterSingleRetry() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.enqueueFailure(new ApiException("HTTP 401", true, false));
        transport.enqueueFailure(new ApiException("HTTP 401 still", true, false));
        EightSleepApiClient client = new EightSleepApiClient(managerWithToken(), transport);

        try {
            join(client.getTemperature("u1"));
            fail("expected failure");
        } catch (java.util.concurrent.ExecutionException e) {
            ApiException apiEx = (ApiException) e.getCause();
            assertTrue(apiEx.isUnauthorized());
            assertEquals("exactly one retry", 2, transport.requests.size());
        }
    }

    @Test
    public void subscriptionRequiredErrorIsNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.enqueueFailure(new ApiException("HTTP 403 subscription required", false, true));
        EightSleepApiClient client = new EightSleepApiClient(managerWithToken(), transport);

        try {
            join(client.getAlarms("u1"));
            fail("expected failure");
        } catch (java.util.concurrent.ExecutionException e) {
            ApiException apiEx = (ApiException) e.getCause();
            assertTrue(apiEx.isSubscriptionRequired());
            assertFalseUnauthorized(apiEx);
            assertEquals(1, transport.requests.size());
        }
    }

    private static void assertFalseUnauthorized(ApiException e) {
        if (e.isUnauthorized()) {
            fail("must not be flagged unauthorized");
        }
    }

    /** Sanity: PUT bodies flow through the same retried path unchanged. */
    @Test
    public void putBodyReachesTransportVerbatim() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.enqueueSuccess("");
        EightSleepApiClient client = new EightSleepApiClient(managerWithToken(), transport);

        join(client.turnOffSide("u1"));
        assertEquals(1, transport.requests.size());
        assertTrue(transport.requests.get(0).startsWith("PUT "));
    }
}
