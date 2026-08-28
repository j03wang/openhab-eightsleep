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
package org.openhab.binding.eightsleep.internal.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.ApiTestFixtures;

/**
 * Tests pure channel-projection decisions.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideChannelLogicTest {

    @Test
    public void meaningfulTargetLevelIsPublished() {
        assertEquals(-41.0, BedSideChannelSync.resolveShownTargetLevel(-41, false, -20.0), 1e-9);
        assertEquals(35.0, BedSideChannelSync.resolveShownTargetLevel(35, true, null), 1e-9);
    }

    @Test
    public void offStateZeroHoldsPreviousTarget() {
        assertEquals(-41.0, BedSideChannelSync.resolveShownTargetLevel(0, false, -41.0), 1e-9);
    }

    @Test
    public void activeNeutralTargetIsPublishedAsZero() {
        assertEquals(0.0, BedSideChannelSync.resolveShownTargetLevel(0, true, -41.0), 1e-9);
    }

    @Test
    public void firstOffStateDefaultsToZero() {
        assertEquals(0.0, BedSideChannelSync.resolveShownTargetLevel(0, false, null), 1e-9);
    }

    @Test
    public void heatingStateFollowsActiveTargetSign() {
        assertEquals("heating", BedSideChannelSync.deriveHeatingState(true, 50));
        assertEquals("cooling", BedSideChannelSync.deriveHeatingState(true, -50));
        assertEquals("idle", BedSideChannelSync.deriveHeatingState(true, 0));
        assertEquals("idle", BedSideChannelSync.deriveHeatingState(false, 80));
    }

    @Test
    public void autopilotTargetComesFromSmartSchedule() {
        var temperature = ApiTestFixtures
                .parseTemperature("{\"smart\":{\"bedTimeLevel\":-32,\"finalSleepLevel\":-12}}");
        assertEquals(Double.valueOf(-32), BedSideChannelSync.autopilotTargetLevel(temperature));
        assertNull(BedSideChannelSync.autopilotTargetLevel(null));
        assertNull(BedSideChannelSync.autopilotTargetLevel(ApiTestFixtures.parseTemperature("{}")));
    }
}
