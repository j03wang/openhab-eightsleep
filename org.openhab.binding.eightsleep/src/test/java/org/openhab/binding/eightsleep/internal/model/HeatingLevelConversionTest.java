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
import static org.junit.Assert.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests for the lookup-table conversion between raw heating levels (-100..100)
 * and real-world temperatures.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class HeatingLevelConversionTest {

    private static final double EPS = 1e-9;

    @Test
    public void celsiusEndpoints() {
        assertEquals(13.0, HeatingLevelConversion.levelToTemperature(-100, false), EPS);
        assertEquals(44.0, HeatingLevelConversion.levelToTemperature(100, false), EPS);
        assertEquals(27.0, HeatingLevelConversion.levelToTemperature(0, false), EPS);
    }

    @Test
    public void fahrenheitEndpoints() {
        assertEquals(55.0, HeatingLevelConversion.levelToTemperature(-100, true), EPS);
        assertEquals(111.0, HeatingLevelConversion.levelToTemperature(100, true), EPS);
    }

    @Test
    public void outOfRangeLevelsClamp() {
        assertEquals(13.0, HeatingLevelConversion.levelToTemperature(-150, false), EPS);
        assertEquals(44.0, HeatingLevelConversion.levelToTemperature(150, false), EPS);
        assertEquals(55.0, HeatingLevelConversion.levelToTemperature(-999, true), EPS);
        assertEquals(111.0, HeatingLevelConversion.levelToTemperature(999, true), EPS);
    }

    /** Midpoint of the 0..6 level segment interpolates to the midpoint of 27..28 C. */
    @Test
    public void interpolationBetweenTablePoints() {
        assertEquals(27.5, HeatingLevelConversion.levelToTemperature(3, false), EPS);
    }

    /**
     * Non-finite input must not silently pick an extreme: NaN previously fell
     * through the closest-match scan to level -100 (max cooling). Unknown maps
     * to the neutral midpoint instead.
     */
    @Test
    public void nonFiniteTemperatureMapsToNeutralLevel() {
        assertEquals(0, HeatingLevelConversion.temperatureToLevel(Double.NaN, false));
        assertEquals(0, HeatingLevelConversion.temperatureToLevel(Double.NaN, true));
        assertEquals(0, HeatingLevelConversion.temperatureToLevel(Double.POSITIVE_INFINITY, false));
        assertEquals(0, HeatingLevelConversion.temperatureToLevel(Double.NEGATIVE_INFINITY, true));
        // and the neutral level round-trips to a sane temperature
        assertEquals(27.0, HeatingLevelConversion.levelToTemperature(
                HeatingLevelConversion.temperatureToLevel(Double.NaN, false), false), EPS);
    }

    @Test
    public void monotonicInBothUnits() {
        for (int level = -100; level <= 100; level++) {
            double previousC = HeatingLevelConversion.levelToTemperature(level - 1, false);
            double currentC = HeatingLevelConversion.levelToTemperature(level, false);
            assertTrue("celsius must be non-decreasing at " + level, currentC >= previousC);

            double previousF = HeatingLevelConversion.levelToTemperature(level - 1, true);
            double currentF = HeatingLevelConversion.levelToTemperature(level, true);
            assertTrue("fahrenheit must be non-decreasing at " + level, currentF >= previousF);
        }
    }

    /**
     * Round-trip stability: converting a temperature to a level and back stays
     * within half a degree (the coarsest table resolution).
     */
    @Test
    public void roundTripWithinHalfDegree() {
        for (int t = 13; t <= 44; t++) {
            int level = HeatingLevelConversion.temperatureToLevel(t, false);
            assertTrue("level in range for " + t, level >= -100 && level <= 100);
            double back = HeatingLevelConversion.levelToTemperature(level, false);
            assertTrue("round-trip drift too large for " + t + " -> " + back, Math.abs(back - t) <= 0.5);
        }
        for (int t = 55; t <= 111; t++) {
            int level = HeatingLevelConversion.temperatureToLevel(t, true);
            assertTrue("level in range for " + t, level >= -100 && level <= 100);
            double back = HeatingLevelConversion.levelToTemperature(level, true);
            assertTrue("round-trip drift too large for " + t + " -> " + back, Math.abs(back - t) <= 0.5);
        }
    }

    /** Out-of-range temperatures clamp to the extreme table levels. */
    @Test
    public void outOfRangeTemperaturesClamp() {
        assertEquals(-100, HeatingLevelConversion.temperatureToLevel(-50, false));
        assertEquals(100, HeatingLevelConversion.temperatureToLevel(100, false));
        assertEquals(-100, HeatingLevelConversion.temperatureToLevel(0, true)); // 0 F below 55 F min
        assertEquals(100, HeatingLevelConversion.temperatureToLevel(200, false));
    }

    /** The two unit tables must roughly agree: F value near C value * 1.8 + 32. */
    @Test
    public void celsiusAndFahrenheitTablesAgree() {
        for (int level = -100; level <= 100; level += 10) {
            double c = HeatingLevelConversion.levelToTemperature(level, false);
            double f = HeatingLevelConversion.levelToTemperature(level, true);
            assertTrue("unit tables disagree at level " + level,
                    Math.abs(f - (c * 1.8 + 32)) < 2.5);
        }
    }
}
