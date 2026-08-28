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
package org.openhab.binding.eightsleep.internal.command;

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes openHAB channel commands to the corresponding Eight Sleep command operation.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class BedSideCommandDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedSideCommandDispatcher.class);

    private BedSideCommandDispatcher() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Dispatches one command.
     *
     * @param channelUID the commanded channel
     * @param command the openHAB command
     * @param context the values available to command operations
     * @throws ApiException if synchronous request validation fails
     */
    public static void dispatch(ChannelUID channelUID, Command command, BedSideCommands.Context context)
            throws ApiException {
        switch (channelUID.getIdWithoutGroup()) {
            case CHANNEL_TARGET_TEMPERATURE -> BedSideCommands.targetTemperature(context, command);
            case CHANNEL_SIDE_POWER -> BedSideCommands.sidePower(context, command);
            case CHANNEL_HEAD_ANGLE -> BedSideCommands.baseAngle(context, command, true);
            case CHANNEL_FEET_ANGLE -> BedSideCommands.baseAngle(context, command, false);
            case CHANNEL_BASE_PRESET -> BedSideCommands.basePreset(context, command);
            case CHANNEL_PILLOW_POWER -> BedSideCommands.pillowPower(context, command);
            case CHANNEL_PILLOW_TARGET_TEMPERATURE -> BedSideCommands.pillowTargetTemperature(context, command);
            case CHANNEL_ALARM_ENABLED -> BedSideCommands.alarmEnabled(context, command);
            case CHANNEL_ALARM_TIME -> BedSideCommands.alarmTime(context, command);
            case CHANNEL_DISMISS_ALARM -> {
                if (command == OnOffType.ON) {
                    BedSideCommands.dismissAlarm(context);
                }
            }
            case CHANNEL_SNOOZE_ALARM -> {
                if (command == OnOffType.ON) {
                    BedSideCommands.snoozeAlarm(context);
                }
            }
            case CHANNEL_AWAY_MODE -> BedSideCommands.awayMode(context, command);
            case CHANNEL_PRIME -> {
                if (command == OnOffType.ON) {
                    BedSideCommands.primePod(context);
                }
            }
            case CHANNEL_LED_BRIGHTNESS -> BedSideCommands.ledBrightness(context, command);
            case CHANNEL_SNORE_MITIGATION -> LOGGER.debug("Snore mitigation is read-only");
            default -> LOGGER.warn("Unsupported channel {} for command {}", channelUID, command);
        }
    }
}
