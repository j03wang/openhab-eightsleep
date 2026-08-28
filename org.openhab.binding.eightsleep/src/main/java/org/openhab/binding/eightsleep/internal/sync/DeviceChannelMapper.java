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
package org.openhab.binding.eightsleep.internal.sync;

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.model.TemperatureState;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
import org.openhab.binding.eightsleep.internal.sync.LastWriteWins.CommandedValue;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.UnDefType;

@NonNullByDefault
final class DeviceChannelMapper {

    record Projection(@Nullable Double heatingLevel, @Nullable Double targetLevel) {
    }

    private DeviceChannelMapper() {
        throw new IllegalAccessError("Non-instantiable");
    }

    static Projection publish(DeviceState state, UserDataSnapshot userData, BedSide side, boolean fahrenheit,
            @Nullable CommandedValue sidePowerCommand, @Nullable CommandedValue awayModeCommand,
            @Nullable Double lastKnownTargetLevel, SyncCollector collector) {
        Double heatingLevel = state.heatingLevel(side);
        Double targetLevel = state.targetHeatingLevel(side);
        Boolean nowHeating = state.nowHeating(side);
        collector.targetLevelAbsent = targetLevel == null;

        if (heatingLevel != null) {
            collector.add(GROUP_DEVICE, CHANNEL_HEATING_LEVEL, new DecimalType(heatingLevel));
        }
        if (targetLevel != null) {
            double shownLevel = resolveShownTargetLevel(targetLevel, nowHeating, lastKnownTargetLevel);
            collector.lastKnownTargetLevel = shownLevel;
            collector.addTemperature(GROUP_CURRENT, CHANNEL_TARGET_TEMPERATURE, shownLevel, fahrenheit);
        } else {
            Double autopilot = autopilotTargetLevel(userData.temperature());
            if (autopilot != null) {
                collector.addTemperature(GROUP_CURRENT, CHANNEL_TARGET_TEMPERATURE, autopilot, fahrenheit);
            }
        }
        Integer remaining = state.heatingDuration(side);
        if (remaining != null) {
            collector.add(GROUP_DEVICE, CHANNEL_REMAINING_TIME, new QuantityType<>(remaining, Units.SECOND));
        }
        if (nowHeating != null && targetLevel != null) {
            collector.add(GROUP_DEVICE, CHANNEL_HEATING_STATE,
                    new StringType(deriveHeatingState(nowHeating, targetLevel)));
        }

        publishHubState(state, collector);
        publishAwayState(userData, awayModeCommand, collector);
        publishPowerState(userData, targetLevel, sidePowerCommand, collector);
        return new Projection(heatingLevel, targetLevel);
    }

    private static void publishHubState(DeviceState state, SyncCollector collector) {
        if (state.ledBrightnessLevel() != null) {
            collector.add(GROUP_DEVICE, CHANNEL_LED_BRIGHTNESS,
                    new DecimalType(state.ledBrightnessLevel().doubleValue()));
        }
        if (state.hasWater() != null) {
            collector.add(GROUP_DEVICE, CHANNEL_HAS_WATER, OnOffType.from(state.hasWater()));
        }
        if (state.needsPriming() != null) {
            collector.add(GROUP_DEVICE, CHANNEL_NEEDS_PRIMING, OnOffType.from(state.needsPriming()));
        }
        if (state.priming() != null) {
            collector.add(GROUP_DEVICE, CHANNEL_IS_PRIMING, OnOffType.from(state.priming()));
        }
        if (state.lastPrime() != null) {
            collector.add(GROUP_DEVICE, CHANNEL_LAST_PRIME, new DateTimeType(state.lastPrime()));
        }
    }

    private static void publishAwayState(UserDataSnapshot userData, @Nullable CommandedValue command,
            SyncCollector collector) {
        boolean known = userData.awayPolledAt().isAfter(Instant.EPOCH) || command != null;
        if (!known) {
            collector.add(GROUP_DEVICE, CHANNEL_AWAY_MODE, UnDefType.UNDEF);
            return;
        }
        Boolean resolved = LastWriteWins.resolveLatest(userData.awayObserved(), userData.awayPolledAt(), command);
        collector.add(GROUP_DEVICE, CHANNEL_AWAY_MODE, OnOffType.from(Boolean.TRUE.equals(resolved)));
        if (command != null && LastWriteWins.shouldRetireCommand(userData.awayObserved(), resolved)) {
            collector.retireAwayModeCommand = true;
        }
    }

    private static void publishPowerState(UserDataSnapshot userData, @Nullable Double targetLevel,
            @Nullable CommandedValue command, SyncCollector collector) {
        TemperatureState temperature = userData.temperature();
        String powerType = temperature != null ? temperature.stateType() : null;
        Boolean polledOn;
        if (powerType != null) {
            polledOn = !"off".equalsIgnoreCase(powerType);
        } else {
            polledOn = targetLevel != null ? targetLevel.doubleValue() != 0.0 : null;
        }
        Boolean resolved = LastWriteWins.resolveLatest(polledOn, userData.temperatureAt(), command);
        if (resolved != null) {
            collector.add(GROUP_DEVICE, CHANNEL_SIDE_POWER, OnOffType.from(resolved));
            collector.retireSidePowerCommand = LastWriteWins.shouldRetireCommand(polledOn, resolved);
        }
    }

    private static double resolveShownTargetLevel(double targetLevelRaw, @Nullable Boolean nowHeating,
            @Nullable Double previousShown) {
        boolean meaningful = targetLevelRaw != 0 || Boolean.TRUE.equals(nowHeating);
        return meaningful || previousShown == null ? targetLevelRaw : previousShown;
    }

    private static String deriveHeatingState(boolean nowHeating, double targetLevelRaw) {
        if (!nowHeating || targetLevelRaw == 0) {
            return "idle";
        }
        return targetLevelRaw > 0 ? "heating" : "cooling";
    }

    private static @Nullable Double autopilotTargetLevel(@Nullable TemperatureState temperature) {
        return temperature != null ? temperature.smartLevel("bedTimeLevel") : null;
    }
}
