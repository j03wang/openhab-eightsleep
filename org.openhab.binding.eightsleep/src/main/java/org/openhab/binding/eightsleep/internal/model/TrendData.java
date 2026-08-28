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
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Sleep trend data used by the binding after an API response has been mapped.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record TrendData(List<Day> days) {

    public static final TrendData EMPTY = new TrendData(List.of());

    public TrendData {
        days = List.copyOf(days);
    }

    public boolean isEmpty() {
        return days.isEmpty();
    }

    public @Nullable Day getDay(int fromEnd) {
        int index = days.size() - 1 - fromEnd;
        return index >= 0 && index < days.size() ? days.get(index) : null;
    }

    public @Nullable Session getCurrentSession() {
        return currentSessionOf(getDay(0));
    }

    public @Nullable Session getPreviousSession() {
        Day currentDay = getDay(0);
        if (currentDay != null && currentDay.sessions().size() >= 2) {
            return currentDay.sessions().get(currentDay.sessions().size() - 2);
        }
        return currentSessionOf(getDay(1));
    }

    public static @Nullable Session currentSessionOf(@Nullable Day day) {
        if (day == null || day.sessions().isEmpty()) {
            return null;
        }
        return day.sessions().get(day.sessions().size() - 1);
    }

    public record Day(@Nullable String day, @Nullable Double score, @Nullable Double tossAndTurns,
            @Nullable Instant presenceStart, @Nullable Instant presenceEnd, @Nullable Double lightDuration,
            @Nullable Double deepDuration, @Nullable Double remDuration, @Nullable Double sleepDuration,
            @Nullable Double presenceDuration, @Nullable Score sleepQualityScore, @Nullable Score sleepRoutineScore,
            List<Session> sessions) {

        public Day {
            sessions = List.copyOf(sessions);
        }
    }

    public record Score(@Nullable Double total, @Nullable Double hrv, @Nullable Double respiratoryRate) {
    }

    public record Session(@Nullable Instant sleepStart, @Nullable Instant sleepEnd, List<Stage> stages,
            Map<String, List<Sample>> timeseries) {

        public Session {
            stages = List.copyOf(stages);
            timeseries = Map.copyOf(timeseries);
        }

        public @Nullable Double latestValue(String seriesName) {
            List<Sample> samples = timeseries.get(seriesName);
            if (samples == null) {
                return null;
            }
            for (int i = samples.size() - 1; i >= 0; i--) {
                Double value = samples.get(i).value();
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        public @Nullable Instant firstTime(String seriesName) {
            List<Sample> samples = timeseries.get(seriesName);
            return samples != null && !samples.isEmpty() ? samples.get(0).timestamp() : null;
        }

        public @Nullable Instant lastTime(String seriesName) {
            List<Sample> samples = timeseries.get(seriesName);
            return samples != null && !samples.isEmpty() ? samples.get(samples.size() - 1).timestamp() : null;
        }
    }

    public record Stage(@Nullable String name, @Nullable Double durationSeconds) {
    }

    public record Sample(@Nullable Instant timestamp, @Nullable Double value) {
    }
}
