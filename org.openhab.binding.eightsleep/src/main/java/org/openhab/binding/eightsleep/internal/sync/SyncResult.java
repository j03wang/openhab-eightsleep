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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Immutable decisions produced by a bed-side synchronization cycle.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public record SyncResult(List<ChannelUpdate> updates, StatusAction statusAction, boolean targetLevelAbsent,
        @Nullable Double lastKnownTargetLevel, boolean retireSidePowerCommand, boolean retireAwayModeCommand,
        @Nullable String retireAlarmId) {

    public SyncResult {
        updates = List.copyOf(updates);
    }

    /**
     * Thing-status action selected by synchronization.
     */
    public enum StatusAction {
        NONE,
        BRIDGE_OFFLINE,
        USER_NOT_FOUND,
        STALE_DATA,
        ONLINE
    }
}
