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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.handler.BedSideHandler.CommandedValue;

/**
 * Regression for the poll/command race: a poll that STARTED before a command but
 * FINISHED after it carries pre-command data. Stamping the payload with the
 * poll's start time (not completion time) makes LWW resolve to the command.
 * <p>
 * Both scenarios use CONTRADICTING values so the assertion fails if the merge
 * picks the wrong source.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class PollRaceTest {

    /** Command issued mid-poll must win over that poll's stale payload. */
    @Test
    public void commandIssuedMidPollWins() {
        Instant pollStarted = Instant.parse("2026-08-22T21:00:00Z");
        Instant commandAt = Instant.parse("2026-08-22T21:00:05Z"); // while in flight

        // stale pre-command data says ON, the command turned the side OFF:
        // only a correct LWW resolution keeps OFF.
        Boolean resolved = BedSideHandler.resolveLatest(
                true /* stale pre-command data */, pollStarted, new CommandedValue(commandAt, false));
        assertFalse("commanded OFF must survive the in-flight poll", resolved);
    }

    /** A genuinely newer observation still beats an older command. */
    @Test
    public void freshObservationAfterCommandWins() {
        Instant commandAt = Instant.parse("2026-08-22T21:00:00Z");
        Instant pollStartedAfter = Instant.parse("2026-08-22T21:00:30Z");

        // the command said ON, but the server now reports OFF (e.g. app-side change):
        // only a correct LWW resolution follows the fresh poll.
        Boolean resolved = BedSideHandler.resolveLatest(
                false /* server now reflects app-side change */, pollStartedAfter,
                new CommandedValue(commandAt, true));
        assertFalse(resolved);
    }
}
