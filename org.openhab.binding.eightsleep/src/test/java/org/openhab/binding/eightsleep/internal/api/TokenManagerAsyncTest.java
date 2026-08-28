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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests for the non-blocking token acquisition path: async completion of cached
 * and refreshed tokens, failure propagation, and shared in-flight refreshes.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TokenManagerAsyncTest {

    private static final String TOKEN_1 = "token-one";
    private static final String TOKEN_2 = "token-two";

    /** Fake transport serving scripted responses and counting calls. */
    private static class FakeAuth implements TokenManager.AuthTransport {
        final java.util.List<String> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        int calls;

        FakeAuth(String... responses) {
            for (String r : responses) {
                this.responses.add(r);
            }
        }

        @Override
        public CompletableFuture<String> authenticate(String clientId, String clientSecret, String username,
                String password) {
            calls++;
            CompletableFuture<String> future = new CompletableFuture<>();
            if (!responses.isEmpty()) {
                future.complete(responses.remove(0));
            } else {
                future.completeExceptionally(new IllegalStateException("no scripted response"));
            }
            return future;
        }
    }

    private static String authJson(String token, double expiresInSeconds, String userId) {
        return "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresInSeconds + ",\"user_id\":\"" + userId
                + "\"}";
    }

    @Test
    public void asyncCachedTokenCompletesWithoutTransportCall() throws Exception {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        assertEquals(TOKEN_1, manager.getAccessTokenAsync().get(5, TimeUnit.SECONDS));
        assertEquals(TOKEN_1, manager.getAccessTokenAsync().get(5, TimeUnit.SECONDS));
        assertEquals("cached token must not refetch", 1, auth.calls);
    }

    @Test
    public void asyncRefreshFetchesWhenAbsent() throws Exception {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"), authJson(TOKEN_2, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        assertEquals(TOKEN_1, manager.getAccessTokenAsync().get(5, TimeUnit.SECONDS));
        manager.invalidate();
        assertEquals("refresh after invalidation must fetch a new token", TOKEN_2,
                manager.getAccessTokenAsync().get(5, TimeUnit.SECONDS));
        assertEquals(2, auth.calls);
    }

    @Test
    public void asyncFailurePropagatesCause() throws Exception {
        FakeAuth auth = new FakeAuth();
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        try {
            manager.getAccessTokenAsync().get(5, TimeUnit.SECONDS);
            org.junit.Assert.fail("expected ExecutionException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof ApiException);
            assertNull(manager.getUserId());
        }
    }

    /**
     * Concurrent callers during one refresh share the same in-flight request: no
     * duplicate authentication calls while the first refresh is still running.
     */
    @Test
    public void concurrentAsyncCallersShareOneRefresh() throws Exception {
        CompletableFuture<String> slowAuth = new CompletableFuture<>();
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, (cid, cs, u, p) -> slowAuth);

        CompletableFuture<String> first = manager.getAccessTokenAsync();
        CompletableFuture<String> second = manager.getAccessTokenAsync();

        slowAuth.complete(authJson(TOKEN_1, 3600, "u1"));

        assertEquals(TOKEN_1, first.get(5, TimeUnit.SECONDS));
        assertEquals(TOKEN_1, second.get(5, TimeUnit.SECONDS));
        assertNotNull(manager.getUserId());
    }

    /** The blocking convenience path still works for off-scheduler callers. */
    @Test
    public void blockingPathStillReturnsToken() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        assertEquals(TOKEN_1, manager.getAccessToken());
    }
}
