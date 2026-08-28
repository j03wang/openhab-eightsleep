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
package org.openhab.binding.eightsleep.internal.api.dto;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * Wire contracts for responses consumed from the Eight Sleep API.
 *
 * @author Joe Wang - Initial contribution
 */
public final class ApiResponses {

    private ApiResponses() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public static class DeviceEnvelope {
        public @Nullable Device result;
    }

    public static class Device {
        public @Nullable Double leftHeatingLevel;
        public @Nullable Double rightHeatingLevel;
        public @Nullable Double leftTargetHeatingLevel;
        public @Nullable Double rightTargetHeatingLevel;
        public @Nullable Boolean leftNowHeating;
        public @Nullable Boolean rightNowHeating;
        public @Nullable Integer leftHeatingDuration;
        public @Nullable Integer rightHeatingDuration;
        public @Nullable Boolean hasWater;
        public @Nullable Boolean needsPriming;
        public @Nullable Boolean priming;
        public @Nullable String lastPrime;
        public @Nullable String modelString;
        public @Nullable String firmwareVersion;
        public @Nullable Double ledBrightnessLevel;
        public @Nullable SensorInfo sensorInfo;
        public @Nullable Map<String, Object> presenceInformation;
        public @Nullable List<String> features;
    }

    public static class SensorInfo {
        public @Nullable String hwRevision;
    }

    public static class DeviceUsers {
        public @Nullable String leftUserId;
        public @Nullable String rightUserId;
        public @Nullable Map<String, String> awaySides;
    }

    public static class DeviceUsersEnvelope {
        public @Nullable DeviceUsers result;
    }

    public static class UserProfileEnvelope {
        public @Nullable UserProfile user;
    }

    public static class UserProfile {
        public @Nullable CurrentDevice currentDevice;
    }

    public static class CurrentDevice {
        public @Nullable String id;
        public @Nullable String side;
    }

    public static class MeEnvelope {
        public @Nullable MeUser user;
    }

    public static class MeUser {
        public @Nullable String userId;
    }

    public static class HouseholdSummary {
        public @Nullable List<Household> households;
    }

    public static class Household {
        public @Nullable List<DeviceSet> sets;
    }

    public static class DeviceSet {
        public @Nullable List<HouseholdDevice> devices;
    }

    public static class HouseholdDevice {
        public @Nullable String deviceId;
        public @Nullable String deviceName;
    }

    public static class Trends {
        public @Nullable List<TrendDay> days;
    }

    public static class TrendDay {
        public @Nullable String day;
        public @Nullable Double score;
        public @Nullable Double tnt;
        public @Nullable String presenceStart;
        public @Nullable String presenceEnd;
        public @Nullable Double lightDuration;
        public @Nullable Double deepDuration;
        public @Nullable Double remDuration;
        public @Nullable Double sleepDuration;
        public @Nullable Double presenceDuration;
        public @Nullable Score sleepQualityScore;
        public @Nullable Score sleepRoutineScore;
        public @Nullable List<TrendSession> sessions;
    }

    public static class Score {
        public @Nullable Double total;
        public @Nullable CurrentValue hrv;
        public @Nullable CurrentValue respiratoryRate;
    }

    public static class CurrentValue {
        public @Nullable Double current;
    }

    public static class TrendSession {
        public @Nullable String sleepStart;
        public @Nullable String sleepEnd;
        public @Nullable List<Stage> stages;
        public @Nullable Map<String, List<List<JsonElement>>> timeseries;
    }

    public static class Stage {
        public @Nullable String stage;
        public @Nullable Double duration;
    }

    public static class Alarms {
        public @Nullable List<Alarm> alarms;
    }

    public static class Alarm {
        public @Nullable String id;
        public @Nullable String time;
        public @Nullable Boolean enabled;
        public @Nullable AlarmRepeat repeat;
        public @Nullable Map<String, Object> thermal;
        public @Nullable Map<String, Object> vibration;
        public @Nullable Map<String, Object> audio;
        public @Nullable Map<String, Object> smart;
        public @Nullable List<String> tags;
        public @Nullable Boolean skipNext;
        public @Nullable Boolean snoozing;
        public @Nullable String nextTimestamp;
    }

    public static class AlarmRepeat {
        public @Nullable Boolean enabled;
        public @Nullable Map<String, Boolean> weekDays;
    }

    public static class Base {
        public @Nullable BaseSide left;
        public @Nullable BaseSide right;
    }

    public static class BaseSide {
        public @Nullable Preset preset;
        public @Nullable Angle leg;
        public @Nullable Angle torso;
        public @Nullable Boolean inSnoreMitigation;
    }

    public static class Preset {
        public @Nullable String name;
    }

    public static class Angle {
        public @Nullable Integer currentAngle;
    }

    public static class Player {
        public @Nullable String state;
        public @Nullable Integer volume;
        public @Nullable Track currentTrack;
        public @Nullable HardwareInfo hardwareInfo;
    }

    public static class Track {
        public @Nullable String id;
        public @Nullable String name;
        public @Nullable String categoryId;
        public @Nullable Double currentPosition;
        public @Nullable Double trackDuration;
    }

    public static class HardwareInfo {
        public @Nullable String sku;
        public @Nullable String hardwareVersion;
        public @Nullable String softwareVersion;
    }

    public static class Temperature {
        public @Nullable Double currentLevel;
        public @Nullable CurrentState currentState;
        public @Nullable Map<String, Integer> smart;
    }

    public static class TemperatureAll {
        public @Nullable List<TemperatureDevice> devices;
    }

    public static class TemperatureDevice {
        public @Nullable TemperatureDeviceInfo device;
        public @Nullable Double currentLevel;
        public @Nullable CurrentState currentState;
    }

    public static class TemperatureDeviceInfo {
        public @Nullable String specialization;
        public @Nullable String side;
        public @Nullable String deviceId;
    }

    public static class CurrentState {
        public @Nullable String type;
    }
}
