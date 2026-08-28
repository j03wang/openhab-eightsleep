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
package org.openhab.binding.eightsleep.internal.sleep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.ApiTestFixtures;
import org.openhab.binding.eightsleep.internal.model.TrendData;

/**
 * Tests sleep-stage and presence derivation from trend sessions.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class SleepSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-23T06:00:00Z");
    private static final String STAGE_SESSION = """
            {"sleepStart":"2026-08-23T05:00:00Z","sleepEnd":"2026-08-23T06:15:00Z",
             "stages":[
               {"stage":"awake","duration":600},
               {"stage":"light","duration":1800},
               {"stage":"deep","duration":1200},
               {"stage":"rem","duration":900}
             ]}""";

    private static TrendData.Session session(String json) {
        return ApiTestFixtures.parseTrendDays("{\"days\":[{\"sessions\":[" + json + "]}]}").getCurrentSession();
    }

    @Test
    public void stageMatchesSegmentCoveringNow() {
        TrendData.Session session = session(STAGE_SESSION);
        assertEquals("awake", SleepSession.currentStage(session, Instant.parse("2026-08-23T05:05:00Z")));
        assertEquals("deep", SleepSession.currentStage(session, Instant.parse("2026-08-23T05:40:00Z")));
        assertEquals("deep", SleepSession.currentStage(session, Instant.parse("2026-08-23T05:55:00Z")));
        assertEquals("rem", SleepSession.currentStage(session, Instant.parse("2026-08-23T06:05:00Z")));
    }

    @Test
    public void stageRequiresCurrentCompleteSession() {
        assertNull(SleepSession.currentStage(null, NOW));
        assertNull(SleepSession.currentStage(session("{}"), NOW));
        assertNull(SleepSession.currentStage(session("{\"sleepStart\":\"2026-08-23T05:00:00Z\"}"), NOW));
        assertNull(SleepSession.currentStage(session(
                "{\"sleepStart\":\"2026-08-23T05:00:00Z\",\"sleepEnd\":\"2026-08-23T06:15:00Z\",\"stages\":[]}"), NOW));
    }

    @Test
    public void stageIsLimitedToSessionWindow() {
        TrendData.Session session = session(STAGE_SESSION);
        assertNull(SleepSession.currentStage(session, Instant.parse("2026-08-23T04:30:00Z")));
        assertEquals("rem", SleepSession.currentStage(session, Instant.parse("2026-08-23T06:15:00Z")));
        assertNull(SleepSession.currentStage(session, Instant.parse("2026-08-23T06:30:00Z")));
    }

    @Test
    public void freshHeartbeatMeansPresent() {
        TrendData.Session session = heartbeatSession("2026-08-23T05:59:00Z");
        assertTrue(SleepSession.isPresent(session, NOW));
    }

    @Test
    public void staleOrMissingHeartbeatMeansAbsent() {
        assertFalse(SleepSession.isPresent(heartbeatSession("2026-08-23T04:00:00Z"), NOW));
        assertFalse(SleepSession.isPresent(null, NOW));
        assertFalse(SleepSession.isPresent(session("{}"), NOW));
        assertFalse(SleepSession.isPresent(session("{\"timeseries\":{\"heartRate\":[]}}"), NOW));
    }

    @Test
    public void smallFutureClockSkewIsTolerated() {
        assertTrue(SleepSession.isPresent(heartbeatSession("2026-08-23T06:02:00Z"), NOW));
    }

    @Test
    public void freshnessBoundaryIsExclusive() {
        assertFalse(SleepSession.isPresent(heartbeatSession("2026-08-23T05:50:00Z"), NOW));
        assertTrue(SleepSession.isPresent(heartbeatSession("2026-08-23T05:50:01Z"), NOW));
    }

    @Test
    public void malformedHeartbeatTimestampIsAbsent() {
        assertFalse(SleepSession
                .isPresent(session("{\"timeseries\":{\"heartRate\":[[\"garbage\",5],[{\"bad\":1},62]]}}"), NOW));
    }

    private static TrendData.Session heartbeatSession(String timestamp) {
        return session("{\"timeseries\":{\"heartRate\":[[\"" + timestamp + "\",62]]}}");
    }
}
