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
package org.openhab.binding.eightsleep.internal.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests encapsulated command reconciliation state.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class CommandStateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    public void recordsAndRetiresChannelsAndAlarms() {
        CommandState state = new CommandState(CLOCK);

        state.recordChannel("sidePower", true);
        var channel = state.channel("sidePower");
        assertNotNull(channel);
        assertTrue(channel.on());
        state.retireChannel("sidePower");
        assertNull(state.channel("sidePower"));

        state.recordAlarm("alarm-1", false);
        var alarm = state.alarm("alarm-1");
        assertNotNull(alarm);
        assertFalse(alarm.on());
        state.retireAlarm("alarm-1");
        assertNull(state.alarm("alarm-1"));
    }

    @Test
    public void retainsLastKnownTargetLevel() {
        CommandState state = new CommandState(CLOCK);
        assertNull(state.lastKnownTargetLevel());
        state.setLastKnownTargetLevel(-32);
        assertEquals(Double.valueOf(-32), state.lastKnownTargetLevel());
    }
}
