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
public class ModelAccessorsTest {

    // ==================== DeviceData ====================

    @Test
    public void featureFlags() {
        DeviceData data = new DeviceData();
        data.features = List.of("cooling", "elevation", "audio");
        assertTrue(data.isPod());
        assertTrue(data.hasBase());
        assertTrue(data.hasSpeaker());

        DeviceData bare = new DeviceData();
        assertFalse(bare.isPod());
        assertFalse(bare.hasBase());
        assertFalse(bare.hasSpeaker());
    }

    @Test
    public void heatingGettersAreSideCaseInsensitive() {
        DeviceData data = new DeviceData();
        data.leftHeatingLevel = -10.0;
        data.rightHeatingLevel = 20.0;
        data.leftTargetHeatingLevel = -15.0;
        data.rightTargetHeatingLevel = 25.0;

        assertEquals(Double.valueOf(-10), data.getHeatingLevel("left"));
        assertEquals(Double.valueOf(20), data.getHeatingLevel("RIGHT"));
        assertEquals(Double.valueOf(-15), data.getTargetHeatingLevel("Left"));
        assertEquals(Double.valueOf(25), data.getTargetHeatingLevel("right"));
    }

    // ==================== BaseData ====================

    @Test
    public void baseSideGetter() {
        BaseData base = new BaseData();
        base.left = new BaseData.SideData();
        base.left.inSnoreMitigation = Boolean.TRUE;

        assertEquals(base.left, base.getSide("left"));
        assertNull("no right data set", base.getSide("right"));
        // non-"right" (incl. null/blank) resolves to the left entry like the
        // production getters do ("right".equalsIgnoreCase(side) ? right : left)
        assertEquals(base.left, base.getSide(null));
        assertEquals(base.left, base.getSide(""));
    }

    // ==================== PlayerState ====================

    @Test
    public void playerStateDefaultsAndFlags() {
        PlayerState state = new PlayerState();
        assertFalse(state.hasSpeaker());
        assertFalse(state.isPlaying());
        assertFalse(state.isPaused());
        assertEquals(0, state.getVolumePercent());

        state.state = "Playing";
        state.volume = 42;
        state.hardwareInfo = new PlayerState.HardwareInfo();
        assertTrue(state.hasSpeaker());
        assertTrue(state.isPlaying());
        assertFalse(state.isPaused());
        assertEquals(42, state.getVolumePercent());
    }

    // ==================== AccountHandler.UserData helpers ====================

    @Test
    public void userDataBaseSideNullSafe() {
        org.openhab.binding.eightsleep.internal.handler.AccountHandler.UserData data =
                new org.openhab.binding.eightsleep.internal.handler.AccountHandler.UserData();
        assertNull(data.getBaseSide("left"));

        BaseData base = new BaseData();
        base.right = new BaseData.SideData();
        data.baseData = base;
        assertEquals(base.right, data.getBaseSide("right"));
    }
}
