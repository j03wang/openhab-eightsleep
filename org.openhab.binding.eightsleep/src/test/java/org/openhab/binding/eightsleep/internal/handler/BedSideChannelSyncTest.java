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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;
import org.openhab.binding.eightsleep.internal.handler.BedSideChannelSync.ChannelUpdate;
import org.openhab.binding.eightsleep.internal.handler.BedSideChannelSync.StatusAction;
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * End-to-end tests of the pure channel-sync resolver against realistic payload
 * shapes (mirroring the live captures): full data, off-side fallbacks, missing
 * user and staleness. This is the mapping that was previously untestable.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideChannelSyncTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    /** Fresh UserData whose trends/temperature payloads come from the given JSON. */
    private static UserDataCache userData(@Nullable String trendsJson, @Nullable String temperatureJson,
            @Nullable String alarmsJson, @Nullable String baseJson) {
        UserDataCache ud = new UserDataCache();
        if (trendsJson != null) {
            ud.trendDays = EightSleepApiClient.parseTrendDays(trendsJson);
        }
        if (temperatureJson != null) {
            ud.temperature = com.google.gson.JsonParser.parseString(temperatureJson).getAsJsonObject();
            ud.temperatureAt = NOW;
        }
        if (alarmsJson != null) {
            ud.alarms.addAll(EightSleepApiClient.parseAlarms(alarmsJson));
            ud.alarmsPolledAt = NOW;
        }
        if (baseJson != null) {
            ud.baseData = EightSleepApiClient.parseBaseData(baseJson);
        }
        return ud;
    }

    private static DeviceData deviceData(String json) {
        return EightSleepApiClient.parseDeviceData(json);
    }

    /** Convenience: find the update for a group#channel id. */
    private static @Nullable ChannelUpdate update(BedSideChannelSync.Result r, String uid) {
        for (ChannelUpdate u : r.updates) {
            if (u.channelUid().equals(uid)) {
                return u;
            }
        }
        return null;
    }

    // ==================== status decisions ====================

    @Test
    public void missingDeviceDataMeansBridgeOffline() {
        var r = BedSideChannelSync.compute(null, new UserDataCache(), "left", false,
                30, NOW, ZONE, null, null, null, null);
        assertEquals(StatusAction.BRIDGE_OFFLINE, r.statusAction);
        assertTrue(r.updates.isEmpty());
    }

    @Test
    public void missingUserDataMeansConfigurationError() {
        var r = BedSideChannelSync.compute(new DeviceData(), null, "left", false,
                30, NOW, ZONE, null, null, null, null);
        assertEquals(StatusAction.USER_NOT_FOUND, r.statusAction);
    }

    @Test
    public void staleUserDataMeansOfflineButStillPublishes() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"off\"}}", null, null);
        ud.lastUpdated = NOW.minusSeconds(3600); // way past the 120 s threshold
        var dd = deviceData("{\"result\":{\"leftHeatingLevel\":-10}}");

        var r = BedSideChannelSync.compute(dd, ud, "left", false, 30, NOW, ZONE, null, null, null, null);
        assertEquals(StatusAction.STALE_DATA, r.statusAction);
        assertNotNull("cached values still publish while stale", update(r, "device#heatingLevel"));
    }

    @Test
    public void freshDataMeansOnline() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"smart\"}}", null, null);
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false,
                30, NOW, ZONE, null, null, null, null);
        assertEquals(StatusAction.ONLINE, r.statusAction);
    }

    // ==================== heating / target ====================

    private static final String FULL_DEVICE = """
            {"result":{"leftHeatingLevel":-16,"leftTargetHeatingLevel":-41,"leftNowHeating":true,
             "leftHeatingDuration":3600,"hasWater":true,"needsPriming":false,"priming":false,
             "lastPrime":"2026-08-01T06:00:00.000Z","ledBrightnessLevel":50,
             "features":["cooling"]}}""";

    @Test
    public void heatingChannelsFromDevicePayload() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"smart\"}}", null, null);
        var r = BedSideChannelSync.compute(deviceData(FULL_DEVICE), ud, "left", false,
                30, NOW, ZONE, null, null, null, null);

        assertEquals(-16.0, ((DecimalType) update(r, "device#heatingLevel").state())
                .doubleValue(), 1e-9);
        // level -41 converts to ~24 C via the app table
        var target = (QuantityType<?>) update(r, "current#targetTemperature").state();
        assertEquals(org.openhab.core.library.unit.SIUnits.CELSIUS, target.getUnit());
        assertEquals(22.11, target.doubleValue(), 0.01);
        // nowHeating=true + negative target => cooling
        assertEquals("cooling", ((StringType) update(r, "device#heatingState").state())
                .toString());
        assertNotNull(update(r, "device#hasWater"));
        assertNotNull("remaining time published", update(r, "device#heatingRemainingTime"));
        assertNotNull("LED brightness", update(r, "device#ledBrightness"));
        assertNotNull("lastPrime timestamp parsed", update(r, "device#lastPrime"));
    }

    /** While the side is OFF the meaningless raw 0 must not flip the shown target to 27 C. */
    @Test
    public void offStateZeroHoldsPreviousTarget() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"off\"}}", null, null);
        DeviceData off = deviceData("{\"result\":{\"leftHeatingLevel\":0,\"leftTargetHeatingLevel\":0,"
                + "\"leftNowHeating\":false}}");

        var r1 = BedSideChannelSync.compute(off, ud, "left", false, 30, NOW, ZONE, null, null, null, -41.0);
        var target = (QuantityType<?>) update(r1, "current#targetTemperature").state();
        assertEquals(22.11, target.doubleValue(), 0.01);
        assertEquals(Double.valueOf(-41.0), r1.lastKnownTargetLevel);

        // first poll ever has nothing to hold: shows the neutral 27 C
        var r2 = BedSideChannelSync.compute(off, ud, "left", false, 30, NOW, ZONE, null, null, null, null);
        var t2 = (QuantityType<?>) update(r2, "current#targetTemperature").state();
        assertEquals(27.0, t2.doubleValue(), 0.01);
        assertEquals(Double.valueOf(0.0), r2.lastKnownTargetLevel);
    }

    /** No device target at all: Autopilot's bedTimeLevel drives the channel instead. */
    @Test
    public void autopilotFallbackWhenTargetAbsent() {
        var ud = userData("{\"days\":[]}",
                "{\"currentState\":{\"type\":\"smart\"},\"smart\":{\"bedTimeLevel\":-32}}", null, null);
        DeviceData noTarget = deviceData("{\"result\":{\"leftHeatingLevel\":-16}}");

        var r = BedSideChannelSync.compute(noTarget, ud, "left", false, 30, NOW, ZONE, null, null, null, null);
        assertTrue(r.targetLevelAbsent);
        var target = (QuantityType<?>) update(r, "current#targetTemperature").state();
        assertEquals(23.13, target.doubleValue(), 0.01);
    }

    // ==================== sleep session channels ====================

    private static final String TRENDS = """
            {"days":[
              {"score":74,"tnt":15,
               "presenceStart":"2026-08-21T21:31:00Z","presenceEnd":"2026-08-22T04:07:30Z",
               "lightDuration":9480,"deepDuration":4560,"remDuration":6030,
               "sleepDuration":20070,"presenceDuration":28110,
               "sleepQualityScore":{"total":84,"hrv":{"current":48.4},
                 "respiratoryRate":{"current":15.8}},
               "sleepRoutineScore":{"total":79},
               "sessions":[{"timeseries":{
                  "tempBedC":[["2026-08-21T21:31:00Z","None"],["2026-08-21T22:00:00Z",26.17]],
                  "tempRoomC":[["2026-08-21T21:31:00Z","None"],["2026-08-21T22:00:00Z",27.27]],
                  "heartRate":[["2026-08-21T21:31:00Z","None"],["2026-08-21T22:00:00Z",48]]}}]},
              {"score":80,"sessions":[{"timeseries":{"heartRate":[["2026-08-22T11:05:00Z",62]]}}]}
            ]}""";

    @Test
    public void currentAndLastSleepChannelsPopulated() {
        var ud = userData(TRENDS, "{\"currentState\":{\"type\":\"smart\"}}", null, null);
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false,
                30, NOW, ZONE, null, null, null, null);

        // The CURRENT (latest-day) session carries no temps in this fixture - its
        // heartRate is fresh though. Temps live on the previous day's session which
        // only feeds the lastSleep summary channels, so no temperature updates here.
        assertNull(update(r, "current#bedTemperature"));
        assertNull(update(r, "device#roomTemperature"));
        // current session's heart rate is the fresh 62 bpm reading (11:05Z)
        assertEquals(62.0, ((DecimalType) update(r, "current#heartRate").state())
                .doubleValue(), 1e-9);
        // heart rate 11:05Z is 55 min old at NOW=12:00Z -> beyond the 10 min window: OFF
        assertEquals(OnOffType.OFF, update(r, "base#bedPresence").state());

        // last completed sleep comes from the PREVIOUS day (score 74)
        assertEquals(74.0, ((DecimalType) update(r, "lastSleep#sleepScore").state())
                .doubleValue(), 1e-9);
        // awake duration = presenceDuration - sleepDuration = 8040 s
        var awake = (QuantityType<?>) update(r, "lastSleep#awakeDuration").state();
        assertEquals(8040.0, awake.doubleValue(), 1e-9);
        // tnt lives on the current day only; the previous day in this fixture has none
        assertNull(update(r, "lastSleep#tossesTurns"));
        assertNull("no stage: session ended hours ago", update(r, "current#sleepStage"));
    }

    /** A live in-progress session publishes the stage covering "now". */
    @Test
    public void liveSessionPublishesCurrentStage() {
        String liveTrends = """
                {"days":[{"presenceStart":"2026-08-22T11:50:00Z",
                  "sessions":[{"sleepStart":"2026-08-22T11:50:00Z","sleepEnd":"2026-08-22T13:00:00Z",
                    "stages":[{"stage":"awake","duration":600},{"stage":"deep","duration":1800}],
                    "timeseries":{"tempBedC":[["2026-08-22T11:55:00Z",30.5]],
                      "heartRate":[["2026-08-22T11:59:00Z",55]]}}]}]}""";
        var ud = userData(liveTrends, "{\"currentState\":{\"type\":\"smart\"}}", null, null);
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false,
                30, NOW, ZONE, null, null, null, null);
        // 10 minutes in: the awake segment (600s) is fully consumed, so per the
        // documented rule the LAST fully-elapsed segment stays current until new
        // data arrives - which is still "awake" only until the next segment starts
        // covering now. At exactly 600s the deep segment covers now -> "deep".
        assertEquals("deep", ((StringType) update(r, "current#sleepStage").state())
                .toString());
        assertEquals(OnOffType.ON, update(r, "base#bedPresence").state());
    }

    // ==================== away mode / power LWW / alarms ====================

    /** Away mode stays UNDEF until this user's first observation or command. */
    @Test
    public void awayModeUndefinedUntilFirstObservation() {
        var ud = userData("{\"days\":[]}", null, null, null);
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false,
                30, NOW, ZONE, null, null, null, null);
        assertEquals(UnDefType.UNDEF, update(r, "device#awayMode").state());

        // a pending command alone is enough to leave UNDEF
        var r2 = BedSideChannelSync.compute(new DeviceData(), ud, "left", false,
                30, NOW, ZONE, null, null, new LastWriteWins.CommandedValue(NOW, true), null);
        assertEquals(OnOffType.ON, update(r2, "device#awayMode").state());
    }

    /** Away mode merges like every mutable channel: newer observation wins. */
    @Test
    public void awayModeLwwResolvesAgainstCommandStamp() {
        var ud = userData("{\"days\":[]}", null, null, null);

        // poll started AFTER the command: server truth (away=ON) wins even though
        // the command said OFF - and agreeing polled value retires the command.
        ud.awayObserved = true;
        ud.awayPolledAt = NOW.minusSeconds(10);
        var post = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE,
                null, null, new LastWriteWins.CommandedValue(NOW.minusSeconds(60), false), null);
        assertEquals(OnOffType.ON, update(post, "device#awayMode").state());
        assertTrue("polled confirmation retires the command", post.retireAwayModeCommand);

        // pre-command poll: command wins, stays pending
        ud.awayPolledAt = NOW.minusSeconds(120);
        var pre = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE,
                null, null, new LastWriteWins.CommandedValue(NOW.minusSeconds(60), false), null);
        assertEquals(OnOffType.OFF, update(pre, "device#awayMode").state());
        assertFalse("contradicting stale poll keeps the command pending", pre.retireAwayModeCommand);

        // no command: polled value publishes as-is
        var noCmd = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE,
                null, null, null, null);
        assertEquals(OnOffType.ON, update(noCmd, "device#awayMode").state());
        assertFalse(noCmd.retireAwayModeCommand);
    }

    @Test
    public void sidePowerLwwAgainstPolledType() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"off\"}}", null, null);
        ud.temperatureAt = NOW.minusSeconds(60);

        // command newer than poll: OFF wins despite polled "off" agreeing? polled agrees -> retires
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE,
                new LastWriteWins.CommandedValue(NOW.minusSeconds(30), false), null, null, null);
        assertEquals(OnOffType.OFF, update(r, "device#sidePower").state());
        assertTrue("server value agrees with resolved -> retire", r.retireSidePowerCommand);

        // contradicting stale poll: command ON beats older poll-OFF, stays pending
        var r2 = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE,
                new LastWriteWins.CommandedValue(NOW, true), null, null, null);
        assertEquals(OnOffType.ON, update(r2, "device#sidePower").state());
        assertTrue(!r2.retireSidePowerCommand);
    }

    @Test
    public void alarmChannelsClearedOnFreshEmptyPoll() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"smart\"}}", "null", null);
        ud.alarmsPolledAt = NOW; // fresh empty poll (e.g. subscription lapse)
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE, null, null, null, null);
        assertEquals(UnDefType.UNDEF, update(r, "current#nextAlarm").state());
        assertEquals(UnDefType.UNDEF, update(r, "current#alarmEnabled").state());
    }

    @Test
    public void selectedAlarmPublishesComputedScheduleAndEnabled() {
        String alarms = """
                {"alarms":[{"id":"a1","enabled":true,"time":"07:00:00",
                  "repeat":{"enabled":true,"weekDays":{}}}]}""";
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"smart\"}}", alarms, null);
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE, null, null, null, null);

        var next = (DateTimeType) update(r, "current#nextAlarm").state();
        // daily 07:00 UTC alarm; NOW is Sat 12:00 UTC -> tomorrow 07:00
        assertEquals(Instant.parse("2026-08-23T07:00:00Z"), next.getInstant());
        assertEquals(OnOffType.ON, update(r, "current#alarmEnabled").state());
    }

    // ==================== base + pillow ====================

    @Test
    public void baseChannelsPublished() {
        var ud = userData("{\"days\":[]}", null, null,
                "{\"left\":{\"preset\":{\"name\":\"reading\"},\"torso\":{\"currentAngle\":30},"
                + "\"leg\":{\"currentAngle\":8},\"inSnoreMitigation\":false}}");
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE, null, null, null, null);
        assertEquals("reading", ((StringType) update(r, "base#preset").state())
                .toString());
        assertEquals(30.0, ((QuantityType<?>) update(r, "base#headAngle").state())
                .doubleValue(), 1e-9);
        assertEquals(8.0, ((QuantityType<?>) update(r, "base#feetAngle").state())
                .doubleValue(), 1e-9);
        assertEquals(OnOffType.OFF, update(r, "base#snoreMitigation").state());
    }

    @Test
    public void pillowChannelsPublishedWhenPresent() {
        var ud = userData("{\"days\":[]}", null, null, null);
        ud.pillowData = EightSleepApiClient.parsePillowData("""
                {"devices":[{"device":{"specialization":"pillow","side":"left","deviceId":"p1"},
                  "currentLevel":-15,"currentState":{"type":"smart"}}]}""");
        var r = BedSideChannelSync.compute(new DeviceData(), ud, "left", false, 30, NOW, ZONE, null, null, null, null);
        assertEquals(OnOffType.ON, update(r, "pillow#pillowPower").state());
        assertEquals(-15.0, ((DecimalType) update(r, "pillow#pillowHeatingLevel")
                .state()).doubleValue(), 1e-9);
    }

    // ==================== fahrenheit mode ====================

    @Test
    public void fahrenheitQuantitiesWhenConfigured() {
        var ud = userData("{\"days\":[]}", "{\"currentState\":{\"type\":\"smart\"}}", null, null);
        var r = BedSideChannelSync.compute(deviceData(FULL_DEVICE), ud, "left",
                /* fahrenheit */ true, 30, NOW, ZONE, null, null, null, null);
        var target = (QuantityType<?>) update(r, "current#targetTemperature").state();
        assertEquals(org.openhab.core.library.unit.ImperialUnits.FAHRENHEIT, target.getUnit());
        assertEquals(71.75, target.doubleValue(), 0.05);
    }
}
