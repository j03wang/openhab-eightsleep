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

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.core.thing.Bridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests account-handler decisions and bed-side registration ownership.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountHandlerTest {

    private static final Logger LOG = LoggerFactory.getLogger(AccountHandlerTest.class);

    @Test
    public void configuredDevicePreferredWhenKnown() {
        var devices = new LinkedHashMap<>(Map.of("dev_b", "B", "dev_a", "A"));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "dev_a", LOG));
    }

    @Test
    public void unknownConfiguredDeviceFallsBackToFirstSorted() {
        var devices = new LinkedHashMap<>(Map.of("dev_z", "Z", "dev_a", "A"));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "ghost", LOG));
    }

    @Test
    public void blankConfigurationPicksFirstSorted() {
        var devices = new LinkedHashMap<>(Map.of("dev_z", "Z", "dev_a", "A"));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "", LOG));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, null, LOG));
        assertEquals("dev_a", AccountHandler.chooseDeviceId(devices, "  ", LOG));
    }

    @Test
    public void registrationIsUniqueAndDropsCachedData() {
        Bridge bridge = mock(Bridge.class);
        AccountHandler account = new AccountHandler(bridge);

        assertTrue(account.registerBedSide("u1", BedSide.LEFT));
        assertFalse(account.registerBedSide("u1", BedSide.LEFT));
        assertFalse(account.registerBedSide("u1", BedSide.RIGHT));

        account.getUserDataOrCreate("u1");
        assertNotNull(account.getUserData("u1"));
        account.unregisterBedSide("u1");
        assertNull(account.getUserData("u1"));
        assertTrue(account.registerBedSide("u1", BedSide.RIGHT));

        account.unregisterBedSide("ghost");
    }
}
