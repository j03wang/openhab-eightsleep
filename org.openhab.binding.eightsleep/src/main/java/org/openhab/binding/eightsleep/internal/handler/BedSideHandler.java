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

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.model.BaseData;
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.binding.eightsleep.internal.model.HeatingLevelConversion;
import org.openhab.binding.eightsleep.internal.model.TrendParser;
import org.openhab.core.i18n.TimeZoneProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for a single {@code bedSide} thing: exposes sleep metrics, temperature control,
 * base controls and alarms of one sleeper.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(BedSideHandler.class);
    private final TimeZoneProvider timeZoneProvider;

    /** Heart rate data younger than this confirms bed presence. */
    static final long PRESENCE_FRESH_SECONDS = 600;

    private @Nullable ScheduledFuture<?> refreshJob;
    private String userId = "";
    private String side = "left";
    /** True when one user controls both zones ("Both"/solo bed). */
    private boolean soloBed;
    /**
     * Last commanded value per channel, kept so the sync loop can do last-write-wins
     * merging against polled data: whichever was observed more recently wins. Entries
     * are never expired - a fresh poll simply arrives with a newer timestamp.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CommandedValue> commanded =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A value written by a channel command, stamped with the command time. */
    record CommandedValue(Instant at, boolean on) {
    }

    /**
     * Last-write-wins merge shared by every mutable channel: whichever observation
     * was stamped more recently wins; ties go to the polled value (the server is
     * authoritative). Returns null when no source has spoken.
     */
    static @Nullable Boolean resolveLatest(@Nullable Boolean polledOn, @Nullable Instant polledAt,
            @Nullable CommandedValue commanded) {
        if (commanded == null) {
            return polledOn;
        }
        if (polledOn == null || polledAt == null) {
            return commanded.on();
        }
        return polledAt.isBefore(commanded.at()) ? commanded.on() : polledOn;
    }

    /**
     * A pending channel command can be dropped once the polled (server) value agrees
     * with what was published - i.e. the polled value WON the merge and the server
     * has confirmed the command. When the command merely beat a stale contradicting
     * poll, the entry must be kept or that stale poll would flicker back next cycle.
     * Static and unit-testable.
     */
    static boolean shouldRetireCommand(@Nullable Boolean polledValue, @Nullable Boolean resolved) {
        return polledValue != null && polledValue.equals(resolved);
    }

    /** alarmId -> commanded enabled state, stamped when the command was sent. */
    private final java.util.concurrent.ConcurrentHashMap<String, CommandedValue> commandedAlarms =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Last known/commandsed raw heating level target, kept for the off-state fallback. */
    private @Nullable Double lastKnownTargetLevel;

    private boolean syncStartedLogged;
    private final java.util.List<String> syncNotes = new ArrayList<>();
    private volatile String lastSyncSummary = "";

    private void note(String label, @Nullable Object value) {
        syncNotes.add(label + "=" + (value == null ? "<null>" : value));
    }

    private void noteOnOff(String label, @Nullable Boolean value) {
        note(label, value == null ? null : OnOffType.from(value));
    }

    public BedSideHandler(Thing thing, TimeZoneProvider timeZoneProvider) {
        super(thing);
        this.timeZoneProvider = timeZoneProvider;
    }

    @Override
    public void initialize() {
        BedSideConfiguration config = getConfigAs(BedSideConfiguration.class);
        if (config.userId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/bedside.status.missing-user");
            return;
        }
        this.userId = config.userId.trim();
        String configuredSide = config.label.isBlank() ? "left" : config.label.trim().toLowerCase();
        // "solo" (Both) beds read data from the left zone; the raw side is kept for
        // API calls that need it (away mode re-sync uses "solo", never a rewrite).
        boolean soloBed = "solo".equals(configuredSide);
        this.side = soloBed ? "left" : configuredSide;
        this.soloBed = soloBed;

        updateStatus(ThingStatus.UNKNOWN);

        AccountHandler account = getAccountHandler();
        if (account == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        // Register even when the bridge is still connecting: the poll loop only reports
        // users that are registered, so late registration would silently skip this sleeper.
        account.registerBedSide(userId, side);
        // Always run the channel-sync job; it reports OFFLINE(BRIDGE_OFFLINE) until the
        // bridge has data and flips ONLINE by itself once polls succeed.
        startRefreshJob(account);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatus) {
        super.bridgeStatusChanged(bridgeStatus);
        if (bridgeStatus.getStatus() == ThingStatus.ONLINE && userId != null && !userId.isBlank()) {
            // Bridge recovered (e.g. after a reconnect): re-arm polling for this side
            AccountHandler account = getAccountHandler();
            if (account != null) {
                account.registerBedSide(userId, side);
                startRefreshJob(account);
                scheduler.schedule(() -> updateChannelsFromCache(account), 2, TimeUnit.SECONDS);
            }
        }
    }

    private synchronized void startRefreshJob(AccountHandler account) {
        stopRefreshJob();
        // State updates are driven by the bridge polling; this job only syncs the channels.
        refreshJob = scheduler.scheduleWithFixedDelay(() -> updateChannelsFromCache(account), 2, 10, TimeUnit.SECONDS);
    }

    private synchronized void stopRefreshJob() {
        ScheduledFuture<?> job = refreshJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
        }
        refreshJob = null;
    }

    @Override
    public void dispose() {
        AccountHandler account = getAccountHandler();
        if (account != null) {
            account.unregisterBedSide(userId);
        }
        stopRefreshJob();
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        AccountHandler account = getAccountHandler();
        EightSleepApiClient client = account != null ? account.getApiClient() : null;
        if (client == null || account == null) {
            logger.debug("No API client available; ignoring command {}", command);
            return;
        }
        if (command instanceof RefreshType) {
            updateChannelsFromCache(account);
            return;
        }

        String channelId = channelUID.getIdWithoutGroup();
        boolean fahrenheit = account.getTemperatureUnit('c') == 'f';

        try {
            switch (channelId) {
                case CHANNEL_TARGET_TEMPERATURE -> handleTargetTemperature(client, command, fahrenheit);
                case CHANNEL_SIDE_POWER -> handleSidePower(client, command);
                case CHANNEL_HEAD_ANGLE -> handleBaseAngle(client, command, true);
                case CHANNEL_FEET_ANGLE -> handleBaseAngle(client, command, false);
                case CHANNEL_BASE_PRESET -> {
                    AccountHandler acct = getAccountHandler();
                    String devId = acct != null ? acct.getDeviceId() : null;
                    if (devId == null) {
                        logger.debug("No device id; cannot set base preset");
                    } else {
                        client.setBasePreset(userId, devId, command.toString().toLowerCase())
                                .thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
                    }
                }
                case CHANNEL_PILLOW_POWER -> handlePillowPower(client, command);
                case CHANNEL_PILLOW_TARGET_TEMPERATURE -> handlePillowTargetTemperature(client, command, fahrenheit);
                case CHANNEL_ALARM_ENABLED -> handleAlarmEnabled(client, command);
                case CHANNEL_ALARM_TIME -> handleAlarmTime(client, command);
                case CHANNEL_DISMISS_ALARM -> {
                    if (command == OnOffType.ON) {
                        handleDismissAlarm(client);
                    }
                }
                case CHANNEL_SNOOZE_ALARM -> {
                    if (command == OnOffType.ON) {
                        handleSnoozeAlarm(client);
                    }
                }
                case CHANNEL_AWAY_MODE -> handleAwayMode(client, account, command);
                case CHANNEL_PRIME -> {
                    if (command == OnOffType.ON) {
                        String devId = account.getDeviceId();
                        if (devId != null) {
                            client.primePod(devId, userId).thenRun(this::scheduleRefresh)
                                    .exceptionally(this::logCommandFailure);
                        } else {
                            logger.warn("No device id known; cannot start priming");
                        }
                    }
                }
                case CHANNEL_LED_BRIGHTNESS -> {
                    AccountHandler acct = getAccountHandler();
                    String devId = acct != null ? acct.getDeviceId() : null;
                    int level = command instanceof DecimalType decimal ? decimal.intValue()
                            : command instanceof QuantityType<?> quantity ? (int) Math.round(quantity.doubleValue())
                            : -1;
                    if (devId != null && level >= 0) {
                        client.setLedBrightness(devId, level).thenRun(this::scheduleRefresh)
                                .exceptionally(this::logCommandFailure);
                    } else {
                        logger.warn("Cannot apply LED brightness from command {}", command);
                    }
                }
                case CHANNEL_SNORE_MITIGATION -> logger.debug("Snore mitigation is read-only");
                default -> logger.warn("Unsupported channel {} for command {}", channelUID, command);
            }
        } catch (Exception e) {
            logger.warn("Failed to execute command {} on {}: {}", command, channelUID, e.getMessage());
        }
    }

    private void handleTargetTemperature(EightSleepApiClient client, Command command, boolean fahrenheit)
            throws ApiException {
        double temperature = parseTemperature(command);
        if (Double.isNaN(temperature)) {
            return;
        }
        if (command instanceof QuantityType<?> quantity) {
            fahrenheit = quantity.getUnit().isCompatible(ImperialUnits.FAHRENHEIT);
        }

        int level = HeatingLevelConversion.temperatureToLevel(temperature, fahrenheit);
        client.setHeatingLevel(userId, level, 0).thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    private void handleSidePower(EightSleepApiClient client, Command command) throws ApiException {
        boolean turnOn = command == OnOffType.ON;
        // Optimistic feedback + timestamped command: the sync loop does last-write-wins
        // against the polled payload, so no stale cycle can flip the switch back.
        updateState(GROUP_DEVICE + "#" + CHANNEL_SIDE_POWER, OnOffType.from(turnOn));
        commanded.put(CHANNEL_SIDE_POWER, new CommandedValue(Instant.now(), turnOn));
        CompletableFuture<Void> future = turnOn
                ? client.turnOnSide(userId)
                : client.turnOffSide(userId);
        future.thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    /** Max angles of the adjustable base sections. */
    static final int HEAD_ANGLE_MAX = 45;
    static final int FEET_ANGLE_MAX = 20;

    /**
     * Heating/cooling/idle from the raw target level sign. Level 0 is neutral
     * (27 C) - actively tracking it is neither heating nor cooling.
     * Static and unit-testable.
     */
    static String deriveHeatingState(boolean nowHeating, double targetLevelRaw) {
        if (!nowHeating || targetLevelRaw == 0) {
            return "idle";
        }
        return targetLevelRaw > 0 ? "heating" : "cooling";
    }

    /**
     * Upstream quirk: while a side is off the API reports target level 0 (27 C),
     * which is meaningless. The shown target therefore holds the last MEANINGFUL
     * raw level; a genuinely commanded 0 (heating flag set) or any non-zero raw
     * value updates it. The returned value is also the new persisted state.
     * Static and unit-testable.
     */
    static double resolveShownTargetLevel(double targetLevelRaw, @Nullable Boolean nowHeating,
            @Nullable Double previousShown) {
        boolean meaningful = targetLevelRaw != 0 || Boolean.TRUE.equals(nowHeating);
        if (meaningful || previousShown == null) {
            return targetLevelRaw;
        }
        return previousShown;
    }

    /**
     * Staleness of cached user data relative to its own poll cadence: older than
     * four intervals (but never less than 60 s) means polls keep failing and the
     * cached values can no longer be trusted as current. A null timestamp (no
     * successful poll ever) counts as stale. Static and unit-testable.
     */
    static boolean isUserDataStale(@Nullable Instant lastUpdated, Instant now, long userIntervalSeconds) {
        long thresholdSeconds = Math.max(60L, 4 * userIntervalSeconds);
        return lastUpdated == null || lastUpdated.plusSeconds(thresholdSeconds).isBefore(now);
    }

    /**
     * Whether the alarm channels should be reset to UNDEF: there is no selectable
     * alarm AND either stale alarm entries are still published or the last alarms
     * poll was recent enough to trust an empty list (e.g. subscription lapse).
     * Static and unit-testable.
     */
    static boolean shouldClearAlarmChannels(boolean nextAlarmPresent, int alarmCount, @Nullable Instant alarmsPolledAt,
            Instant now, long userIntervalSeconds) {
        if (nextAlarmPresent) {
            return false;
        }
        return alarmCount > 0 || !isUserDataStale(alarmsPolledAt, now, userIntervalSeconds);
    }

    /**
     * Computes the leg/torso angle pair for a single-axis base command: the moved
     * axis takes the commanded (clamped) angle, the other axis keeps its last known
     * angle so it does not move. Static and unit-testable.
     */
    static int[] mergeBaseAngles(boolean head, int angle, @Nullable Integer cachedLeg, @Nullable Integer cachedTorso) {
        int clamped = Math.max(0, Math.min(head ? HEAD_ANGLE_MAX : FEET_ANGLE_MAX, angle));
        int currentLeg = cachedLeg != null ? cachedLeg : 0;
        int currentTorso = cachedTorso != null ? cachedTorso : 0;
        return head ? new int[] { currentLeg, clamped } : new int[] { clamped, currentTorso };
    }

    private void handleBaseAngle(EightSleepApiClient client, Command command, boolean head) throws ApiException {
        int angle;
        if (command instanceof QuantityType<?> quantity) {
            angle = (int) Math.round(quantity.doubleValue());
        } else if (command instanceof DecimalType decimal) {
            angle = decimal.intValue();
        } else {
            logger.warn("Unsupported command type {} for base angle", command.getClass().getSimpleName());
            return;
        }

        AccountHandler account = getAccountHandler();
        AccountHandler.UserData data = account != null ? account.getUserData(userId) : null;
        BaseData.SideData baseSide = data != null ? data.getBaseSide(side) : null;
        Integer cachedLeg = baseSide != null && baseSide.leg != null ? baseSide.leg.currentAngle : null;
        Integer cachedTorso = baseSide != null && baseSide.torso != null ? baseSide.torso.currentAngle : null;
        int[] angles = mergeBaseAngles(head, angle, cachedLeg, cachedTorso);

        AccountHandler acct = getAccountHandler();
        String devId = acct != null ? acct.getDeviceId() : null;
        if (devId == null) {
            logger.debug("No device id; cannot set base angle");
            return;
        }
        client.setBaseAngle(userId, devId, angles[0], angles[1]).thenRun(this::scheduleRefresh)
                .exceptionally(this::logCommandFailure);
    }

    private void handlePillowPower(EightSleepApiClient client, Command command) {
        CompletableFuture<Void> future = command == OnOffType.ON ? client.turnOnPillow(userId)
                : command == OnOffType.OFF ? client.turnOffPillow(userId) : null;
        if (future != null) {
            updateState(GROUP_PILLOW + "#" + CHANNEL_PILLOW_POWER, OnOffType.from(OnOffType.ON == command));
            future.thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
        }
    }

    // ==================== alarm commands ====================

    private void handleAlarmEnabled(EightSleepApiClient client, Command command) {
        AccountHandler.UserData userData = getUserData();
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData, Instant.now()) : null;
        if (alarm == null || alarm.id == null) {
            logger.debug("No upcoming alarm to toggle");
            return;
        }
        boolean enable = command == OnOffType.ON;
        // Optimistic feedback + timestamped command (last-write-wins vs the polled list).
        updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_ENABLED, OnOffType.from(enable));
        commandedAlarms.put(alarm.id, new CommandedValue(Instant.now(), enable));
        client.setAlarmEnabled(userId, alarm, enable).thenRun(this::scheduleRefresh)
                .exceptionally(this::logCommandFailure);
    }

    private void handleAlarmTime(EightSleepApiClient client, Command command) {
        Instant newTime = null;
        if (command instanceof DateTimeType dateTime) {
            newTime = dateTime.getInstant();
        } else if (command instanceof StringType string) {
            java.time.LocalTime parsed = TrendParser.parseTimeOfDay(string.toString());
            if (parsed == null) {
                logger.warn("Cannot parse '{}' as an alarm time", string);
                return;
            }
            newTime = parsed.atDate(java.time.LocalDate.now())
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (newTime == null) {
            logger.warn("Unsupported command type {} for alarm time", command.getClass().getSimpleName());
            return;
        }

        AccountHandler.UserData userData = getUserData();
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData, Instant.now()) : null;
        if (alarm == null || alarm.id == null) {
            logger.debug("No upcoming alarm to reschedule");
            return;
        }
        String timeOfDay = java.time.LocalDateTime
                .ofInstant(newTime, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        client.setAlarmTime(userId, alarm, timeOfDay)
                .thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    private void handleDismissAlarm(EightSleepApiClient client) {
        AccountHandler.UserData userData = getUserData();
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData, Instant.now()) : null;
        if (alarm == null || alarm.id == null) {
            logger.debug("No upcoming alarm to dismiss");
            return;
        }
        client.dismissAlarm(userId, alarm.id).thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    private void handleSnoozeAlarm(EightSleepApiClient client) {
        AccountHandler.UserData userData = getUserData();
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData, Instant.now()) : null;
        if (alarm == null || alarm.id == null) {
            logger.debug("No upcoming alarm to snooze");
            return;
        }
        client.snoozeAlarm(userId, alarm.id, DEFAULT_SNOOZE_MINUTES).thenRun(this::scheduleRefresh)
                .exceptionally(this::logCommandFailure);
    }

    private AccountHandler.UserData getUserData() {
        AccountHandler account = getAccountHandler();
        return account != null ? account.getUserData(userId) : null;
    }

    // ==================== away mode / priming ====================

    private void handleAwayMode(EightSleepApiClient client, AccountHandler account, Command command) {
        String devId = account.getDeviceId();
        if (devId == null) {
            logger.warn("No device id known; cannot change away mode");
            return;
        }
        boolean start = command == OnOffType.ON;
        // Instant feedback; the away poll (verified side-slot rule) confirms/corrects.
        updateState(GROUP_DEVICE + "#" + CHANNEL_AWAY_MODE, OnOffType.from(start));
        account.setLastKnownAwayMode(userId, start);
        // side is the configured physical side; the client skips re-assertion when it
        // is not a genuine left/right (solo beds must not be rewritten).
        client.setAwayMode(userId, devId, soloBed ? "solo" : side, start ? "start" : "end")
                .thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    private void handlePillowTargetTemperature(EightSleepApiClient client, Command command, boolean fahrenheit) {
        double temperature = parseTemperature(command);
        if (Double.isNaN(temperature)) {
            return;
        }
        int level = HeatingLevelConversion.temperatureToLevel(temperature, fahrenheit);

        EightSleepApiClient.PillowData pillowData = getPillowData();
        EightSleepApiClient.PillowEntry pillow = pillowData != null ? pillowData.findPillow(side) : null;
        // Writing a level to an off pillow is silently ignored by the API: power on first
        CompletableFuture<Void> future = pillow != null && !pillow.isOn()
                ? client.turnOnPillow(userId).thenCompose(v -> client.setPillowLevel(userId, level))
                : client.setPillowLevel(userId, level);
        future.thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    /**
     * Raw heating level that Autopilot is targeting (smartSchedule.bedTimeLevel), or null.
     */
    private @Nullable Double getAutopilotTargetLevel(AccountHandler.UserData data) {
        return autopilotTargetLevel(data.temperature);
    }

    /**
     * Raw heating level that Autopilot is targeting (smartSchedule.bedTimeLevel),
     * or null. Static and unit-testable.
     */
    static @Nullable Double autopilotTargetLevel(@Nullable JsonObject temperature) {
        return TrendParser.getDouble(TrendParser.getObject(temperature, "smart"), "bedTimeLevel");
    }

    private EightSleepApiClient.PillowData getPillowData() {
        AccountHandler account = getAccountHandler();
        AccountHandler.UserData data = account != null ? account.getUserData(userId) : null;
        return data != null ? data.pillowData : null;
    }

    /**
     * Delegates to {@link EightSleepApiClient.Alarm#computeNextRun(java.time.ZoneId)}.
     */
    private static java.time.@Nullable Instant computeNextRun(
            EightSleepApiClient.Alarm alarm) {
        return alarm.computeNextRun(java.time.ZoneId.systemDefault());
    }

    /**
     * Selects the alarm the alarm channels represent: soonest locally-computed run
     * across ALL alarms, enabled or not (a disabled alarm's schedule is derived
     * from time+weekDays since its server nextTimestamp goes null). Selection is
     * therefore stable: toggling one alarm off doesn't move selection to another.
     * Ties break on id; an alarm without an id loses the tie (it cannot be toggled).
     */
    static EightSleepApiClient.Alarm findTargetAlarm(
            org.openhab.binding.eightsleep.internal.handler.AccountHandler.UserData userData,
            java.time.Instant now) {
        return findTargetAlarm(userData, now, java.time.ZoneId.systemDefault());
    }

    /**
     * As above with an explicit zone - the production path uses the system zone,
     * tests inject a fixed zone so they cannot depend on where they run.
     */
    static EightSleepApiClient.Alarm findTargetAlarm(
            org.openhab.binding.eightsleep.internal.handler.AccountHandler.UserData userData,
            java.time.Instant now, java.time.ZoneId zone) {
        // Rule 2: soonest computed run (enabled or disabled).
        EightSleepApiClient.Alarm target = null;
        Instant targetRun = null;
        for (EightSleepApiClient.Alarm alarm : userData.alarms) {
            Instant run = alarm.computeNextRun(zone, now);
            if (run == null) {
                continue;
            }
            boolean closer = targetRun == null || run.isBefore(targetRun)
                    || (run.equals(targetRun) && target != null
                            && alarm.id != null
                            && (target.id == null || alarm.id.compareTo(target.id) < 0));
            if (closer) {
                target = alarm;
                targetRun = run;
            }
        }
        return target;
    }

    /**
     * Parses a temperature command into a double value, or NaN when unsupported.
     * Static and unit-testable; QuantityType values are converted to their own
     * unit's number (Celsius-compatible units to Celsius, Fahrenheit-compatible
     * to Fahrenheit), plain numbers pass through.
     */
    static double parseTemperature(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            javax.measure.Unit<?> unit = quantity.getUnit();
            if (unit.isCompatible(SIUnits.CELSIUS)) {
                QuantityType<?> celsius = quantity.toInvertibleUnit(SIUnits.CELSIUS);
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
        if (command instanceof StringType string) {
            try {
                return Double.parseDouble(string.toString());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private Void logCommandFailure(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        logger.warn("Eight Sleep command failed: {}", cause.getMessage());
        return null;
    }

    private void scheduleRefresh() {
        AccountHandler account = getAccountHandler();
        if (account != null) {
            scheduler.schedule(() -> updateChannelsFromCache(account), 3, TimeUnit.SECONDS);
        }
    }

    /**
     * Pushes the cached bridge data into the channels of this bed side.
     */
    protected void updateChannelsFromCache(AccountHandler account) {
        // A single unexpected payload quirk must not kill the periodic sync job:
        // scheduleWithFixedDelay suppresses ALL future executions once a run throws.
        try {
            doUpdateChannelsFromCache(account);
        } catch (RuntimeException e) {
            logger.warn("Channel sync failed for user {} side '{}': {}", userId, side, e.getMessage(), e);
        }
    }

    private void doUpdateChannelsFromCache(AccountHandler account) {
        syncNotes.clear();
        if (!syncStartedLogged) {
            syncStartedLogged = true;
            logger.debug("Channel sync active for user {} side '{}'; thing status {}",
                    userId, side, getThing().getStatus());
        }
        DeviceData deviceData = account.getDeviceData();
        AccountHandler.UserData userData = account.getUserData(userId);

        if (deviceData == null || userData == null) {
            if (deviceData == null) {
                // the bridge itself is not producing data yet
                if (getThing().getStatusInfo().getStatusDetail() != ThingStatusDetail.BRIDGE_OFFLINE) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                }
            } else {
                // device data flows, but this user is never polled - almost certainly
                // a bad userId configuration, not a bridge problem
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/bedside.status.user-not-found");
            }
            note("deviceData", deviceData != null);
            note("userData", userData != null);
            logSyncSummary();
            return;
        }
        // Staleness guard: cached values keep being published (frozen but visible),
        // while the thing status reflects whether the data behind them is current.
        boolean userDataStale = isUserDataStale(userData.lastUpdated, Instant.now(),
                account.userRefreshIntervalSeconds());
        if (userDataStale) {
            if (getThing().getStatus() != ThingStatus.OFFLINE
                    || getThing().getStatusInfo().getStatusDetail() != ThingStatusDetail.COMMUNICATION_ERROR) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "@text/bedside.status.stale-data");
            }
        } else if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }

        boolean fahrenheit = account.getTemperatureUnit('c') == 'f';
        Double heatingLevelRaw = deviceData.getHeatingLevel(side);
        Double targetLevelRaw = deviceData.getTargetHeatingLevel(side);
        Boolean nowHeating = "right".equals(side) ? deviceData.rightNowHeating : deviceData.leftNowHeating;
        if (targetLevelRaw == null) {
            // Target key missing for this side: dump everything we know about the payload
            // once per poll so the real field names can be identified from the log.
            logger.debug(
                    "TargetHeatingLevel absent for side '{}' (expected while the bed is off); "
                            + "device json keys: {}",
                    side, deviceData.rawFieldNames);
        }

        // --- heating / temperature channels ---
        note("side", side);
        note("heatingLevelRaw", heatingLevelRaw);
        note("targetLevelRaw", targetLevelRaw);
        note("nowHeating(left)", deviceData.leftNowHeating);
        note("hasWater", deviceData.hasWater);
        if (heatingLevelRaw != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_HEATING_LEVEL, new DecimalType(heatingLevelRaw));
        }
        if (targetLevelRaw != null) {
            // Upstream: when the bed is off the API reports a meaningless 0 (27 C);
            // keep the last meaningful target instead of flipping to it.
            double shownLevel = resolveShownTargetLevel(targetLevelRaw, nowHeating, lastKnownTargetLevel);
            lastKnownTargetLevel = shownLevel;
            double targetTemp = HeatingLevelConversion.levelToTemperature(shownLevel, fahrenheit);
            updateState(GROUP_CURRENT + "#" + CHANNEL_TARGET_TEMPERATURE, toQuantity(targetTemp, fahrenheit));
        } else {
            // Field absent (typical when the side is off): fall back to Autopilot schedule,
            // mirroring upstream's get_autopilot_target_temp fallback.
            AccountHandler.UserData data = userData;
            Double autopilot = getAutopilotTargetLevel(data);
            if (autopilot != null) {
                double targetTemp = HeatingLevelConversion.levelToTemperature(autopilot, fahrenheit);
                updateState(GROUP_CURRENT + "#" + CHANNEL_TARGET_TEMPERATURE, toQuantity(targetTemp, fahrenheit));
            }
        }
        Integer remaining = "right".equals(side) ? deviceData.rightHeatingDuration : deviceData.leftHeatingDuration;
        if (remaining != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_REMAINING_TIME, new QuantityType<>(remaining, Units.SECOND));
        }
        if (nowHeating != null && targetLevelRaw != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_HEATING_STATE,
                    new StringType(deriveHeatingState(nowHeating, targetLevelRaw)));
        }

        // --- sleep session channels (raw trends JSON, parsed defensively) ---
        // Day-level fields (score, presenceStart/End, tnt, durations,
        // sleepQualityScore) sit on each "day"; only timeseries/stages are session-level.
        TrendParser trends = userData.getTrends();
        Double measuredBedC = trends.isEmpty() ? null
                : TrendParser.latestSeriesValue(trends.getCurrentSession(), "tempBedC");
        if (!trends.isEmpty()) {
            JsonObject currentDay = trends.getDay(0);
            JsonObject previousDay = trends.getDay(1);
            JsonObject currentSession = trends.getCurrentSession();

            if (currentDay != null) {
                Instant presenceStart = TrendParser.parseTimestamp(
                        TrendParser.getString(currentDay, "presenceStart"));
                if (presenceStart != null) {
                    updateState(GROUP_CURRENT + "#" + CHANNEL_SESSION_START, new DateTimeType(presenceStart));
                }
                Instant presenceEnd = TrendParser.parseTimestamp(
                        TrendParser.getString(currentDay, "presenceEnd"));
                if (presenceEnd != null) {
                    updateState(GROUP_CURRENT + "#" + CHANNEL_SESSION_END, new DateTimeType(presenceEnd));
                }

                putDecimal(GROUP_CURRENT, CHANNEL_SLEEP_SCORE, TrendParser.getDouble(currentDay, "score"));
                putDecimal(GROUP_CURRENT, CHANNEL_QUALITY_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(currentDay, "sleepQualityScore"), "total"));
                putDecimal(GROUP_CURRENT, CHANNEL_ROUTINE_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(currentDay, "sleepRoutineScore"), "total"));
                putDecimal(GROUP_CURRENT, CHANNEL_HRV, TrendParser.getDouble(
                        TrendParser.getObject(
                                TrendParser.getObject(currentDay, "sleepQualityScore"), "hrv"), "current"));
                putDecimal(GROUP_CURRENT, CHANNEL_BREATH_RATE, TrendParser.getDouble(
                        TrendParser.getObject(
                                TrendParser.getObject(currentDay, "sleepQualityScore"), "respiratoryRate"),
                        "current"));

                Double tnt = TrendParser.getDouble(currentDay, "tnt");
                if (tnt != null) {
                    updateState(GROUP_LAST_SLEEP + "#" + CHANNEL_TOSS_TURNS, new DecimalType(tnt));
                }
            }

            if (currentSession != null) {
                // Timeseries values are celsius readings (upstream converts with "c" fixed)
                putLatestCelsius(currentSession, "tempRoomC", GROUP_DEVICE, CHANNEL_ROOM_TEMPERATURE);
                putLatest(currentSession, "heartRate", GROUP_CURRENT, CHANNEL_HEART_RATE);
                putLatest(currentSession, "respiratoryRate", GROUP_CURRENT, CHANNEL_RESPIRATORY_RATE);
                // Bed presence: fresh heart rate data confirms presence
                updateState(GROUP_BASE + "#" + CHANNEL_BED_PRESENCE, OnOffType.from(
                        isPresent(currentSession, Instant.now())));
                // Current stage from the session's stage segments
                String currentStage = currentSleepStage(currentSession, Instant.now());
                if (currentStage != null) {
                    updateState(GROUP_CURRENT + "#" + CHANNEL_SLEEP_STAGE, new StringType(currentStage));
                }
            } else {
                updateState(GROUP_BASE + "#" + CHANNEL_BED_PRESENCE, OnOffType.OFF);
            }

            // last completed sleep: the previous day
            if (previousDay != null) {
                putDecimal(GROUP_LAST_SLEEP, CHANNEL_SLEEP_SCORE, TrendParser.getDouble(previousDay, "score"));
                putDecimal(GROUP_LAST_SLEEP, CHANNEL_QUALITY_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(previousDay, "sleepQualityScore"), "total"));
                putDecimal(GROUP_LAST_SLEEP, CHANNEL_ROUTINE_SCORE, TrendParser.getDouble(
                        TrendParser.getObject(previousDay, "sleepRoutineScore"), "total"));

                Double lightDuration = TrendParser.getDouble(previousDay, "lightDuration");
                Double deepDuration = TrendParser.getDouble(previousDay, "deepDuration");
                Double remDuration = TrendParser.getDouble(previousDay, "remDuration");
                Double presenceDuration = TrendParser.getDouble(previousDay, "presenceDuration");
                Double sleepDuration = TrendParser.getDouble(previousDay, "sleepDuration");
                putDuration(GROUP_LAST_SLEEP, CHANNEL_LIGHT_SLEEP, lightDuration);
                putDuration(GROUP_LAST_SLEEP, CHANNEL_DEEP_SLEEP, deepDuration);
                putDuration(GROUP_LAST_SLEEP, CHANNEL_REM_SLEEP, remDuration);
                if (sleepDuration != null) {
                    putDuration(GROUP_LAST_SLEEP, CHANNEL_TIME_SLEPT, sleepDuration);
                } else if (lightDuration != null && deepDuration != null && remDuration != null) {
                    putDuration(GROUP_LAST_SLEEP, CHANNEL_TIME_SLEPT, lightDuration + deepDuration + remDuration);
                }
                if (presenceDuration != null && sleepDuration != null) {
                    putDuration(GROUP_LAST_SLEEP, CHANNEL_AWAKE_DURATION, presenceDuration - sleepDuration);
                }
                Instant lastStart = TrendParser.parseTimestamp(
                        TrendParser.getString(previousDay, "presenceStart"));
                Instant lastEnd = TrendParser.parseTimestamp(
                        TrendParser.getString(previousDay, "presenceEnd"));
                if (lastStart != null) {
                    updateState(GROUP_LAST_SLEEP + "#" + CHANNEL_SESSION_START, new DateTimeType(lastStart));
                }
                if (lastEnd != null) {
                    updateState(GROUP_LAST_SLEEP + "#" + CHANNEL_SESSION_END, new DateTimeType(lastEnd));
                }
            }
        }

        // --- bed temperature: the MEASURED surface temperature wins; the level-derived
        // conversion is only a fallback for when no timeseries data exists yet.
        if (measuredBedC != null) {
            updateState(GROUP_CURRENT + "#" + CHANNEL_BED_TEMPERATURE,
                    new QuantityType<>(measuredBedC, SIUnits.CELSIUS));
        } else if (heatingLevelRaw != null) {
            updateState(GROUP_CURRENT + "#" + CHANNEL_BED_TEMPERATURE, toQuantity(
                    HeatingLevelConversion.levelToTemperature(heatingLevelRaw, fahrenheit), fahrenheit));
        }

        // base channels
        BaseData.SideData baseSide = userData.getBaseSide(side);
        if (baseSide != null) {
            if (baseSide.preset != null && baseSide.preset.name != null) {
                updateState(GROUP_BASE + "#" + CHANNEL_BASE_PRESET, new StringType(baseSide.preset.name));
            }
            if (baseSide.torso != null && baseSide.torso.currentAngle != null) {
                updateState(GROUP_BASE + "#" + CHANNEL_HEAD_ANGLE,
                        new QuantityType<>(baseSide.torso.currentAngle, Units.DEGREE_ANGLE));
            }
            if (baseSide.leg != null && baseSide.leg.currentAngle != null) {
                updateState(GROUP_BASE + "#" + CHANNEL_FEET_ANGLE,
                        new QuantityType<>(baseSide.leg.currentAngle, Units.DEGREE_ANGLE));
            }
            if (baseSide.inSnoreMitigation != null) {
                updateState(GROUP_BASE + "#" + CHANNEL_SNORE_MITIGATION,
                        OnOffType.from(baseSide.inSnoreMitigation));
            }
        }

        // --- pillow (Pod 5 accessory) ---
        EightSleepApiClient.PillowData pillowData = userData.pillowData;
        EightSleepApiClient.PillowEntry pillow = pillowData != null ? pillowData.findPillow(side) : null;
        if (pillow != null) {
            int rawPillowLevel = pillow.getLevel();
            double pillowTemp = HeatingLevelConversion.levelToTemperature(rawPillowLevel, fahrenheit);
            updateState(GROUP_PILLOW + "#" + CHANNEL_PILLOW_TARGET_TEMPERATURE, toQuantity(pillowTemp, fahrenheit));
            updateState(GROUP_PILLOW + "#" + CHANNEL_PILLOW_POWER, OnOffType.from(pillow.isOn()));
            updateState(GROUP_PILLOW + "#" + CHANNEL_PILLOW_HEATING_LEVEL, new DecimalType(rawPillowLevel));
        }

        // --- hub LED brightness ---
        if (deviceData.ledBrightnessLevel != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_LED_BRIGHTNESS,
                    new DecimalType(deviceData.ledBrightnessLevel.doubleValue()));
        }

        // --- water / priming state ---
        if (deviceData.hasWater != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_HAS_WATER, OnOffType.from(deviceData.hasWater));
        }
        if (deviceData.needsPriming != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_NEEDS_PRIMING, OnOffType.from(deviceData.needsPriming));
        }
        if (deviceData.priming != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_IS_PRIMING, OnOffType.from(deviceData.priming));
        }
        if (deviceData.lastPrime != null && !deviceData.lastPrime.isBlank()) {
            Instant lastPrime = TrendParser.parseTimestamp(deviceData.lastPrime);
            if (lastPrime != null) {
                updateState(GROUP_DEVICE + "#" + CHANNEL_LAST_PRIME, new DateTimeType(lastPrime));
            }
        }

        // --- away mode state ---
        // Live rule (verified via captures): away = listed in awaySides AND removed
        // from the side slots. Last-write-wins: a command stamps its time; a poll
        // observed later wins. UNDEF until either source has spoken.
        if (!account.isAwayPolledOnce()) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_AWAY_MODE, UnDefType.UNDEF);
        } else {
            updateState(GROUP_DEVICE + "#" + CHANNEL_AWAY_MODE, OnOffType.from(userData.awayMode));
        }

        // --- side power (live, last-write-wins) ---
        // Polled truth: /temperature currentState.type. A channel command writes a
        // timestamped entry; whichever source was observed more recently wins. The
        // heating LEVEL persists while the bed is off, so it never drives this channel.
        String powerType = TrendParser.getString(
                TrendParser.getObject(userData.temperature, "currentState"), "type");
        note("powerType", powerType == null ? "<absent>" : powerType);

        Boolean polledOn = powerType != null ? !"off".equalsIgnoreCase(powerType)
                : (targetLevelRaw != null ? targetLevelRaw != 0 : null);
        Instant polledAt = userData.temperatureAt;
        Boolean resolvedPower = resolveLatest(polledOn, polledAt, commanded.get(CHANNEL_SIDE_POWER));
        if (resolvedPower != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_SIDE_POWER, OnOffType.from(resolvedPower));
            if (shouldRetireCommand(polledOn, resolvedPower)) {
                commanded.remove(CHANNEL_SIDE_POWER); // server confirmed
            }
        }

        // --- alarm state ---
        EightSleepApiClient.Alarm nextAlarm = findTargetAlarm(userData, Instant.now());
        if (shouldClearAlarmChannels(nextAlarm != null, userData.alarms.size(), userData.alarmsPolledAt,
                Instant.now(), account.userRefreshIntervalSeconds())) {
            // A fresh poll reporting no alarms (e.g. subscription lapsed) must clear the
            // channels instead of silently keeping stale values.
            updateState(GROUP_CURRENT + "#" + CHANNEL_NEXT_ALARM, UnDefType.UNDEF);
            updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_ENABLED, UnDefType.UNDEF);
            updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_TIME, UnDefType.UNDEF);
        }
        if (nextAlarm != null) {
            // publish our computed schedule: the server's nextTimestamp goes null for
            // disabled alarms, but the alarm still has a scheduled time-of-day.
            Instant computedRun = computeNextRun(nextAlarm);
            if (computedRun != null) {
                updateState(GROUP_CURRENT + "#" + CHANNEL_NEXT_ALARM, new DateTimeType(computedRun));
            }
            Instant alarmTime = parseAlarmTimeOfDay(nextAlarm.time);
            if (alarmTime != null) {
                updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_TIME, new DateTimeType(alarmTime));
            }
            // Last-write-wins: a command stamps its time; a poll observed later wins.
            Boolean enabledToPublish = nextAlarm.enabled;
            CommandedValue cmd = nextAlarm.id != null ? commandedAlarms.get(nextAlarm.id) : null;
            Instant observedAt = userData.alarmsPolledAt;
            Boolean resolved = resolveLatest(enabledToPublish, observedAt, cmd);
            if (resolved != null) {
                updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_ENABLED, OnOffType.from(resolved));
                // Retire the command only when the polled value WON (server confirmed).
                if (shouldRetireCommand(enabledToPublish, resolved)) {
                    if (nextAlarm.id != null) {
                        commandedAlarms.remove(nextAlarm.id); // server confirmed
                    }
                }
            }
        }


        // Publish the compact sync summary (INFO, only when something changed)
        logSyncSummary();
    }

    private void putDecimal(String group, String channel, @Nullable Double value) {
        if (value != null) {
            updateState(group + "#" + channel, new DecimalType(value));
        }
    }

    /**
     * Publishes the latest numeric value of a session timeseries series.
     */
    private void putLatest(@Nullable JsonObject session, String seriesName, String group, String channel) {
        Double value = TrendParser.latestSeriesValue(session, seriesName);
        if (value != null) {
            updateState(group + "#" + channel, new DecimalType(value));
        }
    }

    /**
     * Publishes the latest value of a temperature series. The API stores these
     * timeseries in celsius (the series names are literally tempBedC / tempRoomC),
     * so the quantity is always created as CELSIUS and openHAB converts for display.
     */
    private void putLatestCelsius(@Nullable JsonObject session, String seriesName, String group, String channel) {
        Double value = TrendParser.latestSeriesValue(session, seriesName);
        if (value != null) {
            updateState(group + "#" + channel, new QuantityType<>(value, SIUnits.CELSIUS));
        }
    }

    /** Publishes a duration in seconds as a Number:Time quantity. */
    private void putDuration(String group, String channel, @Nullable Double seconds) {
        if (seconds != null && seconds >= 0) {
            updateState(group + "#" + channel, new QuantityType<>(seconds, Units.SECOND));
        }
    }

    /**
     * Resolves the current sleep stage from the session's {@code stages} segments
     * (verified shape: [{"stage":"awake","duration":2070}, ...] oldest-first).
     * "Now" must fall inside the session window [sleepStart, sleepEnd] - the only
     * verified currency signal (live captures carry no "processing" flag) - and the
     * stage is the segment covering the elapsed time. Static and unit-testable.
     *
     * @return "awake"/"light"/"deep"/"rem", or null when unknown/not currently sleeping
     */
    static @Nullable String currentSleepStage(@Nullable JsonObject session, Instant now) {
        if (session == null) {
            return null;
        }
        Instant sleepStart = TrendParser.parseTimestamp(TrendParser.getString(session, "sleepStart"));
        Instant sleepEnd = TrendParser.parseTimestamp(TrendParser.getString(session, "sleepEnd"));
        if (sleepStart == null || sleepEnd == null || now.isBefore(sleepStart) || now.isAfter(sleepEnd)) {
            return null; // not inside a live sleep session
        }
        long elapsedSeconds = Duration.between(sleepStart, now).getSeconds();
        JsonElement stagesEl = session.get("stages");
        if (stagesEl == null || !stagesEl.isJsonArray()) {
            return null;
        }
        String current = null;
        long consumed = 0;
        for (JsonElement entry : stagesEl.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject segment = entry.getAsJsonObject();
            Double duration = TrendParser.getDouble(segment, "duration");
            String stage = TrendParser.getString(segment, "stage");
            if (duration == null || stage == null) {
                continue;
            }
            if (elapsedSeconds < consumed + duration) {
                current = stage.toLowerCase();
                break;
            }
            // the last fully elapsed segment stays current until new data arrives
            current = stage.toLowerCase();
            consumed += duration.longValue();
        }
        return current;
    }

    /**
     * Bed presence detection: heart rate data with a timestamp younger than
     * {@link #PRESENCE_FRESH_SECONDS} (either direction - future timestamps are
     * tolerated as clock skew) confirms presence. Static and unit-testable.
     */
    static boolean isPresent(@Nullable JsonObject session, Instant now) {
        JsonObject ts = TrendParser.getObject(session, "timeseries");
        JsonElement el = ts != null ? ts.get("heartRate") : null;
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() == 0) {
            return false;
        }
        JsonElement entry = el.getAsJsonArray().get(el.getAsJsonArray().size() - 1);
        if (!entry.isJsonArray() || entry.getAsJsonArray().size() < 1
                || !entry.getAsJsonArray().get(0).isJsonPrimitive()) {
            return false;
        }
        Instant heartBeatTime = TrendParser.parseTimestamp(entry.getAsJsonArray().get(0).getAsString());
        return heartBeatTime != null
                && Duration.between(heartBeatTime, now).abs().getSeconds() < PRESENCE_FRESH_SECONDS;
    }

    private QuantityType<?> toQuantity(double temperature, boolean fahrenheit) {
        return new QuantityType<>(temperature, fahrenheit ? ImperialUnits.FAHRENHEIT : SIUnits.CELSIUS);
    }

    /**
     * Logs one compact DEBUG line per sync describing every channel decision, so state
     * questions can be answered from the log without TRACE level.
     */
    private void logSyncSummary() {
        String summary = String.join(", ", syncNotes);
        if (!summary.isBlank() && !summary.equals(lastSyncSummary)) {
            lastSyncSummary = summary;
            logger.debug("Sync for user {} side {}: {}", userId, side, summary);
        }
    }

    /**
     * Parses an "HH:MM:SS" alarm time-of-day into today's Instant in the system zone.
     */
    private Instant parseAlarmTimeOfDay(@Nullable String timeOfDay) {
        java.time.LocalTime time = TrendParser.parseTimeOfDay(timeOfDay);
        return time != null
                ? time.atDate(java.time.LocalDate.now()).atZone(java.time.ZoneId.systemDefault()).toInstant()
                : null;
    }

    private synchronized @Nullable AccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        BridgeHandler handler = bridge.getHandler();
        return handler instanceof AccountHandler accountHandler ? accountHandler : null;
    }
}
