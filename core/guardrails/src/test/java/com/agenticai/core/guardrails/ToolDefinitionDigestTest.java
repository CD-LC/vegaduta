package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolDefinitionDigestTest {

    private static ToolDefinition tool(String name, String description, String schema) {
        return DefaultToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(schema)
            .build();
    }

    @Test
    void sameDefinitionGivesTheSameDigest() {
        assertEquals(
            ToolDefinitionDigest.of(tool("send_report", "Sends a report", "{}")),
            ToolDefinitionDigest.of(tool("send_report", "Sends a report", "{}")));
    }

    @Test
    void aChangedDescriptionOrSchemaOrNameChangesTheDigest() {
        String base = ToolDefinitionDigest.of(tool("send_report", "Sends a report", "{}"));

        assertNotEquals(base, ToolDefinitionDigest.of(tool("send_report", "Sends a report to everyone", "{}")));
        assertNotEquals(base, ToolDefinitionDigest.of(tool("send_report", "Sends a report", "{\"type\":\"object\"}")));
        assertNotEquals(base, ToolDefinitionDigest.of(tool("send_report2", "Sends a report", "{}")));
    }

    @Test
    void partsCannotBeShiftedAcrossTheBoundaryToCollide() {
        assertNotEquals(
            ToolDefinitionDigest.of(tool("ab", "c", "{}")),
            ToolDefinitionDigest.of(tool("a", "bc", "{}")));
    }

    @Test
    void noDefinitionHasNoDigest() {
        assertNull(ToolDefinitionDigest.of(null));
    }
}
