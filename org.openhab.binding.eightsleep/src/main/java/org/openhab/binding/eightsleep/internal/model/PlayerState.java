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
 * Current speaker player state.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record PlayerState(@Nullable String state, @Nullable Integer volume, @Nullable Track currentTrack,
        boolean hasSpeaker) {

    public static final PlayerState EMPTY = new PlayerState(null, null, null, false);

    /**
     * Returns whether the player is playing.
     *
     * @return {@code true} when the API state is playing
     */
    public boolean isPlaying() {
        return "playing".equalsIgnoreCase(state);
    }

    /**
     * Returns whether the player is paused.
     *
     * @return {@code true} when the API state is paused
     */
    public boolean isPaused() {
        return "paused".equalsIgnoreCase(state);
    }

    public record Track(@Nullable String id, @Nullable String name, @Nullable String categoryId,
            @Nullable Double currentPosition, @Nullable Double trackDuration) {
    }
}
