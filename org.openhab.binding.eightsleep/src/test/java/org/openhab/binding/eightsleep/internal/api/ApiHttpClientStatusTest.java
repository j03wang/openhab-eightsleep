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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.After;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Exercises {@link ApiHttpClient#send} against a local HTTP server: status to
 * exception-flag mapping, header forwarding and body pass-through. This is the
 * only test that covers the real network layer - all other tests substitute the
 * transport seam.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ApiHttpClientStatusTest {

    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();

    private @Nullable HttpServer server;

    private String startServer(int status, String body) throws Exception {
        HttpServer s = HttpServer.create(new java.net.InetSocketAddress(0), 0);
        s.createContext("/", exchange -> {
            seenAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (var out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        s.start();
        server = s;
        return "http://localhost:" + s.getAddress().getPort() + "/v1/test";
    }

    @After
    public void stopServer() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
        }
    }

    private ApiException failureOf(java.util.concurrent.CompletableFuture<String> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("expected the future to fail");
            return new ApiException("unreachable");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            while (cause != null && !(cause instanceof ApiException)) {
                cause = cause.getCause();
            }
            assertTrue("expected ApiException cause, got " + e.getCause(), cause instanceof ApiException);
            return (ApiException) cause;
        }
    }

    @Test
    public void successPassesBodyThrough() throws Exception {
        String url = startServer(200, "{\"ok\":true}");
        assertEquals("{\"ok\":true}", ApiHttpClient.send("GET", url, null, null).get(5, TimeUnit.SECONDS));
    }

    /** The DELETE branch of the send switch must reach the wire without a body. */
    @Test
    public void deleteReachesServerWithoutBody() throws Exception {
        List<String> seenMethods = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new java.net.InetSocketAddress(0), 0);
        s.createContext("/", exchange -> {
            seenMethods.add(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        s.start();
        try {
            String url = "http://localhost:" + s.getAddress().getPort() + "/v1/test";
            assertEquals("", ApiHttpClient.send("DELETE", url, null, "tok").get(5, TimeUnit.SECONDS));
            assertEquals(List.of("DELETE"), seenMethods);
        } finally {
            s.stop(0);
        }
    }

    /** The access token must reach the wire as an OAuth2 bearer header. */
    @Test
    public void bearerHeaderForwardedWhenTokenPresent() throws Exception {
        String url = startServer(200, "");
        ApiHttpClient.send("GET", url, null, "tok123").get(5, TimeUnit.SECONDS);
        assertEquals(List.of("Bearer tok123"), seenAuthorization);

        seenAuthorization.clear();
        ApiHttpClient.send("GET", url, null, null).get(5, TimeUnit.SECONDS);
        assertEquals("no token -> no Authorization header",
                java.util.Arrays.asList(new String[] { null }), seenAuthorization);
    }

    @Test
    public void unauthorizedFlagSetFor401() throws Exception {
        String url = startServer(401, "{\"error\":\"token expired\"}");
        ApiException e = failureOf(ApiHttpClient.send("GET", url, null, "tok"));
        assertTrue(e.isUnauthorized());
        assertFalse(e.isSubscriptionRequired());
    }

    @Test
    public void subscriptionRequiredDetectedCaseInsensitively() throws Exception {
        for (String body : new String[] { "Subscription required", "SUBSCRIPTION REQUIRED",
                "an active subscription is needed" }) {
            String url = startServer(403, body);
            ApiException e = failureOf(ApiHttpClient.send("GET", url, null, "tok"));
            assertTrue(e.isSubscriptionRequired());
            assertFalse(e.isUnauthorized());
        }
    }

    @Test
    public void forbiddenWithoutSubscriptionWordIsPlainError() throws Exception {
        String url = startServer(403, "{\"error\":\"forbidden\"}");
        ApiException e = failureOf(ApiHttpClient.send("GET", url, null, "tok"));
        assertFalse(e.isUnauthorized());
        assertFalse(e.isSubscriptionRequired());
    }

    @Test
    public void serverErrorCarriesNoFlags() throws Exception {
        String url = startServer(500, "boom");
        ApiException e = failureOf(ApiHttpClient.send("GET", url, null, "tok"));
        assertFalse(e.isUnauthorized());
        assertFalse(e.isSubscriptionRequired());
        assertTrue(e.getMessage().contains("HTTP 500"));
    }

    /** Oversized error bodies are truncated so logs stay readable. */
    @Test
    public void largeErrorBodyTruncatedInMessage() throws Exception {
        String big = "x".repeat(2000);
        String url = startServer(503, big);
        ApiException e = failureOf(ApiHttpClient.send("GET", url, null, "tok"));
        assertFalse(e.getMessage().contains(big));
        assertTrue(e.getMessage().endsWith("..."));
    }
}
