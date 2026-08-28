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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.ApiTestFixtures;

/**
 * Truth-table tests for the away-mode read model and the pillow lookup.
 * <p>
 * Live-verified semantics: a user is AWAY when listed in {@code awaySides} AND
 * no longer occupying their side slot; a present user still occupies a slot
 * even though the server keeps listing them in awaySides.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DeviceAndPillowModelTest {

    private static DeviceAssignments users(String left, String right, Map<String, String> awaySides) {
        return new DeviceAssignments(left, right, awaySides);
    }

    @Test
    public void awayWhenListedAndSlotRemoved() {
        var users = users(null, "u_right", Map.of("leftUserId", "u_left"));
        assertTrue(users.isAway("u_left"));
        assertFalse(users.isAway("u_right"));
    }

    @Test
    public void presentWhenStillOccupyingSide() {
        // live captures list present users in awaySides too (stale record)
        var users = users("u_left", "u_right", Map.of("leftUserId", "u_left", "rightUserId", "u_right"));
        assertFalse("occupying side means present", users.isAway("u_left"));
        assertFalse(users.isAway("u_right"));
    }

    @Test
    public void unknownUserIsNeverAway() {
        var users = users(null, null, Map.of("leftUserId", "u_left"));
        assertFalse(users.isAway("u_stranger"));
        assertFalse(users.isAway(null));
    }

    @Test
    public void emptyAwaySidesMeansEveryonePresent() {
        var users = users("u_left", "u_right", Map.of());
        assertFalse(users.isAway("u_left"));
        assertFalse(users.isAway("u_right"));
    }

    @Test
    public void awayUserOnOppositeSideSlotStaysPresent() {
        // u_x is in awaySides but ALSO occupies the right slot: present wins
        var users = users(null, "u_x", Map.of("leftUserId", "u_x"));
        assertFalse(users.isAway("u_x"));
    }

    private static String payload(String entriesJson) {
        return "{\"devices\":" + entriesJson + "}";
    }

    private static final String PILLOW_LEFT = """
            {"device":{"specialization":"pillow","side":"left","deviceId":"pil_l"},"currentLevel":-10,
             "currentState":{"type":"smart"}}""";
    private static final String PILLOW_RIGHT = """
            {"device":{"specialization":"pillow","side":"right","deviceId":"pil_r"},"currentLevel":5}""";
    private static final String POD = """
            {"device":{"specialization":"pod","side":"left","deviceId":"dev1"},"currentLevel":0}""";

    @Test
    public void findPillowMatchesSide() {
        var data = ApiTestFixtures.parsePillowData(payload("[" + POD + "," + PILLOW_LEFT + "]"));
        var pillow = data.findPillow(BedSide.LEFT);
        assertTrue(pillow != null && pillow.isOn());
        assertEquals(-10, pillow.level().intValue());
        assertNull(data.findPillow(BedSide.RIGHT));
    }

    /** A payload without {@code currentLevel} must surface absence, not a level of zero. */
    @Test
    public void missingCurrentLevelStaysNull() {
        var noLevel = """
                {"device":{"specialization":"pillow","side":"left","deviceId":"pil_n"},"currentState":{"type":"off"}}""";
        var data = ApiTestFixtures.parsePillowData(payload("[" + noLevel + "]"));
        var pillow = data.findPillow(BedSide.LEFT);
        assertTrue(pillow != null);
        assertFalse(pillow.isOn());
        assertNull(pillow.level());
    }

    @Test
    public void soloFallbackForSingleSidelessPillow() {
        var sideless = """
                {"device":{"specialization":"pillow","side":null,"deviceId":"pil_s"},"currentLevel":3}""";
        var data = ApiTestFixtures.parsePillowData(payload("[" + sideless + "]"));
        var pillow = data.findPillow(BedSide.RIGHT); // any side falls back to the single pillow
        assertTrue(pillow != null && pillow.level() != null && pillow.level() == 3);
    }

    @Test
    public void twoPillowsWithoutMatchYieldNull() {
        var data = ApiTestFixtures.parsePillowData(payload("[" + PILLOW_LEFT + "," + PILLOW_RIGHT + "]"));
        assertNull(data.findPillow(BedSide.SOLO));
    }

    @Test
    public void podOnlyPayloadHasNoPillow() {
        var data = ApiTestFixtures.parsePillowData(payload("[" + POD + "]"));
        assertNull(data.findPillow(BedSide.LEFT));
        assertTrue(data.containsPod("dev1"));
        assertFalse(data.containsPod("dev_other"));
        assertFalse(PillowState.EMPTY.containsPod("dev1"));
    }
}
