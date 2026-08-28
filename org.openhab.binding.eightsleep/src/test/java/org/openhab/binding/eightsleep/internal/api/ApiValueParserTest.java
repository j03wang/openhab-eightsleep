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
package org.openhab.binding.eightsleep.internal.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.time.LocalTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Tests conversion of scalar API values into typed temporal values.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ApiValueParserTest {

    @Test
    public void timestampOffsetsAreNormalized() {
        assertEquals(Instant.parse("2026-08-22T04:31:00Z"), ApiValueParser.parseTimestamp("2026-08-22T04:31:00Z"));
        assertEquals(Instant.parse("2026-08-22T04:31:00Z"), ApiValueParser.parseTimestamp("2026-08-22T06:31:00+02:00"));
        assertNull(ApiValueParser.parseTimestamp(null));
        assertNull(ApiValueParser.parseTimestamp("not-a-time"));
    }

    @Test
    public void timeOfDayAcceptsWholeAndFractionalSeconds() {
        assertEquals(LocalTime.of(7, 30), ApiValueParser.parseTimeOfDay("07:30:00"));
        assertEquals(LocalTime.of(6, 45), ApiValueParser.parseTimeOfDay("06:45:00.000"));
        assertNull(ApiValueParser.parseTimeOfDay("25:99:99"));
    }
}
