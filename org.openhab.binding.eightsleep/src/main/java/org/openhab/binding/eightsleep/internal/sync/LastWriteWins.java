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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Last-write-wins merging between channel commands and polled server values,
 * shared by every mutable channel (side power, away mode, alarm enabled).
 * <p>
 * A command is stamped with its time; a poll payload carries the time it was
 * OBSERVED (its start). Whichever source spoke more recently wins; ties go to
 * the polled value because the server is authoritative. A pending command is
 * retired only once a polled value CONFIRMS it - retiring on a contradicting
 * stale poll would let that stale value flicker back next cycle.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class LastWriteWins {

    /** A value written by a channel command, stamped with the command time. */
    public record CommandedValue(Instant at, boolean on) {
    }

    private LastWriteWins() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Whichever observation was stamped more recently wins; ties go to the polled
     * value (the server is authoritative). Returns null when no source has spoken.
     */
    public static @Nullable Boolean resolveLatest(@Nullable Boolean polledOn, @Nullable Instant polledAt,
            @Nullable CommandedValue commanded) {
        if (commanded == null) {
            return polledOn;
        }
        if (polledOn == null || polledAt == null) {
            return commanded.on();
        }
        return polledAt.isBefore(commanded.at()) ? commanded.on() : polledOn;
    }

    /**
     * A pending channel command can be dropped once the polled (server) value agrees
     * with what was published - i.e. the polled value WON the merge and the server
     * has confirmed the command. When the command merely beat a stale contradicting
     * poll, the entry must be kept or that stale poll would flicker back next cycle.
     */
    public static boolean shouldRetireCommand(@Nullable Boolean polledValue, @Nullable Boolean resolved) {
        return polledValue != null && polledValue.equals(resolved);
    }
}
