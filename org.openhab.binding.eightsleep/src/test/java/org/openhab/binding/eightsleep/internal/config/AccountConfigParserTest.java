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
package org.openhab.binding.eightsleep.internal.config;

import static org.junit.Assert.assertEquals;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests normalization and bounds applied to account configuration.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountConfigParserTest {

    @Test
    public void intervalIsClampedToConfiguredBounds() {
        assertEquals(15, AccountConfigParser.clampInterval(1, 15, 600));
        assertEquals(600, AccountConfigParser.clampInterval(99999, 15, 600));
        assertEquals(60, AccountConfigParser.clampInterval(60, 15, 600));
    }

    @Test
    public void temperatureUnitIsNormalizedOrFallsBack() {
        assertEquals('f', AccountConfigParser.parseTemperatureUnit("F", 'c'));
        assertEquals('f', AccountConfigParser.parseTemperatureUnit(" fahrenheit ", 'c'));
        assertEquals('c', AccountConfigParser.parseTemperatureUnit("celsius", 'c'));
        assertEquals('k', AccountConfigParser.parseTemperatureUnit("", 'k'));
        assertEquals('k', AccountConfigParser.parseTemperatureUnit("kelvin", 'k'));
        assertEquals('c', AccountConfigParser.parseTemperatureUnit("42", 'c'));
    }
}
