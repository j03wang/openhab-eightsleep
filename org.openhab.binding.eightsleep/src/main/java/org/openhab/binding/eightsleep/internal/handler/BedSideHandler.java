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
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.model.BaseData;
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.binding.eightsleep.internal.model.HeatingLevelConversion;
import org.openhab.binding.eightsleep.internal.handler.LastWriteWins;
import org.openhab.binding.eightsleep.internal.model.TrendParser;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;
import org.openhab.binding.eightsleep.internal.sleep.DataFreshness;
import org.openhab.binding.eightsleep.internal.sleep.SleepSession;
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
    private final java.util.concurrent.ConcurrentHashMap<String, LastWriteWins.CommandedValue> commanded =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** alarmId -> commanded enabled state, stamped when the command was sent. */
    private final java.util.concurrent.ConcurrentHashMap<String, LastWriteWins.CommandedValue> commandedAlarms =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Last commanded/known raw heating level target, kept for the off-state fallback.
     */
    private @Nullable Double lastKnownTargetLevel;

    private boolean syncStartedLogged;
    private final java.util.List<String> syncNotes = new ArrayList<>();
    private volatile String lastSyncSummary = "";

    private void note(String label, @Nullable Object value) {
        syncNotes.add(label + "=" + (value == null ? "<null>" : value));
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
        BedSideCommands.Context ctx = new BedSideCommands.Context(client, account, userId, side, soloBed,
                account.getTemperatureUnit('c') == 'f', commanded, commandedAlarms,
                () -> scheduleRefresh());

        try {
            switch (channelId) {
                case CHANNEL_TARGET_TEMPERATURE -> BedSideCommands.targetTemperature(ctx, command);
                case CHANNEL_SIDE_POWER -> BedSideCommands.sidePower(ctx, command);
                case CHANNEL_HEAD_ANGLE -> BedSideCommands.baseAngle(ctx, command, true);
                case CHANNEL_FEET_ANGLE -> BedSideCommands.baseAngle(ctx, command, false);
                case CHANNEL_BASE_PRESET -> BedSideCommands.basePreset(ctx, command);
                case CHANNEL_PILLOW_POWER -> BedSideCommands.pillowPower(ctx, command);
                case CHANNEL_PILLOW_TARGET_TEMPERATURE -> BedSideCommands.pillowTargetTemperature(ctx, command);
                case CHANNEL_ALARM_ENABLED -> BedSideCommands.alarmEnabled(ctx, command);
                case CHANNEL_ALARM_TIME -> BedSideCommands.alarmTime(ctx, command);
                case CHANNEL_DISMISS_ALARM -> {
                    if (command == OnOffType.ON) {
                        BedSideCommands.dismissAlarm(ctx);
                    }
                }
                case CHANNEL_SNOOZE_ALARM -> {
                    if (command == OnOffType.ON) {
                        BedSideCommands.snoozeAlarm(ctx);
                    }
                }
                case CHANNEL_AWAY_MODE -> BedSideCommands.awayMode(ctx, command);
                case CHANNEL_PRIME -> {
                    if (command == OnOffType.ON) {
                        BedSideCommands.primePod(ctx);
                    }
                }
                case CHANNEL_LED_BRIGHTNESS -> BedSideCommands.ledBrightness(ctx, command);
                case CHANNEL_SNORE_MITIGATION -> logger.debug("Snore mitigation is read-only");
                default -> logger.warn("Unsupported channel {} for command {}", channelUID, command);
            }
        } catch (Exception e) {
            logger.warn("Failed to execute command {} on {}: {}", command, channelUID, e.getMessage());
        }
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
        UserDataCache userData = account.getUserData(userId);

        BedSideChannelSync.Result result;
        try {
            result = BedSideChannelSync.compute(deviceData, userData, side,
                    account.getTemperatureUnit('c') == 'f', account.isAwayPolledOnce(),
                    account.userRefreshIntervalSeconds(), Instant.now(), java.time.ZoneId.systemDefault(),
                    commanded.get(CHANNEL_SIDE_POWER),
                    alarmEnabledCommandStamp(account), lastKnownTargetLevel);
        } catch (RuntimeException e) {
            // A payload quirk must not kill the periodic job; treat as stale data.
            logger.warn("Channel sync computation failed for user {} side '{}': {}", userId, side, e.getMessage(), e);
            return;
        }

        // --- thing status decision ---
        switch (result.statusAction) {
            case BRIDGE_OFFLINE -> {
                if (getThing().getStatusInfo().getStatusDetail() != ThingStatusDetail.BRIDGE_OFFLINE) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                }
                note("deviceData", false);
                logSyncSummary();
                return;
            }
            case USER_NOT_FOUND -> {
                // device data flows, but this user is never polled - almost certainly
                // a bad userId configuration, not a bridge problem
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/bedside.status.user-not-found");
                note("userData", false);
                logSyncSummary();
                return;
            }
            case STALE_DATA -> {
                if (getThing().getStatus() != ThingStatus.OFFLINE
                        || getThing().getStatusInfo().getStatusDetail() != ThingStatusDetail.COMMUNICATION_ERROR) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "@text/bedside.status.stale-data");
                }
            }
            case ONLINE -> {
                if (getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
            }
            case NONE -> {
                // leave untouched
            }
        }

        // --- apply channel updates ---
        for (BedSideChannelSync.ChannelUpdate update : result.updates) {
            updateState(update.channelUid(), update.state());
        }
        if (result.lastKnownTargetLevel != null) {
            lastKnownTargetLevel = result.lastKnownTargetLevel;
        }
        if (result.retireSidePowerCommand) {
            commanded.remove(CHANNEL_SIDE_POWER); // server confirmed
        }
        if (result.retireAlarmId != null) {
            commandedAlarms.remove(result.retireAlarmId); // server confirmed
        }
        if (result.targetLevelAbsent && userData != null && logger.isDebugEnabled()) {
            // Target key missing for this side: dump everything we know about the payload
            // once per poll so the real field names can be identified from the log.
            logger.debug("TargetHeatingLevel absent for side '{}' (expected while the bed is off); device json keys: {}",
                    side, deviceData != null ? deviceData.rawFieldNames : List.of());
        }
        logSyncSummary();
    }

    /** The pending command stamp for the currently selected alarm, if any. */
    private LastWriteWins.@Nullable CommandedValue alarmEnabledCommandStamp(AccountHandler account) {
        UserDataCache userData = account.getUserData(userId);
        if (userData == null) {
            return null;
        }
        Alarm nextAlarm = AlarmSelector.findTargetAlarm(userData, Instant.now(),
                java.time.ZoneId.systemDefault());
        return nextAlarm != null && nextAlarm.id != null ? commandedAlarms.get(nextAlarm.id) : null;
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


    private synchronized @Nullable AccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        BridgeHandler handler = bridge.getHandler();
        return handler instanceof AccountHandler accountHandler ? accountHandler : null;
    }
}