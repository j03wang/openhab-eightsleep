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
package org.openhab.binding.eightsleep.internal;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Assume;
import org.junit.Test;

/**
 * Guards fixture wiring in both directions: every capture written by
 * {@code tools/capture_fixtures.py} must be consumed by a contract test, and
 * every fixture a test looks up must exist on disk. A name drifts once here
 * already (device-data.json vs devices-id), which silently disabled the live
 * device-payload regression.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class FixtureAlignmentAuditTest {

    private static final Path MODULE_DIR = Path.of(".").toAbsolutePath().normalize();
    private static final Path FIXTURE_DIR = MODULE_DIR.getParent() != null
            ? MODULE_DIR.getParent().resolve("tools").resolve("fixtures") : null;
    private static final Path CONTRACT_TEST = MODULE_DIR
            .resolve("src/test/java/org/openhab/binding/eightsleep/internal/api/EndpointContractTest.java");

    /** Fixtures that are reference material only (auth response shape). */
    private static final Set<String> REFERENCE_ONLY = Set.of("auth-tokens");

    /**
     * A stem belongs to a consumed base name when it IS the base or starts with
     * {@code <base>-} (multi-user captures append a "-<uid suffix>"). Precise
     * prefix matching keeps the unconsumed-fixture check meaningful - a loose
     * "contains a dash" heuristic silently whitelisted every real fixture.
     */
    private static boolean matchesConsumedBase(String stem, Set<String> consumed) {
        for (String base : consumed) {
            if (stem.equals(base) || stem.startsWith(base + "-")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void capturedFixturesMatchContractLookups() throws IOException {
        Assume.assumeTrue("tools/fixtures not present (standalone module build?)",
                FIXTURE_DIR != null && Files.isDirectory(FIXTURE_DIR));
        Assume.assumeTrue("EndpointContractTest source not found", Files.exists(CONTRACT_TEST));

        // names the tests actually look up: fixture("name")
        Set<String> consumed = new HashSet<>();
        Matcher m = Pattern.compile("fixture\\(\"([a-z0-9-]+)\"")
                .matcher(Files.readString(CONTRACT_TEST, StandardCharsets.UTF_8));
        while (m.find()) {
            consumed.add(m.group(1));
        }
        assertTrue("no fixture lookups parsed - scanner or path broken", consumed.size() >= 5);

        // also accept the isLive(...) guards as consumption of the same name set
        StringBuilder problems = new StringBuilder();

        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String stem = file.getFileName().toString().replace(".json", "");
                if (consumed.contains(stem) || REFERENCE_ONLY.contains(stem)
                        || matchesConsumedBase(stem, consumed)) {
                    continue;
                }
                problems.append("captured fixture '").append(stem)
                        .append("' is not consumed by any contract test\n");
            }
        }

        for (String name : consumed) {
            if (!REFERENCE_ONLY.contains(name) && !Files.exists(FIXTURE_DIR.resolve(name + ".json"))) {
                problems.append("contract test looks up '").append(name)
                        .append("' but tools/fixtures/ has no such capture\n");
            }
        }

        assertTrue("fixture alignment drift:\n" + problems, problems.length() == 0);
    }
}
