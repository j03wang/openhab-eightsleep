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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin asynchronous HTTP layer over the JDK {@link HttpClient} used for all
 * Eight Sleep cloud communication.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class ApiHttpClient implements EightSleepApiClient.Transport, TokenManager.AuthTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiHttpClient.class);

    private final HttpClient httpClient;
    private final ApiJsonCodec jsonCodec;

    /**
     * Creates a production transport with a dedicated JDK HTTP client and JSON codec.
     */
    public ApiHttpClient() {
        this(new ApiJsonCodec());
    }

    /**
     * Creates a production transport using the supplied JSON codec.
     *
     * @param jsonCodec the API JSON codec
     */
    public ApiHttpClient(ApiJsonCodec jsonCodec) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL)
                .build(), jsonCodec);
    }

    /**
     * Creates a transport from injectable protocol dependencies.
     *
     * @param httpClient the JDK HTTP client
     * @param jsonCodec the API JSON codec
     */
    public ApiHttpClient(HttpClient httpClient, ApiJsonCodec jsonCodec) {
        this.httpClient = httpClient;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public CompletableFuture<String> authenticate(String clientId, String clientSecret, String username,
            String password) {
        return send("POST", ApiConstants.AUTH_URL,
                jsonCodec.toJson(TokenManager.AuthRequest.of(clientId, clientSecret, username, password)), null);
    }

    @Override
    public CompletableFuture<String> refresh(String clientId, String clientSecret, String refreshToken) {
        return send("POST", ApiConstants.AUTH_URL,
                jsonCodec.toJson(TokenManager.RefreshRequest.of(clientId, clientSecret, refreshToken)), null);
    }

    @Override
    public CompletableFuture<String> send(String method, String url, @Nullable String jsonBody,
            @Nullable String accessToken) {
        return send(method, url, jsonBody, accessToken, Map.of());
    }

    @Override
    public CompletableFuture<String> sendWithHeaders(String method, String url, @Nullable String jsonBody,
            @Nullable String accessToken, Map<String, String> extraHeaders) {
        return send(method, url, jsonBody, accessToken, extraHeaders);
    }

    private CompletableFuture<String> send(String method, String url, @Nullable String jsonBody,
            @Nullable String accessToken, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(ApiConstants.REQUEST_TIMEOUT_SECONDS));

        for (Map.Entry<String, String> header : ApiConstants.DEFAULT_HEADERS.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            default -> builder.method(method,
                    HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "", StandardCharsets.UTF_8));
        }

        HttpRequest request = builder.build();
        LOGGER.debug("{} {}", method, url);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        return CompletableFuture.completedFuture(response.body());
                    }
                    String body = response.body();
                    boolean subscriptionRequired = status == 403 && body != null
                            && body.toLowerCase().contains("subscription");
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(
                            new ApiException("HTTP " + status + " for " + method + " " + url + ": " + truncate(body),
                                    status == 401, subscriptionRequired));
                    return failed;
                });
    }

    private static String truncate(@Nullable String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
