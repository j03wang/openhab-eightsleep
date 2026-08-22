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
    private static final long PRESENCE_FRESH_SECONDS = 600;

    private @Nullable ScheduledFuture<?> refreshJob;
    private String userId = "";
    private String side = "left";
    /** True when one user controls both zones ("Both"/solo bed). */
    private boolean soloBed;
    /** Timestamp until which commanded power state suppresses stale sync updates. */
    private volatile long sidePowerOverrideUntil;

    /** Grace period for the cloud to reflect a power command. */
    private static final long POWER_OVERRIDE_MILLIS = 15_000;

    /** Alarm id whose enabled state we commanded; trusted until the poll confirms. */
    private volatile @Nullable String pendingAlarmId;
    private volatile @Nullable Boolean pendingAlarmEnabled;
    private volatile long alarmOverrideUntil;

    /** Grace period for the cloud + one full alarms poll to reflect an update. */
    private static final long ALARM_OVERRIDE_MILLIS = 45_000;

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
        // Optimistic feedback: the cloud applies instantly, but the polled payload may
        // lag one cycle - don't let a stale sync flip the switch back in between.
        updateState(GROUP_DEVICE + "#" + CHANNEL_SIDE_POWER, OnOffType.from(turnOn));
        sidePowerOverrideUntil = System.currentTimeMillis() + POWER_OVERRIDE_MILLIS;
        CompletableFuture<Void> future = turnOn
                ? client.turnOnSide(userId)
                : client.turnOffSide(userId);
        future.thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
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
        angle = Math.max(0, Math.min(head ? 45 : 20, angle));

        AccountHandler account = getAccountHandler();
        AccountHandler.UserData data = account != null ? account.getUserData(userId) : null;
        BaseData.SideData baseSide = data != null ? data.getBaseSide(side) : null;
        int currentLeg = baseSide != null && baseSide.leg != null && baseSide.leg.currentAngle != null
                ? baseSide.leg.currentAngle : 0;
        int currentTorso = baseSide != null && baseSide.torso != null && baseSide.torso.currentAngle != null
                ? baseSide.torso.currentAngle : 0;

        int legAngle = head ? currentLeg : angle;
        int torsoAngle = head ? angle : currentTorso;
        AccountHandler acct = getAccountHandler();
        String devId = acct != null ? acct.getDeviceId() : null;
        if (devId == null) {
            logger.debug("No device id; cannot set base angle");
            return;
        }
        client.setBaseAngle(userId, devId, legAngle, torsoAngle).thenRun(this::scheduleRefresh)
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
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData) : null;
        if (alarm == null || alarm.id == null) {
            logger.debug("No upcoming alarm to toggle");
            return;
        }
        boolean enable = command == OnOffType.ON;
        // Optimistic feedback: the server takes a few seconds to apply; keep the switch
        // at the commanded value until a poll confirms.
        updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_ENABLED, OnOffType.from(enable));
        pendingAlarmId = alarm.id;
        pendingAlarmEnabled = enable;
        alarmOverrideUntil = System.currentTimeMillis() + ALARM_OVERRIDE_MILLIS;
        client.setAlarmEnabled(userId, alarm, enable).thenRun(this::scheduleRefresh)
                .exceptionally(this::logCommandFailure);
    }

    private void handleAlarmTime(EightSleepApiClient client, Command command) {
        Instant newTime = null;
        if (command instanceof DateTimeType dateTime) {
            newTime = dateTime.getInstant();
        } else if (command instanceof StringType string) {
            try {
                newTime = java.time.LocalTime.parse(string.toString().trim().substring(0, 8))
                        .atDate(java.time.LocalDate.now())
                        .atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (Exception e) {
                logger.warn("Cannot parse '{}' as an alarm time", string);
                return;
            }
        }
        if (newTime == null) {
            logger.warn("Unsupported command type {} for alarm time", command.getClass().getSimpleName());
            return;
        }

        AccountHandler.UserData userData = getUserData();
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData) : null;
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
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData) : null;
        if (alarm == null || alarm.id == null) {
            logger.debug("No upcoming alarm to dismiss");
            return;
        }
        client.dismissAlarm(userId, alarm.id).thenRun(this::scheduleRefresh).exceptionally(this::logCommandFailure);
    }

    private void handleSnoozeAlarm(EightSleepApiClient client) {
        AccountHandler.UserData userData = getUserData();
        EightSleepApiClient.Alarm alarm = userData != null ? findTargetAlarm(userData) : null;
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
        JsonObject temp = data.temperature;
        return TrendParser.getDouble(TrendParser.getObject(temp, "smart"), "bedTimeLevel");
    }

    private EightSleepApiClient.PillowData getPillowData() {
        AccountHandler account = getAccountHandler();
        AccountHandler.UserData data = account != null ? account.getUserData(userId) : null;
        return data != null ? data.pillowData : null;
    }

    /**
     * Computes when an alarm will next fire, WITHOUT relying on the server's
     * nextTimestamp field (which goes stale or null for disabled alarms).
     *
     * Repeating alarms: derived from {@code time} + {@code repeat.weekDays} in the
     * local timezone. A repeat flag with no active weekday is treated as daily.
     * One-shot alarms (repeat disabled): the server's nextTimestamp is used when
     * present, since a bare HH:mm:ss carries no date.
     */
    private static @Nullable Instant computeNextRun(EightSleepApiClient.Alarm alarm) {
        if (alarm == null || alarm.time == null || alarm.time.isBlank()) {
            return null;
        }
        java.time.LocalTime time;
        try {
            time = java.time.LocalTime.parse(alarm.time.trim().substring(0, 8));
        } catch (Exception e) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();

        boolean repeating = Boolean.TRUE.equals(
                alarm.repeat != null ? alarm.repeat.enabled : null);
        Map<String, Boolean> weekDays = alarm.repeat != null ? alarm.repeat.weekDays : null;

        if (repeating) {
            boolean[] mask = new boolean[7]; // Mon..Sun
            boolean anyDay = false;
            if (weekDays != null) {
                String[] names = { "monday", "tuesday", "wednesday", "thursday", "friday",
                        "saturday", "sunday" };
                for (int i = 0; i < names.length; i++) {
                    Boolean active = weekDays.get(names[i]);
                    if (Boolean.TRUE.equals(active)) {
                        mask[i] = true;
                        anyDay = true;
                    }
                }
            }
            if (!anyDay) {
                java.util.Arrays.fill(mask, true); // repeat enabled, no days = daily
            }
            for (int addDays = 0; addDays < 8; addDays++) {
                java.time.LocalDate date = java.time.LocalDate.now(zone).plusDays(addDays);
                int idx = date.getDayOfWeek().getValue() - 1; // Monday = 0
                if (!mask[idx]) {
                    continue;
                }
                Instant candidate = date.atTime(time).atZone(zone).toInstant();
                if (!candidate.isBefore(now)) {
                    return candidate;
                }
            }
            return null;
        }

        // one-shot: fall back to the server-provided timestamp
        return TrendParser.parseTimestamp(alarm.nextTimestamp);
    }

    /**
     * Resolves which alarm the alarm channels represent: the one that will fire
     * soonest by our own computation, disabled alarms included. Ties break on id.
     */
    private EightSleepApiClient.Alarm findTargetAlarm(AccountHandler.UserData userData) {
        EightSleepApiClient.Alarm target = null;
        Instant targetRun = null;
        for (EightSleepApiClient.Alarm alarm : userData.alarms) {
            Instant run = computeNextRun(alarm);
            if (run == null) {
                continue;
            }
            boolean closer = targetRun == null || run.isBefore(targetRun)
                    || (run.equals(targetRun) && target != null
                            && alarm.id != null && target.id != null
                            && alarm.id.compareTo(target.id) < 0);
            if (closer) {
                target = alarm;
                targetRun = run;
            }
        }
        return target;
    }

    /**
     * Publishes the computed next-run instant for the target alarm.
     */

    /**
     * Parses an "HH:MM:SS" alarm time-of-day into today's Instant in the system zone.
     */
    private Instant parseAlarmTimeOfDay(@Nullable String timeOfDay) {
        if (timeOfDay == null || timeOfDay.isBlank()) {
            return null;
        }
        try {
            java.time.LocalTime time = java.time.LocalTime.parse(timeOfDay.trim().substring(0, 8));
            return java.time.LocalDate.now().atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            logger.debug("Cannot parse alarm time '{}': {}", timeOfDay, e.getMessage());
            return null;
        }
    }

    /**
     * Parses a temperature command into a double value, or NaN when unsupported.
     */
    private double parseTemperature(Command command) {
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
            logger.warn("Incompatible unit {} for temperature command", unit);
            return Double.NaN;
        }
        if (command instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        if (command instanceof StringType string) {
            try {
                return Double.parseDouble(string.toString());
            } catch (NumberFormatException e) {
                logger.warn("Cannot parse '{}' as temperature", string);
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
        if (!syncStartedLogged) {
            syncStartedLogged = true;
            logger.debug("Channel sync active for user {} side '{}'; thing status {}",
                    userId, side, getThing().getStatus());
        }
        DeviceData deviceData = account.getDeviceData();
        AccountHandler.UserData userData = account.getUserData(userId);

        if (deviceData == null || userData == null) {
            if (getThing().getStatusInfo().getStatusDetail() != ThingStatusDetail.BRIDGE_OFFLINE) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            }
            note("deviceData", deviceData != null);
            note("userData", userData != null);
            logSyncSummary();
            return;
        }
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
        syncNotes.clear();

        boolean fahrenheit = account.getTemperatureUnit('c') == 'f';
        Double heatingLevelRaw = deviceData.getHeatingLevel(side);
        Double targetLevelRaw = deviceData.getTargetHeatingLevel(side);
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
            double temp = HeatingLevelConversion.levelToTemperature(heatingLevelRaw, fahrenheit);
            updateState(GROUP_CURRENT + "#" + CHANNEL_BED_TEMPERATURE, toQuantity(temp, fahrenheit));
        }
        if (targetLevelRaw != null) {
            // Upstream: when the bed is off the API reports a meaningless 0 (27 C);
            // keep the last meaningful target instead of flipping to it.
            boolean meaningful = targetLevelRaw != 0
                    || Boolean.TRUE.equals("right".equals(side) ? deviceData.rightNowHeating : deviceData.leftNowHeating);
            if (meaningful || lastKnownTargetLevel == null) {
                lastKnownTargetLevel = targetLevelRaw;
            }
            double shownLevel = targetLevelRaw == 0 && lastKnownTargetLevel != 0.0
                    ? lastKnownTargetLevel : targetLevelRaw;
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
        Boolean nowHeating = "right".equals(side) ? deviceData.rightNowHeating : deviceData.leftNowHeating;
        if (nowHeating != null && targetLevelRaw != null) {
            String state = nowHeating ? (targetLevelRaw > 0 ? "heating" : "cooling") : "idle";
            updateState(GROUP_DEVICE + "#" + CHANNEL_HEATING_STATE, new StringType(state));
        }

        // --- sleep session channels (raw trends JSON, parsed defensively) ---
        // Day-level fields (score, presenceStart/End, processing, tnt, durations,
        // sleepQualityScore) sit on each "day"; only timeseries/stages are session-level.
        TrendParser trends = userData.getTrends();
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
                Boolean processing = TrendParser.getBoolean(currentDay, "processing");
                if (processing != null) {
                    updateState(GROUP_CURRENT + "#" + CHANNEL_SLEEP_STAGE,
                            new StringType(Boolean.TRUE.equals(processing) ? "inProgress" : "complete"));
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
                putLatestCelsius(currentSession, "tempBedC", GROUP_CURRENT, CHANNEL_BED_TEMPERATURE);
                putLatestCelsius(currentSession, "tempRoomC", GROUP_DEVICE, CHANNEL_ROOM_TEMPERATURE);
                putLatest(currentSession, "heartRate", GROUP_CURRENT, CHANNEL_HEART_RATE);
                putLatest(currentSession, "respiratoryRate", GROUP_CURRENT, CHANNEL_RESPIRATORY_RATE);
                // Bed presence: fresh heart rate data confirms presence
                updateState(GROUP_BASE + "#" + CHANNEL_BED_PRESENCE, OnOffType.from(isPresent(currentSession)));
            } else {
                updateState(GROUP_BASE + "#" + CHANNEL_BED_PRESENCE, OnOffType.OFF);
            }

            // last completed sleep: the previous day
            if (previousDay != null) {
                putDecimal(GROUP_LAST_SLEEP, CHANNEL_SLEEP_SCORE, TrendParser.getDouble(previousDay, "score"));
                putDecimal(GROUP_LAST_SLEEP, CHANNEL_FITNESS_SCORE, TrendParser.getDouble(previousDay, "score"));
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
            Instant lastPrime = parseTimestamp(deviceData.lastPrime);
            if (lastPrime != null) {
                updateState(GROUP_DEVICE + "#" + CHANNEL_LAST_PRIME, new DateTimeType(lastPrime));
            }
        }

        // --- away mode state ---
        // Live rule (verified via captures): away = listed in awaySides AND removed
        // from the side slots. UNDEF only before the first successful away poll.
        if (!account.isAwayPolledOnce()) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_AWAY_MODE, UnDefType.UNDEF);
        } else {
            updateState(GROUP_DEVICE + "#" + CHANNEL_AWAY_MODE, OnOffType.from(userData.awayMode));
        }

        // --- side power (live) ---
        // Authoritative source: /temperature currentState.type. The heating LEVEL
        // persists while the bed is off, so it must never drive this channel.
        String powerType = TrendParser.getString(
                TrendParser.getObject(userData.temperature, "currentState"), "type");
        note("powerType", powerType == null ? "<absent>" : powerType);
        boolean withinOverride = System.currentTimeMillis() < sidePowerOverrideUntil;
        if (withinOverride) {
            // a power command is in flight; keep the optimistic value
        } else if (powerType != null) {
            updateState(GROUP_DEVICE + "#" + CHANNEL_SIDE_POWER, OnOffType.from(!"off".equalsIgnoreCase(powerType)));
        } else if (targetLevelRaw != null) {
            // no temperature payload yet - fall back to the target level heuristic
            updateState(GROUP_DEVICE + "#" + CHANNEL_SIDE_POWER, OnOffType.from(targetLevelRaw != 0));
        }

        // --- alarm state ---
        EightSleepApiClient.Alarm nextAlarm = findTargetAlarm(userData);
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

            // While an alarm toggle is in flight, trust our commanded value - the polled
            // list lags a few seconds and would otherwise bounce the switch back.
            String pendingId = pendingAlarmId;
            boolean inFlight = System.currentTimeMillis() < alarmOverrideUntil
                    && nextAlarm.id != null && nextAlarm.id.equals(pendingId)
                    && pendingAlarmEnabled != null;
            if (inFlight) {
                updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_ENABLED,
                        OnOffType.from(pendingAlarmEnabled));
            } else if (nextAlarm.enabled != null) {
                updateState(GROUP_CURRENT + "#" + CHANNEL_ALARM_ENABLED,
                        OnOffType.from(nextAlarm.enabled));
            }
        }


        // Publish the compact sync summary (INFO, only when something changed)
        logSyncSummary();
    }

    private void putScore(String group, String channel, @Nullable Double score) {
        if (score != null) {
            updateState(group + "#" + channel, new DecimalType(score));
        }
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
     * Publishes the latest value of a temperature series as a Celsius quantity
     * (the API stores these timeseries in celsius).
     */
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
     * Bed presence detection: heart rate data younger than {@link #PRESENCE_FRESH_SECONDS}
     * confirms presence.
     */
    private boolean isPresent(@Nullable JsonObject session) {
        JsonObject ts = TrendParser.getObject(session, "timeseries");
        JsonElement el = ts != null ? ts.get("heartRate") : null;
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() == 0) {
            return false;
        }
        JsonElement entry = el.getAsJsonArray().get(el.getAsJsonArray().size() - 1);
        if (!entry.isJsonArray() || entry.getAsJsonArray().size() < 1) {
            return false;
        }
        Instant heartBeatTime = TrendParser.parseTimestamp(entry.getAsJsonArray().get(0).getAsString());
        return heartBeatTime != null
                && Duration.between(heartBeatTime, Instant.now()).abs().getSeconds() < PRESENCE_FRESH_SECONDS;
    }

    private QuantityType<?> toQuantity(double temperature, boolean fahrenheit) {
        return new QuantityType<>(temperature, fahrenheit ? ImperialUnits.FAHRENHEIT : SIUnits.CELSIUS);
    }

    /**
     * Logs one compact INFO line per sync describing every channel decision, so state
     * questions can be answered from the log without TRACE level.
     */
    private void logSyncSummary() {
        String summary = String.join(", ", syncNotes);
        if (!summary.isBlank() && !summary.equals(lastSyncSummary)) {
            lastSyncSummary = summary;
            logger.debug("Sync for user {} side {}: {}", userId, side, summary);
        }
    }

    /** Whether the bridge is configured to interpret plain numbers as fahrenheit. */
    private boolean fahrenheitDisplay() {
        AccountHandler account = getAccountHandler();
        return account != null && account.getTemperatureUnit('c') == 'f';
    }

    /**
     * Parses an ISO-8601 timestamp (e.g. lastPrime) tolerating a trailing Z or offset.
     */
    private @Nullable Instant parseTimestamp(@Nullable String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(value.trim()).toInstant();
            } catch (Exception e2) {
                logger.debug("Cannot parse timestamp '{}': {}", value, e2.getMessage());
                return null;
            }
        }
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
