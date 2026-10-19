package com.agenticai.core.agents.tools.mcpregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Rolling-deploy compatibility of the cross-replica command JSON: a control command must not carry
 * a {@code payload} property, because a replica on the previous build does not know the field,
 * rejects the whole command and drops it (a lost DISCONNECT keeps a disabled server callable
 * there).
 */
class McpCommandJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void controlCommandOmitsThePayloadProperty() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode json =
                mapper.readTree(
                        mapper.writeValueAsString(
                                new McpCommand(McpCommand.Op.DISCONNECT, "tenant_a", id, null)));

        assertFalse(json.has("payload"), json.toString());
        assertFalse(json.has("replyKey"), json.toString());
        assertEquals("DISCONNECT", json.get("op").asText());
        assertEquals(id.toString(), json.get("registrationId").asText());
    }

    @Test
    void commandFromAPreviousBuildStillParses() throws Exception {
        UUID id = UUID.randomUUID();
        McpCommand parsed =
                mapper.readValue(
                        "{\"op\":\"TEST\",\"tenant\":\"tenant_a\",\"registrationId\":\""
                                + id
                                + "\",\"replyKey\":\"r1\"}",
                        McpCommand.class);

        assertEquals(McpCommand.Op.TEST, parsed.op());
        assertEquals("r1", parsed.replyKey());
        assertNull(parsed.payload());
    }

    @Test
    void callCommandRoundTripsItsPayload() throws Exception {
        McpCommand call =
                new McpCommand(
                        McpCommand.Op.CALL,
                        "tenant_a",
                        UUID.randomUUID(),
                        "r2",
                        "{\"tool\":\"t\"}");
        assertEquals(call, mapper.readValue(mapper.writeValueAsString(call), McpCommand.class));
    }
}
