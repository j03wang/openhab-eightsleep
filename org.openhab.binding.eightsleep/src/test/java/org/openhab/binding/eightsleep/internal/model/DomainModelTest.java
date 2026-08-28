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
package org.openhab.binding.eightsleep.internal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Accessor-level tests for the small typed models (feature flags, side getters,
 * speaker state) that the handlers rely on.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DomainModelTest {

    @Test
    public void featureFlags() {
        DeviceState data = deviceState(-10.0, 20.0, -15.0, 25.0, List.of("cooling", "elevation", "audio"));
        assertTrue(data.isPod());
        assertTrue(data.hasBase());
        assertTrue(data.hasSpeaker());

        DeviceState bare = DeviceState.EMPTY;
        assertFalse(bare.isPod());
        assertFalse(bare.hasBase());
        assertFalse(bare.hasSpeaker());
    }

    @Test
    public void heatingGettersUseTypedSide() {
        DeviceState data = deviceState(-10.0, 20.0, -15.0, 25.0, List.of());

        assertEquals(Double.valueOf(-10), data.heatingLevel(BedSide.LEFT));
        assertEquals(Double.valueOf(20), data.heatingLevel(BedSide.RIGHT));
        assertEquals(Double.valueOf(-15), data.targetHeatingLevel(BedSide.SOLO));
        assertEquals(Double.valueOf(25), data.targetHeatingLevel(BedSide.RIGHT));
    }

    @Test
    public void baseSideGetter() {
        BaseState.SideState left = new BaseState.SideState(null, null, null, true);
        BaseState base = new BaseState(left, null);

        assertEquals(left, base.side(BedSide.LEFT));
        assertEquals(left, base.side(BedSide.SOLO));
        assertNull("no right data set", base.side(BedSide.RIGHT));
    }

    // ==================== PlayerState ====================

    @Test
    public void playerStateDefaultsAndFlags() {
        PlayerState state = PlayerState.EMPTY;
        assertFalse(state.hasSpeaker());
        assertFalse(state.isPlaying());
        assertFalse(state.isPaused());
        assertNull("missing volume must stay unknown, not read as level zero", state.volume());

        state = new PlayerState("Playing", 42, null, true);
        assertTrue(state.hasSpeaker());
        assertTrue(state.isPlaying());
        assertFalse(state.isPaused());
        assertEquals(42, state.volume().intValue());
    }

    private static DeviceState deviceState(Double leftLevel, Double rightLevel, Double leftTarget, Double rightTarget,
            List<String> features) {
        return new DeviceState(leftLevel, rightLevel, leftTarget, rightTarget, null, null, null, null, null, null, null,
                null, null, features);
    }
}
