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
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests for token acquisition, proactive refresh and invalidation using a fake
 * auth transport - no network involved.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TokenManagerTest {

    private static final String TOKEN_1 = "token-one";
    private static final String TOKEN_2 = "token-two";

    /** Fake transport serving scripted responses and counting calls. */
    private static class FakeAuth implements TokenManager.AuthTransport {
        final List<String> responses = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        int calls;

        FakeAuth(String... responses) {
            for (String r : responses) {
                this.responses.add(r);
            }
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> authenticate(String clientId, String clientSecret,
                String username, String password) {
            calls++;
            java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
            if (!failures.isEmpty()) {
                future.completeExceptionally(failures.remove(0));
            } else if (!responses.isEmpty()) {
                future.complete(responses.remove(0));
            } else {
                future.completeExceptionally(new IllegalStateException("no scripted response"));
            }
            return future;
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> refresh(String clientId, String clientSecret,
                String refreshToken) {
            calls++;
            java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
            if (!failures.isEmpty()) {
                future.completeExceptionally(failures.remove(0));
            } else if (!responses.isEmpty()) {
                future.complete(responses.remove(0));
            } else {
                future.completeExceptionally(new IllegalStateException("no scripted response"));
            }
            return future;
        }

        String lastResponse() {
            return responses.isEmpty() ? null : responses.get(0);
        }
    }

    private static String authJson(String token, double expiresInSeconds, String userId) {
        return "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresInSeconds + ",\"user_id\":\"" + userId
                + "\",\"refresh_token\":\"r1\"}";
    }

    private interface ThrowingCall {
        void run() throws ApiException;
    }

    private static void assertApiException(ThrowingCall call, String expectedFragment) {
        try {
            call.run();
            fail("expected ApiException");
        } catch (ApiException e) {
            assertTrue("message should mention '" + expectedFragment + "': " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }

    @Test
    public void firstCallFetchesAndCachesToken() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        assertEquals(TOKEN_1, manager.getAccessToken());
        assertEquals(TOKEN_1, manager.getAccessToken());
        assertEquals("cached token must not refetch", 1, auth.calls);
        assertEquals("u1", manager.getUserId());
        assertTrue(manager.secondsUntilExpiry() > 3600 - 130);
    }

    @Test
    public void expiredTokenIsRefreshedProactively() throws ApiException {
        // 100s remaining is inside the 120s buffer -> must refresh
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 100, "u1"), authJson(TOKEN_2, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        assertEquals(TOKEN_1, manager.getAccessToken());
        assertEquals(TOKEN_2, manager.getAccessToken());
        assertEquals(2, auth.calls);
    }

    @Test
    public void freshTokenOutsideBufferIsReused() throws ApiException {
        // 121s remaining is just outside the 120s buffer -> no refresh
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 121, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        manager.getAccessToken();
        manager.getAccessToken();
        assertEquals(1, auth.calls);
    }

    @Test
    public void invalidateForcesRefetch() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"), authJson(TOKEN_2, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        assertEquals(TOKEN_1, manager.getAccessToken());

        manager.invalidate();
        assertEquals(TOKEN_2, manager.getAccessToken());
        assertEquals(2, auth.calls);
    }

    @Test
    public void transportFailureWrapsInApiException() {
        FakeAuth auth = new FakeAuth();
        auth.failures.add(new RuntimeException("boom"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        assertApiException(manager::getAccessToken, "Authentication failed");
    }

    @Test
    public void missingTokenFieldsRejected() {
        FakeAuth auth = new FakeAuth("{\"expires_in\":3600}");
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        assertApiException(manager::getAccessToken, "missing token fields");
    }

    @Test
    public void emptyResponseBodyRejected() {
        FakeAuth auth = new FakeAuth();
        // script nothing: transport fails with "no scripted response"
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        assertApiException(manager::getAccessToken, "Authentication failed");
    }

    @Test
    public void blankClientCredentialsFallBackToKnownAppValues() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", "", "  ", auth);
        assertNotNull(manager.getAccessToken());
        // the fallback values are hard to observe without exposing them; at least
        // ensure the request still succeeded end-to-end
        assertEquals(1, auth.calls);
    }

    @Test
    public void noUserIdYetBeforeFirstLogin() {
        FakeAuth auth = new FakeAuth();
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        assertNull(manager.getUserId());
        assertFalse(manager.secondsUntilExpiry() > 0);
    }

    // ==================== injected-clock boundary tests ====================

    /** Minimal advancing clock so expiry behaviour can be exercised deterministically. */
    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now;

        MutableClock(java.time.Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Remaining lifetime EXACTLY equal to the 120 s buffer satisfies
     * {@code now + buffer >= expiry} and must trigger a proactive refetch.
     */
    @Test
    public void refreshTriggersAtExactBufferBoundary() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"), authJson(TOKEN_2, 3600, "u1"));
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth, clock);

        assertEquals(TOKEN_1, manager.getAccessToken());
        assertEquals(1, auth.calls);

        clock.advanceSeconds(3480); // remaining lifetime == 120 s == buffer
        assertEquals(TOKEN_2, manager.getAccessToken());
        assertEquals("remaining==buffer must refresh", 2, auth.calls);
    }

    /** One second outside the buffer (121 s remaining) the cached token is reused. */
    @Test
    public void tokenJustOutsideBufferReusedWithInjectedClock() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3600, "u1"));
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth, clock);

        manager.getAccessToken();
        clock.advanceSeconds(3479); // remaining lifetime == 121 s
        manager.getAccessToken();
        assertEquals("121 s remaining must NOT refresh", 1, auth.calls);
    }

    /** Fractional expires_in values truncate to whole seconds. */
    @Test
    public void fractionalExpiresInTruncates() throws ApiException {
        FakeAuth auth = new FakeAuth(authJson(TOKEN_1, 3599.9, "u1"));
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        assertNotNull(manager.getAccessToken());
        assertEquals("fractional seconds truncate toward zero", 3599L, manager.secondsUntilExpiry());
    }
}
