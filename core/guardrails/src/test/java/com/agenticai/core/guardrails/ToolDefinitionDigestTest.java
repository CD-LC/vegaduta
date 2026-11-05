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

        assertNotEquals(
                base,
                ToolDefinitionDigest.of(tool("send_report", "Sends a report to everyone", "{}")));
        assertNotEquals(
                base,
                ToolDefinitionDigest.of(
                        tool("send_report", "Sends a report", "{\"type\":\"object\"}")));
        assertNotEquals(
                base, ToolDefinitionDigest.of(tool("send_report2", "Sends a report", "{}")));
    }

    @Test
    void partsCannotBeShiftedAcrossTheBoundaryToCollide() {
        assertNotEquals(
                ToolDefinitionDigest.of(tool("ab", "c", "{}")),
                ToolDefinitionDigest.of(tool("a", "bc", "{}")));
    }

    @Test
    void theServerBehindAToolIsPartOfItsIdentity() {
        ToolDefinition def = tool("send_report", "Sends a report", "{}");
        String plain = ToolDefinitionDigest.of(def);
        String a =
                ToolDefinitionDigest.of(
                        def, ToolDefinitionDigest.provenance("srv", "https://mcp.example.com/sse"));
        String b =
                ToolDefinitionDigest.of(
                        def,
                        ToolDefinitionDigest.provenance("srv", "https://other.example.com/sse"));

        assertNotEquals(plain, a);
        assertNotEquals(a, b);
        // No provenance: exactly the legacy digest, so already-recorded digests stay valid.
        assertEquals(plain, ToolDefinitionDigest.of(def, null));
    }

    @Test
    void provenanceNormalisesTheEndpointAndNeverCarriesCredentials() {
        String p =
                ToolDefinitionDigest.provenance(
                        "srv", "HTTPS://user:secret@MCP.Example.com:8443/sse?token=abc#x");
        assertEquals("srv@https://mcp.example.com:8443/sse", p);
        assertEquals(
                "srv@npx", ToolDefinitionDigest.provenance("srv", "npx -y pkg --api-key=SECRET"));
        assertNull(ToolDefinitionDigest.provenance(" ", null));
    }

    @Test
    void aCallbackExposingProvenanceIsDigestedWithIt() {
        ToolDefinition def = tool("t", "d", "{}");
        class Cb
                implements org.springframework.ai.tool.ToolCallback,
                        ToolDefinitionDigest.Provenance {
            public ToolDefinition getToolDefinition() {
                return def;
            }

            public String call(String in) {
                return "";
            }

            public String toolProvenance() {
                return ToolDefinitionDigest.provenance("srv", "https://a.example.com");
            }
        }
        assertEquals(
                ToolDefinitionDigest.of(
                        def, ToolDefinitionDigest.provenance("srv", "https://a.example.com")),
                ToolDefinitionDigest.ofCallback(new Cb()));
        assertNotEquals(ToolDefinitionDigest.of(def), ToolDefinitionDigest.ofCallback(new Cb()));
    }

    @Test
    void noDefinitionHasNoDigest() {
        assertNull(ToolDefinitionDigest.of(null));
    }
}
