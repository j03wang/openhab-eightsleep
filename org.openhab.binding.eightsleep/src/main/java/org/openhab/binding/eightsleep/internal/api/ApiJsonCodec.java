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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

/**
 * JSON codec for Eight Sleep API payloads.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class ApiJsonCodec {

    private final Gson gson;

    /**
     * Creates a codec using the binding's lenient Gson configuration.
     */
    public ApiJsonCodec() {
        this(new GsonBuilder().setStrictness(Strictness.LENIENT).create());
    }

    ApiJsonCodec(Gson gson) {
        this.gson = gson;
    }

    /**
     * Deserializes one API payload.
     *
     * @param json the JSON payload
     * @param type the target contract type
     * @return the decoded value, or {@code null} for a blank or JSON-null payload
     */
    public @Nullable <T> T fromJson(String json, Class<T> type) {
        return json.isBlank() ? null : gson.fromJson(json, type);
    }

    /**
     * Serializes one API request value.
     *
     * @param value the request value, or {@code null}
     * @return the JSON payload, or {@code null} when no value was supplied
     */
    public @Nullable String toJson(@Nullable Object value) {
        return value != null ? gson.toJson(value) : null;
    }
}
