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
package org.openhab.binding.eightsleep.internal.sleep;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.TrendData;

/**
 * Decisions over one live sleep session: the current sleep stage derived from the
 * session's stage segments, and bed presence from fresh heart-rate data.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class SleepSession {

    /** Heart rate data younger than this confirms bed presence. */
    public static final long PRESENCE_FRESH_SECONDS = 600;

    private SleepSession() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Resolves the current sleep stage from the session's {@code stages} segments
     * (verified shape: [{"stage":"awake","duration":2070}, ...] oldest-first).
     * "Now" must fall inside the session window [sleepStart, sleepEnd] - the only
     * verified currency signal (live captures carry no "processing" flag) - and the
     * stage is the segment covering the elapsed time.
     *
     * @return "awake"/"light"/"deep"/"rem", or null when unknown/not currently sleeping
     */
    public static @Nullable String currentStage(TrendData.@Nullable Session session, Instant now) {
        if (session == null) {
            return null;
        }
        Instant sleepStart = session.sleepStart();
        Instant sleepEnd = session.sleepEnd();
        if (sleepStart == null || sleepEnd == null || now.isBefore(sleepStart) || now.isAfter(sleepEnd)) {
            return null;
        }
        long elapsedSeconds = Duration.between(sleepStart, now).getSeconds();
        String current = null;
        long consumed = 0;
        for (TrendData.Stage segment : session.stages()) {
            Double duration = segment.durationSeconds();
            String stage = segment.name();
            if (duration == null || stage == null) {
                continue;
            }
            if (elapsedSeconds < consumed + duration) {
                current = stage.toLowerCase();
                break;
            }
            // the last fully elapsed segment stays current until new data arrives
            current = stage.toLowerCase();
            consumed += duration.longValue();
        }
        return current;
    }

    /**
     * Bed presence detection: heart rate data with a timestamp younger than
     * {@link #PRESENCE_FRESH_SECONDS} (either direction - future timestamps are
     * tolerated as clock skew) confirms presence.
     */
    public static boolean isPresent(TrendData.@Nullable Session session, Instant now) {
        Instant heartBeatTime = session != null ? session.lastTime("heartRate") : null;
        return heartBeatTime != null
                && Duration.between(heartBeatTime, now).abs().getSeconds() < PRESENCE_FRESH_SECONDS;
    }
}
