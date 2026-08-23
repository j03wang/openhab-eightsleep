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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;

/**
 * Null-handling contracts of the shared Gson facade that parsers rely on:
 * toJson(null) yields null (never "null") and blank bodies deserialize to null.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class GsonHelperTest {

    private static class Payload {
        public @Nullable String value;
    }

    @Test
    public void nullObjectSerializesToNull() {
        assertNull(GsonHelper.toJson(null));
    }

    /** Round-trip of a simple payload. */
    @Test
    public void payloadRoundTrip() {
        Payload payload = new Payload();
        payload.value = "x";
        String json = GsonHelper.toJson(payload);
        assertEquals("{\"value\":\"x\"}", json);
        Payload parsed = GsonHelper.fromJson(json, Payload.class);
        assertEquals("x", parsed.value);
    }

    /** Empty and whitespace bodies deserialize to null instead of throwing. */
    @Test
    public void emptyBodyDeserializesToNull() {
        assertNull(GsonHelper.fromJson("", Payload.class));
        assertNull(GsonHelper.fromJson("   ", Payload.class));
    }
}
