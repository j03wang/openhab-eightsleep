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
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.handler.AccountHandler;
import org.openhab.binding.eightsleep.internal.model.UserProfile;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingStatus;
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
    private final Consumer<DiscoveryResult> resultPublisher;

    public BedSideDiscoveryService() {
        this(null);
    }

    BedSideDiscoveryService(@Nullable Consumer<DiscoveryResult> resultPublisher) {
        super(Set.of(THING_TYPE_UID_BED_SIDE), SEARCH_TIME_SECONDS, false);
        this.resultPublisher = resultPublisher != null ? resultPublisher : this::thingDiscovered;
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
        EightSleepService service = account.getService();
        String deviceId = account.getDeviceId();
        if (service == null || deviceId == null) {
            LOGGER.warn("Account bridge has no API service/device id yet; scan skipped");
            stopScan();
            return;
        }

        // label lookup and profile fan-out are composed, not nested: each stage
        // logs its own failure and the scan simply ends when either fails.
        service.getHouseholdDevices().thenApply(devices -> devices.getOrDefault(deviceId, deviceId))
                .thenCompose(deviceLabel -> service.getUserProfileForDevice(deviceId)
                        .thenApply(profiles -> Map.entry(deviceLabel, profiles)))
                .thenAccept(labelAndProfiles -> publishResults(account, labelAndProfiles.getKey(),
                        labelAndProfiles.getValue()))
                .exceptionally(ex -> {
                    LOGGER.debug("Device discovery failed: {}", message(ex));
                    return null;
                });
    }

    private static String message(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
    }

    /** Publishes one discovery result per discovered user profile. */
    private void publishResults(AccountHandler account, String deviceLabel, java.util.List<UserProfile> profiles) {
        LOGGER.debug("Eight Sleep discovery: found {} user(s) for device {}", profiles.size(), account.getDeviceId());
        for (UserProfile profile : profiles) {
            DiscoveryResult result = buildDiscoveryResult(account.getThing().getUID(), deviceLabel, profile);
            if (result != null) {
                resultPublisher.accept(result);
            }
        }
    }

    private static org.openhab.core.config.discovery.@Nullable DiscoveryResult buildDiscoveryResult(ThingUID bridgeUID,
            String deviceLabel, UserProfile profile) {
        String userId = profile.userId();
        if (userId == null) {
            return null;
        }
        String side = normalizeSide(profile.currentDevice() != null && profile.currentDevice().side() != null
                ? profile.currentDevice().side().apiValue()
                : null);
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

        return DiscoveryResultBuilder.create(thingUid).withProperties(properties)
                .withRepresentationProperty(CONFIG_USER_ID).withBridge(bridgeUID).withLabel(label).build();
    }

    /**
     * Normalizes a raw bed side to "left"/"right"/"solo"; null/unknown sides
     * default to "left" like the upstream client.
     */
    private static String normalizeSide(@Nullable String rawSide) {
        if (rawSide == null || rawSide.isBlank()) {
            return "left";
        }
        String side = rawSide.trim().toLowerCase();
        return "right".equals(side) || "solo".equals(side) ? side : "left";
    }

    /**
     * Replaces characters that are illegal in a thing ID segment.
     */
    private static String sanitizeForThingId(String userId) {
        return userId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
