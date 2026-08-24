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
package org.openhab.binding.eightsleep.internal.api.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.TrendParser;

/**
 * An alarm entry from the alarms API.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class Alarm {
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