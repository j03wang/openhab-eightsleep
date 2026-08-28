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
package org.openhab.binding.eightsleep.internal.sync;

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;
import static org.openhab.binding.eightsleep.internal.sync.SyncChannels.*;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.model.TrendData;
import org.openhab.binding.eightsleep.internal.sleep.SleepSession;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

@NonNullByDefault
final class SleepChannelMapper {

    private SleepChannelMapper() {
        throw new IllegalAccessError("Non-instantiable");
    }

    static void publish(TrendData trends, @Nullable Double heatingLevel, boolean fahrenheit, Instant now,
            SyncCollector collector) {
        TrendData.Session currentSession = trends.getCurrentSession();
        Double measuredBedC = currentSession != null ? currentSession.latestValue("tempBedC") : null;
        if (!trends.isEmpty()) {
            publishCurrent(trends.getDay(0), currentSession, now, collector);
            publishPrevious(trends.getDay(1), collector);
        }
        if (measuredBedC != null) {
            add(collector, GROUP_CURRENT, CHANNEL_BED_TEMPERATURE, new QuantityType<>(measuredBedC, SIUnits.CELSIUS));
        } else if (heatingLevel != null) {
            addTemperature(collector, GROUP_CURRENT, CHANNEL_BED_TEMPERATURE, heatingLevel, fahrenheit);
        }
    }

    private static void publishCurrent(TrendData.@Nullable Day day, TrendData.@Nullable Session session, Instant now,
            SyncCollector collector) {
        if (day != null) {
            if (day.presenceStart() != null) {
                add(collector, GROUP_CURRENT, CHANNEL_SESSION_START, new DateTimeType(day.presenceStart()));
            }
            if (day.presenceEnd() != null) {
                add(collector, GROUP_CURRENT, CHANNEL_SESSION_END, new DateTimeType(day.presenceEnd()));
            }
            putDecimal(collector, GROUP_CURRENT, CHANNEL_SLEEP_SCORE, day.score());
            TrendData.Score quality = day.sleepQualityScore();
            TrendData.Score routine = day.sleepRoutineScore();
            putDecimal(collector, GROUP_CURRENT, CHANNEL_QUALITY_SCORE, quality != null ? quality.total() : null);
            putDecimal(collector, GROUP_CURRENT, CHANNEL_ROUTINE_SCORE, routine != null ? routine.total() : null);
            putDecimal(collector, GROUP_CURRENT, CHANNEL_HRV, quality != null ? quality.hrv() : null);
            putDecimal(collector, GROUP_CURRENT, CHANNEL_BREATH_RATE,
                    quality != null ? quality.respiratoryRate() : null);
            putDecimal(collector, GROUP_LAST_SLEEP, CHANNEL_TOSS_TURNS, day.tossAndTurns());
        }
        if (session == null) {
            add(collector, GROUP_BASE, CHANNEL_BED_PRESENCE, OnOffType.OFF);
            return;
        }
        putLatestCelsius(collector, session, "tempRoomC", GROUP_DEVICE, CHANNEL_ROOM_TEMPERATURE);
        putLatest(collector, session, "heartRate", GROUP_CURRENT, CHANNEL_HEART_RATE);
        putLatest(collector, session, "respiratoryRate", GROUP_CURRENT, CHANNEL_RESPIRATORY_RATE);
        add(collector, GROUP_BASE, CHANNEL_BED_PRESENCE, OnOffType.from(SleepSession.isPresent(session, now)));
        String stage = SleepSession.currentStage(session, now);
        if (stage != null) {
            add(collector, GROUP_CURRENT, CHANNEL_SLEEP_STAGE, new StringType(stage));
        }
    }

    private static void publishPrevious(TrendData.@Nullable Day day, SyncCollector collector) {
        if (day == null) {
            return;
        }
        putDecimal(collector, GROUP_LAST_SLEEP, CHANNEL_SLEEP_SCORE, day.score());
        TrendData.Score quality = day.sleepQualityScore();
        TrendData.Score routine = day.sleepRoutineScore();
        putDecimal(collector, GROUP_LAST_SLEEP, CHANNEL_QUALITY_SCORE, quality != null ? quality.total() : null);
        putDecimal(collector, GROUP_LAST_SLEEP, CHANNEL_ROUTINE_SCORE, routine != null ? routine.total() : null);

        Double light = day.lightDuration();
        Double deep = day.deepDuration();
        Double rem = day.remDuration();
        Double presence = day.presenceDuration();
        Double sleep = day.sleepDuration();
        putDuration(collector, GROUP_LAST_SLEEP, CHANNEL_LIGHT_SLEEP, light);
        putDuration(collector, GROUP_LAST_SLEEP, CHANNEL_DEEP_SLEEP, deep);
        putDuration(collector, GROUP_LAST_SLEEP, CHANNEL_REM_SLEEP, rem);
        putDuration(collector, GROUP_LAST_SLEEP, CHANNEL_TIME_SLEPT,
                sleep != null ? sleep : light != null && deep != null && rem != null ? light + deep + rem : null);
        if (presence != null && sleep != null) {
            putDuration(collector, GROUP_LAST_SLEEP, CHANNEL_AWAKE_DURATION, presence - sleep);
        }
        if (day.presenceStart() != null) {
            add(collector, GROUP_LAST_SLEEP, CHANNEL_SESSION_START, new DateTimeType(day.presenceStart()));
        }
        if (day.presenceEnd() != null) {
            add(collector, GROUP_LAST_SLEEP, CHANNEL_SESSION_END, new DateTimeType(day.presenceEnd()));
        }
    }

    private static void putDecimal(SyncCollector collector, String group, String channel, @Nullable Double value) {
        if (value != null) {
            add(collector, group, channel, new DecimalType(value));
        }
    }

    private static void putLatest(SyncCollector collector, TrendData.Session session, String series, String group,
            String channel) {
        putDecimal(collector, group, channel, session.latestValue(series));
    }

    private static void putLatestCelsius(SyncCollector collector, TrendData.Session session, String series,
            String group, String channel) {
        Double value = session.latestValue(series);
        if (value != null) {
            add(collector, group, channel, new QuantityType<>(value, SIUnits.CELSIUS));
        }
    }

    private static void putDuration(SyncCollector collector, String group, String channel, @Nullable Double seconds) {
        if (seconds != null && seconds >= 0) {
            add(collector, group, channel, new QuantityType<>(seconds, Units.SECOND));
        }
    }
}
