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

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.handler.LastWriteWins.CommandedValue;
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.binding.eightsleep.internal.model.HeatingLevelConversion;
import org.openhab.binding.eightsleep.internal.model.TrendParser;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;
import org.openhab.binding.eightsleep.internal.sleep.DataFreshness;
import org.openhab.binding.eightsleep.internal.sleep.SleepSession;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonObject;

/**
 * Pure resolver turning cached account data into the channel updates of one bed
 * side: input is the polled cache plus the pending command stamps, output is the
 * list of {@code group#channel -> State} updates, the thing-status decision and
 * which commands the server confirmed. No openHAB framework objects involved,
 * which makes the entire channel mapping testable against fixture payloads.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class BedSideChannelSync {

    /** One channel state publish: the UID fragment ({@code group#channel}) and its state. */
    public record ChannelUpdate(String channelUid, State state) {
    }

    /** What the handler should do with the thing status after applying the updates. */
    public enum StatusAction {
        /** Leave the status untouched. */
        NONE,
        /** Bridge produces no device data at all. */
        BRIDGE_OFFLINE,
        /** Data flows but this userId is never polled (bad configuration). */
        USER_NOT_FOUND,
        /** Cached data exceeded the freshness threshold. */
        STALE_DATA,
        /** Cache is fresh - the thing should be ONLINE. */
        ONLINE
    }

    /** Everything the sync decided; the handler applies it without further thinking. */
    public static class Result {
        public final List<ChannelUpdate> updates = new ArrayList<>();
        public StatusAction statusAction = StatusAction.NONE;
        /** True when the device payload lacks the target-level key for this side. */
        public boolean targetLevelAbsent;
        /** New value of the handler's persisted shown-target level (null = unchanged). */
        public @Nullable Double lastKnownTargetLevel;
        /** Set when the server confirmed the side-power command and it may be retired. */
        public boolean retireSidePowerCommand;
        /** Alarm id whose enabled-command the server confirmed (null = none). */
        public @Nullable String retireAlarmId;
    }

    private BedSideChannelSync() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Computes all channel updates for one sync cycle.
     *
     * @param sidePowerCommand pending side-power command stamp (null = none)
     * @param alarmEnabledCommand pending command stamp for the selected alarm (null = none)
     * @param lastKnownTargetLevel previously persisted shown target level (null = never set)
     */
    public static Result compute(@Nullable DeviceData deviceData, @Nullable UserDataCache userData,
            String side, boolean fahrenheit, boolean awayPolledOnce, long userIntervalSeconds, Instant now,
            ZoneId zone, @Nullable CommandedValue sidePowerCommand, @Nullable CommandedValue alarmEnabledCommand,
            @Nullable Double lastKnownTargetLevel) {
        Result r = new Result();

        if (deviceData == null) {
            r.statusAction = StatusAction.BRIDGE_OFFLINE;
            return r;
        }
        if (userData == null) {
            r.statusAction = StatusAction.USER_NOT_FOUND;
            return r;
        }
        r.statusAction = DataFreshness.isStale(userData.lastUpdated, now, userIntervalSeconds)
                ? StatusAction.STALE_DATA : StatusAction.ONLINE;

        Double heatingLevelRaw = deviceData.getHeatingLevel(side);
        Double targetLevelRaw = deviceData.getTargetHeatingLevel(side);
        Boolean nowHeating = "right".equals(side) ? deviceData.rightNowHeating : deviceData.leftNowHeating;
        r.targetLevelAbsent = targetLevelRaw == null;

        // --- heating / temperature channels ---
        if (heatingLevelRaw != null) {
            add(r, GROUP_DEVICE, CHANNEL_HEATING_LEVEL, new DecimalType(heatingLevelRaw));
        }
        if (targetLevelRaw != null) {
            // Upstream quirk: while off the API reports a meaningless 0 (27 C); hold the
            // last meaningful target instead of flipping to it.
            double shownLevel = resolveShownTargetLevel(targetLevelRaw, nowHeating, lastKnownTargetLevel);
            r.lastKnownTargetLevel = shownLevel;
            addQuantity(r, GROUP_CURRENT, CHANNEL_TARGET_TEMPERATURE,
                    levelToTemp(shownLevel, fahrenheit), fahrenheit);
        } else {
            // Field absent (typical when the side is off): fall back to the Autopilot
            // schedule, mirroring upstream's get_autopilot_target_temp fallback.
            Double autopilot = autopilotTargetLevel(userData.temperature);
            if (autopilot != null) {
                addQuantity(r, GROUP_CURRENT, CHANNEL_TARGET_TEMPERATURE,
                        levelToTemp(autopilot, fahrenheit), fahrenheit);
            }
        }
        Integer remaining = "right".equals(side) ? deviceData.rightHeatingDuration : deviceData.leftHeatingDuration;
        if (remaining != null) {
            add(r, GROUP_DEVICE, CHANNEL_REMAINING_TIME, new QuantityType<>(remaining, Units.SECOND));
        }
        if (nowHeating != null && targetLevelRaw != null) {
            add(r, GROUP_DEVICE, CHANNEL_HEATING_STATE,
                    new StringType(deriveHeatingState(nowHeating, targetLevelRaw)));
        }

        // --- sleep session channels ---
        TrendParser trends = userData.getTrends();
        Double measuredBedC = trends.isEmpty() ? null
                : TrendParser.latestSeriesValue(trends.getCurrentSession(), "tempBedC");
        if (!trends.isEmpty()) {
            JsonObject currentDay = trends.getDay(0);
            JsonObject previousDay = trends.getDay(1);
            JsonObject currentSession = trends.getCurrentSession();

            if (currentDay != null) {
                Instant presenceStart = TrendParser.parseTimestamp(
                        TrendParser.getString(currentDay, "presenceStart"));
                if (presenceStart != null) {
                    add(r, GROUP_CURRENT, CHANNEL_SESSION_START, new DateTimeType(presenceStart));
                }
                Instant presenceEnd = TrendParser.parseTimestamp(
                        TrendParser.getString(currentDay, "presenceEnd"));
                if (presenceEnd != null) {
                    add(r, GROUP_CURRENT, CHANNEL_SESSION_END, new DateTimeType(presenceEnd));
                }
                putDecimal(r, GROUP_CURRENT, CHANNEL_SLEEP_SCORE, TrendParser.getDouble(currentDay, "score"));
                putDecimal(r, GROUP_CURRENT, CHANNEL_QUALITY_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(currentDay, "sleepQualityScore"), "total"));
                putDecimal(r, GROUP_CURRENT, CHANNEL_ROUTINE_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(currentDay, "sleepRoutineScore"), "total"));
                putDecimal(r, GROUP_CURRENT, CHANNEL_HRV, TrendParser.getDouble(
                        TrendParser.getObject(TrendParser.getObject(currentDay, "sleepQualityScore"), "hrv"),
                        "current"));
                putDecimal(r, GROUP_CURRENT, CHANNEL_BREATH_RATE, TrendParser.getDouble(
                        TrendParser.getObject(
                                TrendParser.getObject(currentDay, "sleepQualityScore"), "respiratoryRate"),
                        "current"));

                Double tnt = TrendParser.getDouble(currentDay, "tnt");
                if (tnt != null) {
                    add(r, GROUP_LAST_SLEEP, CHANNEL_TOSS_TURNS, new DecimalType(tnt));
                }
            }

            if (currentSession != null) {
                // Timeseries values are celsius readings ("tempRoomC" naming is literal)
                putLatestCelsius(r, currentSession, "tempRoomC", GROUP_DEVICE, CHANNEL_ROOM_TEMPERATURE);
                putLatest(r, currentSession, "heartRate", GROUP_CURRENT, CHANNEL_HEART_RATE);
                putLatest(r, currentSession, "respiratoryRate", GROUP_CURRENT, CHANNEL_RESPIRATORY_RATE);
                add(r, GROUP_BASE, CHANNEL_BED_PRESENCE,
                        OnOffType.from(SleepSession.isPresent(currentSession, now)));
                String stage = SleepSession.currentStage(currentSession, now);
                if (stage != null) {
                    add(r, GROUP_CURRENT, CHANNEL_SLEEP_STAGE, new StringType(stage));
                }
            } else {
                add(r, GROUP_BASE, CHANNEL_BED_PRESENCE, OnOffType.OFF);
            }

            // last completed sleep: the previous day
            if (previousDay != null) {
                putDecimal(r, GROUP_LAST_SLEEP, CHANNEL_SLEEP_SCORE,
                        TrendParser.getDouble(previousDay, "score"));
                putDecimal(r, GROUP_LAST_SLEEP, CHANNEL_QUALITY_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(previousDay, "sleepQualityScore"), "total"));
                putDecimal(r, GROUP_LAST_SLEEP, CHANNEL_ROUTINE_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(previousDay, "sleepRoutineScore"), "total"));

                Double lightDuration = TrendParser.getDouble(previousDay, "lightDuration");
                Double deepDuration = TrendParser.getDouble(previousDay, "deepDuration");
                Double remDuration = TrendParser.getDouble(previousDay, "remDuration");
                Double presenceDuration = TrendParser.getDouble(previousDay, "presenceDuration");
                Double sleepDuration = TrendParser.getDouble(previousDay, "sleepDuration");
                putDuration(r, GROUP_LAST_SLEEP, CHANNEL_LIGHT_SLEEP, lightDuration);
                putDuration(r, GROUP_LAST_SLEEP, CHANNEL_DEEP_SLEEP, deepDuration);
                putDuration(r, GROUP_LAST_SLEEP, CHANNEL_REM_SLEEP, remDuration);
                if (sleepDuration != null) {
                    putDuration(r, GROUP_LAST_SLEEP, CHANNEL_TIME_SLEPT, sleepDuration);
                } else if (lightDuration != null && deepDuration != null && remDuration != null) {
                    putDuration(r, GROUP_LAST_SLEEP, CHANNEL_TIME_SLEPT,
                            lightDuration + deepDuration + remDuration);
                }
                if (presenceDuration != null && sleepDuration != null) {
                    putDuration(r, GROUP_LAST_SLEEP, CHANNEL_AWAKE_DURATION, presenceDuration - sleepDuration);
                }
                Instant lastStart = TrendParser.parseTimestamp(
                        TrendParser.getString(previousDay, "presenceStart"));
                Instant lastEnd = TrendParser.parseTimestamp(
                        TrendParser.getString(previousDay, "presenceEnd"));
                if (lastStart != null) {
                    add(r, GROUP_LAST_SLEEP, CHANNEL_SESSION_START, new DateTimeType(lastStart));
                }
                if (lastEnd != null) {
                    add(r, GROUP_LAST_SLEEP, CHANNEL_SESSION_END, new DateTimeType(lastEnd));
                }
            }
        }

        // --- bed temperature: MEASURED surface temperature wins; the level-derived
        // conversion is only a fallback when no timeseries data exists yet.
        if (measuredBedC != null) {
            add(r, GROUP_CURRENT, CHANNEL_BED_TEMPERATURE, new QuantityType<>(measuredBedC, SIUnits.CELSIUS));
        } else if (heatingLevelRaw != null) {
            addQuantity(r, GROUP_CURRENT, CHANNEL_BED_TEMPERATURE,
                    levelToTemp(heatingLevelRaw, fahrenheit), fahrenheit);
        }

        // --- base channels ---
        publishBaseChannels(userData.getBaseSide(side), r);

        // --- pillow (Pod 5 accessory) ---
        EightSleepApiClient.PillowData pillowData = userData.pillowData;
        EightSleepApiClient.PillowEntry pillow = pillowData != null ? pillowData.findPillow(side) : null;
        if (pillow != null) {
            int rawPillowLevel = pillow.getLevel();
            addQuantity(r, GROUP_PILLOW, CHANNEL_PILLOW_TARGET_TEMPERATURE,
                    levelToTemp(rawPillowLevel, fahrenheit), fahrenheit);
            add(r, GROUP_PILLOW, CHANNEL_PILLOW_POWER, OnOffType.from(pillow.isOn()));
            add(r, GROUP_PILLOW, CHANNEL_PILLOW_HEATING_LEVEL, new DecimalType(rawPillowLevel));
        }

        // --- hub LED brightness / water / priming ---
        if (deviceData.ledBrightnessLevel != null) {
            add(r, GROUP_DEVICE, CHANNEL_LED_BRIGHTNESS,
                    new DecimalType(deviceData.ledBrightnessLevel.doubleValue()));
        }
        if (deviceData.hasWater != null) {
            add(r, GROUP_DEVICE, CHANNEL_HAS_WATER, OnOffType.from(deviceData.hasWater));
        }
        if (deviceData.needsPriming != null) {
            add(r, GROUP_DEVICE, CHANNEL_NEEDS_PRIMING, OnOffType.from(deviceData.needsPriming));
        }
        if (deviceData.priming != null) {
            add(r, GROUP_DEVICE, CHANNEL_IS_PRIMING, OnOffType.from(deviceData.priming));
        }
        if (deviceData.lastPrime != null && !deviceData.lastPrime.isBlank()) {
            Instant lastPrime = TrendParser.parseTimestamp(deviceData.lastPrime);
            if (lastPrime != null) {
                add(r, GROUP_DEVICE, CHANNEL_LAST_PRIME, new DateTimeType(lastPrime));
            }
        }

        // --- away mode: UNDEF until the first poll/command has spoken ---
        if (!awayPolledOnce) {
            add(r, GROUP_DEVICE, CHANNEL_AWAY_MODE, UnDefType.UNDEF);
        } else {
            add(r, GROUP_DEVICE, CHANNEL_AWAY_MODE, OnOffType.from(userData.awayMode));
        }

        // --- side power (live, last-write-wins against the temperature poll) ---
        String powerType = TrendParser.getString(
                TrendParser.getObject(userData.temperature, "currentState"), "type");
        Boolean polledOn;
        if (powerType != null) {
            polledOn = !"off".equalsIgnoreCase(powerType);
        } else {
            polledOn = targetLevelRaw != null ? targetLevelRaw.doubleValue() != 0.0 : null;
        }
        Boolean resolvedPower = LastWriteWins.resolveLatest(polledOn, userData.temperatureAt, sidePowerCommand);
        if (resolvedPower != null) {
            add(r, GROUP_DEVICE, CHANNEL_SIDE_POWER, OnOffType.from(resolvedPower));
            r.retireSidePowerCommand = LastWriteWins.shouldRetireCommand(polledOn, resolvedPower);
        }

        // --- alarm state ---
        EightSleepApiClient.Alarm nextAlarm = AlarmSelector.findTargetAlarm(userData, now, zone);
        if (AlarmSelector.shouldClearAlarmChannels(nextAlarm != null, userData.alarms.size(),
                userData.alarmsPolledAt, now, userIntervalSeconds)) {
            add(r, GROUP_CURRENT, CHANNEL_NEXT_ALARM, UnDefType.UNDEF);
            add(r, GROUP_CURRENT, CHANNEL_ALARM_ENABLED, UnDefType.UNDEF);
            add(r, GROUP_CURRENT, CHANNEL_ALARM_TIME, UnDefType.UNDEF);
        }
        if (nextAlarm != null && nextAlarm.id != null) {
            Instant computedRun = nextAlarm.computeNextRun(zone, now);
            if (computedRun != null) {
                add(r, GROUP_CURRENT, CHANNEL_NEXT_ALARM, new DateTimeType(computedRun));
            }
            java.time.LocalTime alarmTimeOfDay = TrendParser.parseTimeOfDay(nextAlarm.time);
            if (alarmTimeOfDay != null) {
                add(r, GROUP_CURRENT, CHANNEL_ALARM_TIME, new DateTimeType(alarmTimeOfDay
                        .atDate(LocalDate.ofInstant(now, zone)).atZone(zone).toInstant()));
            }
            Boolean resolved = LastWriteWins.resolveLatest(nextAlarm.enabled, userData.alarmsPolledAt,
                    alarmEnabledCommand);
            if (resolved != null) {
                add(r, GROUP_CURRENT, CHANNEL_ALARM_ENABLED, OnOffType.from(resolved));
                if (LastWriteWins.shouldRetireCommand(nextAlarm.enabled, resolved)) {
                    r.retireAlarmId = nextAlarm.id; // server confirmed
                }
            }
        }
        return r;
    }

    // ==================== mapping helpers ====================

    private static void add(Result r, String group, String channel, State state) {
        r.updates.add(new ChannelUpdate(group + "#" + channel, state));
    }

    private static void addQuantity(Result r, String group, String channel, double temperature,
            boolean fahrenheit) {
        add(r, group, channel, new QuantityType<>(temperature,
                fahrenheit ? ImperialUnits.FAHRENHEIT : SIUnits.CELSIUS));
    }

    private static double levelToTemp(double level, boolean fahrenheit) {
        return HeatingLevelConversion.levelToTemperature(level, fahrenheit);
    }

    /**
     * Upstream quirk: while off the API reports target level 0 (27 C), which is
     * meaningless. The shown target holds the last MEANINGFUL level; a genuinely
     * commanded 0 (heating flag set) or any non-zero value wins. Returns both the
     * shown level; caller persists it.
     */
    static double resolveShownTargetLevel(double targetLevelRaw, @Nullable Boolean nowHeating,
            @Nullable Double previousShown) {
        boolean meaningful = targetLevelRaw != 0 || Boolean.TRUE.equals(nowHeating);
        if (meaningful || previousShown == null) {
            return targetLevelRaw;
        }
        return previousShown;
    }

    /**
     * Heating/cooling/idle from the raw target level sign. Level 0 is neutral
     * (27 C) - actively tracking it is neither heating nor cooling.
     */
    static String deriveHeatingState(boolean nowHeating, double targetLevelRaw) {
        if (!nowHeating || targetLevelRaw == 0) {
            return "idle";
        }
        return targetLevelRaw > 0 ? "heating" : "cooling";
    }

    /**
     * Raw heating level Autopilot targets (smartSchedule.bedTimeLevel), or null.
     */
    static @Nullable Double autopilotTargetLevel(@Nullable JsonObject temperature) {
        return TrendParser.getDouble(TrendParser.getObject(temperature, "smart"), "bedTimeLevel");
    }

    private static org.openhab.binding.eightsleep.internal.model.BaseData.@Nullable SideData baseSide(
            UserDataCache userData, String side) {
        return userData.getBaseSide(side);
    }

    private static void publishBaseChannels(
            org.openhab.binding.eightsleep.internal.model.BaseData.@Nullable SideData baseSide, Result r) {
        if (baseSide == null) {
            return;
        }
        if (baseSide.preset != null && baseSide.preset.name != null) {
            add(r, GROUP_BASE, CHANNEL_BASE_PRESET, new StringType(baseSide.preset.name));
        }
        if (baseSide.torso != null && baseSide.torso.currentAngle != null) {
            add(r, GROUP_BASE, CHANNEL_HEAD_ANGLE,
                    new QuantityType<>(baseSide.torso.currentAngle, Units.DEGREE_ANGLE));
        }
        if (baseSide.leg != null && baseSide.leg.currentAngle != null) {
            add(r, GROUP_BASE, CHANNEL_FEET_ANGLE,
                    new QuantityType<>(baseSide.leg.currentAngle, Units.DEGREE_ANGLE));
        }
        if (baseSide.inSnoreMitigation != null) {
            add(r, GROUP_BASE, CHANNEL_SNORE_MITIGATION, OnOffType.from(baseSide.inSnoreMitigation));
        }
    }

    private static void putDecimal(Result r, String group, String channel, @Nullable Double value) {
        if (value != null) {
            add(r, group, channel, new DecimalType(value));
        }
    }

    private static void putLatest(Result r, @Nullable JsonObject session, String seriesName, String group,
            String channel) {
        Double value = TrendParser.latestSeriesValue(session, seriesName);
        if (value != null) {
            add(r, group, channel, new DecimalType(value));
        }
    }

    private static void putLatestCelsius(Result r, @Nullable JsonObject session, String seriesName, String group,
            String channel) {
        Double value = TrendParser.latestSeriesValue(session, seriesName);
        if (value != null) {
            add(r, group, channel, new QuantityType<>(value, SIUnits.CELSIUS));
        }
    }

    private static void putDuration(Result r, String group, String channel, @Nullable Double seconds) {
        if (seconds != null && seconds >= 0) {
            add(r, group, channel, new QuantityType<>(seconds, Units.SECOND));
        }
    }
}
