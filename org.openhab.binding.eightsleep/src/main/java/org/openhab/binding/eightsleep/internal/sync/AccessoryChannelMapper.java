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
import static org.openhab.binding.eightsleep.internal.sync.SyncChannels.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.BaseState;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.PillowEntry;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;

@NonNullByDefault
final class AccessoryChannelMapper {

    private AccessoryChannelMapper() {
        throw new IllegalAccessError("Non-instantiable");
    }

    static void publish(UserDataSnapshot userData, BedSide side, boolean fahrenheit, SyncCollector collector) {
        publishBase(userData.baseSide(side), collector);
        PillowEntry pillow = userData.pillowState().findPillow(side);
        if (pillow != null) {
            Integer level = pillow.level();
            if (level != null) {
                addTemperature(collector, GROUP_PILLOW, CHANNEL_PILLOW_TARGET_TEMPERATURE, level, fahrenheit);
                add(collector, GROUP_PILLOW, CHANNEL_PILLOW_HEATING_LEVEL, new DecimalType(level));
            }
            add(collector, GROUP_PILLOW, CHANNEL_PILLOW_POWER, OnOffType.from(pillow.isOn()));
        }
    }

    private static void publishBase(BaseState.@Nullable SideState state, SyncCollector collector) {
        if (state == null) {
            return;
        }
        if (state.presetName() != null) {
            add(collector, GROUP_BASE, CHANNEL_BASE_PRESET, new StringType(state.presetName()));
        }
        if (state.torsoAngle() != null) {
            add(collector, GROUP_BASE, CHANNEL_HEAD_ANGLE, new QuantityType<>(state.torsoAngle(), Units.DEGREE_ANGLE));
        }
        if (state.legAngle() != null) {
            add(collector, GROUP_BASE, CHANNEL_FEET_ANGLE, new QuantityType<>(state.legAngle(), Units.DEGREE_ANGLE));
        }
        if (state.inSnoreMitigation() != null) {
            add(collector, GROUP_BASE, CHANNEL_SNORE_MITIGATION, OnOffType.from(state.inSnoreMitigation()));
        }
    }
}
