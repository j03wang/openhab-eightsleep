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
package org.openhab.binding.eightsleep.internal.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Defensive accessor for the v1 trends API payload.
 * <p>
 * The trends response is heterogeneous (values can be numbers, the literal string
 * "None", or nested objects that vary between sessions), so instead of typed Gson
 * models — which fail wholesale on one unexpected field — every lookup walks the
 * raw JSON and degrades to {@code null}.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class TrendParser {

    private final List<JsonObject> days = new ArrayList<>();

    /**
     * Builds the parser from the raw "days" array of the trends response.
     */
    public TrendParser(JsonArray days) {
        for (JsonElement day : days) {
            if (day != null && day.isJsonObject()) {
                this.days.add(day.getAsJsonObject());
            }
        }
    }

    public boolean isEmpty() {
        return days.isEmpty();
    }

    /** Day index counted from the end like the upstream client: 0 = latest day. */
    public @Nullable JsonObject getDay(int fromEnd) {
        int idx = days.size() - 1 - fromEnd;
        return idx >= 0 && idx < days.size() ? days.get(idx) : null;
    }

    /**
     * Returns all sessions of a day flattened oldest-first; index size-1 is the
     * current session.
     */
    public List<JsonObject> getSessions(int dayFromEnd) {
        List<JsonObject> sessions = new ArrayList<>();
        JsonObject day = getDay(dayFromEnd);
        if (day == null) {
            return sessions;
        }
        JsonElement arr = day.get("sessions");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement s : arr.getAsJsonArray()) {
                if (s.isJsonObject()) {
                    sessions.add(s.getAsJsonObject());
                }
            }
        }
        return sessions;
    }

    /** Latest day's latest session (the current sleep session). */
    public @Nullable JsonObject getCurrentSession() {
        List<JsonObject> sessions = getSessions(0);
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    /** Latest session of an arbitrary day object. */
    public static @Nullable JsonObject getCurrentSessionOf(@Nullable JsonObject day) {
        if (day == null) {
            return null;
        }
        JsonElement arr = day.get("sessions");
        if (arr == null || !arr.isJsonArray()) {
            return null;
        }
        List<JsonObject> sessions = new ArrayList<>();
        for (JsonElement s : arr.getAsJsonArray()) {
            if (s.isJsonObject()) {
                sessions.add(s.getAsJsonObject());
            }
        }
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    /** Second-to-last session across the newest day(s) — the previous sleep. */
    public @Nullable JsonObject getPreviousSession() {
        List<JsonObject> sessions = getSessions(0);
        if (sessions.size() >= 2) {
            return sessions.get(sessions.size() - 2);
        }
        // fall back to the last session of the previous day
        List<JsonObject> prevDaySessions = getSessions(1);
        return prevDaySessions.isEmpty() ? null : prevDaySessions.get(prevDaySessions.size() - 1);
    }

    // ==================== value accessors ====================

    public static @Nullable Double getDouble(@Nullable JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(field);
        return asDouble(el);
    }

    public static @Nullable Boolean getBoolean(@Nullable JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(field);
        return el != null && el.isJsonPrimitive() ? el.getAsBoolean() : null;
    }

    public static @Nullable String getString(@Nullable JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(field);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** Object member access that tolerates missing/non-object intermediates. */
    public static @Nullable JsonObject getObject(@Nullable JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(field);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static @Nullable Double asDouble(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return Double.parseDouble(el.getAsString().trim());
        } catch (NumberFormatException e) {
            // covers the literal "None" and any other junk
            return null;
        }
    }

    /**
     * Last numeric value of a timeseries series, e.g. series "heartRate". Entries are
     * [timestamp, value] pairs where either side may be a string ("None" included).
     */
    public static @Nullable Double latestSeriesValue(@Nullable JsonObject session, String seriesName) {
        JsonObject ts = getObject(session, "timeseries");
        JsonElement el = ts != null ? ts.get(seriesName) : null;
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        JsonArray entries = el.getAsJsonArray();
        for (int i = entries.size() - 1; i >= 0; i--) {
            JsonElement entry = entries.get(i);
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() < 2) {
                continue;
            }
            Double value = asDouble(entry.getAsJsonArray().get(1));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Timestamp string of the first entry of a series (session start marker). */
    public static @Nullable Instant firstEntryTime(@Nullable JsonObject session, String seriesName) {
        JsonObject ts = getObject(session, "timeseries");
        JsonElement el = ts != null ? ts.get(seriesName) : null;
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() == 0) {
            return null;
        }
        JsonElement entry = el.getAsJsonArray().get(0);
        if (!entry.isJsonArray() || entry.getAsJsonArray().size() < 1) {
            return null;
        }
        return parseTimestamp(entry.getAsJsonArray().get(0).getAsString());
    }

    /** Timestamp string of the last entry of a series. */
    public static @Nullable Instant lastEntryTime(@Nullable JsonObject session, String seriesName) {
        JsonObject ts = getObject(session, "timeseries");
        JsonElement el = ts != null ? ts.get(seriesName) : null;
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() == 0) {
            return null;
        }
        JsonElement entry = el.getAsJsonArray().get(el.getAsJsonArray().size() - 1);
        if (!entry.isJsonArray() || entry.getAsJsonArray().size() < 1) {
            return null;
        }
        return parseTimestamp(entry.getAsJsonArray().get(0).getAsString());
    }

    public static @Nullable Instant parseTimestamp(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(value.trim()).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * Parses an "HH:mm:ss" time-of-day, tolerating fractional seconds (the first
     * 8 characters are used) and returning null for blank/malformed input
     * instead of throwing. Static and unit-testable.
     */
    public static java.time.@Nullable LocalTime parseTimeOfDay(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalTime.parse(value.trim().substring(0, Math.min(8, value.trim().length())));
        } catch (Exception e) {
            return null;
        }
    }}
