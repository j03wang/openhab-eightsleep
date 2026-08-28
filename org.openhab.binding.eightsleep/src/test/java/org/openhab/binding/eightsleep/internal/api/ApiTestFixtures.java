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

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.dto.ApiResponses;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BaseState;
import org.openhab.binding.eightsleep.internal.model.DeviceAssignments;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.model.PillowState;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.openhab.binding.eightsleep.internal.model.TemperatureState;
import org.openhab.binding.eightsleep.internal.model.TrendData;
import org.openhab.binding.eightsleep.internal.model.UserProfile;

public final class ApiTestFixtures {

    private ApiTestFixtures() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public static DeviceState parseDeviceData(String body) {
        ApiResponses.DeviceEnvelope envelope = new ApiJsonCodec().fromJson(body, ApiResponses.DeviceEnvelope.class);
        return EightSleepApiMapper.toDeviceState(envelope != null ? envelope.result : null);
    }

    public static UserProfile parseUserProfile(String userId, String body) {
        return EightSleepApiMapper.toUserProfile(userId,
                new ApiJsonCodec().fromJson(body, ApiResponses.UserProfileEnvelope.class));
    }

    public static DeviceAssignments parseUserIdsForDevice(String body) {
        ApiResponses.DeviceUsersEnvelope envelope = new ApiJsonCodec().fromJson(body,
                ApiResponses.DeviceUsersEnvelope.class);
        return EightSleepApiMapper.toDeviceAssignments(envelope != null ? envelope.result : null);
    }

    public static String parseCurrentUserId(String body) {
        return EightSleepApiMapper.toCurrentUserId(new ApiJsonCodec().fromJson(body, ApiResponses.MeEnvelope.class));
    }

    public static Map<String, String> parseHouseholdDevices(String body) {
        return EightSleepApiMapper
                .toHouseholdDevices(new ApiJsonCodec().fromJson(body, ApiResponses.HouseholdSummary.class));
    }

    public static TrendData parseTrendDays(String body) {
        return EightSleepApiMapper.toTrendData(new ApiJsonCodec().fromJson(body, ApiResponses.Trends.class));
    }

    public static List<Alarm> parseAlarms(String body) {
        return EightSleepApiMapper.toAlarms(new ApiJsonCodec().fromJson(body, ApiResponses.Alarms.class));
    }

    public static BaseState parseBaseData(String body) {
        return EightSleepApiMapper.toBaseState(new ApiJsonCodec().fromJson(body, ApiResponses.Base.class));
    }

    public static PlayerState parsePlayerState(String body) {
        return EightSleepApiMapper.toPlayerState(new ApiJsonCodec().fromJson(body, ApiResponses.Player.class));
    }

    public static TemperatureState parseTemperature(String body) {
        return EightSleepApiMapper
                .toTemperatureState(new ApiJsonCodec().fromJson(body, ApiResponses.Temperature.class));
    }

    public static PillowState parsePillowData(String body) {
        return EightSleepApiMapper.toPillowState(new ApiJsonCodec().fromJson(body, ApiResponses.TemperatureAll.class));
    }

    public static String buildAlarmUpdateBody(Alarm alarm, @Nullable Boolean enabled, @Nullable String timeOverride) {
        return new ApiJsonCodec().toJson(EightSleepApiMapper.toAlarmUpdate(alarm, enabled, timeOverride));
    }
}
