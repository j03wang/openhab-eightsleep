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
package org.openhab.binding.eightsleep.internal.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.api.TokenManager;
import org.openhab.binding.eightsleep.internal.model.AccountConfigParser;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;
import org.openhab.core.thing.Bridge;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Tests for the last-write-wins bookkeeping that spans AccountHandler (away
 * command stamping, registration counting) using a scripted API transport -
 * no openHAB framework objects required.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountLwwLogicTest {

    // ==================== away LWW ordering (via LastWriteWins) ====================

    /**
     * The away merge shares {@link LastWriteWins#resolveLatest} with every other
     * mutable channel: a poll that STARTED before the command carries pre-command
     * data and loses; ties go to the polled value.
     */
    @Test
    public void awayPollOrderingTable() {
        Instant commandAt = Instant.parse("2026-08-22T15:00:00Z");
        var command = new LastWriteWins.CommandedValue(commandAt, true);

        // no command -> polled value wins
        assertEquals(Boolean.TRUE, LastWriteWins.resolveLatest(true, commandAt, null));

        // a poll that STARTED before the command carries pre-command data: the
        // COMMAND's value resolves regardless of what the stale poll observed.
        assertEquals(Boolean.TRUE, LastWriteWins.resolveLatest(false, commandAt.minusSeconds(60), command));
        assertEquals(Boolean.TRUE, LastWriteWins.resolveLatest(true, commandAt.minusSeconds(60), command));

        // an exact tie and later polls follow the polled value - server truth even
        // when it still reports the old value (not yet applied, or changed back)
        assertEquals(Boolean.FALSE, LastWriteWins.resolveLatest(false, commandAt, command));
        assertEquals(Boolean.TRUE, LastWriteWins.resolveLatest(true, commandAt.plusNanos(1), command));
        assertEquals(Boolean.FALSE, LastWriteWins.resolveLatest(false, commandAt.plusNanos(1), command));
    }

    // ==================== clampInterval ====================

    @Test
    public void intervalClamping() {
        assertEquals(15, AccountConfigParser.clampInterval(1, 15, 600));
        assertEquals(600, AccountConfigParser.clampInterval(99999, 15, 600));
        assertEquals(60, AccountConfigParser.clampInterval(60, 15, 600));
        assertEquals(15, AccountConfigParser.clampInterval(15, 15, 600));
    }

    // ==================== parseTemperatureUnit ====================

    @Test
    public void unitParsing() {
        assertEquals('f', AccountConfigParser.parseTemperatureUnit("F", 'c'));
        assertEquals('f', AccountConfigParser.parseTemperatureUnit(" fahrenheit ", 'c'));
        assertEquals('c', AccountConfigParser.parseTemperatureUnit("C", 'x'));
        assertEquals('c', AccountConfigParser.parseTemperatureUnit("celsius", 'c'));
        assertEquals('k', AccountConfigParser.parseTemperatureUnit("", 'k'));
        assertEquals('k', AccountConfigParser.parseTemperatureUnit("   ", 'k'));
        assertEquals('k', AccountConfigParser.parseTemperatureUnit("kelvin", 'k'));
        assertEquals('c', AccountConfigParser.parseTemperatureUnit("42", 'c'));
    }

    // ==================== chooseDeviceId ====================

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AccountLwwLogicTest.class);

    @Test
    public void configuredDevicePreferredWhenKnown() {
        var devices = new java.util.LinkedHashMap<>(java.util.Map.of(
                "dev_b", "B", "dev_a", "A"));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "dev_a", LOG));
    }

    /** Unknown configured id falls back to the first sorted device (stable across restarts). */
    @Test
    public void unknownConfiguredDeviceFallsBackToFirstSorted() {
        var devices = new java.util.LinkedHashMap<>(java.util.Map.of(
                "dev_z", "Z", "dev_a", "A"));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "ghost", LOG));
    }

    /** Blank/null configuration picks the first device in sorted order, not encounter order. */
    @Test
    public void blankConfigurationPicksFirstSorted() {
        var devices = new java.util.LinkedHashMap<>(java.util.Map.of(
                "dev_z", "Z", "dev_a", "A"));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "", LOG));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, null, LOG));
        // whitespace-only config is treated like blank
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "  ", LOG));
    }

    // ==================== 1:1 registration on the REAL handler ====================

    /**
     * Exercises the actual {@link AccountHandler#registerBedSide}/{@link #unregisterBedSide}
     * implementations over a bare Bridge instance (no framework runtime needed): a user
     * owns at most one bed side (1:1), re-registration is idempotent, cached user data
     * is dropped on unregister, and unknown unregistration is a no-op.
     */
    @Test
    public void registrationOnRealHandler() {
        Bridge bridge = mock(Bridge.class);
        AccountHandler account = new AccountHandler(bridge);

        assertTrue("first registration returns true", account.registerBedSide("u1", "left"));
        assertFalse("re-registration (e.g. bridgeStatusChanged) is idempotent", account.registerBedSide("u1", "left"));
        assertFalse("a second thing claiming the same user (1:1 model) is rejected", account.registerBedSide("u1", "right"));

        // a polled observation creates the entry; unregister drops it
        account.getUserDataOrCreate("u1");
        assertNotNull(account.getUserData("u1"));
        account.unregisterBedSide("u1");
        assertNull("unregister drops the user's cache immediately", account.getUserData("u1"));
        assertTrue("after unregister the userId can be registered again", account.registerBedSide("u1", "right"));

        // unregistering an unknown user must not throw
        account.unregisterBedSide("ghost");
    }

    /**
     * End-to-end sanity: a client with a scripted transport issues commands with
     * the token from its TokenManager - guards against wiring regressions when
     * the transport seam changes.
     */
    @Test
    public void clientIssuesCommandsWithTokenFromManager() throws Exception {
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        EightSleepApiClient.Transport transport = (method, url, jsonBody, accessToken) -> {
            seen.add(method + " " + url + " auth=" + accessToken + " body=" + jsonBody);
            CompletableFuture<String> done = new CompletableFuture<>();
            done.complete("");
            return done;
        };
        TokenManager manager = new TokenManager("me@x.com", "pw", null, null,
                (cid, cs, u, p) -> CompletableFuture
                        .completedFuture("{\"access_token\":\"t0\",\"expires_in\":3600,\"user_id\":\"u1\"}"));
        EightSleepApiClient client = new EightSleepApiClient(manager, transport);

        EightSleepApiClient.join(client.turnOffSide("u1"));
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).contains("auth=t0"));
        assertTrue(seen.get(0).contains("\"currentState\":{\"type\":\"off\"}"));
    }
}
