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
package org.openhab.binding.eightsleep.internal.api;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.BaseData;
import org.openhab.binding.eightsleep.internal.model.DeviceData;
import org.openhab.binding.eightsleep.internal.model.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for the Eight Sleep cloud API.
 * <p>
 * Ports the behaviour of the original Python client:
 * <ul>
 * <li>OAuth token acquisition and proactive refresh</li>
 * <li>Device, user (trends), base and speaker data polling</li>
 * <li>Temperature, alarm and bed side control</li>
 * <li>Automatic retry after a 401 response</li>
 * </ul>
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class EightSleepApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(EightSleepApiClient.class);

    /** Raw heating level range used by the Eight Sleep API. */
    public static final int HEATING_LEVEL_MIN = -100;
    public static final int HEATING_LEVEL_MAX = 100;

    /**
     * Seam for tests: performs an authorized HTTP call. Production delegates to
     * {@link ApiHttpClient}; tests record URLs/bodies and return canned responses.
     */
    public interface Transport {
        CompletableFuture<String> send(String method, String url, @Nullable String jsonBody,
                @Nullable String accessToken);
    }

    private final TokenManager tokenManager;
    private final Transport transport;

    public EightSleepApiClient(TokenManager tokenManager) {
        this(tokenManager, ApiHttpClient::send);
    }

    /**
     * Public test seam: substitutes the HTTP layer so retry behaviour can be
     * exercised without network access.
     */
    public EightSleepApiClient(TokenManager tokenManager, Transport transport) {
        this.tokenManager = tokenManager;
        this.transport = transport;
    }

    // ==================== data fetching ====================

    /**
     * Fetches current device data (heating levels, water state, presence).
     */
    public CompletableFuture<org.openhab.binding.eightsleep.internal.model.DeviceData> getDeviceData(
            String deviceId) {
        return authorizedGet(ApiConstants.CLIENT_API_URL + "/devices/" + urlEncode(deviceId))
                .thenApply(EightSleepApiClient::parseDeviceData);
    }

    /** Static for contract tests: parses a captured /devices/{id} response body. */
    public static org.openhab.binding.eightsleep.internal.model.DeviceData parseDeviceData(String body) {
        com.google.gson.JsonObject obj = GsonHelper.fromJson(body, com.google.gson.JsonObject.class);
        com.google.gson.JsonObject result = obj != null && obj.has("result") && obj.get("result").isJsonObject()
                ? obj.getAsJsonObject("result") : new com.google.gson.JsonObject();
        org.openhab.binding.eightsleep.internal.model.DeviceData data =
                GsonHelper.fromJson(result.toString(),
                        org.openhab.binding.eightsleep.internal.model.DeviceData.class);
        if (data == null) {
            data = new org.openhab.binding.eightsleep.internal.model.DeviceData();
        }
        // Keep the raw key names for diagnostics when expected fields are missing
        data.rawFieldNames = java.util.List.copyOf(result.keySet());
        return data;
    }

    /** Generic single-field envelope used by several endpoints. */
    public static class ResultEnvelope<T> {
        public @Nullable T result;
    }

    private static class DeviceDataEnvelope extends ResultEnvelope<DeviceData> {
    }

    /**
     * Fetches the user profile which contains the assigned bed side.
     */
    public CompletableFuture<UserProfileResult> getUserProfile(String userId) {
        return authorizedGet(ApiConstants.CLIENT_API_URL + "/users/" + urlEncode(userId))
                .thenApply(body -> parseUserProfile(userId, body));
    }

    /** Static for contract tests. */
    public static UserProfileResult parseUserProfile(String userId, String body) {
        UserProfileEnvelope envelope = null;
        try {
            envelope = GsonHelper.fromJson(body, UserProfileEnvelope.class);
        } catch (RuntimeException e) {
            LOGGER.debug("Unparseable user profile for {}: {}", userId, e.getMessage());
        }
        return new UserProfileResult(userId,
                envelope != null && envelope.user != null ? envelope.user.currentDevice : null);
    }

    /** Static for contract tests: extracts user ids + away sides from a filter response. */
    public static DeviceUsers parseUserIdsForDevice(String body) {
        DeviceUsersEnvelope envelope = GsonHelper.fromJson(body, DeviceUsersEnvelope.class);
        DeviceUsers info = new DeviceUsers();
        if (envelope != null && envelope.result != null) {
            info.leftUserId = envelope.result.leftUserId;
            info.rightUserId = envelope.result.rightUserId;
            info.awaySides = envelope.result.awaySides != null ? envelope.result.awaySides : new HashMap<>();
        }
        return info;
    }

    /**
     * Resolves the users (left/right/away) assigned to a device. Used by discovery.
     */
    public CompletableFuture<List<UserProfileResult>> getUserProfileForDevice(String deviceId) {
        String url = ApiConstants.CLIENT_API_URL + "/devices/" + urlEncode(deviceId)
                + "?filter=leftUserId,rightUserId,awaySides";
        return authorizedGet(url).thenApply(body -> {
            DeviceUsers users = parseUserIdsForDevice(body);
            List<CompletableFuture<UserProfileResult>> futures = new ArrayList<>();
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            if (users.leftUserId != null) {
                ids.add(users.leftUserId);
            }
            if (users.rightUserId != null) {
                ids.add(users.rightUserId);
            }
            ids.addAll(users.awaySides.values());
            for (String id : ids) {
                futures.add(getUserProfile(id).exceptionally(ex -> {
                    LOGGER.warn("Failed to resolve profile of user {}: {}", id,
                            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    return null;
                }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<UserProfileResult> profiles = new ArrayList<>();
            for (CompletableFuture<UserProfileResult> future : futures) {
                UserProfileResult profile = future.join();
                if (profile != null) {
                    profiles.add(profile);
                }
            }
            return profiles;
        });
    }

    /**
     * Fetches the logged-in user id ("me" endpoint), used for discovery.
     */
    public CompletableFuture<String> getCurrentUserId() {
        return authorizedGet(ApiConstants.CLIENT_API_URL + "/users/me")
                .thenApply(EightSleepApiClient::parseCurrentUserId);
    }

    /** Static for contract tests. */
    public static String parseCurrentUserId(String body) {
        MeEnvelope envelope = GsonHelper.fromJson(body, MeEnvelope.class);
        if (envelope != null && envelope.user != null && envelope.user.userId != null) {
            return envelope.user.userId;
        }
        throw new IllegalStateException("No userId in /users/me response");
    }

    /**
     * Fetches all devices of the household for discovery purposes.
     *
     * @return map of deviceId -> deviceName
     */
    public CompletableFuture<Map<String, String>> getHouseholdDevices() {
        return getCurrentUserId().thenCompose(
                userId -> authorizedGet(ApiConstants.APP_API_URL + "v1/household/users/"
                        + urlEncode(userId) + "/summary"))
                .thenApply(EightSleepApiClient::parseHouseholdDevices);
    }

    /** Static for contract tests. */
    public static Map<String, String> parseHouseholdDevices(String body) {
        // LinkedHashMap: discovery and the account bridge pick the FIRST device,
        // so API encounter order must be preserved for deterministic choices.
        Map<String, String> devices = new LinkedHashMap<>();
        HouseholdSummary summary = GsonHelper.fromJson(body, HouseholdSummary.class);
        if (summary != null && summary.households != null) {
            for (Household household : summary.households) {
                if (household.sets == null) {
                    continue;
                }
                for (DeviceSet set : household.sets) {
                    if (set.devices == null) {
                        continue;
                    }
                    for (ApiDevice device : set.devices) {
                        if (device.deviceId != null) {
                            devices.put(device.deviceId,
                                    device.deviceName != null ? device.deviceName : device.deviceId);
                        }
                    }
                }
            }
        }
        return devices;
    }

    /**
     * Fetches the users assigned to a device including the current away sides.
     * The {@code awaySides} map (side -> userId) is the API's source of truth for
     * who is currently in away mode.
     */
    public CompletableFuture<DeviceUsers> getDeviceUsers(String deviceId) {
        String url = ApiConstants.CLIENT_API_URL + "/devices/" + urlEncode(deviceId)
                + "?filter=leftUserId,rightUserId,awaySides";
        return authorizedGet(url).thenApply(body -> {
            DeviceUsers info = new DeviceUsers();
            DeviceUsersEnvelope envelope = GsonHelper.fromJson(body, DeviceUsersEnvelope.class);
            if (envelope != null && envelope.result != null) {
                info.leftUserId = envelope.result.leftUserId;
                info.rightUserId = envelope.result.rightUserId;
                info.awaySides = envelope.result.awaySides != null ? envelope.result.awaySides : new HashMap<>();
            }
            return info;
        });
    }

    /** Public view of the device-user assignment incl. away sides. */
    public static class DeviceUsers {
        public @Nullable String leftUserId;
        public @Nullable String rightUserId;
        /**
         * Side -> userId map from the {@code awaySides} filter. NOTE: in live captures
         * the KEYS are "leftUserId"/"rightUserId" (not "left"/"right") - only the
         * VALUES (user ids) are meaningful, which is all {@link #isAway} uses.
         */
        public Map<String, String> awaySides = new HashMap<>();

        /**
         * Verified live semantics (captured present vs away):
         * an away user is listed in {@code awaySides} AND has been removed from their
         * side slot (leftUserId/rightUserId becomes null while away). A present user
         * is listed in awaySides too (stale record) but still occupies a side slot.
         */
        public boolean isAway(String userId) {
            if (userId == null || !awaySides.containsValue(userId)) {
                return false;
            }
            boolean occupiesSide = userId.equals(leftUserId) || userId.equals(rightUserId);
            return !occupiesSide;
        }
    }

    /**
     * Fetches sleep trends for the given interval (v2 API). The result contains the
     * session days; index 0 from the end is the current/most recent session.
     */
    public CompletableFuture<com.google.gson.JsonArray> getUserTrends(String userId, ZonedDateTime start,
            ZonedDateTime end, String timezone) {
        String url = ApiConstants.CLIENT_API_URL + "/users/" + urlEncode(userId) + "/trends"
                + "?tz=" + urlEncode(timezone)
                + "&from=" + start.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "&to=" + end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "&include-main=false&include-all-sessions=true&model-version=v2";
        return authorizedGet(url).thenApply(EightSleepApiClient::parseTrendDays);
    }

    /**
     * Rolls a stale (already-past) timestamp forward in whole weeks (UTC arithmetic)
     * until it lands after {@code now}. Used for disabled repeating alarms whose
     * server timestamp stopped updating; keeps them ordered correctly without
     * inventing state. Returns null when there is no base timestamp to roll from.
     */
    public static java.time.@Nullable Instant rollToNextWeek(java.time.@Nullable Instant ts,
            java.time.Instant now) {
        if (ts == null) {
            return null;
        }
        java.time.Instant rolled = ts;
        while (rolled.isBefore(now)) {
            rolled = rolled.plus(java.time.Duration.ofDays(7));
        }
        return rolled;
    }

    /** Static for contract tests: extracts the raw "days" array from a trends body. */
    public static com.google.gson.JsonArray parseTrendDays(String body) {
        com.google.gson.JsonObject obj = GsonHelper.fromJson(body, com.google.gson.JsonObject.class);
        if (obj != null && obj.has("days") && obj.get("days").isJsonArray()) {
            return obj.getAsJsonArray("days");
        }
        return new com.google.gson.JsonArray();
    }

    /**
     * Fetches the alarms configured for a user via the v2 endpoint.
     */
    public CompletableFuture<List<Alarm>> getAlarms(String userId) {
        return authorizedGet(ApiConstants.APP_API_URL + "v2/users/" + urlEncode(userId) + "/alarms")
                .thenApply(EightSleepApiClient::parseAlarms);
    }

    /** Static for contract tests. */
    public static List<Alarm> parseAlarms(String body) {
        List<Alarm> alarms = new ArrayList<>();
        AlarmsEnvelope envelope = GsonHelper.fromJson(body, AlarmsEnvelope.class);
        if (envelope != null && envelope.alarms != null) {
            alarms.addAll(envelope.alarms);
        }
        return alarms;
    }

    /**
     * Fetches the adjustable base data for a user.
     */
    public CompletableFuture<BaseData> getBaseData(String userId) {
        return authorizedGet(ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/base")
                .thenApply(EightSleepApiClient::parseBaseData);
    }

    /** Static for contract tests. */
    public static BaseData parseBaseData(String body) {
        BaseData data = GsonHelper.fromJson(body, BaseData.class);
        return data != null ? data : new BaseData();
    }

    /**
     * Fetches the speaker player state. Only present on Pod 5 / speaker equipped beds.
     */
    public CompletableFuture<PlayerState> getPlayerState(String userId) {
        return authorizedGet(ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/audio/player")
                .thenApply(EightSleepApiClient::parsePlayerState);
    }

    /** Static for contract tests. */
    public static PlayerState parsePlayerState(String body) {
        PlayerState state = GsonHelper.fromJson(body, PlayerState.class);
        return state != null ? state : new PlayerState();
    }

    // ==================== control operations ====================

    /**
     * Sets the hub LED brightness (0-100).
     * <p>
     * The device resource accepts a PUT with {@code ledBrightnessLevel} (verified upstream
     * against a live Pod 5).
     */
    public CompletableFuture<Void> setLedBrightness(String deviceId, int levelPercent) {
        int level = Math.max(0, Math.min(100, levelPercent));
        Map<String, Object> body = Map.of("ledBrightnessLevel", level);
        String url = ApiConstants.CLIENT_API_URL + "/devices/" + urlEncode(deviceId);
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /**
     * Fetches the user temperature resource (currentLevel, currentState, smart schedule).
     */
    public CompletableFuture<com.google.gson.JsonObject> getTemperature(String userId) {
        return authorizedGet(temperatureUrl(userId)).thenApply(body -> {
            com.google.gson.JsonObject obj = GsonHelper.fromJson(body, com.google.gson.JsonObject.class);
            return obj != null ? obj : new com.google.gson.JsonObject();
        });
    }

    private static String temperatureUrl(String userId) {
        return ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/temperature";
    }

    /**
     * Updates heating data, optionally powering on first. Mirrors upstream:
     * PUT {@code currentLevel} before PUT {@code timeBased} so the level sticks.
     */
    public CompletableFuture<Void> setHeatingLevel(String userId, int level, int durationSeconds) {
        int clamped = clampLevel(level);
        return turnOnSide(userId).thenCompose(v -> authorizedPut(temperatureUrl(userId),
                        Map.of("currentLevel", clamped)))
                .thenCompose(v -> authorizedPut(temperatureUrl(userId),
                        Map.of("timeBased", Map.of("level", clamped, "durationSeconds", durationSeconds))))
                .thenApply(v -> null);
    }

    /**
     * Sets the heating level for a specific smart sleep stage by reading the smart
     * schedule, updating the stage and writing it back.
     */
    public CompletableFuture<Void> setSmartHeatingLevel(String userId, int level, String sleepStage) {
        int clamped = clampLevel(level);
        return getTemperature(userId).thenCompose(temp -> {
            com.google.gson.JsonObject smart = temp.has("smart") && temp.get("smart").isJsonObject()
                    ? temp.getAsJsonObject("smart") : new com.google.gson.JsonObject();
            smart.addProperty(sleepStage, clamped);
            return authorizedPut(temperatureUrl(userId), Map.of("smart", smart));
        }).thenApply(v -> null);
    }

    /**
     * Turns a side on (smart mode).
     */
    public CompletableFuture<Void> turnOnSide(String userId) {
        return authorizedPut(temperatureUrl(userId), Map.of("currentState", Map.of("type", "smart")))
                .thenApply(v -> null);
    }

    /**
     * Turns a side off.
     */
    public CompletableFuture<Void> turnOffSide(String userId) {
        return authorizedPut(temperatureUrl(userId), Map.of("currentState", Map.of("type", "off")))
                .thenApply(v -> null);
    }

    /**
     * Starts or ends away mode. Action must be {@code start} or {@code end}.
     * <p>
     * Upstream re-asserts the bed side before this call so multi-pod accounts target
     * the right pod - but blindly writing a normalized side REWRITES a solo ("both")
     * profile to left/right. So we only re-assert when the user genuinely occupies one
     * physical side; solo users skip it entirely.
     */
    public CompletableFuture<Void> setAwayMode(String userId, String deviceId, String side, String action) {
        if (!"start".equals(action) && !"end".equals(action)) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ApiException("Invalid away mode action: " + action));
            return failed;
        }
        boolean genuineSide = "left".equalsIgnoreCase(side) || "right".equalsIgnoreCase(side);
        // Setting time to UTC of 24 hours ago makes the API trigger immediately
        String timestamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now().minusSeconds(24 * 3600L));

        CompletableFuture<Void> prepared = genuineSide
                ? setBedSide(userId, deviceId, side.toLowerCase())
                : CompletableFuture.completedFuture(null);
        return prepared.thenCompose(v -> authorizedPut(
                        ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/away-mode",
                        Map.of("awayPeriod", Map.of(action, timestamp))))
                .thenApply(v -> null);
    }

    /**
     * Triggers the pod priming procedure. The requesting user receives the notification.
     */
    public CompletableFuture<Void> primePod(String deviceId, String userId) {
        Map<String, Object> body = Map.of("notifications",
                Map.of("users", List.of(userId), "meta", "rePriming"));
        String url = ApiConstants.APP_API_URL + "v1/devices/" + urlEncode(deviceId) + "/priming/tasks";
        return authorizedPost(url, body).thenApply(v -> null);
    }

    /**
     * Fetches temperature data for all temperature-controlled devices of this user
     * ({@code /temperature/all}). The payload lists the pod and any pillow, which is
     * how pillow-to-bed membership is verified.
     */
    public CompletableFuture<PillowData> getTemperatureAll(String userId) {
        return authorizedGet(ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/temperature/all")
                .thenApply(EightSleepApiClient::parsePillowData);
    }

    /** Static for contract tests. */
    public static PillowData parsePillowData(String body) {
        PillowData data = GsonHelper.fromJson(body, PillowData.class);
        return data != null ? data : new PillowData();
    }

    /**
     * Turns the pillow on (smart mode).
     */
    public CompletableFuture<Void> turnOnPillow(String userId) {
        Map<String, Object> body = Map.of("currentState", Map.of("type", "smart"));
        return putPillow(userId, body);
    }

    /**
     * Turns the pillow off.
     */
    public CompletableFuture<Void> turnOffPillow(String userId) {
        Map<String, Object> body = Map.of("currentState", Map.of("type", "off"));
        return putPillow(userId, body);
    }

    /**
     * Sets the pillow level (-100..100). Writing a level to an off pillow is silently
     * ignored by the API, so callers must power it on first.
     */
    public CompletableFuture<Void> setPillowLevel(String userId, int level) {
        int clamped = clampLevel(level);
        Map<String, Object> body = Map.of("currentLevel", clamped);
        return putPillow(userId, body);
    }

    private CompletableFuture<Void> putPillow(String userId, Map<String, Object> body) {
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/temperature/pillow";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /**
     * Sets which side(s) of the given device a user controls. Side must be
     * {@code solo}, {@code left} or {@code right}; {@code solo} means the user
     * controls the whole ("both sides") bed.
     */
    public CompletableFuture<Void> setBedSide(String userId, String deviceId, String side) {
        String normalized = side.toLowerCase();
        if (!"solo".equals(normalized) && !"left".equals(normalized) && !"right".equals(normalized)) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ApiException("Invalid side parameter: " + side));
            return failed;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("id", deviceId);
        body.put("side", normalized);
        String url = ApiConstants.CLIENT_API_URL + "/users/" + urlEncode(userId) + "/current-device";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /**
     * Snoozes the currently ringing alarm.
     * <p>
     * Upstream: {@code PUT /alarms/{nextAlarmId}/snooze} with
     * {@code {"snoozeMinutes": N, "ignoreDeviceErrors": false}}; 409 means not ringing.
     */
    public CompletableFuture<Void> snoozeAlarm(String userId, String alarmId, int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("snoozeMinutes", minutes);
        body.put("ignoreDeviceErrors", false);
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/alarms/"
                + urlEncode(alarmId) + "/snooze";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /**
     * Dismisses an alarm (also used as "stop" upstream - there is no separate stop endpoint).
     * <p>
     * Upstream: {@code PUT /alarms/{alarmId}/dismiss} with {@code {"ignoreDeviceErrors": false}}.
     */
    public CompletableFuture<Void> dismissAlarm(String userId, String alarmId) {
        Map<String, Object> body = Map.of("ignoreDeviceErrors", false);
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/alarms/"
                + urlEncode(alarmId) + "/dismiss";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /**
     * Enables or disables an alarm by id. The v2 alarms API requires the full alarm
     * object wrapped in {@code alarmSettings}, with server-computed fields stripped and
     * all numeric values as integers (the .NET backend rejects JSON floats like -10.0).
     */
    public CompletableFuture<Void> setAlarmEnabled(String userId, Alarm alarm, boolean enabled) {
        if (alarm.id == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ApiException("Cannot toggle alarm without an id"));
            return failed;
        }
        return authorizedPutRaw(alarmUrl(userId, alarm.id),
                buildAlarmUpdateBody(alarm, enabled, null)).thenApply(v -> null);
    }

    /**
     * Serializes the exact PUT body for alarm updates.
     * <p>
     * VERIFIED against the live API (probe): the body is the BARE alarm object -
     * no {@code alarmSettings} wrapper (wrapper shapes are rejected with
     * "alarm id mismatch between path and body"). Whole numbers are emitted as
     * JSON integers; the backend rejects e.g. -10.0 for Int32 fields.
     */
    public static String buildAlarmUpdateBody(Alarm alarm, @Nullable Boolean enabled,
            @Nullable String timeOverride) {
        Map<String, Object> settings = buildAlarmSettingsPayload(alarm);
        if (enabled != null) {
            settings.put("enabled", enabled);
        }
        if (timeOverride != null) {
            settings.put("time", timeOverride);
        }
        return GsonHelper.toJson(normalizeNumbers(settings));
    }

    private static String alarmUrl(String userId, String alarmId) {
        return ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/alarms/" + urlEncode(alarmId);
    }

    /**
     * Sets the time of an existing alarm. Same payload rules as
     * {@link #setAlarmEnabled(String, Alarm, boolean)}.
     */
    public CompletableFuture<Void> setAlarmTime(String userId, Alarm alarm, String timeOfDay) {
        if (alarm.id == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ApiException("Cannot reschedule alarm without an id"));
            return failed;
        }
        return authorizedPutRaw(alarmUrl(userId, alarm.id),
                buildAlarmUpdateBody(alarm, null, timeOfDay)).thenApply(v -> null);
    }

    /** Builds the alarm payload minus server-computed fields (upstream contract). */
    private static Map<String, Object> buildAlarmSettingsPayload(Alarm alarm) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("id", alarm.id);
        settings.put("time", alarm.time != null ? alarm.time : "07:00:00");
        settings.put("repeat", alarm.repeat != null ? alarm.repeat : new HashMap<>());
        settings.put("thermal", alarm.thermal != null ? alarm.thermal : new HashMap<>());
        settings.put("vibration", alarm.vibration != null ? alarm.vibration : new HashMap<>());
        settings.put("audio", alarm.audio != null ? alarm.audio : new HashMap<>());
        settings.put("smart", alarm.smart != null ? alarm.smart : new HashMap<>());
        if (alarm.tags != null) {
            settings.put("tags", alarm.tags);
        }
        settings.put("skipNext", alarm.skipNext != null ? alarm.skipNext : false);
        settings.put("snoozing", alarm.snoozing != null ? alarm.snoozing : false);
        return settings;
    }

    private CompletableFuture<Void> authorizedPutRaw(String url, String rawJsonBody) {
        return withAuthRetry(token -> transport.send("PUT", url, rawJsonBody, token)).thenApply(v -> null);
    }

    /**
     * Sets the adjustable base angle for a user side.
     * <p>
     * Uses POST {@code /base/angle?ignoreDeviceErrors=false} with a device-scoped
     * payload, matching the verified upstream client.
     */
    public CompletableFuture<Void> setBaseAngle(String userId, String deviceId, int legAngle, int torsoAngle) {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("deviceOnline", true);
        body.put("legAngle", legAngle);
        body.put("torsoAngle", torsoAngle);
        body.put("enableOfflineMode", false);
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId)
                + "/base/angle?ignoreDeviceErrors=false";
        return authorizedPost(url, body).thenApply(v -> null);
    }

    /**
     * Sets the base preset ({@code sleep}, {@code relaxing} or {@code reading}).
     */
    public CompletableFuture<Void> setBasePreset(String userId, String deviceId, String preset) {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("deviceOnline", true);
        body.put("preset", preset);
        body.put("enableOfflineMode", false);
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId)
                + "/base/angle?ignoreDeviceErrors=false";
        return authorizedPost(url, body).thenApply(v -> null);
    }

    // ==================== speaker control ====================

    /** Upstream: PUT /audio/player/state with {"state": "Playing"|"Paused"}. */
    public CompletableFuture<Void> setPlayerState(String userId, boolean playing) {
        Map<String, Object> body = Map.of("state", playing ? "Playing" : "Paused");
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/audio/player/state";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /** Upstream: PUT /audio/player/volume with {"volume": 0-100}. */
    public CompletableFuture<Void> setPlayerVolume(String userId, int volumePercent) {
        Map<String, Object> body = Map.of("volume", Math.max(0, Math.min(100, volumePercent)));
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/audio/player/volume";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    /** Upstream: PUT /audio/player/currentTrack with {"id": trackId, "stopCriteria": "ManualStop"}. */
    public CompletableFuture<Void> setPlayerTrack(String userId, String trackId) {
        Map<String, Object> body = Map.of("id", trackId, "stopCriteria", "ManualStop");
        String url = ApiConstants.APP_API_URL + "v1/users/" + urlEncode(userId) + "/audio/player/currentTrack";
        return authorizedPut(url, body).thenApply(v -> null);
    }

    // ==================== plumbing ====================

    private CompletableFuture<String> authorizedGet(String url) {
        return withAuthRetry(token -> transport.send("GET", url, null, token));
    }

    private CompletableFuture<String> authorizedPost(String url, @Nullable Object body) {
        return withAuthRetry(token -> transport.send("POST", url, GsonHelper.toJson(body), token));
    }

    private CompletableFuture<String> authorizedPut(String url, @Nullable Object body) {
        return withAuthRetry(token -> transport.send("PUT", url, GsonHelper.toJson(body), token));
    }

    /**
     * Runs the request with a valid access token. On a 401 failure the token is
     * invalidated and the request is retried exactly once with a fresh token.
     */
    private CompletableFuture<String> withAuthRetry(java.util.function.Function<String, CompletableFuture<String>> invoker) {
        return supplyToken().thenCompose(invoker).handle((body, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(body);
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof ApiException apiEx && apiEx.isUnauthorized()) {
                LOGGER.debug("401 received, refreshing token and retrying once");
                tokenManager.invalidate();
                return supplyToken().thenCompose(invoker);
            }
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }).thenCompose(future -> future);
    }

    /**
     * Supplies a valid access token, converting checked exceptions into completion exceptions.
     */
    private CompletableFuture<String> supplyToken() {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            future.complete(tokenManager.getAccessToken());
        } catch (ApiException e) {
            future.completeExceptionally(e);
        }
        return future;
    }





    private static int clampLevel(int level) {
        return Math.max(HEATING_LEVEL_MIN, Math.min(HEATING_LEVEL_MAX, level));
    }

    /**
     * Recursively converts whole-number doubles to longs so Gson serializes them as
     * JSON integers. The alarms backend (ASP.NET) rejects values like -10.0 for Int32
     * fields such as thermal.level.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<String, Object> normalizeNumbers(Map<String, Object> map) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            out.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object normalizeValue(Object value) {
        // NOTE: do NOT merge these branches into a ternary - mixing Long and Double
        // operands triggers numeric promotion and yields a Double again.
        if (value instanceof Double d) {
            if (d == Math.floor(d) && !d.isInfinite()) {
                return Long.valueOf(d.longValue());
            }
            return d;
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeNumbers((Map) map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(normalizeValue(item));
            }
            return out;
        }
        return value;
    }

    private static String urlEncode(String value) {
        return ApiHttpClient.urlEncode(value);
    }

    /**
     * Waits synchronously for an already-running async operation. Helper for handlers
     * that dispatch to a background scheduler anyway.
     */
    public static <T> T join(CompletableFuture<T> future) throws ApiException {
        try {
            return future.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiException(cause.getMessage(), cause);
        }
    }


    // ==================== response DTOs ====================

    /** Result of fetching a user profile: the user id plus its current device assignment. */
    public record UserProfileResult(String userId, @Nullable UserCurrentDevice currentDevice) {
    }

    public static class UserCurrentDevice {
        public @Nullable String side;
        public @Nullable String deviceId;
    }

    private static class UserProfileEnvelope {
        public @Nullable UserProfile user;

        static class UserProfile {
            public @Nullable UserCurrentDevice currentDevice;
        }
    }

    private static class DeviceUsersEnvelope {
        public @Nullable DeviceUsers result;

        static class DeviceUsers {
            public @Nullable String leftUserId;
            public @Nullable String rightUserId;
            public @Nullable Map<String, String> awaySides;
        }
    }

    private static class MeEnvelope {
        public @Nullable MeUser user;

        static class MeUser {
            public @Nullable String userId;
            public @Nullable List<String> devices;
        }
    }

    private static class HouseholdSummary {
        public @Nullable List<Household> households;
    }

    private static class Household {
        public @Nullable List<DeviceSet> sets;
    }

    private static class DeviceSet {
        public @Nullable List<ApiDevice> devices;
    }

    private static class ApiDevice {
        public @Nullable String deviceId;
        public @Nullable String deviceName;
    }

    private static class AlarmsEnvelope {
        // v2 alarms response shape: {"alarms": [...], "recommendedAlarm": {...}}
        public @Nullable List<Alarm> alarms;
    }

    /** An alarm entry from the alarms API. */
    public static class Alarm {
        public @Nullable String id;
        public @Nullable String time;
        public @Nullable Boolean enabled;
        public @Nullable AlarmRepeat repeat;
        public @Nullable Map<String, Object> thermal;
        public @Nullable Map<String, Object> vibration;
        public @Nullable Map<String, Object> audio;
        public @Nullable Map<String, Object> smart;
        public @Nullable List<String> tags;
        public @Nullable Boolean skipNext;
        public @Nullable Boolean snoozing;
        public @Nullable String nextTimestamp;

        /**
         * Computes when this alarm fires next, WITHOUT relying on {@code nextTimestamp}
         * (which goes stale or null for disabled alarms).
         *
         * Repeating alarms are derived from {@code time} + {@code repeat.weekDays} in
         * {@code zone}; a repeat flag with no active weekday is treated as daily.
         * One-shot alarms (repeat disabled) use nextTimestamp, since a bare HH:mm:ss
         * carries no date.
         */
        public java.time.@Nullable Instant computeNextRun(java.time.ZoneId zone) {
            return computeNextRun(zone, java.time.Instant.now());
        }

        /** As above with an injectable clock (testability at any point in the week). */
        public java.time.@Nullable Instant computeNextRun(java.time.ZoneId zone,
                java.time.Instant now) {
            if (time == null || time.isBlank()) {
                return null;
            }
            java.time.LocalTime fireTime = org.openhab.binding.eightsleep.internal.model.TrendParser
                    .parseTimeOfDay(time);
            if (fireTime == null) {
                return null;
            }
            boolean repeating = Boolean.TRUE.equals(repeat != null ? repeat.enabled : null);
            Map<String, Boolean> weekDays = repeat != null ? repeat.weekDays : null;

            if (!repeating) {
                // One-shot: nextTimestamp is the only date source, but a DISABLED
                // alarm's stale timestamp (already fired) must not win selection -
                // roll forward a week so it stays in the ordering as "next week".
                java.time.Instant serverTs =
                        org.openhab.binding.eightsleep.internal.model.TrendParser.parseTimestamp(
                                nextTimestamp);
                if (serverTs != null && !serverTs.isBefore(now)) {
                    return serverTs;
                }
                return rollToNextWeek(serverTs, now);
            }
            boolean[] mask = new boolean[7]; // Mon..Sun
            boolean anyDay = false;
            if (weekDays != null) {
                String[] names = { "monday", "tuesday", "wednesday", "thursday", "friday",
                        "saturday", "sunday" };
                for (int i = 0; i < names.length; i++) {
                    if (Boolean.TRUE.equals(weekDays.get(names[i]))) {
                        mask[i] = true;
                        anyDay = true;
                    }
                }
            }
            if (!anyDay) {
                java.util.Arrays.fill(mask, true); // repeat enabled, no days = daily
            }
            java.time.ZoneId effectiveZone = zone;
            java.time.LocalDate date = now.atZone(effectiveZone).toLocalDate();
            for (int addDays = 0; addDays < 8; addDays++) {
                java.time.LocalDate candidateDate = date.plusDays(addDays);
                int idx = candidateDate.getDayOfWeek().getValue() - 1; // Monday = 0
                if (!mask[idx]) {
                    continue;
                }
                java.time.Instant candidate = candidateDate.atTime(fireTime)
                        .atZone(effectiveZone).toInstant();
                if (!candidate.isBefore(now)) {
                    return candidate;
                }
            }
            return null;
        }

        public static class AlarmRepeat {
            public @Nullable Boolean enabled;
            public @Nullable Map<String, Boolean> weekDays;
        }
    }

    /**
     * Response of {@code GET /temperature/all}: lists the pod plus any pillow with their
     * per-device state. Used for Pod 5 pillow support.
     */
    public static class PillowData {
        public @Nullable List<PillowEntry> devices;

        /**
         * Finds the pillow entry of a given side ("left"/"right"), falling back to the
         * single side-less entry (solo bed), mirroring the upstream client.
         */
        public @Nullable PillowEntry findPillow(String side) {
            List<PillowEntry> pillows = new ArrayList<>();
            if (devices == null) {
                return null;
            }
            for (PillowEntry entry : devices) {
                if (entry.isPillow()) {
                    pillows.add(entry);
                }
            }
            for (PillowEntry entry : pillows) {
                if (side.equals(entry.getSide())) {
                    return entry;
                }
            }
            if (pillows.size() == 1 && pillows.get(0).getSide() == null) {
                return pillows.get(0);
            }
            return null;
        }

        /** Whether any pod in this payload matches the given device id (bed membership check). */
        public boolean containsPod(String deviceId) {
            if (devices == null || deviceId == null) {
                return false;
            }
            for (PillowEntry entry : devices) {
                if (entry.isPod() && deviceId.equals(entry.getDeviceId())) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class PillowEntry {
        public @Nullable DeviceInfo device;
        public @Nullable Double currentLevel;
        public @Nullable CurrentState currentState;

        public boolean isPillow() {
            return device != null && "pillow".equals(device.specialization);
        }

        public boolean isPod() {
            return device != null && "pod".equals(device.specialization);
        }

        public @Nullable String getSide() {
            return device != null ? device.side : null;
        }

        public @Nullable String getDeviceId() {
            return device != null ? device.deviceId : null;
        }

        public boolean isOn() {
            return currentState != null && currentState.type != null && !"off".equalsIgnoreCase(currentState.type);
        }

        public int getLevel() {
            return currentLevel != null ? (int) Math.round(currentLevel.doubleValue()) : 0;
        }

        public static class DeviceInfo {
            public @Nullable String specialization;
            public @Nullable String side;
            public @Nullable String deviceId;
        }

        public static class CurrentState {
            public @Nullable String type;
        }
    }
}
