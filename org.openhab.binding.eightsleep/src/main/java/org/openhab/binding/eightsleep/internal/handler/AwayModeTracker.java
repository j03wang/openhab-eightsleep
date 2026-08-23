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
package org.openhab.binding.eightsleep.internal.handler;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Away-mode last-write-wins bookkeeping: commands and polls are stamped with
 * their issue times, so ordering falls out of the timestamps. A poll that STARTED
 * before a command carries pre-command data and loses; one started after wins -
 * including when it still reports the old value, because then either the server
 * has not applied the change or the user changed it back elsewhere. Both are
 * server truth and must be shown.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class AwayModeTracker {

    private volatile boolean polledOnce;
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> commandedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether an away-state observation may overwrite a commanded value: only polls
     * that started strictly after the command count as newer information. The same
     * rule as LastWriteWins.resolveLatest, expressed for the away bookkeeping.
     */
    public static boolean acceptsPolledAway(@Nullable Instant commandedAt, Instant observedAt) {
        return commandedAt == null || !observedAt.isBefore(commandedAt);
    }

    /** Records a user-initiated away command for last-write-wins arbitration. */
    public void recordCommand(String userId) {
        commandedAt.put(userId, Instant.now());
        polledOnce = true;
    }

    /** The command stamp for a user, or null when no command was recorded. */
    public @Nullable Instant commandedAtOf(String userId) {
        return commandedAt.get(userId);
    }

    /** True once an away state is known (commanded or polled). */
    public boolean isKnown() {
        return polledOnce;
    }
}
