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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.sync.SyncResult.StatusAction;

@NonNullByDefault
final class SyncCollector {

    final List<ChannelUpdate> updates = new ArrayList<>();
    StatusAction statusAction = StatusAction.NONE;
    boolean targetLevelAbsent;
    @Nullable
    Double lastKnownTargetLevel;
    boolean retireSidePowerCommand;
    boolean retireAwayModeCommand;
    @Nullable
    String retireAlarmId;

    SyncResult build() {
        return new SyncResult(updates, statusAction, targetLevelAbsent, lastKnownTargetLevel, retireSidePowerCommand,
                retireAwayModeCommand, retireAlarmId);
    }
}
