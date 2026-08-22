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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authentication provider that keeps an Eight Sleep OAuth token fresh.
 * <p>
 * The token is refreshed proactively {@link #TOKEN_EXPIRY_BUFFER_SECONDS} before
 * it expires, and on demand after a 401 response from the API.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TokenManager {

    /** Refresh the token this many seconds before it actually expires. */
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 120;

    private final Logger logger = LoggerFactory.getLogger(TokenManager.class);

    private final String username;
    private final String password;
    private final String clientId;
    private final String clientSecret;

    private @Nullable String accessToken;
    private @Nullable String userId;
    private volatile long expiryEpochSeconds;

    public TokenManager(String username, String password, @Nullable String clientId, @Nullable String clientSecret) {
        this.username = username;
        this.password = password;
        // Defaults match the values used by the official mobile app
        this.clientId = clientId != null && !clientId.isBlank() ? clientId : ApiConstants.KNOWN_CLIENT_ID;
        this.clientSecret = clientSecret != null && !clientSecret.isBlank() ? clientSecret
                : ApiConstants.KNOWN_CLIENT_SECRET;
    }

    /**
     * Returns a valid access token, refreshing it first when necessary.
     */
    public synchronized String getAccessToken() throws ApiException {
        if (accessToken == null || Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS >= expiryEpochSeconds) {
            refresh();
        }
        String token = accessToken;
        if (token == null) {
            throw new ApiException("No access token available");
        }
        return token;
    }

    /**
     * Forces a token refresh. Called by the API client after a 401 response.
     */
    public synchronized void invalidate() {
        accessToken = null;
    }

    public synchronized @Nullable String getUserId() {
        return userId;
    }

    private void refresh() throws ApiException {
        CompletableFuture<AuthResponse> future = new CompletableFuture<>();
        ApiHttpClient.postJson(ApiConstants.AUTH_URL, AuthRequest.of(clientId, clientSecret, username, password), null)
                .thenAccept(body -> {
                    AuthResponse response = GsonHelper.fromJson(body, AuthResponse.class);
                    if (response != null) {
                        future.complete(response);
                    } else {
                        future.completeExceptionally(new ApiException("Empty auth response"));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    future.completeExceptionally(new ApiException("Authentication failed: " + cause.getMessage(), ex));
                    return null;
                });

        AuthResponse response;
        try {
            response = future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted while authenticating", e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiException("Authentication failed: " + cause.getMessage(), e);
        }

        this.accessToken = response.getAccessToken();
        Long expiresIn = response.getExpiresIn();
        this.userId = response.getUserId();
        if (accessToken == null || expiresIn == null) {
            throw new ApiException("Authentication response missing token fields");
        }
        this.expiryEpochSeconds = Instant.now().getEpochSecond() + expiresIn.longValue();
        logger.debug("Obtained new access token for {}, expires in {}s", username, expiresIn);
    }

    public static class AuthRequest {
        // Field names must be the OAuth2 standard snake_case keys the API expects
        public @Nullable String client_id;
        public @Nullable String client_secret;
        public @Nullable String grant_type;
        public @Nullable String username;
        public @Nullable String password;

        public static AuthRequest of(String clientId, String clientSecret, String username, String password) {
            AuthRequest request = new AuthRequest();
            request.client_id = clientId;
            request.client_secret = clientSecret;
            request.grant_type = "password";
            request.username = username;
            request.password = password;
            return request;
        }
    }

    @SuppressWarnings("unused")
    private static class AuthResponse {
        public @Nullable String access_token;
        public @Nullable Double expires_in;
        public @Nullable String user_id;
        public @Nullable String refresh_token;

        public @Nullable String getAccessToken() {
            return access_token;
        }

        public Long getExpiresIn() {
            return expires_in != null ? expires_in.longValue() : null;
        }

        public @Nullable String getUserId() {
            return user_id;
        }
    }
}
