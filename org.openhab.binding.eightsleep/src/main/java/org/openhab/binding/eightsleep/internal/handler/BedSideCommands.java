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


import org.openhab.binding.eightsleep.internal.api.model.Alarm;
import org.openhab.binding.eightsleep.internal.api.model.PillowData;
import org.openhab.binding.eightsleep.internal.api.model.PillowEntry;
import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.DEFAULT_SNOOZE_MINUTES;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.handler.LastWriteWins.CommandedValue;
import org.openhab.binding.eightsleep.internal.model.HeatingLevelConversion;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command execution for one bed side: turns an openHAB command into API calls.
 * Extracted from BedSideHandler so the handler only dispatches; all Eight Sleep
 * specific command behavior lives here.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class BedSideCommands {

    /** Max angles of the adjustable base sections. */
    public static final int HEAD_ANGLE_MAX = 45;
    public static final int FEET_ANGLE_MAX = 20;

    /** Everything a command needs; built fresh per handleCommand invocation. */
    public record Context(EightSleepApiClient client, AccountHandler account, String userId, String side,
            boolean soloBed, boolean fahrenheit, ConcurrentHashMap<String, CommandedValue> commanded,
            ConcurrentHashMap<String, CommandedValue> commandedAlarms, Runnable onApplied) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BedSideCommands.class);

    private BedSideCommands() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Parses a temperature command into a double value, or NaN when unsupported.
     */
    public static double parseTemperature(org.openhab.core.types.Command command) {
        if (command instanceof QuantityType<?> quantity) {
            javax.measure.Unit<?> unit = quantity.getUnit();
            if (unit.isCompatible(org.openhab.core.library.unit.SIUnits.CELSIUS)) {
                QuantityType<?> celsius = quantity.toInvertibleUnit(org.openhab.core.library.unit.SIUnits.CELSIUS);
                return celsius != null ? celsius.doubleValue() : quantity.doubleValue();
            }
            if (unit.isCompatible(ImperialUnits.FAHRENHEIT)) {
                QuantityType<?> fahr = quantity.toInvertibleUnit(ImperialUnits.FAHRENHEIT);
                return fahr != null ? fahr.doubleValue() : quantity.doubleValue();
            }
            return Double.NaN;
        }
        if (command instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        if (command instanceof org.openhab.core.library.types.StringType string) {
            try {
                return Double.parseDouble(string.toString());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * Computes the leg/torso angle pair for a single-axis base command: the moved
     * axis takes the commanded (clamped) angle, the other axis keeps its last known
     * angle so it does not move.
     */
    public static int[] mergeBaseAngles(boolean head, int angle, @Nullable Integer cachedLeg,
            @Nullable Integer cachedTorso) {
        int clamped = Math.max(0, Math.min(head ? HEAD_ANGLE_MAX : FEET_ANGLE_MAX, angle));
        int currentLeg = cachedLeg != null ? cachedLeg : 0;
        int currentTorso = cachedTorso != null ? cachedTorso : 0;
        return head ? new int[] { currentLeg, clamped } : new int[] { clamped, currentTorso };
    }

    /**
     * Resolves the alarm the alarm channels target and runs {@code action} with it;
     * logs and returns silently when no actionable alarm exists.
     */
    private static void withTargetAlarm(Context ctx, String action, java.util.function.Consumer<Alarm> consumer) {
        UserDataCache userData = ctx.account().getUserData(ctx.userId());
        Alarm alarm = userData != null ? AlarmSelector.findTargetAlarm(userData, Instant.now()) : null;
        if (alarm == null || alarm.id == null) {
            LOGGER.debug("No upcoming alarm to {}", action);
            return;
        }
        consumer.accept(alarm);
    }

    /** Completes a command: refresh on success, log on expected failures. */
    private static void apply(Context ctx, java.util.concurrent.CompletionStage<?> stage) {
        stage.thenRun(ctx.onApplied()).exceptionally(BedSideCommands::logFailure);
    }

    private static Void logFailure(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        LOGGER.warn("Eight Sleep command failed: {}", cause.getMessage());
        return null;
    }

    // ==================== individual commands ====================

    public static void targetTemperature(Context ctx, org.openhab.core.types.Command command) throws ApiException {
        double temperature = parseTemperature(command);
        if (Double.isNaN(temperature)) {
            return;
        }
        boolean fahrenheit = ctx.fahrenheit();
        if (command instanceof QuantityType<?> quantity) {
            fahrenheit = quantity.getUnit().isCompatible(ImperialUnits.FAHRENHEIT);
        }
        int level = HeatingLevelConversion.temperatureToLevel(temperature, fahrenheit);
        apply(ctx, ctx.client().setHeatingLevel(ctx.userId(), level, 0));
    }

    public static void sidePower(Context ctx, org.openhab.core.types.Command command) throws ApiException {
        boolean turnOn = command == OnOffType.ON;
        // Optimistic feedback + timestamped command: the sync loop does last-write-wins
        // against the polled payload, so no stale cycle can flip the switch back.
        ctx.commanded().put("sidePower", new CommandedValue(Instant.now(), turnOn));
        apply(ctx, turnOn ? ctx.client().turnOnSide(ctx.userId()) : ctx.client().turnOffSide(ctx.userId()));
    }

    public static void baseAngle(Context ctx, org.openhab.core.types.Command command, boolean head)
            throws ApiException {
        int angle;
        if (command instanceof QuantityType<?> quantity) {
            angle = (int) Math.round(quantity.doubleValue());
        } else if (command instanceof DecimalType decimal) {
            angle = decimal.intValue();
        } else {
            LOGGER.warn("Unsupported command type {} for base angle", command.getClass().getSimpleName());
            return;
        }

        Integer cachedLeg = null;
        Integer cachedTorso = null;
        UserDataCache data = ctx.account().getUserData(ctx.userId());
        var baseSide = data != null ? data.getBaseSide(ctx.side()) : null;
        if (baseSide != null && baseSide.leg != null && baseSide.leg.currentAngle != null) {
            cachedLeg = baseSide.leg.currentAngle;
        }
        if (baseSide != null && baseSide.torso != null && baseSide.torso.currentAngle != null) {
            cachedTorso = baseSide.torso.currentAngle;
        }
        int[] angles = mergeBaseAngles(head, angle, cachedLeg, cachedTorso);

        String devId = ctx.account().getDeviceId();
        if (devId == null) {
            LOGGER.debug("No device id; cannot set base angle");
            return;
        }
        apply(ctx, ctx.client().setBaseAngle(ctx.userId(), devId, angles[0], angles[1]));
    }

    public static void basePreset(Context ctx, org.openhab.core.types.Command command) {
        String devId = ctx.account().getDeviceId();
        if (devId == null) {
            LOGGER.debug("No device id; cannot set base preset");
            return;
        }
        apply(ctx, ctx.client().setBasePreset(ctx.userId(), devId, command.toString().toLowerCase()));
    }

    public static void pillowPower(Context ctx, org.openhab.core.types.Command command) {
        var future = command == OnOffType.ON ? ctx.client().turnOnPillow(ctx.userId())
                : command == OnOffType.OFF ? ctx.client().turnOffPillow(ctx.userId()) : null;
        if (future != null) {
            apply(ctx, future);
        }
    }

    public static void pillowTargetTemperature(Context ctx, org.openhab.core.types.Command command) {
        double temperature = parseTemperature(command);
        if (Double.isNaN(temperature)) {
            return;
        }
        int level = HeatingLevelConversion.temperatureToLevel(temperature, ctx.fahrenheit());

        UserDataCache data = ctx.account().getUserData(ctx.userId());
        PillowData pillowData = data != null ? data.pillowData : null;
        PillowEntry pillow = pillowData != null ? pillowData.findPillow(ctx.side()) : null;
        // Writing a level to an off pillow is silently ignored by the API: power on first
        var future = pillow != null && !pillow.isOn()
                ? ctx.client().turnOnPillow(ctx.userId()).thenCompose(v -> ctx.client().setPillowLevel(ctx.userId(),
                        level))
                : ctx.client().setPillowLevel(ctx.userId(), level);
        apply(ctx, future);
    }

    public static void alarmEnabled(Context ctx, org.openhab.core.types.Command command) {
        withTargetAlarm(ctx, "toggle", alarm -> {
            boolean enable = command == OnOffType.ON;
            // Optimistic feedback + timestamped command (LWW vs the polled list).
            ctx.commandedAlarms().put(alarm.id, new CommandedValue(Instant.now(), enable));
            apply(ctx, ctx.client().setAlarmEnabled(ctx.userId(), alarm, enable));
        });
    }

    public static void alarmTime(Context ctx, org.openhab.core.types.Command command) {
        Instant newTime;
        if (command instanceof org.openhab.core.library.types.DateTimeType dateTime) {
            newTime = dateTime.getInstant();
        } else if (command instanceof org.openhab.core.library.types.StringType string) {
            java.time.@Nullable LocalTime parsed = org.openhab.binding.eightsleep.internal.model.TrendParser
                    .parseTimeOfDay(string.toString());
            if (parsed == null) {
                LOGGER.warn("Cannot parse '{}' as an alarm time", string);
                return;
            }
            newTime = parsed.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant();
        } else {
            LOGGER.warn("Unsupported command type {} for alarm time", command.getClass().getSimpleName());
            return;
        }

        withTargetAlarm(ctx, "reschedule", alarm -> {
            String timeOfDay = LocalDateTime.ofInstant(newTime, ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            apply(ctx, ctx.client().setAlarmTime(ctx.userId(), alarm, timeOfDay));
        });
    }

    public static void dismissAlarm(Context ctx) {
        withTargetAlarm(ctx, "dismiss", alarm -> apply(ctx,
                ctx.client().dismissAlarm(ctx.userId(), alarm.id)));
    }

    public static void snoozeAlarm(Context ctx) {
        withTargetAlarm(ctx, "snooze", alarm -> apply(ctx,
                ctx.client().snoozeAlarm(ctx.userId(), alarm.id, DEFAULT_SNOOZE_MINUTES)));
    }

    public static void awayMode(Context ctx, org.openhab.core.types.Command command) {
        String devId = ctx.account().getDeviceId();
        if (devId == null) {
            LOGGER.warn("No device id known; cannot change away mode");
            return;
        }
        boolean start = command == OnOffType.ON;
        // Instant feedback; the away poll (verified side-slot rule) confirms/corrects.
        ctx.account().setLastKnownAwayMode(ctx.userId(), start);
        // side is the configured physical side; the client skips re-assertion when it
        // is not a genuine left/right (solo beds must not be rewritten).
        apply(ctx, ctx.client().setAwayMode(ctx.userId(), devId, ctx.soloBed() ? "solo" : ctx.side(),
                start ? "start" : "end"));
    }

    public static void primePod(Context ctx) {
        String devId = ctx.account().getDeviceId();
        if (devId != null) {
            apply(ctx, ctx.client().primePod(devId, ctx.userId()));
        } else {
            LOGGER.warn("No device id known; cannot start priming");
        }
    }

    public static void ledBrightness(Context ctx, org.openhab.core.types.Command command) {
        String devId = ctx.account().getDeviceId();
        int level = command instanceof DecimalType decimal ? decimal.intValue()
                : command instanceof QuantityType<?> quantity ? (int) Math.round(quantity.doubleValue())
                : -1;
        if (devId != null && level >= 0) {
            apply(ctx, ctx.client().setLedBrightness(devId, level));
        } else {
            LOGGER.warn("Cannot apply LED brightness from command {}", command);
        }
    }
}
