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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Adjustable base data as returned by {@code GET /v1/users/{userId}/base}.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BaseData {

    public @Nullable SideData left;
    public @Nullable SideData right;

    /**
     * Returns the base data of a side ({@code "left"} or {@code "right"}).
     */
    public @Nullable SideData getSide(String side) {
        return "right".equalsIgnoreCase(side) ? right : left;
    }

    public static class SideData {
        public @Nullable Preset preset;
        public @Nullable Leg leg;
        public @Nullable Torso torso;
        public @Nullable Boolean inSnoreMitigation;
    }

    public static class Preset {
        public @Nullable String name;
    }

    public static class Leg {
        public @Nullable Integer currentAngle;
    }

    public static class Torso {
        public @Nullable Integer currentAngle;
    }
}
