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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.EightSleepBindingConstants;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.api.TokenManager;
import org.openhab.binding.eightsleep.internal.config.AccountConfiguration;
import org.openhab.binding.eightsleep.internal.discovery.BedSideDiscoveryService;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.openhab.binding.eightsleep.internal.polling.AccountPollingCoordinator;
import org.openhab.binding.eightsleep.internal.polling.UserDataCache;
import org.openhab.binding.eightsleep.internal.polling.UserDataSnapshot;
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

    /**
     * Authentication and domain service created for one connection attempt.
     *
     * @param tokenManager the connection's OAuth token manager
     * @param service the connection's domain service
     */
    public record AccountConnection(TokenManager tokenManager, EightSleepService service) {
    }

    /**
     * Creates connection-scoped API dependencies from account configuration.
     */
    @FunctionalInterface
    public interface AccountConnectionFactory {
        /**
         * Creates one authenticated connection context.
         *
         * @param configuration the current account configuration
         * @return connection-scoped authentication and service dependencies
         */
        AccountConnection create(AccountConfiguration configuration);
    }

    private static final int TREND_LOOKBACK_DAYS = 3;
    private static final long AUTH_RETRY_SECONDS = 60;

    private final Logger logger = LoggerFactory.getLogger(AccountHandler.class);

    private @Nullable EightSleepService service;

    private final AccountPollingCoordinator pollingCoordinator;
    private final AccountConnectionFactory connectionFactory;

    /** Pending reconnect attempt; cancelled on dispose and before scheduling a new one. */
    private @Nullable ScheduledFuture<?> reconnectJob;
    // cached state, read by bed side handlers
    private volatile @Nullable DeviceState deviceState;

    private final Map<String, UserDataCache> userDataByUser = new ConcurrentHashMap<>();
    /** userId -> side of the single bedSide thing registered for it (1:1 model). */
    private final Map<String, BedSide> sideByUserId = new ConcurrentHashMap<>();
    private volatile @Nullable String deviceId;
    private volatile long connectionGeneration;
    private volatile boolean disposed;

    /**
     * Creates an account handler with an injectable connection factory.
     *
     * @param bridge the account bridge
     * @param connectionFactory factory for connection-scoped API dependencies
     */
    public AccountHandler(Bridge bridge, AccountConnectionFactory connectionFactory) {
        super(bridge);
        this.connectionFactory = connectionFactory;
        pollingCoordinator = new AccountPollingCoordinator(scheduler,
                userId -> userDataByUser.computeIfAbsent(userId, key -> new UserDataCache()), this::acceptDeviceState,
                error -> handlePollFailure("device", error));
    }

    @Override
    public void initialize() {
        disposed = false;
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
        long generation = beginConnectionAttempt();
        AccountConnection connection = connectionFactory.create(config);
        TokenManager localTokenManager = connection.tokenManager();
        EightSleepService localService = connection.service();

        localTokenManager.getAccessTokenAsync().whenComplete((token, authFailure) -> {
            if (!isCurrentConnection(generation)) {
                return;
            }
            if (authFailure != null) {
                Throwable cause = authFailure.getCause() != null ? authFailure.getCause() : authFailure;
                logger.debug("Eight Sleep authentication failed: {}", cause.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, cause.getMessage());
                scheduleReconnect(config, generation);
                return;
            }
            logger.debug("Eight Sleep authenticated");

            // resolve the pod (device id) to bind, then start polling
            localService.getHouseholdDevices().thenAccept(devices -> {
                if (!isCurrentConnection(generation)) {
                    return;
                }
                if (devices.isEmpty()) {
                    // A transient API hiccup can return an empty household - retry like any other
                    // failure instead of latching a terminal CONFIGURATION_ERROR.
                    logger.debug("No Eight Sleep devices found for this account (yet); retrying");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "@text/account.status.no-devices");
                    scheduleReconnect(config, generation);
                    return;
                }
                String devId = chooseDeviceId(devices, config.deviceId);

                Map<String, String> properties = new HashMap<>(thing.getProperties());
                properties.put(EightSleepBindingConstants.CONFIG_USERNAME, config.username);
                for (Map.Entry<String, String> entry : devices.entrySet()) {
                    properties.put("device." + entry.getKey(), entry.getValue());
                }
                if (!activateConnection(generation, config, localService, devId)) {
                    return;
                }
                updateProperties(properties);
                if (isCurrentConnection(generation)) {
                    updateStatus(ThingStatus.ONLINE);
                }
            }).exceptionally(ex -> {
                if (!isCurrentConnection(generation)) {
                    return null;
                }
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                logger.debug("Failed to initialize Eight Sleep account: {}", cause.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, cause.getMessage());
                scheduleReconnect(config, generation);
                return null;
            });
        });
    }

    /**
     * Prefers the configured deviceId when it belongs to this account; otherwise the
     * first device in sorted order so multi-pod accounts get a stable choice across
     * restarts.
     */
    private String chooseDeviceId(java.util.Map<String, String> devices, @Nullable String configured) {
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

    private synchronized void startPolling(AccountConfiguration config, EightSleepService service, String devId) {
        stopPolling();
        long deviceInterval = config.deviceRefreshIntervalSeconds();
        long userInterval = config.userRefreshIntervalSeconds();
        long baseInterval = config.baseRefreshIntervalSeconds();

        pollingCoordinator.start(service, devId, deviceInterval, userInterval, baseInterval, TREND_LOOKBACK_DAYS);
    }

    private synchronized void stopPolling() {
        pollingCoordinator.stop();
    }

    private void acceptDeviceState(DeviceState state) {
        deviceState = state;
        updateStatus(ThingStatus.ONLINE);
    }

    /**
     * Resolves the configured user-data poll interval (clamped like startPolling),
     * so consumers can derive data-staleness thresholds from the same cadence.
     */
    public long userRefreshIntervalSeconds() {
        return getConfigAs(AccountConfiguration.class).userRefreshIntervalSeconds();
    }

    private void handlePollFailure(String what, ApiException e) {
        if (e.isUnauthorized()) {
            logger.debug("Unauthorized during {} poll, forcing reconnect", what);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/account.status.auth-expired");
            scheduleReconnect(getConfigAs(AccountConfiguration.class), connectionGeneration);
        } else {
            logger.debug("{} poll failed: {}", what, e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private synchronized void scheduleReconnect(AccountConfiguration config, long generation) {
        if (!isCurrentConnection(generation)) {
            return;
        }
        cancelReconnect();
        reconnectJob = scheduler.schedule(() -> {
            if (isCurrentConnection(generation) && getThing().getStatus() != ThingStatus.REMOVING) {
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
        disposed = true;
        resetConnection();
        cancelReconnect();
        super.dispose();
    }

    @Override
    public void thingUpdated(Thing thing) {
        super.thingUpdated(thing);
        // Apply configuration changes to the active session instead of waiting for
        // the next reactivation: rebuild clients and restart the poll jobs.
        AccountConfiguration config = getConfigAs(AccountConfiguration.class);
        if (config.username.isBlank() || config.password.isBlank()) {
            resetConnection();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/account.status.missing-credentials");
            return;
        }
        resetConnection();
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

    public @Nullable EightSleepService getService() {
        return service;
    }

    public @Nullable DeviceState getDeviceState() {
        return deviceState;
    }

    @Nullable
    UserDataCache getUserData(String userId) {
        return userDataByUser.get(userId);
    }

    /**
     * Returns an immutable snapshot of the current cached data for a user.
     *
     * @param userId the user identifier
     * @return the snapshot, or {@code null} if the user is not registered
     */
    public @Nullable UserDataSnapshot getUserSnapshot(String userId) {
        UserDataCache data = userDataByUser.get(userId);
        return data != null ? data.snapshot() : null;
    }

    /**
     * Returns the cache entry for a user, creating it when absent. The poll fan-out
     * uses this on every observation write; commands deliberately do not.
     */
    UserDataCache getUserDataOrCreate(String userId) {
        return userDataByUser.computeIfAbsent(userId, k -> new UserDataCache());
    }

    public @Nullable String getDeviceId() {
        return deviceId;
    }

    /**
     * Registers the single bedSide thing for a user. Under the 1:1 model each user
     * owns at most one thing (left, right or solo).
     *
     * @return true when this registration is new (first claim of the user id)
     */
    public boolean registerBedSide(String userId, BedSide side) {
        boolean first = sideByUserId.putIfAbsent(userId, side) == null;
        if (first) {
            pollingCoordinator.register(userId);
        }
        return first;
    }

    /**
     * Updates the side associated with an existing user registration.
     *
     * @param userId the registered user identifier
     * @param side the new side
     */
    public void updateRegisteredSide(String userId, BedSide side) {
        sideByUserId.computeIfPresent(userId, (key, existing) -> side);
    }

    /**
     * Unregisters the bedSide thing for a user: polling and cached data are dropped
     * immediately.
     */
    public void unregisterBedSide(String userId) {
        if (sideByUserId.remove(userId) != null) {
            userDataByUser.remove(userId);
            pollingCoordinator.unregister(userId);
        }
    }

    /**
     * Returns the temperature unit as "c"/"f" based on the bridge configuration.
     */
    public char getTemperatureUnit(char fallback) {
        return getConfigAs(AccountConfiguration.class).temperatureUnit(fallback);
    }

    private synchronized long beginConnectionAttempt() {
        cancelReconnect();
        stopPolling();
        service = null;
        deviceId = null;
        deviceState = null;
        return ++connectionGeneration;
    }

    private synchronized boolean isCurrentConnection(long generation) {
        return !disposed && generation == connectionGeneration;
    }

    private synchronized boolean activateConnection(long generation, AccountConfiguration config,
            EightSleepService service, String deviceId) {
        if (!isCurrentConnection(generation)) {
            return false;
        }
        this.service = service;
        this.deviceId = deviceId;
        startPolling(config, service, deviceId);
        return true;
    }

    private synchronized void resetConnection() {
        connectionGeneration++;
        stopPolling();
        service = null;
        deviceId = null;
        deviceState = null;
    }
}
