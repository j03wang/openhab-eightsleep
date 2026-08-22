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
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.openhab.binding.eightsleep.internal.model.TrendParser;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
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

    // cached state, read by bed side handlers
    private volatile @Nullable DeviceData deviceData;
    private volatile boolean hasBase;
    private volatile boolean speakerAvailable;

    private final Map<String, UserData> userDataByUser = new ConcurrentHashMap<>();
    private final Map<String, String> sideByUserId = new ConcurrentHashMap<>();
    private final Map<String, String> userLabelById = new HashMap<>();
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

        scheduler.execute(() -> startAuthentication(config));
    }

    private void startAuthentication(AccountConfiguration config) {
        TokenManager localTokenManager = new TokenManager(config.username, config.password,
                emptyToNull(config.clientId), emptyToNull(config.clientSecret));
        EightSleepApiClient localApiClient = new EightSleepApiClient(localTokenManager);
        this.tokenManager = localTokenManager;
        this.apiClient = localApiClient;

        try {
            localTokenManager.getAccessToken();
        } catch (ApiException e) {
            logger.warn("Eight Sleep authentication failed: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            scheduleReconnect(config);
            return;
        }

        // determine device id + users
        try {
            String userId = EightSleepApiClient.join(localApiClient.getCurrentUserId());
            logger.debug("Authenticated account user {}", userId);
        } catch (ApiException e) {
            logger.debug("Could not resolve current Eight Sleep user: {}", e.getMessage());
        }

        fetchInitialStructure(config, localApiClient);
    }

    private void fetchInitialStructure(AccountConfiguration config, EightSleepApiClient client) {
        client.getHouseholdDevices().thenAccept(devices -> {
            if (devices.isEmpty()) {
                logger.warn("No Eight Sleep devices found for this account");
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/account.status.no-devices");
                return;
            }
            String firstDeviceId = devices.keySet().iterator().next();
            deviceId = firstDeviceId;

            Map<String, String> properties = new HashMap<>(thing.getProperties());
            properties.put(EightSleepBindingConstants.CONFIG_USERNAME, config.username);
            for (Map.Entry<String, String> entry : devices.entrySet()) {
                properties.put("device." + entry.getKey(), entry.getValue());
            }
            updateProperties(properties);

            startPolling(config, client, firstDeviceId);
            updateStatus(ThingStatus.ONLINE);
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            logger.warn("Failed to initialize Eight Sleep account: {}", cause.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, cause.getMessage());
            scheduleReconnect(config);
            return null;
        });
    }

    private synchronized void startPolling(AccountConfiguration config, EightSleepApiClient client, String devId) {
        stopPolling();

        long deviceInterval = clampInterval(config.deviceRefreshInterval, 15, 600);
        long userInterval = clampInterval(config.userRefreshInterval, 15, 600);
        long baseInterval = clampInterval(config.baseRefreshInterval, 30, 900);

        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> pollDeviceData(client, devId), 0, deviceInterval,
                TimeUnit.SECONDS));
        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> pollUserData(client), userInterval / 2, userInterval,
                TimeUnit.SECONDS));
        pollJobs.add(
                scheduler.scheduleWithFixedDelay(() -> pollBaseData(client), baseInterval, baseInterval, TimeUnit.SECONDS));
        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> pollAwayState(client, devId), 2, deviceInterval,
                TimeUnit.SECONDS));

        // initial immediate polls
        pollDeviceData(client, devId);
        pollAwayState(client, devId);
    }

    private synchronized void stopPolling() {
        for (ScheduledFuture<?> job : pollJobs) {
            if (job != null && !job.isCancelled()) {
                job.cancel(true);
            }
        }
        pollJobs.clear();
    }

    private void pollDeviceData(EightSleepApiClient client, String devId) {
        try {
            DeviceData data = EightSleepApiClient.join(client.getDeviceData(devId));
            deviceData = data;
            hasBase = data.hasBase();
            if (!speakerAvailable && data.hasSpeaker()) {
                speakerAvailable = true;
            }
            updateStatus(ThingStatus.ONLINE);
        } catch (ApiException e) {
            handlePollFailure("device", e);
        }
    }

    private void pollUserData(EightSleepApiClient client) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime start = now.minusDays(TREND_LOOKBACK_DAYS);
        // The trends API wants the IANA timezone id (upstream passes the HA timezone)
        String tz = java.util.TimeZone.getDefault().getID();
        for (String userId : Set.copyOf(sideByUserId.keySet())) {
            client.getUserTrends(userId, start, now, tz).thenAccept(days -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                data.trendDays = days;
                data.lastUpdated = Instant.now();
            }).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                logger.debug("Failed to refresh trends for user {}: {}", userId, cause.getMessage());
                return null;
            });

            client.getPlayerState(userId).thenAccept(state -> {
                if (state.hasSpeaker()) {
                    speakerAvailable = true;
                }
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                data.playerState = state;
            }).exceptionally(ex -> {
                // Speaker is optional; a missing endpoint is not an error worth logging at warn level
                logger.debug("Speaker state not available for user {}", userId);
                return null;
            });

            client.getAlarms(userId).thenAccept(alarms -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                data.alarms.clear();
                data.alarms.addAll(alarms);
            }).exceptionally(ex -> {
                // Accounts without an active subscription get 403 from the alarms API;
                // degrade gracefully so the rest of the binding keeps working (upstream #122)
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof ApiException apiEx && apiEx.isSubscriptionRequired()) {
                    logger.debug("Alarms require a subscription for user {}; skipping", userId);
                    UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                    data.alarms.clear();
                } else {
                    logger.debug("Failed to refresh alarms for user {}: {}", userId, cause.getMessage());
                }
                return null;
            });

            client.getTemperature(userId).thenAccept(temp -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                data.temperature = temp;
            }).exceptionally(ex -> {
                logger.debug("Failed to refresh temperature data for user {}", userId);
                return null;
            });

            client.getTemperatureAll(userId).thenAccept(pillowData -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                data.pillowData = pillowData;
            }).exceptionally(ex -> {
                // Pillow is optional (Pod 5 accessory); a missing payload just means no pillow
                logger.debug("No pillow data for user {}", userId);
                return null;
            });
        }
    }

    /**
     * Polls which users are currently in away mode and updates their cached state.
     */
    /**
     * Away-state read model (verified against live captures):
     * away  = user in awaySides AND removed from their side slot
     * present = user occupies a side slot (even though awaySides still lists them)
     */
    private void pollAwayState(EightSleepApiClient client, String devId) {
        client.getDeviceUsers(devId).thenAccept(users -> {
            java.util.Set<String> candidates = new java.util.HashSet<>(userDataByUser.keySet());
            if (users.leftUserId != null) {
                candidates.add(users.leftUserId);
            }
            if (users.rightUserId != null) {
                candidates.add(users.rightUserId);
            }
            candidates.addAll(users.awaySides.values());

            for (String uid : candidates) {
                UserData data = userDataByUser.computeIfAbsent(uid, k -> new UserData());
                data.awayMode = users.isAway(uid);
            }
            awayPolledOnce = true;
        }).exceptionally(ex -> {
            logger.debug("Failed to refresh away-mode state: {}",
                    ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            return null;
        });
    }

    private volatile boolean awayPolledOnce;
    private volatile String lastAwaySummary = "";

    /**
     * Records the commanded away state so it survives bridge restarts.
     */
    public void setLastKnownAwayMode(String userId, boolean away) {
        UserData data = userDataByUser.get(userId);
        if (data != null) {
            data.awayMode = away;
        }
        awayPolledOnce = true;
    }

    /**
     * True once an away state is known (commanded or restored); before that the away
     * channels have no meaningful value.
     */
    public boolean isAwayPolledOnce() {
        return awayPolledOnce;
    }

    private void pollBaseData(EightSleepApiClient client) {
        for (String userId : Set.copyOf(sideByUserId.keySet())) {
            client.getBaseData(userId).thenAccept(base -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
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

    private void scheduleReconnect(AccountConfiguration config) {
        scheduler.schedule(() -> {
            if (getThing().getStatus() != ThingStatus.REMOVING) {
                logger.debug("Retrying Eight Sleep connection");
                startAuthentication(config);
            }
        }, AUTH_RETRY_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        stopPolling();
        super.dispose();
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

    public @Nullable UserData getUserData(String userId) {
        return userDataByUser.get(userId);
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
     * Registers a bed side thing with the account so its user gets polled.
     *
     * @return true when the user id was registered (first time)
     */
    public boolean registerBedSide(String userId, String side) {
        sideByUserId.putIfAbsent(userId, side);
        return userDataByUser.containsKey(userId);
    }

    public void unregisterBedSide(String userId) {
        // keep other things that may reference the same user
    }

    /**
     * Resolves the effective side ("left"/"right") for a user id, defaulting via the
     * registered mapping or falling back to "left" like the original client does.
     */
    public String resolveSide(String userId, String configuredSide) {
        return !configuredSide.isBlank() ? configuredSide.toLowerCase()
                : sideByUserId.getOrDefault(userId, "left");
    }

    /**
     * Returns the temperature unit as "c"/"f" based on the bridge configuration.
     */
    public char getTemperatureUnit(char fallback) {
        String unit = getConfigAs(AccountConfiguration.class).temperatureUnit;
        if (!unit.isBlank()) {
            char first = Character.toLowerCase(unit.trim().charAt(0));
            if (first == 'c' || first == 'f') {
                return first;
            }
        }
        return fallback;
    }

    /**
     * Mutable per-user cache of polled data.
     */
    public static class UserData {
        public final List<EightSleepApiClient.Alarm> alarms = new CopyOnWriteArrayList<>();
        public volatile @Nullable BaseData baseData;
        public volatile @Nullable PlayerState playerState;
        public volatile EightSleepApiClient.PillowData pillowData;
        /** Raw /temperature payload (currentLevel, smart schedule, ...). */
        public volatile com.google.gson.JsonObject temperature;
        /** Raw v1 trends "days" payload, parsed defensively on read. */
        public volatile com.google.gson.JsonArray trendDays = new com.google.gson.JsonArray();
        public volatile boolean awayMode;
        public volatile @Nullable Instant lastUpdated;

        /**
         * Defensive parser over the raw trends payload. Session 0 is the current one.
         */
        public TrendParser getTrends() {
            return new TrendParser(trendDays);
        }

        public BaseData.SideData getBaseSide(String side) {
            BaseData base = baseData;
            return base != null ? base.getSide(side) : null;
        }
    }

    private static @Nullable String emptyToNull(@Nullable String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static long clampInterval(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
