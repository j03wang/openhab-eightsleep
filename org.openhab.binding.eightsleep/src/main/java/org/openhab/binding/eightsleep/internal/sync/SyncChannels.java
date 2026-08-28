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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.eightsleep.internal.temperature.HeatingLevelConversion;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.types.State;

@NonNullByDefault
final class SyncChannels {

    private SyncChannels() {
        throw new IllegalAccessError("Non-instantiable");
    }

    static void add(SyncCollector collector, String group, String channel, State state) {
        collector.updates.add(new ChannelUpdate(group + "#" + channel, state));
    }

    static void addTemperature(SyncCollector collector, String group, String channel, double level,
            boolean fahrenheit) {
        add(collector, group, channel, new QuantityType<>(temperature(level, fahrenheit),
                fahrenheit ? ImperialUnits.FAHRENHEIT : SIUnits.CELSIUS));
    }

    static double temperature(double level, boolean fahrenheit) {
        return HeatingLevelConversion.levelToTemperature(level, fahrenheit);
    }
}
