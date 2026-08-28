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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.dto.ApiRequests;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BaseState;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceAssignments;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.model.PillowState;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.openhab.binding.eightsleep.internal.model.TemperatureState;
import org.openhab.binding.eightsleep.internal.model.TrendData;
import org.openhab.binding.eightsleep.internal.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain-facing Eight Sleep operations built on the API contract client.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EightSleepService.class);
    private static final int HEATING_LEVEL_MIN = -100;
    private static final int HEATING_LEVEL_MAX = 100;

    private final EightSleepApiClient client;

    /**
     * Creates a service using a production API client.
     *
     * @param tokenManager the OAuth token manager
     */
    public EightSleepService(TokenManager tokenManager) {
        this(new EightSleepApiClient(tokenManager));
    }

    /**
     * Creates a service using the supplied API client.
     *
     * @param client the API contract client
     */
    public EightSleepService(EightSleepApiClient client) {
        this.client = client;
    }

    /**
     * Fetches and maps a device.
     *
     * @param deviceId the device identifier
     * @return a future containing the mapped device data
     */
    public CompletableFuture<DeviceState> getDeviceState(String deviceId) {
        return client.getDevice(deviceId)
                .thenApply(envelope -> EightSleepApiMapper.toDeviceState(envelope != null ? envelope.result : null));
    }

    /**
     * Fetches and maps a user profile.
     *
     * @param userId the user identifier
     * @return a future containing the mapped user profile
     */
    public CompletableFuture<UserProfile> getUserProfile(String userId) {
        return client.getUserProfile(userId).thenApply(envelope -> EightSleepApiMapper.toUserProfile(userId, envelope));
    }

    /**
     * Resolves the profiles assigned to a device, including away users.
     *
     * @param deviceId the device identifier
     * @return a future containing the successfully resolved profiles
     */
    public CompletableFuture<List<UserProfile>> getUserProfileForDevice(String deviceId) {
        return getDeviceAssignments(deviceId).thenCompose(users -> {
            List<CompletableFuture<UserProfile>> futures = new ArrayList<>();
            var ids = new LinkedHashSet<String>();
            if (users.leftUserId() != null) {
                ids.add(users.leftUserId());
            }
            if (users.rightUserId() != null) {
                ids.add(users.rightUserId());
            }
            ids.addAll(users.awaySides().values());
            for (String id : ids) {
                futures.add(getUserProfile(id).exceptionally(ex -> {
                    LOGGER.warn("Failed to resolve profile of user {}: {}", id,
                            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    return null;
                }));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
        });
    }

    /**
     * Fetches the authenticated user's identifier.
     *
     * @return a future containing the user identifier
     */
    public CompletableFuture<String> getCurrentUserId() {
        return client.getMe().thenApply(EightSleepApiMapper::toCurrentUserId);
    }

    /**
     * Fetches the devices in the authenticated user's household.
     *
     * @return a future containing device identifiers mapped to display names
     */
    public CompletableFuture<Map<String, String>> getHouseholdDevices() {
        return getCurrentUserId().thenCompose(client::getHouseholdSummary)
                .thenApply(EightSleepApiMapper::toHouseholdDevices);
    }

    /**
     * Fetches and maps the users and away-side assignments for a device.
     *
     * @param deviceId the device identifier
     * @return a future containing the device-user assignments
     */
    public CompletableFuture<DeviceAssignments> getDeviceAssignments(String deviceId) {
        return client.getDeviceUsers(deviceId).thenApply(
                envelope -> EightSleepApiMapper.toDeviceAssignments(envelope != null ? envelope.result : null));
    }

    /**
     * Fetches and maps sleep trends for an interval.
     *
     * @param userId the user identifier
     * @param start the first date to include
     * @param end the last date to include
     * @param timezone the API timezone identifier
     * @return a future containing the mapped trend data
     */
    public CompletableFuture<TrendData> getUserTrends(String userId, ZonedDateTime start, ZonedDateTime end,
            String timezone) {
        return client.getTrends(userId, start, end, timezone).thenApply(EightSleepApiMapper::toTrendData);
    }

    /**
     * Fetches and maps a user's alarms.
     *
     * @param userId the user identifier
     * @return a future containing the mapped alarms
     */
    public CompletableFuture<List<Alarm>> getAlarms(String userId) {
        return client.getAlarms(userId).thenApply(EightSleepApiMapper::toAlarms);
    }

    /**
     * Fetches and maps a user's adjustable-base state.
     *
     * @param userId the user identifier
     * @return a future containing the mapped base data
     */
    public CompletableFuture<BaseState> getBaseState(String userId) {
        return client.getBase(userId).thenApply(EightSleepApiMapper::toBaseState);
    }

    /**
     * Fetches and maps a user's audio-player state.
     *
     * @param userId the user identifier
     * @return a future containing the mapped player state
     */
    public CompletableFuture<PlayerState> getPlayerState(String userId) {
        return client.getPlayer(userId).thenApply(EightSleepApiMapper::toPlayerState);
    }

    /**
     * Fetches and maps a user's temperature state.
     *
     * @param userId the user identifier
     * @return a future containing the mapped temperature data
     */
    public CompletableFuture<TemperatureState> getTemperatureState(String userId) {
        return client.getTemperature(userId).thenApply(EightSleepApiMapper::toTemperatureState);
    }

    /**
     * Fetches and maps all temperature-controlled devices for a user.
     *
     * @param userId the user identifier
     * @return a future containing the mapped pillow data
     */
    public CompletableFuture<PillowState> getPillowState(String userId) {
        return client.getTemperatureAll(userId).thenApply(EightSleepApiMapper::toPillowState);
    }

    /**
     * Updates one stage of a user's smart-temperature schedule.
     *
     * @param userId the user identifier
     * @param level the requested heating level
     * @param sleepStage the smart-schedule stage to update
     * @return a future completed when the schedule is updated
     */
    public CompletableFuture<Void> setSmartHeatingLevel(String userId, int level, String sleepStage) {
        int clamped = clampHeatingLevel(level);
        return client.getTemperature(userId).thenCompose(response -> {
            Map<String, Integer> smart = response != null && response.smart != null ? new HashMap<>(response.smart)
                    : new HashMap<>();
            smart.put(sleepStage, clamped);
            return client.setSmartTemperature(userId, new ApiRequests.SmartTemperatureUpdate(smart));
        });
    }

    /**
     * Enables or disables an alarm while preserving its remaining settings.
     *
     * @param userId the user identifier
     * @param alarm the alarm to update
     * @param enabled whether the alarm should be enabled
     * @return a future completed when the alarm is updated
     */
    public CompletableFuture<Void> setAlarmEnabled(String userId, Alarm alarm, boolean enabled) {
        if (alarm.id() == null) {
            return failedFuture("Cannot toggle alarm without an id");
        }
        return client.updateAlarm(userId, alarm.id(), EightSleepApiMapper.toAlarmUpdate(alarm, enabled, null));
    }

    /**
     * Changes an alarm time while preserving its remaining settings.
     *
     * @param userId the user identifier
     * @param alarm the alarm to update
     * @param timeOfDay the new API time-of-day value
     * @return a future completed when the alarm is updated
     */
    public CompletableFuture<Void> setAlarmTime(String userId, Alarm alarm, String timeOfDay) {
        if (alarm.id() == null) {
            return failedFuture("Cannot reschedule alarm without an id");
        }
        return client.updateAlarm(userId, alarm.id(), EightSleepApiMapper.toAlarmUpdate(alarm, null, timeOfDay));
    }

    /**
     * Sets a device's LED brightness.
     *
     * @param deviceId the device identifier
     * @param levelPercent the requested brightness percentage
     * @return a future completed when the brightness is updated
     */
    public CompletableFuture<Void> setLedBrightness(String deviceId, int levelPercent) {
        return client.setLedBrightness(deviceId, new ApiRequests.LedBrightnessUpdate(clampPercent(levelPercent)));
    }

    /**
     * Turns a side on and applies a time-based heating level.
     *
     * @param userId the user identifier
     * @param level the requested heating level
     * @param durationSeconds the heating duration in seconds
     * @return a future completed when all temperature updates succeed
     */
    public CompletableFuture<Void> setHeatingLevel(String userId, int level, int durationSeconds) {
        int clamped = clampHeatingLevel(level);
        return turnOnSide(userId)
                .thenCompose(v -> client.setTemperatureLevel(userId, new ApiRequests.TemperatureLevelUpdate(clamped)))
                .thenCompose(v -> client.setTemperatureTimer(userId,
                        new ApiRequests.TemperatureTimerUpdate(clamped, durationSeconds)));
    }

    /**
     * Turns on temperature control in smart mode.
     *
     * @param userId the user identifier
     * @return a future completed when the side is turned on
     */
    public CompletableFuture<Void> turnOnSide(String userId) {
        return client.setTemperatureState(userId, new ApiRequests.TemperatureStateUpdate("smart"));
    }

    /**
     * Turns off temperature control.
     *
     * @param userId the user identifier
     * @return a future completed when the side is turned off
     */
    public CompletableFuture<Void> turnOffSide(String userId) {
        return client.setTemperatureState(userId, new ApiRequests.TemperatureStateUpdate("off"));
    }

    /**
     * Starts away mode for a user.
     *
     * @param userId the user identifier
     * @param returnDateIso the optional return date in API format
     * @param includePartner whether to include the user's partner
     * @return a future completed when away mode starts
     */
    public CompletableFuture<Void> setAwayMode(String userId, @Nullable String returnDateIso, boolean includePartner) {
        return client.setAwayMode(userId, new ApiRequests.AwayModeHeaders(returnDateIso, includePartner));
    }

    /**
     * Ends away mode by restoring a user's device-side assignment.
     *
     * @param userId the user identifier
     * @param deviceId the device identifier
     * @param side the side assignment
     * @return a future completed when the assignment is restored
     */
    public CompletableFuture<Void> clearAwayMode(String userId, String deviceId, String side) {
        return setBedSide(userId, deviceId, side);
    }

    /**
     * Creates or replaces a scheduled return from away mode.
     *
     * @param userId the user identifier
     * @param setId the household set identifier
     * @param returnDate the return instant
     * @param includePartner whether to include the user's partner
     * @return a future completed when the return is scheduled
     */
    public CompletableFuture<Void> setAwayReturnDate(String userId, String setId, Instant returnDate,
            boolean includePartner) {
        return client.setAwayReturnDate(userId, new ApiRequests.AwayReturnSchedule(setId,
                DateTimeFormatter.ISO_INSTANT.format(returnDate), includePartner));
    }

    /**
     * Cancels a scheduled return from away mode.
     *
     * @param userId the user identifier
     * @param setId the household set identifier
     * @return a future completed when the schedule is removed
     */
    public CompletableFuture<Void> cancelAwayReturn(String userId, String setId) {
        return client.cancelAwayReturn(userId, setId);
    }

    /**
     * Starts the device priming procedure.
     *
     * @param deviceId the device identifier
     * @param userId the user to notify
     * @return a future completed when the priming task is created
     */
    public CompletableFuture<Void> primePod(String deviceId, String userId) {
        return client.primePod(deviceId, new ApiRequests.PrimingTask(userId));
    }

    /**
     * Turns on the user's pillow in smart mode.
     *
     * @param userId the user identifier
     * @return a future completed when the pillow is turned on
     */
    public CompletableFuture<Void> turnOnPillow(String userId) {
        return client.setPillowState(userId, new ApiRequests.TemperatureStateUpdate("smart"));
    }

    /**
     * Turns off the user's pillow.
     *
     * @param userId the user identifier
     * @return a future completed when the pillow is turned off
     */
    public CompletableFuture<Void> turnOffPillow(String userId) {
        return client.setPillowState(userId, new ApiRequests.TemperatureStateUpdate("off"));
    }

    /**
     * Sets the user's pillow heating level.
     *
     * @param userId the user identifier
     * @param level the requested heating level
     * @return a future completed when the pillow level is updated
     */
    public CompletableFuture<Void> setPillowLevel(String userId, int level) {
        return client.setPillowLevel(userId, new ApiRequests.TemperatureLevelUpdate(clampHeatingLevel(level)));
    }

    /**
     * Assigns a user to a device side.
     *
     * @param userId the user identifier
     * @param deviceId the device identifier
     * @param side the {@code solo}, {@code left}, or {@code right} side
     * @return a future completed when the assignment succeeds
     */
    public CompletableFuture<Void> setBedSide(String userId, String deviceId, String side) {
        BedSide bedSide = BedSide.fromString(side);
        if (bedSide == null) {
            return failedFuture("Invalid side parameter: " + side);
        }
        return setBedSide(userId, deviceId, bedSide);
    }

    /**
     * Assigns a user to a typed device side.
     *
     * @param userId the user identifier
     * @param deviceId the device identifier
     * @param side the side assignment
     * @return a future completed when the assignment succeeds
     */
    public CompletableFuture<Void> setBedSide(String userId, String deviceId, BedSide side) {
        return client.setBedSide(userId, new ApiRequests.CurrentDeviceUpdate(deviceId, side.apiValue()));
    }

    /**
     * Snoozes an alarm.
     *
     * @param userId the user identifier
     * @param alarmId the alarm identifier
     * @param minutes the snooze duration in minutes
     * @return a future completed when the alarm is snoozed
     */
    public CompletableFuture<Void> snoozeAlarm(String userId, String alarmId, int minutes) {
        return client.snoozeAlarm(userId, alarmId, new ApiRequests.SnoozeAlarm(minutes, false));
    }

    /**
     * Dismisses an alarm.
     *
     * @param userId the user identifier
     * @param alarmId the alarm identifier
     * @return a future completed when the alarm is dismissed
     */
    public CompletableFuture<Void> dismissAlarm(String userId, String alarmId) {
        return client.dismissAlarm(userId, alarmId, new ApiRequests.DismissAlarm(false));
    }

    /**
     * Sets the adjustable-base leg and torso angles.
     *
     * @param userId the user identifier
     * @param deviceId the device identifier
     * @param legAngle the requested leg angle
     * @param torsoAngle the requested torso angle
     * @return a future completed when the base angle is updated
     */
    public CompletableFuture<Void> setBaseAngle(String userId, String deviceId, int legAngle, int torsoAngle) {
        return client.setBaseAngle(userId, new ApiRequests.BaseAngle(deviceId, true, legAngle, torsoAngle, false));
    }

    /**
     * Applies an adjustable-base preset.
     *
     * @param userId the user identifier
     * @param deviceId the device identifier
     * @param preset the preset name
     * @return a future completed when the preset is applied
     */
    public CompletableFuture<Void> setBasePreset(String userId, String deviceId, String preset) {
        return client.setBasePreset(userId, new ApiRequests.BasePreset(deviceId, true, preset, false));
    }

    /**
     * Starts or pauses audio playback.
     *
     * @param userId the user identifier
     * @param playing whether the player should be playing
     * @return a future completed when the player state is updated
     */
    public CompletableFuture<Void> setPlayerState(String userId, boolean playing) {
        return client.setPlayerState(userId, new ApiRequests.PlayerState(playing ? "Playing" : "Paused"));
    }

    /**
     * Sets the audio-player volume.
     *
     * @param userId the user identifier
     * @param volumePercent the requested volume percentage
     * @return a future completed when the volume is updated
     */
    public CompletableFuture<Void> setPlayerVolume(String userId, int volumePercent) {
        return client.setPlayerVolume(userId, new ApiRequests.PlayerVolume(clampPercent(volumePercent)));
    }

    /**
     * Selects the current audio track.
     *
     * @param userId the user identifier
     * @param trackId the track identifier
     * @return a future completed when the track is selected
     */
    public CompletableFuture<Void> setPlayerTrack(String userId, String trackId) {
        return client.setPlayerTrack(userId, new ApiRequests.PlayerTrack(trackId, "ManualStop"));
    }

    private static CompletableFuture<Void> failedFuture(String message) {
        return CompletableFuture.failedFuture(new ApiException(message));
    }

    private static int clampHeatingLevel(int level) {
        return Math.max(HEATING_LEVEL_MIN, Math.min(HEATING_LEVEL_MAX, level));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
