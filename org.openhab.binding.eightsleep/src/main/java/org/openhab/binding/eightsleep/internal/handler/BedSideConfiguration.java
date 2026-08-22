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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for the {@code bedSide} thing.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class BedSideConfiguration {

    /**
     * Eight Sleep user id of the sleeper on this bed side. When left empty the binding assigns the first
     * user reported by the account during discovery.
     */
    public String userId = "";

    /** Label used in channel names, e.g. "Left". */
    public String label = "";
}
