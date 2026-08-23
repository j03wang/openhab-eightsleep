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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Guards against the "channel silently dropped" bug class: an updateState call
 * whose first argument does not carry its group prefix, or names a channel id
 * that no group declares. openHAB silently ignores updates to UIDs that don't
 * exist on the thing, so such mistakes produce permanently NULL channels with
 * zero errors - this happened twice during development.
 *
 * The test parses the real thing-types.xml plus the handler sources, so it
 * fails at build time whenever a new publish drifts out of declaration.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class ChannelUidAuditTest {

    private static final Path MODULE_DIR = Path.of(".");
    private static final Path THING_XML = MODULE_DIR.resolve("src/main/resources/OH-INF/thing/thing-types.xml");
    private static final Path CONSTANTS = MODULE_DIR
            .resolve("src/main/java/org/openhab/binding/eightsleep/internal/EightSleepBindingConstants.java");
    private static final Path HANDLER_DIR = MODULE_DIR
            .resolve("src/main/java/org/openhab/binding/eightsleep/internal/handler");

    /** group#channel UIDs declared on the bedSide thing type. */
    private static Set<String> declaredUids(String xml) {
        Map<String, String> groupTypeChannels = new HashMap<>();
        Matcher gtm = Pattern.compile(
                "<channel-group-type id=\"(\\w+)\">(.*?)</channel-group-type>", Pattern.DOTALL)
                .matcher(xml);
        while (gtm.find()) {
            Set<String> ids = new LinkedHashSet<>();
            Matcher cm = Pattern.compile("<channel id=\"(\\w+)\"").matcher(gtm.group(2));
            while (cm.find()) {
                ids.add(cm.group(1));
            }
            groupTypeChannels.put(gtm.group(1), String.join(",", ids));
        }

        Set<String> uids = new HashSet<>();
        Matcher cgm = Pattern.compile("<channel-group id=\"(\\w+)\" typeId=\"(\\w+)\"").matcher(xml);
        while (cgm.find()) {
            String groupId = cgm.group(1);
            String[] channels = groupTypeChannels.getOrDefault(cgm.group(2), "").split(",");
            for (String ch : channels) {
                if (!ch.isBlank()) {
                    uids.add(groupId + "#" + ch);
                }
            }
        }
        return uids;
    }

    /** GROUP_/CHANNEL_ string constants defined in the binding constants class. */
    private static Map<String, String> constants() throws IOException {
        Map<String, String> map = new HashMap<>();
        Matcher m = Pattern.compile("(?:GROUP|CHANNEL)_([A-Z_]+) = \"(\\w+)\"")
                .matcher(Files.readString(CONSTANTS, StandardCharsets.UTF_8));
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    /** All java files under the internal/handler package. */
    private Stream<Path> handlerSources() throws IOException {
        try (Stream<Path> stream = Files.walk(HANDLER_DIR)) {
            return stream.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList()).stream();
        }
    }

    /**
     * Every updateState call in the handlers must reference a declared
     * {@code group#channel} UID. Catches both failure modes seen in practice:
     * missing group prefix, and a channel id that differs from the XML.
     */
    @Test
    public void everyPublishedChannelUidIsDeclared() throws IOException {
        Set<String> declared = declaredUids(Files.readString(THING_XML, StandardCharsets.UTF_8));
        assertTrue("no channels parsed from thing-types.xml - parser or path broken",
                declared.size() > 20);

        Map<String, String> constValues = constants();
        StringBuilder problems = new StringBuilder();
        int checked = 0;

        try (Stream<Path> files = handlerSources()) {
            for (Path file : files.toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // 1) direct updateState(GROUP_x#CHANNEL_y, ...) calls
                Matcher m = Pattern.compile(
                        "updateState\\(\\s*((?:GROUP|CHANNEL)_\\w+(?:\\s*\\+\\s*\"#\"\\s*\\+\\s*(?:CHANNEL)_\\w+)?)",
                        Pattern.DOTALL).matcher(source);

                while (m.find()) {
                    checked++;
                    auditExpression(file, m.group(1), declared, constValues, problems);
                }

                // 2) helper-mediated publishes: putDecimal/putDuration(group, channel, ...)
                Matcher twoArg = Pattern.compile(
                        "(?:putDecimal|putDuration)\\(\\s*(GROUP_\\w+)\\s*,\\s*(CHANNEL_\\w+)",
                        Pattern.DOTALL).matcher(source);
                while (twoArg.find()) {
                    checked++;
                    auditUid(file, twoArg.group(1), twoArg.group(2), declared, constValues, problems);
                }

                // 3) putLatest/putLatestCelsius(session, "series", group, channel)
                Matcher fourArg = Pattern.compile(
                        "putLatest(?:Celsius)?\\(\\w+\\s*,\\s*\"[^\"]*\"\\s*,\\s*(GROUP_\\w+)\\s*,\\s*(CHANNEL_\\w+)",
                        Pattern.DOTALL).matcher(source);
                while (fourArg.find()) {
                    checked++;
                    auditUid(file, fourArg.group(1), fourArg.group(2), declared, constValues, problems);
                }
            }
        }

        assertTrue("audited fewer than 10 updateState calls - scanner broken?", checked >= 10);
        if (problems.length() > 0) {
            fail("Channel UID mismatches:\n" + problems);
        }
    }

    /** Audits a raw updateState expression (GROUP_x + "#" + CHANNEL_y or bare CHANNEL_y). */
    private static void auditUid(Path file, String groupConstRaw, String chanConstRaw, Set<String> declared,
            Map<String, String> constValues, StringBuilder problems) {
        String group = constValues.get(groupConstRaw.replace("GROUP_", ""));
        String chan = constValues.get(chanConstRaw.replace("CHANNEL_", ""));
        if (group == null || chan == null) {
            problems.append(file.getFileName()).append(": unknown constant in ")
                    .append(groupConstRaw).append('/').append(chanConstRaw).append('\n');
            return;
        }
        String uid = group + "#" + chan;
        if (!declared.contains(uid)) {
            problems.append(file.getFileName()).append(": published UID '").append(uid)
                    .append("' is NOT declared in thing-types.xml\n");
        }
    }

    private static void auditExpression(Path file, String rawExpression, Set<String> declared,
            Map<String, String> constValues, StringBuilder problems) {
        String expression = rawExpression.replaceAll("\\s+", "");
        String[] parts = expression.split("\\+|\"#\"|\"");
        List<String> tokens = new java.util.ArrayList<>();
        for (String part : parts) {
            String token = part.replace("\"", "").trim();
            if (!token.isEmpty() && !token.equals("#")) {
                tokens.add(token);
            }
        }

        if (tokens.size() == 1 && tokens.get(0).startsWith("CHANNEL_")) {
            // unprefixed write: the exact silent-drop bug
            problems.append(file.getFileName()).append(": UNPREFIXED updateState(")
                    .append(tokens.get(0)).append(")\n");
            return;
        }
        if (tokens.size() == 2) {
            auditUid(file, tokens.get(0), tokens.get(1), declared, constValues, problems);
            return;
        }
        problems.append(file.getFileName()).append(": unparseable expression ")
                .append(expression).append('\n');
    }

    /**
     * The XML must stay in sync with the constants class: every channel declared
     * on the thing type needs a matching CHANNEL_ constant (and vice versa for
     * groups), otherwise the audit above cannot reason about new publishes.
     */
    @Test
    public void xmlChannelsHaveMatchingConstants() throws IOException {
        String xml = Files.readString(THING_XML, StandardCharsets.UTF_8);
        Map<String, String> constValues = constants();

        Set<String> xmlChannelIds = new HashSet<>();
        Matcher cm = Pattern.compile("<channel-group-type id=\"(\\w+)\">(.*?)</channel-group-type>",
                Pattern.DOTALL).matcher(xml);
        while (cm.find()) {
            Matcher ids = Pattern.compile("<channel id=\"(\\w+)\"").matcher(cm.group(2));
            while (ids.find()) {
                xmlChannelIds.add(ids.group(1));
            }
        }

        Set<String> constantIds = new HashSet<>(constValues.values());
        Set<String> missing = new HashSet<>(xmlChannelIds);
        missing.removeAll(constantIds);

        assertTrue("XML channels without a CHANNEL_ constant: " + missing,
                constantIds.containsAll(xmlChannelIds));
    }
}
