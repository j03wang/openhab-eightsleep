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
    private final AuthTransport authTransport;

    /**
     * Transport port for OAuth authentication and token refresh.
     */
    public interface AuthTransport {
        CompletableFuture<String> authenticate(String clientId, String clientSecret, String username, String password);

        /**
         * Exchanges a refresh token for new credentials via the
         * {@code refresh_token} grant.
         */
        default CompletableFuture<String> refresh(String clientId, String clientSecret, String refreshToken) {
            return CompletableFuture.failedFuture(new ApiException("Refresh-token grant is not supported"));
        }
    }

    private volatile long expiryEpochSeconds;
    private final java.time.Clock clock;
    private final ApiJsonCodec jsonCodec;

    public TokenManager(String username, String password, @Nullable String clientId, @Nullable String clientSecret) {
        this(username, password, clientId, clientSecret, new ApiHttpClient(), java.time.Clock.systemUTC(),
                new ApiJsonCodec());
    }

    /**
     * Creates a token manager with an injected authentication transport.
     */
    public TokenManager(String username, String password, @Nullable String clientId, @Nullable String clientSecret,
            AuthTransport authTransport) {
        this(username, password, clientId, clientSecret, authTransport, java.time.Clock.systemUTC(),
                new ApiJsonCodec());
    }

    /**
     * Creates a token manager with injected authentication and time dependencies.
     */
    public TokenManager(String username, String password, @Nullable String clientId, @Nullable String clientSecret,
            AuthTransport authTransport, java.time.Clock clock) {
        this(username, password, clientId, clientSecret, authTransport, clock, new ApiJsonCodec());
    }

    /**
     * Creates a token manager from injectable authentication, time, and JSON dependencies.
     *
     * @param username the account username
     * @param password the account password
     * @param clientId the optional OAuth client id
     * @param clientSecret the optional OAuth client secret
     * @param authTransport the OAuth transport
     * @param clock the clock used for token expiry
     * @param jsonCodec the API JSON codec
     */
    public TokenManager(String username, String password, @Nullable String clientId, @Nullable String clientSecret,
            AuthTransport authTransport, java.time.Clock clock, ApiJsonCodec jsonCodec) {
        this.username = username;
        this.password = password;
        // Defaults match the values used by the official mobile app
        this.clientId = clientId != null && !clientId.isBlank() ? clientId : ApiConstants.KNOWN_CLIENT_ID;
        this.clientSecret = clientSecret != null && !clientSecret.isBlank() ? clientSecret
                : ApiConstants.KNOWN_CLIENT_SECRET;
        this.authTransport = authTransport;
        this.clock = clock;
        this.jsonCodec = jsonCodec;
    }

    private @Nullable String accessToken;
    private @Nullable String userId;

    /**
     * Returns a valid access token, refreshing it first when necessary.
     * <p>
     * Blocking convenience for callers already off the scheduler threads; prefer
     * {@link #getAccessTokenAsync()} on scheduler or completion-thread contexts.
     */
    public synchronized String getAccessToken() throws ApiException {
        try {
            return getAccessTokenAsync().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted while authenticating", e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiException("Authentication failed: " + cause.getMessage(), e);
        }
    }

    /**
     * Returns a valid access token without blocking the calling thread; a refresh
     * is triggered first when necessary. Concurrent callers of one refresh share
     * the same in-flight request.
     */
    public synchronized CompletableFuture<String> getAccessTokenAsync() {
        if (accessToken == null
                || clock.instant().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS >= expiryEpochSeconds) {
            CompletableFuture<String> pending = refreshAsync();
            inflightRefresh = pending;
            return pending;
        }
        CompletableFuture<String> pending = inflightRefresh;
        if (pending != null) {
            // A refresh started by another caller has not stored its token yet
            String token = accessToken;
            if (token == null) {
                return pending;
            }
        }
        return CompletableFuture.completedFuture(accessToken);
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

    /** Seconds until the current token expires (negative when already expired). */
    public synchronized long secondsUntilExpiry() {
        return expiryEpochSeconds - clock.instant().getEpochSecond();
    }

    private @Nullable CompletableFuture<String> inflightRefresh;

    private @Nullable String refreshToken;

    private synchronized CompletableFuture<String> refreshAsync() {
        CompletableFuture<AuthResponse> future = new CompletableFuture<>();
        // Prefer the refresh_token grant like the official app; fall back to the
        // password grant when no refresh token is known yet or the exchange fails.
        if (refreshToken != null) {
            authTransport.refresh(clientId, clientSecret, refreshToken).thenAccept(body -> {
                completeAuth(future, body);
            }).exceptionally(ex -> passwordGrantFallback(future));
        } else {
            authTransport.authenticate(clientId, clientSecret, username, password).thenAccept(body -> {
                completeAuth(future, body);
            }).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                future.completeExceptionally(new ApiException("Authentication failed: " + cause.getMessage(), ex));
                return null;
            });
        }

        return future.handle((response, failure) -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                throw failure instanceof java.util.concurrent.CompletionException ce ? ce
                        : new java.util.concurrent.CompletionException(cause instanceof ApiException apiEx ? apiEx
                                : new ApiException(cause.getMessage(), cause));
            }
            applyAuthResponse(response);
            String token = accessToken;
            if (token == null) {
                throw new java.util.concurrent.CompletionException(new ApiException("No access token available"));
            }
            return token;
        });
    }

    private void completeAuth(CompletableFuture<AuthResponse> future, String body) {
        AuthResponse response = jsonCodec.fromJson(body, AuthResponse.class);
        if (response != null && response.getAccessToken() != null && response.getExpiresIn() != null) {
            future.complete(response);
        } else {
            future.completeExceptionally(new ApiException(
                    response == null ? "Empty auth response" : "Authentication response missing token fields"));
        }
    }

    private Void passwordGrantFallback(CompletableFuture<AuthResponse> future) {
        // Refresh failed (expired/revoked token): clear it and re-authenticate.
        synchronized (this) {
            refreshToken = null;
        }
        authTransport.authenticate(clientId, clientSecret, username, password).thenAccept(body -> {
            completeAuth(future, body);
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            future.completeExceptionally(new ApiException("Authentication failed: " + cause.getMessage(), ex));
            return null;
        });
        return null;
    }

    /** Must be called from {@link #refreshAsync}'s completion path only. */
    private void applyAuthResponse(AuthResponse response) {
        Long expiresIn = response.getExpiresIn();
        this.accessToken = response.getAccessToken();
        this.userId = response.getUserId();
        if (response.getRefreshToken() != null) {
            this.refreshToken = response.getRefreshToken();
        }
        this.expiryEpochSeconds = clock.instant().getEpochSecond() + (expiresIn != null ? expiresIn.longValue() : 0);
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
        // The identity key appears as camelCase "userId" in current auth-api
        // responses and as snake_case "user_id" in older captures - map both.
        public @Nullable String userId;
        public @Nullable String user_id;
        public @Nullable String refresh_token;

        public @Nullable String getAccessToken() {
            return access_token;
        }

        public Long getExpiresIn() {
            return expires_in != null ? expires_in.longValue() : null;
        }

        public @Nullable String getUserId() {
            return userId != null ? userId : user_id;
        }

        public @Nullable String getRefreshToken() {
            return refresh_token;
        }
    }

    /** {@code refresh_token} grant body, matching the official app. */
    public static class RefreshRequest {
        public @Nullable String client_id;
        public @Nullable String client_secret;
        public @Nullable String grant_type;
        public @Nullable String refresh_token;

        public static RefreshRequest of(String clientId, String clientSecret, String refreshToken) {
            RefreshRequest request = new RefreshRequest();
            request.client_id = clientId;
            request.client_secret = clientSecret;
            request.grant_type = "refresh_token";
            request.refresh_token = refreshToken;
            return request;
        }
    }
}
