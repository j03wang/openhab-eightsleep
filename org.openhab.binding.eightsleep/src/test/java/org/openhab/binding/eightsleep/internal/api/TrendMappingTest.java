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
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.TrendData;

/**
 * Tests typed trend-contract to domain mapping.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TrendMappingTest {

    @Test
    public void trendContractMapsToDomain() {
        TrendData trends = ApiTestFixtures.parseTrendDays("""
                {"days":[{"score":50,"sessions":[{"timeseries":{
                  "heartRate":[["2026-08-22T04:31:00Z","None"],["2026-08-22T05:00:00Z",58]],
                  "tempBedC":[["2026-08-22T05:00:00Z",29.5]]}}]}]}""");

        assertTrue(!trends.isEmpty());
        assertEquals(Double.valueOf(50), trends.getDay(0).score());
        assertEquals(Double.valueOf(58), trends.getCurrentSession().latestValue("heartRate"));
        assertEquals(Double.valueOf(29.5), trends.getCurrentSession().latestValue("tempBedC"));
        assertEquals(Instant.parse("2026-08-22T04:31:00Z"), trends.getCurrentSession().firstTime("heartRate"));
        assertNull(trends.getCurrentSession().latestValue("missing"));
    }

    @Test
    public void previousSessionFallsBackToPreviousDay() {
        TrendData trends = ApiTestFixtures.parseTrendDays("""
                {"days":[
                  {"sessions":[{"timeseries":{"heartRate":[["2026-08-21T05:00:00Z",50]]}}]},
                  {"sessions":[{"timeseries":{"heartRate":[["2026-08-22T05:00:00Z",60]]}}]}
                ]}""");

        assertEquals(Double.valueOf(50), trends.getPreviousSession().latestValue("heartRate"));
    }

    @Test
    public void malformedTimeseriesEntriesAreSkipped() {
        TrendData trends = ApiTestFixtures.parseTrendDays("""
                {"days":[{"sessions":[{"timeseries":{
                  "heartRate":[["missing-value"],["not-a-time",62],[{"bad":1},63]]
                }}]}]}""");

        assertEquals(Double.valueOf(63), trends.getCurrentSession().latestValue("heartRate"));
        assertNull(trends.getCurrentSession().lastTime("heartRate"));
    }
}
