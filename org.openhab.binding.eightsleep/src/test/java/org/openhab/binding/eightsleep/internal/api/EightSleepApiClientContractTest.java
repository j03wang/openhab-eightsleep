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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.dto.ApiResponses;

/**
 * Tests that the API client exposes wire contracts without mapping domain values.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepApiClientContractTest {

    @Test
    public void deviceResponseIsDeserializedIntoContractDto() {
        TokenManager tokenManager = new TokenManager("me@example.com", "password", null, null,
                (clientId, clientSecret, username, password) -> CompletableFuture
                        .completedFuture("{\"access_token\":\"token\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
        EightSleepApiClient client = new EightSleepApiClient(tokenManager,
                (method, url, body, token) -> CompletableFuture
                        .completedFuture("{\"result\":{\"leftHeatingLevel\":-12,\"features\":[\"cooling\"]}}"));

        ApiResponses.DeviceEnvelope response = client.getDevice("device-1").join();

        ApiResponses.Device result = Objects.requireNonNull(response.result);
        assertEquals(Double.valueOf(-12), result.leftHeatingLevel);
        assertEquals("cooling", Objects.requireNonNull(result.features).get(0));
    }

    @Test
    public void deviceIdentifierIsEncodedInRequestUrl() {
        TokenManager tokenManager = new TokenManager("me@example.com", "password", null, null,
                (clientId, clientSecret, username, password) -> CompletableFuture
                        .completedFuture("{\"access_token\":\"token\",\"expires_in\":3600}"));
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        EightSleepApiClient client = new EightSleepApiClient(tokenManager, (method, url, body, token) -> {
            requestedUrl.set(url);
            return CompletableFuture.completedFuture("{\"result\":{}}");
        });

        client.getDevice("pod /+é").join();

        assertEquals(ApiConstants.CLIENT_API_URL + "/devices/pod+%2F%2B%C3%A9", requestedUrl.get());
    }
}
