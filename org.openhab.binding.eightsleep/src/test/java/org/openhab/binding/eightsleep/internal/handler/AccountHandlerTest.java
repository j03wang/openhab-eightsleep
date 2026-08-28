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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.api.TokenManager;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Tests account-handler decisions and bed-side registration ownership.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountHandlerTest {

    @Test
    public void configuredDeviceIsSelectedThroughConnectionLifecycle() throws Exception {
        var devices = new LinkedHashMap<>(Map.of("dev_b", "B", "dev_a", "A"));
        AccountHandler account = connectingAccount(devices, "dev_b");
        try {
            awaitDevice(account);
            assertEquals("dev_b", account.getDeviceId());
        } finally {
            account.dispose();
        }
    }

    @Test
    public void unknownConfiguredDeviceFallsBackThroughConnectionLifecycle() throws Exception {
        var devices = new LinkedHashMap<>(Map.of("dev_z", "Z", "dev_a", "A"));
        AccountHandler account = connectingAccount(devices, "ghost");
        try {
            awaitDevice(account);
            assertEquals("dev_a", account.getDeviceId());
        } finally {
            account.dispose();
        }
    }

    @Test
    public void registrationIsUniqueAndDropsCachedData() {
        Bridge bridge = mock(Bridge.class);
        AccountHandler account = new AccountHandler(bridge, config -> {
            throw new AssertionError("connection factory must not be used by registration operations");
        });
        account.setCallback(mock(ThingHandlerCallback.class));

        assertTrue(account.registerBedSide("u1", BedSide.LEFT));
        assertFalse(account.registerBedSide("u1", BedSide.LEFT));
        assertFalse(account.registerBedSide("u1", BedSide.RIGHT));

        account.getUserDataOrCreate("u1");
        assertNotNull(account.getUserData("u1"));
        account.unregisterBedSide("u1");
        assertNull(account.getUserData("u1"));
        assertTrue(account.registerBedSide("u1", BedSide.RIGHT));

        account.unregisterBedSide("ghost");
        account.dispose();
    }

    private static AccountHandler connectingAccount(Map<String, String> devices, String configuredDevice) {
        Bridge bridge = mock(Bridge.class);
        Configuration configuration = new Configuration();
        configuration.put("username", "me@example.com");
        configuration.put("password", "secret");
        configuration.put("deviceId", configuredDevice);
        when(bridge.getConfiguration()).thenReturn(configuration);
        when(bridge.getProperties()).thenReturn(Map.of());

        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.getAccessTokenAsync()).thenReturn(CompletableFuture.completedFuture("token"));
        EightSleepService service = mock(EightSleepService.class);
        when(service.getHouseholdDevices()).thenReturn(CompletableFuture.completedFuture(devices));
        when(service.getDeviceState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(DeviceState.EMPTY));

        AccountHandler account = new AccountHandler(bridge,
                config -> new AccountHandler.AccountConnection(tokenManager, service));
        account.setCallback(mock(ThingHandlerCallback.class));
        account.initialize();
        return account;
    }

    private static void awaitDevice(AccountHandler account) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (account.getDeviceId() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull("device selection did not complete", account.getDeviceId());
    }
}
