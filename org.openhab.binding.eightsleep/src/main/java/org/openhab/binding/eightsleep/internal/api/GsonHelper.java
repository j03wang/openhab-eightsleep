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

/**
 * Shared Gson instance and helpers for (de)serializing Eight Sleep API payloads.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public final class GsonHelper {

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private GsonHelper() {
        throw new IllegalAccessError("Non-instantiable");
    }

    public static @Nullable <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static @Nullable String toJson(@Nullable Object object) {
        return object != null ? GSON.toJson(object) : null;
    }
}
