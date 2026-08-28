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
package org.openhab.binding.eightsleep.internal.polling;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests polling-data freshness thresholds.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class DataFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    public void missingTimestampIsStale() {
        assertTrue(DataFreshness.isStale(null, NOW, 30));
    }

    @Test
    public void thresholdIsFourPollingIntervals() {
        assertFalse(DataFreshness.isStale(NOW.minusSeconds(119), NOW, 30));
        assertFalse(DataFreshness.isStale(NOW.minusSeconds(120), NOW, 30));
        assertTrue(DataFreshness.isStale(NOW.minusSeconds(121), NOW, 30));
    }

    @Test
    public void longIntervalScalesThreshold() {
        Instant lastUpdate = NOW.minusSeconds(2000);
        assertFalse(DataFreshness.isStale(lastUpdate, NOW, 600));
        assertTrue(DataFreshness.isStale(lastUpdate, NOW, 30));
    }

    @Test
    public void smallIntervalUsesSixtySecondFloor() {
        assertTrue(DataFreshness.isStale(NOW.minusSeconds(61), NOW, 15));
        assertFalse(DataFreshness.isStale(NOW.minusSeconds(59), NOW, 15));
    }
}
