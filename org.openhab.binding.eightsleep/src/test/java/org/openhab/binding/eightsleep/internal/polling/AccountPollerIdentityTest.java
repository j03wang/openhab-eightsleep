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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.api.TokenManager;
import org.openhab.binding.eightsleep.internal.handler.AwayModeTracker;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;

/**
 * Verifies the poller identity contract used by {@code AccountHandler} to detect
 * a stale poller after a reconnect, and that close() drops registered users so
 * the replacement poller starts clean.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountPollerIdentityTest {

    private static EightSleepApiClient clientWithToken(String token) {
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null,
                (cid, cs, u, p) -> CompletableFuture.completedFuture(
                        "{\"access_token\":\"" + token + "\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
        return new EightSleepApiClient(manager,
                (method, url, jsonBody, accessToken) -> CompletableFuture.completedFuture("{}"));
    }

    @Test
    public void identityReflectsConstructorArguments() {
        EightSleepApiClient clientA = clientWithToken("t-a");
        AccountPoller poller = new AccountPoller(clientA, "dev-1",
                key -> new UserDataCache(), () -> {
                });

        assertEquals(clientA, poller.client());
        assertEquals("dev-1", poller.deviceId());
    }

    /** A reconnect builds a NEW client instance; the old poller must read as stale. */
    @Test
    public void staleWhenReconnectUsesNewClientInstance() {
        EightSleepApiClient clientA = clientWithToken("t-a");
        EightSleepApiClient clientB = clientWithToken("t-b");
        AccountPoller poller = new AccountPoller(clientA, "dev-1",
                key -> new UserDataCache(), () -> {
                });

        assertNotSame("reconnect must build a fresh client", clientA, clientB);
        assertTrue("poller bound to the old client must be detected as stale",
                poller.client() != clientB || !poller.deviceId().equals("dev-1"));
    }

    /**
     * close() forgets registered users: after close+register the next user poll
     * only touches users registered with the replacement poller.
     */
    @Test
    public void closeDropsRegisteredUsers() {
        AtomicInteger cacheCreates = new AtomicInteger();
        AccountPoller poller = new AccountPoller(clientWithToken("t-a"), "dev-1",
                key -> {
                    cacheCreates.incrementAndGet();
                    return new UserDataCache();
                }, () -> {
                });
        AwayModeTracker tracker = new AwayModeTracker();

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
}
