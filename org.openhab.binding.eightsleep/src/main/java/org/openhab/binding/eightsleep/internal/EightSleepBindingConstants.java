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
package org.openhab.binding.eightsleep.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link EightSleepBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class EightSleepBindingConstants {

    /** Constructor */
    private EightSleepBindingConstants() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public static final String BINDING_ID = "eightsleep";

    // thing type ids
    private static final String THING_TYPE_ACCOUNT = "account";
    private static final String THING_TYPE_BED_SIDE = "bedSide";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_UID_ACCOUNT = new ThingTypeUID(BINDING_ID, THING_TYPE_ACCOUNT);
    public static final ThingTypeUID THING_TYPE_UID_BED_SIDE = new ThingTypeUID(BINDING_ID, THING_TYPE_BED_SIDE);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_UID_ACCOUNT,
            THING_TYPE_UID_BED_SIDE);

    // bridge config properties
    public static final String CONFIG_USERNAME = "username";
    public static final String CONFIG_PASSWORD = "password";
    public static final String CONFIG_CLIENT_ID = "clientId";
    public static final String CONFIG_CLIENT_SECRET = "clientSecret";

    // bed side thing config properties
    public static final String CONFIG_USER_ID = "userId";

    // channel group ids
    public static final String GROUP_CURRENT = "current";
    public static final String GROUP_LAST_SLEEP = "lastSleep";
    public static final String GROUP_DEVICE = "device";
    public static final String GROUP_BASE = "base";

    // current / last sleep channels
    public static final String CHANNEL_BED_TEMPERATURE = "bedTemperature";
    public static final String CHANNEL_TARGET_TEMPERATURE = "targetTemperature";
    public static final String CHANNEL_ROOM_TEMPERATURE = "roomTemperature";
    public static final String CHANNEL_HEART_RATE = "heartRate";
    public static final String CHANNEL_RESPIRATORY_RATE = "respiratoryRate";
    public static final String CHANNEL_HRV = "hrv";
    public static final String CHANNEL_BREATH_RATE = "breathRate";
    public static final String CHANNEL_SLEEP_SCORE = "sleepScore";
    public static final String CHANNEL_FITNESS_SCORE = "fitnessScore";
    public static final String CHANNEL_QUALITY_SCORE = "qualityScore";
    public static final String CHANNEL_ROUTINE_SCORE = "routineScore";
    public static final String CHANNEL_TIME_SLEPT = "timeSlept";
    public static final String CHANNEL_SLEEP_STAGE = "sleepStage";
    public static final String CHANNEL_TOSS_TURNS = "tossesTurns";
    public static final String CHANNEL_LIGHT_SLEEP = "lightSleepDuration";
    public static final String CHANNEL_DEEP_SLEEP = "deepSleepDuration";
    public static final String CHANNEL_REM_SLEEP = "remSleepDuration";
    public static final String CHANNEL_AWAKE_DURATION = "awakeDuration";
    public static final String CHANNEL_SESSION_START = "sessionStart";
    public static final String CHANNEL_SESSION_END = "sessionEnd";
    public static final String CHANNEL_NEXT_ALARM = "nextAlarm";

    // device level channels
    public static final String CHANNEL_HEATING_LEVEL = "heatingLevel";
    public static final String CHANNEL_HEATING_STATE = "heatingState";
    public static final String CHANNEL_REMAINING_TIME = "heatingRemainingTime";

    // base channels
    public static final String CHANNEL_BASE_PRESET = "basePreset";
    public static final String CHANNEL_HEAD_ANGLE = "headAngle";
    public static final String CHANNEL_FEET_ANGLE = "feetAngle";
    public static final String CHANNEL_SNORE_MITIGATION = "snoreMitigation";
    public static final String CHANNEL_BED_PRESENCE = "bedPresence";

    // pillow channels (Pod 5 accessory)
    public static final String GROUP_PILLOW = "pillow";
    public static final String CHANNEL_PILLOW_POWER = "pillowPower";
    public static final String CHANNEL_PILLOW_TARGET_TEMPERATURE = "pillowTargetTemperature";
    public static final String CHANNEL_PILLOW_HEATING_LEVEL = "pillowHeatingLevel";

    // hub channels
    public static final String CHANNEL_LED_BRIGHTNESS = "ledBrightness";
    public static final String CHANNEL_HAS_WATER = "hasWater";
    public static final String CHANNEL_NEEDS_PRIMING = "needsPriming";
    public static final String CHANNEL_IS_PRIMING = "isPriming";
    public static final String CHANNEL_LAST_PRIME = "lastPrime";

    // alarm channels
    public static final String CHANNEL_ALARM_ENABLED = "alarmEnabled";
    public static final String CHANNEL_ALARM_TIME = "alarmTime";
    public static final String CHANNEL_DISMISS_ALARM = "dismissAlarm";
    public static final String CHANNEL_SNOOZE_ALARM = "snoozeAlarm";

    // away mode / priming
    public static final String CHANNEL_AWAY_MODE = "awayMode";
    public static final String CHANNEL_PRIME = "primeTrigger";

    /** Default snooze duration in minutes (upstream default is 9). */
    public static final int DEFAULT_SNOOZE_MINUTES = 9;

    // switch/control channels
    public static final String CHANNEL_SIDE_POWER = "sidePower";

    // commands for heating state channel
    public static final String HEATING_STATE_OFF = "off";
    public static final String HEATING_STATE_SMART = "smart";

    // sleep stage options
    public static final String SLEEP_STAGE_CURRENT = "current";
    public static final String SLEEP_STAGE_BEDTIME = "bedTimeLevel";
    public static final String SLEEP_STAGE_INITIAL = "initialSleepLevel";
    public static final String SLEEP_STAGE_FINAL = "finalSleepLevel";
}
