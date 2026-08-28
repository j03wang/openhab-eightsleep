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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Current temperature state and smart schedule.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record TemperatureState(@Nullable Double currentLevel, @Nullable String stateType, Map<String, Integer> smart) {

    public static final TemperatureState EMPTY = new TemperatureState(null, null, Map.of());

    public TemperatureState {
        smart = Collections.unmodifiableMap(new HashMap<>(smart));
    }

    /**
     * Returns the configured heating level for a smart-temperature stage.
     *
     * @param stage the API stage name
     * @return the heating level, or {@code null} if the stage has no level
     */
    public @Nullable Double smartLevel(String stage) {
        Integer level = smart.get(stage);
        return level != null ? level.doubleValue() : null;
    }
}
