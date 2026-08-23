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
package org.openhab.binding.eightsleep.internal.handler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.Type;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonParser;

/**
 * Tests for the pure decision helpers extracted from BedSideHandler:
 * temperature command parsing, base-angle merging, bed presence and the LWW
 * away-poll acceptance window.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideLogicTest {

    private static final Instant NOW = Instant.parse("2026-08-23T06:00:00Z");

    // ==================== parseTemperature ====================

    @Test
    public void quantityTypesConvertToCelsius() {
        // Fahrenheit IS Celsius-compatible: parseTemperature returns the Celsius value
        assertEquals(22.2, BedSideCommands.parseTemperature(new QuantityType<>(72.0, ImperialUnits.FAHRENHEIT)), 0.1);
        // Kelvin likewise
        assertEquals(20.0, BedSideCommands.parseTemperature(new QuantityType<>(293.15, Units.KELVIN)), 0.01);
        assertEquals(21.5, BedSideCommands.parseTemperature(new QuantityType<>(21.5, SIUnits.CELSIUS)), 1e-9);
        // handleTargetTemperature re-derives the unit from the command for the conversion table
        var fahrenheitCommand = new QuantityType<>(72.0, ImperialUnits.FAHRENHEIT);
        assertTrue(fahrenheitCommand.getUnit().isCompatible(ImperialUnits.FAHRENHEIT));
    }

    @Test
    public void incompatibleUnitIsNaN() {
        assertTrue(Double.isNaN(BedSideCommands.parseTemperature(new QuantityType<>(5, Units.SECOND))));
        assertTrue(Double.isNaN(BedSideCommands.parseTemperature(new QuantityType<>(50, Units.PERCENT))));
    }

    @Test
    public void plainNumbersPassThrough() {
        assertEquals(-16.5, BedSideCommands.parseTemperature(new DecimalType("-16.5")), 1e-9);
        assertEquals(30.25, BedSideCommands.parseTemperature(new StringType("30.25")), 1e-9);
        assertTrue("unparsable string", Double.isNaN(BedSideCommands.parseTemperature(new StringType("warm"))));
    }

    // ==================== mergeBaseAngles ====================

    @Test
    public void headCommandKeepsCachedLegAngle() {
        assertArrayEquals(new int[] { 20, 45 }, BedSideCommands.mergeBaseAngles(true, 99, 20, 10));
    }

    @Test
    public void feetCommandKeepsCachedTorsoAngle() {
        assertArrayEquals(new int[] { 15, 40 }, BedSideCommands.mergeBaseAngles(false, 15, 20, 40));
    }

    @Test
    public void anglesClampToSectionRanges() {
        // head max 45 even when commanding higher; feet max 20
        assertArrayEquals(new int[] { 5, 45 }, BedSideCommands.mergeBaseAngles(true, 999, 5, null));
        assertArrayEquals(new int[] { 0, 7 }, BedSideCommands.mergeBaseAngles(false, -5, null, 7));
    }

    @Test
    public void missingCacheDefaultsOtherAxisToZero() {
        // documented risk: without polled base data the other axis snaps to flat
        assertArrayEquals(new int[] { 0, 30 }, BedSideCommands.mergeBaseAngles(true, 30, null, 12));
        assertArrayEquals(new int[] { 10, 0 }, BedSideCommands.mergeBaseAngles(false, 10, 8, null));
    }

    // ==================== currentSleepStage ====================

    /** Verified live shape: stages are oldest-first {stage, duration} segments in seconds. */
    private static final String STAGE_SESSION = """
            {"sleepStart":"2026-08-23T05:00:00Z","sleepEnd":"2026-08-23T06:15:00Z",
             "stages":[
               {"stage":"awake","duration":600},
               {"stage":"light","duration":1800},
               {"stage":"deep","duration":1200},
               {"stage":"rem","duration":900}
             ]}""";

    @Test
    public void stageMatchesSegmentCoveringNow() {
        var session = JsonParser.parseString(STAGE_SESSION).getAsJsonObject();
        // 5 min in -> inside the awake segment (0..600s)
        assertEquals("awake", org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T05:05:00Z")));
        // 40 min (2400s) -> exactly the light/deep boundary; deep covers it
        assertEquals("deep", org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T05:40:00Z")));
        // 55 min (3300s) -> still deep; rem starts at 60 min
        assertEquals("deep", org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T05:55:00Z")));
        // 65 min (3900s) -> inside rem
        assertEquals("rem", org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T06:05:00Z")));
    }

    /** After the session's sleepEnd there is no current stage (night is over). */
    @Test
    public void noStageAfterSleepEnd() {
        var session = JsonParser.parseString(STAGE_SESSION).getAsJsonObject();
        assertNull(org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T06:30:00Z")));
    }

    /** Exactly at sleepEnd the session window still includes "now" (inclusive end). */
    @Test
    public void stageAtExactSleepEndIsLastSegment() {
        var session = JsonParser.parseString(STAGE_SESSION).getAsJsonObject();
        // total stages = 600+1800+1200+900 = 4500s = exactly sleepEnd - sleepStart
        assertEquals("rem", org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T06:15:00Z")));
    }

    @Test
    public void noStageBeforeSleepStart() {
        var session = JsonParser.parseString(STAGE_SESSION).getAsJsonObject();
        assertNull(org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T04:30:00Z")));
    }

    // ==================== isUserDataStale ====================

    private static final Instant STALE_NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    public void nullLastUpdatedMeansStale() {
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(null, STALE_NOW, 30));
    }

    @Test
    public void freshDataInsideThreshold() {
        // default interval 30s -> threshold 120s; strictly inside is fresh
        Instant lastUpdate = STALE_NOW.minusSeconds(119);
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(lastUpdate, STALE_NOW, 30));
        // exactly at the threshold (age == 120s) data is still fresh - only a value
        // OLDER than lastUpdated + threshold counts as stale (strict isBefore)
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(STALE_NOW.minusSeconds(120), STALE_NOW, 30));
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(STALE_NOW.minusSeconds(121), STALE_NOW, 30));
    }

    @Test
    public void longIntervalScalesThreshold() {
        // interval 600s -> threshold 2400s; 2000s old is still fresh there,
        // while the same age would be stale for the default interval
        Instant lastUpdate = STALE_NOW.minusSeconds(2000);
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(lastUpdate, STALE_NOW, 600));
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(lastUpdate, STALE_NOW, 30));
    }

    @Test
    public void smallIntervalUsesSixtySecondFloor() {
        // clamped minimum interval 15s -> 4x = 60s; a value older than that is stale
        Instant lastUpdate = STALE_NOW.minusSeconds(61);
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(lastUpdate, STALE_NOW, 15));
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.DataFreshness.isStale(STALE_NOW.minusSeconds(59), STALE_NOW, 15));
    }

    // ==================== resolveShownTargetLevel ====================

    @Test
    public void meaningfulRawLevelsAlwaysShown() {
        assertEquals(-41.0, BedSideChannelSync.resolveShownTargetLevel(-41, false, -20.0), 1e-9);
        assertEquals(35.0, BedSideChannelSync.resolveShownTargetLevel(35, true, null), 1e-9);
    }

    /** The meaningless off-state zero holds the last meaningful level instead. */
    @Test
    public void offStateZeroHoldsPreviousTarget() {
        assertEquals(-41.0, BedSideChannelSync.resolveShownTargetLevel(0, false, -41.0), 1e-9);
        // and it keeps holding across repeated off polls
        assertEquals(-41.0, BedSideChannelSync.resolveShownTargetLevel(0, false, -41.0), 1e-9);
    }

    /** A commanded neutral 0 with the heating flag set is genuine and wins. */
    @Test
    public void commandedNeutralZeroWithHeatingWins() {
        assertEquals(0.0, BedSideChannelSync.resolveShownTargetLevel(0, true, -41.0), 1e-9);
    }

    /** First poll ever reporting an off-state 0 has nothing to hold - shows 0. */
    @Test
    public void firstPollOffStateZeroShowsZero() {
        assertEquals(0.0, BedSideChannelSync.resolveShownTargetLevel(0, false, null), 1e-9);
    }

    @Test
    public void noStageWithoutLiveSessionOrData() {
        Instant now = Instant.parse("2026-08-23T05:10:00Z");
        assertNull(org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(null, now));
        // session without stages array or sleep boundaries
        assertNull(org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(
                JsonParser.parseString("{}").getAsJsonObject(), now));
        assertNull(org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(
                JsonParser.parseString("{\"sleepStart\":\"2026-08-23T05:00:00Z\"}").getAsJsonObject(), now));
        assertNull(org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(
                JsonParser.parseString(
                        "{\"sleepStart\":\"2026-08-23T05:00:00Z\",\"sleepEnd\":\"2026-08-23T06:15:00Z\",\"stages\":[]}")
                        .getAsJsonObject(), now));
    }

    /** Regression: the live fixture has no "processing" flag - stage currency must
     * come from the session window, not a day-level flag. */
    @Test
    public void stageWorksWithoutProcessingFlag() {
        var session = JsonParser.parseString(STAGE_SESSION).getAsJsonObject();
        assertFalse("fixture shape must not rely on a processing field",
                session.has("processing"));
        assertEquals("awake", org.openhab.binding.eightsleep.internal.sleep.SleepSession.currentStage(session,
                Instant.parse("2026-08-23T05:05:00Z")));
    }

    // ==================== isPresent ====================

    private static final String HR_SESSION = """
            {"timeseries":{"heartRate":[["2026-08-23T05:55:00Z","None"],["%s",62]]}}""";

    @Test
    public void freshHeartbeatMeansPresent() {
        var session = JsonParser.parseString(String.format(HR_SESSION, "2026-08-23T05:59:00Z")).getAsJsonObject();
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(session, NOW));
    }

    @Test
    public void staleHeartbeatMeansAbsent() {
        var session = JsonParser.parseString(String.format(HR_SESSION, "2026-08-23T04:00:00Z")).getAsJsonObject();
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(session, NOW));
    }

    /** Future timestamps are tolerated (device clock skew) thanks to abs(). */
    @Test
    public void slightlyFutureTimestampStillPresent() {
        var session = JsonParser.parseString(String.format(HR_SESSION, "2026-08-23T06:02:00Z")).getAsJsonObject();
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(session, NOW));
    }

    @Test
    public void missingSeriesOrGarbageIsAbsent() {
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(null, NOW));
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(JsonParser.parseString("{}").getAsJsonObject(), NOW));
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(
                JsonParser.parseString("{\"timeseries\":{\"heartRate\":[]}}").getAsJsonObject(), NOW));
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(
                JsonParser.parseString("""
                        {"timeseries":{"heartRate":[["garbage",5]]}}""").getAsJsonObject(), NOW));
    }

    /** Exactly SleepSession.PRESENCE_FRESH_SECONDS old is already stale (strict <). */
    @Test
    public void heartbeatAtExactFreshnessBoundaryIsAbsent() {
        // NOW = 2026-08-23T06:00:00Z, so exactly 600 s old is 05:50:00Z
        var boundary = JsonParser.parseString(String.format(HR_SESSION, "2026-08-23T05:50:00Z")).getAsJsonObject();
        assertFalse("600s is not < 600s", org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(boundary, NOW));
        // one second inside the window is present
        var fresh = JsonParser.parseString(String.format(HR_SESSION, "2026-08-23T05:59:00Z")).getAsJsonObject();
        assertTrue(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(fresh, NOW));
    }

    /**
     * Regression: a malformed entry (object instead of a [timestamp, value] array)
     * previously threw out of getAsString and killed the periodic sync job.
     */
    @Test
    public void malformedEntryTypesDoNotThrow() {
        // object where the timestamp element should be
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(JsonParser.parseString(
                "{\"timeseries\":{\"heartRate\":[[{\"bad\":1},62]]}}").getAsJsonObject(), NOW));
        // non-array entry entirely
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(JsonParser.parseString(
                "{\"timeseries\":{\"heartRate\":[{\"weird\":true}]}}").getAsJsonObject(), NOW));
        // numeric "timestamp"
        assertFalse(org.openhab.binding.eightsleep.internal.sleep.SleepSession.isPresent(JsonParser.parseString(
                "{\"timeseries\":{\"heartRate\":[[123456,62]]}}").getAsJsonObject(), NOW));
    }

    // ==================== deriveHeatingState ====================

    @Test
    public void heatingStateFollowsTargetLevelSign() {
        assertEquals("heating", BedSideChannelSync.deriveHeatingState(true, 50));
        assertEquals("cooling", BedSideChannelSync.deriveHeatingState(true, -50));
        // level 0 is neutral (27 C): actively tracking it is neither direction
        assertEquals("idle", BedSideChannelSync.deriveHeatingState(true, 0));
        assertEquals("idle", BedSideChannelSync.deriveHeatingState(false, 80));
        assertEquals("idle", BedSideChannelSync.deriveHeatingState(false, -80));
    }

    // ==================== acceptsPolledAway ====================

    @Test
    public void noPriorCommandAcceptsAnyPoll() {
        assertTrue(AwayModeTracker.acceptsPolledAway(null, NOW));
    }

    /** A poll started after the command is newer information, even 1 s later. */
    @Test
    public void pollStartedAfterCommandAccepted() {
        Instant commandAt = NOW;
        assertTrue("post-command polls are server truth",
                AwayModeTracker.acceptsPolledAway(commandAt, commandAt.plusSeconds(1)));
        assertTrue(AwayModeTracker.acceptsPolledAway(commandAt, commandAt.plusNanos(1)));
        assertTrue(AwayModeTracker.acceptsPolledAway(commandAt, commandAt.plusSeconds(60)));
        assertFalse("a poll started before the command is pre-command data",
                AwayModeTracker.acceptsPolledAway(commandAt, commandAt.minusSeconds(60)));
    }

    // ==================== autopilotTargetLevel / shouldClearAlarmChannels ====================

    @Test
    public void autopilotTargetReadFromSmartSchedule() {
        var temp = JsonParser.parseString(
                "{\"smart\":{\"bedTimeLevel\":-32,\"finalSleepLevel\":-12}}").getAsJsonObject();
        assertEquals(Double.valueOf(-32), BedSideChannelSync.autopilotTargetLevel(temp));

        // missing/blank payloads degrade to null instead of throwing
        assertNull(BedSideChannelSync.autopilotTargetLevel(null));
        assertNull(BedSideChannelSync.autopilotTargetLevel(JsonParser.parseString("{}").getAsJsonObject()));
        assertNull(BedSideChannelSync.autopilotTargetLevel(
                JsonParser.parseString("{\"smart\":\"junk\"}").getAsJsonObject()));
    }

    @Test
    public void alarmChannelsNeverClearedWhileAlarmSelected() {
        // a selected alarm always wins - nothing is cleared regardless of freshness
        assertFalse(org.openhab.binding.eightsleep.internal.alarm.AlarmSelector.shouldClearAlarmChannels(true, 0, null, STALE_NOW, 30));
        assertFalse(org.openhab.binding.eightsleep.internal.alarm.AlarmSelector.shouldClearAlarmChannels(true, 5, STALE_NOW, STALE_NOW, 30));
    }

    @Test
    public void freshEmptyPollClearsStaleAlarmEntries() {
        // no selection, no entries, but a recent successful (empty) poll -> clear
        assertTrue(org.openhab.binding.eightsleep.internal.alarm.AlarmSelector.shouldClearAlarmChannels(false, 0, STALE_NOW, STALE_NOW, 30));
    }

    @Test
    public void staleLeftoverEntriesAreClearedEvenWithoutFreshPoll() {
        // leftover published values from a since-removed alarm must not linger forever
        Instant old = STALE_NOW.minusSeconds(3600);
        assertTrue(org.openhab.binding.eightsleep.internal.alarm.AlarmSelector.shouldClearAlarmChannels(false, 3, old, STALE_NOW, 30));
        // and with neither entries nor a fresh poll there is nothing to act on
        assertFalse(org.openhab.binding.eightsleep.internal.alarm.AlarmSelector.shouldClearAlarmChannels(false, 0, old, STALE_NOW, 30));
        assertFalse(org.openhab.binding.eightsleep.internal.alarm.AlarmSelector.shouldClearAlarmChannels(false, 0, null, STALE_NOW, 30));
    }
}
