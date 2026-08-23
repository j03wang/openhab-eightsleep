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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.EightSleepBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin asynchronous HTTP layer over the JDK {@link HttpClient} used for all
 * Eight Sleep cloud communication.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ApiHttpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiHttpClient.class);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private ApiHttpClient() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public static CompletableFuture<String> getJson(String url, @Nullable String accessToken) {
        return send("GET", url, null, accessToken);
    }

    public static CompletableFuture<String> postJson(String url, @Nullable Object jsonBody,
            @Nullable String accessToken) {
        return send("POST", url, GsonHelper.toJson(jsonBody), accessToken);
    }

    public static CompletableFuture<String> putJson(String url, @Nullable Object jsonBody,
            @Nullable String accessToken) {
        return send("PUT", url, GsonHelper.toJson(jsonBody), accessToken);
    }

    public static CompletableFuture<String> send(String method, String url, @Nullable String jsonBody,
            @Nullable String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(ApiConstants.REQUEST_TIMEOUT_SECONDS));

        for (Map.Entry<String, String> header : ApiConstants.DEFAULT_HEADERS.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            default -> builder.method(method, HttpRequest.BodyPublishers.ofString(
                    jsonBody != null ? jsonBody : "", StandardCharsets.UTF_8));
        }

        HttpRequest request = builder.build();
        LOGGER.debug("{} {}", method, url);

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    int status = response.statusCode();
                    // Single-line body capture for fixture extraction:
                    // grep 'EIGHTSLEEP-BODY' openhab.log
                    if (LOGGER.isTraceEnabled()) {
                        String path = URI.create(url).getPath().replaceFirst("^/v[0-9]+", "")
                                .replaceAll("[a-f0-9-]{16,}", "{id}")
                                .replaceAll("/\\d{4,}", "/{id}");
                        LOGGER.trace("EIGHTSLEEP-BODY {} {} {} {}", method, path, status,
                                response.body() == null ? "" : response.body().replace('\n', ' ').trim());
                    }
                    if (status >= 200 && status < 300) {
                        return CompletableFuture.completedFuture(response.body());
                    }
                    String body = response.body();
                    boolean subscriptionRequired = status == 403 && body != null
                            && body.toLowerCase().contains("subscription");
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new ApiException(
                            "HTTP " + status + " for " + method + " " + url + ": " + truncate(body),
                            status == 401, subscriptionRequired));
                    return failed;
                });
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String truncate(@Nullable String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
