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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.UserCurrentDevice;
import org.openhab.binding.eightsleep.internal.model.UserProfile;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.ThingUID;

/**
 * Tests for the discovery helpers (side normalization, thing-id sanitization,
 * result building) extracted from the scan flow.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DiscoverySanitizeTest {

    // ==================== normalizeSide ====================

    @Test
    public void sidesNormalizeToLowercase() {
        assertEquals("left", BedSideDiscoveryService.normalizeSide("Left"));
        assertEquals("right", BedSideDiscoveryService.normalizeSide("RIGHT "));
        assertEquals("solo", BedSideDiscoveryService.normalizeSide("Solo"));
    }

    /** Null/blank/unknown sides default to "left" like the upstream client. */
    @Test
    public void unknownSidesDefaultToLeft() {
        assertEquals("left", BedSideDiscoveryService.normalizeSide(null));
        assertEquals("left", BedSideDiscoveryService.normalizeSide(""));
        assertEquals("left", BedSideDiscoveryService.normalizeSide("   "));
        assertEquals("left", BedSideDiscoveryService.normalizeSide("diagonal"));
    }

    // ==================== sanitizeForThingId ====================

    @Test
    public void legalCharactersKept() {
        assertEquals("user_abc-123XYZ", BedSideDiscoveryService.sanitizeForThingId("user_abc-123XYZ"));
    }

    @Test
    public void illegalCharactersReplaced() {
        assertEquals("u_1__b", BedSideDiscoveryService.sanitizeForThingId("u:1é#b"));
        assertEquals("a_b_c", BedSideDiscoveryService.sanitizeForThingId("a b/c"));
        assertEquals("plain", BedSideDiscoveryService.sanitizeForThingId("plain"));
    }

    // ==================== buildDiscoveryResult ====================

    private static final ThingUID BRIDGE = new ThingUID("eightsleep", "account", "bridge1");

    /** A profile without a userId cannot produce a thing - must yield null, not throw. */
    @Test
    public void nullUserIdYieldsNoResult() {
        assertNull(BedSideDiscoveryService.buildDiscoveryResult(BRIDGE, "Pod", profile(null, "left")));
    }

    private static UserProfile profile(String userId, String side) {
        var device = new UserCurrentDevice(BedSide.fromString(side), "dev1");
        return new UserProfile(userId, device);
    }

    @Test
    public void resultCarriesUserIdAndNormalizedLabel() {
        DiscoveryResult result = BedSideDiscoveryService.buildDiscoveryResult(BRIDGE, "Master Pod",
                profile("u/1", "Right"));
        assertEquals("eightsleep:bedSide:bridge1:u_1", result.getThingUID().toString());
        assertEquals("u/1", result.getProperties().get("userId"));
        assertEquals("right", result.getProperties().get("label"));
        assertEquals(BRIDGE, result.getBridgeUID());
        // getRepresentationProperty returns the property NAME, not its value
        assertEquals("userId", result.getRepresentationProperty());
        assertTrue("label mentions side and device",
                result.getLabel().contains("Right") && result.getLabel().contains("Master Pod"));
    }

    @Test
    public void soloUserGetsBothLabel() {
        DiscoveryResult result = BedSideDiscoveryService.buildDiscoveryResult(BRIDGE, "Pod",
                profile("solo_user", "solo"));
        assertTrue(result.getLabel().contains("Both"));
        assertEquals("solo", result.getProperties().get("label"));
    }

    @Test
    public void missingSideDefaultsToLeftInResult() {
        DiscoveryResult result = BedSideDiscoveryService.buildDiscoveryResult(BRIDGE, "Pod", profile("u2", null));
        assertEquals("left", result.getProperties().get("label"));
        assertTrue(result.getLabel().contains("Left"));
    }
}
