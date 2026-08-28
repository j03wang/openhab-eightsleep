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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.sync.LastWriteWins.CommandedValue;

/**
 * Regression tests for the last-write-wins merge used by every mutable channel
 * (side power, away mode, alarm enabled).
 *
 * Bugs guarded against:
 * <ul>
 * <li>a stale polled payload overwriting a fresh command (the "side power stuck"
 * reports)</li>
 * <li>an override window expiring before the server applied the change (the
 * "alarm switch flips back ON" report)</li>
 * </ul>
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class LastWriteWinsMergeTest {

    private static final Instant T0 = Instant.parse("2026-08-22T15:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-22T15:01:00Z");

    private static @Nullable CommandedValue cmd(Instant at, boolean on) {
        return new CommandedValue(at, on);
    }

    /** Delegates to the extracted resolver. */
    private static @Nullable Boolean resolve(Boolean polledOn, @Nullable Instant polledAt,
            @Nullable CommandedValue commanded) {
        return LastWriteWins.resolveLatest(polledOn, polledAt, commanded);
    }

    @Test
    public void newerCommandBeatsOlderPoll() {
        assertTrue(resolve(false, T0, cmd(T1, true)));
    }

    @Test
    public void newerPollBeatsOlderCommand() {
        assertFalse(resolve(false, T1, cmd(T0, true)));
        assertTrue(resolve(true, T1, cmd(T0, false)));
    }

    /** Ties go to the polled value: the server is authoritative. */
    @Test
    public void equalTimestampsFavorPolled() {
        assertFalse(resolve(false, T0, cmd(T0, true)));
        assertTrue(resolve(true, T0, cmd(T0, false)));
    }

    @Test
    public void noCommandPassesPollThrough() {
        assertTrue(resolve(true, T0, null));
        assertFalse(resolve(false, T0, null));
    }

    @Test
    public void noPollKeepsCommand() {
        assertTrue(resolve(null, null, cmd(T0, true)));
        assertFalse(resolve(null, null, cmd(T0, false)));
    }

    @Test
    public void noSourcesYieldsNothing() {
        assertNull(resolve(null, null, null));
    }

    // ==================== shouldRetireCommand ====================

    /**
     * A command entry may only be dropped once the polled value agrees with what was
     * published (the server confirmed). If the command beat a contradicting stale
     * poll, the entry must be kept - retiring it would let that stale poll flicker
     * back on the next sync.
     */
    @Test
    public void commandRetiredOnlyWhenPolledValueConfirms() {
        // server confirmed: polled agrees with resolved -> retire
        assertTrue(LastWriteWins.shouldRetireCommand(true, true));
        assertTrue(LastWriteWins.shouldRetireCommand(false, false));

        // command beat a contradicting (stale) poll -> keep waiting for confirmation
        assertFalse(LastWriteWins.shouldRetireCommand(true, false));
        assertFalse(LastWriteWins.shouldRetireCommand(false, true));

        // nothing polled yet -> nothing to confirm against
        assertFalse(LastWriteWins.shouldRetireCommand(null, true));
        assertFalse(LastWriteWins.shouldRetireCommand(null, null));
    }
}
