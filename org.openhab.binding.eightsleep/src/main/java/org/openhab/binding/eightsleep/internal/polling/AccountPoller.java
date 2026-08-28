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
package org.openhab.binding.eightsleep.internal.polling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BooleanSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the per-user poll fan-out (trends, speaker, alarms, temperature, pillow)
 * and the device-level away-state poll, writing results into {@link UserDataCache}
 * entries. Extracted from AccountHandler; the handler only schedules it.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountPoller.class);

    private final EightSleepService service;
    private final String deviceId;
    private final java.util.function.Function<String, UserDataCache> cacheFor;
    private final BooleanSupplier active;

    /** User ids currently registered for polling. */
    private final Set<String> userids = new CopyOnWriteArraySet<>();

    public AccountPoller(EightSleepService service, String deviceId,
            java.util.function.Function<String, UserDataCache> cacheFor) {
        this(service, deviceId, cacheFor, () -> true);
    }

    public AccountPoller(EightSleepService service, String deviceId,
            java.util.function.Function<String, UserDataCache> cacheFor, BooleanSupplier active) {
        this.service = service;
        this.deviceId = deviceId;
        this.cacheFor = cacheFor;
        this.active = active;
    }

    /**
     * Releases registered users when the poller is replaced by one bound to a
     * fresher API client; in-flight requests simply write into caches that the
     * new session re-polls.
     */
    public void close() {
        userids.clear();
    }

    public void register(String userId) {
        userids.add(userId);
    }

    public void unregister(String userId) {
        userids.remove(userId);
    }

    /**
     * Polls which users are currently in away mode and updates their cached state.
     * Away-state read model (verified against live captures):
     * away = user in awaySides AND removed from their side slot;
     * present = user occupies a side slot (even though awaySides still lists them).
     * <p>
     * Observations are written unconditionally with their START stamp; the merge
     * against commanded stamps happens in the channel sync, like every other
     * mutable channel.
     */
    public void pollAwayState() {
        // Stamp with the START time: the payload reflects the world as it was when
        // the request was issued, so any command sent while it is in flight is newer.
        Instant observedAt = Instant.now();
        service.getDeviceAssignments(deviceId).thenAccept(users -> {
            if (!active.getAsBoolean()) {
                return;
            }
            Set<String> candidates = new java.util.HashSet<>();
            if (users.leftUserId() != null) {
                candidates.add(users.leftUserId());
            }
            if (users.rightUserId() != null) {
                candidates.add(users.rightUserId());
            }
            candidates.addAll(users.awaySides().values());

            for (String uid : candidates) {
                UserDataCache data = cacheFor.apply(uid);
                data.awayObserved = users.isAway(uid);
                data.awayPolledAt = observedAt;
            }
        }).exceptionally(ex -> {
            LOGGER.debug("Failed to refresh away-mode state: {}",
                    ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            return null;
        });
    }

    /** Polls trends, speaker state, alarms, temperature and pillow data per registered user. */
    public void pollUserData(int trendLookbackDays) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime start = now.minusDays(trendLookbackDays);
        // The trends API wants the IANA timezone id (upstream passes the HA timezone)
        String tz = java.util.TimeZone.getDefault().getID();
        for (String userId : Set.copyOf(userids)) {
            service.getUserTrends(userId, start, now, tz).thenAccept(days -> {
                if (!active.getAsBoolean()) {
                    return;
                }
                UserDataCache data = cacheFor.apply(userId);
                data.trends = days;
                data.lastUpdated = Instant.now();
            }).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                LOGGER.debug("Failed to refresh trends for user {}: {}", userId, cause.getMessage());
                return null;
            });

            service.getPlayerState(userId).thenAccept(state -> {
                if (!active.getAsBoolean()) {
                    return;
                }
                UserDataCache data = cacheFor.apply(userId);
                data.playerState = state;
            }).exceptionally(ex -> {
                // Speaker is optional; a missing endpoint is not an error worth logging at warn level
                LOGGER.debug("Speaker state not available for user {}", userId);
                return null;
            });

            Instant pollStartedAt = Instant.now();
            service.getAlarms(userId).thenAccept(alarms -> {
                if (!active.getAsBoolean()) {
                    return;
                }
                UserDataCache data = cacheFor.apply(userId);
                // Stamp with the START time: any command issued while this request was
                // in flight is newer and must win the LWW merge.
                data.alarmsPolledAt = pollStartedAt;
                data.alarms.clear();
                data.alarms.addAll(alarms);
            }).exceptionally(ex -> {
                handleAlarmFailure(userId, pollStartedAt, ex);
                return null;
            });

            Instant tempPollStartedAt = Instant.now();
            service.getTemperatureState(userId).thenAccept(temp -> {
                if (!active.getAsBoolean()) {
                    return;
                }
                UserDataCache data = cacheFor.apply(userId);
                // Stamp with the START time so commands issued mid-flight win LWW.
                data.temperatureAt = tempPollStartedAt;
                data.temperature = temp;
            }).exceptionally(ex -> {
                LOGGER.debug("Failed to refresh temperature data for user {}", userId);
                return null;
            });

            service.getPillowState(userId).thenAccept(pillowState -> {
                if (!active.getAsBoolean()) {
                    return;
                }
                UserDataCache data = cacheFor.apply(userId);
                data.pillowState = pillowState;
            }).exceptionally(ex -> {
                // Pillow is optional (Pod 5 accessory); a missing payload just means no pillow
                LOGGER.debug("No pillow data for user {}", userId);
                return null;
            });
        }
    }

    /**
     * Accounts without an active subscription get 403 from the alarms API; degrade
     * gracefully so the rest of the binding keeps working (upstream #122).
     */
    private void handleAlarmFailure(String userId, Instant pollStartedAt, Throwable ex) {
        if (!active.getAsBoolean()) {
            return;
        }
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ApiException apiEx && apiEx.isSubscriptionRequired()) {
            LOGGER.debug("Alarms require a subscription for user {}; skipping", userId);
            UserDataCache data = cacheFor.apply(userId);
            data.alarms.clear();
            // Stamp so the LWW merge knows this empty list is fresh - otherwise the
            // alarm channels would keep showing a stale alarm forever.
            data.alarmsPolledAt = pollStartedAt;
        } else {
            LOGGER.debug("Failed to refresh alarms for user {}: {}", userId, cause.getMessage());
        }
    }
}
