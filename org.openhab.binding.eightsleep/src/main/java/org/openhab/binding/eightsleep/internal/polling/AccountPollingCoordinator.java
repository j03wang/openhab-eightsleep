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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.ApiException;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns account polling jobs and prevents callbacks from superseded sessions from publishing state.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class AccountPollingCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountPollingCoordinator.class);

    private final ScheduledExecutorService scheduler;
    private final Function<String, UserDataCache> cacheFor;
    private final Consumer<DeviceState> deviceConsumer;
    private final Consumer<ApiException> deviceFailureConsumer;
    private final Set<String> registeredUsers = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArrayList<ScheduledFuture<?>> jobs = new CopyOnWriteArrayList<>();

    private volatile long generation;
    private volatile @Nullable AccountPoller poller;

    public AccountPollingCoordinator(ScheduledExecutorService scheduler, Function<String, UserDataCache> cacheFor,
            Consumer<DeviceState> deviceConsumer, Consumer<ApiException> deviceFailureConsumer) {
        this.scheduler = scheduler;
        this.cacheFor = cacheFor;
        this.deviceConsumer = deviceConsumer;
        this.deviceFailureConsumer = deviceFailureConsumer;
    }

    /**
     * Starts a fresh set of polling jobs, replacing any previous session.
     *
     * @param service the service bound to the current authenticated session
     * @param deviceId the selected device identifier
     * @param deviceIntervalSeconds the device and away polling interval
     * @param userIntervalSeconds the per-user polling interval
     * @param baseIntervalSeconds the adjustable-base polling interval
     * @param trendLookbackDays the number of trend days to request
     */
    public synchronized void start(EightSleepService service, String deviceId, long deviceIntervalSeconds,
            long userIntervalSeconds, long baseIntervalSeconds, int trendLookbackDays) {
        stop();
        long session = generation;
        AccountPoller activePoller = new AccountPoller(service, deviceId, cacheFor, () -> session == generation);
        registeredUsers.forEach(activePoller::register);
        poller = activePoller;

        jobs.add(scheduler.scheduleWithFixedDelay(() -> pollDevice(service, deviceId, session), 0,
                deviceIntervalSeconds, TimeUnit.SECONDS));
        jobs.add(scheduler.scheduleWithFixedDelay(() -> {
            if (session == generation) {
                activePoller.pollUserData(trendLookbackDays);
            }
        }, 0, userIntervalSeconds, TimeUnit.SECONDS));
        jobs.add(scheduler.scheduleWithFixedDelay(() -> pollBaseState(service, session), baseIntervalSeconds,
                baseIntervalSeconds, TimeUnit.SECONDS));
        jobs.add(scheduler.scheduleWithFixedDelay(() -> {
            if (session == generation) {
                activePoller.pollAwayState();
            }
        }, 2, deviceIntervalSeconds, TimeUnit.SECONDS));
    }

    /**
     * Stops all jobs and invalidates their in-flight callbacks.
     */
    public synchronized void stop() {
        generation++;
        jobs.forEach(job -> job.cancel(true));
        jobs.clear();
        AccountPoller activePoller = poller;
        if (activePoller != null) {
            activePoller.close();
        }
        poller = null;
    }

    /**
     * Registers a user for per-user polling.
     *
     * @param userId the user identifier
     */
    public synchronized void register(String userId) {
        registeredUsers.add(userId);
        AccountPoller activePoller = poller;
        if (activePoller != null) {
            activePoller.register(userId);
        }
    }

    /**
     * Stops polling a user.
     *
     * @param userId the user identifier
     */
    public synchronized void unregister(String userId) {
        registeredUsers.remove(userId);
        AccountPoller activePoller = poller;
        if (activePoller != null) {
            activePoller.unregister(userId);
        }
    }

    private void pollDevice(EightSleepService service, String deviceId, long session) {
        service.getDeviceState(deviceId).whenComplete((state, failure) -> {
            if (session != generation) {
                return;
            }
            if (failure == null) {
                deviceConsumer.accept(state);
                return;
            }
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof ApiException apiException) {
                deviceFailureConsumer.accept(apiException);
            } else {
                LOGGER.debug("Unexpected device poll failure: {}", cause.getMessage());
            }
        });
    }

    private void pollBaseState(EightSleepService service, long session) {
        for (String userId : Set.copyOf(registeredUsers)) {
            service.getBaseState(userId).thenAccept(base -> {
                if (session == generation) {
                    cacheFor.apply(userId).baseState = base;
                }
            }).exceptionally(failure -> {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                LOGGER.debug("Failed to refresh base data for user {}: {}", userId, cause.getMessage());
                return null;
            });
        }
    }
}
