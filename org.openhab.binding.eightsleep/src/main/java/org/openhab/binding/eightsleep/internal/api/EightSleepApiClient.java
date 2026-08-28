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

import static org.openhab.binding.eightsleep.internal.api.ApiHttpClient.urlEncode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.api.dto.ApiRequests;
import org.openhab.binding.eightsleep.internal.api.dto.ApiResponses;
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

    /**
     * Seam for tests: performs an authorized HTTP call. Production delegates to
     * {@link ApiHttpClient}; tests record URLs/bodies and return canned responses.
     */
    public interface Transport {
        /**
         * Sends an authorized HTTP request.
         *
         * @param method the HTTP method
         * @param url the absolute request URL
         * @param jsonBody the serialized request body, or {@code null} when absent
         * @param accessToken the access token, or {@code null} when unavailable
         * @return a future containing the response body
         */
        CompletableFuture<String> send(String method, String url, @Nullable String jsonBody,
                @Nullable String accessToken);

        /**
         * Sends an authorized HTTP request with additional headers.
         *
         * @param method the HTTP method
         * @param url the absolute request URL
         * @param jsonBody the serialized request body, or {@code null} when absent
         * @param accessToken the access token, or {@code null} when unavailable
         * @param headers the additional request headers
         * @return a future containing the response body
         */
        default CompletableFuture<String> sendWithHeaders(String method, String url, @Nullable String jsonBody,
                @Nullable String accessToken, Map<String, String> headers) {
            return send(method, url, jsonBody, accessToken);
        }
    }

    private final TokenManager tokenManager;
    private final Transport transport;

    /**
     * Creates a client using the production HTTP transport.
     *
     * @param tokenManager the OAuth token manager
     */
    public EightSleepApiClient(TokenManager tokenManager) {
        this(tokenManager, ApiHttpClient::send);
    }

    /**
     * Creates a client using the supplied HTTP transport.
     *
     * @param tokenManager the OAuth token manager
     * @param transport the transport used to send requests
     */
    public EightSleepApiClient(TokenManager tokenManager, Transport transport) {
        this.tokenManager = tokenManager;
        this.transport = transport;
    }

    /**
     * Fetches a device.
     *
     * @param deviceId the device identifier
     * @return a future containing the device response
     */
    public CompletableFuture<ApiResponses.DeviceEnvelope> getDevice(String deviceId) {
        return clientGet("/devices/" + urlEncode(deviceId), ApiResponses.DeviceEnvelope.class);
    }

    /**
     * Fetches a user profile.
     *
     * @param userId the user identifier
     * @return a future containing the user-profile response
     */
    public CompletableFuture<ApiResponses.UserProfileEnvelope> getUserProfile(String userId) {
        return clientGet("/users/" + urlEncode(userId), ApiResponses.UserProfileEnvelope.class);
    }

    /**
     * Fetches the authenticated user.
     *
     * @return a future containing the authenticated-user response
     */
    public CompletableFuture<ApiResponses.MeEnvelope> getMe() {
        return clientGet("/users/me", ApiResponses.MeEnvelope.class);
    }

    /**
     * Fetches the household summary for a user.
     *
     * @param userId the user identifier
     * @return a future containing the household-summary response
     */
    public CompletableFuture<ApiResponses.HouseholdSummary> getHouseholdSummary(String userId) {
        return appGet("v1/household/users/" + urlEncode(userId) + "/summary", ApiResponses.HouseholdSummary.class);
    }

    /**
     * Fetches the users and away-side assignments for a device.
     *
     * @param deviceId the device identifier
     * @return a future containing the filtered device-users response
     */
    public CompletableFuture<ApiResponses.DeviceUsersEnvelope> getDeviceUsers(String deviceId) {
        return clientGet("/devices/" + urlEncode(deviceId) + "?filter=leftUserId,rightUserId,awaySides",
                ApiResponses.DeviceUsersEnvelope.class);
    }

    /**
     * Fetches sleep trends for the given interval (v2 API). The result contains the
     * session days; index 0 from the end is the current/most recent session.
     *
     * @param userId the user identifier
     * @param start the first date to include
     * @param end the last date to include
     * @param timezone the API timezone identifier
     * @return a future containing the trends response
     */
    public CompletableFuture<ApiResponses.Trends> getTrends(String userId, ZonedDateTime start, ZonedDateTime end,
            String timezone) {
        String path = "/users/" + urlEncode(userId) + "/trends" + "?tz=" + urlEncode(timezone) + "&from="
                + start.format(DateTimeFormatter.ISO_LOCAL_DATE) + "&to=" + end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "&include-main=false&include-all-sessions=true&model-version=v2";
        return clientGet(path, ApiResponses.Trends.class);
    }

    /**
     * Fetches the alarms configured for a user via the v2 endpoint.
     *
     * @param userId the user identifier
     * @return a future containing the alarms response
     */
    public CompletableFuture<ApiResponses.Alarms> getAlarms(String userId) {
        return appGet("v2/users/" + urlEncode(userId) + "/alarms", ApiResponses.Alarms.class);
    }

    /**
     * Fetches the adjustable base data for a user.
     *
     * @param userId the user identifier
     * @return a future containing the base response
     */
    public CompletableFuture<ApiResponses.Base> getBase(String userId) {
        return appGet("v1/users/" + urlEncode(userId) + "/base", ApiResponses.Base.class);
    }

    /**
     * Fetches the speaker player state. Only present on Pod 5 / speaker equipped beds.
     *
     * @param userId the user identifier
     * @return a future containing the player response
     */
    public CompletableFuture<ApiResponses.Player> getPlayer(String userId) {
        return appGet("v1/users/" + urlEncode(userId) + "/audio/player", ApiResponses.Player.class);
    }

    // ==================== control: device actions ====================

    /**
     * Sets the hub LED brightness (0-100).
     * <p>
     * The device resource accepts a PUT with {@code ledBrightnessLevel} (verified upstream
     * against a live Pod 5).
     *
     * @param deviceId the device identifier
     * @param request the LED-brightness request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setLedBrightness(String deviceId, ApiRequests.LedBrightnessUpdate request) {
        return clientPut("/devices/" + urlEncode(deviceId), request);
    }

    // ==================== control: temperature ====================

    /**
     * Fetches the user temperature resource (currentLevel, currentState, smart schedule).
     *
     * @param userId the user identifier
     * @return a future containing the temperature response
     */
    public CompletableFuture<ApiResponses.Temperature> getTemperature(String userId) {
        return appGet("v1/users/" + urlEncode(userId) + "/temperature", ApiResponses.Temperature.class);
    }

    /**
     * Writes the current heating level.
     *
     * @param userId the user identifier
     * @param request the temperature-level request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setTemperatureLevel(String userId, ApiRequests.TemperatureLevelUpdate request) {
        return appPut("v1/users/" + urlEncode(userId) + "/temperature", request);
    }

    /**
     * Writes the time-based heating settings.
     *
     * @param userId the user identifier
     * @param request the temperature-timer request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setTemperatureTimer(String userId, ApiRequests.TemperatureTimerUpdate request) {
        return appPut("v1/users/" + urlEncode(userId) + "/temperature", request);
    }

    /**
     * Writes the complete smart-temperature schedule.
     *
     * @param userId the user identifier
     * @param request the smart-temperature request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setSmartTemperature(String userId, ApiRequests.SmartTemperatureUpdate request) {
        return appPut("v1/users/" + urlEncode(userId) + "/temperature", request);
    }

    /**
     * Writes the current temperature-control state.
     *
     * @param userId the user identifier
     * @param request the temperature-state request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setTemperatureState(String userId, ApiRequests.TemperatureStateUpdate request) {
        return appPut("v1/users/" + urlEncode(userId) + "/temperature", request);
    }

    // ==================== control: away mode & bed side ====================

    /**
     * Starts away mode: removes the user's current bed-side assignment. Mirrors the
     * official app, which passes the optional return date and partner flags as
     * {@code X-8S-*} headers on the same DELETE.
     *
     * @param userId the user identifier
     * @param request the away-mode header contract
     * @return a future completed when away mode starts
     */
    public CompletableFuture<Void> setAwayMode(String userId, ApiRequests.AwayModeHeaders request) {
        Map<String, String> headers = new HashMap<>();
        if (request.includePartner()) {
            headers.put("X-8S-Include-Partner", "true");
        }
        if (request.returnDate() != null && !request.returnDate().isBlank()) {
            headers.put("X-8S-Return-Date", request.returnDate());
        }
        return appWithHeaders("DELETE", "v1/household/users/" + urlEncode(userId) + "/current-set", headers);
    }

    /**
     * Creates or replaces a scheduled return from away mode.
     *
     * @param userId the user identifier
     * @param request the away-return schedule request
     * @return a future completed when the schedule is stored
     */
    public CompletableFuture<Void> setAwayReturnDate(String userId, ApiRequests.AwayReturnSchedule request) {
        return appPost("v1/household/users/" + urlEncode(userId) + "/schedule", request);
    }

    /**
     * Cancels a scheduled return; the user stays away.
     *
     * @param userId the user identifier
     * @param setId the household set identifier
     * @return a future completed when the schedule is removed
     */
    public CompletableFuture<Void> cancelAwayReturn(String userId, String setId) {
        return appDelete("v1/household/users/" + urlEncode(userId) + "/schedule/" + urlEncode(setId));
    }

    /**
     * Triggers the pod priming procedure. The requesting user receives the notification.
     *
     * @param deviceId the device identifier
     * @param request the priming-task request
     * @return a future completed when the priming task is created
     */
    public CompletableFuture<Void> primePod(String deviceId, ApiRequests.PrimingTask request) {
        return appPost("v1/devices/" + urlEncode(deviceId) + "/priming/tasks", request);
    }

    // ==================== control: pillow & temperature/all ====================

    /**
     * Fetches temperature data for all temperature-controlled devices of this user
     * ({@code /temperature/all}). The payload lists the pod and any pillow, which is
     * how pillow-to-bed membership is verified.
     *
     * @param userId the user identifier
     * @return a future containing the temperature-all response
     */
    public CompletableFuture<ApiResponses.TemperatureAll> getTemperatureAll(String userId) {
        return appGet("v1/users/" + urlEncode(userId) + "/temperature/all", ApiResponses.TemperatureAll.class);
    }

    /**
     * Writes the pillow power state.
     *
     * @param userId the user identifier
     * @param request the temperature-state request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setPillowState(String userId, ApiRequests.TemperatureStateUpdate request) {
        return putPillow(userId, request);
    }

    /**
     * Writes the pillow heating level.
     *
     * @param userId the user identifier
     * @param request the temperature-level request
     * @return a future completed when the update succeeds
     */
    public CompletableFuture<Void> setPillowLevel(String userId, ApiRequests.TemperatureLevelUpdate request) {
        return putPillow(userId, request);
    }

    private CompletableFuture<Void> putPillow(String userId, ApiRequests.Request request) {
        return appPut("v1/users/" + urlEncode(userId) + "/temperature/pillow", request);
    }

    /**
     * Writes the user's current device and side assignment.
     *
     * @param userId the user identifier
     * @param request the current-device request
     * @return a future completed when the assignment succeeds
     */
    public CompletableFuture<Void> setBedSide(String userId, ApiRequests.CurrentDeviceUpdate request) {
        return clientPut("/users/" + urlEncode(userId) + "/current-device", request);
    }

    // ==================== control: alarms ====================

    /**
     * Snoozes the currently ringing alarm.
     * <p>
     * Upstream: {@code PUT /alarms/{nextAlarmId}/snooze} with
     * {@code {"snoozeMinutes": N, "ignoreDeviceErrors": false}}; 409 means not ringing.
     *
     * @param userId the user identifier
     * @param alarmId the alarm identifier
     * @param request the alarm-snooze request
     * @return a future completed when the alarm is snoozed
     */
    public CompletableFuture<Void> snoozeAlarm(String userId, String alarmId, ApiRequests.SnoozeAlarm request) {
        return appPut("v1/users/" + urlEncode(userId) + "/alarms/" + urlEncode(alarmId) + "/snooze", request);
    }

    /**
     * Dismisses an alarm (also used as "stop" upstream - there is no separate stop endpoint).
     * <p>
     * Upstream: {@code PUT /alarms/{alarmId}/dismiss} with {@code {"ignoreDeviceErrors": false}}.
     *
     * @param userId the user identifier
     * @param alarmId the alarm identifier
     * @param request the alarm-dismissal request
     * @return a future completed when the alarm is dismissed
     */
    public CompletableFuture<Void> dismissAlarm(String userId, String alarmId, ApiRequests.DismissAlarm request) {
        return appPut("v1/users/" + urlEncode(userId) + "/alarms/" + urlEncode(alarmId) + "/dismiss", request);
    }

    /**
     * Replaces an alarm definition.
     *
     * @param userId the user identifier
     * @param alarmId the alarm identifier
     * @param request the alarm-update request
     * @return a future completed when the alarm is updated
     */
    public CompletableFuture<Void> updateAlarm(String userId, String alarmId, ApiRequests.AlarmUpdate request) {
        return appPut("v1/users/" + urlEncode(userId) + "/alarms/" + urlEncode(alarmId), request);
    }

    // ==================== control: adjustable base ====================

    /**
     * Sets the adjustable base angle for a user side.
     * <p>
     * Uses POST {@code /base/angle?ignoreDeviceErrors=false} with a device-scoped
     * payload, matching the verified upstream client.
     *
     * @param userId the user identifier
     * @param request the base-angle request
     * @return a future completed when the base angle is updated
     */
    public CompletableFuture<Void> setBaseAngle(String userId, ApiRequests.BaseAngle request) {
        return appPost("v1/users/" + urlEncode(userId) + "/base/angle?ignoreDeviceErrors=false", request);
    }

    /**
     * Sets the base preset ({@code sleep}, {@code relaxing} or {@code reading}).
     *
     * @param userId the user identifier
     * @param request the base-preset request
     * @return a future completed when the preset is applied
     */
    public CompletableFuture<Void> setBasePreset(String userId, ApiRequests.BasePreset request) {
        return appPost("v1/users/" + urlEncode(userId) + "/base/angle?ignoreDeviceErrors=false", request);
    }

    // ==================== speaker control ====================

    /**
     * Writes the audio player state.
     *
     * @param userId the user identifier
     * @param request the player-state request
     * @return a future completed when the player state is updated
     */
    public CompletableFuture<Void> setPlayerState(String userId, ApiRequests.PlayerState request) {
        return appPut("v1/users/" + urlEncode(userId) + "/audio/player/state", request);
    }

    /**
     * Writes the audio player volume.
     *
     * @param userId the user identifier
     * @param request the player-volume request
     * @return a future completed when the volume is updated
     */
    public CompletableFuture<Void> setPlayerVolume(String userId, ApiRequests.PlayerVolume request) {
        return appPut("v1/users/" + urlEncode(userId) + "/audio/player/volume", request);
    }

    /**
     * Writes the current audio track.
     *
     * @param userId the user identifier
     * @param request the player-track request
     * @return a future completed when the current track is updated
     */
    public CompletableFuture<Void> setPlayerTrack(String userId, ApiRequests.PlayerTrack request) {
        return appPut("v1/users/" + urlEncode(userId) + "/audio/player/currentTrack", request);
    }

    // ==================== plumbing ====================

    private <T> CompletableFuture<T> clientGet(String path, Class<T> responseType) {
        return get(ApiConstants.CLIENT_API_URL + path, responseType);
    }

    private <T> CompletableFuture<T> appGet(String path, Class<T> responseType) {
        return get(ApiConstants.APP_API_URL + path, responseType);
    }

    private <T> CompletableFuture<T> get(String url, Class<T> responseType) {
        return withAuthRetry(token -> transport.send("GET", url, null, token))
                .thenApply(body -> GsonHelper.fromJson(body, responseType));
    }

    private CompletableFuture<Void> clientPut(String path, ApiRequests.Request request) {
        return send("PUT", ApiConstants.CLIENT_API_URL + path, request);
    }

    private CompletableFuture<Void> appPut(String path, ApiRequests.Request request) {
        return send("PUT", ApiConstants.APP_API_URL + path, request);
    }

    private CompletableFuture<Void> appPost(String path, ApiRequests.Request request) {
        return send("POST", ApiConstants.APP_API_URL + path, request);
    }

    private CompletableFuture<Void> appDelete(String path) {
        return send("DELETE", ApiConstants.APP_API_URL + path, null);
    }

    private CompletableFuture<Void> send(String method, String url, ApiRequests.@Nullable Request request) {
        String body = request != null ? GsonHelper.toJson(request) : null;
        return withAuthRetry(token -> transport.send(method, url, body, token)).thenApply(ignored -> null);
    }

    private CompletableFuture<Void> appWithHeaders(String method, String path, Map<String, String> headers) {
        return withAuthRetry(
                token -> transport.sendWithHeaders(method, ApiConstants.APP_API_URL + path, null, token, headers))
                .thenApply(ignored -> null);
    }

    /**
     * Runs the request with a valid access token. On a 401 failure the token is
     * invalidated and the request is retried exactly once with a fresh token.
     */
    private CompletableFuture<String> withAuthRetry(
            java.util.function.Function<String, CompletableFuture<String>> invoker) {
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
     * Supplies a valid access token without blocking: token acquisition composes
     * asynchronously so no scheduler or completion thread waits on authentication.
     */
    private CompletableFuture<String> supplyToken() {
        return tokenManager.getAccessTokenAsync();
    }
}
