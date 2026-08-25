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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.EightSleepBindingConstants;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.api.TokenManager;
import org.openhab.binding.eightsleep.internal.discovery.BedSideDiscoveryService;
import org.openhab.binding.eightsleep.internal.model.BaseData;
import org.openhab.binding.eightsleep.internal.model.AccountConfigParser;
import org.openhab.binding.eightsleep.internal.model.UserDataCache;
import org.openhab.binding.eightsleep.internal.polling.AccountPoller;
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.openhab.binding.eightsleep.internal.model.TrendParser;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AccountHandler} manages authentication against the Eight Sleep cloud,
 * polls device/user/base/speaker data and exposes it to the {@link BedSideHandler}s.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountHandler extends BaseBridgeHandler {

    private static final int TREND_LOOKBACK_DAYS = 3;
    private static final long AUTH_RETRY_SECONDS = 60;

    private final Logger logger = LoggerFactory.getLogger(AccountHandler.class);

    private @Nullable TokenManager tokenManager;
    private @Nullable EightSleepApiClient apiClient;

    private final List<ScheduledFuture<?>> pollJobs = new CopyOnWriteArrayList<>();
    private @Nullable AccountPoller poller;

    private synchronized AccountPoller getOrCreatePoller(EightSleepApiClient client, String devId) {
        // Rebuild the poller whenever it would reference a different client: after a
        // reconnect the old client's token context is obsolete and must not be reused.
        if (poller == null || poller.client() != client || !poller.deviceId().equals(devId)) {
            if (poller != null) {
                poller.close();
            }
            poller = new AccountPoller(client, devId, (k) -> userDataByUser.computeIfAbsent(k, key -> new UserDataCache()),
                    () -> speakerAvailable = true);
        }
        return poller;
    }

    /** Pending reconnect attempt; cancelled on dispose and before scheduling a new one. */
    private @Nullable ScheduledFuture<?> reconnectJob;
    /**
     * Generation counter bumped on every dispose()/reconnect cycle. Poll callbacks
     * capture it when scheduled and drop their results when it no longer matches,
     * so in-flight work cannot publish stale state after disposal or a reconnect.
     */
    private volatile long lifecycleGeneration;

    // cached state, read by bed side handlers
    private volatile @Nullable DeviceData deviceData;
    private volatile boolean hasBase;
    private volatile boolean speakerAvailable;

    private final Map<String, UserDataCache> userDataByUser = new ConcurrentHashMap<>();
    /** userId -> side of the single bedSide thing registered for it (1:1 model). */
    private final Map<String, String> sideByUserId = new ConcurrentHashMap<>();
    private volatile @Nullable String deviceId;

    public AccountHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        AccountConfiguration config = getConfigAs(AccountConfiguration.class);

        if (config.username.isBlank() || config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/account.status.missing-credentials");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        // The connection program loop: authenticate -> resolve device -> start polling.
        // Each phase is a named step below; every failure path funnels into
        // scheduleReconnect so the loop restarts until the bridge is disposed.
        scheduler.execute(() -> connect(config));
    }

    /**
     * Connection lifecycle: build clients, authenticate, pick the pod and start
     * the poll jobs. Runs on the scheduler but never blocks: authentication and
     * pod resolution are composed asynchronously; every failure path funnels into
     * {@link #scheduleReconnect} so the loop restarts until the bridge is disposed.
     */
    private void connect(AccountConfiguration config) {
        TokenManager localTokenManager = new TokenManager(config.username, config.password,
                AccountConfigParser.emptyToNull(config.clientId), AccountConfigParser.emptyToNull(config.clientSecret));
        EightSleepApiClient localApiClient = new EightSleepApiClient(localTokenManager);

        localTokenManager.getAccessTokenAsync().whenComplete((token, authFailure) -> {
            if (authFailure != null) {
                Throwable cause = authFailure.getCause() != null ? authFailure.getCause() : authFailure;
                logger.debug("Eight Sleep authentication failed: {}", cause.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, cause.getMessage());
                scheduleReconnect(config);
                return;
            }
            logger.debug("Eight Sleep authenticated");
            this.tokenManager = localTokenManager;
            this.apiClient = localApiClient;

            // resolve the pod (device id) to bind, then start polling
            localApiClient.getHouseholdDevices().thenAccept(devices -> {
                if (devices.isEmpty()) {
                    // A transient API hiccup can return an empty household - retry like any other
                    // failure instead of latching a terminal CONFIGURATION_ERROR.
                    logger.debug("No Eight Sleep devices found for this account (yet); retrying");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "@text/account.status.no-devices");
                    scheduleReconnect(config);
                    return;
                }
                String devId = chooseDeviceId(devices, config.deviceId, logger);
                deviceId = devId;

                Map<String, String> properties = new HashMap<>(thing.getProperties());
                properties.put(EightSleepBindingConstants.CONFIG_USERNAME, config.username);
                for (Map.Entry<String, String> entry : devices.entrySet()) {
                    properties.put("device." + entry.getKey(), entry.getValue());
                }
                updateProperties(properties);

                // start the poll jobs and go online
                startPolling(config, localApiClient, devId);
                updateStatus(ThingStatus.ONLINE);
            }).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                logger.debug("Failed to initialize Eight Sleep account: {}", cause.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, cause.getMessage());
                scheduleReconnect(config);
                return null;
            });
        });
    }

    /**
     * Prefers the configured deviceId when it belongs to this account; otherwise the
     * first device in sorted order so multi-pod accounts get a stable choice across
     * restarts. Static and unit-testable.
     */
    static String chooseDeviceId(java.util.Map<String, String> devices, @Nullable String configured,
            org.slf4j.Logger logger) {
        String configuredTrimmed = configured != null ? configured.trim() : "";
        if (!configuredTrimmed.isEmpty()) {
            if (devices.containsKey(configuredTrimmed)) {
                return configuredTrimmed;
            }
            String fallback = devices.keySet().stream().sorted().findFirst().orElseThrow();
            logger.warn("Configured deviceId '{}' not found for this account; using '{}'. Known devices: {}",
                    configuredTrimmed, fallback, devices);
            return fallback;
        }
        return devices.keySet().stream().sorted().findFirst().orElseThrow();
    }

    private synchronized void startPolling(AccountConfiguration config, EightSleepApiClient client, String devId) {
        stopPolling();
        long generation = lifecycleGeneration;

        long deviceInterval = AccountConfigParser.clampInterval(config.deviceRefreshInterval, 15, 600);
        long userInterval = AccountConfigParser.clampInterval(config.userRefreshInterval, 15, 600);
        long baseInterval = AccountConfigParser.clampInterval(config.baseRefreshInterval, 30, 900);

        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> pollDeviceData(client, devId, generation), 0,
                deviceInterval, TimeUnit.SECONDS));
        AccountPoller poller = getOrCreatePoller(client, devId);
        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> {
            if (generation == lifecycleGeneration) {
                poller.pollUserData(TREND_LOOKBACK_DAYS);
            }
        }, 0, userInterval, TimeUnit.SECONDS));
        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> {
            if (generation == lifecycleGeneration) {
                pollBaseData(client);
            }
        }, baseInterval, baseInterval, TimeUnit.SECONDS));
        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> {
            if (generation == lifecycleGeneration) {
                poller.pollAwayState();
            }
        }, 2, deviceInterval, TimeUnit.SECONDS));

        // The fixed-delay jobs already fire immediately (initial delay 0/2s); no
        // separate synchronous first run - that used to block the scheduler thread
        // for up to a full HTTP round trip.
    }

    private synchronized void stopPolling() {
        for (ScheduledFuture<?> job : pollJobs) {
            if (job != null && !job.isCancelled()) {
                job.cancel(true);
            }
        }
        pollJobs.clear();
    }

    private void pollDeviceData(EightSleepApiClient client, String devId, long generation) {
        try {
            DeviceData data = EightSleepApiClient.join(client.getDeviceData(devId));
            if (generation != lifecycleGeneration) {
                // Handler was disposed or reconnected while this poll was in flight:
                // the result is stale and must not be published.
                logger.debug("Discarding device poll result from a superseded session");
                return;
            }
            deviceData = data;
            hasBase = data.hasBase();
            if (!speakerAvailable && data.hasSpeaker()) {
                speakerAvailable = true;
            }
            updateStatus(ThingStatus.ONLINE);
        } catch (ApiException e) {
            if (generation == lifecycleGeneration) {
                handlePollFailure("device", e);
            } else {
                logger.debug("Ignoring device poll failure from a superseded session: {}", e.getMessage());
            }
        }
    }


    /**
     * Resolves the configured user-data poll interval (clamped like startPolling),
     * so consumers can derive data-staleness thresholds from the same cadence.
     */
    public long userRefreshIntervalSeconds() {
        return AccountConfigParser.clampInterval(getConfigAs(AccountConfiguration.class).userRefreshInterval, 15, 600);
    }

    private void pollBaseData(EightSleepApiClient client) {
        for (String userId : Set.copyOf(sideByUserId.keySet())) {
            client.getBaseData(userId).thenAccept(base -> {
                UserDataCache data = userDataByUser.computeIfAbsent(userId, k -> new UserDataCache());
                data.baseData = base;
            }).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                logger.debug("Failed to refresh base data for user {}: {}", userId, cause.getMessage());
                return null;
            });
        }
    }

    private void handlePollFailure(String what, ApiException e) {
        if (e.isUnauthorized()) {
            logger.debug("Unauthorized during {} poll, forcing reconnect", what);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "@text/account.status.auth-expired");
            scheduleReconnect(getConfigAs(AccountConfiguration.class));
        } else {
            logger.debug("{} poll failed: {}", what, e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private synchronized void scheduleReconnect(AccountConfiguration config) {
        cancelReconnect();
        reconnectJob = scheduler.schedule(() -> {
            if (getThing().getStatus() != ThingStatus.REMOVING) {
                logger.debug("Retrying Eight Sleep connection");
                connect(config);
            }
        }, AUTH_RETRY_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelReconnect() {
        ScheduledFuture<?> job = reconnectJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(false);
        }
        reconnectJob = null;
    }

    @Override
    public void dispose() {
        // Invalidate any poll callback still in flight before cancelling the jobs:
        // a task already running sees the new generation and drops its result.
        lifecycleGeneration++;
        cancelReconnect();
        stopPolling();
        super.dispose();
    }

    @Override
    public void thingUpdated(Thing thing) {
        super.thingUpdated(thing);
        // Apply configuration changes to the active session instead of waiting for
        // the next reactivation: rebuild clients and restart the poll jobs.
        AccountConfiguration config = getConfigAs(AccountConfiguration.class);
        if (config.username.isBlank() || config.password.isBlank()) {
            lifecycleGeneration++;
            stopPolling();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/account.status.missing-credentials");
            return;
        }
        scheduler.execute(() -> connect(config));
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The bridge itself has no channels; commands belong to the bed sides.
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(BedSideDiscoveryService.class);
    }

    /**
     * Called by the discovery service when it is deactivated.
     */
    public void unregisterDiscoveryService(BedSideDiscoveryService service) {
        // nothing to clean up; the framework manages the lifecycle
    }

    // ==================== accessors for bed side handlers ====================

    public @Nullable EightSleepApiClient getApiClient() {
        return apiClient;
    }

    public @Nullable DeviceData getDeviceData() {
        return deviceData;
    }

    public @Nullable UserDataCache getUserData(String userId) {
        return userDataByUser.get(userId);
    }

    /**
     * Returns the cache entry for a user, creating it when absent. The poll fan-out
     * uses this on every observation write; commands deliberately do not.
     */
    public UserDataCache getUserDataOrCreate(String userId) {
        return userDataByUser.computeIfAbsent(userId, k -> new UserDataCache());
    }

    public @Nullable String getDeviceId() {
        return deviceId;
    }

    public boolean isHasBase() {
        return hasBase;
    }

    public boolean isSpeakerAvailable() {
        return speakerAvailable;
    }

    /**
     * Registers the single bedSide thing for a user. Under the 1:1 model each user
     * owns at most one thing (left, right or solo).
     *
     * @return true when this registration is new (first claim of the user id)
     */
    public boolean registerBedSide(String userId, String side) {
        boolean first = sideByUserId.putIfAbsent(userId, side) == null;
        if (first) {
            AccountPoller p = poller;
            if (p != null) {
                p.register(userId);
            }
        }
        return first;
    }

    /**
     * Unregisters the bedSide thing for a user: polling and cached data are dropped
     * immediately.
     */
    public void unregisterBedSide(String userId) {
        if (sideByUserId.remove(userId) != null) {
            userDataByUser.remove(userId);
            AccountPoller p = poller;
            if (p != null) {
                p.unregister(userId);
            }
        }
    }

        /**
     * Returns the temperature unit as "c"/"f" based on the bridge configuration.
     */
    public char getTemperatureUnit(char fallback) {
        return AccountConfigParser.parseTemperatureUnit(getConfigAs(AccountConfiguration.class).temperatureUnit, fallback);
    }

}
