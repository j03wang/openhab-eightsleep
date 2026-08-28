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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;

/**
 * Guards the OH-INF metadata against silent drift:
 * <ul>
 * <li>every {@code @text/} key referenced from Java exists in the properties file</li>
 * <li>every config parameter declared in thing-types.xml has i18n label/description keys</li>
 * <li>config parameter names in XML match the fields of the *Configuration classes
 * (a rename in one place but not the other silently breaks both translation and binding)</li>
 * </ul>
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
public class I18nConfigAuditTest {

    private static final Path MODULE_DIR = Path.of(".");
    private static final Path THING_XML = MODULE_DIR.resolve("src/main/resources/OH-INF/thing/thing-types.xml");
    private static final Path PROPERTIES = MODULE_DIR.resolve("src/main/resources/OH-INF/i18n/eightsleep.properties");
    private static final Path CONFIG_DIR = MODULE_DIR
            .resolve("src/main/java/org/openhab/binding/eightsleep/internal/config");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Set<String> propertyKeys(String properties) {
        Set<String> keys = new HashSet<>();
        for (String line : properties.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.strip().startsWith("#")) {
                keys.add(line.substring(0, eq).strip());
            }
        }
        return keys;
    }

    /** All public String/int fields of a configuration class. */
    private static Set<String> configFields(Path javaFile) throws IOException {
        Set<String> fields = new HashSet<>();
        Matcher m = Pattern.compile("public\\s+(?:String|int)\\s+(\\w+)").matcher(read(javaFile));
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    private record ConfigSection(String kind, String id, String xml) {
    }

    /** bridge-type and thing-type sections with their config-description parameters. */
    private static List<ConfigSection> configSections(String xml) {
        List<ConfigSection> sections = new ArrayList<>();
        Matcher tm = Pattern.compile("<(bridge|thing)-type id=\"(\\w+)\">(.*?)</\\1-type>", Pattern.DOTALL)
                .matcher(xml);
        while (tm.find()) {
            sections.add(new ConfigSection(tm.group(1), tm.group(2), tm.group(3)));
        }
        return sections;
    }

    @Test
    public void textKeysUsedInJavaExist() throws IOException {
        Set<String> keys = propertyKeys(read(PROPERTIES));
        assertTrue("no i18n keys parsed - path broken?", keys.size() > 10);

        StringBuilder problems = new StringBuilder();
        try (Stream<Path> files = Files.walk(MODULE_DIR.resolve("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = Pattern.compile("@text/([\\w.\\-]+)")
                        .matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!keys.contains(m.group(1))) {
                        problems.append(file.getFileName()).append(": missing key @text/").append(m.group(1))
                                .append('\n');
                    }
                }
            }
        }
        if (problems.length() > 0) {
            fail("Missing i18n keys:\n" + problems);
        }
    }

    @Test
    public void configParametersHaveI18nKeys() throws IOException {
        Set<String> keys = propertyKeys(read(PROPERTIES));
        String xml = read(THING_XML);
        StringBuilder problems = new StringBuilder();

        for (ConfigSection section : configSections(xml)) {
            Matcher pm = Pattern.compile("<parameter name=\"(\\w+)\"").matcher(section.xml());
            while (pm.find()) {
                String param = pm.group(1);
                String base = "thing-type.config.eightsleep." + section.id() + "." + param + ".";
                for (String suffix : new String[] { "label", "description" }) {
                    if (!keys.contains(base + suffix)) {
                        problems.append(base).append(suffix).append(" is missing\n");
                    }
                }
            }
        }
        if (problems.length() > 0) {
            fail("Config parameters without i18n entries:\n" + problems);
        }
    }

    /**
     * The XML parameter names must match the configuration class fields - this is
     * exactly the bug class where the side parameter was renamed to "label" in the
     * XML but not in BedSideConfiguration (or vice versa).
     */
    @Test
    public void xmlParametersMatchConfigurationClasses() throws IOException {
        Map<String, Set<String>> expectedFields = new HashMap<>();
        try (Stream<Path> files = Files.walk(CONFIG_DIR)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Configuration.java")).toList()) {
                String name = file.getFileName().toString().replace("Configuration.java", "").toLowerCase();
                expectedFields.put(name.equals("account") || name.equals("bedside") ? name : name, configFields(file));
            }
        }
        // XML ids vs class name prefixes: account -> AccountConfiguration, bedSide -> BedSideConfiguration
        Map<String, Set<String>> byXmlId = new HashMap<>();
        byXmlId.put("account", expectedFields.getOrDefault("account", Set.of()));
        byXmlId.put("bedSide", expectedFields.getOrDefault("bedside", Set.of()));
        assertTrue("configuration classes not discovered", expectedFields.size() >= 2);

        String xml = read(THING_XML);
        StringBuilder problems = new StringBuilder();
        for (ConfigSection section : configSections(xml)) {
            Set<String> fields = byXmlId.get(section.id());
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            Matcher pm = Pattern.compile("<parameter name=\"(\\w+)\"").matcher(section.xml());
            while (pm.find()) {
                String param = pm.group(1);
                if (!fields.contains(param)) {
                    problems.append(section.kind() + " '" + section.id() + "': XML parameter '" + param
                            + "' has no field in the Configuration class (fields: " + fields + ")\n");
                }
            }
        }
        if (problems.length() > 0) {
            fail("XML/configuration drift:\n" + problems);
        }
    }
}
