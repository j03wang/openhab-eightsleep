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

/**
 * Wire contracts for requests sent to the Eight Sleep API.
 *
 * @author Joe Wang - Initial contribution
 */
public final class ApiRequests {

    private ApiRequests() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public interface Request {
    }

    public record LedBrightnessUpdate(int ledBrightnessLevel) implements Request {
    }

    public record TemperatureLevelUpdate(int currentLevel) implements Request {
    }

    public record TemperatureTimerUpdate(TimeBased timeBased) implements Request {
        public TemperatureTimerUpdate(int level, int durationSeconds) {
            this(new TimeBased(level, durationSeconds));
        }

        public record TimeBased(int level, int durationSeconds) {
        }
    }

    public record TemperatureStateUpdate(CurrentState currentState) implements Request {
        public TemperatureStateUpdate(String type) {
            this(new CurrentState(type));
        }

        public record CurrentState(String type) {
        }
    }

    public record SmartTemperatureUpdate(Map<String, Integer> smart) implements Request {
    }

    public record AwayModeHeaders(@Nullable String returnDate, boolean includePartner) implements Request {
    }

    public record AwayReturnSchedule(Schedule schedule, boolean includePartner) implements Request {
        public AwayReturnSchedule(String setId, String dateToReturn, boolean includePartner) {
            this(new Schedule(setId, dateToReturn), includePartner);
        }

        public record Schedule(String setId, String dateToReturn) {
        }
    }

    public record PrimingTask(Notifications notifications) implements Request {
        public PrimingTask(String userId) {
            this(new Notifications(List.of(userId), "fill_pod"));
        }

        public record Notifications(List<String> users, String meta) {
        }
    }

    public record CurrentDeviceUpdate(String id, String side) implements Request {
    }

    public record SnoozeAlarm(int snoozeMinutes, boolean ignoreDeviceErrors) implements Request {
    }

    public record DismissAlarm(boolean ignoreDeviceErrors) implements Request {
    }

    public record AlarmUpdate(@Nullable String id, @Nullable String time, @Nullable Boolean enabled, Object repeat,
            Object thermal, Object vibration, Object audio, Object smart, @Nullable List<String> tags, Boolean skipNext,
            Boolean snoozing) implements Request {
    }

    public record BaseAngle(String deviceId, boolean deviceOnline, int legAngle, int torsoAngle,
            boolean enableOfflineMode) implements Request {
    }

    public record BasePreset(String deviceId, boolean deviceOnline, String preset,
            boolean enableOfflineMode) implements Request {
    }

    public record PlayerState(String state) implements Request {
    }

    public record PlayerVolume(int volume) implements Request {
    }

    public record PlayerTrack(String id, String stopCriteria) implements Request {
    }
}
