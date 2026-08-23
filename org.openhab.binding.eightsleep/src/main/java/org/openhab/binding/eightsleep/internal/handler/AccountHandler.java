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
    /** Pending reconnect attempt; cancelled on dispose and before scheduling a new one. */
    private @Nullable ScheduledFuture<?> reconnectJob;

    // cached state, read by bed side handlers
    private volatile @Nullable DeviceData deviceData;
    private volatile boolean hasBase;
    private volatile boolean speakerAvailable;

    private final Map<String, UserData> userDataByUser = new ConcurrentHashMap<>();
    /** userId -> number of bedSide things currently registered for it. */
    private final Map<String, Integer> registrationCountByUser = new ConcurrentHashMap<>();
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
                // A transient API hiccup can return an empty household - retry like any other
                // failure instead of latching a terminal CONFIGURATION_ERROR.
                logger.debug("No Eight Sleep devices found for this account (yet); retrying");
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "@text/account.status.no-devices");
                scheduleReconnect(config);
                return;
            }
            // Prefer the configured device; otherwise fall back to the first device in
            // sorted order so multi-pod accounts get a stable choice across restarts.
            String configured = config.deviceId != null ? config.deviceId.trim() : "";
            String chosen;
            if (!configured.isBlank() && devices.containsKey(configured)) {
                chosen = configured;
            } else if (!configured.isBlank()) {
                logger.warn("Configured deviceId '{}' not found for this account; using '{}'. Known devices: {}",
                        configured, devices.keySet().stream().sorted().findFirst().orElse(""), devices);
                chosen = devices.keySet().stream().sorted().findFirst().orElseThrow();
            } else {
                chosen = devices.keySet().stream().sorted().findFirst().orElseThrow();
            }
            deviceId = chosen;

            Map<String, String> properties = new HashMap<>(thing.getProperties());
            properties.put(EightSleepBindingConstants.CONFIG_USERNAME, config.username);
            for (Map.Entry<String, String> entry : devices.entrySet()) {
                properties.put("device." + entry.getKey(), entry.getValue());
            }
            updateProperties(properties);

            startPolling(config, client, chosen);
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
        pollJobs.add(scheduler.scheduleWithFixedDelay(() -> pollUserData(client), 0, userInterval, TimeUnit.SECONDS));
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

            Instant pollStartedAt = Instant.now();
            client.getAlarms(userId).thenAccept(alarms -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                // Stamp with the START time: any command issued while this request was
                // in flight is newer and must win the LWW merge.
                data.alarmsPolledAt = pollStartedAt;
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
                    // Stamp so the LWW merge knows this empty list is fresh - otherwise the
                    // alarm channels would keep showing a stale alarm forever.
                    data.alarmsPolledAt = pollStartedAt;
                } else {
                    logger.debug("Failed to refresh alarms for user {}: {}", userId, cause.getMessage());
                }
                return null;
            });

            java.time.Instant tempPollStartedAt = Instant.now();
            client.getTemperature(userId).thenAccept(temp -> {
                UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
                // Stamp with the START time so commands issued mid-flight win LWW.
                data.temperatureAt = tempPollStartedAt;
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
     * Away-state read model (verified against live captures):
     * away = user in awaySides AND removed from their side slot;
     * present = user occupies a side slot (even though awaySides still lists them).
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

            Instant observedAt = Instant.now();
            for (String uid : candidates) {
                UserData data = userDataByUser.computeIfAbsent(uid, k -> new UserData());
                boolean away = users.isAway(uid);
                java.time.Instant commandedAt = data.awayCommandedAt;
                // last-write-wins: ignore a polled value that predates a command
                if (acceptsPolledAway(commandedAt, observedAt)) {
                    data.awayMode = away;
                    data.awayPolledAt = observedAt;
                }
            }
            awayPolledOnce = true;
        }).exceptionally(ex -> {
            logger.debug("Failed to refresh away-mode state: {}",
                    ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            return null;
        });
    }

    private volatile boolean awayPolledOnce;

    /**
     * Resolves the configured user-data poll interval (clamped like startPolling),
     * so consumers can derive data-staleness thresholds from the same cadence.
     */
    public long userRefreshIntervalSeconds() {
        return clampInterval(getConfigAs(AccountConfiguration.class).userRefreshInterval, 15, 600);
    }

    /**
     * Records the commanded away state so it survives bridge restarts.
     */
    public void setLastKnownAwayMode(String userId, boolean away) {
        UserData data = userDataByUser.computeIfAbsent(userId, k -> new UserData());
        data.awayMode = away;
        data.awayCommandedAt = Instant.now();
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

    private synchronized void scheduleReconnect(AccountConfiguration config) {
        cancelReconnect();
        reconnectJob = scheduler.schedule(() -> {
            if (getThing().getStatus() != ThingStatus.REMOVING) {
                logger.debug("Retrying Eight Sleep connection");
                startAuthentication(config);
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
        cancelReconnect();
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
     * @return true when this was the first registration for the user id
     */
    public boolean registerBedSide(String userId, String side) {
        Integer count = registrationCountByUser.merge(userId, 1, Integer::sum);
        if (count == 1) {
            sideByUserId.put(userId, side);
        }
        return count == 1;
    }

    /**
     * Unregisters a bed side thing. Polling and cached data for the user are dropped
     * only when the last thing referencing the user goes away.
     */
    public void unregisterBedSide(String userId) {
        Integer count = registrationCountByUser.computeIfPresent(userId, (k, v) -> v > 1 ? v - 1 : null);
        if (count == null) {
            // last reference gone: stop polling and forget the cached data
            sideByUserId.remove(userId);
            userDataByUser.remove(userId);
        }
    }

        /**
     * Returns the temperature unit as "c"/"f" based on the bridge configuration.
     */
    public char getTemperatureUnit(char fallback) {
        return parseTemperatureUnit(getConfigAs(AccountConfiguration.class).temperatureUnit, fallback);
    }

    /**
     * Mutable per-user cache of polled data.
     */
    public static class UserData {
        public final List<EightSleepApiClient.Alarm> alarms = new CopyOnWriteArrayList<>();
        /** When {@code alarms} was last fetched - used for last-write-wins merging. */
        public volatile java.time.Instant alarmsPolledAt;
        public volatile @Nullable BaseData baseData;
        public volatile @Nullable PlayerState playerState;
        public volatile EightSleepApiClient.PillowData pillowData;
        /** Raw /temperature payload (currentLevel, smart schedule, ...). */
        public volatile com.google.gson.JsonObject temperature;
        /** When {@code temperature} was fetched - used for last-write-wins merging. */
        public volatile java.time.Instant temperatureAt;
        /** Raw v1 trends "days" payload, parsed defensively on read. */
        public volatile com.google.gson.JsonArray trendDays = new com.google.gson.JsonArray();
        public volatile boolean awayMode;
        /** Instant of the last command that set awayMode (for last-write-wins). */
        public volatile java.time.Instant awayCommandedAt;
        /** Instant of the last successful away-state poll; epoch means "never". */
        public volatile java.time.Instant awayPolledAt = Instant.EPOCH;
        /**
         * When cached data was last (re)freshed - the construction moment counts as
         * fresh so a just-created entry is not immediately flagged stale; every
         * completed poll overwrites it.
         */
        public volatile java.time.Instant lastUpdated = Instant.now();

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

    /** Grace window: a poll observed this soon after a command is considered pre-command. */
    static final long AWAY_COMMAND_GRACE_SECONDS = 2;

    /**
     * Whether an away-state observation may overwrite a commanded value: a poll
     * observed within the grace window after a command is treated as pre-command
     * data and rejected.
     */
    static boolean acceptsPolledAway(java.time.@Nullable Instant commandedAt, java.time.Instant observedAt) {
        return commandedAt == null || !observedAt.minusSeconds(AWAY_COMMAND_GRACE_SECONDS).isBefore(commandedAt);
    }

    /**
     * Resolves the temperature unit from a configuration string ("C"/"F", any case,
     * tolerating whitespace). Returns {@code fallback} for blank/unknown values.
     */
    public static char parseTemperatureUnit(String unit, char fallback) {
        if (unit != null && !unit.isBlank()) {
            char first = Character.toLowerCase(unit.trim().charAt(0));
            if (first == 'c' || first == 'f') {
                return first;
            }
        }
        return fallback;
    }

    static long clampInterval(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
