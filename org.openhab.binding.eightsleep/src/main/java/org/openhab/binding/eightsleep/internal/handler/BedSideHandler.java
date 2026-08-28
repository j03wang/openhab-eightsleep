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

import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.alarm.AlarmSelector;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.command.BedSideCommands;
import org.openhab.binding.eightsleep.internal.command.CommandState;
import org.openhab.binding.eightsleep.internal.config.BedSideConfiguration;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
import org.openhab.binding.eightsleep.internal.sync.BedSideChannelSync;
import org.openhab.binding.eightsleep.internal.sync.LastWriteWins;
import org.openhab.binding.eightsleep.internal.sync.SyncResult;
import org.openhab.binding.eightsleep.internal.sync.SyncResult.ChannelUpdate;
import org.openhab.core.i18n.TimeZoneProvider;
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
    private final Clock clock;
    private final BedSideCommands commands;
    private final BedSideChannelSync channelSync;

    private @Nullable ScheduledFuture<?> refreshJob;
    private String userId = "";
    private @Nullable String registeredUserId;
    private @Nullable AccountHandler registeredAccountHandler;
    private BedSide side = BedSide.LEFT;
    /**
     * Last commanded value per channel, kept so the sync loop can do last-write-wins
     * merging against polled data: whichever was observed more recently wins. Entries
     * are never expired - a fresh poll simply arrives with a newer timestamp.
     */
    private final CommandState commandState;

    private boolean syncStartedLogged;
    private final java.util.List<String> syncNotes = new ArrayList<>();
    private volatile String lastSyncSummary = "";
    /** Set in dispose; in-flight sync callbacks check it before touching the thing. */
    private volatile boolean disposed;

    private void note(String label, @Nullable Object value) {
        syncNotes.add(label + "=" + (value == null ? "<null>" : value));
    }

    /**
     * Creates a bed-side handler with injectable command and synchronization collaborators.
     *
     * @param thing the bed-side thing
     * @param timeZoneProvider the openHAB time-zone provider
     * @param clock the clock used for command and synchronization timestamps
     * @param commands the command dispatcher
     * @param channelSync the channel synchronization collaborator
     */
    public BedSideHandler(Thing thing, TimeZoneProvider timeZoneProvider, Clock clock, BedSideCommands commands,
            BedSideChannelSync channelSync) {
        super(thing);
        this.timeZoneProvider = timeZoneProvider;
        this.clock = clock;
        this.commands = commands;
        this.channelSync = channelSync;
        commandState = new CommandState(clock);
    }

    @Override
    public void initialize() {
        disposed = false;
        applyConfiguration(false);
    }

    private void applyConfiguration(boolean syncImmediately) {
        BedSideConfiguration config = getConfigAs(BedSideConfiguration.class);
        if (config.userId.isBlank()) {
            releaseRegistration();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/bedside.status.missing-user");
            return;
        }
        String newUserId = config.userId.trim();
        BedSide configuredSide = BedSide.fromString(config.label.isBlank() ? "left" : config.label.trim());
        if (configuredSide == null) {
            releaseRegistration();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid bed side");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);

        AccountHandler account = getAccountHandler();
        if (account == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        // Register even when the bridge is still connecting: the poll loop only reports
        // users that are registered, so late registration would silently skip this sleeper.
        String registered = registeredUserId;
        AccountHandler registeredAccount = registeredAccountHandler;
        if (registered != null && (!registered.equals(newUserId) || registeredAccount != account)) {
            if (registeredAccount != null) {
                registeredAccount.unregisterBedSide(registered);
            }
            registeredUserId = null;
            registeredAccountHandler = null;
        }
        if (registeredUserId == null) {
            if (!account.registerBedSide(newUserId, configuredSide)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/bedside.status.duplicate-user");
                return;
            }
            registeredUserId = newUserId;
            registeredAccountHandler = account;
        } else {
            account.updateRegisteredSide(newUserId, configuredSide);
        }
        userId = newUserId;
        side = configuredSide;
        // Always run the channel-sync job; it reports OFFLINE(BRIDGE_OFFLINE) until the
        // bridge has data and flips ONLINE by itself once polls succeed.
        startRefreshJob(account);
        if (syncImmediately) {
            scheduleOneShotSync(account, 0);
        }
    }

    private void releaseRegistration() {
        stopRefreshJob();
        String registered = registeredUserId;
        AccountHandler registeredAccount = registeredAccountHandler;
        if (registered != null && registeredAccount != null) {
            registeredAccount.unregisterBedSide(registered);
        }
        registeredUserId = null;
        registeredAccountHandler = null;
    }

    @Override
    public void thingUpdated(Thing thing) {
        super.thingUpdated(thing);
        applyConfiguration(true);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatus) {
        super.bridgeStatusChanged(bridgeStatus);
        if (bridgeStatus.getStatus() == ThingStatus.ONLINE && userId != null && !userId.isBlank()) {
            applyConfiguration(false);
            AccountHandler account = getAccountHandler();
            if (account != null && registeredAccountHandler == account) {
                scheduleOneShotSync(account, 2);
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

    /** Tracks one-shot syncs so they can be cancelled on dispose like the periodic job. */
    private final java.util.List<ScheduledFuture<?>> oneShotJobs = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void scheduleOneShotSync(AccountHandler account, long delaySeconds) {
        // Capture the future via an array: the lambda runs later, after schedule() returns.
        ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];
        ScheduledFuture<?> job = scheduler.schedule(() -> {
            oneShotJobs.remove(handle[0]);
            updateChannelsFromCache(account);
        }, delaySeconds, TimeUnit.SECONDS);
        handle[0] = job;
        oneShotJobs.add(job);
    }

    @Override
    public void dispose() {
        disposed = true;
        releaseRegistration();
        stopRefreshJob();
        for (ScheduledFuture<?> job : oneShotJobs) {
            job.cancel(false);
        }
        oneShotJobs.clear();
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        AccountHandler account = getAccountHandler();
        EightSleepService service = account != null ? account.getService() : null;
        if (service == null || account == null) {
            logger.debug("No API service available; ignoring command {}", command);
            return;
        }
        if (command instanceof RefreshType) {
            updateChannelsFromCache(account);
            return;
        }

        BedSideCommands.Context ctx = new BedSideCommands.Context(service, userId, side,
                account.getTemperatureUnit('c') == 'f', timeZoneProvider.getTimeZone(), account.getDeviceId(),
                account.getUserSnapshot(userId), commandState, () -> scheduleRefresh());

        try {
            commands.dispatch(channelUID, command, ctx);
        } catch (Exception e) {
            logger.warn("Failed to execute command {} on {}: {}", command, channelUID, e.getMessage());
        }
    }

    private void scheduleRefresh() {
        AccountHandler account = getAccountHandler();
        if (account != null) {
            scheduleOneShotSync(account, 3);
        }
    }

    /**
     * Pushes the cached bridge data into the channels of this bed side.
     */
    protected void updateChannelsFromCache(AccountHandler account) {
        if (disposed) {
            return;
        }
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
            logger.debug("Channel sync active for user {} side '{}'; thing status {}", userId, side,
                    getThing().getStatus());
        }
        DeviceState deviceState = account.getDeviceState();
        UserDataSnapshot userData = account.getUserSnapshot(userId);

        SyncResult result;
        try {
            result = channelSync.compute(deviceState, userData, side, account.getTemperatureUnit('c') == 'f',
                    account.userRefreshIntervalSeconds(), clock.instant(), timeZoneProvider.getTimeZone(),
                    commandState.channel(CHANNEL_SIDE_POWER), alarmEnabledCommandStamp(account),
                    commandState.channel(CHANNEL_AWAY_MODE), commandState.lastKnownTargetLevel());
        } catch (RuntimeException e) {
            // A payload quirk must not kill the periodic job; treat as stale data.
            logger.warn("Channel sync computation failed for user {} side '{}': {}", userId, side, e.getMessage(), e);
            return;
        }

        // --- thing status decision ---
        switch (result.statusAction()) {
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
        for (ChannelUpdate update : result.updates()) {
            updateState(update.channelUid(), update.state());
        }
        if (result.lastKnownTargetLevel() != null) {
            commandState.setLastKnownTargetLevel(result.lastKnownTargetLevel());
        }
        if (result.retireSidePowerCommand()) {
            commandState.retireChannel(CHANNEL_SIDE_POWER);
        }
        if (result.retireAwayModeCommand()) {
            commandState.retireChannel(CHANNEL_AWAY_MODE);
        }
        if (result.retireAlarmId() != null) {
            commandState.retireAlarm(result.retireAlarmId());
        }
        if (result.targetLevelAbsent() && userData != null && logger.isDebugEnabled()) {
            logger.debug("TargetHeatingLevel absent for side '{}' (expected while the bed is off)", side);
        }
        logSyncSummary();
    }

    /** The pending command stamp for the currently selected alarm, if any. */
    private LastWriteWins.@Nullable CommandedValue alarmEnabledCommandStamp(AccountHandler account) {
        UserDataSnapshot userData = account.getUserSnapshot(userId);
        if (userData == null) {
            return null;
        }
        Alarm nextAlarm = AlarmSelector.findTargetAlarm(userData.alarms(), clock.instant(),
                timeZoneProvider.getTimeZone());
        return nextAlarm != null && nextAlarm.id() != null ? commandState.alarm(nextAlarm.id()) : null;
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
