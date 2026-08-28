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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Alarm state used by the binding.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record Alarm(@Nullable String id, @Nullable LocalTime time, @Nullable Boolean enabled,
        @Nullable AlarmRepeat repeat, Map<String, Object> thermal, Map<String, Object> vibration,
        Map<String, Object> audio, Map<String, Object> smart, List<String> tags, @Nullable Boolean skipNext,
        @Nullable Boolean snoozing, @Nullable Instant nextRun) {

    public Alarm {
        thermal = immutableMap(thermal);
        vibration = immutableMap(vibration);
        audio = immutableMap(audio);
        smart = immutableMap(smart);
        tags = List.copyOf(tags);
    }

    /**
     * Advances a timestamp by whole weeks until it is not before the supplied instant.
     *
     * @param timestamp the timestamp to advance
     * @param now the earliest accepted instant
     * @return the original or advanced timestamp, or {@code null} when none was supplied
     */
    public static @Nullable Instant rollToNextWeek(@Nullable Instant timestamp, Instant now) {
        if (timestamp == null) {
            return null;
        }
        Instant rolled = timestamp;
        while (rolled.isBefore(now)) {
            rolled = rolled.plus(Duration.ofDays(7));
        }
        return rolled;
    }

    /**
     * Computes the next alarm occurrence relative to the current instant.
     *
     * @param zone the scheduling time zone
     * @return the next occurrence, or {@code null} if it cannot be determined
     */
    public @Nullable Instant computeNextRun(ZoneId zone) {
        return computeNextRun(zone, Instant.now());
    }

    /**
     * Computes the next alarm occurrence relative to a supplied instant.
     *
     * @param zone the scheduling time zone
     * @param now the instant from which to compute
     * @return the next occurrence, or {@code null} if it cannot be determined
     */
    public @Nullable Instant computeNextRun(ZoneId zone, Instant now) {
        if (time == null) {
            return null;
        }
        boolean repeating = Boolean.TRUE.equals(repeat != null ? repeat.enabled() : null);
        Map<String, Boolean> weekDays = repeat != null ? repeat.weekDays() : Map.of();
        if (!repeating) {
            return nextRun != null && !nextRun.isBefore(now) ? nextRun : rollToNextWeek(nextRun, now);
        }

        boolean[] mask = new boolean[7];
        boolean anyDay = false;
        String[] names = { "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday" };
        for (int i = 0; i < names.length; i++) {
            if (Boolean.TRUE.equals(weekDays.get(names[i]))) {
                mask[i] = true;
                anyDay = true;
            }
        }
        if (!anyDay) {
            Arrays.fill(mask, true);
        }
        LocalDate date = now.atZone(zone).toLocalDate();
        for (int addDays = 0; addDays < 8; addDays++) {
            LocalDate candidateDate = date.plusDays(addDays);
            if (!mask[candidateDate.getDayOfWeek().getValue() - 1]) {
                continue;
            }
            Instant candidate = candidateDate.atTime(time).atZone(zone).toInstant();
            if (!candidate.isBefore(now)) {
                return candidate;
            }
        }
        return null;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new HashMap<>(source));
    }

    public record AlarmRepeat(@Nullable Boolean enabled, Map<String, Boolean> weekDays) {
        public AlarmRepeat {
            weekDays = immutableMap(weekDays);
        }
    }
}
