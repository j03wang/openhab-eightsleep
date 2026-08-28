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
package org.openhab.binding.eightsleep.internal.polling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.eightsleep.internal.model.Alarm;

/**
 * Tests immutable views of mutable poll caches.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class UserDataSnapshotTest {

    @Test
    public void alarmListDoesNotChangeAfterCapture() {
        UserDataCache cache = new UserDataCache();
        cache.alarms.add(new Alarm("a1", LocalTime.NOON, true, null, Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                null, null, null));

        UserDataSnapshot snapshot = cache.snapshot();
        cache.alarms.clear();

        assertEquals(1, snapshot.alarms().size());
        assertTrue(cache.alarms.isEmpty());
    }
}
