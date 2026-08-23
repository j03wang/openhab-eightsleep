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

    // ==================== acceptsPolledAway ordering table ====================

    @Test
    public void awayPollOrderingTable() {
        Instant commandAt = Instant.parse("2026-08-22T15:00:00Z");

        // no command -> always accept
        assertTrue(AwayModeTracker.acceptsPolledAway(null, commandAt));
        assertTrue(AwayModeTracker.acceptsPolledAway(null, commandAt.plusSeconds(600)));

        // a poll that STARTED before the command carries pre-command data: rejected.
        // An exact tie follows the LWW convention - ties go to the polled value.
        assertFalse(AwayModeTracker.acceptsPolledAway(commandAt, commandAt.minusSeconds(60)));
        assertTrue(AwayModeTracker.acceptsPolledAway(commandAt, commandAt));
        // a poll started after the command is newer information: accepted - even if it
        // still reports the old value, because that is server truth (not yet applied,
        // or changed back from elsewhere)
        assertTrue(AwayModeTracker.acceptsPolledAway(commandAt, commandAt.plusSeconds(1)));
        assertTrue(AwayModeTracker.acceptsPolledAway(commandAt, commandAt.plusNanos(1)));
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

    // ==================== registration counting on the REAL handler ====================

    /**
     * Exercises the actual {@link AccountHandler#registerBedSide}/{@link #unregisterBedSide}
     * implementations over a bare Bridge instance (no framework runtime needed): the
     * first registration wins the side slot, re-registration is idempotent, cached user
     * data survives until the last reference goes away, and unknown unregistration is a no-op.
     */
    @Test
    public void referenceCountingOnRealHandler() {
        Bridge bridge = mock(Bridge.class);
        AccountHandler account = new AccountHandler(bridge);

        assertTrue("first registration returns true", account.registerBedSide("u1", "left"));
        assertFalse("re-registration (e.g. bridgeStatusChanged) is idempotent", account.registerBedSide("u1", "left"));
        assertFalse(account.registerBedSide("u1", "right"));

        // cached data exists while at least one thing references the user
        account.setLastKnownAwayMode("u1", true);
        assertNotNull(account.getUserData("u1"));

        assertFalse("data must survive while references remain", dropsReference(account, "u1"));
        assertFalse("still referenced after the second dispose", dropsReference(account, "u1"));
        assertNotNull(account.getUserData("u1"));
        assertTrue("last unregister drops the user entirely", dropsReference(account, "u1"));
        assertNull("user fully removed", account.getUserData("u1"));

        // unregistering an unknown user must not throw
        account.unregisterBedSide("ghost");
    }

    /** One unregister call; mirrors dispose() semantics by returning whether data was dropped. */
    private static boolean dropsReference(AccountHandler account, String userId) {
        int sizeBefore = java.util.Optional.ofNullable(account.getUserData(userId)).map(u -> 1).orElse(0);
        account.unregisterBedSide(userId);
        return sizeBefore == 1 && account.getUserData(userId) == null;
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
