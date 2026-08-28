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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Parsers for date and time scalar values used by Eight Sleep domain models.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class ApiValueParser {

    private ApiValueParser() {
        throw new IllegalAccessError("Non-instantiable");
    }

    /**
     * Parses an ISO-8601 API timestamp.
     *
     * @param value the timestamp text
     * @return the parsed instant, or {@code null} for an absent or malformed value
     */
    public static @Nullable Instant parseTimestamp(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value.trim()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /**
     * Parses an {@code HH:mm:ss} API time-of-day, tolerating fractional seconds.
     *
     * @param value the time text
     * @return the parsed time, or {@code null} for an absent or malformed value
     */
    public static java.time.@Nullable LocalTime parseTimeOfDay(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return java.time.LocalTime.parse(trimmed.substring(0, Math.min(8, trimmed.length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
