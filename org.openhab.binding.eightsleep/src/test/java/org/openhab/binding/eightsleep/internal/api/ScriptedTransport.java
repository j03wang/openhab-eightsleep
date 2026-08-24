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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Shared scripted {@link EightSleepApiClient.Transport} for network-free client
 * tests: records each request as {@code METHOD url body=... token=...} and serves
 * queued responses in order.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ScriptedTransport implements EightSleepApiClient.Transport {

    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final List<CompletableFuture<String>> script = new CopyOnWriteArrayList<>();

    /** Queues a successful response body for the next send call. */
    public void enqueueSuccess(String body) {
        script.add(CompletableFuture.completedFuture(body));
    }

    /** Queues {@code count} empty successful responses. */
    public void enqueueSuccesses(int count) {
        for (int i = 0; i < count; i++) {
            enqueueSuccess("");
        }
    }

    /** Queues a failed response for the next send call. */
    public void enqueueFailure(ApiException e) {
        CompletableFuture<String> f = new CompletableFuture<>();
        f.completeExceptionally(e);
        script.add(f);
    }

    @Override
    public CompletableFuture<String> send(String method, String url, @Nullable String jsonBody,
            @Nullable String accessToken) {
        requests.add(method + " " + url + " body=" + jsonBody + " token=" + accessToken);
        return script.remove(0);
    }
}
