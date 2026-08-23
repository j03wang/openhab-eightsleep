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
package org.openhab.binding.eightsleep.internal.discovery;

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.EightSleepApiClient;
import org.openhab.binding.eightsleep.internal.handler.AccountHandler;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers {@code bedSide} things for the users of an online {@code account} bridge.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedSideDiscoveryService.class);
    private static final int SEARCH_TIME_SECONDS = 10;

    private @Nullable AccountHandler accountHandler;

    public BedSideDiscoveryService() {
        super(Set.of(THING_TYPE_UID_BED_SIDE), SEARCH_TIME_SECONDS, false);
    }

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        if (handler instanceof AccountHandler accountHandler) {
            this.accountHandler = accountHandler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return accountHandler;
    }

    @Override
    public void deactivate() {
        if (accountHandler != null) {
            accountHandler.unregisterDiscoveryService(this);
        }
        super.deactivate();
    }

    @Override
    protected void startScan() {
        AccountHandler account = accountHandler;
        if (account == null || account.getThing().getStatus() != ThingStatus.ONLINE) {
            LOGGER.debug("Account bridge is not online, skipping scan");
            stopScan();
            return;
        }
        EightSleepApiClient client = account.getApiClient();
        String deviceId = account.getDeviceId();
        if (client == null || deviceId == null) {
            LOGGER.warn("Account bridge has no API client/device id yet; scan skipped");
            stopScan();
            return;
        }


        client.getHouseholdDevices().thenAccept(devices -> {
            String deviceLabel = devices.getOrDefault(deviceId, deviceId);
            client.getUserProfileForDevice(deviceId).thenAccept(profiles -> {
                LOGGER.debug("Eight Sleep discovery: found {} user(s) for device {}", profiles.size(), deviceId);
                for (EightSleepApiClient.UserProfileResult profile : profiles) {
                    DiscoveryResult result = buildDiscoveryResult(account.getThing().getUID(), deviceLabel, profile);
                    if (result != null) {
                        thingDiscovered(result);
                    }
                }
            }).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                LOGGER.warn("User profile discovery failed: {}", cause.getMessage());
                return null;
            });
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            LOGGER.warn("Device discovery failed: {}", cause.getMessage());
            return null;
        });
    }

    /** Visible for tests. */
    static org.openhab.core.config.discovery.DiscoveryResult buildDiscoveryResult(ThingUID bridgeUID,
            String deviceLabel, EightSleepApiClient.UserProfileResult profile) {
        String userId = profile.userId();
        if (userId == null) {
            return null;
        }
        String side = normalizeSide(profile.currentDevice() != null ? profile.currentDevice().side : null);
        String sanitizedUserId = sanitizeForThingId(userId);
        // The (type, bridgeUID, id) overload yields ...:bedSide:<bridgeId>:<userId>,
        // keeping the user id as the last segment.
        ThingUID thingUid = new ThingUID(THING_TYPE_UID_BED_SIDE, bridgeUID, sanitizedUserId);

        Map<String, Object> properties = new HashMap<>();
        properties.put(CONFIG_USER_ID, userId);
        properties.put("label", side);

        String label = "Eight Sleep Bed Side (" + switch (side) {
            case "left" -> "Left";
            case "right" -> "Right";
            default -> "Both";
        } + ") - " + deviceLabel;

        return DiscoveryResultBuilder.create(thingUid)
                .withProperties(properties).withRepresentationProperty(CONFIG_USER_ID)
                .withBridge(bridgeUID).withLabel(label).build();
    }

    /**
     * Normalizes a raw bed side to "left"/"right"/"solo"; null/unknown sides
     * default to "left" like the upstream client.
     * Visible for tests.
     */
    static String normalizeSide(@Nullable String rawSide) {
        if (rawSide == null || rawSide.isBlank()) {
            return "left";
        }
        String side = rawSide.trim().toLowerCase();
        return "right".equals(side) || "solo".equals(side) ? side : "left";
    }

    /**
     * Replaces characters that are illegal in a thing ID segment.
     * Visible for tests.
     */
    static String sanitizeForThingId(String userId) {
        return userId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
