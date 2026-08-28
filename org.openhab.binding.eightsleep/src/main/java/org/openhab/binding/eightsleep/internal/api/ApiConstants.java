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
package org.openhab.binding.eightsleep.internal.api;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Constants and endpoint URLs for the Eight Sleep cloud APIs.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class ApiConstants {

    private ApiConstants() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public static final String CLIENT_API_URL = "https://client-api.8slp.net/v1";
    public static final String APP_API_URL = "https://app-api.8slp.net/";
    public static final String AUTH_URL = "https://auth-api.8slp.net/v1/tokens";

    /** Client credentials used by the official mobile app. */
    public static final String KNOWN_CLIENT_ID = "0894c7f33bb94800a03f1f4df13a4f38";
    public static final String KNOWN_CLIENT_SECRET = "f0954a3ed5763ba3d06834c73731a32f15f168f47d4f164751275def86db0c76";

    public static final int REQUEST_TIMEOUT_SECONDS = 60;

    public static final Map<String, String> DEFAULT_HEADERS = Map.of("accept", "application/json", "content-type",
            "application/json", "user-agent", "openHAB Eight Sleep Binding");
}
