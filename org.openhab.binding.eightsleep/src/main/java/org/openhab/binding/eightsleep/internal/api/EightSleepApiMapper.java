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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.dto.ApiRequests;
import org.openhab.binding.eightsleep.internal.api.dto.ApiResponses;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BaseState;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceAssignments;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.model.PillowEntry;
import org.openhab.binding.eightsleep.internal.model.PillowState;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.openhab.binding.eightsleep.internal.model.TemperatureState;
import org.openhab.binding.eightsleep.internal.model.TrendData;
import org.openhab.binding.eightsleep.internal.model.UserCurrentDevice;
import org.openhab.binding.eightsleep.internal.model.UserProfile;

import com.google.gson.JsonElement;

/**
 * Maps Eight Sleep wire contracts to binding domain models.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
final class EightSleepApiMapper {

    private static final DateTimeFormatter ALARM_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private EightSleepApiMapper() {
        throw new IllegalAccessError("Non-instantiable");
    }

    static DeviceState toDeviceState(ApiResponses.@Nullable Device source) {
        if (source == null) {
            return DeviceState.EMPTY;
        }
        return new DeviceState(source.leftHeatingLevel, source.rightHeatingLevel, source.leftTargetHeatingLevel,
                source.rightTargetHeatingLevel, source.leftNowHeating, source.rightNowHeating,
                source.leftHeatingDuration, source.rightHeatingDuration, source.hasWater, source.needsPriming,
                source.priming, ApiValueParser.parseTimestamp(source.lastPrime), source.ledBrightnessLevel,
                source.features != null ? source.features : List.of());
    }

    static DeviceAssignments toDeviceAssignments(ApiResponses.@Nullable DeviceUsers source) {
        if (source == null) {
            return DeviceAssignments.EMPTY;
        }
        return new DeviceAssignments(source.leftUserId, source.rightUserId,
                source.awaySides != null ? source.awaySides : Map.of());
    }

    static @Nullable UserCurrentDevice toCurrentDevice(ApiResponses.@Nullable CurrentDevice source) {
        return source != null ? new UserCurrentDevice(BedSide.fromString(source.side), source.id) : null;
    }

    static UserProfile toUserProfile(String userId, ApiResponses.@Nullable UserProfileEnvelope source) {
        return new UserProfile(userId,
                source != null && source.user != null ? toCurrentDevice(source.user.currentDevice) : null);
    }

    static String toCurrentUserId(ApiResponses.@Nullable MeEnvelope source) {
        if (source != null && source.user != null && source.user.userId != null) {
            return source.user.userId;
        }
        throw new IllegalStateException("No userId in /users/me response");
    }

    static ApiRequests.AlarmUpdate toAlarmUpdate(Alarm alarm, @Nullable Boolean enabled,
            @Nullable String timeOverride) {
        String alarmTime = alarm.time() != null ? ALARM_TIME_FORMATTER.format(alarm.time()) : "07:00:00";
        return new ApiRequests.AlarmUpdate(alarm.id(), timeOverride != null ? timeOverride : alarmTime,
                enabled != null ? enabled : alarm.enabled(), alarm.repeat() != null ? alarm.repeat() : Map.of(),
                normalizeValue(alarm.thermal()), normalizeValue(alarm.vibration()), normalizeValue(alarm.audio()),
                normalizeValue(alarm.smart()), alarm.tags(), alarm.skipNext() != null ? alarm.skipNext() : false,
                alarm.snoozing() != null ? alarm.snoozing() : false);
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Double number) {
            if (number == Math.floor(number) && !number.isInfinite()) {
                return Long.valueOf(number.longValue());
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new HashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), normalizeValue(item)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EightSleepApiMapper::normalizeValue).toList();
        }
        return value;
    }

    static Map<String, String> toHouseholdDevices(ApiResponses.@Nullable HouseholdSummary source) {
        Map<String, String> devices = new LinkedHashMap<>();
        if (source == null || source.households == null) {
            return devices;
        }
        for (ApiResponses.Household household : source.households) {
            if (household.sets == null) {
                continue;
            }
            for (ApiResponses.DeviceSet set : household.sets) {
                if (set.devices == null) {
                    continue;
                }
                for (ApiResponses.HouseholdDevice device : set.devices) {
                    if (device.deviceId != null) {
                        devices.put(device.deviceId, device.deviceName != null ? device.deviceName : device.deviceId);
                    }
                }
            }
        }
        return devices;
    }

    static TrendData toTrendData(ApiResponses.@Nullable Trends source) {
        if (source == null || source.days == null) {
            return TrendData.EMPTY;
        }
        List<TrendData.Day> days = new ArrayList<>();
        for (ApiResponses.TrendDay day : source.days) {
            days.add(new TrendData.Day(day.day, day.score, day.tnt, parseTimestamp(day.presenceStart),
                    parseTimestamp(day.presenceEnd), day.lightDuration, day.deepDuration, day.remDuration,
                    day.sleepDuration, day.presenceDuration, toScore(day.sleepQualityScore),
                    toScore(day.sleepRoutineScore), toSessions(day.sessions)));
        }
        return new TrendData(days);
    }

    private static TrendData.@Nullable Score toScore(ApiResponses.@Nullable Score source) {
        return source != null
                ? new TrendData.Score(source.total, currentValue(source.hrv), currentValue(source.respiratoryRate))
                : null;
    }

    private static @Nullable Double currentValue(ApiResponses.@Nullable CurrentValue source) {
        return source != null ? source.current : null;
    }

    private static List<TrendData.Session> toSessions(@Nullable List<ApiResponses.TrendSession> sources) {
        if (sources == null) {
            return List.of();
        }
        List<TrendData.Session> sessions = new ArrayList<>();
        for (ApiResponses.TrendSession source : sources) {
            List<TrendData.Stage> stages = new ArrayList<>();
            if (source.stages != null) {
                for (ApiResponses.Stage stage : source.stages) {
                    stages.add(new TrendData.Stage(stage.stage, stage.duration));
                }
            }
            sessions.add(new TrendData.Session(parseTimestamp(source.sleepStart), parseTimestamp(source.sleepEnd),
                    stages, toTimeseries(source.timeseries)));
        }
        return sessions;
    }

    private static Map<String, List<TrendData.Sample>> toTimeseries(
            @Nullable Map<String, List<List<JsonElement>>> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, List<TrendData.Sample>> result = new HashMap<>();
        source.forEach((name, entries) -> {
            List<TrendData.Sample> samples = new ArrayList<>();
            for (List<JsonElement> entry : entries) {
                if (entry.size() >= 2) {
                    samples.add(new TrendData.Sample(parseTimestamp(asString(entry.get(0))), asDouble(entry.get(1))));
                }
            }
            result.put(name, List.copyOf(samples));
        });
        return result;
    }

    private static @Nullable String asString(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static @Nullable Double asDouble(JsonElement value) {
        if (!value.isJsonPrimitive()) {
            return null;
        }
        try {
            return Double.valueOf(value.getAsString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<Alarm> toAlarms(ApiResponses.@Nullable Alarms source) {
        if (source == null || source.alarms == null) {
            return List.of();
        }
        List<Alarm> alarms = new ArrayList<>();
        for (ApiResponses.Alarm item : source.alarms) {
            Alarm.AlarmRepeat repeat = item.repeat != null
                    ? new Alarm.AlarmRepeat(item.repeat.enabled,
                            item.repeat.weekDays != null ? item.repeat.weekDays : Map.of())
                    : null;
            alarms.add(new Alarm(item.id, ApiValueParser.parseTimeOfDay(item.time), item.enabled, repeat,
                    copyMap(item.thermal), copyMap(item.vibration), copyMap(item.audio), copyMap(item.smart),
                    item.tags != null ? item.tags : List.of(), item.skipNext, item.snoozing,
                    ApiValueParser.parseTimestamp(item.nextTimestamp)));
        }
        return List.copyOf(alarms);
    }

    private static Map<String, Object> copyMap(@Nullable Map<String, Object> source) {
        return source != null ? source : Map.of();
    }

    static BaseState toBaseState(ApiResponses.@Nullable Base source) {
        return source != null ? new BaseState(toBaseSide(source.left), toBaseSide(source.right)) : BaseState.EMPTY;
    }

    private static BaseState.@Nullable SideState toBaseSide(ApiResponses.@Nullable BaseSide source) {
        if (source == null) {
            return null;
        }
        return new BaseState.SideState(source.preset != null ? source.preset.name : null,
                source.leg != null ? source.leg.currentAngle : null,
                source.torso != null ? source.torso.currentAngle : null, source.inSnoreMitigation);
    }

    static PlayerState toPlayerState(ApiResponses.@Nullable Player source) {
        if (source == null) {
            return PlayerState.EMPTY;
        }
        PlayerState.Track track = source.currentTrack != null
                ? new PlayerState.Track(source.currentTrack.id, source.currentTrack.name,
                        source.currentTrack.categoryId, source.currentTrack.currentPosition,
                        source.currentTrack.trackDuration)
                : null;
        return new PlayerState(source.state, source.volume, track, source.hardwareInfo != null);
    }

    static TemperatureState toTemperatureState(ApiResponses.@Nullable Temperature source) {
        if (source == null) {
            return TemperatureState.EMPTY;
        }
        return new TemperatureState(source.currentLevel, source.currentState != null ? source.currentState.type : null,
                source.smart != null ? source.smart : Map.of());
    }

    static PillowState toPillowState(ApiResponses.@Nullable TemperatureAll source) {
        if (source == null || source.devices == null) {
            return PillowState.EMPTY;
        }
        List<PillowEntry> devices = new ArrayList<>();
        for (ApiResponses.TemperatureDevice item : source.devices) {
            devices.add(new PillowEntry(item.device != null ? item.device.specialization : null,
                    item.device != null ? BedSide.fromString(item.device.side) : null,
                    item.device != null ? item.device.deviceId : null, item.currentLevel,
                    item.currentState != null ? item.currentState.type : null));
        }
        return new PillowState(devices);
    }

    static @Nullable Instant parseTimestamp(@Nullable String value) {
        return ApiValueParser.parseTimestamp(value);
    }
}
