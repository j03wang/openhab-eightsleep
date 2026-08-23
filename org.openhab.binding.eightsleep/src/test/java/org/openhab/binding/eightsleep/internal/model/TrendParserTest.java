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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

import com.google.gson.JsonParser;

/**
 * Direct tests for the defensive trends-payload accessors.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TrendParserTest {

    private static TrendParser parserOf(String json) {
        return new TrendParser(JsonParser.parseString(json).getAsJsonArray());
    }

    private static final String SESSION = """
            {"timeseries":{
              "heartRate":[["2026-08-22T04:31:00Z","None"],["2026-08-22T05:00:00Z",58]],
              "tempBedC":[["2026-08-22T04:31:00Z","None"],["2026-08-22T05:00:00Z",29.5]]}}""";

    @Test
    public void emptyArrayIsEmpty() {
        assertTrue(parserOf("[]").isEmpty());
        assertNull(parserOf("[]").getDay(0));
        assertNull(parserOf("[]").getCurrentSession());
    }

    /** Non-object day entries are skipped instead of throwing. */
    @Test
    public void nonObjectDaysSkipped() {
        TrendParser parser = parserOf("[\"junk\",42,null,{\"score\":50}]");
        assertEquals(1, parser.getDay(0) != null ? 1 : 0);
        assertEquals(Double.valueOf(50), TrendParser.getDouble(parser.getDay(0), "score"));
        assertNull(parser.getDay(1));
    }

    @Test
    public void getDayOutOfRangeIsNull() {
        TrendParser parser = parserOf("[{\"score\":1},{\"score\":2}]");
        assertNotNull(parser.getDay(0));
        assertNotNull(parser.getDay(1));
        assertNull(parser.getDay(2));
        assertNull(parser.getDay(-1));
    }

    @Test
    public void sessionsMissingOrMalformed() {
        assertNull(parserOf("[{}]").getCurrentSession());
        assertNull(parserOf("[{\"sessions\":\"nope\"}]").getCurrentSession());
        // session entries that are not objects are skipped, object ones survive
        var sessions = parserOf("[{\"sessions\":[1,\"x\",{\"a\":1}]}]").getSessions(0);
        assertEquals(1, sessions.size());
    }

    @Test
    public void previousSessionFallsBackToPreviousDay() {
        // current day has a single session; the "previous" must come from day -1
        TrendParser parser = parserOf(
                "[{\"sessions\":[{\"timeseries\":{\"heartRate\":[[\"2026-08-21T05:00:00Z\",50]]}}]},"
                        + "{\"sessions\":[{\"timeseries\":{\"heartRate\":[[\"2026-08-22T05:00:00Z\",60]]}}]}]");
        var previous = parser.getPreviousSession();
        assertNotNull(previous);
        assertEquals(Double.valueOf(50), TrendParser.latestSeriesValue(previous, "heartRate"));
    }

    @Test
    public void latestSeriesValueSkipsNoneAndStopsAtFirstNumber() {
        var session = JsonParser.parseString(SESSION).getAsJsonObject();
        assertEquals(Double.valueOf(58), TrendParser.latestSeriesValue(session, "heartRate"));
        assertEquals(Double.valueOf(29.5), TrendParser.latestSeriesValue(session, "tempBedC"));
        // every entry junk -> null; series missing -> null
        var allNone = JsonParser.parseString(
                "{\"timeseries\":{\"x\":[[\"t\",\"None\"],[\"t2\",\"None\"]]}}").getAsJsonObject();
        assertNull(TrendParser.latestSeriesValue(allNone, "x"));
        assertNull(TrendParser.latestSeriesValue(session, "missing"));
    }

    @Test
    public void malformedEntriesTolerated() {
        var session = JsonParser.parseString("""
                {"timeseries":{"hr":["bad",[1],["t"], ["t",3]]}}""").getAsJsonObject();
        assertEquals(Double.valueOf(3), TrendParser.latestSeriesValue(session, "hr"));
    }

    @Test
    public void firstAndLastEntryTime() {
        var session = JsonParser.parseString(SESSION).getAsJsonObject();
        assertEquals(Instant.parse("2026-08-22T04:31:00Z"),
                TrendParser.firstEntryTime(session, "heartRate"));
        assertEquals(Instant.parse("2026-08-22T05:00:00Z"),
                TrendParser.lastEntryTime(session, "heartRate"));
        assertNull(TrendParser.firstEntryTime(session, "missing"));
    }

    @Test
    public void parseTimestampVariants() {
        assertEquals(Instant.parse("2026-08-22T04:31:00Z"), TrendParser.parseTimestamp("2026-08-22T04:31:00Z"));
        assertEquals(Instant.parse("2026-08-22T04:31:00Z"),
                TrendParser.parseTimestamp("2026-08-22T06:31:00+02:00"));
        assertEquals(Instant.parse("2026-08-22T04:31:00.500Z"),
                TrendParser.parseTimestamp(" 2026-08-22T04:31:00.500Z "));
        assertNull(TrendParser.parseTimestamp(null));
        assertNull(TrendParser.parseTimestamp(""));
        assertNull(TrendParser.parseTimestamp("null"));
        assertNull(TrendParser.parseTimestamp("not-a-time"));
    }

    @Test
    public void scalarAccessorsDegradeToNull() {
        var obj = JsonParser.parseString("{\"s\":\"x\",\"n\":5,\"b\":true,\"o\":{\"k\":1},\"none\":null}")
                .getAsJsonObject();
        assertEquals("x", TrendParser.getString(obj, "s"));
        assertEquals(Boolean.TRUE, TrendParser.getBoolean(obj, "b"));
        assertEquals(Double.valueOf(5), TrendParser.getDouble(obj, "n"));
        assertNotNull(TrendParser.getObject(obj, "o"));
        assertNull(TrendParser.getString(obj, "o")); // wrong type
        assertNull(TrendParser.getDouble(obj, "none"));
        assertNull(TrendParser.getString(obj, "missing"));
        assertNull(TrendParser.getString(null, "s"));
        assertNull(TrendParser.getDouble(null, "n"));
        assertNull(TrendParser.getObject(obj, "b"));
    }

    // ==================== parseTimeOfDay ====================

    @Test
    public void timeOfDayParsesWholeAndFractionalSeconds() {
        assertEquals(java.time.LocalTime.of(7, 30, 0), TrendParser.parseTimeOfDay("07:30:00"));
        assertEquals(java.time.LocalTime.of(6, 45, 0), TrendParser.parseTimeOfDay("06:45:00.000"));
        assertEquals(java.time.LocalTime.of(23, 59, 59), TrendParser.parseTimeOfDay(" 23:59:59 "));
    }

    @Test
    public void timeOfDayMalformedYieldsNull() {
        assertNull(TrendParser.parseTimeOfDay(null));
        assertNull(TrendParser.parseTimeOfDay(""));
        assertNull(TrendParser.parseTimeOfDay("   "));
        assertNull(TrendParser.parseTimeOfDay("not-a-time"));
        assertNull(TrendParser.parseTimeOfDay("25:99:99"));
    }
}
