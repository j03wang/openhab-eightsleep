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
import static org.junit.Assert.assertNull;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests normalization and bounds applied to account configuration.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountConfigurationTest {

    @Test
    public void intervalIsClampedToConfiguredBounds() {
        AccountConfiguration config = new AccountConfiguration();
        config.deviceRefreshInterval = 1;
        config.userRefreshInterval = 99999;
        config.baseRefreshInterval = 60;
        assertEquals(15, config.deviceRefreshIntervalSeconds());
        assertEquals(600, config.userRefreshIntervalSeconds());
        assertEquals(60, config.baseRefreshIntervalSeconds());
    }

    @Test
    public void temperatureUnitIsNormalizedOrFallsBack() {
        AccountConfiguration config = new AccountConfiguration();
        config.temperatureUnit = " fahrenheit ";
        assertEquals('f', config.temperatureUnit('c'));
        config.temperatureUnit = "celsius";
        assertEquals('c', config.temperatureUnit('f'));
        config.temperatureUnit = "";
        assertEquals('k', config.temperatureUnit('k'));
        config.temperatureUnit = "kelvin";
        assertEquals('k', config.temperatureUnit('k'));
    }

    @Test
    public void blankClientOverridesNormalizeToNull() {
        AccountConfiguration config = new AccountConfiguration();
        config.clientId = " ";
        config.clientSecret = "";
        assertNull(config.clientIdOrNull());
        assertNull(config.clientSecretOrNull());
    }
}
