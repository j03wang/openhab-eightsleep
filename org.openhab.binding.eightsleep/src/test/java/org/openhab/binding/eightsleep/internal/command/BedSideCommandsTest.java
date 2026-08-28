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
package org.openhab.binding.eightsleep.internal.command;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.api.EightSleepService;
import org.openhab.binding.eightsleep.internal.model.Alarm;
import org.openhab.binding.eightsleep.internal.model.BaseState;
import org.openhab.binding.eightsleep.internal.model.BedSide;
import org.openhab.binding.eightsleep.internal.polling.UserDataCache;
import org.openhab.binding.eightsleep.internal.temperature.HeatingLevelConversion;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;

/**
 * Tests command conversion and base-state merging through observable service calls.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideCommandsTest {

    private final BedSideCommands commands = new BedSideCommands(Clock.systemUTC());

    @Test
    public void fahrenheitQuantityIsConvertedToHeatingLevel() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("targetTemperature"), new QuantityType<>(72.0, ImperialUnits.FAHRENHEIT),
                context(service, null));

        verify(service).setHeatingLevel("u1", HeatingLevelConversion.temperatureToLevel(72, true), 0);
    }

    @Test
    public void celsiusQuantityIsConvertedToHeatingLevel() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("targetTemperature"), new QuantityType<>(22.0, SIUnits.CELSIUS),
                context(service, null));

        verify(service).setHeatingLevel("u1", HeatingLevelConversion.temperatureToLevel(22, false), 0);
    }

    @Test
    public void kelvinQuantityIsConvertedThroughCelsius() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("targetTemperature"), new QuantityType<>(295.15, Units.KELVIN),
                context(service, null));

        verify(service).setHeatingLevel("u1", HeatingLevelConversion.temperatureToLevel(22, false), 0);
    }

    @Test
    public void pillowTemperatureUsesExplicitQuantityUnit() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("pillowTargetTemperature"), new QuantityType<>(72.0, ImperialUnits.FAHRENHEIT),
                context(service, null));

        verify(service).setPillowLevel("u1", HeatingLevelConversion.temperatureToLevel(72, true));
    }

    @Test
    public void incompatibleTemperatureUnitDoesNotCallService() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("targetTemperature"), new QuantityType<>(5, Units.SECOND), context(service, null));

        verify(service, never()).setHeatingLevel(org.mockito.ArgumentMatchers.anyString(), anyInt(), anyInt());
    }

    @Test
    public void scalarTemperatureUsesConfiguredUnit() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("targetTemperature"), new DecimalType("-16.5"), context(service, null));

        verify(service).setHeatingLevel("u1", HeatingLevelConversion.temperatureToLevel(-16.5, false), 0);
    }

    @Test
    public void invalidStringTemperatureDoesNotCallService() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("targetTemperature"), new StringType("warm"), context(service, null));

        verify(service, never()).setHeatingLevel(org.mockito.ArgumentMatchers.anyString(), anyInt(), anyInt());
    }

    @Test
    public void headCommandClampsAngleAndKeepsCachedLegAngle() throws Exception {
        UserDataCache cache = new UserDataCache();
        cache.baseState = new BaseState(new BaseState.SideState(null, 20, 10, false), null);
        EightSleepService service = service();

        commands.dispatch(channel("headAngle"), new DecimalType(99), context(service, cache));

        verify(service).setBaseAngle("u1", "dev1", 20, 45);
    }

    @Test
    public void feetCommandKeepsCachedTorsoAngle() throws Exception {
        UserDataCache cache = new UserDataCache();
        cache.baseState = new BaseState(new BaseState.SideState(null, 20, 40, false), null);
        EightSleepService service = service();

        commands.dispatch(channel("feetAngle"), new DecimalType(15), context(service, cache));

        verify(service).setBaseAngle("u1", "dev1", 15, 40);
    }

    @Test
    public void missingBaseStateKeepsUnknownAxisFlat() throws Exception {
        EightSleepService service = service();
        commands.dispatch(channel("headAngle"), new DecimalType(30), context(service, null));

        verify(service).setBaseAngle("u1", "dev1", 0, 30);
    }

    @Test
    public void alarmSelectionUsesInjectedClock() throws Exception {
        Alarm first = new Alarm("first", LocalTime.of(7, 0), true, null, Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), null, null, Instant.parse("2026-01-01T07:00:00Z"));
        Alarm second = new Alarm("second", LocalTime.of(8, 0), true, null, Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), null, null, Instant.parse("2026-01-01T08:00:00Z"));
        UserDataCache cache = new UserDataCache();
        cache.alarms.addAll(List.of(second, first));
        EightSleepService service = service();
        BedSideCommands fixedCommands = new BedSideCommands(
                Clock.fixed(Instant.parse("2026-01-01T06:00:00Z"), ZoneOffset.UTC));

        fixedCommands.dispatch(channel("alarmEnabled"), OnOffType.OFF, context(service, cache));

        verify(service).setAlarmEnabled("u1", first, false);
    }

    @Test
    public void alarmTimeUsesConfiguredTimeZone() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        Alarm alarm = new Alarm("alarm", LocalTime.of(8, 0), true, null, Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), null, null, now.plusSeconds(3600));
        UserDataCache cache = new UserDataCache();
        cache.alarms.add(alarm);
        EightSleepService service = service();
        BedSideCommands fixedCommands = new BedSideCommands(Clock.fixed(now, ZoneOffset.UTC));
        ZoneId zone = ZoneId.of("America/Los_Angeles");

        fixedCommands.dispatch(channel("alarmTime"), new DateTimeType(Instant.parse("2026-08-29T15:30:00Z")),
                context(service, cache, zone));

        verify(service).setAlarmTime("u1", alarm, "08:30:00");
    }

    private static EightSleepService service() {
        EightSleepService service = mock(EightSleepService.class);
        when(service.setHeatingLevel(org.mockito.ArgumentMatchers.anyString(), anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(service.setBaseAngle(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(service.setAlarmEnabled(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Alarm.class), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(service.setPillowLevel(org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(service.setAlarmTime(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Alarm.class), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return service;
    }

    private static BedSideCommands.Context context(EightSleepService service, UserDataCache cache) {
        return context(service, cache, ZoneOffset.UTC);
    }

    private static BedSideCommands.Context context(EightSleepService service, UserDataCache cache, ZoneId zone) {
        return new BedSideCommands.Context(service, "u1", BedSide.LEFT, false, zone, "dev1",
                cache != null ? cache.snapshot() : null, new CommandState(Clock.systemUTC()), () -> {
                });
    }

    private static ChannelUID channel(String id) {
        ChannelUID channel = mock(ChannelUID.class);
        when(channel.getIdWithoutGroup()).thenReturn(id);
        return channel;
    }
}
