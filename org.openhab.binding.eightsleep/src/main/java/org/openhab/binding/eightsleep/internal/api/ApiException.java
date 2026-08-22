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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Exception thrown for Eight Sleep API failures (authentication, network, unexpected responses).
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean unauthorized;
    private final boolean subscriptionRequired;

    public ApiException(String message) {
        super(message);
        this.unauthorized = false;
        this.subscriptionRequired = false;
    }

    public ApiException(String message, @Nullable Throwable cause) {
        super(message, cause);
        this.unauthorized = isUnauthorizedCause(cause);
        this.subscriptionRequired = isSubscriptionRequiredCause(cause);
    }

    public ApiException(String message, boolean unauthorized) {
        super(message);
        this.unauthorized = unauthorized;
        this.subscriptionRequired = false;
    }

    public ApiException(String message, boolean unauthorized, boolean subscriptionRequired) {
        super(message);
        this.unauthorized = unauthorized;
        this.subscriptionRequired = subscriptionRequired;
    }

    public boolean isUnauthorized() {
        return unauthorized;
    }

    /**
     * True for the 403 "subscription required" response the alarms API gives to
     * accounts without an active subscription. Must degrade gracefully.
     */
    public boolean isSubscriptionRequired() {
        return subscriptionRequired;
    }

    private static boolean isUnauthorizedCause(@Nullable Throwable cause) {
        while (cause != null) {
            if (cause instanceof ApiException apiEx && apiEx.isUnauthorized()) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isSubscriptionRequiredCause(@Nullable Throwable cause) {
        while (cause != null) {
            if (cause instanceof ApiException apiEx && apiEx.isSubscriptionRequired()) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
