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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

/**
 * Tests command input conversion and adjustable-base command merging.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideCommandsTest {

    @Test
    public void quantityTypesConvertToCelsius() {
        assertEquals(22.2, BedSideCommands.parseTemperature(new QuantityType<>(72.0, ImperialUnits.FAHRENHEIT)), 0.1);
        assertEquals(20.0, BedSideCommands.parseTemperature(new QuantityType<>(293.15, Units.KELVIN)), 0.01);
        assertEquals(21.5, BedSideCommands.parseTemperature(new QuantityType<>(21.5, SIUnits.CELSIUS)), 1e-9);
    }

    @Test
    public void incompatibleTemperatureUnitIsRejected() {
        assertTrue(Double.isNaN(BedSideCommands.parseTemperature(new QuantityType<>(5, Units.SECOND))));
        assertTrue(Double.isNaN(BedSideCommands.parseTemperature(new QuantityType<>(50, Units.PERCENT))));
    }

    @Test
    public void scalarTemperatureCommandsAreParsed() {
        assertEquals(-16.5, BedSideCommands.parseTemperature(new DecimalType("-16.5")), 1e-9);
        assertEquals(30.25, BedSideCommands.parseTemperature(new StringType("30.25")), 1e-9);
        assertTrue(Double.isNaN(BedSideCommands.parseTemperature(new StringType("warm"))));
    }

    @Test
    public void headCommandKeepsCachedLegAngle() {
        assertArrayEquals(new int[] { 20, 45 }, BedSideCommands.mergeBaseAngles(true, 99, 20, 10));
    }

    @Test
    public void feetCommandKeepsCachedTorsoAngle() {
        assertArrayEquals(new int[] { 15, 40 }, BedSideCommands.mergeBaseAngles(false, 15, 20, 40));
    }

    @Test
    public void baseAnglesAreClampedToSectionRanges() {
        assertArrayEquals(new int[] { 5, 45 }, BedSideCommands.mergeBaseAngles(true, 999, 5, null));
        assertArrayEquals(new int[] { 0, 7 }, BedSideCommands.mergeBaseAngles(false, -5, null, 7));
    }

    @Test
    public void missingCachedBaseAngleDefaultsToFlat() {
        assertArrayEquals(new int[] { 0, 30 }, BedSideCommands.mergeBaseAngles(true, 30, null, 12));
        assertArrayEquals(new int[] { 10, 0 }, BedSideCommands.mergeBaseAngles(false, 10, 8, null));
    }
}
