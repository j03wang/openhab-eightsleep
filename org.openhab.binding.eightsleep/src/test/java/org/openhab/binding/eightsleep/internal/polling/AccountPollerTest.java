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
package org.openhab.binding.eightsleep.internal.polling;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.api.TokenManager;

/**
 * Tests user registration cleanup and lifecycle invalidation in {@link AccountPoller}.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountPollerTest {

    private static EightSleepService serviceWithToken(String token) {
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, (cid, cs, u, p) -> CompletableFuture
                .completedFuture("{\"access_token\":\"" + token + "\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
        return new EightSleepService(new EightSleepApiClient(manager,
                (method, url, jsonBody, accessToken) -> CompletableFuture.completedFuture("{}")));
    }

    /**
     * close() forgets registered users: after close+register the next user poll
     * only touches users registered with the replacement poller.
     */
    @Test
    public void closeDropsRegisteredUsers() {
        AtomicInteger cacheCreates = new AtomicInteger();
        AccountPoller poller = new AccountPoller(serviceWithToken("t-a"), "dev-1", key -> {
            cacheCreates.incrementAndGet();
            return new UserDataCache();
        });

        poller.register("u-old");
        poller.close();
        poller.register("u-new");

        // The away-state poll fans out over device users regardless of registration,
        // but pollUserData only touches registered users: u-old must not be polled.
        poller.pollUserData(3);

        // Give the async fan-out a moment to settle; assertions are on observable
        // effects via the cache factory (bounded wait, no fixed sleeps beyond 1s).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (cacheCreates.get() < 1 && System.nanoTime() < deadline) {
            Thread.yield();
        }

        // pollUserData issues five fan-out requests per registered user
        // (trends, player, alarms, temperature, pillow): only the re-registered
        // user may be polled after close() dropped the old registration.
        assertEquals("only the re-registered user may be polled", 5, cacheCreates.get());
    }

    @Test
    public void inactivePollerDiscardsCompletedCallbacks() {
        AtomicInteger cacheCreates = new AtomicInteger();
        AccountPoller poller = new AccountPoller(serviceWithToken("t-a"), "dev-1", key -> {
            cacheCreates.incrementAndGet();
            return new UserDataCache();
        }, () -> false);

        poller.register("u1");
        poller.pollUserData(3);

        assertEquals(0, cacheCreates.get());
    }
}
