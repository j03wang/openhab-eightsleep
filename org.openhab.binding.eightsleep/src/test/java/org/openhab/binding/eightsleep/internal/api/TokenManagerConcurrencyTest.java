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
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Concurrency guard: when an expired token is hit by many threads at once,
 * exactly ONE auth request may be issued - the rest must reuse the refreshed
 * token. A thundering herd of OAuth logins would trip rate limits.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TokenManagerConcurrencyTest {

    private static final int THREADS = 12;

    private static class CountingAuth implements TokenManager.AuthTransport {
        int calls;
        int completed;

        @Override
        public synchronized CompletableFuture<String> authenticate(String clientId, String clientSecret,
                String username, String password) {
            calls++;
            // simulate a slow network round-trip so threads pile up behind the lock
            CompletableFuture<String> future = new CompletableFuture<>();
            Thread runner = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                future.complete("{\"access_token\":\"tok\",\"expires_in\":3600,\"user_id\":\"u1\"}");
            });
            runner.start();
            return future;
        }
    }

    @Test
    public void concurrentGetAccessTokenIssuesExactlyOneAuthCall() throws Exception {
        CountingAuth auth = new CountingAuth();
        // 1s expiry: every caller after the first would refresh if caching were broken
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<String> tokens = new CopyOnWriteArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    tokens.add(manager.getAccessToken());
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        pool.shutdown();
        assertTrue("pool did not finish", pool.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(failures.toString(), 0, failures.size());
        assertEquals(THREADS, tokens.size());
        assertEquals("all callers must see the same token", 1,
                tokens.stream().distinct().count());
        assertEquals("thundering herd must collapse into one auth call", 1, auth.calls);
    }

    /**
     * After invalidate() (401 handling) racing callers must also produce a single
     * fresh fetch - two parallel 401 handlers must not double-authenticate.
     */
    @Test
    public void invalidateThenRacingCallersFetchOnce() throws Exception {
        CountingAuth auth = new CountingAuth();
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null, auth);
        manager.getAccessToken();
        manager.invalidate();

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    manager.getAccessToken();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(failures.toString(), 0, failures.size());
        assertEquals("invalidate + herd must still be a single refetch", 2, auth.calls);
    }
}
