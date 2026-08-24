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
 * Speaker player state as returned by {@code GET /v1/users/{userId}/audio/player}.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class PlayerState {

    /** {@code Playing} or {@code Paused}. */
    public @Nullable String state;
    public @Nullable Integer volume;
    public @Nullable Track currentTrack;
    public @Nullable HardwareInfo hardwareInfo;

    public boolean hasSpeaker() {
        return hardwareInfo != null;
    }

    public boolean isPlaying() {
        return "playing".equalsIgnoreCase(state);
    }

    public boolean isPaused() {
        return "paused".equalsIgnoreCase(state);
    }

    /**
     * The reported volume in percent; {@code null} when the API did not include a
     * volume, so callers can distinguish "unknown" from an actual level of zero.
     */
    public @Nullable Integer getVolumePercent() {
        return volume;
    }

    public static class Track {
        public @Nullable String id;
        public @Nullable String name;
        public @Nullable String categoryId;
        public @Nullable Double currentPosition;
        public @Nullable Double trackDuration;
    }

    public static class HardwareInfo {
        public @Nullable String sku;
        public @Nullable String hardwareVersion;
        public @Nullable String softwareVersion;
    }
}
