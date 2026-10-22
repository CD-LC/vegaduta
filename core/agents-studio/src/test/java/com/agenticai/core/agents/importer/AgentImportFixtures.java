package com.agenticai.core.agents.importer;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Test helper: loads the realistic export fixtures under {@code src/test/resources/agent-import}.
 */
final class AgentImportFixtures {

    static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private AgentImportFixtures() {}

    static String text(String name) {
        try (InputStream in =
                AgentImportFixtures.class.getResourceAsStream("/agent-import/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(String name) {
        return parse(text(name));
    }

    static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The whole report flattened to one lowercase string, for "this text must not appear anywhere"
     * checks.
     */
    static String flat(ConversionReport report) {
        return report.toString().toLowerCase();
    }

    static boolean hasNeedsManual(ConversionReport report, String whatContains) {
        return report.needsManual().stream().anyMatch(i -> i.what().contains(whatContains));
    }

    static boolean hasConverted(ConversionReport report, String whatContains) {
        return report.converted().stream().anyMatch(i -> i.what().contains(whatContains));
    }

    static boolean hasUnsupported(ConversionReport report, String whatContains) {
        return report.unsupported().stream().anyMatch(i -> i.what().contains(whatContains));
    }
}
