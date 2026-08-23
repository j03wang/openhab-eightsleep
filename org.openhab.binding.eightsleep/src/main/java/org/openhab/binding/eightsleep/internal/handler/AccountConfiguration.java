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
 * Configuration for the {@code account} bridge thing.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class AccountConfiguration {

    /** Eight Sleep account email address. */
    public String username = "";

    /** Eight Sleep account password. */
    public String password = "";

    /**
     * Optional OAuth client id override. When empty the client id of the official mobile app is used.
     */
    public String clientId = "";

    /**
     * Optional OAuth client secret override. When empty the client secret of the official mobile app is used.
     */
    public String clientSecret = "";

    /** Polling interval of the device data in seconds. */
    public int deviceRefreshInterval = 60;

    /** Polling interval of the user (sleep) data in seconds. */
    public int userRefreshInterval = 30;

    /** Polling interval of the adjustable base data in seconds. */
    public int baseRefreshInterval = 60;

    /** Temperature unit used when reporting temperatures: "C" or "F". Defaults to the openHAB locale unit. */
    public String temperatureUnit = "";

    /**
     * Eight Sleep device (pod) to bind. Empty means the first device reported by the account; accounts with
     * more than one pod should set this explicitly.
     */
    public String deviceId = "";
}
