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
package org.openhab.binding.eightsleep.internal.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.handler.AccountHandler;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.UserProfile;
import org.openhab.binding.eightsleep.internal.model.UserProfile.UserCurrentDevice;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;

/**
 * Tests discovery through its published results rather than implementation helpers.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DiscoverySanitizeTest {

    private static final ThingUID BRIDGE_UID = new ThingUID("eightsleep", "account", "bridge1");

    @Test
    public void scanPublishesSanitizedThingWithNormalizedSide() {
        List<DiscoveryResult> results = scan(List.of(profile("u/1", BedSide.RIGHT)), "Master Pod");

        DiscoveryResult result = results.get(0);
        assertEquals("eightsleep:bedSide:bridge1:u_1", result.getThingUID().toString());
        assertEquals("u/1", result.getProperties().get("userId"));
        assertEquals("right", result.getProperties().get("label"));
        assertEquals(BRIDGE_UID, result.getBridgeUID());
        assertEquals("userId", result.getRepresentationProperty());
        assertTrue(result.getLabel().contains("Right") && result.getLabel().contains("Master Pod"));
    }

    @Test
    public void scanPublishesSoloAsBothAndMissingSideAsLeft() {
        List<DiscoveryResult> results = scan(List.of(profile("solo_user", BedSide.SOLO), profile("unknown", null)),
                "Pod");

        assertEquals("solo", results.get(0).getProperties().get("label"));
        assertTrue(results.get(0).getLabel().contains("Both"));
        assertEquals("left", results.get(1).getProperties().get("label"));
        assertTrue(results.get(1).getLabel().contains("Left"));
    }

    private static List<DiscoveryResult> scan(List<UserProfile> profiles, String deviceLabel) {
        Bridge bridge = mock(Bridge.class);
        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        when(bridge.getUID()).thenReturn(BRIDGE_UID);

        EightSleepService service = mock(EightSleepService.class);
        when(service.getHouseholdDevices()).thenReturn(CompletableFuture.completedFuture(Map.of("dev1", deviceLabel)));
        when(service.getUserProfileForDevice("dev1")).thenReturn(CompletableFuture.completedFuture(profiles));

        AccountHandler account = mock(AccountHandler.class);
        when(account.getThing()).thenReturn(bridge);
        when(account.getService()).thenReturn(service);
        when(account.getDeviceId()).thenReturn("dev1");

        List<DiscoveryResult> results = new ArrayList<>();
        BedSideDiscoveryService discovery = new BedSideDiscoveryService(results::add);
        discovery.setThingHandler(account);
        discovery.startScan();
        return results;
    }

    private static UserProfile profile(String userId, BedSide side) {
        return new UserProfile(userId, new UserCurrentDevice(side, "dev1"));
    }
}
