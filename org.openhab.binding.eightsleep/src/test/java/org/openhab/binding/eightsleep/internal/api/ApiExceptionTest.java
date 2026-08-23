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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Flag semantics of {@link ApiException}: unauthorized and subscription-required
 * drive the binding's graceful-degradation decisions, so they must be exact.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ApiExceptionTest {

    @Test
    public void plainConstructorSetsNoFlags() {
        ApiException e = new ApiException("HTTP 500 boom");
        assertFalse(e.isUnauthorized());
        assertFalse(e.isSubscriptionRequired());
        assertEquals("HTTP 500 boom", e.getMessage());
    }

    @Test
    public void unauthorizedFlag() {
        assertTrue(new ApiException("HTTP 401", true, false).isUnauthorized());
        assertFalse(new ApiException("HTTP 401", true, false).isSubscriptionRequired());
        assertFalse(new ApiException("HTTP 403", false, false).isUnauthorized());
    }

    @Test
    public void subscriptionFlag() {
        assertTrue(new ApiException("HTTP 403 subscription required", false, true).isSubscriptionRequired());
        assertFalse(new ApiException("HTTP 403 subscription required", false, true).isUnauthorized());
    }

    @Test
    public void causeChainPropagatesFlags() {
        ApiException inner = new ApiException("wrapped", true, true);
        ApiException outer = new ApiException("outer", new RuntimeException(inner));
        assertTrue("unauthorized must survive wrapping", outer.isUnauthorized());
        assertTrue("subscription flag must survive wrapping", outer.isSubscriptionRequired());

        ApiException benignOuter = new ApiException("outer2",
                new RuntimeException(new ApiException("inner-benign")));
        assertFalse(benignOuter.isUnauthorized());
        assertFalse(benignOuter.isSubscriptionRequired());
    }

    @Test
    public void nullCauseIsTolerated() {
        ApiException e = new ApiException("msg", (Throwable) null);
        assertFalse(e.isUnauthorized());
        assertFalse(e.isSubscriptionRequired());
    }

    /** Sanity for the static helper used when building error messages. */
    @Test
    public void messageTruncationHelper() {
        String longBody = "x".repeat(600);
        String truncated = ApiHttpClient.truncate(longBody);
        assertEquals(503, truncated.length());
        assertTrue(truncated.endsWith("..."));
        assertEquals("", ApiHttpClient.truncate(null));
        assertEquals("short", ApiHttpClient.truncate("short"));
    }

    // ==================== urlEncode ====================

    /**
     * URL encoding must be form-style (space -> "+", reserved chars percent-encoded),
     * matching {@code URLEncoder} semantics used for userIds/deviceIds/timezones.
     */
    @Test
    public void urlEncodingIsFormStyle() {
        assertEquals("u_abc-123", ApiHttpClient.urlEncode("u_abc-123"));
        assertEquals("a+b+c", ApiHttpClient.urlEncode("a b c"));
        assertEquals("a%2Bb", ApiHttpClient.urlEncode("a+b"));
        assertEquals("%2Fusers%2Fme", ApiHttpClient.urlEncode("/users/me"));
        assertEquals("%C3%A9", ApiHttpClient.urlEncode("\u00e9"));
        assertEquals("", ApiHttpClient.urlEncode(""));
    }
}
