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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Conversion between the raw -100..100 heating levels used by the Eight Sleep API
 * and real world temperatures.
 * <p>
 * The official Eight Sleep app does not use an algebraic formula for this conversion;
 * like the original client we use lookup tables with linear interpolation.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class HeatingLevelConversion {

    private HeatingLevelConversion() {
        throw new IllegalAccessError("Non-instantiable");
    }

    // raw level -> celsius
    private static final double[] CELSIUS_LEVELS = { -100, -97, -94, -91, -83, -75, -67, -58, -50, -42, -33, -25, -17,
            -8, 0, 6, 11, 17, 22, 28, 33, 39, 44, 50, 56, 61, 67, 72, 78, 83, 89, 100 };
    private static final double[] CELSIUS_VALUES = { 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44 };

    // raw level -> fahrenheit
    private static final double[] FAHRENHEIT_LEVELS = { -100, -99, -97, -95, -94, -92, -90, -86, -81, -77, -72, -68,
            -63, -58, -54, -49, -44, -40, -35, -31, -26, -21, -18, -17, -12, -7, -3, 1, 4, 7, 10, 14, 16, 17, 20, 23,
            26, 29, 32, 35, 38, 41, 44, 48, 51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 80, 81, 85, 88, 92, 100 };
    private static final double[] FAHRENHEIT_VALUES = { 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95,
            96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 107, 108, 109, 110, 111 };

    /**
     * Converts a raw heating level (-100..100) to degrees.
     *
     * @param fahrenheit true to convert to Fahrenheit, false for Celsius
     */
    public static double levelToTemperature(double level, boolean fahrenheit) {
        double[] levels = fahrenheit ? FAHRENHEIT_LEVELS : CELSIUS_LEVELS;
        double[] values = fahrenheit ? FAHRENHEIT_VALUES : CELSIUS_VALUES;

        if (level <= levels[0]) {
            return values[0];
        }
        if (level >= levels[levels.length - 1]) {
            return values[values.length - 1];
        }
        for (int i = 1; i < levels.length; i++) {
            if (level <= levels[i]) {
                double ratio = (level - levels[i - 1]) / (levels[i] - levels[i - 1]);
                return values[i - 1] + ratio * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    /**
     * Converts a temperature to the closest raw heating level (-100..100).
     *
     * @param fahrenheit true if the given temperature is in Fahrenheit, false for Celsius
     */
    public static int temperatureToLevel(double temperature, boolean fahrenheit) {
        double[] levels = fahrenheit ? FAHRENHEIT_LEVELS : CELSIUS_LEVELS;
        double[] values = fahrenheit ? FAHRENHEIT_VALUES : CELSIUS_VALUES;

        int closestIndex = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            double diff = Math.abs(values[i] - temperature);
            if (diff < minDiff) {
                minDiff = diff;
                closestIndex = i;
            }
        }
        return (int) levels[closestIndex];
    }
}
