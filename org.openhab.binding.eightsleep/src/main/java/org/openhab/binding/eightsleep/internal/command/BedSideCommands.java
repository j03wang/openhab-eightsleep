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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.ApiValueParser;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.PillowState;
import org.openhab.binding.eightsleep.internal.model.PillowState.PillowEntry;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
import org.openhab.binding.eightsleep.internal.temperature.HeatingLevelConversion;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateful command dispatcher for one bed side. It routes openHAB commands to
 * API operations and uses an injected clock for deterministic alarm selection.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class BedSideCommands {

    private static final int HEAD_ANGLE_MAX = 45;
    private static final int FEET_ANGLE_MAX = 20;

    /** Everything a command needs; built fresh per handleCommand invocation. */
    public record Context(EightSleepService service, String userId, BedSide side, boolean fahrenheit, ZoneId zone,
            @Nullable String deviceId, @Nullable UserDataSnapshot userData, CommandState commandState,
            Runnable onApplied) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BedSideCommands.class);
    private final Clock clock;

    /**
     * Creates a command dispatcher.
     *
     * @param clock the clock used when selecting the actionable alarm
     */
    public BedSideCommands(Clock clock) {
        this.clock = clock;
    }

    /**
     * Dispatches one command to its corresponding Eight Sleep operation.
     *
     * @param channelUID the commanded channel
     * @param command the openHAB command
     * @param context the values available to command operations
     * @throws ApiException if synchronous request validation fails
     */
    public void dispatch(ChannelUID channelUID, Command command, Context context) throws ApiException {
        switch (channelUID.getIdWithoutGroup()) {
            case CHANNEL_TARGET_TEMPERATURE -> targetTemperature(context, command);
            case CHANNEL_SIDE_POWER -> sidePower(context, command);
            case CHANNEL_HEAD_ANGLE -> baseAngle(context, command, true);
            case CHANNEL_FEET_ANGLE -> baseAngle(context, command, false);
            case CHANNEL_BASE_PRESET -> basePreset(context, command);
            case CHANNEL_PILLOW_POWER -> pillowPower(context, command);
            case CHANNEL_PILLOW_TARGET_TEMPERATURE -> pillowTargetTemperature(context, command);
            case CHANNEL_ALARM_ENABLED -> alarmEnabled(context, command);
            case CHANNEL_ALARM_TIME -> alarmTime(context, command);
            case CHANNEL_DISMISS_ALARM -> {
                if (command == OnOffType.ON) {
                    dismissAlarm(context);
                }
            }
            case CHANNEL_SNOOZE_ALARM -> {
                if (command == OnOffType.ON) {
                    snoozeAlarm(context);
                }
            }
            case CHANNEL_AWAY_MODE -> awayMode(context, command);
            case CHANNEL_PRIME -> {
                if (command == OnOffType.ON) {
                    primePod(context);
                }
            }
            case CHANNEL_LED_BRIGHTNESS -> ledBrightness(context, command);
            case CHANNEL_SNORE_MITIGATION -> LOGGER.debug("Snore mitigation is read-only");
            default -> LOGGER.warn("Unsupported channel {} for command {}", channelUID, command);
        }
    }

    private static double parseTemperature(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            javax.measure.Unit<?> unit = quantity.getUnit();
            if (unit.equals(ImperialUnits.FAHRENHEIT)) {
                QuantityType<?> fahr = quantity.toInvertibleUnit(ImperialUnits.FAHRENHEIT);
                return fahr != null ? fahr.doubleValue() : quantity.doubleValue();
            }
            if (unit.isCompatible(org.openhab.core.library.unit.SIUnits.CELSIUS)) {
                QuantityType<?> celsius = quantity.toInvertibleUnit(org.openhab.core.library.unit.SIUnits.CELSIUS);
                return celsius != null ? celsius.doubleValue() : quantity.doubleValue();
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
    private static int[] mergeBaseAngles(boolean head, int angle, @Nullable Integer cachedLeg,
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
    private void withTargetAlarm(Context ctx, String action, java.util.function.Consumer<Alarm> consumer) {
        UserDataSnapshot userData = ctx.userData();
        Alarm alarm = userData != null ? AlarmSelector.findTargetAlarm(userData.alarms(), clock.instant(), ctx.zone())
                : null;
        if (alarm == null || alarm.id() == null) {
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

    private static void targetTemperature(Context ctx, Command command) throws ApiException {
        double temperature = parseTemperature(command);
        if (Double.isNaN(temperature)) {
            return;
        }
        boolean fahrenheit = commandUsesFahrenheit(command, ctx.fahrenheit());
        int level = HeatingLevelConversion.temperatureToLevel(temperature, fahrenheit);
        apply(ctx, ctx.service().setHeatingLevel(ctx.userId(), level, 0));
    }

    private static void sidePower(Context ctx, Command command) throws ApiException {
        boolean turnOn = command == OnOffType.ON;
        // Optimistic feedback + timestamped command: the sync loop does last-write-wins
        // against the polled payload, so no stale cycle can flip the switch back.
        ctx.commandState().recordChannel(CHANNEL_SIDE_POWER, turnOn);
        apply(ctx, turnOn ? ctx.service().turnOnSide(ctx.userId()) : ctx.service().turnOffSide(ctx.userId()));
    }

    private static void baseAngle(Context ctx, Command command, boolean head) throws ApiException {
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
        UserDataSnapshot data = ctx.userData();
        var baseSide = data != null ? data.baseSide(ctx.side()) : null;
        if (baseSide != null) {
            cachedLeg = baseSide.legAngle();
            cachedTorso = baseSide.torsoAngle();
        }
        int[] angles = mergeBaseAngles(head, angle, cachedLeg, cachedTorso);

        String devId = ctx.deviceId();
        if (devId == null) {
            LOGGER.debug("No device id; cannot set base angle");
            return;
        }
        apply(ctx, ctx.service().setBaseAngle(ctx.userId(), devId, angles[0], angles[1]));
    }

    private static void basePreset(Context ctx, Command command) {
        String devId = ctx.deviceId();
        if (devId == null) {
            LOGGER.debug("No device id; cannot set base preset");
            return;
        }
        apply(ctx, ctx.service().setBasePreset(ctx.userId(), devId, command.toString().toLowerCase()));
    }

    private static void pillowPower(Context ctx, Command command) {
        var future = command == OnOffType.ON ? ctx.service().turnOnPillow(ctx.userId())
                : command == OnOffType.OFF ? ctx.service().turnOffPillow(ctx.userId()) : null;
        if (future != null) {
            apply(ctx, future);
        }
    }

    private static void pillowTargetTemperature(Context ctx, Command command) {
        double temperature = parseTemperature(command);
        if (Double.isNaN(temperature)) {
            return;
        }
        int level = HeatingLevelConversion.temperatureToLevel(temperature,
                commandUsesFahrenheit(command, ctx.fahrenheit()));

        UserDataSnapshot data = ctx.userData();
        PillowState pillowState = data != null ? data.pillowState() : null;
        PillowEntry pillow = pillowState != null ? pillowState.findPillow(ctx.side()) : null;
        // Writing a level to an off pillow is silently ignored by the API: power on first
        var future = pillow != null && !pillow.isOn()
                ? ctx.service().turnOnPillow(ctx.userId())
                        .thenCompose(v -> ctx.service().setPillowLevel(ctx.userId(), level))
                : ctx.service().setPillowLevel(ctx.userId(), level);
        apply(ctx, future);
    }

    private void alarmEnabled(Context ctx, Command command) {
        withTargetAlarm(ctx, "toggle", alarm -> {
            boolean enable = command == OnOffType.ON;
            // Optimistic feedback + timestamped command (LWW vs the polled list).
            ctx.commandState().recordAlarm(alarm.id(), enable);
            apply(ctx, ctx.service().setAlarmEnabled(ctx.userId(), alarm, enable));
        });
    }

    private void alarmTime(Context ctx, Command command) {
        Instant newTime;
        if (command instanceof org.openhab.core.library.types.DateTimeType dateTime) {
            newTime = dateTime.getInstant();
        } else if (command instanceof org.openhab.core.library.types.StringType string) {
            java.time.@Nullable LocalTime parsed = ApiValueParser.parseTimeOfDay(string.toString());
            if (parsed == null) {
                LOGGER.warn("Cannot parse '{}' as an alarm time", string);
                return;
            }
            newTime = parsed.atDate(LocalDate.now(clock.withZone(ctx.zone()))).atZone(ctx.zone()).toInstant();
        } else {
            LOGGER.warn("Unsupported command type {} for alarm time", command.getClass().getSimpleName());
            return;
        }

        withTargetAlarm(ctx, "reschedule", alarm -> {
            String timeOfDay = LocalDateTime.ofInstant(newTime, ctx.zone())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            apply(ctx, ctx.service().setAlarmTime(ctx.userId(), alarm, timeOfDay));
        });
    }

    private void dismissAlarm(Context ctx) {
        withTargetAlarm(ctx, "dismiss", alarm -> apply(ctx, ctx.service().dismissAlarm(ctx.userId(), alarm.id())));
    }

    private void snoozeAlarm(Context ctx) {
        withTargetAlarm(ctx, "snooze",
                alarm -> apply(ctx, ctx.service().snoozeAlarm(ctx.userId(), alarm.id(), DEFAULT_SNOOZE_MINUTES)));
    }

    private static void awayMode(Context ctx, Command command) {
        boolean start = command == OnOffType.ON;
        // Optimistic feedback + timestamped stamp (LWW vs the away poll), same as
        // the other mutable channels; the away poll confirms/corrects.
        ctx.commandState().recordChannel(CHANNEL_AWAY_MODE, start);
        if (start) {
            // Away = drop the user's current bed-side assignment (app contract).
            apply(ctx, ctx.service().setAwayMode(ctx.userId(), null, false));
        } else {
            String devId = ctx.deviceId();
            if (devId == null) {
                LOGGER.warn("No device id known; cannot leave away mode");
                return;
            }
            apply(ctx, ctx.service().setBedSide(ctx.userId(), devId, ctx.side()));
        }
    }

    private static void primePod(Context ctx) {
        String devId = ctx.deviceId();
        if (devId != null) {
            apply(ctx, ctx.service().primePod(devId, ctx.userId()));
        } else {
            LOGGER.warn("No device id known; cannot start priming");
        }
    }

    private static void ledBrightness(Context ctx, Command command) {
        String devId = ctx.deviceId();
        int level = command instanceof DecimalType decimal ? decimal.intValue()
                : command instanceof QuantityType<?> quantity ? (int) Math.round(quantity.doubleValue()) : -1;
        if (devId != null && level >= 0) {
            apply(ctx, ctx.service().setLedBrightness(devId, level));
        } else {
            LOGGER.warn("Cannot apply LED brightness from command {}", command);
        }
    }

    private static boolean commandUsesFahrenheit(Command command, boolean fallback) {
        return command instanceof QuantityType<?> quantity ? quantity.getUnit().equals(ImperialUnits.FAHRENHEIT)
                : fallback;
    }
}
